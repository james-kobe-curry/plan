package com.tongpin.app

import java.security.MessageDigest
import java.time.LocalDate

enum class CheckInAmountMode { ADD, TOTAL }

/** Amount remains an increment until this request runs inside AppStore.update. */
data class CheckInSubmission(
    val operationId: String,
    val mode: CheckInAmountMode,
    val amount: Int,
    val note: String,
    val baselinePlanFingerprint: String,
    val baselineEntryFingerprint: String,
)

internal fun draftHash(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

fun draftPlanFingerprint(plan: Plan): String = draftHash(DataCodec.planFingerprint(plan))

fun draftCheckInFingerprint(data: AppData, planId: String, date: LocalDate): String {
    val entry = data.checkIns.firstOrNull { it.planId == planId && it.date == date.toString() }
    return draftHash(DataCodec.writeJsonObject(linkedMapOf(
        "planId" to planId, "date" to date.toString(), "amount" to entry?.amount,
        "note" to entry?.note, "updatedAt" to entry?.updatedAt,
    )))
}

fun checkInResultAmount(previous: Int, amount: Int, mode: CheckInAmountMode, plan: Plan): Int {
    val maximum = DomainValidation.maxAmount(plan.scale)
    require(previous in 0..maximum && amount in 0..maximum) { "打卡数量超出支持范围" }
    require(plan.tracking != TrackingMode.TASK || mode == CheckInAmountMode.TOTAL) { "普通任务只需记录完成状态" }
    require(mode != CheckInAmountMode.ADD || amount > 0) { "请填写大于 0 的本次新增数量" }
    val result = if (mode == CheckInAmountMode.ADD) previous.toLong() + amount.toLong() else amount.toLong()
    require(result <= maximum) { "保存后的当天总量超出支持范围，请检查本次数量" }
    require(plan.tracking != TrackingMode.TASK || result in 0L..1L) { "普通任务只能记录待完成或已完成" }
    return result.toInt()
}

/** A changed baseline is a conflict, including an already committed retry of an increment. */
fun applyCheckInSubmission(data: AppData, planId: String, date: LocalDate, submission: CheckInSubmission): AppData {
    DomainValidation.id(submission.operationId)
    val plan = requireNotNull(data.plans.firstOrNull { it.id == planId }) { "找不到这个计划，请重新打开打卡" }
    require(draftPlanFingerprint(plan) == submission.baselinePlanFingerprint) { "任务已发生变化，请重新打开打卡；本次输入仍保留在草稿中" }
    require(draftCheckInFingerprint(data, planId, date) == submission.baselineEntryFingerprint) { "当天记录已发生变化，请重新打开确认；本次输入仍保留在草稿中" }
    val result = checkInResultAmount(amountFor(data, planId, date), submission.amount, submission.mode, plan)
    return setCheckIn(data, planId, date, result, submission.note)
}
