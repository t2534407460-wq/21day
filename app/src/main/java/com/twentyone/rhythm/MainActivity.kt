package com.twentyone.rhythm

import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    var permissionRevision by mutableIntStateOf(0)
    var habitRequest by mutableIntStateOf(0)
    var settingsRequest by mutableIntStateOf(0)
    var settingsTarget=""
    var bedtimeRequest by mutableIntStateOf(0)
    var nfcListener: ((String)->Unit)?=null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge(); Alarms.schedule(this)
        if(intent.action=="habits") habitRequest++
        if(intent.action=="bedtime_record") bedtimeRequest++
        if(intent.action=="ai_settings") { settingsTarget="AI 助手";settingsRequest++ }
        setContent { RhythmTheme { AppRoot(this) } }
    }
    override fun onNewIntent(intent:android.content.Intent) { super.onNewIntent(intent);setIntent(intent);if(intent.action=="habits") habitRequest++;if(intent.action=="bedtime_record") bedtimeRequest++;if(intent.action=="ai_settings") { settingsTarget="AI 助手";settingsRequest++ } }
    override fun onResume() {
        super.onResume();permissionRevision++;Alarms.schedule(this)
        NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(this,{ tag -> val id=tag.id.joinToString(""){"%02X".format(it)}; runOnUiThread { nfcListener?.invoke(id) } },NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,null)
    }
    override fun onPause() { NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this); super.onPause() }
}
@Composable fun clock(): LocalDateTime { var now by remember { mutableStateOf(LocalDateTime.now()) }; LaunchedEffect(Unit) { while(true){ delay(1000); now=LocalDateTime.now() } }; return now }

