extends Node
class_name SpringBoneChain

## Advanced/optional: simple spring-physics jiggle for a bone chain
## (e.g. a cape or hair chain you manually rigged in Blender, since
## Meshy's auto-rig does not create cloth bones on its own).
##
## Usage:
##   1. Add cape/hair bones to the model's skeleton in Blender and re-export.
##   2. Attach this script to a Node under the character, set `skeleton_path`
##      to the Skeleton3D, and `root_bone` to the first bone of the chain
##      (e.g. "Cape_01"). All descendant bones are picked up automatically.
##   3. Tune stiffness / damping / gravity / wind to taste.

@export var skeleton_path: NodePath
@export var root_bone: String = ""
@export_range(0.0, 1.0) var stiffness: float = 0.15
@export_range(0.0, 1.0) var damping: float = 0.85
@export var gravity_dir: Vector3 = Vector3(0.0, -1.0, 0.0)
@export_range(0.0, 2.0) var gravity_strength: float = 0.15
@export var wind_dir: Vector3 = Vector3(1.0, 0.0, 0.3)
@export_range(0.0, 2.0) var wind_strength: float = 0.2
@export_range(0.0, 10.0) var wind_speed: float = 2.0

var _skeleton: Skeleton3D
var _bones: Array[int] = []
var _rest_local: Array[Transform3D] = []
var _current_dir: Array[Vector3] = []
var _velocity: Array[Vector3] = []

func _ready() -> void:
	_skeleton = get_node_or_null(skeleton_path) as Skeleton3D
	if _skeleton == null:
		push_warning("SpringBoneChain: skeleton_path does not point to a Skeleton3D")
		return
	_build_chain(root_bone)

func _build_chain(bone_name: String) -> void:
	var root_idx := _skeleton.find_bone(bone_name)
	if root_idx == -1:
		push_warning("SpringBoneChain: bone not found: %s" % bone_name)
		return

	_bones.clear()
	_rest_local.clear()
	_current_dir.clear()
	_velocity.clear()

	var stack: Array[int] = [root_idx]
	while stack.size() > 0:
		var current: int = stack.pop_back()
		_bones.append(current)
		for child in _skeleton.get_bone_children(current):
			stack.append(child)

	for b in _bones:
		var rest: Transform3D = _skeleton.get_bone_rest(b)
		_rest_local.append(rest)
		_current_dir.append(rest.origin.normalized() if rest.origin.length() > 0.0001 else Vector3.FORWARD)
		_velocity.append(Vector3.ZERO)

func _physics_process(delta: float) -> void:
	if _skeleton == null or _bones.is_empty():
		return

	var t := Time.get_ticks_msec() / 1000.0
	var wind := wind_dir.normalized() * (sin(t * wind_speed) * 0.5 + 0.5) * wind_strength
	var gravity := gravity_dir.normalized() * gravity_strength

	for i in _bones.size():
		var rest := _rest_local[i]
		var rest_dir := rest.origin.normalized()
		if rest_dir.length() < 0.0001:
			continue

		var target_dir: Vector3 = (rest_dir + gravity + wind).normalized()

		# Spring-damper: pull current direction toward target, damp the
		# resulting velocity so it settles instead of oscillating forever.
		var force: Vector3 = (target_dir - _current_dir[i]) * stiffness
		_velocity[i] = (_velocity[i] + force) * damping
		_current_dir[i] = (_current_dir[i] + _velocity[i] * delta * 60.0).normalized()

		var axis := rest_dir.cross(_current_dir[i])
		var angle := rest_dir.angle_to(_current_dir[i])
		var rot := Basis.IDENTITY
		if axis.length() > 0.0001 and angle > 0.0001:
			rot = Basis(axis.normalized(), angle)

		var new_basis: Basis = rot * rest.basis
		_skeleton.set_bone_pose_rotation(_bones[i], new_basis.get_rotation_quaternion())
