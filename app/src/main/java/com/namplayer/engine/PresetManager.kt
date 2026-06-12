package com.namplayer.engine

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// ─────────────────────────────────────────────────────────────
// PresetManager — يحفظ ويستعيد إعدادات كاملة
// كل preset يحفظ: NAM path, Cabinet path, Gain, EQ, Gate
// ─────────────────────────────────────────────────────────────

data class Preset(
    val name:       String,
    val namPath:    String  = "",
    val cabPath:    String  = "",
    val inputGain:  Float   = 0f,
    val outputGain: Float   = 0f,
    val bassDb:     Float   = 0f,
    val midDb:      Float   = 0f,
    val trebleDb:   Float   = 0f,
    val gateOn:     Boolean = false,
    val gateThresh: Float   = -60f,
    val eqOn:       Boolean = false,
    val timestamp:  Long    = System.currentTimeMillis()
)

class PresetManager(ctx: Context) {

    private val prefs: SharedPreferences =
        ctx.getSharedPreferences("nam_presets", Context.MODE_PRIVATE)
    private val gson = Gson()

    // ── Save ──────────────────────────────────────────────

    fun save(preset: Preset): Boolean {
        val list = loadAll().toMutableList()
        val idx  = list.indexOfFirst { it.name == preset.name }
        if (idx >= 0) list[idx] = preset else list.add(0, preset)
        return runCatching {
            prefs.edit().putString("presets", gson.toJson(list)).apply()
            true
        }.getOrDefault(false)
    }

    // ── Load all ──────────────────────────────────────────

    fun loadAll(): List<Preset> {
        val json = prefs.getString("presets", null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<Preset>>() {}.type
            gson.fromJson<List<Preset>>(json, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    // ── Delete ────────────────────────────────────────────

    fun delete(name: String) {
        val list = loadAll().filter { it.name != name }
        prefs.edit().putString("presets", gson.toJson(list)).apply()
    }

    // ── Last used ─────────────────────────────────────────

    fun saveLast(preset: Preset) =
        prefs.edit().putString("last", gson.toJson(preset)).apply()

    fun loadLast(): Preset? {
        val json = prefs.getString("last", null) ?: return null
        return runCatching { gson.fromJson(json, Preset::class.java) }.getOrNull()
    }
}
