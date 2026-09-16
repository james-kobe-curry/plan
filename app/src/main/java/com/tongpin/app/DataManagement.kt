package com.tongpin.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun transferName(scope:TransferScope)=when(scope){TransferScope.PLANS->"计划";TransferScope.RECORDS->"记录";TransferScope.ALL->"全部数据"}

@Composable fun DataManagementDialog(data:AppData,initialScope:TransferScope,canUndo:Boolean,onUndo:()->Unit,
    onExport:(TransferScope)->Unit,onImport:(TransferScope)->Unit,
    onRestoreSnapshot:(SnapshotInfo,AppData)->Unit,onExportSnapshot:(SnapshotInfo,AppData)->Unit,onDismiss:()->Unit,
    onCompleteBackup:()->Unit = {},recoveryOnly:Boolean=false){
    val context=LocalContext.current
    val store=remember{SnapshotStore(context)}
    val autoStore=remember{AutoSnapshotStore(context)}
    val coroutine=rememberCoroutineScope()
    var snapshots by remember{mutableStateOf<List<SnapshotInfo>>(emptyList())}
    var automatic by remember{mutableStateOf<List<SnapshotInfo>>(emptyList())}
    var loading by remember{mutableStateOf(true)}
    var error by remember{mutableStateOf("")}
    var notice by remember{mutableStateOf("")}
    var tab by rememberSaveable{mutableIntStateOf(if(recoveryOnly)1 else 0)}
    var selectedScope by rememberSaveable{mutableStateOf(initialScope)}
    var naming by remember{mutableStateOf(false)}
    var renaming by remember{mutableStateOf<SnapshotInfo?>(null)}
    var deleting by remember{mutableStateOf<SnapshotInfo?>(null)}
    var deletingAuto by remember{mutableStateOf<SnapshotInfo?>(null)}
    var viewing by remember{mutableStateOf<Pair<SnapshotInfo,AppData>?>(null)}
    fun refresh(){coroutine.launch{loading=true;try{val lists=withContext(Dispatchers.IO){store.list() to autoStore.list()};snapshots=lists.first;automatic=lists.second;error=""}catch(e:Exception){error=e.message?:"暂时无法读取存档"}finally{loading=false}}}
    LaunchedEffect(Unit){refresh()}
    PlanDialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
        Surface(Modifier.fillMaxSize(),color=Paper){Column(Modifier.fillMaxSize().safeDrawingPadding()){
            Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onDismiss){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回")};Text("数据与存档",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)}
            Row(Modifier.padding(horizontal=20.dp).fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                if(!recoveryOnly)FilterChip(tab==0,{tab=0},label={Text("备份导入")},modifier=Modifier.weight(1f))
                FilterChip(tab==1,{tab=1},label={Text("我的存档")},modifier=Modifier.weight(1f))
                FilterChip(tab==2,{tab=2},label={Text("自动存档")},modifier=Modifier.weight(1f))
            }
            if(error.isNotBlank())FormErrorBanner(error,Modifier.padding(horizontal=20.dp,vertical=8.dp))
            if(notice.isNotBlank())Surface(Modifier.padding(horizontal=20.dp,vertical=8.dp),shape=PlanSmallShape,color=Mint){Text(notice,Modifier.padding(12.dp),color=Teal,fontSize=13.sp)}
            if(loading)LinearProgressIndicator(Modifier.fillMaxWidth(),color=Teal)
            LazyColumn(Modifier.fillMaxWidth().weight(1f),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                if(tab==0&&!recoveryOnly){
                    item{Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Text("按需要，保存与带回",style=MaterialTheme.typography.headlineSmall);Text("分类整理计划与记录，或将全部积累一起打包。",color=Muted,fontSize=13.sp,lineHeight=21.sp)}}
                    item{Surface(shape=PlanCardShape,color=Hero){Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){Icon(Icons.Outlined.Backup,null,tint=Gold);Text("一键备份与恢复",color=OnHero,fontWeight=FontWeight.Bold,fontSize=18.sp)}
                        Text("计划、记录、命名与自动存档、提示音及应用偏好，一起保存。",color=OnHero,fontSize=13.sp,lineHeight=21.sp)
                        Button(onCompleteBackup,colors=ButtonDefaults.buttonColors(containerColor=Teal,contentColor=OnAccent),shape=PlanSmallShape){Text("打开一键备份")}
                    }}}
                    items(TransferScope.entries,key={it.name}){type->
                        val active=type==selectedScope
                        val title=when(type){TransferScope.PLANS->"计划备份与导入";TransferScope.RECORDS->"记录备份与导入";TransferScope.ALL->"当前全部数据"}
                        val subtitle=when(type){TransferScope.PLANS->"包含进行中、已归档任务及历次安排。";TransferScope.RECORDS->"打卡、任务心得、每日心得、专注记录与对应任务信息。";TransferScope.ALL->"一起保存任务、分类、归档、记录、每日心得与个人资料。"}
                        val icon=when(type){TransferScope.PLANS->Icons.Outlined.EventNote;TransferScope.RECORDS->Icons.Outlined.History;TransferScope.ALL->Icons.Outlined.Backup}
                        Column(Modifier.fillMaxWidth().clip(PlanCardShape).background(SurfaceColor).border(if(active)1.5.dp else 1.dp,if(active)Teal else Line,PlanCardShape).clickable{selectedScope=type}.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Box(Modifier.size(42.dp).clip(PlanSmallShape).background(if(type==TransferScope.RECORDS)Apricot else Mint),contentAlignment=Alignment.Center){Icon(icon,null,tint=Teal)};Text(title,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))}
                            Text(subtitle,fontSize=13.sp,color=Muted,lineHeight=21.sp)
                            Text(if(type==TransferScope.ALL)"恢复时替换当前数据，并保留恢复前的副本。" else "导入时合并到当前数据；已有内容不会被覆盖。",fontSize=12.sp,color=Teal,lineHeight=19.sp)
                            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                                OutlinedButton({selectedScope=type;onExport(type)},Modifier.weight(1f),shape=PlanSmallShape){Icon(Icons.Outlined.SaveAlt,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("导出备份")}
                                Button({selectedScope=type;onImport(type)},Modifier.weight(1f),shape=PlanSmallShape){Icon(Icons.Outlined.FileOpen,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("导入")}
                            }
                        }
                    }
                    if(canUndo)item{OutlinedButton(onUndo,Modifier.fillMaxWidth(),shape=PlanButtonShape){Icon(Icons.Outlined.Undo,null);Spacer(Modifier.width(8.dp));Text("撤销上次导入或恢复")}}
                    item{Text("导入前会展示文件内容和处理方式。旧版计划文件与完整备份也可使用。",fontSize=12.sp,color=Muted,lineHeight=20.sp)}
                }else if(tab==1){
                    item{Column(Modifier.fillMaxWidth().clip(PlanCardShape).background(Hero).padding(22.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                        Icon(Icons.Outlined.Bookmarks,null,tint=Gold,modifier=Modifier.size(30.dp));Text("留住一个阶段",color=OnHero,style=MaterialTheme.typography.headlineSmall);Text("给当前计划与记录起个名字，随时回来看看。",color=OnHero,fontSize=13.sp,lineHeight=21.sp)
                        if(recoveryOnly)Text("请选择已有存档，查看内容后恢复。",color=OnHero,fontSize=13.sp)
                        else Button({renaming=null;naming=true},enabled=!loading,colors=ButtonDefaults.buttonColors(containerColor=Teal,contentColor=OnAccent),shape=PlanSmallShape){Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(6.dp));Text("新建存档")}
                    }}
                    item{SectionTitle("已保存的阶段","${snapshots.size} / 50 份")}
                    if(snapshots.isEmpty()&&!loading)item{EmptyPanel(Icons.Outlined.Inventory2,"第一份存档，从此刻开始","任务、归档、打卡与专注记录会一起保存。")}
                    items(snapshots,key={it.id}){snapshot->
                        Column(Modifier.fillMaxWidth().clip(PlanCardShape).background(SurfaceColor).padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                            Row(verticalAlignment=Alignment.CenterVertically){Icon(if(snapshot.corrupt)Icons.Outlined.ErrorOutline else Icons.Outlined.Folder,null,tint=if(snapshot.corrupt)MaterialTheme.colorScheme.error else Teal);Text(snapshot.name,Modifier.weight(1f).padding(start=10.dp),fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis)}
                            Text(if(snapshot.corrupt)"这份存档暂时无法读取，其他存档仍可使用。" else "${snapshot.plans} 项进行中 · ${snapshot.archived} 个归档版本\n${snapshot.checkIns} 条打卡 · ${snapshot.focusRecords} 次专注 · ${snapshot.dailyNotes} 篇心得"+(if(snapshot.recordsOnly>0)" · ${snapshot.recordsOnly} 份记录来源信息" else ""),fontSize=12.sp,color=Muted,lineHeight=20.sp)
                            Text(snapshotTime(snapshot.updatedAt),fontSize=11.sp,color=Muted)
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
                                if(!recoveryOnly)TextButton({deleting=snapshot},enabled=!loading){Text("删除",color=Muted)}
                                if(!recoveryOnly)TextButton({renaming=snapshot;naming=true},enabled=!loading&&!snapshot.corrupt){Text("改名")}
                                FilledTonalButton({coroutine.launch{loading=true;try{val content=withContext(Dispatchers.IO){store.read(snapshot.id)};viewing=snapshot to content;error=""}catch(e:Exception){error=e.message?:"读取失败"}finally{loading=false}}},enabled=!loading&&!snapshot.corrupt){Text("查看")}
                            }
                        }
                    }
                    item{Text("可分别导出存档，也可通过“一键备份”保存全部存档、当前数据与提示音。",color=Muted,fontSize=12.sp,lineHeight=20.sp)}
                }else{
                    item{Column(Modifier.fillMaxWidth().clip(PlanCardShape).background(Hero).padding(22.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                        Icon(Icons.Outlined.History,null,tint=Gold,modifier=Modifier.size(30.dp));Text("每天，为积累留一份底稿",color=OnHero,style=MaterialTheme.typography.headlineSmall)
                        Text("每天首次使用时保存已有计划与记录，保留最近 7 份。当天的存档不会随之后的修改而覆盖，也不占用命名存档名额。",color=OnHero,fontSize=13.sp,lineHeight=21.sp)
                    }}
                    item{SectionTitle("最近的自动存档","${automatic.size} / 7 份")}
                    if(automatic.isEmpty()&&!loading)item{EmptyPanel(Icons.Outlined.History,"从第一份计划开始保护","有计划或记录后，每天首次使用会自动保存一份。")}
                    items(automatic,key={it.id}){snapshot->
                        Column(Modifier.fillMaxWidth().clip(PlanCardShape).background(SurfaceColor).padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                            Row(verticalAlignment=Alignment.CenterVertically){Icon(if(snapshot.corrupt)Icons.Outlined.ErrorOutline else Icons.Outlined.History,null,tint=if(snapshot.corrupt)MaterialTheme.colorScheme.error else Teal);Text(snapshot.name,Modifier.weight(1f).padding(start=10.dp),fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis)}
                            Text(if(snapshot.corrupt)"这份自动存档暂时无法读取。" else "${snapshot.plans} 项当前计划 · ${snapshot.archived} 个归档版本\n${snapshot.checkIns} 条打卡 · ${snapshot.focusRecords} 次专注 · ${snapshot.dailyNotes} 篇心得",fontSize=12.sp,color=Muted,lineHeight=20.sp)
                            Text(snapshotTime(snapshot.createdAt),fontSize=11.sp,color=Muted)
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
                                if(snapshot.corrupt&&!recoveryOnly)TextButton({deletingAuto=snapshot},enabled=!loading){Text("删除",color=Muted)}
                                FilledTonalButton({coroutine.launch{loading=true;try{val content=withContext(Dispatchers.IO){autoStore.read(snapshot.id)};viewing=snapshot to content;error=""}catch(e:Exception){error=e.message?:"读取失败"}finally{loading=false}}},enabled=!loading&&!snapshot.corrupt){Text("查看")}
                            }
                        }
                    }
                    item{Text("自动存档可查看、导出与恢复，也包含在一键备份中。需要长期保留某个阶段时，可以导出该存档。",color=Muted,fontSize=12.sp,lineHeight=20.sp)}
                }
            }
        }}
    }
    if(naming)SnapshotNameDialog(renaming?.name.orEmpty(),renaming!=null,{naming=false}){name,done->
        coroutine.launch{loading=true;try{
            withContext(Dispatchers.IO){val current=renaming;if(current==null)store.create(name,data)else store.rename(current.id,name)}
            snapshots=withContext(Dispatchers.IO){store.list()};notice=if(renaming==null)"存档已保存" else "存档已改名";naming=false;error="";done(null)
        }catch(e:Exception){done(e.message?:"未能保存存档")}finally{loading=false}}
    }
    deleting?.let{s->PlanAlertDialog(onDismissRequest={if(!loading)deleting=null},title={Text("删除这份存档？")},text={Text("仅删除「${s.name}」这份存档。当前任务与记录保持原样。")},confirmButton={TextButton({if(loading)return@TextButton;loading=true;coroutine.launch{try{withContext(Dispatchers.IO){store.delete(s.id)};snapshots=withContext(Dispatchers.IO){store.list()};deleting=null;notice="存档已删除";error=""}catch(e:Exception){error=e.message?:"未能删除";deleting=null}finally{loading=false}}}){Text("删除")}},dismissButton={TextButton({deleting=null}){Text("取消")}})}
    viewing?.let{(info,content)->SnapshotPreviewDialog(info,content,{viewing=null},{onExportSnapshot(info,content)},{onRestoreSnapshot(info,content)})}
    deletingAuto?.let{s->PlanAlertDialog(onDismissRequest={if(!loading)deletingAuto=null},title={Text("删除这份自动存档？")},text={Text("仅删除「${s.name}」。当前计划、记录及命名存档保持原样。")},confirmButton={TextButton({if(loading)return@TextButton;loading=true;coroutine.launch{try{withContext(Dispatchers.IO){autoStore.delete(s.id)};automatic=withContext(Dispatchers.IO){autoStore.list()};deletingAuto=null;notice="自动存档已删除";error=""}catch(e:Exception){error=e.message?:"未能删除";deletingAuto=null}finally{loading=false}}}){Text("删除")}},dismissButton={TextButton({deletingAuto=null}){Text("取消")}})}
    if(loading)PlanDialog(onDismissRequest={}){Surface(shape=PlanCardShape){Row(Modifier.padding(24.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){CircularProgressIndicator(Modifier.size(26.dp),strokeWidth=3.dp);Text("正在整理存档…")}}}
}

