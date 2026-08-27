#!/usr/bin/env node
/**
 * Image -> 3D model -> auto-rig -> GLB pipeline, built on the Meshy AI API.
 *
 * Usage:
 *   1. cp .env.example .env   and put your MESHY_API_KEY in it
 *   2. drop character reference images into ./images/
 *   3. npm run generate
 *
 * Output lands in ./output/<image-name>/ as:
 *   - model.glb            (textured, unrigged model)
 *   - rigged.glb           (rigged model, skipped with --no-rig)
 *   - animations/*.glb     (any bundled basic animations, e.g. walking/running)
 */

import { readFileSync, existsSync, mkdirSync, writeFileSync, readdirSync } from "node:fs";
import { join, extname, basename } from "node:path";

const API_BASE = "https://api.meshy.ai/openapi/v1";
const IMAGES_DIR = "images";
const OUTPUT_DIR = "output";
const POLL_INTERVAL_MS = 5000;
const POLL_TIMEOUT_MS = 15 * 60 * 1000; // 15 min per task
const IMAGE_EXTS = new Set([".png", ".jpg", ".jpeg", ".webp"]);

function loadDotEnv(path = ".env") {
  if (!existsSync(path)) return;
  for (const line of readFileSync(path, "utf8").split("\n")) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) continue;
    const eq = trimmed.indexOf("=");
    if (eq === -1) continue;
    const key = trimmed.slice(0, eq).trim();
    const value = trimmed.slice(eq + 1).trim().replace(/^["']|["']$/g, "");
    if (!(key in process.env)) process.env[key] = value;
  }
}

function parseArgs(argv) {
  const args = { pose: "a-pose", height: 1.7, rig: true, modelType: "standard", textureResolution: "2k" };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--no-rig") args.rig = false;
    else if (a === "--pose") args.pose = argv[++i];
    else if (a === "--height") args.height = Number(argv[++i]);
    else if (a === "--model-type") args.modelType = argv[++i];
    else if (a === "--texture-resolution") args.textureResolution = argv[++i];
  }
  return args;
}

function apiHeaders(apiKey, json = true) {
  const headers = { Authorization: `Bearer ${apiKey}` };
  if (json) headers["Content-Type"] = "application/json";
  return headers;
}

async function apiPost(path, apiKey, body) {
  const res = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers: apiHeaders(apiKey),
    body: JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(`POST ${path} failed (${res.status}): ${JSON.stringify(data)}`);
  }
  return data;
}

async function apiGet(path, apiKey) {
  const res = await fetch(`${API_BASE}${path}`, { headers: apiHeaders(apiKey, false) });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(`GET ${path} failed (${res.status}): ${JSON.stringify(data)}`);
  }
  return data;
}

async function pollTask(path, apiKey, label) {
  const start = Date.now();
  while (true) {
    const task = await apiGet(path, apiKey);
    const status = task.status;
    const progress = task.progress ?? 0;
    process.stdout.write(`\r  ${label}: ${status} (${progress}%)   `);
    if (status === "SUCCEEDED") {
      process.stdout.write("\n");
      return task;
    }
    if (status === "FAILED" || status === "CANCELED") {
      process.stdout.write("\n");
      throw new Error(`${label} ended with status ${status}: ${JSON.stringify(task.task_error ?? task)}`);
    }
    if (Date.now() - start > POLL_TIMEOUT_MS) {
      throw new Error(`${label} timed out after ${POLL_TIMEOUT_MS / 1000}s`);
    }
    await new Promise((r) => setTimeout(r, POLL_INTERVAL_MS));
  }
}

