package com.jjk.rigmobile.model

import android.content.ContentResolver
import android.net.Uri

object ModelImporter {

    /** Loads [uri] as a Mesh, dispatching on file extension (.obj / .gltf / .glb). */
    fun import(resolver: ContentResolver, uri: Uri, displayName: String): Mesh {
        val lower = displayName.lowercase()
        return when {
            lower.endsWith(".obj") -> resolver.openInputStream(uri)!!.use { ObjLoader.load(it) }
            lower.endsWith(".glb") -> {
                val bytes = resolver.openInputStream(uri)!!.use { it.readBytes() }
                GltfLoader.loadGlb(bytes)
            }
            lower.endsWith(".gltf") -> {
                val text = resolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                GltfLoader.loadGltfJson(text)
            }
            else -> throw IllegalArgumentException("Unsupported file type: $displayName (use .obj, .gltf, or .glb)")
        }
    }
}
