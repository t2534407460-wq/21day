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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.*

class ReviewActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) { super.onCreate(savedInstanceState);enableEdgeToEdge();ReviewScheduler.schedule(this);setContent { RhythmTheme {
        ReviewScreen(this,Modifier.safeDrawingPadding(),intent.getStringExtra("tab") ?: "week",intent.getStringExtra("subject") ?: "",onBack={finish()})
    } } }
}
private data class ReviewData(val specs:List<ReviewSpec>,val habits:List<Habit>,val entries:List<HabitEntry>,val weekly:Boolean,val cycle:Boolean,val configured:Boolean)
private data class ReviewContent(val id:String,val report:ReviewReport?,val stats:HabitStats?,val changed:Boolean,val remote:Boolean)
@Composable fun ReviewScreen(a:Context,modifier:Modifier=Modifier,initialTab:String="week",initialSubject:String="",onBack:(()->Unit)?=null,showNavigation:Boolean=true) {
    val store=remember { ReviewStore(a) };val scope=rememberCoroutineScope();val now=clock()
    var revision by remember { mutableIntStateOf(0) };var standaloneTab by rememberSaveable { mutableStateOf(initialTab) };var subject by rememberSaveable { mutableStateOf(initialSubject) }
    val tab=if(showNavigation) standaloneTab else initialTab
    var period by rememberSaveable { mutableStateOf("") }
    var chooseSubject by remember { mutableStateOf(false) };var choosePeriod by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) };var busy by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,_->revision++ }
        val plans=Store(a).prefs
        val planListener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,key->if(key in setOf("habits","plan")) revision++ }
        store.prefs.registerOnSharedPreferenceChangeListener(listener);store.settings.registerOnSharedPreferenceChangeListener(listener);plans.registerOnSharedPreferenceChangeListener(planListener)
        onDispose { store.prefs.unregisterOnSharedPreferenceChangeListener(listener);store.settings.unregisterOnSharedPreferenceChangeListener(listener);plans.unregisterOnSharedPreferenceChangeListener(planListener) }
    }
    val data by produceState<ReviewData?>(null,revision,now.toLocalDate()) {
        value=withContext(Dispatchers.IO) {
            val plans=Store(a)
            val (habits,entries)=HabitStore.decode(JSONObject(plans.prefs.getString("habits","{\"plans\":[],\"entries\":[]}")!!))
            val since=store.settings.getString("since",null) ?: now.toLocalDate().toString().also { store.settings.edit().putString("since",it).apply() }
            ReviewData(Reviews.specs(habits,plans.plan,LocalDate.parse(since),true,true),habits,entries,
                store.settings.getBoolean("weekly",true),store.settings.getBoolean("cycle",true),CloudCoach.configured(a))
        }
    }
    val all=data?.specs.orEmpty()
    val subjects=all.filter { it.kind==tab }.distinctBy { it.subject }
    val selected=subjects.firstOrNull { it.subject==subject } ?: subjects.firstOrNull()
    val specs=all.filter { it.kind==tab && it.subject==selected?.subject }.sortedWith(compareBy<ReviewSpec> { it.due.isAfter(now) }.thenByDescending { it.from })
    val spec=specs.firstOrNull { it.id==period } ?: specs.firstOrNull()
    val content by produceState<ReviewContent?>(null,data,spec?.id,revision,now.toLocalDate()) {
        value=null
        val current=spec;val snapshot=data
        if(current!=null && snapshot!=null) value=withContext(Dispatchers.IO) {
            val report=store.get(current.id);val h=snapshot.habits.find { it.id==current.subject }
            ReviewContent(current.id,report,h?.let { HabitAnalysis.stats(it,snapshot.entries,minOf(now.toLocalDate(),current.to),current.from,current.to) },
                h!=null && report?.status=="done" && Reviews.habitFacts(h,snapshot.entries,current)!=report.facts,store.remoteInFlight(current.id))
        }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        if(showNavigation) {
            Row(verticalAlignment=Alignment.CenterVertically) { if(onBack!=null) IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回")};Heading("变化") }
            TabRow(selectedTabIndex=if(tab=="week") 0 else 1,containerColor=Paper,contentColor=Moss) {
                listOf("week" to "周总结","cycle" to "21天总结").forEach { (id,label) -> Tab(selected=tab==id,onClick={standaloneTab=id;period=""},text={Text(label)}) }
            }
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
        if(data==null) SmallNote("正在读取总结…")
        else if(spec==null) Sheet(color=Sage) { Text("还没有${if(tab=="week") "周" else "21天"}总结");SmallNote("添加作息或习惯计划后，这里会显示对应周期。计划持续生效，每21天总结一次。") }
        spec?.let { spec ->
            val loaded=content?.takeIf { it.id==spec.id };val report=loaded?.report;val due=!spec.due.isAfter(now)
            val automatic=if(spec.kind=="week") data?.weekly==true else data?.cycle==true
            Sheet {
                Text(if(spec.kind=="week") "这一周的回顾" else "这一轮的回顾",fontSize=21.sp,fontWeight=FontWeight.SemiBold)
                loaded?.stats?.let { stats ->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Column { LargeNumber("${stats.recorded}");SmallNote("已记录") };Column { LargeNumber("${stats.reached}");SmallNote("达标日") };Column { LargeNumber("${stats.scheduled}");SmallNote("已到计划日") }
                    }
                }
                when {
                    loaded==null -> SmallNote("正在读取这份报告…")
                    report?.status=="done" -> {
                        SmallNote("DeepSeek · 按生成时的记录总结")
                        if(loaded.changed) SmallNote("生成后记录已有变化，以下文字保留当时的结果。")
                        SelectionContainer { Text(report.answer,fontSize=15.sp,lineHeight=25.sp) }
                    }
                    !due -> SmallNote("${spec.due.toLocalDate()} 00:00 周期结束"+if(automatic) "，届时安排生成" else "；自动总结已关闭")
                    loaded.remote -> SmallNote("这份报告由另一台设备生成，同步后显示结果；本机不会自动重复请求 AI。")
                    report?.status=="running" && System.currentTimeMillis()-report.at<600_000 -> SmallNote("正在生成，可离开页面。")
                    else -> {
                        SmallNote(report?.message?.takeIf { it.isNotBlank() } ?: if(data?.configured!=true) "等待在设置中填写 API Key" else if(!automatic) "自动总结已关闭，可手动生成" else "等待网络与后台任务执行")
                        TextButton(onClick={
                            if(!CloudCoach.configured(a)) a.startActivity(Intent(a,MainActivity::class.java).setAction("ai_settings"))
                            else { busy=spec.id;scope.launch { try { ReviewRunner.generate(a,spec,true) } catch(e:Exception) { message=e.message } finally { busy=null;ReviewScheduler.schedule(a) } } }
                        },enabled=busy==null) { Text(if(data?.configured!=true) "设置 AI" else "立即生成 / 重试") }
                    }
                }
            }
        }
    }
    message?.let { AlertDialog(onDismissRequest={message=null},text={Text(it)},confirmButton={TextButton(onClick={message=null}){Text("知道了")}}) }
}
