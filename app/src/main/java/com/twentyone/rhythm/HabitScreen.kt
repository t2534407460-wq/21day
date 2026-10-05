package com.twentyone.rhythm

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.*
import java.time.format.DateTimeFormatter

private val habitDate=DateTimeFormatter.ofPattern("MM.dd")
private val weekNames=listOf("一","二","三","四","五","六","日")
private fun frequency(days:Set<Int>)=if(days.size==7) "每天" else "每周"+days.sorted().joinToString("、") { weekNames[it-1] }

@Composable fun HabitScreen(a:MainActivity,today:LocalDate,revision:Int,scrollState:ScrollState,onSettings:()->Unit) {
    val store=remember { HabitStore(a) }
    val habits=remember(revision) { store.habits() };val entries=remember(revision) { store.entries() }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(selected) { scrollState.scrollTo(0) }
    var create by remember { mutableStateOf(false) };var editing by remember { mutableStateOf<Habit?>(null) }
    var seed by remember { mutableStateOf<Habit?>(null) };var record by remember { mutableStateOf<Pair<Habit,LocalDate>?>(null) }
    var archive by remember { mutableStateOf<Habit?>(null) };var message by remember { mutableStateOf<String?>(null) }
    var history by rememberSaveable { mutableStateOf(false) }
    fun changed() { HabitReminders.schedule(a) }
    val current=habits.find { it.id==selected }
    BackHandler(enabled=current!=null && !create && editing==null && record==null && archive==null) { selected=null }
    if(current==null) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Eyebrow("SMALL STEPS / 21 DAYS");Heading("把想做的，变成日常。") }
        }
        val active=habits.filter { !it.archived && it.end>=today }
        val due=active.filter { it.scheduled(today) }
        val recorded=due.count { h -> entries.any { it.habitId==h.id && it.date==today } }
        Sheet(color=Sage) {
            Text("${today.format(habitDate)} · 今日习惯",fontWeight=FontWeight.SemiBold)
            Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) { LargeNumber("$recorded / ${due.size}");Text("项已记录",color=Muted,modifier=Modifier.padding(bottom=7.dp)) }
            if(due.isNotEmpty()) LinearProgressIndicator(progress={recorded.toFloat()/due.size},modifier=Modifier.fillMaxWidth(),color=Moss,trackColor=Color.White,drawStopIndicator={})
            SmallNote(if(active.isEmpty()) "从一件小事开始，给自己一个 21 天的尝试。" else if(due.isEmpty()) "今天没有安排。休息日也是计划的一部分。" else "记录真实进展。偶尔中断，已有积累仍然保留。")
            Primary("添加习惯") { seed=null;create=true }
        }
        if(active.any { h -> h.rules.any { it.reminder!=null } }) {
            val nm=a.getSystemService(android.app.NotificationManager::class.java)
            val notificationOk=nm.areNotificationsEnabled() && nm.getNotificationChannel(HabitReminders.CHANNEL)?.importance!=android.app.NotificationManager.IMPORTANCE_NONE
            if(!notificationOk || !Alarms.exactAllowed(a)) TextButton(onClick=onSettings) { Text(if(!notificationOk) "开启通知，接收习惯提醒 →" else "提醒可能延迟，检查精确闹钟权限 →") }
        }
        SettingLink("复盘看板","每周日与每轮21天，按习惯查看") { a.startActivity(android.content.Intent(a,ReviewActivity::class.java)) }
        if(habits.isEmpty()) {
            Text("选一个起点",fontWeight=FontWeight.SemiBold)
            listOf("阅读" to "每天读 20 分钟，留下一句收获", "运动" to "每周一、三、五，动起来 30 分钟", "戒烟" to "记录每天实际支数，目标为 0 支").forEach { (title,subtitle) ->
                SettingLink(title,subtitle) { seed=habitTemplate(title,today);create=true }
            }
            SmallNote("也可以自定义名称、目标、执行日期与提醒。21 天是尝试和复盘周期。")
        } else {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(!history,{history=false},label={Text("进行中 ${active.size}")},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Sage,selectedLabelColor=Ink))
                FilterChip(history,{history=true},label={Text("已结束 / 归档 ${habits.size-active.size}")},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Sage,selectedLabelColor=Ink))
            }
            val visible=if(history) habits.filter { it !in active }.reversed() else active.sortedWith(compareBy<Habit> { !it.scheduled(today) }.thenBy { it.rule(today).reminder ?: 1440 })
            if(visible.isEmpty()) SmallNote(if(history) "完成或归档的计划会留在这里。" else "当前没有进行中的计划，可以新建一轮。")
            visible.forEach { h ->
                val e=entries.find { it.habitId==h.id && it.date==today }
                Sheet {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { selected=h.id }) {
                            Text(h.name,fontSize=21.sp,fontWeight=FontWeight.Medium)
                            SmallNote(if(h.archived) "已归档 · ${h.start.format(habitDate)} 开始" else if(today>h.end) "本轮结束 · 21 天" else if(today<h.start) "${h.start.format(habitDate)} 开始" else "第 ${h.day(today)} / 21 天 · ${frequency(h.rule(today).days)}")
                        }
                        IconButton(onClick={selected=h.id}) { Icon(Icons.Outlined.ChevronRight,"查看${h.name}") }
                    }
                    Text(h.goal(today),color=Moss)
                    SmallNote(if(h.archived || today>h.end) "记录与趋势已保留" else if(!h.scheduled(today)) "今天无需打卡" else if(e==null) "尚未记录 · "+(h.rule(today).reminder?.let { "${timeText(it)} 提醒" } ?: "未设置提醒") else entrySummary(h,e))
                    if(!h.archived && h.scheduled(today)) OutlinedButton(onClick={record=h to today},modifier=Modifier.fillMaxWidth()) { Text(if(e==null) "记录${h.name}" else "修改${h.name}记录") }
                }
            }
        }
    } else {
        TextButton(onClick={selected=null}) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,null);Text("全部习惯") }
        HabitDetail(current,entries,today,onRecord={record=current to it},onEdit={editing=current},onArchive={archive=current},onRenew={seed=current;create=true},onReview={a.startActivity(android.content.Intent(a,ReviewActivity::class.java).putExtra("subject",current.id).putExtra("tab",if(today>current.end) "cycle" else "week"))},action={
            HabitAction(a,store,current,entries.find { it.habitId==current.id && it.date==today },clock(),onManual={record=current to today},onMessage={message=it})
        })
    }
    val sleepStore=remember { Store(a) }
    val beforeBed=sleepStore.plan?.let { Schedule.nextWindDown(it,sleepStore.rules,LocalDateTime.now()) }?.let { it.hour*60+it.minute } ?: 22*60
    if(create || editing!=null) HabitEditor(editing,seed,today,beforeBed,onDismiss={create=false;editing=null;seed=null}) { h ->
        runCatching { store.save(h);changed() }.onSuccess { create=false;editing=null;seed=null;selected=h.id }.onFailure { message=it.message ?: "保存失败" }
    }
    record?.let { (h,date) -> HabitRecordDialog(h,date,entries.find { it.habitId==h.id && it.date==date },onDismiss={record=null},onSave={ e ->
        runCatching { store.record(e);HabitReminders.cancelNotice(a,h.id);changed() }.onSuccess { record=null }.onFailure { message=it.message ?: "保存失败" }
    },onRemove={store.removeEntry(h.id,date);changed();record=null}) }
    archive?.let { h -> AlertDialog(onDismissRequest={archive=null},title={Text("归档${h.name}？")},text={Text("停止这轮提醒，保留已有记录和趋势。之后可用相同设置开始新一轮。")},confirmButton={TextButton(onClick={runCatching { store.save(h.copy(archivedOn=today));HabitReminders.cancelNotice(a,h.id);changed();archive=null }.onFailure { message=it.message }}){Text("确认归档")}},dismissButton={TextButton(onClick={archive=null}){Text("取消")}}) }
    message?.let { AlertDialog(onDismissRequest={message=null},text={Text(it)},confirmButton={TextButton(onClick={message=null}){Text("知道了")}}) }
}

