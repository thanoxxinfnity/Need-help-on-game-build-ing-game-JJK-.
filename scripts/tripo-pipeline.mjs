#!/usr/bin/env node
/**
 * Image -> 3D model -> auto-rig -> animate -> GLB pipeline, built on the
 * Tripo AI API (https://api.tripo3d.ai/v2/openapi).
 *
 * Usage:
 *   1. cp .env.example .env   and put your TRIPO_API_KEY in it (starts with tsk_)
 *   2. drop character reference images into ./images/
 *   3. npm run generate:tripo
 *
 * Output lands in ./output/<image-name>/ as:
 *   - model.glb             (textured, unrigged model)
 *   - rigged.glb            (rigged model, skipped with --no-rig)
 *   - animations/*.glb      (retargeted animations, skipped with --no-animations)
 */

import { readFileSync, existsSync, mkdirSync, writeFileSync, readdirSync } from "node:fs";
import { join, extname, basename } from "node:path";

const BASE_URL = "https://api.tripo3d.ai/v2/openapi";
const IMAGES_DIR = "images";
const OUTPUT_DIR = "output";
const POLL_INTERVAL_MS = 3000;
const POLL_TIMEOUT_MS = 15 * 60 * 1000; // 15 min per task
const IMAGE_EXTS = new Set([".png", ".jpg", ".jpeg", ".webp"]);
const MIME_TYPES = { ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".webp": "image/webp" };

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
  // Defaults tuned for max realism: flagship model + detailed geometry/texture.
  const args = {
    rig: true,
    animations: ["preset:walk", "preset:run"],
    modelVersion: "P1-20260311",
    textureQuality: "detailed",
    geometryQuality: "detailed",
    rigType: "biped",
    rigModelVersion: "v1.0-20240301",
    faceLimit: undefined,
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--no-rig") args.rig = false;
    else if (a === "--no-animations") args.animations = [];
    else if (a === "--animations") args.animations = argv[++i].split(",").map((s) => `preset:${s.trim()}`);
    else if (a === "--model-version") args.modelVersion = argv[++i];
    else if (a === "--texture-quality") args.textureQuality = argv[++i];
    else if (a === "--geometry-quality") args.geometryQuality = argv[++i];
    else if (a === "--rig-type") args.rigType = argv[++i];
    else if (a === "--face-limit") args.faceLimit = Number(argv[++i]);
  }
  return args;
}

function authHeaders(apiKey, json = true) {
  const headers = { Authorization: `Bearer ${apiKey}` };
  if (json) headers["Content-Type"] = "application/json";
  return headers;
}

async function unwrap(res, label) {
  const data = await res.json().catch(() => ({}));
  if (!res.ok || data.code !== 0) {
    throw new Error(`${label} failed (HTTP ${res.status}, code ${data.code}): ${data.message ?? JSON.stringify(data)}`);
  }
  return data.data;
}

async function uploadImage(imagePath, apiKey) {
  const ext = extname(imagePath).toLowerCase();
  const bytes = readFileSync(imagePath);
  const form = new FormData();
  form.append("file", new Blob([bytes], { type: MIME_TYPES[ext] ?? "image/jpeg" }), basename(imagePath));

  const res = await fetch(`${BASE_URL}/upload/sts`, {
    method: "POST",
    headers: { Authorization: `Bearer ${apiKey}` },
    body: form,
  });
  const data = await unwrap(res, "image upload");
  return data.image_token;
}

async function createTask(apiKey, body) {
  const res = await fetch(`${BASE_URL}/task`, {
    method: "POST",
    headers: authHeaders(apiKey),
    body: JSON.stringify(body),
  });
  const data = await unwrap(res, `create task (${body.type})`);
  return data.task_id;
}