@Composable fun AppRoot(activity:MainActivity) {
    LaunchedEffect(Unit) { while(true) {
        if(activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))ProjectSync.schedule(activity)
        delay(15000)
    } }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { CloudCoach.clearLegacyModel(activity) } } }
    val s=remember { Store(activity) }; val now=clock()
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) { val l=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,_->revision++ }; s.prefs.registerOnSharedPreferenceChangeListener(l); onDispose { s.prefs.unregisterOnSharedPreferenceChangeListener(l) } }
    val p=remember(revision) { s.plan }; val r=remember(revision) { s.rules }
    LaunchedEffect(now.toLocalDate(),s.pendingAt) { s.applyPending(); Alarms.schedule(activity) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var settingsCategory by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(activity.habitRequest) { if(activity.habitRequest>0) tab=2 }
    LaunchedEffect(activity.settingsRequest) { if(activity.settingsRequest>0) { settingsCategory=activity.settingsTarget;tab=4 } }
    var editor by remember { mutableStateOf(false) }; var renew by remember { mutableStateOf(false) }
    var logDay by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activity.bedtimeRequest) { if(activity.bedtimeRequest>0) { tab=0;logDay=p?.let { BedtimeSchedule.recordDay(it,r,LocalDateTime.now())?.toString() } } }
    var message by remember { mutableStateOf<String?>(null) }
    var showPrivacy by remember { mutableStateOf(false) }
    BackHandler(enabled=!editor && logDay==null && message==null && !showPrivacy) {
        if(s.keepTaskOnBack) activity.moveTaskToBack(true) else activity.finish()
    }
    val scrollState=rememberScrollState()
    LaunchedEffect(tab){scrollState.scrollTo(0)}
    Scaffold(containerColor=Paper,bottomBar={
        NavigationBar(containerColor=Paper,tonalElevation=0.dp) {
            listOf("今天","21 天","习惯","变化","设置").forEachIndexed { i,label ->
                val icon=listOf(Icons.Outlined.WbSunny,Icons.Outlined.CalendarMonth,Icons.Outlined.CheckCircle,Icons.Outlined.Insights,Icons.Outlined.Tune)[i]
                NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Icon(icon,label)},label={Text(label)},colors=NavigationBarItemDefaults.colors(indicatorColor=Sage,selectedIconColor=Ink,selectedTextColor=Ink))
            }
        }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(scrollState).padding(horizontal=22.dp).padding(top=20.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            when(tab) {
                0,1->if(p==null) {
                    Eyebrow("廿一 / 按自己的节奏来");Heading("从一个小习惯开始。")
                    if(tab==0) HabitDeck(activity,now,revision,onManage={tab=2})
                    DawnArt(Modifier.fillMaxWidth().height(170.dp))
                    Sheet(color=Sage) { Text("想让作息更规律？",fontWeight=FontWeight.SemiBold);SmallNote("设定睡前时间，完成夜间打卡与醒后验证。");Primary("设置我的 21 天") { editor=true } }
                    OutlinedButton(onClick={tab=2},modifier=Modifier.fillMaxWidth()) { Text("先去创建阅读、运动等习惯") }
                    SmallNote("习惯模块可独立使用。21 天是启动与复盘周期，并非养成保证。")
                } else if(tab==0) Today(activity,s,p,r,now,revision,onHabits={tab=2},onRecord={logDay=it},onSettings={tab=4},onPlan={tab=1})
                    else PlanScreen(s,p,r,now,onDay={logDay=it},onEdit={editor=true},onRenew={renew=true;editor=true})
                2->HabitScreen(activity,now.toLocalDate(),revision,scrollState,onSettings={settingsCategory="权限与后台";tab=4})
                3->if(p!=null) Trends(s,p,onCoach={activity.startActivity(android.content.Intent(activity,CoachActivity::class.java))})
                    else { Heading("作息变化");SmallNote("开始作息计划后，在这里回顾早晚记录。其他习惯的趋势在各自详情中查看。");Primary("查看我的习惯") { tab=2 } }
                4->SettingsScreen(activity,s,r,activity.permissionRevision,settingsCategory,{settingsCategory=it},scrollState,onHabits={tab=2},onEdit={editor=true},onMessage={message=it},onPrivacy={showPrivacy=true})
            }
        }
    }
    if(editor) PlanDialog(p,s.editableRules,renew,onDismiss={editor=false;renew=false}) { plan,rules ->
        val active=p?.let { Schedule.night(it,r,LocalDateTime.now()) }!=null
        if(renew && (active || s.wake.active)) { message="请在本晚限制与起床验证结束后开始下一轮" }
        else {
            val deferred=s.saveRules(rules)
            s.createPlan(plan); Alarms.schedule(activity); editor=false;renew=false
            if(deferred) message="已保存。当前夜间规则保持，新的安排将在本晚结束后生效。"
        }
    }
    logDay?.let { day -> RecordFlow(s.log(day),onDismiss={logDay=null},onChange={ change ->
        if(p!=null && BedtimeSchedule.recordDay(p,r,LocalDateTime.now()).toString()==day) { s.updateLog(day,change);RhythmWidget.refresh(activity) }
        else { logDay=null;message="这个夜晚的打卡时间已结束，请查看当前日期。" }
    },onComplete={
        runCatching { s.completeBedtime(day);Alarms.schedule(activity) }
            .onSuccess { logDay=null;message="睡前打卡已保存。醒后完成起床验证，记录会自动接上。" }
            .onFailure { message=it.message }
    }) }
    message?.let { text -> AlertDialog(onDismissRequest={message=null},title={Text("廿一")},text={Text(text)},confirmButton={TextButton(onClick={message=null}){Text("知道了")}}) }
    if(showPrivacy) PrivacyDialog { showPrivacy=false }
    AutomaticUpdatePrompt(activity) { settingsCategory="数据与更新";tab=4 }
}

