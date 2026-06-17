package com.nexbytes.h7skertool.utils

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

object ModManager {
    private const val TAG = "ModManager"
    private const val MODS_DIR = ".mods"

    fun getModsDir(context: Context): File {
        val gamePath = try {
            File("/sdcard/Android/data/com.dts.freefireth/files/$MODS_DIR")
                .also { if (it.parentFile?.canWrite() == true) it.mkdirs() }
        } catch (_: Exception) { null }
        return if (gamePath != null && gamePath.exists()) gamePath
        else File(context.filesDir, MODS_DIR).also { it.mkdirs() }
    }

    fun saveMod(context: Context, name: String, content: String, type: ModType = ModType.RESPONSE): Boolean {
        return try {
            val dir = getModsDir(context)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "${name.sanitize()}.modz")
            val wrapper = ModFile(name = name, type = type.name, content = content, createdAt = System.currentTimeMillis())
            file.writeText(Gson().toJson(wrapper), Charsets.UTF_8)
            Log.i(TAG, "Saved mod '${name}' → ${file.absolutePath}")
            true
        } catch (e: Exception) { Log.e(TAG, "saveMod failed: ${e.message}"); false }
    }

    fun loadMods(context: Context): List<ModFile> {
        return try {
            val dir = getModsDir(context)
            if (!dir.exists()) { dir.mkdirs(); return emptyList() }
            val gson = Gson()
            dir.listFiles { f -> f.extension == "modz" }
                ?.mapNotNull { f ->
                    try { val t = f.readText(Charsets.UTF_8); if (t.isBlank()) null else gson.fromJson(t, ModFile::class.java) }
                    catch (e: Exception) { Log.w(TAG, "Skip ${f.name}: ${e.message}"); null }
                }?.sortedByDescending { it.createdAt } ?: emptyList()
        } catch (e: Exception) { Log.e(TAG, "loadMods error: ${e.message}"); emptyList() }
    }

    fun deleteMod(context: Context, name: String): Boolean {
        return try { File(getModsDir(context), "${name.sanitize()}.modz").delete() } catch (_: Exception) { false }
    }

    private fun String.sanitize(): String = replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(64)
}

enum class ModType { REQUEST, RESPONSE, HEADER }

data class ModFile(
    val name: String = "",
    val type: String = ModType.RESPONSE.name,
    val content: String = "",
    val createdAt: Long = 0L,
    val version: Int = 1
)
