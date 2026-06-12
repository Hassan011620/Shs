package com.namplayer.engine

import kotlin.math.*

// ─────────────────────────────────────────────────────────────
// 3-Band EQ — Bass / Mid / Treble
// يعمل بعد NAM + CabIR في السلسلة
// كل band: biquad peaking filter
// ─────────────────────────────────────────────────────────────

class ThreeBandEq {

    var enabled: Boolean = false

    // Gain بالـ dB لكل band
    var bassDb:   Float = 0f; set(v) { field = v; updateBass()   }
    var midDb:    Float = 0f; set(v) { field = v; updateMid()    }
    var trebleDb: Float = 0f; set(v) { field = v; updateTreble() }

    private var sampleRate = 48000f

    // Biquad coefficients per band
    private val bassCoef   = BiquadCoef()
    private val midCoef    = BiquadCoef()
    private val trebleCoef = BiquadCoef()

    // State (z1, z2) per band
    private val bassState   = floatArrayOf(0f, 0f)
    private val midState    = floatArrayOf(0f, 0f)
    private val trebleState = floatArrayOf(0f, 0f)

    init {
        updateAll()
    }

    fun setSampleRate(sr: Int) {
        sampleRate = sr.toFloat()
        updateAll()
    }

    // ── Process ───────────────────────────────────────────

    fun process(buf: FloatArray, frames: Int) {
        if (!enabled) return
        if (bassDb   != 0f) biquad(buf, frames, bassCoef,   bassState)
        if (midDb    != 0f) biquad(buf, frames, midCoef,    midState)
        if (trebleDb != 0f) biquad(buf, frames, trebleCoef, trebleState)
    }

    // ── Biquad filter ─────────────────────────────────────

    private fun biquad(buf: FloatArray, frames: Int, c: BiquadCoef, s: FloatArray) {
        val b0=c.b0; val b1=c.b1; val b2=c.b2; val a1=c.a1; val a2=c.a2
        var z1=s[0]; var z2=s[1]
        for (i in 0 until frames) {
            val x = buf[i]
            val y = b0*x + z1
            z1 = b1*x - a1*y + z2
            z2 = b2*x - a2*y
            buf[i] = y
        }
        s[0]=z1; s[1]=z2
    }

    // ── Peaking EQ coefficient calculation ───────────────

    private fun peakingEq(freq: Float, gainDb: Float, q: Float): BiquadCoef {
        val w0 = 2f * PI.toFloat() * freq / sampleRate
        val A  = 10f.pow(gainDb / 40f)
        val alpha = sin(w0) / (2f * q)
        val cosW0 = cos(w0)

        val b0 =  1f + alpha * A
        val b1 = -2f * cosW0
        val b2 =  1f - alpha * A
        val a0 =  1f + alpha / A
        val a1 = -2f * cosW0
        val a2 =  1f - alpha / A

        return BiquadCoef(b0/a0, b1/a0, b2/a0, a1/a0, a2/a0)
    }

    // ── Low shelf ─────────────────────────────────────────

    private fun lowShelf(freq: Float, gainDb: Float): BiquadCoef {
        val w0 = 2f * PI.toFloat() * freq / sampleRate
        val A  = 10f.pow(gainDb / 40f)
        val alpha = sin(w0) / 2f * sqrt((A + 1f/A) * (1f/0.707f - 1f) + 2f)
        val cosW0 = cos(w0)
        val sqA = sqrt(A)

        val b0 =  A * ((A+1) - (A-1)*cosW0 + 2f*sqA*alpha)
        val b1 =  2f * A * ((A-1) - (A+1)*cosW0)
        val b2 =  A * ((A+1) - (A-1)*cosW0 - 2f*sqA*alpha)
        val a0 =  (A+1) + (A-1)*cosW0 + 2f*sqA*alpha
        val a1 = -2f * ((A-1) + (A+1)*cosW0)
        val a2 =  (A+1) + (A-1)*cosW0 - 2f*sqA*alpha

        return BiquadCoef(b0/a0, b1/a0, b2/a0, a1/a0, a2/a0)
    }

    // ── High shelf ────────────────────────────────────────

    private fun highShelf(freq: Float, gainDb: Float): BiquadCoef {
        val w0 = 2f * PI.toFloat() * freq / sampleRate
        val A  = 10f.pow(gainDb / 40f)
        val alpha = sin(w0) / 2f * sqrt((A + 1f/A) * (1f/0.707f - 1f) + 2f)
        val cosW0 = cos(w0)
        val sqA = sqrt(A)

        val b0 =  A * ((A+1) + (A-1)*cosW0 + 2f*sqA*alpha)
        val b1 = -2f * A * ((A-1) + (A+1)*cosW0)
        val b2 =  A * ((A+1) + (A-1)*cosW0 - 2f*sqA*alpha)
        val a0 =  (A+1) - (A-1)*cosW0 + 2f*sqA*alpha
        val a1 =  2f * ((A-1) - (A+1)*cosW0)
        val a2 =  (A+1) - (A-1)*cosW0 - 2f*sqA*alpha

        return BiquadCoef(b0/a0, b1/a0, b2/a0, a1/a0, a2/a0)
    }

    private fun updateBass()   { bassCoef.set(lowShelf(200f, bassDb)) }
    private fun updateMid()    { midCoef.set(peakingEq(800f, midDb, 0.7f)) }
    private fun updateTreble() { trebleCoef.set(highShelf(3200f, trebleDb)) }
    private fun updateAll()    { updateBass(); updateMid(); updateTreble() }

    data class BiquadCoef(var b0:Float=1f, var b1:Float=0f, var b2:Float=0f,
                          var a1:Float=0f, var a2:Float=0f) {
        fun set(o: BiquadCoef) { b0=o.b0; b1=o.b1; b2=o.b2; a1=o.a1; a2=o.a2 }
    }
}
