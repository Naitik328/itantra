package com.sih.itantra.ai

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.File

/**
 * Stages a model that ships inside the APK's `assets/` out onto the real filesystem, once.
 *
 * Two of the three files a Piper voice needs can't be read straight from `assets/`:
 *  - ONNX Runtime memory-maps the model, which needs a real file descriptor on disk — that's the
 *    point of `noCompress` in the Gradle config, but mmap still wants a path, not an asset stream.
 *  - espeak-ng opens its data files with `fopen`, so `espeak-ng-data/` has to exist as an actual
 *    directory tree, not as entries inside the package.
 *
 * So the whole model directory is copied to [Context.getFilesDir] on first launch and reused
 * thereafter. A [MARKER] file stamped with [version] guards the copy: bump the version when the
 * bundled model changes and the next launch re-stages it.
 */
class ModelInstaller(private val context: Context) {

    /**
     * Ensure the asset directory [assetDir] is present under files/[targetName] and return it.
     * Runs the copy only when the marker is missing or its version differs. Blocking I/O — call
     * off the main thread.
     */
    fun install(assetDir: String, targetName: String, version: String): File {
        val target = File(context.filesDir, targetName)
        val marker = File(target, MARKER)

        if (marker.takeIf { it.exists() }?.readText() == version) {
            return target
        }

        // A stale or partial copy is worse than none: wipe before re-staging so a mid-copy crash
        // last time can't leave a half-written model that loads and then segfaults.
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()

        val started = System.currentTimeMillis()
        copyAssetTree(context.assets, assetDir, target)
        marker.writeText(version)
        Log.i(TAG, "staged $assetDir -> $target in ${System.currentTimeMillis() - started} ms")
        return target
    }

    private fun copyAssetTree(assets: AssetManager, path: String, destDir: File) {
        val children = assets.list(path) ?: emptyArray()
        if (children.isEmpty()) {
            // A leaf: `list` returns nothing for a file. Copy it.
            copyAssetFile(assets, path, File(destDir, path.substringAfterLast('/')))
            return
        }
        destDir.mkdirs()
        for (child in children) {
            val childPath = "$path/$child"
            val grandChildren = assets.list(childPath) ?: emptyArray()
            if (grandChildren.isEmpty()) {
                copyAssetFile(assets, childPath, File(destDir, child))
            } else {
                copyAssetTree(assets, childPath, File(destDir, child))
            }
        }
    }

    private fun copyAssetFile(assets: AssetManager, assetPath: String, dest: File) {
        assets.open(assetPath).use { input ->
            dest.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        }
    }

    private companion object {
        const val TAG = "ModelInstaller"
        const val MARKER = ".installed"
    }
}