async function downloadFile(url, destPath) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Failed to download ${url}: ${res.status}`);
  const buf = Buffer.from(await res.arrayBuffer());
  writeFileSync(destPath, buf);
}

// Walks any nested object/array and returns { "a.b.c": url } for every
// string value that looks like a downloadable .glb URL. Used for the
// animation bundle whose exact shape isn't guaranteed by the docs.
function collectGlbUrls(obj, prefix = "") {
  const found = {};
  if (!obj || typeof obj !== "object") return found;
  for (const [key, value] of Object.entries(obj)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (typeof value === "string" && /\.glb(\?|$)/i.test(value)) {
      found[path] = value;
    } else if (value && typeof value === "object") {
      Object.assign(found, collectGlbUrls(value, path));
    }
  }
  return found;
}

function imageToDataUri(filePath) {
  const ext = extname(filePath).toLowerCase();
  const mime = ext === ".png" ? "image/png" : ext === ".webp" ? "image/webp" : "image/jpeg";
  const bytes = readFileSync(filePath);
  return `data:${mime};base64,${bytes.toString("base64")}`;
}

async function processImage(imagePath, apiKey, args) {
  const name = basename(imagePath, extname(imagePath));
  const outDir = join(OUTPUT_DIR, name);
  mkdirSync(outDir, { recursive: true });

  console.log(`\n=== ${name} ===`);
  console.log("  uploading image + starting 3D generation...");

  const dataUri = imageToDataUri(imagePath);
  const createRes = await apiPost("/image-to-3d", apiKey, {
    image_url: dataUri,
    model_type: args.modelType,
    should_texture: true,
    enable_pbr: true,
    texture_resolution: args.textureResolution,
    pose_mode: args.pose,
  });
  const modelTaskId = createRes.result;
  const modelTask = await pollTask(`/image-to-3d/${modelTaskId}`, apiKey, "model generation");

  if (modelTask.model_urls?.glb) {
    await downloadFile(modelTask.model_urls.glb, join(outDir, "model.glb"));
    console.log(`  saved ${join(outDir, "model.glb")}`);
  }

  if (!args.rig) return;

  console.log("  starting auto-rig...");
  const rigCreateRes = await apiPost("/rigging", apiKey, {
    input_task_id: modelTaskId,
    height_meters: args.height,
  });
  const rigTaskId = rigCreateRes.result;
  const rigTask = await pollTask(`/rigging/${rigTaskId}`, apiKey, "rigging");

  const result = rigTask.result ?? rigTask;
  if (result.rigged_character_glb_url) {
    await downloadFile(result.rigged_character_glb_url, join(outDir, "rigged.glb"));
    console.log(`  saved ${join(outDir, "rigged.glb")}`);
  } else {
    console.log("  WARNING: no rigged_character_glb_url in response, dumping raw result for inspection");
    writeFileSync(join(outDir, "rigging_raw_response.json"), JSON.stringify(rigTask, null, 2));
  }

  if (result.basic_animations) {
    const animUrls = collectGlbUrls(result.basic_animations);
    if (Object.keys(animUrls).length) {
      const animDir = join(outDir, "animations");
      mkdirSync(animDir, { recursive: true });
      for (const [path, url] of Object.entries(animUrls)) {
        const fileName = `${path.replace(/\./g, "_")}.glb`;
        await downloadFile(url, join(animDir, fileName));
        console.log(`  saved ${join(animDir, fileName)}`);
      }
    }
  }
}

async function main() {
  loadDotEnv();
  const apiKey = process.env.MESHY_API_KEY;
  if (!apiKey || apiKey === "your_meshy_api_key_here") {
    console.error("MESHY_API_KEY missing. Copy .env.example to .env and put your key in it.");
    process.exit(1);
  }

  const args = parseArgs(process.argv.slice(2));
  mkdirSync(IMAGES_DIR, { recursive: true });
  mkdirSync(OUTPUT_DIR, { recursive: true });

  const files = readdirSync(IMAGES_DIR)
    .filter((f) => IMAGE_EXTS.has(extname(f).toLowerCase()))
    .map((f) => join(IMAGES_DIR, f));

  if (!files.length) {
    console.error(`No images found in ./${IMAGES_DIR}/. Add .png/.jpg/.jpeg/.webp files and re-run.`);
    process.exit(1);
  }

  console.log(`Found ${files.length} image(s). rig=${args.rig} pose=${args.pose} height=${args.height}m`);

  const failures = [];
  for (const file of files) {
    try {
      await processImage(file, apiKey, args);
    } catch (err) {
      console.error(`  FAILED (${basename(file)}): ${err.message}`);
      failures.push({ file, error: err.message });
    }
  }

  console.log(`\nDone. ${files.length - failures.length}/${files.length} succeeded.`);
  if (failures.length) {
    console.log("Failures:");
    for (const f of failures) console.log(`  - ${f.file}: ${f.error}`);
    process.exitCode = 1;
  }
}

main();