private fun habitTemplate(name:String,today:LocalDate)=when(name) {
    "阅读"->Habit(name=name,start=today,mode=HabitMode.AT_LEAST,unit="分钟",rules=listOf(HabitRule(today,target=20)))
    "运动"->Habit(name=name,start=today,mode=HabitMode.AT_LEAST,unit="分钟",rules=listOf(HabitRule(today,days=setOf(1,3,5),target=30,reminder=18*60)))
    "戒烟"->Habit(name=name,start=today,mode=HabitMode.AT_MOST,unit="支",rules=listOf(HabitRule(today,target=0,reminder=22*60)),input=HabitInput.DAILY,smoking=true)
    else->Habit(name="",start=today,mode=HabitMode.AT_LEAST,rules=listOf(HabitRule(today)),input=HabitInput.COUNT)
}
private fun entrySummary(h:Habit,e:HabitEntry)=(if(h.mode==HabitMode.CHECK) if(e.value==1) "已完成" else "未完成" else "${e.value} ${h.unit}")+" · "+if(h.reached(e.date,e.value)) "达到当日目标" else "已记录，未达目标"

@Composable private fun HabitDetail(h:Habit,entries:List<HabitEntry>,today:LocalDate,onRecord:(LocalDate)->Unit,onEdit:()->Unit,onArchive:()->Unit,onRenew:()->Unit,onReview:()->Unit,action:@Composable ()->Unit) {
    val own=entries.filter { it.habitId==h.id };val stats=HabitAnalysis.stats(h,own,today)
    Heading(h.name)
    SmallNote("${h.start} — ${h.end} · "+if(h.archived) "已归档" else if(today>h.end) "本轮结束" else if(today<h.start) "尚未开始" else "第 ${h.day(today)} / 21 天")
    Sheet(color=Sage) {
        Text(h.goal(today),fontSize=22.sp,fontWeight=FontWeight.Medium)
        SmallNote(frequency(h.rule(today).days)+" · "+(h.rule(today).reminder?.let { "${timeText(it)} 提醒" } ?: "不提醒"))
        if(!h.archived) h.rules.firstOrNull { it.from>today }?.let { SmallNote("${it.from} 起：${h.goal(it.from)} · ${frequency(it.days)} · "+(it.reminder?.let { m->timeText(m) } ?: "不提醒")) }
        if(!h.archived) Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) { action() }
        if(!h.archived && h.scheduled(today)) TextButton(onClick={onRecord(today)},modifier=Modifier.fillMaxWidth()) { Text(if(own.any { it.date==today }) "修改今日记录" else if(h.input==HabitInput.DAILY) "手动填写今日记录" else "手动填写今日总量") }
        else SmallNote(if(h.archived || today>h.end) "这一轮记录已保留，可回顾后开始下一轮。" else "今天不在执行日期内。")
        if(!h.archived && today<h.end) TextButton(onClick=onEdit) { Text("调整计划") }
    }
    SettingLink("查看该习惯的 AI 复盘","每周与21天看板",onReview)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        Column { LargeNumber("${stats.recorded}");SmallNote("已记录 / ${stats.scheduled} 计划日") }
        Column { LargeNumber("${stats.reached}");SmallNote("达到当日目标") }
        Column { LargeNumber("${stats.missed}");SmallNote("过去未记录") }
    }
    Sheet {
        Text("21 天记录",fontWeight=FontWeight.SemiBold)
        SmallNote("点日期记录或补记；数字为日期。✓ 达标，· 已记录未达标，— 休息，空白为未记录。")
        h.dates().chunked(7).forEach { week -> Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            week.forEach { date -> val e=own.find { it.date==date };val due=h.scheduled(date);val reached=e!=null && h.reached(date,e.value)
                Surface(onClick={onRecord(date)},enabled=(h.archived && e!=null) || (!h.archived && due && date<=today),shape=RoundedCornerShape(10.dp),color=if(reached) Sage else Color.Transparent,
                    modifier=Modifier.weight(1f).semantics { contentDescription="${h.name} $date "+if(e!=null) entrySummary(h,e) else if(!due) "休息" else "未记录" }) {
                    Column(Modifier.padding(vertical=9.dp),horizontalAlignment=Alignment.CenterHorizontally) { Text(date.format(DateTimeFormatter.ofPattern("M/d")),fontSize=11.sp,color=if(date>today) Muted else Ink);Text(if(reached) "✓" else if(e!=null) "·" else if(!due) "—" else "",modifier=Modifier.height(22.dp),color=Moss) }
                }
            }
        } }
        if(h.archived) SmallNote("归档后仅供回顾，未开始的日期不计入计划日。")
    }
    Sheet {
        Text("每周趋势",fontWeight=FontWeight.SemiBold)
        if(h.mode!=HabitMode.CHECK) HabitChart(h,own,today)
        repeat(3) { i -> val first=h.start.plusDays(i*7L);val last=first.plusDays(6);val s=HabitAnalysis.stats(h,own,today,first,last)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) { Text("第 ${i+1} 周",fontWeight=FontWeight.Medium);SmallNote("${first.format(habitDate)}—${last.format(habitDate)}") }
                Column(horizontalAlignment=Alignment.End) { Text(if(first>today) "尚未开始" else "${s.reached} 达标 / ${s.recorded} 已记录",fontSize=14.sp);if(h.mode!=HabitMode.CHECK) SmallNote(s.average?.let { "记录日均 %.1f ${h.unit}".format(it) } ?: "暂无数值") }
            }
        }
        SmallNote("达标按记录当天的目标计算，日均只统计已记录日。未记录不当作零，也不推断已经完成。")
    }
    Sheet(color=Sage) { Text("下一步，做一点调整",fontWeight=FontWeight.SemiBold);Text(HabitAnalysis.advice(h,own,today),fontSize=14.sp);SmallNote("基于本机打卡的复盘提示。先回看事实，再决定是否调整。") }
    if(own.isNotEmpty()) Sheet {
        Text("最近记录",fontWeight=FontWeight.SemiBold)
        own.sortedByDescending { it.date }.take(7).forEach { e ->
            Column(Modifier.fillMaxWidth().clickable { onRecord(e.date) }) {
                Text("${e.date.format(habitDate)} · ${entrySummary(h,e)}",fontSize=14.sp)
                SmallNote("目标：${h.goal(e.date)}"+(if(e.note.isNotBlank()) "\n${e.note}" else ""))
            }
        }
    }
    if(h.archived || today>h.end) Primary("开始新一轮",click=onRenew)
    else TextButton(onClick=onArchive) { Text("归档此计划",color=Muted) }
}

