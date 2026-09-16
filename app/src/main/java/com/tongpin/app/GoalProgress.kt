package com.tongpin.app

import java.math.BigDecimal
import java.math.RoundingMode

fun totalGoalProgress(data:AppData,plan:Plan):Pair<String,Float>{
    val versions=data.plans.filter{it.seriesId==plan.seriesId&&it.unit.trim().equals(plan.unit.trim(),ignoreCase=true)&&it.tracking==TrackingMode.QUANTITY}.associateBy{it.id}
    val sum=data.checkIns.fold(BigDecimal.ZERO){value,entry->
        val version=versions[entry.planId]
        if(version==null)value else value.add(BigDecimal.valueOf(entry.amount.toLong(),version.scale))
    }
    val target=BigDecimal.valueOf((plan.totalTarget?:plan.target).toLong(),plan.scale)
    return sum.stripTrailingZeros().toPlainString() to (if(target.signum()<=0)0f else sum.divide(target,6,RoundingMode.HALF_UP).toFloat().coerceIn(0f,1f))
}