@Composable private fun SnapshotNameDialog(initial:String,renaming:Boolean,onDismiss:()->Unit,onSave:(String,(String?)->Unit)->Unit){
    var value by rememberSaveable{mutableStateOf(initial)}
    var error by remember{mutableStateOf("")}
    var busy by remember{mutableStateOf(false)}
    FormDialog(title=if(renaming)"重命名存档" else "新建存档",subtitle="为这一阶段留一个名字。",error=error,onDismiss={if(!busy)onDismiss()},confirmLabel=if(busy)"正在保存…" else "保存存档",onConfirm={if(!busy){if(value.isBlank())error="请填写存档名称" else{busy=true;onSave(value.trim()){message->busy=false;error=message.orEmpty()}}}}){
        OutlinedTextField(value,{if(it.length<=40){value=it;error=""}},label={Text("存档名称")},placeholder={Text("例如：九月学习计划")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
        Text("保存当前任务、已归档任务、打卡与专注记录。",color=Muted,fontSize=13.sp,lineHeight=21.sp)
    }
}

@Composable private fun SnapshotPreviewDialog(info:SnapshotInfo,data:AppData,onDismiss:()->Unit,onExport:()->Unit,onRestore:()->Unit){
    var detail by remember{mutableStateOf<String?>(null)}
    var showRecords by rememberSaveable{mutableStateOf(false)}
    var showDailyNotes by rememberSaveable{mutableStateOf(false)}
    val tasks=libraryTasks(data)
    PlanDialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
        Surface(Modifier.fillMaxSize(),color=Paper){Column(Modifier.fillMaxSize().safeDrawingPadding()){
            Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onDismiss){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回存档")};Text("存档详情",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)}
            LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
                item{Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text(info.name,style=MaterialTheme.typography.headlineMedium);Text(snapshotTime(info.createdAt),color=Muted,fontSize=12.sp);Text("${tasks.size} 项任务 · ${data.checkIns.size} 条打卡 · ${data.focusRecords.size} 次专注 · ${data.dailyNotes.size} 篇心得",color=Teal,fontSize=13.sp)}}
                items(tasks,key={it.seriesId}){p->Row(Modifier.fillMaxWidth().clip(PlanCardShape).background(SurfaceColor).clickable{detail=p.seriesId}.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Icon(planCategoryIcon(p,data),null,tint=Teal);Column(Modifier.weight(1f).padding(horizontal=12.dp)){Text(p.title,fontWeight=FontWeight.SemiBold);Text((if(p.archived)"已归档" else "进行中")+" · "+planTargetLabel(p),color=Muted,fontSize=12.sp)};Icon(Icons.Outlined.ChevronRight,null,tint=Muted)}}
                item{OutlinedButton({showRecords=true},Modifier.fillMaxWidth()){Text("查看打卡与专注记录")}}
                item{OutlinedButton({showDailyNotes=true},Modifier.fillMaxWidth().heightIn(min=48.dp),shape=PlanButtonShape){Icon(Icons.Outlined.EditNote,null);Spacer(Modifier.width(8.dp));Text("查看每日心得 · ${data.dailyNotes.size} 篇")}}
                if(tasks.isEmpty()&&data.checkIns.isEmpty()&&data.dailyNotes.isEmpty())item{Text("这份存档尚未添加任务。",color=Muted)}
            }
            Row(Modifier.fillMaxWidth().background(SurfaceColor).padding(20.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                OutlinedButton(onExport,Modifier.weight(1f),shape=PlanButtonShape){Text("导出文件")}
                Button(onRestore,Modifier.weight(1f),shape=PlanButtonShape){Text("恢复此存档")}
            }
        }}
    }
    detail?.let{PlanDetailDialog(data,it,{detail=null},{},{_,_->},readOnly=true)}
    if(showRecords)HistoryBrowser(data,{showRecords=false},{_,_->},readOnly=true)
    if(showDailyNotes)DailyNotesDialog(data,{showDailyNotes=false},{},readOnly=true)
}

private fun snapshotTime(time:Long):String=Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm"))