@Composable private fun HabitChart(h:Habit,entries:List<HabitEntry>,today:LocalDate) {
    val dates=h.dates();val values=dates.map { date -> entries.find { it.date==date }?.value }
    val ceiling=maxOf(1,(values.filterNotNull()+dates.map { h.rule(it).target }).maxOrNull() ?: 1).toFloat()
    SmallNote("纵轴 0—${ceiling.toInt()} ${h.unit}")
    Canvas(Modifier.fillMaxWidth().height(110.dp).semantics { contentDescription="21天数值趋势，柱为实际记录，短线为当日目标，缺失不画柱" }) {
        val step=size.width/21;val height=size.height-8.dp.toPx()
        drawLine(Sage,Offset(0f,height),Offset(size.width,height),1.dp.toPx())
        dates.forEachIndexed { i,date -> if(h.scheduled(date) && date<=today) {
            val x=step*(i+.5f);val targetY=height*(1-h.rule(date).target/ceiling)
            values[i]?.let { value -> if(value==0) drawCircle(Moss,2.dp.toPx(),Offset(x,height)) else drawLine(Moss,Offset(x,height),Offset(x,height*(1-value/ceiling)),step*.45f) }
            drawLine(Clay,Offset(x-step*.35f,targetY),Offset(x+step*.35f,targetY),2.dp.toPx())
        } }
    }
    SmallNote("左：第1天 → 右：第21天 · 绿柱/点：实际${h.unit} · 棕线：目标；缺失留空")
}

