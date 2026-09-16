package com.tongpin.app

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDate

@Composable fun SkipDayDialog(plan:Plan,onDismiss:()->Unit,onSave:(String)->Unit){
    var reason by rememberSaveable{mutableStateOf("")}
    var error by rememberSaveable{mutableStateOf("")}
    FormDialog(title="跳过今天",subtitle=plan.title,error=error,onDismiss=onDismiss,confirmLabel="确认跳过",onConfirm={
        try{onSave(reason.trim())}catch(e:Exception){error=e.message?:"暂未保存，请重试"}
    }){
        Text("今天休息一下，不计入未完成；已有历史记录会保留，之后的安排照常。",color=Muted)
        OutlinedTextField(reason,{if(it.length<=DomainValidation.MAX_NOTE_LENGTH){reason=it;error=""}},label={Text("原因（选填）")},minLines=2,maxLines=4,modifier=Modifier.fillMaxWidth())
    }
}

@Composable fun RestorePlanDialog(data:AppData,plan:Plan,onDismiss:()->Unit,onRestore:(LocalDate)->Unit){
    val context=LocalContext.current
    val versions=data.plans.filter{it.seriesId==plan.seriesId&&!it.recordsOnly}
    val ids=versions.map{it.id}.toSet()
    val minimum=maxOf(LocalDate.now(),versions.mapNotNull{p->p.endDate?.takeIf{it>p.startDate}?.let(LocalDate::parse)}.maxOrNull()?:LocalDate.now(),
        data.checkIns.filter{it.planId in ids}.maxOfOrNull{LocalDate.parse(it.date).plusDays(1)}?:LocalDate.now())
    var selected by rememberSaveable{mutableStateOf(minimum.toString())}
    var error by rememberSaveable{mutableStateOf("")}
    FormDialog(title="恢复任务",subtitle=plan.title,error=error,onDismiss=onDismiss,confirmLabel="恢复计划",onConfirm={
        try{val date=LocalDate.parse(selected);require(date>=minimum){"请选择 $minimum 或之后的日期"};onRestore(date)}catch(e:Exception){error=e.message?:"暂未恢复，请重试"}
    }){
        Text("从选择的日期继续安排，之前的打卡、专注和累计进度都会保留。",color=Muted)
        OutlinedButton({val d=LocalDate.parse(selected);DatePickerDialog(context, planDialogTheme(context),{_,y,m,day->selected=LocalDate.of(y,m+1,day).toString();error=""},d.year,d.monthValue-1,d.dayOfMonth).show()},Modifier.fillMaxWidth()){Text("恢复日期：$selected")}
        if(plan.dueDate!=null && plan.dueDate<selected)Text("原截止日期已过，恢复后将不设截止日期，可在编辑中重新设置。",color=Muted)
    }
}
