package com.tongpin.app

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable fun FocusSettingsDialog(settings:FocusReminderSettings,permissionRevision:Int,notifications:FocusNotifications,onDismiss:()->Unit,onChange:(FocusReminderSettings)->Unit){
    val context=LocalContext.current
    val clipboard=LocalClipboardManager.current
    val library=remember{ReminderSoundLibrary(context)}
    val preview=remember{ReminderPreview(context)}
    val scope=rememberCoroutineScope()
    var current by remember{mutableStateOf(settings)}
    var sounds by remember{mutableStateOf(emptyList<ReminderSound>())}
    var problems by remember{mutableStateOf(emptyList<String>())}
    var allowed by remember{mutableStateOf(notifications.notificationsAllowed())}
    var exact by remember{mutableStateOf(notifications.canScheduleExactAlarms())}
    var revision by remember{mutableIntStateOf(0)}
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    var notice by remember{mutableStateOf("")}
    var playingId by remember{mutableStateOf<String?>(null)}
    var playRequest by remember{mutableIntStateOf(0)}
    var deleting by remember{mutableStateOf<ReminderSound?>(null)}
    var lastTest by remember{mutableLongStateOf(0L)}
    var cooldown by remember{mutableStateOf(false)}
    var showDiagnostics by remember{mutableStateOf(false)}
    var diagnosticText by remember{mutableStateOf("")}
    var copied by remember{mutableStateOf(false)}
    fun stopPreview(){playRequest++;preview.stop();playingId=null}
    fun refresh(){revision++}
    fun change(value:FocusReminderSettings):Boolean{
        stopPreview()
        return try{onChange(value);current=notifications.settings();error="";notice="";refresh();true}
        catch(e:Exception){error=e.message?:"设置未能保存，请重试";false}
    }
    fun play(sound:ReminderSound){
        if(playingId==sound.id){stopPreview();return}
        notifications.stopSound()
        stopPreview();val request=playRequest;playingId=sound.id;error="";notice="试听使用媒体音量，最多播放 10 秒"
        scope.launch{
            try{
                val uri=withContext(Dispatchers.IO){library.uri(sound)}
                if(playRequest==request)preview.play(uri,{if(playRequest==request)playingId=null},{if(playRequest==request)error=it})
            }catch(e:CancellationException){throw e}catch(e:Exception){if(playRequest==request){playingId=null;error=e.message?:"无法试听这个提示音"}}
        }
    }
    LaunchedEffect(settings){current=settings}
    LaunchedEffect(permissionRevision,revision){
        try{
            sounds=withContext(Dispatchers.IO){library.list()}
            current=notifications.settings();problems=notifications.soundStatus()
            allowed=notifications.notificationsAllowed();exact=notifications.canScheduleExactAlarms()
        }catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"无法读取提醒设置"}
    }
    LaunchedEffect(lastTest){if(lastTest>0L){cooldown=true;delay((10_000L-(SystemClock.elapsedRealtime()-lastTest)).coerceAtLeast(0L));cooldown=false}}
    LaunchedEffect(showDiagnostics){
        if(showDiagnostics)while(true){
            diagnosticText=runCatching{notifications.diagnosticReport()}.getOrElse{"暂时无法读取诊断，请重新打开。"}
            delay(1000L)
        }
    }
    DisposableEffect(context){
        val lifecycle=(context as? ComponentActivity)?.lifecycle
        val observer=LifecycleEventObserver{_,event->
            if(event==Lifecycle.Event.ON_STOP)stopPreview()
            if(event==Lifecycle.Event.ON_RESUME)refresh()
        }
        lifecycle?.addObserver(observer)
        onDispose{stopPreview();lifecycle?.removeObserver(observer)}
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){refresh()}
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null&&!busy){busy=true;stopPreview();error="";notice="正在检查并保存音频…"
            scope.launch{
                try{
                    val sound=withContext(Dispatchers.IO){library.importAudio(uri)}
                    notice=if(change(current.copy(soundId=sound.id)))"已保存并选用「${sound.name}」" else "音频已保存，请重新选择"
                }catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"音频导入失败，请选择其他文件";notice=""}
                finally{busy=false;refresh()}
            }
        }
    }
    val selected=sounds.firstOrNull{it.id==current.soundId}?:sounds.firstOrNull()
    PlanDialog(onDismissRequest={if(!busy)onDismiss()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
        Surface(Modifier.fillMaxSize(),color=Paper){
            Column(Modifier.safeDrawingPadding()){
                Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically){
                    IconButton({if(!busy)onDismiss()},enabled=!busy){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回")}
                    Text("专注提醒",Modifier.weight(1f),fontSize=21.sp,fontWeight=FontWeight.Bold)
                    TextButton(onDismiss,enabled=!busy){Text("完成")}
                }
                LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(20.dp,8.dp,20.dp,24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    item{Surface(color=Hero,shape=RoundedCornerShape(24.dp)){
                        Column(Modifier.fillMaxWidth().padding(22.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                            Text("为每一次完成，留一个回响",color=OnHero,fontSize=13.sp)
                            Text(selected?.name?:"清晨铃",color=OnHero,fontSize=27.sp,fontWeight=FontWeight.Bold)
                            Text(if(!current.enabled)"完成提醒已关闭" else if(!current.sound)"声音已关闭 · 可继续选择提示音" else "当前完成提示音",color=OnHero,fontSize=13.sp)
                        }
                    }}
                    item{Surface(color=SurfaceColor,shape=RoundedCornerShape(20.dp)){
                        Column(Modifier.padding(horizontal=18.dp,vertical=8.dp)){
                            ReminderToggle("完成提醒",current.enabled,!busy){change(current.copy(enabled=it))}
                            ReminderToggle("提示声音",current.sound,current.enabled&&!busy){change(current.copy(sound=it))}
                            ReminderToggle("振动",current.vibrate,current.enabled&&!busy){change(current.copy(vibrate=it))}
                            HorizontalDivider(Modifier.padding(vertical=8.dp),color=Line)
                            ReminderToggle("兼容播放",current.compatibilitySound,!busy){change(current.copy(compatibilitySound=it))}
                            Text("测试通知没有声音时可使用。由应用播放所选提示音，仍遵循通知音量、静音、勿扰和系统提醒设置。",fontSize=12.sp,color=Muted,modifier=Modifier.padding(bottom=10.dp))
                        }
                    }}
                    item{Surface(color=if(problems.isEmpty())Mint else Apricot,shape=RoundedCornerShape(18.dp)){
                        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
                            Text("提醒状态",fontWeight=FontWeight.Bold)
                            Text(if(problems.isEmpty())"未发现静音设置，可发送测试提醒确认。" else problems.joinToString("\n"),fontSize=13.sp,color=Ink)
                            if(!allowed)TextButton({stopPreview();if(Build.VERSION.SDK_INT>=33)permission.launch(Manifest.permission.POST_NOTIFICATIONS) else notifications.openNotificationSettings()}){Text("允许通知")}
                            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                                TextButton({stopPreview();notifications.stopSound();notice="已停止提醒声音"}){Text("停止提醒声音")}
                                TextButton({stopPreview();copied=false;diagnosticText=notifications.diagnosticReport();showDiagnostics=true}){Text("查看诊断")}
                            }
                        }
                    }}
                    item{Text("内置提示音",Modifier.padding(top=8.dp),fontWeight=FontWeight.Bold,fontSize=17.sp)}
                    items(sounds.filter{!it.custom},key={it.id}){sound->SoundRow(sound,selected?.id==sound.id,playingId==sound.id,!busy,{change(current.copy(soundId=sound.id))},{play(sound)})}
                    item{Row(Modifier.fillMaxWidth().padding(top=10.dp),verticalAlignment=Alignment.CenterVertically){
                        Text("我的提示音",Modifier.weight(1f),fontWeight=FontWeight.Bold,fontSize=17.sp)
                        Text("${sounds.count{it.custom}} / ${SoundRules.MAX_CUSTOM_SOUNDS}",color=Muted,fontSize=13.sp)
                    }}
                    items(sounds.filter{it.custom},key={it.id}){sound->SoundRow(sound,current.soundId==sound.id,playingId==sound.id,!busy,{change(current.copy(soundId=sound.id))},{play(sound)},{stopPreview();deleting=sound})}
                    item{OutlinedButton({stopPreview();error="";importer.launch(arrayOf("audio/*"))},Modifier.fillMaxWidth().heightIn(min=50.dp),enabled=!busy,shape=RoundedCornerShape(15.dp)){
                        Icon(Icons.Outlined.LibraryMusic,null,Modifier.size(19.dp));Spacer(Modifier.width(8.dp));Text(if(busy)"正在导入…" else "导入音频")
                    }}
                    item{Text("支持手机可播放的音频，最长 60 秒、最大 20 MB。导入后保存在应用中，原文件移动也不影响使用。",fontSize=12.sp,color=Muted)}
                    item{HorizontalDivider(Modifier.padding(vertical=8.dp),color=Line)}
                    item{Text("系统设置",fontWeight=FontWeight.Bold,fontSize=17.sp)}
                    item{Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
                        OutlinedButton({stopPreview();notifications.openCompletionSettings()},Modifier.fillMaxWidth(),enabled=!busy){Text("当前提醒设置")}
                        OutlinedButton({stopPreview();notifications.openSoundSettings()},Modifier.fillMaxWidth(),enabled=!busy){Text("系统声音设置")}
                        OutlinedButton({stopPreview();notifications.openNotificationSettings()},Modifier.fillMaxWidth(),enabled=!busy){Text("应用通知设置")}
                        if(!exact)OutlinedButton({stopPreview();notifications.openExactAlarmSettings()},Modifier.fillMaxWidth(),enabled=!busy){Text("允许准时提醒")}
                        Text(if(exact)"准时提醒已允许" else "允许“闹钟与提醒”后，可减少锁屏休眠时的提醒延迟。",fontSize=12.sp,color=Muted)
                    }}
                    item{Text("试听使用媒体音量，最多播放 10 秒；测试提醒使用真实通知设置，不产生专注记录。声音和振动遵循手机的静音、勿扰及通知设置。\n\n导入的提示音可随打包备份恢复，不包含在单独的计划与记录备份里。",fontSize=12.sp,color=Muted)}
                }
                Surface(color=SurfaceColor,shadowElevation=8.dp){
                    Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
                        if(error.isNotEmpty())FormErrorBanner(error,maxHeight=100.dp)
                        else if(notice.isNotEmpty())Text(notice,fontSize=12.sp,color=Muted,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                            OutlinedButton({if(playingId!=null)stopPreview() else selected?.let{play(it)}},Modifier.weight(1f).heightIn(min=48.dp),enabled=!busy&&selected!=null,contentPadding=PaddingValues(8.dp),shape=RoundedCornerShape(14.dp)){Text(if(playingId!=null)"停止试听" else "试听当前")}
                            Button({
                                if(lastTest==0L||SystemClock.elapsedRealtime()-lastTest>=10_000L){
                                    stopPreview();error=""
                                    try{notifications.showTest();lastTest=SystemClock.elapsedRealtime();cooldown=true;notice="测试提醒已发送，请留意声音与振动"}
                                    catch(e:Exception){error=e.message?:"测试提醒未发送，请检查设置"}
                                    refresh()
                                }
                            },Modifier.weight(1f).heightIn(min=48.dp),enabled=!busy&&!cooldown,contentPadding=PaddingValues(8.dp),shape=RoundedCornerShape(14.dp)){Text(if(cooldown)"稍后可再测试" else "发送测试提醒")}
                        }
                    }
                }
            }
        }
    }
    if(showDiagnostics)PlanAlertDialog(onDismissRequest={showDiagnostics=false},title={Text("提醒诊断")},text={
        Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text(diagnosticText,fontSize=13.sp)
            if(copied)Text("已复制诊断信息",color=Teal,fontSize=12.sp)
        }
    },dismissButton={TextButton({clipboard.setText(AnnotatedString(diagnosticText));copied=true}){Text("复制诊断")}},confirmButton={TextButton({showDiagnostics=false}){Text("关闭")}})
    deleting?.let{sound->PlanAlertDialog(onDismissRequest={if(!busy)deleting=null},title={Text("删除提示音？")},text={Text("将删除「${sound.name}」。如果正在使用它，会切换回清晨铃。")},dismissButton={TextButton({deleting=null},enabled=!busy){Text("取消")}},confirmButton={TextButton({
        if(!busy){busy=true;scope.launch{
            try{
                if(current.soundId==sound.id){onChange(current.copy(soundId=ReminderSoundLibrary.defaultId));current=notifications.settings()}
                withContext(Dispatchers.IO){notifications.deleteSoundChannels(sound.id);library.delete(sound.id)}
                error="";notice="提示音已删除"
            }catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"删除失败，请重试"}
            finally{busy=false;deleting=null;refresh()}
        }}
    },enabled=!busy){Text("删除")}})}
}