@Composable private fun HabitEditor(original:Habit?,seed:Habit?,today:LocalDate,beforeBed:Int,onDismiss:()->Unit,onSave:(Habit)->Unit) {
    val initial=original ?: seed ?: habitTemplate("",today)
    val effective=if(original==null) today else maxOf(original.start,today.plusDays(1))
    var name by rememberSaveable { mutableStateOf(initial.name) };var mode by rememberSaveable { mutableStateOf(initial.mode.name) }
    var input by rememberSaveable { mutableStateOf(initial.input.name) };var smoking by rememberSaveable { mutableStateOf(initial.smoking) }
    var visual by rememberSaveable { mutableStateOf(initial.visual.name) }
    var unit by rememberSaveable { mutableStateOf(initial.unit) };var target by rememberSaveable { mutableStateOf(initial.rule(effective).target.toString()) }
    var start by rememberSaveable { mutableStateOf(if(original==null) today.toString() else original.start.toString()) }
    var days by remember { mutableStateOf(initial.rule(effective).days) };var reminder by rememberSaveable { mutableStateOf(initial.rule(effective).reminder!=null) }
    var time by rememberSaveable { mutableStateOf(timeText(if(original==null && initial.smoking) beforeBed else initial.rule(effective).reminder ?: 21*60)) };var error by remember { mutableStateOf("") }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(color=Paper,shape=RoundedCornerShape(24.dp),modifier=Modifier.fillMaxWidth().padding(14.dp).heightIn(max=760.dp)) {
            Column(Modifier.imePadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(if(original==null) "添加习惯" else "调整习惯计划",fontSize=22.sp,fontWeight=FontWeight.SemiBold)
                Column(Modifier.weight(1f,false).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    if(original==null) Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf("阅读","运动","戒烟","自定义").forEach { type -> AssistChip(onClick={val h=habitTemplate(type,today);name=h.name;mode=h.mode.name;input=h.input.name;smoking=h.smoking;visual=h.visual.name;unit=h.unit;target=h.rules.first().target.toString();days=h.rules.first().days;time=timeText(if(h.smoking) beforeBed else h.rules.first().reminder!!)},label={Text(type)}) }
                    }
                    OutlinedTextField(name,{name=it.take(40)},label={Text("习惯名称")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    if(original==null) {
                        DateInput("开始日期 · 一轮21天",start,minDate=today){start=it}
                        Text(if(smoking) "戒烟方式" else "打卡方式",fontWeight=FontWeight.Medium)
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            val choices=if(smoking) listOf(HabitInput.DAILY to "断崖式戒烟",HabitInput.COUNT to "每日控量") else listOf(HabitInput.TIMER to "时段计时",HabitInput.COUNT to "次数打卡")
                            choices.forEach { (kind,title) -> FilterChip(input==kind.name,{
                                input=kind.name
                                if(smoking) { mode=HabitMode.AT_MOST.name;unit="支";target=if(kind==HabitInput.DAILY) "0" else "10" }
                                else { mode=HabitMode.AT_LEAST.name;unit=if(kind==HabitInput.TIMER) "分钟" else "次";target=if(kind==HabitInput.TIMER) "20" else "1" }
                            },label={Text(title)},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Sage,selectedLabelColor=Ink)) }
                        }
                        if(input==HabitInput.COUNT.name && !smoking) Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            listOf(HabitMode.AT_LEAST,HabitMode.AT_MOST).forEach { kind -> FilterChip(mode==kind.name,{mode=kind.name},label={Text(kind.label)},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Sage,selectedLabelColor=Ink)) }
                        }
                    } else SmallNote("本轮：${original.input.label} · ${original.mode.label}（${original.unit}）。目标、频率和提醒从 $effective 生效，过去与今天保持原安排。")
                    SmallNote(when(HabitInput.valueOf(input)) { HabitInput.TIMER->"长按开始与结束，累计实际分钟并保留时段。";HabitInput.COUNT->"每次长按记录 1 $unit，一天内累计；不会按住连加。";HabitInput.DAILY->if(smoking) "每日睡前长按确认零支；未打卡不会自动算零。" else "每天长按确认一次。";HabitInput.MANUAL->"填写当天实际总量。" })
                    if(mode!=HabitMode.CHECK.name && input!=HabitInput.DAILY.name) {
                        OutlinedTextField(target,{target=it.take(6)},label={Text(if(mode==HabitMode.AT_MOST.name) "每日上限（可为0）" else "每日目标")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth())
                        if(original==null && input==HabitInput.COUNT.name && !smoking) OutlinedTextField(unit,{unit=it.take(8)},label={Text("每次计数单位，如次、杯、页")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    }
                    Text("每周哪些天执行",fontWeight=FontWeight.Medium)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(3.dp)) { weekNames.forEachIndexed { i,text ->
                        Surface(onClick={days=if(i+1 in days) days-(i+1) else days+(i+1)},color=if(i+1 in days) Moss else Sage,shape=RoundedCornerShape(10.dp),modifier=Modifier.weight(1f).heightIn(min=48.dp).semantics { contentDescription="周$text"+(if(i+1 in days) "已选" else "未选") }) {
                            Box(contentAlignment=Alignment.Center) { Text(text,color=if(i+1 in days) Color.White else Ink) }
                        }
                    } }
                    Row(verticalAlignment=Alignment.CenterVertically) { Text("定时提醒",Modifier.weight(1f));Switch(reminder,{reminder=it}) }
                    if(reminder) TimeInput("打卡提醒时间",time){time=it}
                    if(smoking && original==null) SmallNote("默认参考当前睡前提醒时间，保存后按此固定时间提醒，可自行调整。")
                    SmallNote("时段与次数累计为每日总量；可在记录中更正误记。")
                    if(error.isNotEmpty()) Text(error,color=Clay)
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                    TextButton(onClick=onDismiss){Text("取消")}
                    Button(onClick={
                        val date=runCatching { LocalDate.parse(start) }.getOrNull();val chosen=HabitMode.valueOf(mode);val count=if(chosen==HabitMode.CHECK) 1 else target.toIntOrNull();val minute=parseTime(time)
                        val rule=HabitRule(if(original==null) date ?: today else effective,days,count ?: -1,if(reminder) minute else null)
                        if(name.isBlank() || unit.isBlank() || date==null || !rule.valid(chosen) || (reminder && minute==null) || (original==null && date<today)) error="请填写名称、有效目标，并至少选择一天。"
                        else onSave(if(original==null) Habit(name=name.trim(),start=date,mode=chosen,unit=if(chosen==HabitMode.CHECK) "次" else unit.trim(),rules=listOf(rule),input=HabitInput.valueOf(input),smoking=smoking,visual=HabitVisual.valueOf(visual))
                            else original.copy(name=name.trim(),rules=original.rules.filter { it.from<effective }+rule))
                    }) { Text("保存习惯") }
                }
            }
        }
    }
}

