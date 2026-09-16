package com.tongpin.app

import android.content.Context
import android.content.Intent
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.ByteArrayOutputStream
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var focusRequest by mutableIntStateOf(0)
    private var todayRequest by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if(intent.getBooleanExtra("open_focus",false))focusRequest++
        if(intent.getBooleanExtra("open_today",false))todayRequest++
        enableEdgeToEdge()
        setContent { TongpinTheme { TongpinApp(focusRequest,todayRequest) } }
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);if(intent.getBooleanExtra("open_focus",false))focusRequest++;if(intent.getBooleanExtra("open_today",false))todayRequest++}
}

fun categoryName(c:Category)=when(c){Category.STUDY->"学习";Category.FITNESS->"健身";Category.LIFE->"日常"}
fun categoryIcon(c:Category):ImageVector=when(c){Category.STUDY->Icons.Outlined.MenuBook;Category.FITNESS->Icons.Outlined.FitnessCenter;Category.LIFE->Icons.Outlined.Spa}
@Composable fun categoryColor(c:Category)=when(c){Category.STUDY->Mint;Category.FITNESS->Apricot;Category.LIFE->BlueWash}
val weekNames=listOf("一","二","三","四","五","六","日")
fun frequency(p:Plan)=when(p.weekdays){(1..7).toSet()->"每天";(1..5).toSet()->"工作日";setOf(6,7)->"周末";else->"周"+p.weekdays.sorted().joinToString("、"){weekNames[it-1]}}

@Composable fun PageColumn(content:@Composable ColumnScope.()->Unit){Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=PlanPagePadding).padding(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(PlanPageGap),content=content)}
@Composable fun SectionTitle(title:String,side:String="",action:(()->Unit)?=null){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(title,style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f));if(side.isNotEmpty()){if(action!=null)TextButton(onClick=action){Text(side)}else Text(side,color=Muted,style=MaterialTheme.typography.bodyMedium)}}}
@Composable fun Tag(text:String,color:Color=Mint){Text(text,Modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal=7.dp,vertical=3.dp),fontSize=12.sp,color=Ink)}
@Composable fun Ring(progress:Float,center:String,label:String,modifier:Modifier=Modifier,color:Color=Gold,track:Color=OnHero.copy(alpha=.15f)){
    val value=buildAnnotatedString{
        append(center.removeSuffix("%"))
        if(center.endsWith("%"))withStyle(SpanStyle(fontSize=12.sp,fontWeight=FontWeight.Medium)){append("%")}
    }
    val valueStyle=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=20.sp,lineHeight=24.sp,fontWeight=FontWeight.SemiBold,letterSpacing=0.sp)
    val labelStyle=TextStyle(fontSize=11.sp,lineHeight=15.sp,fontWeight=FontWeight.Normal,letterSpacing=0.sp)
    val measurer=rememberTextMeasurer()
    val numberSize=measurer.measure(value,valueStyle,softWrap=false).size
    val labelSize=measurer.measure(label,labelStyle,softWrap=false).size
    // The ring grows only when accessibility text needs more space, including 100%.
    val diameter=with(LocalDensity.current){maxOf(numberSize.width,labelSize.width,numberSize.height+labelSize.height).toDp()+30.dp}.coerceAtLeast(88.dp)
    Box(modifier.size(diameter),contentAlignment=Alignment.Center){
        Canvas(Modifier.fillMaxSize().padding(4.dp)){drawArc(track,-90f,360f,false,style=Stroke(6.dp.toPx(),cap=StrokeCap.Round));if(progress>0)drawArc(color,-90f,progress.coerceIn(0f,1f)*360f,false,style=Stroke(6.dp.toPx(),cap=StrokeCap.Round))}
        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(2.dp)){
            Text(value,style=valueStyle,color=OnHero,maxLines=1,softWrap=false)
            Text(label,style=labelStyle,color=OnHero,maxLines=1,softWrap=false)
        }
    }
}

