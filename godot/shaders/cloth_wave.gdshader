// Wind-sway "cloth" shader for capes, jackets, hair, sashes, etc.
//
// Meshy's auto-rig only produces a standard bipedal skeleton (no cloth/cape
// bones), so this fakes the wave with a vertex-shader wind displacement
// instead of physics bones. Works on any mesh, no extra rigging needed.
//
// How to use in Godot:
//   1. Import the rigged GLB (Scene tab, or drag into FileSystem dock).
//   2. Select the cloth/cape mesh surface (a MeshInstance3D under the
//      imported scene) and open its Surface Material Override slot.
//   3. Create a new ShaderMaterial, assign this file as its shader.
//   4. Plug the character's base color texture into `albedo_texture`
//      (find it in the imported .glb's extracted textures, or the
//      original material Meshy generated) and, if you have them,
//      `normal_texture` / `orm_texture` for full PBR-quality shading.
//   5. Tune `pivot_y` / `sway_range` so the top of the cloth (shoulders,
//      waistband) stays still and the bottom (hem, cape tail) sways.

shader_type spatial;
render_mode blend_mix, cull_back, diffuse_burley, specular_schlick_ggx;

uniform sampler2D albedo_texture : source_color;
uniform vec4 albedo_tint : source_color = vec4(1.0, 1.0, 1.0, 1.0);

uniform bool use_normal_map = false;
uniform sampler2D normal_texture : hint_normal;
uniform float normal_strength : hint_range(0.0, 2.0) = 1.0;

uniform bool use_orm_map = false;
uniform sampler2D orm_texture; // R = AO, G = roughness, B = metallic (glTF convention)
uniform float roughness : hint_range(0.0, 1.0) = 0.85;
uniform float metallic : hint_range(0.0, 1.0) = 0.0;

// -- Wind / sway controls --
uniform vec3 wind_direction = vec3(1.0, 0.0, 0.3);
uniform float wind_speed : hint_range(0.0, 10.0) = 2.0;
uniform float wind_strength : hint_range(0.0, 2.0) = 0.15;
uniform float turbulence : hint_range(0.0, 2.0) = 0.5;

// Local-space Y where the cloth is "attached" to the body (no sway),
// and how far below that the sway reaches full strength.
uniform float pivot_y = 0.0;
uniform float sway_range : hint_range(0.01, 5.0) = 1.0;
uniform float sway_power : hint_range(0.1, 4.0) = 2.0;

void vertex() {
	vec3 wind_dir = normalize(wind_direction + vec3(0.0001)); // avoid normalize(0)

	float dist_below_pivot = clamp((pivot_y - VERTEX.y) / max(sway_range, 0.001), 0.0, 1.0);
	float mask = pow(dist_below_pivot, sway_power);

	float phase = VERTEX.x * 1.7 + VERTEX.z * 1.3;
	float wave = sin(TIME * wind_speed + phase) * 0.6
		+ sin(TIME * wind_speed * 1.9 + phase * 2.1) * 0.4 * turbulence;

	VERTEX += wind_dir * wave * wind_strength * mask;
}

void fragment() {
	vec4 tex = texture(albedo_texture, UV);
	ALBEDO = tex.rgb * albedo_tint.rgb;
	ALPHA = tex.a * albedo_tint.a;

	if (use_orm_map) {
		vec3 orm = texture(orm_texture, UV).rgb;
		AO = orm.r;
		ROUGHNESS = orm.g;
		METALLIC = orm.b;
	} else {
		ROUGHNESS = roughness;
		METALLIC = metallic;
	}

	if (use_normal_map) {
		NORMAL_MAP = texture(normal_texture, UV).rgb;
		NORMAL_MAP_DEPTH = normal_strength;
	}
}
