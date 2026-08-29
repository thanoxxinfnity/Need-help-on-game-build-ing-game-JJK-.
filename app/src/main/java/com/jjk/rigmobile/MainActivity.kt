package com.jjk.rigmobile

import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.jjk.rigmobile.databinding.ActivityMainBinding
import com.jjk.rigmobile.export.GlbExporter
import com.jjk.rigmobile.model.Mesh
import com.jjk.rigmobile.model.ModelImporter
import com.jjk.rigmobile.rig.AutoRigger
import com.jjk.rigmobile.rig.AutoSkinner
import com.jjk.rigmobile.rig.FKPose
import com.jjk.rigmobile.rig.Skeleton

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var currentMesh: Mesh? = null
    private var currentSkeleton: Skeleton? = null

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri)
    }

    private val createDocumentLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("model/gltf-binary")) { uri ->
        if (uri != null) exportTo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.jointOverlay.glView = binding.glSurfaceView
        binding.jointOverlay.onJointSelected = { name ->
            if (name != null) binding.statusText.text = "Dragging: $name"
        }
        binding.jointOverlay.onJointsChanged = {
            // Live-editing the bind skeleton invalidates any previously previewed pose.
            binding.glSurfaceView.modelRenderer.skinningEnabled = false
            if (binding.btnPreview.isChecked) binding.btnPreview.isChecked = false
        }

        binding.btnImport.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("*/*"))
        }

        binding.btnAutoRig.setOnClickListener { runAutoRig() }

        binding.btnAdjust.setOnCheckedChangeListener { _, isChecked ->
            binding.jointOverlay.jointsEditable = isChecked
            if (isChecked) {
                binding.btnPreview.isChecked = false
                binding.statusText.text = "Drag the cyan dots to reposition joints, then tap Preview Pose."
            }
        }

        binding.btnPreview.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.btnAdjust.isChecked = false
                binding.jointOverlay.jointsEditable = false
                startPosePreview()
            } else {
                binding.glSurfaceView.modelRenderer.skinningEnabled = false
                binding.glSurfaceView.requestRender()
            }
        }

        binding.btnExport.setOnClickListener {
            createDocumentLauncher.launch("rigged_model.glb")
        }
    }

    private fun importModel(uri: Uri) {
        val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "model"
        try {
            val mesh = ModelImporter.import(contentResolver, uri, displayName)
            currentMesh = mesh
            currentSkeleton = null
            binding.glSurfaceView.modelRenderer.loadMesh(mesh)
            binding.glSurfaceView.modelRenderer.showSkeleton = false
            binding.jointOverlay.skeleton = null
            binding.jointOverlay.jointsEditable = false
            binding.glSurfaceView.requestRender()

            binding.statusText.text = "Loaded $displayName — ${mesh.vertexCount} vertices. Tap Auto-Rig."
            binding.btnAutoRig.isEnabled = true
            binding.btnAdjust.isEnabled = false
            binding.btnAdjust.isChecked = false
            binding.btnPreview.isEnabled = false
            binding.btnPreview.isChecked = false
            binding.btnExport.isEnabled = false
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun runAutoRig() {
        val mesh = currentMesh ?: return
        val skeleton = AutoRigger.fit(mesh, includeFingers = binding.checkFingers.isChecked)
        currentSkeleton = skeleton

        val renderer = binding.glSurfaceView.modelRenderer
        renderer.skeleton = skeleton
        renderer.showSkeleton = true
        renderer.skinningEnabled = false
        binding.jointOverlay.skeleton = skeleton
        binding.glSurfaceView.requestRender()

        binding.statusText.text = "Auto-rig fitted (${skeleton.bones.size} bones). Adjust joints if needed, then preview or export."
        binding.btnAdjust.isEnabled = true
        binding.btnPreview.isEnabled = true
        binding.btnExport.isEnabled = true
    }

    private fun startPosePreview() {
        val mesh = currentMesh ?: return
        val skeleton = currentSkeleton ?: return
        val skin = AutoSkinner.computeWeights(mesh, skeleton)
        val bindPositions = skeleton.bones.map { it.bindWorldPosition }
        val jointMats = FKPose.testPoseSkinningMatrices(skeleton, bindPositions)

        val renderer = binding.glSurfaceView.modelRenderer
        renderer.setSkinning(skin)
        renderer.jointMatrices = jointMats
        renderer.skinningEnabled = true
        renderer.showSkeleton = false
        binding.glSurfaceView.requestRender()
        binding.statusText.text = "Previewing a test bend pose — confirms the auto-skin weights follow the rig."
    }

    private fun exportTo(uri: Uri) {
        val mesh = currentMesh
        val skeleton = currentSkeleton
        if (mesh == null || skeleton == null) {
            Toast.makeText(this, "Nothing to export yet", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val skin = AutoSkinner.computeWeights(mesh, skeleton)
            val textureMax = when (binding.spinnerTextureRes.selectedItemPosition) {
                1 -> 2048
                2 -> 4096
                else -> null
            }
            val glb = GlbExporter.export(mesh, skeleton, skin, textureMax)
            contentResolver.openOutputStream(uri)?.use { it.write(glb) }
            binding.statusText.text = "Exported rigged .glb (${glb.size / 1024} KB). Import it into your game engine."
            Toast.makeText(this, "Export complete", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        } finally {
            cursor?.close()
        }
        return null
    }
}
