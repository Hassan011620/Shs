package com.namplayer.engine

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

private const val TAG = "NamScanner"

data class NamFileInfo(
    val file: File,
    val name: String = file.nameWithoutExtension,
    val sizeKb: Long = file.length() / 1024
)

object NamFileScanner {

    // مسارات البحث الشائعة
    private val SEARCH_DIRS = listOf(
        "NAM", "nam", "Downloads", "Music/NAM",
        "Documents/NAM", "Guitar/NAM", "Amp Models"
    )

    fun scan(ctx: Context): List<NamFileInfo> {
        val found = mutableListOf<NamFileInfo>()
        val ext = ctx.getExternalFilesDir(null)

        val roots = mutableListOf<File>()

        // External storage
        runCatching {
            Environment.getExternalStorageDirectory()?.let { roots.add(it) }
        }
        // App external dir
        ext?.let { roots.add(it.parentFile ?: it) }

        for (root in roots) {
            // مسارات محددة
            for (rel in SEARCH_DIRS) {
                val dir = File(root, rel)
                if (dir.exists()) scanDir(dir, found, depth = 0)
            }
            // root مباشر
            root.listFiles()
                ?.filter { it.extension.lowercase() == "nam" }
                ?.forEach { found.add(NamFileInfo(it)) }
        }

        val unique = found.distinctBy { it.file.absolutePath }
            .sortedByDescending { it.file.lastModified() }
        Log.i(TAG, "Found ${unique.size} .nam files")
        return unique
    }

    private fun scanDir(dir: File, out: MutableList<NamFileInfo>, depth: Int) {
        if (depth > 3) return
        dir.listFiles()?.forEach { f ->
            when {
                f.isFile && f.extension.lowercase() == "nam" -> out.add(NamFileInfo(f))
                f.isDirectory -> scanDir(f, out, depth + 1)
            }
        }
    }
}