@Composable fun DawnArt(modifier:Modifier=Modifier) {
    Canvas(modifier) {
        val c=androidx.compose.ui.geometry.Offset(size.width*.62f,size.height*.55f)
        drawCircle(Lime,size.height*.32f,c)
        drawArc(Moss.copy(alpha=.3f),180f,180f,false,androidx.compose.ui.geometry.Offset(c.x-size.height*.42f,c.y-size.height*.42f),androidx.compose.ui.geometry.Size(size.height*.84f,size.height*.84f),style=Stroke(1.5f))
        val path=Path().apply { moveTo(0f,size.height*.87f); cubicTo(size.width*.2f,size.height*.57f,size.width*.48f,size.height*.97f,size.width,size.height*.57f); lineTo(size.width,size.height);lineTo(0f,size.height);close() }
        drawPath(path,Sage)
        drawLine(Moss,androidx.compose.ui.geometry.Offset(0f,size.height*.88f),androidx.compose.ui.geometry.Offset(size.width,size.height*.88f),1.5f)
    }
}
@Composable fun Today(activity:MainActivity,s:Store,p:Plan,r:Rules,now:LocalDateTime,revision:Int,onHabits:()->Unit,onRecord:(String)->Unit,onSettings:()->Unit,onPlan:()->Unit) {
    val recordDate=BedtimeSchedule.recordDay(p,r,now);val day=p.day(recordDate ?: now.toLocalDate()); val night=Schedule.night(p,r,now); val wake=s.wake
    val morning=(recordDate ?: now.toLocalDate()).plusDays(1);val restMorning=RestCalendar.isRest(morning)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
        Column { Eyebrow(now.format(DateTimeFormatter.ofPattern("MM 月 dd 日  ·  EEEE",java.util.Locale.CHINESE))); Heading(if(now.hour<12) "早安，慢慢开始。" else if(now.hour<18) "留一点时间给自己。" else "今晚，好好休息。") }
    }
    Sheet(color=Ink) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Eyebrow(if(day in 1..21) "DAY ${day.toString().padStart(2,'0')}  /  21" else if(day<1) "即将开始" else "本轮已结束",Lime); Icon(Icons.Outlined.DarkMode,null,tint=Lime) }
        Text(r.bedtimeSource(recordDate ?: now.toLocalDate()),fontSize=22.sp,color=Color.White,fontWeight=FontWeight.Medium)
        Row(Modifier.fillMaxWidth().padding(vertical=8.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            Column { SmallLight("${BedtimeSchedule.bedtime(recordDate ?: now.toLocalDate(),r).format(DateTimeFormatter.ofPattern("MM.dd"))} 睡前 / 锁屏"); LargeNumber(timeText(r.bedtime(recordDate ?: now.toLocalDate())),Color.White,43) }
            Column(horizontalAlignment=Alignment.End) { SmallLight("${morning.format(DateTimeFormatter.ofPattern("MM.dd"))} ${if(restMorning) "参考起床" else "起床"}"); LargeNumber(timeText(r.wakeTime(morning)),Lime,43) }
        }
        if(restMorning) SmallLight("休息日自然醒 · 不响起床闹钟，无需强制验证")
        HorizontalDivider(color=Color.White.copy(alpha=.15f))
        Text(if(night==null) "在约定的时间，把手机放下。" else if(NightAccessibilityService.instance==null) "计划已到时间，应用限制权限尚未开启。" else "夜间限制正在执行，必要应用仍可使用。",fontSize=13.sp,color=Color.White.copy(alpha=.8f))
    }
    if(wake.active) Sheet(color=Lime.copy(alpha=.6f)) {
        Text("起床验证正在进行",fontWeight=FontWeight.SemiBold)
        SmallNote(if(wake.phase==WakePhase.SECOND_WAIT) "第一次已完成。到时间后再确认一次。" else "到洗漱区扫码，或触碰已绑定的 NFC 标签。")
        Primary("继续起床验证") { activity.startActivity(android.content.Intent(activity,WakeActivity::class.java)) }
    }
    HabitDeck(activity,now,revision,onManage=onHabits)
    Sheet {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("睡前打卡",fontWeight=FontWeight.SemiBold);Icon(Icons.Outlined.DarkMode,null,tint=Moss)
        }
        Text(p.action,fontSize=18.sp,lineHeight=27.sp,fontWeight=FontWeight.Medium)
        if(recordDate!=null) {
            val log=s.log(recordDate.toString());val checked=BedtimeSchedule.checked(log)
            SmallNote("${recordDate.format(DateTimeFormatter.ofPattern("MM月dd日"))} 晚间 · "+if(checked) "已完成 · ${stamp(log.bedtimeCheckedAt)}" else "困意、心情、影响原因，每晚留下一次记录。")
            Primary(if(checked) "查看睡前打卡" else "开始睡前打卡") { onRecord(recordDate.toString()) }
            if(!checked) SmallNote("${BedtimeSchedule.deadline(recordDate,r).format(DateTimeFormatter.ofPattern("MM.dd HH:mm"))} 前未完成将锁屏一次。"+if(BedtimeLock.available(activity) && Alarms.exactAllowed(activity)) "" else "请先在设置中开启锁屏和精确闹钟权限。")
        } else SmallNote(if(day<1) "计划开始后，每晚来这里记录。" else "本轮睡前记录已结束，可在21天中回顾。")
        HorizontalDivider(color=Sage)
        val morning=s.log(now.toLocalDate().toString())
        Text("${now.format(DateTimeFormatter.ofPattern("MM.dd"))} 醒后记录",fontWeight=FontWeight.Medium)
        SmallNote(if(morning.verifiedAt>0) "已验证 · ${stamp(morning.verifiedAt)} 自动保存" else "完成全部起床验证后自动记录，无需再次打卡。")
    }
    Row(Modifier.fillMaxWidth().clickable(onClick=onPlan),horizontalArrangement=Arrangement.SpaceBetween) { Text("我的 21 天",fontWeight=FontWeight.SemiBold); Text("查看计划  →",color=Moss,fontSize=13.sp) }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        val week=((day.coerceIn(1,21)-1)/7)*7
        repeat(7){i-> val date=p.start.plusDays((week+i).toLong()); val logged=s.log(date.toString()); val isToday=date==now.toLocalDate()
            Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(34.dp).background(if(BedtimeSchedule.checked(logged)) Moss else if(isToday) Lime else Sage,CircleShape),contentAlignment=Alignment.Center) { Text(if(BedtimeSchedule.checked(logged)) "✓" else "${week+i+1}",color=if(BedtimeSchedule.checked(logged)) Color.White else Ink,fontSize=13.sp) }
                Text(if(isToday) "今天" else date.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW,java.util.Locale.CHINESE),color=Muted,fontSize=11.sp)
            }
        }
    }
    if(!Alarms.exactAllowed(activity) || NightAccessibilityService.instance==null || !BedtimeLock.available(activity)) Sheet(color=Sage) {
        Text("让计划真正执行起来",fontWeight=FontWeight.Medium)
        SmallNote("完成精确闹钟、通知、超时锁屏和应用限制授权，并先试一次起床验证。")
        TextButton(onClick=onSettings){Text("检查权限与验证点  →")}
    }
}
@Composable fun SmallLight(text:String) { Text(text,color=Color.White.copy(alpha=.65f),fontSize=12.sp) }

