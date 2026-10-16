package io.github.sloppytopp.n0rma.ui

import androidx.compose.runtime.mutableStateMapOf

/** Real compass bearings measured with the turn-in-place Finder, kept briefly: they are only valid from where you stood. */
object Bearings {
    const val MAX_AGE_MS = 10 * 60_000L
    class Measured(val deg: Double, val atMs: Long)
    private val map = mutableStateMapOf<String, Measured>()

    fun put(key: String, deg: Double, now: Long = System.currentTimeMillis()) { map[key] = Measured(deg, now) }
    fun get(key: String, now: Long = System.currentTimeMillis()): Double? =
        map[key]?.takeIf { now - it.atMs <= MAX_AGE_MS }?.deg
    fun clear() = map.clear()
}