@Composable private fun TodayProgressCard(completed:Int,total:Int,date:LocalDate,today:LocalDate){
    BoxWithConstraints(Modifier.fillMaxWidth().clip(PlanCardShape).background(Hero).padding(horizontal=16.dp,vertical=12.dp)){
        val largeText=LocalDensity.current.fontScale>=1.5f||maxWidth<280.dp
        val details:@Composable ()->Unit={Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
            Text(if(date==today)"今日进度" else date.format(DateTimeFormatter.ofPattern("M 月 d 日"))+"进度",color=OnHero,fontSize=13.sp)
            Text(if(total==0){if(date==today)"从一个小任务开始" else "这一天没有待办任务"} else if(completed==total){if(date==today)"今日目标，全部达成" else "当天目标，全部达成"} else "完成 $completed / $total 项任务",color=OnHero,fontWeight=FontWeight.SemiBold,fontSize=18.sp,lineHeight=25.sp)
        }}
        val ring:@Composable ()->Unit={Ring(if(total==0)0f else completed.toFloat()/total,if(total==0)"0%" else "${completed*100/total}%","完成率")}
        if(largeText)Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){Box(Modifier.fillMaxWidth()){details()};ring()}
        else Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Box(Modifier.weight(1f)){details()};ring()}
    }
}

@Composable fun TodayScreen(data:AppData,today:LocalDate,date:LocalDate,onDate:(LocalDate)->Unit,onNew:()->Unit,onCheck:(Plan)->Unit,onShare:()->Unit,onTemplate:(Plan)->Unit,
    onSkip:(Plan,String,()->Unit)->Unit={_,_,_->},onUndoSkip:(Plan)->Unit={},onPin:(Plan)->Unit={},
    onMove:(Plan,Int,List<String>)->Unit={_,_,_->},onCollapse:(Boolean)->Unit={},
    backupDue:Boolean=false,onBackup:()->Unit={},onDeferBackup:(Int)->Unit={},
    homePreferences:HomePreferences=HomePreferences(),onPersonalize:()->Unit={},onDailyNote:()->Unit={},onSortingFinished:()->Unit={},sortingSaving:Boolean=false){
    var filter by rememberSaveable{mutableStateOf("all")}
    val filters=categoryFilterOptions(data)
    LaunchedEffect(filters){if(filter!="all" && filters.none{it.first==filter})filter="all"}
    var arranging by rememberSaveable(date,today){mutableStateOf(false)}
    var sortingStartOrder by rememberSaveable(date,today){mutableStateOf(listOf<String>())}
    var taskMenu by remember(date,today){mutableStateOf(false)}
    var skipping by remember(date,today){mutableStateOf<Plan?>(null)}
    val scheduled=remember(data.plans,date){scheduledPlansOrdered(data,date)}
    val finishSorting:()->Unit={
        if(!sortingSaving){
            val changed=arranging&&sortingStartOrder!=scheduled.map{it.id}
            arranging=false
            sortingStartOrder=emptyList()
            if(changed)onSortingFinished()
        }
    }
    val skipped=remember(data.plans,date){effectivePlansForDate(data,date).filter{isArranged(it,date) && it.skips.any{skip->skip.date==date.toString()}}}
    val amounts=remember(data.checkIns,date){data.checkIns.asSequence().filter{it.date==date.toString()}.associate{it.planId to it.amount}}
    val progress=scheduled.count{(amounts[it.id]?:0)>=it.target} to scheduled.size
    val completed=progress.first;val total=progress.second
    val monday=today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    PageColumn{
        Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
            BoxWithConstraints(Modifier.fillMaxWidth()){
                val greeting:@Composable ()->Unit={
                    Row(Modifier.heightIn(min=48.dp).clip(PlanSmallShape).then(if(data.profile.showOnHome)Modifier.clickable(onClick=onPersonalize)else Modifier),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        if(data.profile.showOnHome)ProfileAvatar(data.profile,Modifier.size(30.dp))
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){
                            if(data.profile.showOnHome)Text(data.nickname,fontSize=16.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                            Text(date.format(DateTimeFormatter.ofPattern("M月d日"))+" · 周"+weekNames[date.dayOfWeek.value-1],color=Muted,fontSize=12.sp)
                        }
                    }
                }
                val actions:@Composable ()->Unit={
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        PlanSecondaryButton(onShare){Icon(Icons.Outlined.IosShare,null,Modifier.size(18.dp));Spacer(Modifier.width(5.dp));Text("分享")}
                        PlanSecondaryButton(onDailyNote){Icon(Icons.Outlined.EditNote,null,Modifier.size(18.dp));Spacer(Modifier.width(5.dp));Text(if(dailyNoteFor(data,date)==null)"写心得" else "看心得")}
                    }
                }
                if(LocalDensity.current.fontScale>1.3f||maxWidth<330.dp)Column(verticalArrangement=Arrangement.spacedBy(6.dp)){greeting();actions()}
                else Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){Box(Modifier.weight(1f)){greeting()};actions()}
            }
            if(data.profile.showOnHome&&data.profile.motto.isNotBlank())Text(data.profile.motto,fontSize=12.sp,lineHeight=18.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)){(0..6).forEach{i->val d=monday.plusDays(i.toLong());val active=d==date;Column(Modifier.weight(1f).clip(PlanSmallShape).background(if(active)Teal else Color.Transparent).clickable(enabled=!d.isAfter(today)){onDate(d)}.padding(vertical=7.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(weekNames[i],color=if(active)OnAccent else Muted,fontSize=11.sp);Text(d.dayOfMonth.toString(),modifier=Modifier.padding(top=3.dp,bottom=3.dp),color=if(active)OnAccent else if(d>today)Muted.copy(alpha=.5f) else Ink,fontWeight=FontWeight.SemiBold,fontSize=16.sp);Box(Modifier.size(4.dp).background(if(d==today){if(active)OnAccent else Teal}else Color.Transparent,CircleShape))}}}
        if(date!=today)BoxWithConstraints(Modifier.fillMaxWidth().clip(PlanSmallShape).background(SoftSurface).padding(horizontal=12.dp,vertical=4.dp)){
            val label:@Composable ()->Unit={Text("正在查看 "+date.format(DateTimeFormatter.ofPattern(if(date.year==today.year)"M 月 d 日" else "yyyy 年 M 月 d 日")),fontSize=14.sp,lineHeight=21.sp,fontWeight=FontWeight.Medium)}
            val returnToday:@Composable ()->Unit={PlanQuietButton({onDate(today)}){Icon(Icons.Outlined.Today,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("回到今天")}}
            if(LocalDensity.current.fontScale>1.3f||maxWidth<300.dp)Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(2.dp)){Box(Modifier.padding(top=8.dp)){label()};Box(Modifier.align(Alignment.End)){returnToday()}}
            else Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){Box(Modifier.weight(1f)){label()};returnToday()}
        }
        if(backupDue)BackupReminderCard(onBackup,onDeferBackup)
        homePreferences.order.filter{homePreferences.shows(it)}.forEach{module->
            when(module){
                HomeModule.PROGRESS->{
        TodayProgressCard(completed,total,date,today)
                }
                HomeModule.TASKS->{
                    Column(verticalArrangement=Arrangement.spacedBy(if(homePreferences.density==HomeDensity.COMPACT)8.dp else 12.dp)){
        Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
            BoxWithConstraints(Modifier.fillMaxWidth()){
                val title:@Composable ()->Unit={Text(if(date==today)"今日安排" else "当天安排",style=MaterialTheme.typography.titleLarge)}
                val actions:@Composable ()->Unit={
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        PlanPrimaryButton(onNew){Icon(Icons.Outlined.Add,null,Modifier.size(18.dp));Spacer(Modifier.width(4.dp));Text("新任务")}
                        Box{
                            IconButton({taskMenu=true}){Icon(Icons.Outlined.MoreHoriz,"安排管理",tint=Muted)}
                            DropdownMenu(taskMenu,{taskMenu=false}){
                                if(date==today)DropdownMenuItem(text={Text(if(arranging)"完成排序" else "整理顺序")},leadingIcon={Icon(Icons.Outlined.SwapVert,null)},onClick={if(arranging)finishSorting() else{sortingStartOrder=scheduled.map{it.id};arranging=true};taskMenu=false})
                                DropdownMenuItem(text={Text(if(data.collapseCompleted)"展开已完成" else "折叠已完成")},leadingIcon={Icon(if(data.collapseCompleted)Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,null)},onClick={onCollapse(!data.collapseCompleted);taskMenu=false})
                            }
                        }
                    }
                }
                if(LocalDensity.current.fontScale>=1.5f||maxWidth<330.dp)Column(verticalArrangement=Arrangement.spacedBy(4.dp)){title();actions()}
                else Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){Box(Modifier.weight(1f)){title()};actions()}
            }
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){(listOf("all" to "全部")+filters).forEach{(key,label)->PlanCategoryChip(label,selected=filter==key,onClick={filter=key})}}
        }
        if(arranging&&date==today)PlanSecondaryButton(finishSorting,Modifier.align(Alignment.End),enabled=!sortingSaving){Icon(Icons.Outlined.Done,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("完成排序")}
        if(arranging && date==today)Text("使用上下箭头调整顺序；置顶任务排在前面。",fontSize=12.sp,color=Muted)
        if(scheduled.isEmpty() && skipped.isEmpty()){
            if(date==today&&data.plans.none{!it.archived&&!it.recordsOnly}){
                Column(Modifier.fillMaxWidth().clip(PlanCardShape).background(SurfaceColor).padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Tag("第一步 · 选择一个小目标")
                    Text("让计划，从今天发生。",style=MaterialTheme.typography.titleMedium)
                    Text("添加一件想完成的事，从小任务开始。",color=Muted)
                    Button(onClick={onTemplate(templatePlans()[0])},modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(6.dp));Text("添加学习任务")}
                    TextButton(onClick=onNew,modifier=Modifier.align(Alignment.CenterHorizontally)){Text("自己创建任务")}
                }
            }else EmptyPanel(Icons.Outlined.WbSunny,if(date==today)"今天没有安排" else "这一天没有安排","休息也是计划的一部分。暂停和到期任务可在「计划」中管理。")
        }else{
            val filtered=scheduled.filter{filter=="all"||planCategoryKey(it)==filter}
            val sorting=arranging && date==today
            val shown=if(sorting)filtered else filtered.filter{!data.collapseCompleted||(amounts[it.id]?:0)<it.target}
            if(filtered.isEmpty() && skipped.none{filter=="all"||planCategoryKey(it)==filter})EmptyPanel(Icons.Outlined.FilterList,"这个分类暂无安排","切换分类，看看其他计划。")
            val hidden=if(data.collapseCompleted&&!sorting)filtered.count{(amounts[it.id]?:0)>=it.target}else 0
            shown.forEach{p->key(p.id){
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
                    TaskCard(p,amounts[p.id]?:0,{onCheck(p)},
                        onPin=if(date==today)({onPin(p)})else null,
                        onSkip=if(date==today&&!p.archived&&(amounts[p.id]?:0)==0)({skipping=p})else null,
                        data=data,compact=homePreferences.density==HomeDensity.COMPACT,isToday=date==today)
                    if(sorting){
                        val group=filtered.filter{it.pinned==p.pinned}.map{it.id}
                        val index=group.indexOf(p.id)
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
                            IconButton({onMove(p,-1,group)},enabled=index>0){Icon(Icons.Outlined.ArrowUpward,"上移${p.title}")}
                            IconButton({onMove(p,1,group)},enabled=index<group.lastIndex){Icon(Icons.Outlined.ArrowDownward,"下移${p.title}")}
                        }
                    }
                }
            }}
            if(hidden>0)PlanQuietButton({onCollapse(false)},Modifier.fillMaxWidth()){Icon(Icons.Outlined.CheckCircle,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("已完成 $hidden 项 · 点击展开")}
        }
        val visibleSkipped=skipped.filter{filter=="all"||planCategoryKey(it)==filter}
        if(visibleSkipped.isNotEmpty()){
            SectionTitle("已跳过","不计入未完成")
            visibleSkipped.forEach{p->Column(Modifier.fillMaxWidth().clip(PlanCardShape).background(SurfaceColor).padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){Text(p.title,Modifier.weight(1f),fontWeight=FontWeight.SemiBold);if(date==today)TextButton({onUndoSkip(p)}){Text("撤销跳过")}}
                p.skips.first{it.date==date.toString()}.reason.takeIf{it.isNotBlank()}?.let{Text(it,fontSize=13.sp,color=Muted)}
            }}
        }
                    }
                }
                HomeModule.RECENT->HomeRecent(data)
            }
        }
    }
    skipping?.let{p->SkipDayDialog(p,{skipping=null}){reason->onSkip(p,reason){skipping=null}}}
}

