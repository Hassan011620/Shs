package com.namplayer.ui

import android.app.Application
import android.content.*
import android.net.Uri
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.namplayer.audio.AudioEngine
import com.namplayer.audio.AudioStats
import com.namplayer.audio.EngineState
import com.namplayer.converter.NamModel
import com.namplayer.converter.NamParser
import com.namplayer.engine.*
import com.namplayer.usb.UsbAudioDev
import com.namplayer.usb.UsbAudioManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app.applicationContext
    val usb         = UsbAudioManager(ctx)
    val presets     = PresetManager(ctx)

    // ── Service ───────────────────────────────────────────

    private var svc: AudioEngine? = null
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            svc = (b as AudioEngine.EngineBinder).get()
            observeService()
            restoreLastPreset()
        }
        override fun onServiceDisconnected(n: ComponentName) { svc = null }
    }

    // ── State flows ───────────────────────────────────────

    private val _engineState = MutableStateFlow(EngineState.IDLE)
    val engineState: StateFlow<EngineState> = _engineState

    private val _stats = MutableStateFlow(AudioStats())
    val stats: StateFlow<AudioStats> = _stats

    private val _model       = MutableStateFlow<NamModel?>(null)
    val model: StateFlow<NamModel?> = _model

    private val _namPath     = MutableStateFlow("")
    private val _cabName     = MutableStateFlow<String?>(null)
    val cabName: StateFlow<String?> = _cabName

    private val _cabPath     = MutableStateFlow("")

    // Gains
    private val _inputGain   = MutableStateFlow(0f)
    val inputGainDb: StateFlow<Float> = _inputGain
    private val _outputGain  = MutableStateFlow(0f)
    val outputGainDb: StateFlow<Float> = _outputGain

    // Gate
    private val _gateOn      = MutableStateFlow(false)
    val gateEnabled: StateFlow<Boolean> = _gateOn
    private val _gateThresh  = MutableStateFlow(-60f)
    val gateThreshDb: StateFlow<Float> = _gateThresh

    // EQ
    private val _eqOn        = MutableStateFlow(false)
    val eqEnabled: StateFlow<Boolean> = _eqOn
    private val _bass        = MutableStateFlow(0f)
    val bassDb: StateFlow<Float> = _bass
    private val _mid         = MutableStateFlow(0f)
    val midDb: StateFlow<Float> = _mid
    private val _treble      = MutableStateFlow(0f)
    val trebleDb: StateFlow<Float> = _treble

    // Files
    private val _namFiles    = MutableStateFlow<List<NamFileInfo>>(emptyList())
    val namFiles: StateFlow<List<NamFileInfo>> = _namFiles

    private val _presetList  = MutableStateFlow<List<Preset>>(emptyList())
    val presetList: StateFlow<List<Preset>> = _presetList

    val loading      = MutableStateFlow(false)
    val usbDevices   = usb.devices
    val activeUsb    = usb.active

    private val _msg = MutableSharedFlow<String>()
    val msg: SharedFlow<String> = _msg

    val isRunning get() = _engineState.value == EngineState.RUNNING

    init {
        ctx.bindService(Intent(ctx, AudioEngine::class.java), conn, Context.BIND_AUTO_CREATE)
        usb.scan()
        _presetList.value = presets.loadAll()
        scanNamFiles()
    }

    private fun observeService() {
        val s = svc ?: return
        viewModelScope.launch { s.state.collect { _engineState.value = it } }
        viewModelScope.launch { s.stats.collect  { _stats.value      = it } }
    }

    // ── Audio ─────────────────────────────────────────────

    fun toggle() { if (isRunning) svc?.stop() else svc?.start() }

    fun setInputGain(db: Float) {
        _inputGain.value = db
        svc?.onnx?.setInputGainDb(db)
    }
    fun setOutputGain(db: Float) {
        _outputGain.value = db
        svc?.onnx?.setOutputGainDb(db)
    }

    // ── Load .nam ─────────────────────────────────────────

    fun loadNam(uri: Uri) = viewModelScope.launch {
        loading.value = true
        runCatching {
            val stream = ctx.contentResolver.openInputStream(uri) ?: error("Cannot open")
            val json   = NamParser.parse(stream).getOrThrow()
            val mdl    = NamParser.build(json).getOrThrow()
            val ok     = svc?.onnx?.load(mdl) ?: false
            if (ok) {
                _model.value   = mdl
                _namPath.value = uri.toString()
                _msg.emit("✅ ${mdl.displayName}")
            } else _msg.emit("❌ فشل التحميل")
        }.onFailure { _msg.emit("❌ ${it.message}") }
        loading.value = false
    }

    fun loadNamFile(info: NamFileInfo) = viewModelScope.launch {
        loading.value = true
        runCatching {
            val json = NamParser.parse(info.file.inputStream()).getOrThrow()
            val mdl  = NamParser.build(json).getOrThrow()
            val ok   = svc?.onnx?.load(mdl) ?: false
            if (ok) {
                _model.value   = mdl
                _namPath.value = info.file.absolutePath
                _msg.emit("✅ ${mdl.displayName}")
            } else _msg.emit("❌ فشل التحميل")
        }.onFailure { _msg.emit("❌ ${it.message}") }
        loading.value = false
    }

    // ── Cabinet IR ────────────────────────────────────────

    fun loadCab(uri: Uri) = viewModelScope.launch {
        loading.value = true
        runCatching {
            val name   = getFileName(uri)
            val stream = ctx.contentResolver.openInputStream(uri) ?: error("Cannot open")
            val ok     = svc?.cabIr?.load(stream, name) ?: false
            if (ok) {
                _cabName.value = name
                _cabPath.value = uri.toString()
                _msg.emit("🔊 Cabinet: $name")
            } else _msg.emit("❌ ملف WAV غير صالح")
        }.onFailure { _msg.emit("❌ ${it.message}") }
        loading.value = false
    }

    fun clearCab() {
        svc?.cabIr?.unload()
        _cabName.value = null; _cabPath.value = ""
        viewModelScope.launch { _msg.emit("🔊 Cabinet: off") }
    }

    fun setCabEnabled(on: Boolean) { svc?.cabIr?.let { it.enabled = on } }

    // ── Noise Gate ────────────────────────────────────────

    fun setGateEnabled(on: Boolean) {
        _gateOn.value = on
        svc?.gate?.enabled = on
    }
    fun setGateThreshold(db: Float) {
        _gateThresh.value = db
        svc?.gate?.thresholdDb = db
    }

    // ── 3-Band EQ ─────────────────────────────────────────

    fun setEqEnabled(on: Boolean) {
        _eqOn.value = on
        svc?.eq?.enabled = on
    }
    fun setBass(db: Float)   { _bass.value = db;   svc?.eq?.bassDb   = db }
    fun setMid(db: Float)    { _mid.value = db;    svc?.eq?.midDb    = db }
    fun setTreble(db: Float) { _treble.value = db; svc?.eq?.trebleDb = db }

    // ── Recording ─────────────────────────────────────────

    fun toggleRecord() {
        val s = svc ?: return
        if (s.recorder.isRecording) {
            val f = s.stopRecording()
            viewModelScope.launch {
                _msg.emit(if (f != null) "💾 ${f.name}" else "❌ خطأ في الحفظ")
            }
        } else {
            s.startRecording()
            viewModelScope.launch { _msg.emit("⏺ تسجيل...") }
        }
    }

    // ── Presets ───────────────────────────────────────────

    fun savePreset(name: String) {
        val p = buildCurrentPreset(name)
        presets.save(p)
        presets.saveLast(p)
        _presetList.value = presets.loadAll()
        viewModelScope.launch { _msg.emit("💾 Preset: $name") }
    }

    fun loadPreset(p: Preset) = viewModelScope.launch {
        // Gains
        setInputGain(p.inputGain)
        setOutputGain(p.outputGain)
        // Gate
        setGateEnabled(p.gateOn)
        setGateThreshold(p.gateThresh)
        // EQ
        setEqEnabled(p.eqOn)
        setBass(p.bassDb); setMid(p.midDb); setTreble(p.trebleDb)
        // NAM
        if (p.namPath.isNotEmpty()) {
            runCatching {
                val uri = Uri.parse(p.namPath)
                if (uri.scheme == "file") {
                    val f = java.io.File(uri.path ?: p.namPath)
                    if (f.exists()) {
                        val json = NamParser.parse(f.inputStream()).getOrThrow()
                        val mdl  = NamParser.build(json).getOrThrow()
                        svc?.onnx?.load(mdl)
                        _model.value = mdl
                    }
                } else {
                    loadNam(uri)
                }
            }
        }
        presets.saveLast(p)
        _msg.emit("📂 Preset: ${p.name}")
    }

    fun deletePreset(name: String) {
        presets.delete(name)
        _presetList.value = presets.loadAll()
        viewModelScope.launch { _msg.emit("🗑 حُذف: $name") }
    }

    private fun buildCurrentPreset(name: String) = Preset(
        name       = name,
        namPath    = _namPath.value,
        cabPath    = _cabPath.value,
        inputGain  = _inputGain.value,
        outputGain = _outputGain.value,
        bassDb     = _bass.value,
        midDb      = _mid.value,
        trebleDb   = _treble.value,
        gateOn     = _gateOn.value,
        gateThresh = _gateThresh.value,
        eqOn       = _eqOn.value
    )

    private fun restoreLastPreset() {
        presets.loadLast()?.let { loadPreset(it) }
    }

    // ── Scan .nam files ───────────────────────────────────

    fun scanNamFiles() = viewModelScope.launch {
        val found = NamFileScanner.scan(ctx)
        _namFiles.value = found
        if (found.isNotEmpty()) _msg.emit("📂 وجدت ${found.size} ملف .nam")
    }

    // ── USB ───────────────────────────────────────────────

    fun onUsbAttached() { usb.scan() }
    fun onUsbDetached() { usb.disconnect(); usb.scan() }
    fun selectUsb(d: UsbAudioDev) {
        usb.request(d)
        viewModelScope.launch { _msg.emit("🔌 ${d.label}") }
    }

    // ── Helpers ───────────────────────────────────────────

    private fun getFileName(uri: Uri) = runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            c.moveToFirst()
            c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "unknown"

    override fun onCleared() {
        super.onCleared()
        runCatching { ctx.unbindService(conn) }
    }
}
