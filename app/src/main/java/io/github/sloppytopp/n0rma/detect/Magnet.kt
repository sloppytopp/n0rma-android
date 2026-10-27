package io.github.sloppytopp.n0rma.detect

import kotlin.math.sqrt

/**
 * Close-range magnetic locator. Electronics (and steel, magnets, speakers) bend the local magnetic field, so the reading
 * rises as the phone gets near them. It only helps you pin down WHERE something is within a few centimetres; it can't tell
 * you what it is, and a quiet reading proves nothing.
 */
class MagMeter(private val alpha: Float = 0.3f) {
    var baseline: Float? = null; private set
    var current: Float = 0f; private set
    var peak: Float = 0f; private set

    fun magnitude(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)

    /** Feed one raw sensor sample (microtesla). Smoothed so the number doesn't flicker. */
    fun update(x: Float, y: Float, z: Float) {
        val m = magnitude(x, y, z)
        current = if (current == 0f) m else current + alpha * (m - current)
        val d = delta
        if (d > peak) peak = d
    }

    /** Take the current reading as "normal here" (hold the phone away from the thing you are checking first). */
    fun setBaseline() { baseline = current; peak = 0f }
    fun clearBaseline() { baseline = null; peak = 0f }

    /** How far above the baseline, in microtesla (never negative). 0 until a baseline is set. */
    val delta: Float get() = baseline?.let { maxOf(0f, current - it) } ?: 0f

    /** 0..1 for the hot/cold bar; ~100 uT above baseline fills it. */
    val fill: Float get() = (delta / FULL_SCALE_UT).coerceIn(0f, 1f)

    val level: MagLevel get() = when {
        baseline == null -> MagLevel.NO_BASELINE
        delta < QUIET_UT -> MagLevel.QUIET
        delta < NEAR_UT -> MagLevel.NOTICEABLE
        else -> MagLevel.STRONG
    }

    companion object {
        const val FULL_SCALE_UT = 100f
        const val QUIET_UT = 5f
        const val NEAR_UT = 25f
    }
}

enum class MagLevel { NO_BASELINE, QUIET, NOTICEABLE, STRONG }
