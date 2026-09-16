package com.tongpin.app

import org.junit.Assert.*
import org.junit.Test

class FocusDurationTest {
    private val plan = Plan(id = "study-time", title = "学习", category = Category.STUDY, target = 30, unit = "分钟")

    @Test fun decimalHoursConvertStoredHundredthsToMinutes() {
        assertEquals(90, planFocusDuration(plan.copy(target = 150, unit = "小时", scale = 2)).minutes)
        assertEquals(1, planFocusDuration(plan.copy(target = 1, unit = "小时", scale = 2)).minutes)
        assertEquals(1440, planFocusDuration(plan.copy(target = 2400, unit = "小时", scale = 2)).minutes)
        assertNull(planFocusDuration(plan.copy(target = 2401, unit = "小时", scale = 2)).minutes)
    }

    @Test fun ordinaryTaskNeverSetsDuration() {
        assertEquals(PlanFocusDuration(), planFocusDuration(plan.copy(target = 1, unit = "次", tracking = TrackingMode.TASK)))
    }

    @Test fun customMinutesAcceptIntegersWithinRecordBounds() {
        assertEquals(1, parseFocusMinutes("1"))
        assertEquals(25, parseFocusMinutes("25"))
        assertEquals(45, parseFocusMinutes(" 45 "))
        assertEquals(30, parseFocusMinutes("030"))
        assertEquals(MAX_FOCUS_MINUTES, parseFocusMinutes(MAX_FOCUS_MINUTES.toString()))
        assertEquals(DomainValidation.MAX_FOCUS_SECONDS, MAX_FOCUS_MINUTES * 60)
    }

    @Test fun customMinutesRejectEmptyFractionsSignsUnitsAndOverflow() {
        listOf("", " ", "0", "-1", "+25", "1.5", "25.0", "1e2", "25分钟", "2 5", "２５", "999999999999999999999999", (MAX_FOCUS_MINUTES + 1).toString()).forEach {
            assertNull("Should reject: $it", parseFocusMinutes(it))
        }
    }

    @Test fun minutePlansUseTheFullTargetForEverySupportedAlias() {
        listOf("分钟", "分", "min", "minute", "minutes", " MIN ", "Minutes").forEach {
            assertEquals("Unit: $it", PlanFocusDuration(minutes = 30), planFocusDuration(plan.copy(unit = it)))
        }
    }

    @Test fun hourPlansConvertToWholeMinutes() {
        listOf("小时", "时", "h", "hour", "hours", " HOUR ").forEach {
            assertEquals("Unit: $it", PlanFocusDuration(minutes = 120), planFocusDuration(plan.copy(target = 2, unit = it)))
        }
    }

    @Test fun secondPlansRoundUpToAllowAtLeastOneMinute() {
        listOf("秒", "秒钟", "s", "sec", "second", "seconds", " SEC ").forEach {
            assertEquals("Unit: $it", PlanFocusDuration(minutes = 1), planFocusDuration(plan.copy(target = 30, unit = it)))
        }
        assertEquals(1, planFocusDuration(plan.copy(target = 1, unit = "秒")).minutes)
        assertEquals(1, planFocusDuration(plan.copy(target = 60, unit = "秒")).minutes)
        assertEquals(2, planFocusDuration(plan.copy(target = 61, unit = "秒")).minutes)
        assertEquals(2, planFocusDuration(plan.copy(target = 120, unit = "秒")).minutes)
    }

    @Test fun nonTimeTasksAndFreeFocusPreserveTheCurrentDuration() {
        assertEquals(PlanFocusDuration(), planFocusDuration(null))
        listOf("页", "个", "次", "公里", "组", "minute/page", "").forEach {
            assertEquals("Unit: $it", PlanFocusDuration(), planFocusDuration(plan.copy(unit = it)))
        }
    }

    @Test fun maximumTimeTargetIsAcceptedInEveryTimeScale() {
        assertEquals(MAX_FOCUS_MINUTES, planFocusDuration(plan.copy(target = MAX_FOCUS_MINUTES)).minutes)
        assertEquals(MAX_FOCUS_MINUTES, planFocusDuration(plan.copy(target = 24, unit = "小时")).minutes)
        assertEquals(MAX_FOCUS_MINUTES, planFocusDuration(plan.copy(target = DomainValidation.MAX_FOCUS_SECONDS, unit = "秒")).minutes)
    }

    @Test fun excessiveTargetsRequireCustomDurationInsteadOfClampingOrOverflow() {
        listOf(
            plan.copy(target = MAX_FOCUS_MINUTES + 1),
            plan.copy(target = 25, unit = "小时"),
            plan.copy(target = DomainValidation.MAX_FOCUS_SECONDS + 1, unit = "秒"),
            plan.copy(target = Int.MAX_VALUE, unit = "小时"),
        ).forEach {
            val result = planFocusDuration(it)
            assertNull(result.minutes)
            assertTrue(result.message?.contains("自定义") == true)
        }
    }

    @Test fun invalidTimeTargetsNeverProduceAUsableDuration() {
        listOf("分钟", "小时", "秒").forEach { unit ->
            listOf(0, -1, Int.MIN_VALUE).forEach { target ->
                val result = planFocusDuration(plan.copy(target = target, unit = unit))
                assertNull(result.minutes)
                assertTrue(result.message?.isNotBlank() == true)
            }
        }
    }
}