@Composable private fun HomeRecent(data:AppData){
    val recent=remember(data.checkIns){homeRecentEntries(data.checkIns)}
    val plans=remember(data.plans){data.plans.associateBy{it.id}}
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        SectionTitle("最近积累")
        if(recent.isEmpty())Text("完成一次打卡，把进步留在这里。",color=Muted,fontSize=14.sp)
        recent.forEach{entry->val plan=plans[entry.planId]
            Row(Modifier.fillMaxWidth().clip(PlanCardShape).background(SurfaceColor).padding(PlanCardPadding),verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                Icon(if(plan==null)Icons.Outlined.CheckCircle else planCategoryIcon(plan,data),null,tint=Teal,modifier=Modifier.size(22.dp))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){
                    Text(plan?.title?:"历史任务",fontWeight=FontWeight.SemiBold)
                    Text(entry.date+if(plan?.tracking==TrackingMode.QUANTITY)" · ${formatQuantity(entry.amount,plan)} ${plan.unit}" else " · 已完成",fontSize=12.sp,color=Muted)
                    if(entry.note.isNotBlank())Text(entry.note,color=Muted,maxLines=3,overflow=TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable fun TaskCard(p:Plan,amount:Int,onCheck:()->Unit,onPin:(()->Unit)?=null,onSkip:(()->Unit)?=null,data:AppData=AppData(),compact:Boolean=false,isToday:Boolean=true){
    val done=amount>=p.target
    var menu by remember(p.id){mutableStateOf(false)}
    val background by animateColorAsState(if(done)SoftSurface else SurfaceColor,tween(180),label="taskSurface")
    val progress by animateFloatAsState((amount.toFloat()/p.target).coerceIn(0f,1f),tween(220),label="taskProgress")
    Column(Modifier.fillMaxWidth().clip(PlanCardShape).background(background).then(if(PlanStyle==ThemeStyle.PAPER)Modifier.border(1.dp,Line,PlanCardShape)else Modifier).clickable(onClick=onCheck).padding(if(compact)12.dp else PlanCardPadding),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
            Box(Modifier.size(if(compact)32.dp else 36.dp).clip(PlanSmallShape).background(planCategoryColor(p,data)),contentAlignment=Alignment.Center){Icon(planCategoryIcon(p,data),null,tint=Ink,modifier=Modifier.size(20.dp))}
            Column(Modifier.weight(1f)){
                Text(p.title,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text("${planCategoryName(p,data)} · ${frequency(p)}"+if(p.pinned)" · 置顶" else "",color=Muted,fontSize=12.sp)
            }
            if(onPin!=null||onSkip!=null)Box{
                IconButton({menu=true},Modifier.size(48.dp)){Icon(Icons.Outlined.MoreHoriz,"${p.title}的更多操作",tint=Muted,modifier=Modifier.size(20.dp))}
                DropdownMenu(menu,{menu=false}){
                    if(onPin!=null)DropdownMenuItem(text={Text(if(p.pinned)"取消置顶" else "置顶")},leadingIcon={Icon(Icons.Outlined.PushPin,null)},onClick={menu=false;onPin()})
                    if(onSkip!=null)DropdownMenuItem(text={Text(if(isToday)"跳过今天" else "跳过当天")},leadingIcon={Icon(Icons.Outlined.EventBusy,null)},onClick={menu=false;onSkip()})
                }
            }
        }
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Text(if(p.tracking==TrackingMode.QUANTITY)"${formatQuantity(amount,p)} / ${formatQuantity(p.target,p)} ${p.unit}" else if(done){if(isToday)"今天已完成" else "当天已完成"} else "完成后打卡",Modifier.weight(1f),fontWeight=if(p.tracking==TrackingMode.QUANTITY)FontWeight.Medium else FontWeight.Normal,fontSize=14.sp,color=if(done)Teal else Muted)
            PlanSecondaryButton(onCheck){
                Crossfade(done,animationSpec=tween(180),label="checkmark"){checked->Icon(if(checked)Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,null,Modifier.size(20.dp))}
                Spacer(Modifier.width(6.dp));Text(if(done)"已完成" else if(amount>0)"继续打卡" else "待打卡",fontSize=13.sp)
            }
        }
        if(p.tracking==TrackingMode.QUANTITY)LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),color=Teal,trackColor=Line,drawStopIndicator={})
    }
}

fun templatePlans()=listOf(
    Plan(title="英语学习",category=Category.STUDY,target=1,unit="次",tracking=TrackingMode.TASK),
    Plan(title="专业课复习",category=Category.STUDY,target=1,unit="次",weekdays=(1..5).toSet(),tracking=TrackingMode.TASK),
    Plan(title="阅读",category=Category.STUDY,target=1,unit="次",tracking=TrackingMode.TASK),
    Plan(title="运动",category=Category.FITNESS,target=1,unit="次",weekdays=setOf(1,3,5),tracking=TrackingMode.TASK),
    Plan(title="每日拉伸",category=Category.FITNESS,target=1,unit="次",tracking=TrackingMode.TASK),
    Plan(title="睡前复盘",category=Category.LIFE,target=1,unit="次",tracking=TrackingMode.TASK)
)

@Composable fun FocusScreen(data:AppData,timer:ClockState,remaining:Int,onDuration:(Int)->Unit,onPlan:(String?)->Unit,onToggle:()->Unit,onReset:()->Unit,onToday:()->Unit,onReminders:()->Unit,
    preferences:FocusPreferences,onPreferencesChange:(FocusPreferences)->Unit){
    val arcLine=Line;val arcTeal=Teal
    val fontScale=LocalDensity.current.fontScale
    val started=timer.id.isNotEmpty()&&!timer.completed
    val currentPlan=data.plans.find{it.id==timer.planId}
    var menu by remember{mutableStateOf(false)}
    var showDuration by rememberSaveable{mutableStateOf(false)}
    var manageFavorites by rememberSaveable{mutableStateOf(false)}
    var preferenceError by rememberSaveable{mutableStateOf("")}
    val localToday=LocalDate.now()
    val todaySeconds=data.focusRecords.filter{Instant.ofEpochMilli(it.completedAt).atZone(ZoneId.systemDefault()).toLocalDate()==localToday}.sumOf{it.seconds.toLong()}
    PageColumn{
        Column{Text("把这一刻，留给自己。",style=MaterialTheme.typography.headlineMedium);Text("一次只做一件事。",modifier=Modifier.padding(top=8.dp),color=Muted)}
        Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center){OutlinedButton(onClick={menu=true},enabled=!started,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.MenuBook,null,Modifier.size(19.dp));Text(currentPlan?.title?:"自由专注",modifier=Modifier.weight(1f).padding(horizontal=8.dp),maxLines=2,overflow=TextOverflow.Ellipsis);Icon(Icons.Outlined.ExpandMore,null)};DropdownMenu(expanded=menu,onDismissRequest={menu=false}){DropdownMenuItem(text={Text("自由专注")},onClick={onPlan(null);menu=false});focusPlansOrdered(data).forEach{p->DropdownMenuItem(text={Text(p.title)},onClick={onPlan(p.id);menu=false})}}}
        BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical=8.dp),contentAlignment=Alignment.Center){
            val diameter=minOf(maxWidth,(240*fontScale.coerceIn(1f,1.4f)).dp)
            val timeText="%02d:%02d".format(remaining/60,remaining%60)
            val baseSize=if(remaining>=60000)44f else if(remaining>=6000)52f else 58f
            val timeMeasurer=rememberTextMeasurer()
            val measured=timeMeasurer.measure(timeText,TextStyle(fontSize=baseSize.sp,fontWeight=FontWeight.Light),softWrap=false).size.width
            val available=with(LocalDensity.current){(diameter-48.dp).toPx()}
            val timeSize=(baseSize*(available/measured.coerceAtLeast(1)).coerceAtMost(1f)).sp
            Box(Modifier.size(diameter).clip(if(PlanStyle==ThemeStyle.SOFT)CircleShape else PlanCardShape).background(SurfaceColor).planStyleDecoration(),contentAlignment=Alignment.Center){
                Canvas(Modifier.fillMaxSize().padding(12.dp)){drawArc(arcLine,-90f,360f,false,style=Stroke(7.dp.toPx(),cap=StrokeCap.Round));val fraction=1f-remaining.toFloat()/(timer.minutes*60);if(fraction>0)drawArc(arcTeal,-90f,fraction*360f,false,style=Stroke(7.dp.toPx(),cap=StrokeCap.Round))}
                Column(horizontalAlignment=Alignment.CenterHorizontally){Tag(if(timer.completed)"专注完成" else if(timer.running)"专注进行中" else if(started)"已暂停" else "准备好了就开始");Text(timeText,fontSize=timeSize,maxLines=1,softWrap=false,fontWeight=FontWeight.Light,color=Ink,modifier=Modifier.padding(vertical=16.dp));Text(if(timer.completed)"每一分钟都算数" else "慢慢来，也是在向前",color=Muted,fontSize=14.sp)}
            }
        }
        PlanPrimaryButton(onClick=onToggle,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)){Icon(if(timer.completed)Icons.Outlined.Replay else if(timer.running)Icons.Outlined.Pause else Icons.Outlined.PlayArrow,null);Spacer(Modifier.width(9.dp));Text(if(timer.completed)"准备下一次专注" else if(timer.running)"暂停一下" else if(started)"继续专注" else "开始专注",fontSize=16.sp)}
        if(started)PlanQuietButton(onClick=onReset,modifier=Modifier.align(Alignment.CenterHorizontally)){Text("结束本次",color=Muted)}
        if(timer.completed)PlanSecondaryButton(onClick=onToday,modifier=Modifier.fillMaxWidth()){Text("回到今日，确认任务进度")}
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text("常用时长",fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                TextButton(onClick={manageFavorites=true},enabled=!started){Text("管理")}
            }
            FormErrorBanner(preferenceError)
            BoxWithConstraints(Modifier.fillMaxWidth()){
                val presetColumns=if(maxWidth<290.dp||fontScale>1.5f)1 else if(maxWidth<330.dp||fontScale>1.15f)2 else 4
                Column{preferences.favorites.chunked(presetColumns).forEach{row->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        row.forEach{m->FilterChip(selected=timer.minutes==m,onClick={
                            try{onDuration(m);preferenceError=""}catch(e:Exception){preferenceError=e.message?:"未能保存时长"}
                        },enabled=!started,label={Text("$m 分钟",fontSize=12.sp)},modifier=Modifier.weight(1f))}
                        repeat(presetColumns-row.size){Spacer(Modifier.weight(1f))}
                    }
                }}
            }
            PlanSecondaryButton(onClick={showDuration=true},enabled=!started,modifier=Modifier.fillMaxWidth()){
                Icon(Icons.Outlined.Edit,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp))
                Text("自定义时长 · ${timer.minutes} 分钟")
            }
            Text(if(started)"本次专注的任务与时长已锁定。" else "计时任务会带入目标时长；其他任务使用上次手动选择的时长。",fontSize=12.sp,color=Muted)
        }
        Row(Modifier.fillMaxWidth().clip(PlanCardShape).background(Mint).padding(PlanCardPadding),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.AutoAwesome,null,tint=Teal);Column(Modifier.padding(start=12.dp)){Text("今天已专注 ${todaySeconds/60} 分钟",fontWeight=FontWeight.SemiBold);Text("完成一段完整计时后，自动记入统计。",color=Muted,fontSize=12.sp)}}
        TextButton(onReminders,modifier=Modifier.align(Alignment.CenterHorizontally)){Icon(Icons.Outlined.NotificationsActive,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("专注提醒设置")}
        Text("专注时长与任务打卡分别记录。完成后可在今日页更新任务进度。",color=Muted,fontSize=12.sp,lineHeight=20.sp)
    }
    if(showDuration&&!started)FocusDurationDialog(timer.minutes,preferences,{showDuration=false}){minutes,saveFavorite->
        if(saveFavorite)onPreferencesChange(preferences.withFavorite(minutes))
        onDuration(minutes);preferenceError="";showDuration=false
    }
    if(manageFavorites&&!started)FocusFavoritesDialog(preferences,{manageFavorites=false}){updated->
        onPreferencesChange(updated);preferenceError="";manageFavorites=false
    }
}