@Composable fun HabitRecordDialog(h:Habit,date:LocalDate,old:HabitEntry?,onDismiss:()->Unit,onSave:(HabitEntry)->Unit,onRemove:()->Unit) {
    if(h.archived) {
        AlertDialog(onDismissRequest=onDismiss,title={Text("${h.name} · ${date.format(habitDate)}")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("当日目标：${h.goal(date)}");Text(old?.let { entrySummary(h,it) } ?: "未记录");if(!old?.note.isNullOrBlank()) Text(old!!.note);SmallNote("已归档，记录仅供回顾。")
            old?.sessions?.takeLast(12)?.forEach { session ->
                val format=DateTimeFormatter.ofPattern("M/d HH:mm:ss")
                SmallNote("${Instant.ofEpochMilli(session.start).atZone(ZoneId.systemDefault()).format(format)} 至 ${Instant.ofEpochMilli(session.end).atZone(ZoneId.systemDefault()).format(format)}")
            }
        }},confirmButton={TextButton(onClick=onDismiss){Text("关闭")}})
        return
    }
    var value by rememberSaveable { mutableStateOf(old?.value?.toString() ?: "") };var note by rememberSaveable { mutableStateOf(old?.note ?: "") }
    var error by remember { mutableStateOf("") };var confirmRemove by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest=onDismiss,title={Text("${h.name} · ${date.format(habitDate)}")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("当日目标：${h.goal(date)}")
        old?.sessions?.takeLast(12)?.forEach { session ->
            val format=DateTimeFormatter.ofPattern("M/d HH:mm:ss")
            SmallNote("${Instant.ofEpochMilli(session.start).atZone(ZoneId.systemDefault()).format(format)} 至 ${Instant.ofEpochMilli(session.end).atZone(ZoneId.systemDefault()).format(format)}")
        }
        if(!old?.sessions.isNullOrEmpty()) SmallNote("修改总量会替换当天的时段明细；仅修改备注会保留时段。")
        if(h.mode==HabitMode.CHECK) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(value=="1",{value="1"},label={Text("完成了")},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Sage,selectedLabelColor=Ink));FilterChip(value=="0",{value="0"},label={Text("未完成")},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Sage,selectedLabelColor=Ink))
        } else OutlinedTextField(value,{value=it.take(6)},label={Text("当日实际总量（${h.unit}）")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true)
        SmallNote(if(h.mode==HabitMode.AT_MOST) "填写真实数量，0 也需要主动记录。" else "填写这一天的合计，再次保存会修改原记录，不重复累加。")
        OutlinedTextField(note,{note=it.take(300)},label={Text("备注 / 影响原因（可选）")},maxLines=4)
        if(date<LocalDate.now()) SmallNote("这是补记，按所选日期的目标统计。")
        if(old!=null) TextButton(onClick={confirmRemove=true}){Text("撤销这条记录",color=Clay)}
        if(error.isNotEmpty())Text(error,color=Clay)
    }},confirmButton={TextButton(onClick={val v=value.toIntOrNull();if(v==null || v !in 0..100000 || (h.mode==HabitMode.CHECK && v !in 0..1)) error="请填写有效数值或选择完成情况" else onSave(if(old!=null && v==old.value) old.copy(note=note.trim()) else HabitEntry(h.id,date,v,note.trim()))}){Text("保存打卡")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
    if(confirmRemove) AlertDialog(onDismissRequest={confirmRemove=false},title={Text("撤销后恢复为未记录")},text={Text("这一天不会再计入达标次数和记录日均。")},confirmButton={TextButton(onClick=onRemove){Text("确认撤销")}},dismissButton={TextButton(onClick={confirmRemove=false}){Text("保留")}})
}
