package com.namplayer.audio

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "AudioRecorder"

class AudioRecorder(private val ctx: Context) {

    enum class RecState { IDLE, RECORDING }

    private val _state = MutableStateFlow(RecState.IDLE)
    val state: StateFlow<RecState> = _state

    private var outStream:      FileOutputStream? = null
    private var wavFile:        File?             = null
    private var sampleRate      = 48000
    private var samplesWritten  = 0L

    val isRecording     get() = _state.value == RecState.RECORDING
    val durationSeconds get() = if (sampleRate > 0) samplesWritten / sampleRate else 0L
    val filePath:       String? get() = wavFile?.absolutePath

    // ── Start ─────────────────────────────────────────────

    fun start(sr: Int): Boolean {
        if (isRecording) return false
        sampleRate = sr; samplesWritten = 0

        return runCatching {
            // ✅ إصلاح: fallback لـ app-private dir إذا فشل external storage
            val dir = getBestDir()
            dir.mkdirs()

            val ts   = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            wavFile  = File(dir, "nam_$ts.wav")
            outStream = FileOutputStream(wavFile!!)
            writeWavHeader(outStream!!, 0, sr)
            _state.value = RecState.RECORDING
            Log.i(TAG, "Recording: ${wavFile!!.absolutePath}")
            true
        }.onFailure {
            Log.e(TAG, "Start failed: ${it.message}")
            // fallback to cache dir
            runCatching {
                val dir = File(ctx.cacheDir, "NAMPlayer").also { it.mkdirs() }
                val ts  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                wavFile = File(dir, "nam_$ts.wav")
                outStream = FileOutputStream(wavFile!!)
                writeWavHeader(outStream!!, 0, sr)
                _state.value = RecState.RECORDING
            }
        }.getOrDefault(false)
    }

    private fun getBestDir(): File {
        // Android 10+ → app-specific external
        ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.let {
            if (it.canWrite()) return File(it, "NAMPlayer")
        }
        // Public Music dir (Android 9 and below)
        val pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        if (pub.canWrite()) return File(pub, "NAMPlayer")
        // Internal fallback
        return File(ctx.filesDir, "recordings")
    }

    // ── Write ─────────────────────────────────────────────

    fun write(buf: FloatArray, frames: Int) {
        if (!isRecording) return
        runCatching {
            val bytes = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frames)
                bytes.putShort((buf[i].coerceIn(-1f,1f) * 32767f).toInt().toShort())
            outStream?.write(bytes.array())
            samplesWritten += frames
        }
    }

    // ── Stop ──────────────────────────────────────────────

    fun stop(): File? {
        if (!isRecording) return null
        val file = wavFile ?: return null
        runCatching {
            outStream?.flush(); outStream?.close(); outStream = null
            RandomAccessFile(file, "rw").use { writeWavHeaderRaf(it, samplesWritten, sampleRate) }
            Log.i(TAG, "Saved: ${file.name} (${samplesWritten/sampleRate}s)")
        }.onFailure { Log.e(TAG, "Stop failed: ${it.message}") }
        _state.value = RecState.IDLE
        wavFile = null
        return file
    }

    // ── WAV headers ───────────────────────────────────────

    private fun writeWavHeader(s: OutputStream, samples: Long, sr: Int) {
        val data  = (samples * 2).toInt()
        ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(data + 36)
            put("WAVE".toByteArray()); put("fmt ".toByteArray())
            putInt(16); putShort(1); putShort(1)
            putInt(sr); putInt(sr * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data)
        }.also { s.write(it.array()) }
    }

    private fun writeWavHeaderRaf(raf: RandomAccessFile, samples: Long, sr: Int) {
        val data = samples * 2
        raf.seek(0)
        raf.write("RIFF".toByteArray())
        raf.write(le4((data + 36).toInt()))
        raf.write("WAVE".toByteArray()); raf.write("fmt ".toByteArray())
        raf.write(le4(16)); raf.write(le2(1)); raf.write(le2(1))
        raf.write(le4(sr)); raf.write(le4(sr * 2)); raf.write(le2(2)); raf.write(le2(16))
        raf.write("data".toByteArray()); raf.write(le4(data.toInt()))
    }

    private fun le4(v: Int)   = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    private fun le2(v: Int)   = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
}
