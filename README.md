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

By default script ab **ultra-realistic settings** pe chalta hai: `ultra_mode: true` (higher-fidelity geometry), `enable_pbr: true`, `texture_resolution: 4k`, `should_remesh: true`. Isse zyada details, sharp textures, aur realistic material response milta hai.

## Options

```bash
npm run generate -- --no-rig                # sirf model generate karo, rig mat karo
npm run generate -- --no-ultra               # ultra_mode geometry off (faster/cheaper, less detail)
npm run generate -- --pose t-pose            # a-pose (default) ya t-pose
npm run generate -- --height 1.75            # character height in meters, rigging accuracy ke liye
npm run generate -- --model-type lowpoly     # standard (default) | smart-topology | lowpoly
npm run generate -- --texture-resolution 8k  # 2k | 4k (default) | 8k — jitna zyada utna realistic, lekin zyada credits/time
npm run generate -- --ai-model meshy-7       # ai_model override (default: "latest")
npm run generate -- --topology quad          # triangle (default) | quad
npm run generate -- --polycount 100000       # target face count (remesh phase)
```

## Notes

- Auto-rigging sirf **standard humanoid (bipedal)** characters ke liye achhe se kaam karta hai — Meshy ki apni limitation hai.
- Har image sequentially process hoti hai (Meshy account ke concurrent-task limits se bachne ke liye). Bahut saare images ho toh script ko chalta chhod do, ye apne aap loop karega aur end mein pass/fail summary dega.
- `.env` kabhi commit mat karna — wo already `.gitignore` mein hai.
- Agar rigging response ka format kabhi change ho jaaye, to script `output/<name>/rigging_raw_response.json` mein raw response dump kar dega taaki debug ho sake.
- `--texture-resolution 8k` aur `--ultra` dono zyada Meshy credits use karte hain — agar credits limited hain toh pehle 1-2 test character generate karke check kar lena.

## Cloth / cape "wave" effect in Godot

Meshy ka auto-rig sirf ek standard bipedal skeleton banata hai — cape, coat,
sash, ya hair ke liye alag physics bones nahi deta. Isliye cloth "wave" ka
effect Godot mein add karna padta hai. Do options di gayi hain, `godot/` folder mein:

### Option A — Wind-sway shader (recommended, works turant)

`godot/shaders/cloth_wave.gdshader` — koi extra rigging nahi chahiye, kisi
bhi mesh pe kaam karta hai:

1. Rigged GLB ko Godot mein import karo.
2. Scene tree mein cape/cloth wale mesh surface ko select karo (jo
   `MeshInstance3D` hai), uske Surface Material Override slot mein jao.
3. Naya `ShaderMaterial` banao, shader field mein `cloth_wave.gdshader` daalo.
4. `albedo_texture` uniform mein character ka base color texture daalo
   (agar `normal_texture` / `orm_texture` bhi hai to `use_normal_map` /
   `use_orm_map` on kar ke unhe bhi assign karo — ultra-realistic PBR look
   ke liye).
5. `pivot_y` aur `sway_range` tune karo taaki cape ka upar wala hissa
   (shoulders) fixed rahe aur neeche wala hissa (hem/tail) hawa mein wave kare.
6. `wind_direction`, `wind_speed`, `wind_strength` se wind ka feel adjust karo.

### Option B — Bone-based jiggle (advanced, agar manually cape bones add karo)

Agar future mein Blender mein cape/hair ke liye extra bones manually rig
karoge, `godot/scripts/spring_bone_chain.gd` use karo — ye ek simple
spring-physics jiggle simulate karta hai us bone chain pe (`root_bone` set
karke). Script ke top comments mein poora setup likha hai.
