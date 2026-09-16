package com.tongpin.app

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Device-local delivery state; dates are task dates in the current device time zone. */
internal data class PlanReminderDay(
    val seriesId: String, val date: String, val handled: Boolean = false,
    val dismissed: Boolean = false, val snoozeAt: Long? = null, val deliveredAt: Long? = null,
    val deliveryConfiguration: String? = null,
)

internal data class PlanReminderEvent(val planId: String, val seriesId: String, val date: String, val at: Long, val snoozed: Boolean = false)

internal object PlanReminderRules {
    fun key(series: String, date: String): String = "$series@$date"
    // A successor becoming effective at midnight is not a user changing its time.
    // Keep the daily state attached to the task series through version boundaries.
    fun configuration(plan: Plan): String = "${plan.seriesId}|${plan.reminderTime}"
    fun configurationMatches(stored: String?, plan: Plan): Boolean =
        stored == configuration(plan) || stored == "${plan.id}|${plan.reminderTime}"

    /** One active owner controls a series; only explicitly superseded versions may finish today. */
    private class Timeline(data: AppData, private val today: LocalDate) {
        private val order = compareBy<Plan> { it.startDate }.thenBy { it.id }
        val owners = data.plans.asSequence().filter { !it.archived && !it.recordsOnly }
            .groupBy { it.seriesId }.mapValues { (_, plans) -> plans.maxWith(order) }
        private val relays = data.plans.asSequence().filter { plan ->
            val owner = owners[plan.seriesId]
            plan.archived && plan.superseded && !plan.recordsOnly && owner != null &&
                owner.startDate > today.toString() && inPeriod(plan, today)
        }.groupBy { it.seriesId }.mapValues { (_, plans) -> plans.maxWith(order) }
        val plans: List<Plan> get() = owners.values.toList() + relays.values
        fun current(series: String): Plan? = relays[series] ?: owners[series]
        fun eligible(plan: Plan, date: LocalDate): Boolean {
            val owner = owners[plan.seriesId] ?: return false
            if (owner.reminderTime == null || paused(owner, date)) return false
            val effective = if (date == today) current(plan.seriesId) else owner
            return effective?.id == plan.id && plan.reminderTime != null && isScheduled(plan, date)
        }
    }

    private fun inPeriod(plan: Plan, date: LocalDate): Boolean = date.toString() >= plan.startDate &&
        plan.endDate?.let { date.toString() < it } != false && plan.dueDate?.let { date.toString() <= it } != false
    private fun paused(plan: Plan, date: LocalDate): Boolean = plan.pauses.any {
        date.toString() >= it.startDate && (it.endDate == null || date.toString() < it.endDate)
    }

    /** Configuration follows today's effective version, not a future successor's new identity. */
    fun configurationPlans(data: AppData, date: LocalDate): List<Plan> = Timeline(data, date).let { timeline ->
        timeline.owners.keys.mapNotNull(timeline::current)
    }

    fun eligible(data: AppData, plan: Plan, date: LocalDate): Boolean =
        Timeline(data, date).eligible(plan, date) && amountFor(data, plan.id, date) < plan.target

    fun acceptsDeliveryConfiguration(state: PlanReminderDay, plan: Plan): Boolean =
        state.deliveryConfiguration == null || state.deliveryConfiguration == configuration(plan)

    fun configuredState(data: AppData, plan: Plan, previous: PlanReminderDay?, changed: Boolean, now: Long, zone: ZoneId): PlanReminderDay? {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var state = previous
        if (changed) {
            state = state?.let { existing -> existing.copy(snoozeAt = null,
                // Older delivery state has no configuration. Once edited, its old
                // notification actions must not control the revised reminder.
                deliveryConfiguration = existing.deliveryConfiguration ?: if (existing.deliveredAt != null) "" else null) }
            if (eligible(data, plan, today)) {
                if (at(plan, today, zone) < now) state = (state ?: PlanReminderDay(plan.seriesId, today.toString())).copy(handled = true)
                else if (state?.deliveredAt == null && state?.dismissed != true) state = state?.copy(handled = false)
            }
        }
        val snoozeAt = state?.snoozeAt
        if (snoozeAt != null && (!eligible(data, plan, today) || Instant.ofEpochMilli(snoozeAt).atZone(zone).toLocalDate() != today))
            state = state?.copy(snoozeAt = null)
        return state
    }

