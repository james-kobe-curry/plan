package com.tongpin.app

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import java.io.ByteArrayOutputStream
import java.time.LocalDate

@Composable fun TongpinApp(focusRequest:Int=0,todayRequest:Int=0) {
    val context=LocalContext.current
    if(PlanRecovery.failure!=null){RecoveryGate();return}
    val store=remember{AppStore(context)}
    var initial by remember{mutableStateOf<AppStoreSnapshot?>(null)}
    LaunchedEffect(store){initial=withContext(Dispatchers.IO){store.loadSnapshot()}}
    val firstLoad=initial
    if(firstLoad==null){
        Surface(Modifier.fillMaxSize(),color=Paper){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator(color=Teal)}}
    }else TongpinContent(focusRequest,todayRequest,store,firstLoad)
}

@Composable private fun TongpinContent(focusRequest:Int,todayRequest:Int,store:AppStore,firstLoad:AppStoreSnapshot){
    val context=LocalContext.current
    var loadError by remember{mutableStateOf(firstLoad.result.exceptionOrNull()?.let{it.message?:"无法读取本地记录，请恢复备份"})}
    var data by remember{mutableStateOf(firstLoad.result.getOrElse{AppData()})}
    var displayedRevision by remember{mutableLongStateOf(firstLoad.revision)}
    val dataOperations=remember{Mutex()}
    val coroutine=rememberCoroutineScope()
    val snack=remember{SnackbarHostState()}
    var noticeGeneration by remember{mutableIntStateOf(0)}
    var issue by remember{mutableStateOf<String?>(null)}
    var busy by remember{mutableStateOf(false)}
    val homeStore=remember{HomePreferencesStore(context)}
    var homePreferences by remember{mutableStateOf(homeStore.load())}
    var tab by rememberSaveable{mutableIntStateOf(homeStore.load().startPage)}
    val pageStates=rememberSaveableStateHolder()
    var today by remember{mutableStateOf(LocalDate.now())}
    var selectedDate by remember{mutableStateOf(today)}
    var editor by remember{mutableStateOf<Plan?>(null)}
    var showEditor by remember{mutableStateOf(false)}
    var checking by remember{mutableStateOf<Pair<Plan,LocalDate>?>(null)}
    var archive by remember{mutableStateOf<Plan?>(null)}
    var detail by remember{mutableStateOf<String?>(null)}
    var share by remember{mutableStateOf(false)}
    var shareDate by remember{mutableStateOf(today)}
    var dailyNotes by rememberSaveable{mutableStateOf(false)}
    var dailyNoteDate by rememberSaveable{mutableStateOf<String?>(null)}
    var about by remember{mutableStateOf(false)}
    var nickname by rememberSaveable{mutableStateOf(false)}
    var history by remember{mutableStateOf(false)}
    var review by rememberSaveable{mutableStateOf(false)}
    var appearance by rememberSaveable{mutableStateOf(false)}
    var homeCustomization by rememberSaveable{mutableStateOf(false)}
    var categoryManager by rememberSaveable{mutableStateOf(false)}
    var planReminders by rememberSaveable{mutableStateOf(false)}
    var backupDue by remember{mutableStateOf(false)}
    val backupReminder=remember{BackupReminderStore(context)}
    var reminderDialog by rememberSaveable{mutableStateOf(false)}
    var undoRestore by remember{mutableStateOf(false)}
    var canUndoRestore by remember{mutableStateOf(firstLoad.canUndoRestore)}
    var dataManager by remember{mutableStateOf(false)}
    var completeBackup by rememberSaveable{mutableStateOf(false)}
    var managerScope by remember{mutableStateOf(TransferScope.ALL)}
    var importScope by rememberSaveable{mutableStateOf(TransferScope.ALL)}
    var pendingBundle by remember{mutableStateOf<TransferBundle?>(null)}
    var importSummary by remember{mutableStateOf("")}
    var importSource by remember{mutableStateOf("")}
    var exportText by remember{mutableStateOf<String?>(null)}
    var exportLabel by remember{mutableStateOf("")}
    var exportNotice by remember{mutableStateOf<String?>(null)}
    val reminders=remember{FocusNotifications(context)}
    var reminderSettings by remember{mutableStateOf(reminders.settings())}
    var permissionRevision by remember{mutableIntStateOf(0)}
    var foreground by remember{mutableStateOf((context as? ComponentActivity)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED)==true)}
    val clock=remember{FocusClock(context)}
    val focusStore=remember{FocusPreferencesStore(context)}
    var focusPreferences by remember{mutableStateOf(focusStore.load())}
    var timer by remember{mutableStateOf(clock.load())}
    var tickRemaining by remember{mutableIntStateOf(clock.remaining(timer))}
    var stopTimer by remember{mutableStateOf(false)}
    DisposableEffect(homeStore){
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener{_,key->
            if(key==null||key in CompleteBackupArchive.homePreferenceKeys)homePreferences=homeStore.load()
        }
        homeStore.prefs.registerOnSharedPreferenceChangeListener(listener)
        homePreferences=homeStore.load()
        onDispose{homeStore.prefs.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    DisposableEffect(focusStore){
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener{_,key->
            if(key==null||key==FocusPreferencesStore.FAVORITES_KEY||key==FocusPreferencesStore.LAST_MINUTES_KEY)
                focusPreferences=focusStore.load()
        }
        focusStore.prefs.registerOnSharedPreferenceChangeListener(listener)
        focusPreferences=focusStore.load()
        onDispose{focusStore.prefs.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    LaunchedEffect(store){
        store.revision.collectLatest{revision->
            if(revision==displayedRevision)return@collectLatest
            val snapshot=withContext(Dispatchers.IO){store.loadSnapshot()}
            // A slow read must not replace a newer commit, including one made by an old Activity.
            if(snapshot.revision>=displayedRevision&&snapshot.revision==store.revision.value){
                displayedRevision=snapshot.revision
                snapshot.result.fold(onSuccess={data=it;loadError=null},onFailure={loadError=it.message?:"无法读取本地记录，请恢复备份"})
                canUndoRestore=snapshot.canUndoRestore
            }
        }
    }
    fun clearNotice(){noticeGeneration++;snack.currentSnackbarData?.dismiss()}
    fun message(s:String){clearNotice();coroutine.launch{snack.showSnackbar(s)}}
    fun safely(action:()->Unit){try{action()}catch(e:Exception){issue=e.message?:"操作未完成，请重试"}}
    suspend fun updateData(transform:(AppData)->AppData):AppData = dataOperations.withLock {
        check(loadError==null){"请先恢复数据"}
        withContext(NonCancellable){
            val committed=withContext(Dispatchers.IO){store.update(transform)}
            committed
        }
    }
    fun changeData(notice:String?=null,onSuccess:()->Unit={},transform:(AppData)->AppData){
        if(busy)return
        busy=true
        coroutine.launch{
            try{updateData(transform);onSuccess();notice?.let(::message)}
            catch(e:CancellationException){throw e}catch(e:Exception){issue="未能保存：${e.message}"}
            finally{busy=false}
        }
    }
    suspend fun updateWithUndo(createUndo:(AppData,AppData)->QuickUndo?,transform:(AppData)->AppData):Pair<AppData,QuickUndo?>{
        var undo:QuickUndo?=null
        // Capture from the same disk transaction that commits the action, not a UI snapshot.
        val saved=updateData{before->transform(before).also{after->undo=createUndo(before,after)}}
        return saved to undo
    }
    fun offerUndo(notice:String,undo:QuickUndo?){
        if(undo==null){message(notice);return}
        clearNotice()
        val generation=noticeGeneration
        coroutine.launch{
            val result=snack.showSnackbar(notice,actionLabel="撤销",withDismissAction=true,duration=SnackbarDuration.Long)
            if(result==SnackbarResult.ActionPerformed&&generation==noticeGeneration){
                // A save may have started in the same frame as this tap.
                snapshotFlow{busy}.first{!it}
                if(generation==noticeGeneration){
                    noticeGeneration++
                    changeData("已撤销"){current->applyQuickUndo(current,undo)}
                }
            }
        }
    }
    fun changeWithUndo(notice:(AppData)->String,createUndo:(AppData,AppData)->QuickUndo?,onSuccess:()->Unit={},transform:(AppData)->AppData){
        if(busy)return
        busy=true
        coroutine.launch{
            try{val(saved,undo)=updateWithUndo(createUndo,transform);onSuccess();offerUndo(notice(saved),undo)}
            catch(e:CancellationException){throw e}catch(e:Exception){issue="未能保存：${e.message}"}
            finally{busy=false}
        }
    }
    fun newPlan(){editor=null;showEditor=true}
    fun editPlan(p:Plan){editor=p;showEditor=true}
    fun openData(scope:TransferScope){managerScope=scope;dataManager=true}
    fun deferBackup(days:Int){coroutine.launch{
        try{withContext(Dispatchers.IO){backupReminder.defer(days)};backupDue=false}
        catch(e:CancellationException){throw e}catch(e:Exception){issue=e.message?:"提醒设置未能保存"}
    }}
    fun resetClock(){val minutes=focusStore.load().lastMinutes;val next=ClockState(minutes=minutes,remaining=minutes*60);clock.save(next,clearPending=true);timer=next;tickRemaining=next.remaining}
    suspend fun isolateDraftsBeforeRestore(){
        withContext(NonCancellable+Dispatchers.IO){DraftStore(context).clearAll()}
    }
    suspend fun replaceData(clearDrafts:Boolean,operation:()->AppData):AppData = dataOperations.withLock {
        clearNotice()
        withContext(NonCancellable){
            if(clearDrafts)isolateDraftsBeforeRestore()
            val restored=withContext(Dispatchers.IO){operation()}
            restored
        }
    }
    fun pauseTask(plan:Plan){
        val date=LocalDate.now()
        changeWithUndo(notice={if(amountFor(it,plan.id,date)>0)"已从明天暂停，今天的记录保留" else "任务已暂停，历史记录保留"},
            createUndo={before,after->pauseUndo(before,after,plan.id)}){pausePlan(it,plan.id,date)}
    }
    fun resumeTask(plan:Plan){changeData("计划已恢复安排"){resumePlan(it,plan.id,LocalDate.now())}}
    fun restoreTask(plan:Plan,date:LocalDate,onSuccess:()->Unit){changeData("已从 $date 恢复，之前的积累保留",onSuccess){restoreArchivedPlan(it,plan.id,date,LocalDate.now())}}
    val notifyPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){
        permissionRevision++;safely{timer=clock.start(clock.load());tickRemaining=clock.remaining(timer)}
    }
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
        val text=exportText
        exportText=null
        if(uri!=null){if(text==null)issue="文件准备状态已失效，请重新导出" else coroutine.launch{
            busy=true
            try{withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri,"wt")?.use{it.write(text.toByteArray(Charsets.UTF_8))}?:error("无法写入文件")};exportNotice="$exportLabel 已保存"}
            catch(e:Exception){issue="导出未完成：${e.message}"}finally{busy=false}
        }}
    }
    fun export(source:AppData,scope:TransferScope,label:String=transferName(scope)){
        if(busy)return
        coroutine.launch{busy=true;try{
            exportText=withContext(Dispatchers.IO){TransferCodec.encode(source,scope)};exportLabel=label
            exporter.launch("plan-${scope.name.lowercase()}-${LocalDate.now()}-${System.currentTimeMillis().toString().takeLast(6)}.plan.json")
        }catch(e:Exception){issue=e.message?:"文件准备失败"}finally{busy=false}}
    }
    fun stageImport(bundle:TransferBundle,scope:TransferScope,source:String){
        coroutine.launch{busy=true;try{
            val preview=withContext(Dispatchers.IO){mergeTransfer(data,bundle,scope)}
            importScope=scope;importSummary=preview.summary;importSource=source;pendingBundle=bundle
        }catch(e:Exception){issue=e.message?:"不能导入这个文件"}finally{busy=false}}
    }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)coroutine.launch{busy=true;try{
            val bundle=withContext(Dispatchers.IO){
                val bytes=context.contentResolver.openInputStream(uri)?.use{input->
                    val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true){val n=input.read(buffer);if(n<0)break;require(output.size()+n<=DataCodec.MAX_BYTES){"文件超过支持的大小"};output.write(buffer,0,n)}
                    output.toByteArray()
                }?:error("无法读取这个文件")
                val text=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                TransferCodec.decode(text)
            }
            val preview=withContext(Dispatchers.IO){mergeTransfer(data,bundle,importScope)}
            importSummary=preview.summary;importSource="所选文件";pendingBundle=bundle
        }catch(e:Exception){issue=e.message?:"无法读取这个文件"}finally{busy=false}}
    }
    fun launchImport(scope:TransferScope){importScope=scope;importer.launch(arrayOf("*/*"))}
    LaunchedEffect(focusRequest){if(focusRequest>0)tab=2}
    LaunchedEffect(todayRequest){if(todayRequest>0){tab=0;selectedDate=LocalDate.now()}}
    LaunchedEffect(data,loadError,permissionRevision,completeBackup){if(loadError==null&&!completeBackup)PlanReminderScheduler.reschedule(context)}
    LaunchedEffect(today,hasBackupContent(data),loadError,permissionRevision,completeBackup,foreground){
        if(loadError==null&&!completeBackup&&foreground){
            try{backupDue=withContext(Dispatchers.IO){AutoSnapshotStore(context).checkpoint(data,today);backupReminder.observe(data).due}}
            catch(e:CancellationException){throw e}catch(e:Exception){issue="自动存档未能更新：${e.message ?: "请稍后重试"}"}
        }
    }
    DisposableEffect(context){
        val activity=context as? ComponentActivity
        val observer=LifecycleEventObserver{_,event->
            if(event==Lifecycle.Event.ON_RESUME){val now=LocalDate.now();if(now!=today){today=now;selectedDate=now};foreground=true;safely{clock.restore();timer=clock.tick();reminderSettings=reminders.settings()};permissionRevision++}
            else if(event==Lifecycle.Event.ON_STOP)foreground=false
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose{activity?.lifecycle?.removeObserver(observer)}
    }
    LaunchedEffect(Unit){
        safely{clock.restore()}
        while(true){try{
            timer=clock.tick();tickRemaining=clock.remaining(timer)
            if(loadError==null&&!busy&&!completeBackup){
                val pending=clock.pendingCompletions()
                if(pending.isNotEmpty()){
                    updateData{current->val existing=current.focusRecords.map{it.id}.toSet();val planIds=current.plans.mapTo(hashSetOf()){it.id};val records=pending.filter{it.id !in existing}.map{event->FocusRecord(event.id,event.planId?.takeIf{id->id in planIds},event.seconds,event.completedAt)};current.copy(focusRecords=current.focusRecords+records)}
                    pending.forEach{clock.acknowledgeCompletion(it.id)}
                }
            }
        }catch(e:CancellationException){throw e}catch(e:Exception){issue="暂未能保存计时：${e.message}";delay(if(e is FocusPersistenceException)30_000 else 5_000)};delay(500)}
    }
    LaunchedEffect(Unit){while(true){delay(15000);val now=LocalDate.now();if(now!=today){today=now;selectedDate=now}}}
    CompositionLocalProvider(LocalPlanSnackbarHost provides snack){
    Scaffold(containerColor=Paper,snackbarHost={SnackbarHost(snack)},bottomBar={
        NavigationBar(containerColor=SurfaceColor,tonalElevation=0.dp){
            listOf("今日" to Icons.Outlined.Today,"计划" to Icons.Outlined.CalendarMonth,"专注" to Icons.Outlined.Timelapse,"记录" to Icons.Outlined.Insights).forEachIndexed{i,(label,icon)->
                NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Icon(icon,label)},label={Text(label)},colors=NavigationBarItemDefaults.colors(selectedIconColor=Teal,selectedTextColor=Teal,indicatorColor=Mint,unselectedIconColor=Muted,unselectedTextColor=Muted))
            }
        }
    }){insets->Column(Modifier.fillMaxSize().padding(insets)){
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically){
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(Teal),contentAlignment=Alignment.Center){Icon(Icons.Outlined.DoneAll,null,tint=OnAccent,modifier=Modifier.size(21.dp))}
            Text("plan",Modifier.padding(start=9.dp),fontWeight=FontWeight.Bold,fontSize=20.sp)
            Spacer(Modifier.weight(1f))
            if(tab==0)PlanQuietButton({appearance=true}){
                Icon(Icons.Outlined.Palette,null,Modifier.size(20.dp));Spacer(Modifier.width(5.dp));Text("换肤")
            }
            IconButton({about=true}){Icon(Icons.Outlined.Info,"使用说明",tint=Muted,modifier=Modifier.size(21.dp))}
        }
        pageStates.SaveableStateProvider(tab){when(tab){
            0->TodayScreen(data,today,selectedDate,{selectedDate=it},::newPlan,{checking=it to selectedDate},{shareDate=selectedDate;share=true},{plan->changeData("已添加「${plan.title}」"){it.copy(plans=it.plans+plan)}},
                onSkip={p,reason,onSuccess->val date=LocalDate.now();changeWithUndo(notice={"今天已跳过，之后的安排照常"},createUndo={before,after->skipUndo(before,after,p.id,date)},onSuccess=onSuccess){skipPlanDay(it,p.id,date,reason)}},
                onUndoSkip={p->changeData("已恢复今天的安排"){undoPlanSkip(it,p.id,LocalDate.now())}},
                onPin={p->changeData(if(p.pinned)"已取消置顶" else "任务已置顶"){setPlanPinned(it,p.id,!p.pinned)}},
                onMove={p,direction,ids->changeData{movePlan(it,p.id,direction,ids)}},
                onCollapse={value->changeData{it.copy(collapseCompleted=value)}},
                backupDue=backupDue,onBackup={completeBackup=true},onDeferBackup=::deferBackup,
                homePreferences=homePreferences,onPersonalize={nickname=true},onDailyNote={dailyNoteDate=selectedDate.toString()},onSortingFinished={message("任务顺序已保存")},sortingSaving=busy)
            1->PlansScreen(data,::newPlan,::editPlan,{archive=it},{openData(TransferScope.PLANS)},{detail=it.seriesId},{plan->changeData("已添加「${plan.title}」"){it.copy(plans=it.plans+plan)}},
                onPause=::pauseTask,onResume=::resumeTask,onRestore=::restoreTask,onManageCategories={categoryManager=true})
            2->FocusScreen(data,timer,if(timer.running)tickRemaining else timer.remaining,{mins->
                check(timer.id.isEmpty()||timer.completed){"请先结束当前专注"}
                require(mins in 1..MAX_FOCUS_MINUTES){"专注时长无效"}
                focusStore.save(focusStore.load().withManualMinutes(mins));focusPreferences=focusStore.load()
                val next=ClockState(planId=timer.planId,minutes=mins,remaining=mins*60);clock.save(next);timer=next
            },{id->
                if(timer.id.isEmpty()||timer.completed)safely{val suggestion=focusMinutesForPlan(data.plans.find{it.id==id},focusStore.load());val minutes=requireNotNull(suggestion.minutes);val next=ClockState(planId=id,minutes=minutes,remaining=minutes*60);clock.save(next);timer=next;suggestion.message?.let{message(it)}}
            },{
                if(timer.completed)safely{val next=ClockState(planId=timer.planId,minutes=timer.minutes,remaining=timer.minutes*60);clock.save(next);timer=next}
                else if(timer.running)safely{timer=clock.pause(timer)}
                else if(Build.VERSION.SDK_INT>=33&&!reminders.notificationsAllowed())notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else safely{timer=clock.start(timer);tickRemaining=clock.remaining(timer)}
            },{stopTimer=true},{tab=0;selectedDate=today},{reminderDialog=true},preferences=focusPreferences,onPreferencesChange={value->
                check(timer.id.isEmpty()||timer.completed){"请先结束当前专注"}
                focusStore.save(value.copy(lastMinutes=focusStore.load().lastMinutes));focusPreferences=focusStore.load()
            })
            3->RecordsScreen(data,today,{shareDate=today;share=true},{nickname=true},{openData(TransferScope.ALL)},{about=true},{history=true},{p,date->checking=p to date},{reminderDialog=true},{tab=1},
                onReview={review=true},onAppearance={appearance=true},onPlanReminders={planReminders=true},
                onProfile={nickname=true},onHomeCustomization={homeCustomization=true},onCategories={categoryManager=true},onDailyNotes={dailyNotes=true})
        }}
    }}
    if(dataManager)DataManagementDialog(data,managerScope,canUndoRestore,{undoRestore=true},{export(data,it)},::launchImport,
        {info,snapshot->stageImport(TransferBundle(TransferScope.ALL,snapshot),TransferScope.ALL,"存档「${info.name}」")},
        {info,snapshot->export(snapshot,TransferScope.ALL,"存档「${info.name}」")},{dataManager=false},onCompleteBackup={completeBackup=true},recoveryOnly=loadError!=null)
    if(completeBackup)CompleteBackupDialog(onDismiss={completeBackup=false},onRestored={_->
        resetClock();reminderSettings=reminders.settings();permissionRevision++
        PlanReminderScheduler.resetAfterRestore(context)
        dataManager=false;detail=null;history=false;tab=3
    },canRestore={val state=clock.load();!busy&&(loadError!=null||((state.id.isEmpty()||state.completed)&&clock.pendingCompletions().isEmpty()))},onBeforeRestore={clearNotice();if(loadError!=null)resetClock();isolateDraftsBeforeRestore();reminders.stopSound();PlanReminderScheduler.cancelAll(context)},onRecoveryRequired={PlanRecovery.failure=it},onExported={backupDue=false},restoreOnly=loadError!=null)
    if(dailyNotes)DailyNotesDialog(data,{dailyNotes=false},{dailyNoteDate=it.toString()})
    dailyNoteDate?.let{day->key(day){
        val date=LocalDate.parse(day)
        val originalText by rememberSaveable(day){mutableStateOf(dailyNoteFor(data,date)?.text)}
        val originalTime by rememberSaveable(day){mutableLongStateOf(dailyNoteFor(data,date)?.updatedAt?:0L)}
        val original=originalText?.let{DailyNote(day,it,originalTime)}
        DailyNoteEditor(date,original,{dailyNoteDate=null}){text->
            check(!busy){"正在保存，请稍候"};busy=true
            try{
                updateData{current->
                    val latest=dailyNoteFor(current,date)
                    check(latest==original||latest?.text==text.trim()||(latest==null&&text.isBlank())){"这一天的心得已发生变化，请关闭后重新打开"}
                    setDailyNote(current,date,text)
                }
                dailyNoteDate=null
                if(text.isNotBlank())message("心得已保存") else if(original!=null)message("心得已删除")
            }finally{busy=false}
        }
    }}
    if(history)HistoryBrowser(data,{history=false},{p,date->checking=p to date})
    if(review)PeriodReviewDialog(data,today,onDismiss={review=false},onHistory={review=false;history=true})
    if(appearance)AppearanceDialog{appearance=false}
    if(homeCustomization)HomeCustomizationDialog(homePreferences,{homeCustomization=false}){value->
        homeStore.save(value);homePreferences=homeStore.load();homeCustomization=false
    }
    if(categoryManager)CategoryManagerDialog(data,{categoryManager=false}){transform->
        check(!busy){"正在保存，请稍候"};busy=true
        try{updateData(transform)}finally{busy=false}
    }
    if(planReminders)PlanReminderSettingsDialog(data,onDismiss={planReminders=false})
    detail?.let{PlanDetailDialog(data,it,{detail=null},::editPlan,{p,date->checking=p to date},onPause=::pauseTask,onResume=::resumeTask,onRestore=::restoreTask)}
    if(showEditor)key(editor?.id){PlanEditor(editor,data,{showEditor=false}){plan->
        check(!busy){"正在保存，请稍候"};busy=true
        try{val old=editor;updateData{current->
            if(old==null)current.copy(plans=current.plans+plan)
            else{val latest=current.plans.find{it.id==old.id};check(latest!=null&&draftPlanFingerprint(latest)==draftPlanFingerprint(old)){"任务已发生变化，请重新打开编辑；本次内容保留在草稿中"};revisePlan(current,old.id,plan,LocalDate.now())}
        };message(if(old==null)"任务已保存" else "任务已保存，历史进度保留")}
        finally{busy=false}
    }}
    checking?.let{(plan,date)->CheckInDialog(plan,data,date,{checking=null}){submission->
        check(!busy){"正在保存，请稍候"};busy=true
        try{val(saved,undo)=updateWithUndo({before,after->checkInUndo(before,after,plan.id,date)}){applyCheckInSubmission(it,plan.id,date,submission)};offerUndo(if(amountFor(saved,plan.id,date)>=plan.target)"完成啦，又前进了一点" else "进度已保存",undo)}
        finally{busy=false}
    }}
    archive?.let{p->PlanAlertDialog(onDismissRequest={archive=null},title={Text("归档这个任务？")},text={Text("「${p.title}」将不再继续安排。任务信息、完成进度与心得会保留在「已归档」，也会保存在备份与存档中。")},confirmButton={TextButton({changeData(onSuccess={archive=null}){archivePlan(it,p.id,LocalDate.now())}}){Text("归档")}},dismissButton={TextButton({archive=null}){Text("取消")}})}
    if(share)ProgressCardDialog(data,shareDate,onDismiss={share=false})
    if(stopTimer)PlanAlertDialog(onDismissRequest={stopTimer=false},title={Text("结束本次专注？")},text={Text("尚未完成的专注不会计入统计。你也可以先暂停，稍后继续。")},confirmButton={TextButton({safely{val next=ClockState(planId=timer.planId,minutes=timer.minutes,remaining=timer.minutes*60);clock.save(next);timer=next;stopTimer=false}}){Text("结束并重置")}},dismissButton={TextButton({stopTimer=false}){Text("继续专注")}})
    if(nickname)ProfileDialog(data,{nickname=false}){name,profile->
        check(!busy){"正在保存，请稍候"};busy=true
        try{updateData{it.copy(nickname=name,profile=profile)};nickname=false}finally{busy=false}
    }
    if(reminderDialog)FocusSettingsDialog(reminderSettings,permissionRevision,reminders,{reminderDialog=false}){reminders.updateSettings(it);reminderSettings=reminders.settings()}
    if(about)PlanAlertDialog(onDismissRequest={about=false},title={Text("关于 plan")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("plan 1.12.1\n安排任务，珍藏每一段积累。",fontWeight=FontWeight.Bold)
        Text("输入名称即可添加任务，按需设置时长、数量或长期目标。通过打卡、专注计时和历史月历记录进步，在归档页回顾曾经的安排与心得。")
        Text("数量打卡可记录本次新增，也可修改当天总量。未保存的任务和打卡内容可保留为草稿，下次继续。设置集中在记录页右上角。",color=Muted)
        Text("任务支持跳过当天、暂停和恢复，可以置顶、调整顺序或折叠已完成。历史记录可搜索心得、筛选日期，并查看每次专注明细。",color=Muted)
        Text("每个任务可以选填提醒时间，支持稍后提醒。通过周月复盘查看完成、跳过、暂停、专注时长与心得；外观支持跟随系统、浅色与深色。",color=Muted)
        Text("每天首次使用会保存自动存档，保留最近 7 份。「数据与存档」支持分类导入和完整打包备份，包含命名存档、自动存档、自定义提示音与外观偏好。",color=Muted)
        Text("在首页点「换肤」选择清简、纸页或柔和风格，配色和明暗可分别设置。卡片形状、标题与装饰会随风格改变，进度卡也会使用当前外观。",color=Muted)
        Text("可设置头像、昵称和首页寄语，自定义分类与任务图标，调整首页模块、显示密度及默认打开页面。个人资料、分类和外观设置均可随一键备份保存。",color=Muted)
        Text("专注页可管理常用时长；分享进度卡默认每日、详细，可预览并选择是否包含心得。",color=Muted)
        Text("首页点「写心得」可以选填一天一篇的每日心得，不影响打卡和完成率。在记录页查看、搜索与补写；任务打卡中的心得独立保留。",color=Muted)
        Text("专注与任务进度分别记录。卸载或更换设备前，请先保存备份文件。",color=Muted)
    }},confirmButton={TextButton({about=false}){Text("知道了")}})
    pendingBundle?.let{bundle->PlanAlertDialog(onDismissRequest={pendingBundle=null},title={Text(if(importScope==TransferScope.ALL)"恢复全部数据？" else "导入${transferName(importScope)}？")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text(importSource,color=Muted);Text(importSummary)
        Text(when { importScope==TransferScope.ALL && loadError!=null -> "将使用这份备份恢复数据，结束当前专注。无法读取的原文件会保留。"; importScope==TransferScope.ALL -> "将替换当前任务和记录，结束当前专注。恢复前会保留副本，可撤销。"; else -> "将合并到当前数据，已有内容保留。导入前会保留副本，可撤销。" },color=Teal)
    }},confirmButton={TextButton({if(busy)return@TextButton;busy=true;coroutine.launch{try{
          val scope=importScope
          if(scope==TransferScope.ALL)PlanReminderScheduler.cancelAll(context)
        replaceData(clearDrafts=scope==TransferScope.ALL){
            if(scope==TransferScope.ALL){val merged=mergeTransfer(AppData(),bundle,scope).result;store.restore(merged);merged}
            else store.restore{current->mergeTransfer(current,bundle,scope).result}
        }
        if(scope==TransferScope.ALL){resetClock();PlanReminderScheduler.resetAfterRestore(context)};pendingBundle=null;dataManager=false;detail=null;history=false;tab=if(scope==TransferScope.PLANS)1 else 3;message(if(scope==TransferScope.ALL)"数据已恢复" else "${transferName(scope)}已导入")
      }catch(e:Exception){PlanReminderScheduler.reschedule(context);issue=e.message?:"导入未完成"}finally{busy=false}}}){Text(if(importScope==TransferScope.ALL)"替换并恢复" else "合并导入")}},dismissButton={TextButton({pendingBundle=null}){Text("取消")}})}
    if(undoRestore)PlanAlertDialog(onDismissRequest={undoRestore=false},title={Text("找回导入前的数据？")},text={Text("当前数据将替换为上次导入或恢复之前保留的副本，当前专注也会结束。")},confirmButton={TextButton({if(busy)return@TextButton;busy=true;coroutine.launch{try{PlanReminderScheduler.cancelAll(context);replaceData(clearDrafts=true){store.recoverPrevious()};resetClock();PlanReminderScheduler.resetAfterRestore(context);undoRestore=false;dataManager=false;message("已找回之前的数据")}catch(e:Exception){PlanReminderScheduler.reschedule(context);issue=e.message?:"未能恢复副本"}finally{busy=false}}}){Text("确认找回")}},dismissButton={TextButton({undoRestore=false}){Text("取消")}})
    if(loadError!=null&&pendingBundle==null&&!undoRestore&&!dataManager&&!completeBackup)PlanAlertDialog(onDismissRequest={},title={Text("数据需要恢复")},text={Text("原文件已保留。${loadError}\n可从一键备份、数据文件或本机存档找回内容。恢复时会结束当前专注。")},confirmButton={Column{
        if(canUndoRestore)TextButton({undoRestore=true}){Text("找回安全副本")}
        TextButton({completeBackup=true}){Text("从一键备份恢复")}
        TextButton({openData(TransferScope.ALL)}){Text("从本机存档恢复")}
        TextButton({launchImport(TransferScope.ALL)}){Text("从数据文件恢复")}
    }},dismissButton={TextButton({(context as? android.app.Activity)?.finish()}){Text("关闭应用")}})
    exportNotice?.let{PlanAlertDialog(onDismissRequest={exportNotice=null},title={Text("文件已保存")},text={Text(it)},confirmButton={TextButton({exportNotice=null}){Text("完成")}})}
    issue?.let{PlanAlertDialog(onDismissRequest={issue=null},title={Text("操作未完成")},text={Text(it)},confirmButton={TextButton({issue=null}){Text("知道了")}})}
    if(busy)PlanDialog(onDismissRequest={},showMessages=false){Surface(shape=RoundedCornerShape(20.dp)){Row(Modifier.padding(24.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){CircularProgressIndicator(Modifier.size(26.dp),strokeWidth=3.dp);Text("正在处理…")}}}
    }
}
