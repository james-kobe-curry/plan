package com.tongpin.app

import java.time.LocalDate

data class RecordsDay(val date: LocalDate, val completed: Int, val scheduled: Int)
data class RecentRecord(val entry: CheckIn, val plan: Plan?, val date: LocalDate, val canEdit: Boolean)
data class RecordsOverview(
    val days: List<RecordsDay>,
    val completed: Int,
    val scheduled: Int,
    val checkInDays: Int,
    val focusSeconds: Long,
    val recent: List<RecentRecord>,
)

/** A single record scan replaces seven repeated plan-by-record searches on the records screen. */
fun recordsOverview(data: AppData, today: LocalDate): RecordsOverview {
    val dates = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val amounts = dates.associate { it.toString() to HashMap<String, Int>() }
    val positiveDates = HashSet<String>()
    val recent = ArrayList<CheckIn>(6)
    data.checkIns.forEach { entry ->
        // Keep amountFor's first matching entry semantics, including a zero first entry.
        amounts[entry.date]?.putIfAbsent(entry.planId, entry.amount)
        if (entry.amount > 0) {
            positiveDates.add(entry.date)
            // Keep equal entries in their original order, like sortedWith(...).take(5).
            val before = recent.indexOfFirst { historyCheckInOrder.compare(entry, it) < 0 }
            if (before >= 0) recent.add(before, entry) else if (recent.size < 5) recent.add(entry)
            if (recent.size > 5) recent.removeAt(5)
        }
    }
    val days = dates.map { date ->
        val dailyAmounts = amounts.getValue(date.toString())
        var completed = 0
        var scheduled = 0
        effectivePlansForDate(data, date).forEach { plan ->
            if (isScheduled(plan, date)) {
                scheduled++
                if ((dailyAmounts[plan.id] ?: 0) >= plan.target) completed++
            }
        }
        RecordsDay(date, completed, scheduled)
    }
    val plansById = HashMap<String, Plan>()
    data.plans.forEach { plansById.putIfAbsent(it.id, it) }
    return RecordsOverview(
        days = days,
        completed = days.sumOf { it.completed },
        scheduled = days.sumOf { it.scheduled },
        checkInDays = positiveDates.size,
        focusSeconds = data.focusRecords.sumOf { it.seconds.toLong() },
        recent = recent.map { entry ->
            val plan = plansById[entry.planId]
            val date = LocalDate.parse(entry.date)
            RecentRecord(entry, plan, date, plan != null && date <= today && isScheduled(plan, date))
        },
    )
}