@Composable fun Trends(s:Store,p:Plan,onCoach:()->Unit) {
    val logs=s.logs().filter { runCatching { p.contains(LocalDate.parse(it.day)) }.getOrDefault(false) }
    val done=logs.count { BedtimeSchedule.checked(it) }; val verified=(1L..21L).count { s.log(p.start.plusDays(it).toString()).verifiedAt>0 }
    Eyebrow("SMALL CHANGES, REAL PROGRESS"); Heading("看见自己的变化。")
    Sheet(color=Sage) {
        Text("DeepSeek 作息助手",fontWeight=FontWeight.SemiBold)
        SmallNote("打字或语音转文字，聊聊作息、回顾今天与最近7天，也能补记。")
        Primary("打开作息助手",click=onCoach)
    }
    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Sheet(Modifier.weight(1f),Sage) { Eyebrow("睡前已打卡"); LargeNumber("$done"); SmallNote("天 / 本轮 21 天") }
        Sheet(Modifier.weight(1f),Lime.copy(alpha=.55f)) { Eyebrow("起床已验证"); LargeNumber("$verified"); SmallNote("天 / 按验证记录") }
    }
    Sheet {
        Text("最近的作息",fontWeight=FontWeight.SemiBold)
        val recent=(0L..20L).map { p.start.plusDays(it) }.filter { date ->
            val evening=s.log(date.toString());val morning=s.log(date.plusDays(1).toString())
            evening.bedtimeCheckedAt>0 || evening.bed.isNotBlank() || morning.verifiedAt>0 || morning.rise.isNotBlank()
        }.takeLast(7)
        if(recent.isEmpty()) {
            DawnArt(Modifier.fillMaxWidth().height(100.dp));Text("变化，从第一次记录开始。",color=Muted)
            SmallNote("完成睡前打卡和醒后验证后，这里会把一晚与次晨连起来。")
        } else recent.forEach { date ->
            val evening=s.log(date.toString());val morning=s.log(date.plusDays(1).toString())
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text("${date.format(DateTimeFormatter.ofPattern("MM.dd"))} 晚 → ${date.plusDays(1).format(DateTimeFormatter.ofPattern("MM.dd"))} 早",fontSize=14.sp)
                SmallNote("睡前："+(if(BedtimeSchedule.checked(evening)) stamp(evening.bedtimeCheckedAt) else if(evening.bed.isNotBlank()) "自述上床 ${evening.bed}" else "未打卡")+
                    "\n醒后："+(if(morning.verifiedAt>0) "${stamp(morning.verifiedAt)} 已验证" else if(morning.rise.isNotBlank()) "自述起床 ${morning.rise} · 未验证" else "未验证"))
            }
        }
    }
    Sheet {
        Text("每周复盘",fontWeight=FontWeight.SemiBold)
        val recorded=logs.filter { BedtimeSchedule.checked(it) || it.status!="未记录" }
        Text(if(recorded.isEmpty()) "先积累几天记录，再一起回顾。" else "本轮已记录 ${recorded.size} 天，其中 $done 晚完成了睡前打卡。",fontSize=15.sp)
        val reason=logs.map { it.bedReason.ifEmpty { it.reason } }.filter { it.isNotBlank() && it!="无" }.groupingBy { it }.eachCount().maxByOrNull { it.value }
        if(reason!=null) Text("记录最多的阻碍：${reason.key}（${reason.value} 次）。",fontSize=14.sp)
        SmallNote("回顾三个问题：时间是否更稳定？执行是否更轻松？下一周只调整哪一件事？")
        val badge=when { done>=21->"完整一轮";done>=14->"持续生长";done>=7->"第一周的积累";done>=1->"已经开始";else->null }
        if(badge!=null) AssistChip(onClick={},label={Text("✦  $badge")})
    }
    Sheet {
        Text("例外也值得被看见",fontWeight=FontWeight.SemiBold)
        val events=s.events().takeLast(8).reversed()
        if(events.isEmpty()) SmallNote("临时使用和规则调整会记录在这里。偶尔中断，之前的进度仍然保留。")
        events.forEach { e-> Column { Text(e.kind,fontSize=14.sp); SmallNote(Instant.ofEpochMilli(e.time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))+" · "+e.detail) } }
    }
}

