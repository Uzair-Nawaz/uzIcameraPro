package com.uzicamera.pro.camera

import java.util.concurrent.TimeUnit

/** Utility helpers for camera operations */
object CameraUtils {

    /** Formats elapsed recording seconds into MM:SS string */
    fun formatDuration(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return "%02d:%02d".format(m, s)
    }

    /** Converts nanoseconds to seconds */
    fun nanosToSeconds(nanos: Long): Int =
        TimeUnit.NANOSECONDS.toSeconds(nanos).toInt()

    /** Clamps a float value between min and max */
    fun clamp(value: Float, min: Float, max: Float): Float =
        value.coerceIn(min, max)
}