@Composable fun FocusDurationDialog(initial:Int,preferences:FocusPreferences,onDismiss:()->Unit,onSave:(Int,Boolean)->Unit){
    var value by rememberSaveable(initial){mutableStateOf(initial.toString())}
    var saveFavorite by rememberSaveable{mutableStateOf(false)}
    var error by rememberSaveable{mutableStateOf("")}
    FormDialog(title="自定义专注时长",error=error,onDismiss=onDismiss,confirmLabel="确定",onConfirm={
        val minutes=parseFocusMinutes(value)
        if(minutes==null)error="请输入 1–$MAX_FOCUS_MINUTES 的整数分钟"
        else try{
            if(saveFavorite)preferences.withFavorite(minutes)
            onSave(minutes,saveFavorite)
        }catch(e:Exception){error=e.message?:"未能保存时长"}
    }){
        OutlinedTextField(value,{if(it.length<=8){value=it;error=""}},label={Text("本次时长（分钟）")},isError=error.isNotBlank(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth())
        Text("可设置 1–$MAX_FOCUS_MINUTES 分钟",color=Muted,fontSize=13.sp)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).toggleable(value=saveFavorite,role=Role.Checkbox,onValueChange={saveFavorite=it;error=""}).padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
            Checkbox(checked=saveFavorite,onCheckedChange=null)
            Column(Modifier.padding(start=8.dp).weight(1f)){
                Text("保存为常用")
                Text(if(parseFocusMinutes(value) in preferences.favorites)"此时长已在常用列表中" else "最多 $MAX_FOCUS_FAVORITES 项，可在专注页管理",color=Muted,fontSize=12.sp)
            }
        }
    }
}

