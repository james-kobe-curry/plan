package com.tongpin.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.util.UUID

/** A task may have many historical versions, but appears once in the library. */
fun libraryTasks(data: AppData): List<Plan> = plansInDisplayOrder(data, data.plans.filter { !it.recordsOnly }
    .groupBy { it.seriesId }.values.map { versions ->
        versions.lastOrNull { !it.archived } ?: versions.maxWith(compareBy<Plan> { it.endDate ?: it.startDate }.thenBy { it.startDate })
    })

@OptIn(ExperimentalLayoutApi::class)
@Composable fun PlansScreen(data:AppData,onNew:()->Unit,onEdit:(Plan)->Unit,onArchive:(Plan)->Unit,
    onData:()->Unit,onDetail:(Plan)->Unit,onTemplate:(Plan)->Unit,
    onPause:(Plan)->Unit={},onResume:(Plan)->Unit={},onRestore:(Plan,LocalDate,()->Unit)->Unit={_,_,_->},onManageCategories:()->Unit={}) {
    var restoring by remember { mutableStateOf<Plan?>(null) }
    var archived by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var categoryFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val categoryOptions = categoryFilterOptions(data)
    LaunchedEffect(data.categories) { if (categoryFilter != null && categoryOptions.none { it.first == categoryFilter }) categoryFilter = null }
    var templates by rememberSaveable { mutableStateOf(false) }
    val tasks=remember(data.plans){libraryTasks(data)}
    val active=tasks.count{!it.archived}
    val ended=tasks.count{it.archived}
    val visible=tasks.filter{it.archived==archived && it.title.contains(query.trim(),true) && (categoryFilter==null || planCategoryKey(it)==categoryFilter)}
    PageColumn {
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)){
            val largeText = LocalDensity.current.fontScale >= 1.5f
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val title: @Composable () -> Unit = {
                    Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        Text("我的计划",style=MaterialTheme.typography.headlineMedium)
                        Text("$active 项进行中 · $ended 项已归档",color=Muted,style=MaterialTheme.typography.bodyMedium)
                    }
                }
                val add: @Composable () -> Unit = {
                    PlanPrimaryButton(onNew) { Icon(Icons.Outlined.Add,null,Modifier.size(20.dp));Spacer(Modifier.width(6.dp));Text("新任务") }
                }
                if (largeText || maxWidth < 330.dp) {
                    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) { title(); add() }
                } else {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f)) { title() }
                        add()
                    }
                }
            }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){
                PlanQuietButton(onManageCategories){Icon(Icons.Outlined.Category,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("管理分类")}
                PlanQuietButton(onData){Icon(Icons.Outlined.FolderOpen,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("备份与导入")}
            }
        }
        Row(Modifier.fillMaxWidth().clip(PlanSmallShape).background(Line).padding(4.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)){
            LibraryTab("进行中  $active",!archived,Modifier.weight(1f)){archived=false}
            LibraryTab("已归档  $ended",archived,Modifier.weight(1f)){archived=true}
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(8.dp)){
            PlanCategoryChip("全部",categoryFilter==null,{categoryFilter=null})
            categoryOptions.forEach{(key,label)->PlanCategoryChip(label,categoryFilter==key,{categoryFilter=key})}
        }
        if(tasks.size>4 || query.isNotEmpty())OutlinedTextField(query,{query=it},singleLine=true,label={Text("搜索任务")},leadingIcon={Icon(Icons.Outlined.Search,null)},shape=PlanSmallShape,modifier=Modifier.fillMaxWidth())
        if(archived)Text("归档任务可查看详情，也可恢复继续使用。",color=Muted,fontSize=13.sp,lineHeight=20.sp)
        if(visible.isEmpty())EmptyPanel(if(archived)Icons.Outlined.Inventory2 else Icons.Outlined.EventNote,
            if(query.isNotBlank()||categoryFilter!=null)"没有找到相关任务" else if(archived)"旅程，会在这里留下印记" else "为今天添一件小事",
            if(query.isNotBlank()||categoryFilter!=null)"换一个关键词或分类试试。" else if(archived)"归档后的任务可在这里回顾安排、进度与心得。" else "只需一个名称，就能开始。")
        visible.forEach { p -> LibraryTaskCard(data,p,{onDetail(p)},{onEdit(p)},{onArchive(p)},{if(isPlanPaused(p))onResume(p) else onPause(p)},{restoring=p}) }
        if(!archived){
            PlanQuietButton({templates=!templates},Modifier.fillMaxWidth()){Icon(Icons.Outlined.Dashboard,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text(if(templates)"收起任务模板" else "从模板开始");Icon(if(templates)Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null)}
            if(templates)templatePlans().forEach{p->Row(Modifier.fillMaxWidth().clip(PlanCardShape).background(planCategoryColor(p,data)).padding(14.dp),verticalAlignment=Alignment.CenterVertically){Icon(planCategoryIcon(p,data),null,tint=Teal);Column(Modifier.weight(1f).padding(horizontal=12.dp)){Text(p.title,fontWeight=FontWeight.SemiBold);Text(frequency(p),fontSize=12.sp,color=Muted)};FilledTonalIconButton({val id=UUID.randomUUID().toString();onTemplate(p.copy(id=id,seriesId=id))},Modifier.size(48.dp)){Icon(Icons.Outlined.Add,"添加${p.title}",tint=Teal)}}}
        }
    }
    restoring?.let{p->RestorePlanDialog(data,p,{restoring=null}){date->onRestore(p,date){restoring=null;archived=false}}}
}