@Composable fun PlanDialog(p:Plan?,r:Rules,renew:Boolean,onDismiss:()->Unit,onSave:(Plan,Rules)->Unit) {
    var start by remember { mutableStateOf(if(p==null || renew) LocalDate.now().toString() else p.start.toString()) }
    var goal by remember { mutableStateOf(p?.goal ?: "规律睡觉，清醒地开始每一天") }
    var action by remember { mutableStateOf(p?.action ?: "洗漱后，把手机放到固定充电处") }
    var bed by remember { mutableStateOf(timeText(r.bed)) }; var wake by remember { mutableStateOf(timeText(r.wake)) }
    var weekends by remember { mutableStateOf(r.weekends) }; var wb by remember { mutableStateOf(timeText(r.weekendBed)) }; var ww by remember { mutableStateOf(timeText(r.weekendWake)) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(p==null || renew) "开始我的 21 天" else "调整计划")},text={ Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        if(p==null || renew) DateInput("开始日期",start){start=it}
        OutlinedTextField(goal,{goal=it.take(120)},label={Text("这一轮，我希望")})
        OutlinedTextField(action,{action=it.take(120)},label={Text("每天的一个小行动")})
        TimeInput("夜间限制开始",bed){bed=it};TimeInput("起床 / 夜间限制结束",wake){wake=it}
        Row(verticalAlignment=Alignment.CenterVertically) { Switch(weekends,{weekends=it}); Text("休息日使用不同时间",fontSize=14.sp) }
        if(weekends) { TimeInput("休息日前夜睡前",wb){wb=it};TimeInput("休息日参考起床 / 限制结束",ww){ww=it};SmallNote("包含周末与2026年中国大陆法定假期，调休上班日按普通作息。凌晨睡前属于前一晚，配当天起床。其他年份暂按周末，可提前设置特殊日期。") }
        SmallNote("休息日自然醒，不响起床闹钟、不强制扫码或 NFC；调休上班日照常。共用时间或设置特殊时间不会开启休息日闹钟，睡前提醒与限制保持。")
        SmallNote("给睡眠留足时间。夜间修改时间会在本晚结束后生效。21 天是启动周期，并非养成保证。")
        if(error.isNotEmpty()) Text(error,color=Clay)
    } },confirmButton={TextButton(onClick={
        val date=runCatching { LocalDate.parse(start) }.getOrNull();val b=parseTime(bed);val w=parseTime(wake);val weekendB=parseTime(wb);val weekendW=parseTime(ww)
        if(date==null || b==null || w==null || weekendB==null || weekendW==null || goal.isBlank() || action.isBlank()) error="请填写有效日期、24 小时时间和目标。"
        else { val newR=r.copy(bed=b,wake=w,weekends=weekends,weekendBed=weekendB,weekendWake=weekendW); if(!newR.valid()) error="开始和结束时间不能相同。" else onSave(Plan(date,action.trim(),goal.trim()),newR) }
    }){Text("保存计划")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}