@Composable fun FocusFavoritesDialog(preferences:FocusPreferences,onDismiss:()->Unit,onSave:(FocusPreferences)->Unit){
    var encoded by rememberSaveable{mutableStateOf(encodeFocusFavorites(preferences.favorites))}
    var error by rememberSaveable{mutableStateOf("")}
    val selected=preferences.copy(favorites=parseFocusFavorites(encoded)?:DEFAULT_FOCUS_FAVORITES)
    FormDialog(title="管理常用时长",subtitle="保留 1–$MAX_FOCUS_FAVORITES 项，在自定义时长中添加",error=error,onDismiss=onDismiss,confirmLabel="保存",onConfirm={
        try{onSave(selected)}catch(e:Exception){error=e.message?:"未能保存常用时长"}
    }){
        selected.favorites.forEach{minutes->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SoftSurface).padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically){
                Text("$minutes 分钟",modifier=Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
                IconButton(onClick={encoded=encodeFocusFavorites(selected.withoutFavorite(minutes).favorites);error=""},enabled=selected.favorites.size>1){
                    Icon(Icons.Outlined.RemoveCircleOutline,"移除 $minutes 分钟")
                }
            }
        }
        Text("移除快捷按钮不会改变本次时长，也不会清除专注记录。",color=Muted,fontSize=12.sp)
        OutlinedButton(onClick={encoded=encodeFocusFavorites(DEFAULT_FOCUS_FAVORITES);error=""},modifier=Modifier.fillMaxWidth()){
            Text("恢复默认常用时长")
        }
    }
}

