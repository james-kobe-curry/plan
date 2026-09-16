package com.tongpin.app

import java.util.Locale

const val MAX_FOCUS_MINUTES = DomainValidation.MAX_FOCUS_SECONDS / 60

/** A null duration keeps the current selection; a message asks for an explicit replacement. */
data class PlanFocusDuration(val minutes: Int? = null, val message: String? = null)

/** Custom durations accept whole minutes, including pasted numbers with surrounding whitespace. */
fun parseFocusMinutes(value: String): Int? {
    val number = value.trim()
    if (number.isEmpty() || number.any { it !in '0'..'9' }) return null
    return number.toIntOrNull()?.takeIf { it in 1..MAX_FOCUS_MINUTES }
}

/** Uses the plan's complete time target, independent of check-in progress. */
fun planFocusDuration(plan: Plan?): PlanFocusDuration {
    if (plan == null || plan.tracking == TrackingMode.TASK) return PlanFocusDuration()
    val target = plan.target.toLong()
    val divisor = when (plan.scale) { 0 -> 1L; 2 -> 100L; else -> return PlanFocusDuration(message = "计划时长无效，请自定义本次时长") }
    val minutes = when (plan.unit.trim().lowercase(Locale.ROOT)) {
        "分钟", "分", "min", "minute", "minutes" -> (target + divisor - 1L) / divisor
        "小时", "时", "h", "hour", "hours" -> (target * 60L + divisor - 1L) / divisor
        "秒", "秒钟", "s", "sec", "second", "seconds" -> (target + 60L * divisor - 1L) / (60L * divisor)
        else -> return PlanFocusDuration()
    }
    if (target <= 0) {
        return PlanFocusDuration(message = "计划时长无效，请自定义本次时长")
    }
    if (minutes !in 1L..MAX_FOCUS_MINUTES.toLong()) {
        return PlanFocusDuration(message = "计划时长超过 $MAX_FOCUS_MINUTES 分钟，请自定义本次时长")
    }
    return PlanFocusDuration(minutes = minutes.toInt())
}
