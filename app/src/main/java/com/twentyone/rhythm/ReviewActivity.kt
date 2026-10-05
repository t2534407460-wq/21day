package com.twentyone.rhythm

import android.content.Intent
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
    override fun onCreate(savedInstanceState:Bundle?) { super.onCreate(savedInstanceState);enableEdgeToEdge();ReviewScheduler.schedule(this);setContent { RhythmTheme { ReviewScreen(this) } } }
}
@Composable private fun ReviewScreen(a:ReviewActivity) {
    val store=remember { ReviewStore(a) };val scope=rememberCoroutineScope();val now=clock()
    var revision by remember { mutableIntStateOf(0) };var tab by rememberSaveable { mutableStateOf(a.intent.getStringExtra("tab") ?: "week") };var subject by rememberSaveable { mutableStateOf(a.intent.getStringExtra("subject") ?: "") }
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
    Scaffold(containerColor=Paper) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) { IconButton(onClick={a.finish()}){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回")};Heading("复盘看板") }
            SmallNote("每个习惯分开回顾，让下一步有据可循。")
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                listOf("week" to "每周复盘","cycle" to "21天复盘").forEach { (id,label) -> FilterChip(tab==id,{tab=id},label={Text(label)},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Sage,selectedLabelColor=Ink)) }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                subjects.forEach { spec -> FilterChip(selected?.subject==spec.subject,{subject=spec.subject},label={Text(spec.name)},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Sage,selectedLabelColor=Ink)) }
            }
            if(specs.isEmpty()) Sheet(color=Sage) { Text("这块看板还没有计划。");SmallNote("添加习惯后会出现对应周期；周总结从启用本功能的当周开始。") }
            specs.forEach { spec ->
                val report=store.get(spec.id);val due=!spec.due.isAfter(now)
                val automatic=store.settings.getBoolean(if(spec.kind=="week") "weekly" else "cycle",true)
                Sheet {
                    Text(spec.name,fontSize=21.sp,fontWeight=FontWeight.SemiBold)
                    SmallNote("${spec.from} 至 ${spec.to}")
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
            SmallNote("建议仅供习惯复盘，不自动修改计划。未记录不视为零，21天不是养成保证。")
        }
    }
    message?.let { AlertDialog(onDismissRequest={message=null},text={Text(it)},confirmButton={TextButton(onClick={message=null}){Text("知道了")}}) }
}
