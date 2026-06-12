package com.namplayer.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

private const val TAG = "CabIR"

// ─────────────────────────────────────────────────────────────
// CabIrLoader — Cabinet Impulse Response
// إصلاح: directConvolve index bug
// إضافة: IR normalize, safe bounds checks
// ─────────────────────────────────────────────────────────────

class CabIrLoader {

    var isLoaded:  Boolean = false; private set
    var irName:    String  = "";    private set
    var enabled:   Boolean = true

    private var kernel:    FloatArray = FloatArray(0)
    private var kernelLen: Int        = 0
    private var overlap:   FloatArray = FloatArray(0)

    // ── Load ──────────────────────────────────────────────

    fun load(stream: InputStream, name: String): Boolean {
        return runCatching {
            val wav = parseWav(stream) ?: error("Invalid WAV")
            val maxLen = 2048
            var k = if (wav.size > maxLen) wav.copyOfRange(0, maxLen) else wav

            // normalize IR
            val peak = k.maxOfOrNull { abs(it) } ?: 1f
            if (peak > 0.001f) k = FloatArray(k.size) { k[it] / peak }

            kernel    = k
            kernelLen = k.size
            overlap   = FloatArray(kernelLen + 1)
            irName    = name.removeSuffix(".wav")
            isLoaded  = true
            Log.i(TAG, "IR loaded: $irName len=$kernelLen")
            true
        }.onFailure {
            Log.e(TAG, "Load failed: ${it.message}")
            isLoaded = false
        }.getOrDefault(false)
    }

    fun load(ctx: Context, uri: Uri, name: String): Boolean =
        ctx.contentResolver.openInputStream(uri)?.let { load(it, name) } ?: false

    fun unload() {
        kernel = FloatArray(0); overlap = FloatArray(0)
        kernelLen = 0; isLoaded = false; irName = ""
    }

    // ── Process ───────────────────────────────────────────

    fun process(input: FloatArray, output: FloatArray, frames: Int) {
        if (!isLoaded || !enabled || kernelLen == 0) {
            input.copyInto(output, 0, 0, frames); return
        }
        if (frames <= 128) directConvolve(input, output, frames)
        else               overlapAdd(input, output, frames)
    }

    // ── Direct convolution — BUG FIXED ───────────────────
    // إصلاح: bounds check صحيح للـ overlap access

    private fun directConvolve(input: FloatArray, output: FloatArray, frames: Int) {
        val kLen = minOf(kernelLen, 256)
        for (n in 0 until frames) {
            var sum = 0f
            for (k in 0 until kLen) {
                val srcIdx = n - k
                val sample = when {
                    srcIdx >= 0 -> input[srcIdx]
                    // ✅ إصلاح: index صحيح من overlap
                    else -> {
                        val oi = overlap.size + srcIdx
                        if (oi >= 0 && oi < overlap.size) overlap[oi] else 0f
                    }
                }
                sum += kernel[k] * sample
            }
            output[n] = sum
        }
        // حفظ tail
        val saveLen = minOf(frames, overlap.size)
        System.arraycopy(input, frames - saveLen, overlap, overlap.size - saveLen, saveLen)
    }

    // ── Overlap-Add FFT convolution ───────────────────────

    private fun overlapAdd(input: FloatArray, output: FloatArray, frames: Int) {
        val fftSize = nextPow2(frames + kernelLen - 1)

        val xR = FloatArray(fftSize).also { input.copyInto(it, 0, 0, frames) }
        val xI = FloatArray(fftSize)
        val hR = FloatArray(fftSize).also { kernel.copyInto(it, 0, 0, kernelLen) }
        val hI = FloatArray(fftSize)

        fft(xR, xI, false); fft(hR, hI, false)

        val yR = FloatArray(fftSize) { xR[it] * hR[it] - xI[it] * hI[it] }
        val yI = FloatArray(fftSize) { xR[it] * hI[it] + xI[it] * hR[it] }
        fft(yR, yI, true)

        for (i in 0 until frames) {
            output[i] = yR[i] + (if (i < overlap.size) overlap[i] else 0f)
        }

        val tailLen = fftSize - frames
        val newOverlap = FloatArray(maxOf(tailLen, kernelLen))
        for (i in 0 until tailLen) {
            if (frames + i < fftSize) newOverlap[i] = yR[frames + i]
        }
        overlap = newOverlap
    }

    // ── WAV Parser ────────────────────────────────────────

    private fun parseWav(stream: InputStream): FloatArray? {
        val bytes = stream.readBytes()
        if (bytes.size < 44) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        if (String(bytes, 0, 4) != "RIFF") return null
        if (String(bytes, 8, 4) != "WAVE") return null

        bb.position(12)
        var numCh = 1; var bits = 16; var sr = 44100
        var audioData: ByteArray? = null

        while (bb.remaining() >= 8) {
            val id   = String(bytes, bb.position(), 4); bb.position(bb.position() + 4)
            val size = bb.int
            when (id) {
                "fmt " -> {
                    bb.short; numCh = bb.short.toInt()
                    sr = bb.int; bb.int; bb.short
                    bits = bb.short.toInt()
                    val extra = size - 16
                    if (extra > 0) bb.position((bb.position() + extra).coerceAtMost(bytes.size))
                }
                "data" -> {
                    audioData = ByteArray(size.coerceAtMost(bb.remaining()))
                    bb.get(audioData)
                }
                else -> {
                    val skip = size.coerceAtMost(bb.remaining())
                    bb.position(bb.position() + skip)
                }
            }
        }

        val data = audioData ?: return null
        val db   = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val total = data.size / (bits / 8)
        val perCh = total / numCh
        val result = FloatArray(perCh)

        for (i in 0 until perCh) {
            var sum = 0f
            for (ch in 0 until numCh) {
                sum += when (bits) {
                    16 -> db.short / 32768f
                    24 -> {
                        val b0 = db.get().toInt() and 0xFF
                        val b1 = db.get().toInt() and 0xFF
                        val b2 = db.get().toInt()
                        ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f
                    }
                    32 -> if (db.remaining() >= 4) db.float else 0f
                    else -> if (db.remaining() >= 2) db.short / 32768f else 0f
                }
            }
            result[i] = sum / numCh
        }
        Log.d(TAG, "WAV parsed: $perCh samples @ ${sr}Hz ${bits}bit ${numCh}ch")
        return result
    }

    // ── Helpers ───────────────────────────────────────────

    private fun nextPow2(n: Int): Int { var p = 1; while (p < n) p = p shl 1; return p }

    private fun fft(re: FloatArray, im: FloatArray, inv: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t     = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = 2.0 * PI / len * if (inv) -1 else 1
            val wR  = cos(ang).toFloat(); val wI = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cR = 1f; var cI = 0f
                for (jj in 0 until len / 2) {
                    val uR = re[i+jj];  val uI = im[i+jj]
                    val vR = re[i+jj+len/2]*cR - im[i+jj+len/2]*cI
                    val vI = re[i+jj+len/2]*cI + im[i+jj+len/2]*cR
                    re[i+jj] = uR+vR; im[i+jj] = uI+vI
                    re[i+jj+len/2] = uR-vR; im[i+jj+len/2] = uI-vI
                    val nR = cR*wR - cI*wI; cI = cR*wI + cI*wR; cR = nR
                }
                i += len
            }
            len = len shl 1
        }
        if (inv) { val s = 1f/n; for (i in re.indices) { re[i] *= s; im[i] *= s } }
    }
}
