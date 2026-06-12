package com.namplayer.engine

import ai.onnxruntime.*
import android.content.Context
import android.util.Log
import com.namplayer.converter.NamModel
import java.nio.FloatBuffer
import kotlin.math.*

private const val TAG = "OnnxEngine"

class OnnxInferenceEngine(context: Context) {

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var currentModel: NamModel? = null

    // LSTM state
    private var lstmH: Array<FloatArray> = emptyArray()
    private var lstmC: Array<FloatArray> = emptyArray()

    // WaveNet dilated buffers — حجم صحيح الآن
    private var waveBuffers: Array<FloatArray> = emptyArray()
    private var waveBufPos:  IntArray = intArrayOf()

    // Gain
    private var inputGain:  Float = 1f
    private var outputGain: Float = 1f

    // ── Resampler state ───────────────────────────────────
    // يحوّل sample rate الموديل إلى sample rate الجهاز
    private var resampleRatio: Float = 1f
    private var resampleAcc:   Float = 0f
    private var resamplePrev:  Float = 0f

    val isLoaded: Boolean get() = currentModel != null

    // ── Load ──────────────────────────────────────────────

    fun load(model: NamModel): Boolean {
        unload()
        return runCatching {
            val onnxBytes = OnnxBuilder.build(model)
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(2)
                try { addNnapi() } catch (_: Exception) {}
            }
            session = ortEnv.createSession(onnxBytes, opts)
            currentModel = model

            // loudness normalization → -18 dBFS
            outputGain = 10f.pow((-18f - model.loudnessDb) / 20f).coerceIn(0.1f, 8f)

            resetState(model)
            Log.i(TAG, "Loaded ${model.arch} '${model.displayName}' SR=${model.sampleRate}")
            true
        }.onFailure { Log.e(TAG, "Load failed", it) }
         .getOrDefault(false)
    }

    // deviceSr: sample rate الجهاز الفعلي لضبط الـ resampler
    fun setDeviceSampleRate(deviceSr: Int) {
        val modelSr = currentModel?.sampleRate ?: deviceSr
        resampleRatio = modelSr.toFloat() / deviceSr.toFloat()
        resampleAcc   = 0f
        resamplePrev  = 0f
        Log.d(TAG, "Resample ratio: $resampleRatio (model=$modelSr, device=$deviceSr)")
    }

    fun unload() {
        session?.close(); session = null
        currentModel = null
        lstmH = emptyArray(); lstmC = emptyArray()
        waveBuffers = emptyArray()
        resampleRatio = 1f
    }

    // ── Process ───────────────────────────────────────────

    fun process(input: FloatArray, output: FloatArray, frames: Int) {
        val mdl = currentModel ?: run { output.fill(0f, 0, frames); return }
        val sess = session

        // إذا SR مختلف نحتاج resample
        if (abs(resampleRatio - 1f) > 0.001f) {
            val resampled = resample(input, frames)
            val tempOut = FloatArray(resampled.size)
            processModel(sess, mdl, resampled, tempOut, resampled.size)
            resampleOutput(tempOut, output, frames)
        } else {
            processModel(sess, mdl, input, output, frames)
        }

        // Hard limiter — منع clipping
        for (i in 0 until frames) {
            output[i] = output[i].coerceIn(-1f, 1f)
        }

        // DC offset removal (single-pole high-pass at ~20Hz)
        dcBlock(output, frames)
    }

    private fun processModel(sess: OrtSession?, mdl: NamModel, input: FloatArray, output: FloatArray, frames: Int) {
        runCatching {
            when (mdl) {
                is NamModel.Lstm    -> if (sess != null) processLstm(sess, mdl, input, output, frames)
                                       else processLstmNative(mdl, input, output, frames)
                is NamModel.WaveNet -> processWaveNet(mdl, input, output, frames)
                is NamModel.Linear  -> processLinear(mdl, input, output, frames)
            }
        }.onFailure {
            Log.w(TAG, "Process error, fallback: ${it.message}")
            processFallback(mdl, input, output, frames)
        }
    }

    // ── Resampler (linear interpolation) ─────────────────

    private fun resample(input: FloatArray, frames: Int): FloatArray {
        val outLen = (frames / resampleRatio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        var acc = resampleAcc
        var prev = resamplePrev
        var inIdx = 0
        for (i in 0 until outLen) {
            while (acc >= 1f && inIdx < frames - 1) {
                prev = input[inIdx++]
                acc -= 1f
            }
            val curr = if (inIdx < frames) input[inIdx] else prev
            out[i] = prev + (curr - prev) * acc
            acc += resampleRatio
        }
        resampleAcc  = acc - (inIdx).toFloat()
        resamplePrev = if (frames > 0) input[frames - 1] else prev
        return out
    }

    private fun resampleOutput(src: FloatArray, dst: FloatArray, dstFrames: Int) {
        val ratio = src.size.toFloat() / dstFrames.toFloat()
        for (i in 0 until dstFrames) {
            val pos = i * ratio
            val idx = pos.toInt().coerceIn(0, src.size - 1)
            dst[i] = src[idx]
        }
    }

    // ── DC blocker ────────────────────────────────────────
    private var dcState = 0f
    private fun dcBlock(buf: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            val x = buf[i]
            dcState = dcState * 0.9999f + x - (if (i > 0) buf[i-1] else 0f)
            buf[i] = x - dcState * 0.00001f
        }
    }

    // ── LSTM via ONNX ─────────────────────────────────────

    private fun processLstm(sess: OrtSession, mdl: NamModel.Lstm, input: FloatArray, output: FloatArray, frames: Int) {
        val inputTensor = OnnxTensor.createTensor(ortEnv,
            FloatBuffer.wrap(applyGain(input, frames)),
            longArrayOf(1, frames.toLong(), 1))

        val h0Flat = lstmH.flatMap { it.toList() }.toFloatArray()
        val c0Flat = lstmC.flatMap { it.toList() }.toFloatArray()
        val h0 = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(h0Flat),
            longArrayOf(mdl.numLayers.toLong(), 1, mdl.hiddenSize.toLong()))
        val c0 = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(c0Flat),
            longArrayOf(mdl.numLayers.toLong(), 1, mdl.hiddenSize.toLong()))

        val results = sess.run(mapOf("input" to inputTensor, "h0" to h0, "c0" to c0))

        val outTensor = results[0].value as Array<*>
        for (t in 0 until frames) {
            output[t] = ((outTensor[0] as Array<*>)[t] as FloatArray)[0] * outputGain
        }
        updateLstmState(results, mdl)
        inputTensor.close(); h0.close(); c0.close(); results.close()
    }

    private fun updateLstmState(results: OrtSession.Result, mdl: NamModel.Lstm) {
        runCatching {
            val hn = results[1].value as Array<*>
            val cn = results[2].value as Array<*>
            for (l in 0 until mdl.numLayers) {
                ((hn[l] as Array<*>)[0] as FloatArray).copyInto(lstmH[l])
                ((cn[l] as Array<*>)[0] as FloatArray).copyInto(lstmC[l])
            }
        }
    }

    // ── WaveNet Native — BUG FIXED ────────────────────────
    // إصلاح: bufSize محسوب بشكل صحيح الآن
    // إصلاح: modulo index صحيح

    private fun processWaveNet(mdl: NamModel.WaveNet, input: FloatArray, output: FloatArray, frames: Int) {
        val ch = mdl.channels
        val ks = mdl.kernelSize

        for (t in 0 until frames) {
            val x = input[t] * inputGain
            val inter = FloatArray(ch)

            mdl.dilations.forEachIndexed { li, dilation ->
                // ✅ إصلاح: bufSize = kernelSize * dilation (بدون * ks مكرر)
                val bufSize = ks * dilation
                val buf     = waveBuffers[li]
                val pos     = waveBufPos[li]
                val inCh    = if (li == 0) 1 else ch

                // push
                if (inCh == 1) {
                    buf[pos % bufSize] = x
                } else {
                    for (c in 0 until ch) buf[(pos % bufSize) * ch + c] = inter[c]
                }

                // dilated conv
                val cw = mdl.convWeights[li]
                val cb = mdl.convBiases[li]
                for (oc in 0 until ch) {
                    var sum = cb[oc]
                    for (k in 0 until ks) {
                        // ✅ إصلاح: modulo صحيح
                        val si = ((pos - k * dilation + bufSize * 1000) % bufSize)
                        if (inCh == 1) {
                            if (si < buf.size) sum += cw[oc * ks + k] * buf[si]
                        } else {
                            if (si * ch + ch - 1 < buf.size) {
                                for (ic in 0 until ch) {
                                    sum += cw[(oc * inCh + ic) * ks + k] * buf[si * ch + ic]
                                }
                            }
                        }
                    }
                    inter[oc] = relu(sum)
                }
                waveBufPos[li] = (pos + 1) % bufSize
            }

            var y = mdl.headB
            for (c in 0 until ch) y += mdl.headW[c] * inter[c]
            output[t] = y * outputGain
        }
    }

    // ── Linear ────────────────────────────────────────────

    private fun processLinear(mdl: NamModel.Linear, input: FloatArray, output: FloatArray, frames: Int) {
        for (i in 0 until frames)
            output[i] = (mdl.w * input[i] * inputGain + mdl.b) * outputGain
    }

    // ── Fallback ──────────────────────────────────────────

    private fun processFallback(mdl: NamModel, input: FloatArray, output: FloatArray, frames: Int) {
        when (mdl) {
            is NamModel.Lstm    -> processLstmNative(mdl, input, output, frames)
            is NamModel.WaveNet -> processWaveNet(mdl, input, output, frames)
            is NamModel.Linear  -> processLinear(mdl, input, output, frames)
        }
    }

    // ── Native LSTM — BUG FIXED ───────────────────────────
    // إصلاح: layer > 0 يقرأ من lstmH[l-1] بشكل صحيح

    private fun processLstmNative(mdl: NamModel.Lstm, input: FloatArray, output: FloatArray, frames: Int) {
        val hidden   = mdl.hiddenSize
        val gateSize = 4 * hidden
        val gates    = FloatArray(gateSize)
        val newH     = FloatArray(hidden)

        for (t in 0 until frames) {
            val xIn = input[t] * inputGain
            for (l in 0 until mdl.numLayers) {
                val h  = lstmH[l]; val c = lstmC[l]
                val wi = mdl.wi[l]; val wh = mdl.wh[l]
                val bi = mdl.bi[l]; val bh = mdl.bh[l]

                for (g in 0 until gateSize) {
                    var s = bi[g] + bh[g]
                    // ✅ إصلاح: layer 0 يقرأ xIn، layer > 0 يقرأ من lstmH[l-1]
                    if (l == 0) {
                        s += if (g < wi.size) wi[g] * xIn else 0f
                    } else {
                        val prevH = lstmH[l - 1]
                        val wiRow = g * hidden
                        for (j in 0 until hidden) {
                            if (wiRow + j < wi.size) s += wi[wiRow + j] * prevH[j]
                        }
                    }
                    for (j in 0 until hidden) {
                        val whIdx = g * hidden + j
                        if (whIdx < wh.size) s += wh[whIdx] * h[j]
                    }
                    gates[g] = s
                }
                for (j in 0 until hidden) {
                    val ig = sigmoid(gates[j])
                    val fg = sigmoid(gates[hidden + j])
                    val gg = tanh(gates[2 * hidden + j].toDouble()).toFloat()
                    val og = sigmoid(gates[3 * hidden + j])
                    c[j]    = fg * c[j] + ig * gg
                    newH[j] = og * tanh(c[j].toDouble()).toFloat()
                }
                newH.copyInto(h)
            }
            var y = mdl.headB
            val lastH = lstmH[mdl.numLayers - 1]
            for (j in 0 until hidden) y += mdl.headW[j] * lastH[j]
            output[t] = y * outputGain
        }
    }

    // ── State reset ───────────────────────────────────────

    private fun resetState(model: NamModel) {
        when (model) {
            is NamModel.Lstm -> {
                lstmH = Array(model.numLayers) { FloatArray(model.hiddenSize) }
                lstmC = Array(model.numLayers) { FloatArray(model.hiddenSize) }
            }
            is NamModel.WaveNet -> {
                // ✅ إصلاح: حجم buffer صحيح
                waveBuffers = Array(model.dilations.size) { i ->
                    val inCh    = if (i == 0) 1 else model.channels
                    val bufSize = model.kernelSize * model.dilations[i]
                    FloatArray(bufSize * inCh)
                }
                waveBufPos = IntArray(model.dilations.size)
            }
            else -> {}
        }
    }

    fun resetState() { currentModel?.let { resetState(it) } }

    // ── Helpers ───────────────────────────────────────────

    private fun applyGain(input: FloatArray, frames: Int): FloatArray =
        if (inputGain == 1f) input.copyOfRange(0, frames)
        else FloatArray(frames) { input[it] * inputGain }

    fun setInputGainDb(db: Float)  { inputGain  = 10f.pow(db / 20f) }
    fun setOutputGainDb(db: Float) { outputGain = 10f.pow(db / 20f) }

    private fun sigmoid(x: Float) = 1f / (1f + exp(-x))
    private fun relu(x: Float)    = maxOf(0f, x)

    fun close() { unload(); ortEnv.close() }
}