@Composable fun Metric(label:String,value:String,unit:String,modifier:Modifier){Column(modifier.clip(PlanCardShape).background(SurfaceColor).padding(PlanCardPadding),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(label,color=Muted,fontSize=14.sp);Text(value,fontSize=if(value.length>7)21.sp else if(value.length>5)24.sp else 30.sp,fontWeight=FontWeight.Bold);Text(unit,fontSize=12.sp,color=Muted)}}
@Composable fun SettingsRow(icon:ImageVector,title:String,subtitle:String,onClick:()->Unit){Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(18.dp),verticalAlignment=Alignment.CenterVertically){Icon(icon,null,tint=Teal);Column(Modifier.weight(1f).padding(horizontal=12.dp)){Text(title,fontWeight=FontWeight.SemiBold);Text(subtitle,fontSize=12.sp,color=Muted)};Icon(Icons.Outlined.ChevronRight,null,tint=Muted)}}
@Composable fun EmptyPanel(icon:ImageVector,title:String,subtitle:String){Column(Modifier.fillMaxWidth().clip(PlanCardShape).background(SurfaceColor).padding(PlanCardPadding),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){Icon(icon,null,Modifier.size(32.dp),tint=Teal);Text(title,fontWeight=FontWeight.SemiBold);Text(subtitle,color=Muted,fontSize=14.sp)}}

@Composable fun NicknameDialog(initial:String,onDismiss:()->Unit,onSave:(String)->Unit){
    var value by rememberSaveable{mutableStateOf(initial)}
    var error by rememberSaveable{mutableStateOf("")}
    FormDialog(title="设置昵称",error=error,onDismiss=onDismiss,confirmLabel="保存",onConfirm={
        if(value.isBlank())error="请填写昵称" else try{onSave(value.trim())}catch(e:Exception){error=e.message?:"昵称未保存"}
    }){OutlinedTextField(value,{if(it.length<=30){value=it;error=""}},label={Text("昵称")},singleLine=true,isError=error.isNotBlank(),modifier=Modifier.fillMaxWidth())}
}

