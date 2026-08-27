# JJK Game — Image → 3D Model → Rig → GLB Pipeline

Ye script tere character reference images leta hai, [Meshy AI](https://www.meshy.ai)
ke API se unka 3D model banata hai, humanoid characters ko auto-rig karta hai
(skeleton + basic walk/run animations), aur sab kuch `.glb` format mein
`output/` folder mein save kar deta hai — seedha Unity/Unreal/Godot/Three.js
mein import karne ke liye ready.

## Setup

1. Node.js 18+ install hona chahiye.
2. Apni Meshy API key lo: https://app.meshy.ai → Settings → API Access.
3. `.env.example` ko `.env` mein copy karo aur key daalo:
   ```
   cp .env.example .env
   ```
   phir `.env` mein `MESHY_API_KEY=...` set karo.
4. `images/` folder mein apne character reference images daalo (`.png`, `.jpg`, `.jpeg`, `.webp`).
   - Best result ke liye: clean background, poora character front-facing, ek image = ek character.

## Run

```bash
npm run generate
```

Har image ke liye `output/<image-name>/` banega jisme:
- `model.glb` — textured 3D model (unrigged)
- `rigged.glb` — skeleton ke saath rigged model
- `animations/*.glb` — agar Meshy ne basic walk/run animation di ho

## Options

```bash
npm run generate -- --no-rig                # sirf model generate karo, rig mat karo
npm run generate -- --pose t-pose            # a-pose (default) ya t-pose
npm run generate -- --height 1.75            # character height in meters, rigging accuracy ke liye
npm run generate -- --model-type lowpoly     # standard (default) | smart-topology | lowpoly
npm run generate -- --texture-resolution 4k  # 2k (default) | 4k | 8k
```

## Notes

- Auto-rigging sirf **standard humanoid (bipedal)** characters ke liye achhe se kaam karta hai — Meshy ki apni limitation hai.
- Har image sequentially process hoti hai (Meshy account ke concurrent-task limits se bachne ke liye). Bahut saare images ho toh script ko chalta chhod do, ye apne aap loop karega aur end mein pass/fail summary dega.
- `.env` kabhi commit mat karna — wo already `.gitignore` mein hai.
- Agar rigging response ka format kabhi change ho jaaye, to script `output/<name>/rigging_raw_response.json` mein raw response dump kar dega taaki debug ho sake.
