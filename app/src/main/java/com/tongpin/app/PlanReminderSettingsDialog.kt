package com.tongpin.app

import android.Manifest
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable fun PlanReminderSettingsDialog(data: AppData, onDismiss: () -> Unit) {
    val context=LocalContext.current
    var revision by remember{mutableIntStateOf(0)}
    var messages by remember{mutableStateOf(emptyList<String>())}
    var allowed by remember{mutableStateOf(false)}
    var exact by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    fun safely(action:()->Unit){try{action();error=""}catch(e:Exception){error=e.message?:"暂时无法打开设置"}}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){
        revision++;PlanReminderScheduler.reschedule(context)
    }
    DisposableEffect(context){
        val lifecycle=(context as? ComponentActivity)?.lifecycle
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)revision++}
        lifecycle?.addObserver(observer)
        onDispose{lifecycle?.removeObserver(observer)}
    }
    LaunchedEffect(revision){safely{
        messages=PlanReminderScheduler.status(context)
        allowed=PlanReminderScheduler.notificationsAllowed(context)
        exact=PlanReminderScheduler.canScheduleExact(context)
        PlanReminderScheduler.reschedule(context)
    }}
    val count=data.plans.count{!it.archived&&!it.recordsOnly&&it.reminderTime!=null}
    FormDialog(title="计划提醒",subtitle="$count 项任务设置了提醒时间",error=error,
        onDismiss=onDismiss,confirmLabel="完成",onConfirm=onDismiss){
        Text("在任务编辑页选填提醒时间，到时按该任务的日期和重复安排通知。当天已完成、跳过、暂停或到期的任务不会继续提醒。")
        Text("通知支持“15 分钟后”和“今天不再提醒”。这些操作只调整提醒，不会修改打卡进度。",color=Muted)
        Text(if(messages.isEmpty())"提醒设置已就绪。"else messages.joinToString("\n"),color=if(messages.isEmpty())Teal else Muted)
        if(!allowed)Button({safely{
            if(Build.VERSION.SDK_INT>=33)permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else PlanReminderScheduler.openSettings(context)
        }},Modifier.fillMaxWidth()){Text("允许通知")}
        OutlinedButton({safely{PlanReminderScheduler.openSettings(context)}},Modifier.fillMaxWidth()){Text("任务通知设置")}
        if(!exact)OutlinedButton({safely{PlanReminderScheduler.openExactSettings(context)}},Modifier.fillMaxWidth()){Text("允许准时提醒")}
        Text("任务提醒遵循系统的声音与勿扰设置。临时设置已经过去的时间，会从下一次有效安排开始提醒。",style=MaterialTheme.typography.bodySmall,color=Muted)
    }
}
