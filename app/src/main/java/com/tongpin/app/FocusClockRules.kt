package com.tongpin.app

/** Pure time calculations, separate from Android persistence and notification delivery. */
object FocusClockRules {
    fun remaining(
        running: Boolean, storedRemaining: Int, maximum: Int,
        wallDeadline: Long, elapsedDeadline: Long, savedBoot: Int, currentBoot: Int,
        nowWall: Long, nowElapsed: Long,
    ): Int {
        if (!running) return storedRemaining.coerceIn(0, maximum)
        val millis = if (elapsedDeadline > 0 && savedBoot == currentBoot) elapsedDeadline - nowElapsed
            else wallDeadline - nowWall
        if (millis <= 0) return 0
        return ((millis + 999L) / 1000L).coerceAtMost(maximum.toLong()).toInt()
    }

    fun completedAt(elapsedDeadline: Long, nowElapsed: Long, nowWall: Long): Long =
        (nowWall - (nowElapsed - elapsedDeadline).coerceAtLeast(0)).coerceAtLeast(1)
}
