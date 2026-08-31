package com.sih.itantra.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Signal-strength maths shared by the on-screen level meter and the energy VAD gate.
 *
 * Everything is expressed in dBFS (decibels relative to full scale): 0 dBFS is a sample at the
 * 16-bit maximum, and quieter signals are negative. Speech at a sensible mic distance lands
 * around -30 to -15 dBFS; a quiet room floor sits near -55.
 */
object AudioLevel {

    /** Reported for digital silence, where the true value would be negative infinity. */
    const val SILENCE_DBFS = -100f

    private const val FULL_SCALE = 32768.0

    fun rms(frame: ShortArray, count: Int = frame.size): Double {
        if (count <= 0) return 0.0
        var sumOfSquares = 0.0
        for (i in 0 until count) {
            val s = frame[i].toDouble()
            sumOfSquares += s * s
        }
        return sqrt(sumOfSquares / count)
    }

    fun dbfs(frame: ShortArray, count: Int = frame.size): Float {
        val r = rms(frame, count)
        if (r <= 0.0) return SILENCE_DBFS
        val db = 20.0 * log10(r / FULL_SCALE)
        return db.toFloat().coerceAtLeast(SILENCE_DBFS)
    }

    /**
     * Map a dBFS reading onto 0f..1f for a progress bar. [floorDb] is the bottom of the scale;
     * anything at or below it reads as empty.
     */
    fun normalized(dbfs: Float, floorDb: Float = -60f): Float {
        if (dbfs <= floorDb) return 0f
        return ((dbfs - floorDb) / -floorDb).coerceIn(0f, 1f)
    }
}