    /** DST gaps move to the next real clock time; overlap dates still get one reminder. */
    fun at(plan: Plan, date: LocalDate, zone: ZoneId): Long =
        date.atTime(LocalTime.parse(requireNotNull(plan.reminderTime))).atZone(zone).toInstant().toEpochMilli()

    /** Find one upcoming event per task, jumping over pause ranges and years before a start date. */
    fun candidates(data: AppData, days: Map<String, PlanReminderDay>, now: Long, zone: ZoneId, minimum: Long = now): List<PlanReminderEvent> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val timeline = Timeline(data, today)
        val amounts = data.checkIns.filter { it.date == today.toString() }.associate { it.planId to it.amount }
        return timeline.plans.asSequence().filter { it.reminderTime != null && timeline.owners[it.seriesId]?.reminderTime != null }.mapNotNull { plan ->
            val state = days[key(plan.seriesId, today.toString())]
            val snooze = state?.snoozeAt?.takeIf { !state.dismissed && acceptsDeliveryConfiguration(state, plan) && it >= minimum && timeline.eligible(plan, today) && (amounts[plan.id] ?: 0) < plan.target &&
                Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == today }
                ?.let { PlanReminderEvent(plan.id, plan.seriesId, today.toString(), it, true) }
            var date = maxOf(today, LocalDate.parse(plan.startDate))
            val limit = minOf(plan.dueDate?.let(LocalDate::parse) ?: LocalDate.of(9999, 12, 31),
                plan.endDate?.let { LocalDate.parse(it).minusDays(1) } ?: LocalDate.of(9999, 12, 31))
            val skips = plan.skips.mapTo(hashSetOf()) { it.date }
            var pauseIndex = 0
            var regular: PlanReminderEvent? = null
            while (date <= limit) {
                while (pauseIndex < plan.pauses.size && plan.pauses[pauseIndex].endDate?.let { it <= date.toString() } == true) pauseIndex++
                val pause = plan.pauses.getOrNull(pauseIndex)
                if (pause != null && pause.startDate <= date.toString()) {
                    if (pause.endDate == null) break
                    date = LocalDate.parse(pause.endDate); continue
                }
                val day = days[key(plan.seriesId, date.toString())]
                if (date.dayOfWeek.value in plan.weekdays && date.toString() !in skips && day?.handled != true && day?.dismissed != true &&
                    timeline.eligible(plan, date) && (date != today || (amounts[plan.id] ?: 0) < plan.target)) {
                    val instant = at(plan, date, zone)
                    if (instant >= minimum) { regular = PlanReminderEvent(plan.id, plan.seriesId, date.toString(), instant); break }
                }
                if (date == LocalDate.of(9999, 12, 31)) break
                date = date.plusDays(1)
            }
            listOfNotNull(snooze, regular).minByOrNull { it.at }
        }.sortedWith(compareBy<PlanReminderEvent> { it.at }.thenBy { it.seriesId }).distinctBy { it.seriesId }.toList()
    }

    fun action(data: AppData, state: PlanReminderDay?, event: PlanReminderEvent, now: Long, zone: ZoneId, snooze: Boolean): PlanReminderDay? {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val plan = data.plans.find { it.id == event.planId && it.seriesId == event.seriesId } ?: return null
        if (state == null || event.date != today.toString() || state.date != event.date || state.seriesId != event.seriesId ||
            state.deliveredAt != event.at || state.dismissed || state.snoozeAt != null ||
            !acceptsDeliveryConfiguration(state, plan) || !eligible(data, plan, today)) return null
        val next = now + 15 * 60_000L
        // A rest-of-day action never creates an unexpected reminder for tomorrow's task.
        return if (snooze && Instant.ofEpochMilli(next).atZone(zone).toLocalDate() == today)
            state.copy(handled = true, snoozeAt = next)
        else state.copy(handled = true, dismissed = true, snoozeAt = null)
    }
}