async function pollTask(taskId, apiKey, label) {
  const start = Date.now();
  while (true) {
    const res = await fetch(`${BASE_URL}/task/${taskId}`, { headers: authHeaders(apiKey, false) });
    const task = await unwrap(res, `poll ${label}`);
    process.stdout.write(`\r  ${label}: ${task.status} (${task.progress ?? 0}%)   `);
    if (task.status === "success") {
      process.stdout.write("\n");
      return task;
    }
    if (["failed", "cancelled", "banned", "expired"].includes(task.status)) {
      process.stdout.write("\n");
      throw new Error(`${label} ended with status ${task.status}: ${task.error_msg ?? ""}`);
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

async function processImage(imagePath, apiKey, args) {
  const name = basename(imagePath, extname(imagePath));
  const outDir = join(OUTPUT_DIR, name);
  mkdirSync(outDir, { recursive: true });

  console.log(`\n=== ${name} ===`);
  console.log("  uploading image...");
  const imageToken = await uploadImage(imagePath, apiKey);

  console.log("  starting 3D model generation...");
  const modelTaskBody = {
    type: "image_to_model",
    file: { type: "image", file_token: imageToken },
    model_version: args.modelVersion,
    texture: true,
    pbr: true,
    texture_quality: args.textureQuality,
  };
  // geometry_quality is only accepted by older model versions; the P1
  // flagship model always generates at its own (already high) fidelity.
  if (!args.modelVersion.startsWith("P1")) {
    modelTaskBody.geometry_quality = args.geometryQuality;
  }
  if (args.faceLimit) modelTaskBody.face_limit = args.faceLimit;

  const modelTaskId = await createTask(apiKey, modelTaskBody);
  const modelTask = await pollTask(modelTaskId, apiKey, "model generation");

  const modelUrl = modelTask.output?.pbr_model ?? modelTask.output?.model;
  if (modelUrl) {
    await downloadFile(modelUrl, join(outDir, "model.glb"));
    console.log(`  saved ${join(outDir, "model.glb")}`);
  } else {
    console.log("  WARNING: no model URL in response, dumping raw output for inspection");
    writeFileSync(join(outDir, "model_raw_response.json"), JSON.stringify(modelTask, null, 2));
  }

  if (!args.rig) return;

  console.log("  starting auto-rig...");
  const rigTaskId = await createTask(apiKey, {
    type: "animate_rig",
    original_model_task_id: modelTaskId,
    model_version: args.rigModelVersion,
    out_format: "glb",
    rig_type: args.rigType,
    spec: "tripo",
  });
  const rigTask = await pollTask(rigTaskId, apiKey, "rigging");

  const riggedUrl = rigTask.output?.pbr_model ?? rigTask.output?.model;
  if (riggedUrl) {
    await downloadFile(riggedUrl, join(outDir, "rigged.glb"));
    console.log(`  saved ${join(outDir, "rigged.glb")}`);
  } else {
    console.log("  WARNING: no rigged model URL in response, dumping raw output for inspection");
    writeFileSync(join(outDir, "rigging_raw_response.json"), JSON.stringify(rigTask, null, 2));
    return;
  }

  if (!args.animations.length) return;

  const animDir = join(outDir, "animations");
  mkdirSync(animDir, { recursive: true });
  for (const animation of args.animations) {
    const label = `animation (${animation})`;
    console.log(`  retargeting ${animation}...`);
    const animTaskId = await createTask(apiKey, {
      type: "animate_retarget",
      original_model_task_id: rigTaskId,
      animation,
      out_format: "glb",
      bake_animation: true,
    });
    const animTask = await pollTask(animTaskId, apiKey, label);
    const animUrl = animTask.output?.pbr_model ?? animTask.output?.model;
    if (animUrl) {
      const fileName = `${animation.replace(/^preset:/, "").replace(/:/g, "_")}.glb`;
      await downloadFile(animUrl, join(animDir, fileName));
      console.log(`  saved ${join(animDir, fileName)}`);
    } else {
      console.log(`  WARNING: no output URL for ${animation}`);
    }
  }
}

async function main() {
  loadDotEnv();
  const apiKey = process.env.TRIPO_API_KEY;
  if (!apiKey || !apiKey.startsWith("tsk_")) {
    console.error("TRIPO_API_KEY missing or invalid (must start with 'tsk_'). Set it in .env.");
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

  console.log(
    `Found ${files.length} image(s). rig=${args.rig} model_version=${args.modelVersion} ` +
      `texture_quality=${args.textureQuality} geometry_quality=${args.geometryQuality} ` +
      `animations=${args.animations.join(",") || "none"}`
  );

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
