package com.twentyone.rhythm

import android.content.Intent
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.*

class ReviewActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) { super.onCreate(savedInstanceState);enableEdgeToEdge();ReviewScheduler.schedule(this);setContent { RhythmTheme {
        ReviewScreen(this,Modifier.safeDrawingPadding(),intent.getStringExtra("tab") ?: "week",intent.getStringExtra("subject") ?: "",onBack={finish()})
    } } }
}
@Composable fun ReviewScreen(a:Context,modifier:Modifier=Modifier,initialTab:String="week",initialSubject:String="",onBack:(()->Unit)?=null) {
    val store=remember { ReviewStore(a) };val scope=rememberCoroutineScope();val now=clock()
    var revision by remember { mutableIntStateOf(0) };var tab by rememberSaveable { mutableStateOf(initialTab) };var subject by rememberSaveable { mutableStateOf(initialSubject) }
    var period by rememberSaveable { mutableStateOf("") }
    var chooseSubject by remember { mutableStateOf(false) };var choosePeriod by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) };var busy by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,_->revision++ }
        store.prefs.registerOnSharedPreferenceChangeListener(listener);Store(a).prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { store.prefs.unregisterOnSharedPreferenceChangeListener(listener);Store(a).prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val all=remember(revision,now.toLocalDate()) { store.specs(a) }
    val subjects=all.filter { it.kind==tab }.distinctBy { it.subject }
    val selected=subjects.firstOrNull { it.subject==subject } ?: subjects.firstOrNull()
    val specs=all.filter { it.kind==tab && it.subject==selected?.subject }.sortedWith(compareBy<ReviewSpec> { it.due.isAfter(now) }.thenByDescending { it.from })
    val spec=specs.firstOrNull { it.id==period } ?: specs.firstOrNull()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) { if(onBack!=null) IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回")};Heading("变化") }
        TabRow(selectedTabIndex=if(tab=="week") 0 else 1,containerColor=Paper,contentColor=Moss) {
            listOf("week" to "周总结","cycle" to "21天总结").forEach { (id,label) -> Tab(selected=tab==id,onClick={tab=id;period=""},text={Text(label)}) }
        }
        if(subjects.isNotEmpty()) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick={chooseSubject=true},modifier=Modifier.fillMaxWidth()) { Text(selected?.name ?: "选择计划") }
                DropdownMenu(chooseSubject,{chooseSubject=false}) { subjects.forEach { item ->
                    DropdownMenuItem(text={Text(item.name)},onClick={subject=item.subject;period="";chooseSubject=false})
                } }
            }
            Box(Modifier.weight(2f)) {
                OutlinedButton(onClick={choosePeriod=true},modifier=Modifier.fillMaxWidth()) { Text(spec?.let { "${it.from} — ${it.to}" } ?: "选择周期",fontSize=12.sp) }
                DropdownMenu(choosePeriod,{choosePeriod=false}) { specs.forEach { item ->
                    DropdownMenuItem(text={Text("${item.from} — ${item.to}")},onClick={period=item.id;choosePeriod=false})
                } }
            }
        }
        if(spec==null) Sheet(color=Sage) { Text("还没有${if(tab=="week") "周" else "21天"}总结");SmallNote("添加习惯后，这里会显示对应周期。周总结也包含作息计划。") }
        spec?.let { spec ->
            val report=store.get(spec.id);val due=!spec.due.isAfter(now)
            val automatic=store.settings.getBoolean(if(spec.kind=="week") "weekly" else "cycle",true)
            Sheet {
                Text(if(spec.kind=="week") "这一周的回顾" else "这一轮的回顾",fontSize=21.sp,fontWeight=FontWeight.SemiBold)
                val h=HabitStore(a).habits().find { it.id==spec.subject }
                if(h!=null) {
                    val stats=HabitAnalysis.stats(h,HabitStore(a).entries(),minOf(now.toLocalDate(),spec.to),spec.from,spec.to)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Column { LargeNumber("${stats.recorded}");SmallNote("已记录") };Column { LargeNumber("${stats.reached}");SmallNote("达标日") };Column { LargeNumber("${stats.scheduled}");SmallNote("已到计划日") }
                    }
                }
                when {
                    report?.status=="done" -> {
                        SmallNote("DeepSeek · 按生成时的记录总结")
                        if(h!=null && Reviews.habitFacts(h,HabitStore(a).entries(),spec)!=report.facts) SmallNote("生成后记录已有变化，以下文字保留当时的结果。")
                        SelectionContainer { Text(report.answer,fontSize=15.sp,lineHeight=25.sp) }
                    }
                    !due -> SmallNote("${spec.due.toLocalDate()} 00:00 周期结束"+if(automatic) "，届时安排生成" else "；自动总结已关闭")
                    ReviewStore(a).remoteInFlight(spec.id) -> SmallNote("这份报告由另一台设备生成，同步后显示结果；本机不会自动重复请求 AI。")
                    report?.status=="running" && System.currentTimeMillis()-report.at<600_000 -> SmallNote("正在生成，可离开页面。")
                    else -> {
                        SmallNote(report?.message?.takeIf { it.isNotBlank() } ?: if(!CloudCoach.configured(a)) "等待在设置中填写 API Key" else if(!automatic) "自动总结已关闭，可手动生成" else "等待网络与后台任务执行")
                        TextButton(onClick={
                            if(!CloudCoach.configured(a)) a.startActivity(Intent(a,MainActivity::class.java).setAction("ai_settings"))
                            else { busy=spec.id;scope.launch { try { ReviewRunner.generate(a,spec,true) } catch(e:Exception) { message=e.message } finally { busy=null;ReviewScheduler.schedule(a) } } }
                        },enabled=busy==null) { Text(if(!CloudCoach.configured(a)) "设置 AI" else "立即生成 / 重试") }
                    }
                }
            }
        }
    }
    message?.let { AlertDialog(onDismissRequest={message=null},text={Text(it)},confirmButton={TextButton(onClick={message=null}){Text("知道了")}}) }
}