@Composable private fun LibraryTab(label:String,selected:Boolean,modifier:Modifier,onClick:()->Unit){
    Box(modifier.heightIn(min=48.dp).clip(PlanSmallShape).background(if(selected)SurfaceColor else Color.Transparent).semantics{this.selected=selected}.clickable(role=Role.Tab,onClick=onClick).padding(vertical=12.dp,horizontal=6.dp),contentAlignment=Alignment.Center){Text(label,color=if(selected)Teal else Muted,fontWeight=FontWeight.SemiBold,fontSize=14.sp)}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun LibraryTaskCard(data:AppData,p:Plan,onDetail:()->Unit,onEdit:()->Unit,onArchive:()->Unit,onPauseToggle:()->Unit,onRestore:()->Unit){
    var more by remember(p.id) { mutableStateOf(false) }
    val ids=data.plans.filter{it.seriesId==p.seriesId}.map{it.id}.toSet()
    val entries=data.checkIns.filter{it.planId in ids}
    val days=entries.map{it.date}.distinct().size
    Column(Modifier.fillMaxWidth().clip(PlanCardShape).background(SurfaceColor).clickable(onClickLabel="查看任务详情",onClick=onDetail).padding(PlanCardPadding),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
            Box(Modifier.size(44.dp).clip(PlanSmallShape).background(planCategoryColor(p,data)),contentAlignment=Alignment.Center){Icon(planCategoryIcon(p,data),null,tint=Teal)}
            Column(Modifier.weight(1f)){Text(p.title,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis);Text(planCategoryName(p,data)+" · "+if(p.archived)"已归档" else frequency(p),fontSize=12.sp,color=Muted)}
            Icon(Icons.Outlined.ChevronRight,null,tint=Muted,modifier=Modifier.size(20.dp))
        }
        if(!p.archived && (isPlanPaused(p)||isPlanExpired(p,LocalDate.now())))FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
            if(isPlanPaused(p))Tag(if(p.pauses.last().startDate>LocalDate.now().toString())"${p.pauses.last().startDate} 起暂停" else "已暂停",BlueWash)
            if(isPlanExpired(p,LocalDate.now()))Tag("已到期",Apricot)
        }
        p.reminderTime?.let{Text("执行日 $it 提醒",fontSize=12.sp,color=Teal)}
        if(p.tracking==TrackingMode.QUANTITY)Text("每次 ${planTargetLabel(p)}",fontSize=14.sp,color=Ink)
        if(p.totalTarget!=null){val goal=totalGoalProgress(data,p);Text("累计 ${goal.first} / ${formatQuantity(p.totalTarget,p)} ${p.unit}",color=Teal,fontSize=13.sp);LinearProgressIndicator(progress={goal.second},modifier=Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),color=Teal,trackColor=Mint,drawStopIndicator={})}
        Text(if(days>0)"已打卡 $days 天" else if(p.archived)"计划信息已保留" else if(p.startDate>LocalDate.now().toString())"${p.startDate} 开始" else "尚未打卡",color=Muted,fontSize=12.sp)
        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
            if(p.archived)PlanSecondaryButton(onRestore){Icon(Icons.Outlined.Restore,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("恢复任务")}
            else{
                PlanSecondaryButton(onEdit){
                    Icon(Icons.Outlined.Edit,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("编辑")
                }
                Box {
                    PlanQuietButton({more=true}) { Icon(Icons.Outlined.MoreHoriz,null,Modifier.size(20.dp));Spacer(Modifier.width(6.dp));Text("更多") }
                    DropdownMenu(expanded=more,onDismissRequest={more=false}) {
                        if(!isPlanExpired(p,LocalDate.now())||isPlanPaused(p)) DropdownMenuItem(
                            text={Text(if(isPlanPaused(p))"恢复安排" else "暂停计划")},
                            leadingIcon={Icon(if(isPlanPaused(p))Icons.Outlined.PlayArrow else Icons.Outlined.Pause,null)},
                            onClick={more=false;onPauseToggle()},modifier=Modifier.heightIn(min=48.dp),
                        )
                        if(isPlanExpired(p,LocalDate.now())) DropdownMenuItem(
                            text={Text("延长截止日期")},leadingIcon={Icon(Icons.Outlined.Event,null)},
                            onClick={more=false;onEdit()},modifier=Modifier.heightIn(min=48.dp),
                        )
                        DropdownMenuItem(text={Text("归档任务")},leadingIcon={Icon(Icons.Outlined.Inventory2,null)},
                            onClick={more=false;onArchive()},modifier=Modifier.heightIn(min=48.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun PlanDetailDialog(data:AppData,seriesId:String,onDismiss:()->Unit,onEdit:(Plan)->Unit,onCheck:(Plan,LocalDate)->Unit,readOnly:Boolean=false,
    onPause:(Plan)->Unit={},onResume:(Plan)->Unit={},onRestore:(Plan,LocalDate,()->Unit)->Unit={_,_,_->}){
    val versions=data.plans.filter{it.seriesId==seriesId}.sortedByDescending{it.startDate}
    val plan=libraryTasks(data).firstOrNull{it.seriesId==seriesId}?:return
    val byId=versions.associateBy{it.id}
    val entries=data.checkIns.filter{it.planId in byId}.sortedByDescending{it.date}
    val complete=entries.count{it.amount >= byId.getValue(it.planId).target}
    val seconds=data.focusRecords.filter{it.planId in byId}.sumOf{it.seconds.toLong()}
    var showVersions by rememberSaveable{mutableStateOf(false)}
    var restoring by remember{mutableStateOf(false)}
    PlanDialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false),showMessages=true){
        Surface(Modifier.fillMaxSize(),color=Paper){Column(Modifier.fillMaxSize().safeDrawingPadding()){
            Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onDismiss){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回计划")};Text(if(plan.archived)"归档回顾" else "任务详情",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge);if(!readOnly&&!plan.archived&&!plan.recordsOnly)PlanSecondaryButton({onEdit(plan)}){Icon(Icons.Outlined.Edit,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("编辑")}}
            LazyColumn(Modifier.fillMaxWidth().weight(1f),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                item{
                    Column(Modifier.fillMaxWidth().clip(PlanCardShape).background(SurfaceColor).padding(PlanCardPadding),verticalArrangement=Arrangement.spacedBy(18.dp)){
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Icon(planCategoryIcon(plan,data),null,tint=Teal,modifier=Modifier.size(28.dp))
                            Text(plan.title,style=MaterialTheme.typography.headlineSmall)
                        }
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){DetailMetric("完成",complete.toString(),"次");DetailMetric("记录",entries.map{it.date}.distinct().size.toString(),"天");DetailMetric("专注",(seconds/60).toString(),"分钟")}
                    }
                }
                item{
                    Column(Modifier.fillMaxWidth().clip(PlanCardShape).background(SurfaceColor).padding(PlanCardPadding),verticalArrangement=Arrangement.spacedBy(10.dp)){
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                            Text("任务安排",fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                            if(isPlanExpired(plan,LocalDate.now()))Tag("已到期",Apricot) else if(isPlanPaused(plan))Tag("已暂停",BlueWash)
                        }
                        Text("${planCategoryName(plan,data)} · ${frequency(plan)}",color=Muted)
                        Text(if(plan.tracking==TrackingMode.TASK)"完成任务后打卡" else "每次目标 ${planTargetLabel(plan)}",color=Ink)
                        Text("开始于 ${versions.minOf{it.startDate}}",fontSize=13.sp,color=Muted)
                        plan.endDate?.let{Text("归档生效 $it",fontSize=13.sp,color=Muted)}
                        plan.dueDate?.let{Text("计划截止 $it",fontSize=13.sp,color=Muted)}
                        plan.reminderTime?.let{Text("执行日 $it 提醒"+if(plan.archived||isPlanPaused(plan))" · 当前暂停提醒" else " · 完成后不再提醒",fontSize=13.sp,color=Muted)}
                        if(plan.totalTarget!=null){val goal=totalGoalProgress(data,plan);Text("总进度 ${goal.first} / ${formatQuantity(plan.totalTarget,plan)} ${plan.unit}",fontWeight=FontWeight.SemiBold,color=Teal)}
                        if(!readOnly){
                            if(plan.archived)PlanPrimaryButton({restoring=true},Modifier.fillMaxWidth()){Icon(Icons.Outlined.Restore,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text("恢复这个任务")}
                            else if(isPlanExpired(plan,LocalDate.now()))PlanSecondaryButton({onEdit(plan)},Modifier.fillMaxWidth()){Icon(Icons.Outlined.Event,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text("延长截止日期")}
                            else PlanSecondaryButton({if(isPlanPaused(plan))onResume(plan) else onPause(plan)},Modifier.fillMaxWidth()){Icon(if(isPlanPaused(plan))Icons.Outlined.PlayArrow else Icons.Outlined.Pause,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text(if(isPlanPaused(plan))"恢复安排" else "暂停计划")}
                        }
                        if(plan.pauses.isNotEmpty()){
                            Text("暂停记录",fontWeight=FontWeight.SemiBold)
                            plan.pauses.takeLast(10).asReversed().forEach{pause->Text("${pause.startDate} 起 · "+(pause.endDate?.let{"$it 恢复"}?:"尚未恢复"),fontSize=12.sp,color=Muted)}
                            if(plan.pauses.size>10)Text("更早的暂停记录已保存在备份中",fontSize=12.sp,color=Muted)
                        }
                    }
                }
                if(versions.size>1){
                    item{PlanQuietButton({showVersions=!showVersions},Modifier.fillMaxWidth()){Icon(Icons.Outlined.History,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text(if(showVersions)"收起安排变更" else "查看 ${versions.size} 版安排");Icon(if(showVersions)Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null)}}
                    if(showVersions)items(versions,key={"version:${it.id}"}){p->Column(Modifier.fillMaxWidth().clip(PlanSmallShape).background(SurfaceColor).padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(p.title,fontWeight=FontWeight.SemiBold);Text("${p.startDate} 起 · ${frequency(p)} · ${planTargetLabel(p)}",fontSize=12.sp,color=Muted);p.endDate?.let{Text("$it 起停止安排",fontSize=12.sp,color=Muted)}}}
                }
                val skips=versions.flatMap{it.skips}.distinctBy{it.date}.sortedByDescending{it.date}
                if(skips.isNotEmpty()){
                    item{SectionTitle("休息的日子","${skips.size} 天跳过")}
                    items(skips,key={"skip:${it.date}"}){skip->Column(Modifier.fillMaxWidth().clip(PlanSmallShape).background(BlueWash).padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
                        Text(skip.date+" · 已跳过",fontWeight=FontWeight.SemiBold,fontSize=13.sp)
                        Text(skip.reason.ifBlank{"给自己一天休息"},color=Muted,fontSize=13.sp)
                    }}
                }
                item{SectionTitle("回忆与记录","${entries.size} 条打卡")}
                if(entries.isEmpty())item{EmptyPanel(Icons.Outlined.AutoAwesome,"任务也有自己的故事","安排已保留，随时可以回来看看。")}
                items(entries,key={"${it.planId}:${it.date}"}){entry->
                    val p=byId.getValue(entry.planId);val date=LocalDate.parse(entry.date);val editable=!readOnly&&date<=LocalDate.now()&&isScheduled(p,date)
                    Column(Modifier.fillMaxWidth().clip(PlanCardShape).background(SurfaceColor).clickable(enabled=editable){onCheck(p,date)}.padding(PlanCardPadding),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(entry.date,Modifier.weight(1f),color=Muted,fontSize=12.sp);Icon(if(entry.amount>=p.target)Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,null,tint=Teal,modifier=Modifier.size(18.dp))}
                        Text(if(p.tracking==TrackingMode.TASK)"完成任务" else "${formatQuantity(entry.amount,p)} / ${formatQuantity(p.target,p)} ${p.unit}",fontWeight=FontWeight.Bold)
                        if(entry.note.isNotBlank())Text(entry.note,color=Muted,fontSize=14.sp,lineHeight=22.sp)
                    }
                }
            }
        }}
    }
    if(restoring)RestorePlanDialog(data,plan,{restoring=false}){date->onRestore(plan,date){restoring=false}}
}

@Composable private fun DetailMetric(label:String,value:String,unit:String){Column{Text("$value $unit",color=Ink,fontSize=18.sp,fontWeight=FontWeight.SemiBold);Text(label,color=Muted,fontSize=12.sp)}}