@Composable private fun SoundRow(sound:ReminderSound,selected:Boolean,playing:Boolean,enabled:Boolean,onSelect:()->Unit,onPlay:()->Unit,onDelete:(()->Unit)?=null){
    Surface(shape=RoundedCornerShape(18.dp),color=if(selected)Mint else SurfaceColor,border=BorderStroke(1.dp,if(selected)Ink.copy(alpha=.18f) else Line)){
        Row(Modifier.fillMaxWidth().clickable(enabled=enabled,onClick=onSelect).padding(start=2.dp,end=6.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically){
            RadioButton(selected,onSelect,enabled=enabled,modifier=Modifier.semantics{contentDescription="选择${sound.name}"})
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){
                Text(sound.name,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis,fontSize=15.sp)
                Text("${String.format(Locale.ROOT,"%.1f",sound.durationMs/1000.0)} 秒 · ${sound.subtitle}",fontSize=11.sp,color=Muted,maxLines=2,overflow=TextOverflow.Ellipsis)
            }
            IconButton(onPlay,enabled=enabled){Icon(if(playing)Icons.Outlined.StopCircle else Icons.Outlined.PlayCircle,if(playing)"停止试听${sound.name}" else "试听${sound.name}")}
            if(onDelete!=null)IconButton(onDelete,enabled=enabled){Icon(Icons.Outlined.DeleteOutline,"删除${sound.name}",tint=Muted)}
        }
    }
}

@Composable private fun ReminderToggle(label:String,checked:Boolean,enabled:Boolean=true,onChange:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(label,modifier=Modifier.weight(1f),fontWeight=FontWeight.SemiBold);Switch(checked,onChange,enabled=enabled,modifier=Modifier.semantics{contentDescription=label})}
}
