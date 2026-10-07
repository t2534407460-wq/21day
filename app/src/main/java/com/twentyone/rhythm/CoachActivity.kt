package com.twentyone.rhythm

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.content.ActivityNotFoundException
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter

class CoachActivity:ComponentActivity() {
    var cancelGeneration:(()->Unit)?=null
    var settingsRevision by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        setContent { RhythmTheme { CoachScreen(this) } }
    }
    override fun onResume() { super.onResume();settingsRevision++ }
    override fun onPause() { cancelGeneration?.invoke();super.onPause() }
}

@Composable private fun CoachScreen(a:CoachActivity) {
    val store=remember { Store(a) };val scope=rememberCoroutineScope()
    val habits=remember { HabitStore(a) }
    val keyboard=LocalSoftwareKeyboardController.current
    val inputFocus=remember { FocusRequester() }
    val voicePrefs=remember { ProjectPreferences.get(a,"coach_voice") }
    var keyboardVoice by remember { mutableStateOf(voicePrefs.getBoolean("keyboard",true)) }
    var keyboardRequest by remember { mutableIntStateOf(0) }
    var voiceFallback by remember { mutableStateOf(false) }
    val keyboardVisible=WindowInsets.ime.getBottom(LocalDensity.current)>0
    val shortScreen=LocalConfiguration.current.screenHeightDp<480
    val compact=shortScreen || keyboardVisible
    val listState=rememberLazyListState()
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,_->revision++ }
        store.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { store.prefs.unregisterOnSharedPreferenceChangeListener(listener);a.cancelGeneration=null }
    }
    val today=clock().toLocalDate()
    var historyFrom by rememberSaveable { mutableStateOf(today.minusDays(6).toString()) }
    var historyThrough by rememberSaveable { mutableStateOf(today.toString()) }
    fun snapshot(through:LocalDate,days:Int)=RecordHistory.facts(store.logs(),store.plan,habits.habits(),habits.entries(),store.events(),through,days)
    val facts=remember(today,revision,historyFrom,historyThrough) { snapshot(LocalDate.parse(historyThrough),java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(historyFrom),LocalDate.parse(historyThrough)).toInt()+1) }
    var ready by remember { mutableStateOf(CloudCoach.configured(a)) }
    LaunchedEffect(a.settingsRevision) { ready=CloudCoach.configured(a);keyboardVoice=voicePrefs.getBoolean("keyboard",true) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var retry by remember { mutableStateOf<(suspend ()->Unit)?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showReports by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var recording by rememberSaveable { mutableStateOf(false) }
    var referenceDay by rememberSaveable { mutableStateOf(today.toString()) }
    var draftText by remember { mutableStateOf("") }
    var drafts by remember { mutableStateOf<List<CoachDraft>>(emptyList()) }
    var originals by remember { mutableStateOf<Map<String,DayLog>>(emptyMap()) }
    var messages by remember { mutableStateOf(CoachChat.load(a)) }
    val reports=remember { ProjectPreferences.get(a,"coach_reports") }
    fun append(message:CoachMessage) {
        messages=(messages+message).takeLast(60);CoachChat.save(a,messages)
    }
    fun startGeneration(block:suspend ()->Unit) {
        if(busy) return
        if(!ready) { showSettings=true;return }
        busy=true;status="";retry=block
        job=scope.launch {
            try { block();retry=null }
            catch(e:TimeoutCancellationException) { status="等待回答超时了，可以重试。" }
            catch(e:CancellationException) { status="已停止接收。已发送内容无法撤回，服务端可能已计费。";throw e }
            catch(e:Exception) { status=e.message?.take(200) ?: "暂时没能连接，请重试。" }
            finally { busy=false }
        }
    }
    fun summary(days:Int) {
        if(!ready) { showSettings=true;return }
        val snapshot=snapshot(today,days)
        append(CoachMessage("user",if(days==1) "帮我回顾一下今天的作息、习惯打卡和临时使用记录。" else "看看我最近7天的作息、习惯打卡和临时使用记录，有什么建议？"))
        keyboard?.hide()
        startGeneration {
            val answer=CoachInference.generate(a,CoachAnalysis.summaryPrompt(snapshot))
            currentCoroutineContext().ensureActive()
            val report=JSONObject().put("answer",answer).put("facts",snapshot.text).put("at",System.currentTimeMillis()).put("source","DeepSeek / ${CloudCoach.model}")
            reports.edit().putString("last_$days",report.toString()).apply()
            append(CoachMessage("assistant",answer,days=days,facts=snapshot.text))
        }
    }
    fun send() {
        if(input.isBlank() || busy || drafts.isNotEmpty()) return
        if(!ready) { showSettings=true;return }
        val text=input.trim();val date=referenceDay
        append(CoachMessage("user",text,context=!recording));input="";keyboard?.hide()
        if(recording) {
            draftText=text
            startGeneration {
                val day=LocalDate.parse(date)
                require(day in today.minusDays(365)..today) { "请选择最近一年且不晚于今天的参考日期" }
                val result=CoachDrafts.extract(a,text,day,today)
                currentCoroutineContext().ensureActive()
                drafts=result;originals=result.associate { it.day to store.log(it.day) }
            }
        } else {
            val history=messages.toList();val snapshot=facts
            startGeneration {
                val answer=CoachInference.chat(a,history,snapshot)
                currentCoroutineContext().ensureActive()
                append(CoachMessage("assistant",answer))
            }
        }
    }
    fun useInputMethodVoice() {
        keyboardVoice=true;voicePrefs.edit().putBoolean("keyboard",true).apply()
        voiceFallback=false;status="请点键盘上的麦克风开始说话；识别后可修改，再点发送。"
        keyboardRequest++
    }
    LaunchedEffect(keyboardRequest) {
        if(keyboardRequest>0) { inputFocus.requestFocus();withFrameNanos { };keyboard?.show() }
    }
    val voice=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text=if(result.resultCode==Activity.RESULT_OK) CoachChat.voiceText(input,result.data,if(recording) 300 else 2000) else null
        if(text!=null) input=text
        status=CoachChat.voiceStatus(result.resultCode,text!=null)
        voiceFallback=text==null
    }
    a.cancelGeneration={job?.takeIf { it.isActive }?.cancel()}
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { CloudCoach.clearLegacyModel(a) } }
            .onFailure { status=it.message ?: "旧模型清理失败，下次打开时会重试" }
    }
    LaunchedEffect(messages.size,messages.lastOrNull()?.id,busy,status,drafts) {
        listState.animateScrollToItem((listState.layoutInfo.totalItemsCount-1).coerceAtLeast(0))
    }
    Column(Modifier.fillMaxSize().background(Paper).safeDrawingPadding().imePadding()) {
        if(!(shortScreen && keyboardVisible)) Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={a.finish()}) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回变化",tint=Ink) }
            Column(Modifier.weight(1f)) {
                Text("记录助手",fontSize=19.sp,fontWeight=FontWeight.SemiBold,color=Ink)
                Text(if(ready) "DeepSeek · 回顾作息、习惯和操作" else "连接后，开始你的第一段对话",fontSize=11.sp,color=Muted)
            }
            IconButton(onClick={showHistory=true}) { Icon(Icons.Outlined.History,"历史记录",tint=Muted) }
            IconButton(onClick={showSettings=true},enabled=!busy) { Icon(Icons.Outlined.Tune,"助手设置",tint=Muted) }
        }
        HorizontalDivider(color=Sage)
        LazyColumn(state=listState,modifier=Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            item {
                Column(Modifier.padding(top=14.dp,bottom=8.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.NightsStay,null,tint=Moss,modifier=Modifier.size(32.dp))
                    Text("从记录里，看见每天。",fontSize=25.sp,fontWeight=FontWeight.SemiBold,color=Ink)
                    Text("聊聊作息、阅读运动、戒烟打卡，或回顾临时使用申请。",fontSize=14.sp,lineHeight=23.sp,color=Muted)
                    Text("${facts.from} 至 ${facts.through} · ${facts.recorded} 天有记录",fontSize=12.sp,color=Moss)
                    TextButton(onClick={showHistory=true},contentPadding=PaddingValues(0.dp)) { Text("查看历史 / 更改聊天日期范围") }
                    if(!ready) TextButton(onClick={showSettings=true},contentPadding=PaddingValues(0.dp)) { Text("连接 DeepSeek") }
                }
            }
            items(messages,key={it.id}) { message ->
                CoachBubble(message,if(message.days>0) snapshot(today,message.days).text!=message.facts else false)
            }
            if(busy) item {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp,color=Moss)
                    Text(if(recording) "正在整理这段记录…" else "正在想一想…",fontSize=14.sp,color=Muted)
                    TextButton(onClick={job?.cancel()}) { Text("停止生成") }
                }
            }
            if(drafts.isNotEmpty()) item {
                Sheet(color=Sage) {
                    Text("待确认的补记",fontWeight=FontWeight.SemiBold,color=Ink)
                    drafts.forEach { draft ->
                        Text(draft.day,fontWeight=FontWeight.Medium)
                        draft.changes(originals.getValue(draft.day)).forEach { (label,value) -> Text("$label：$value",fontSize=14.sp) }
                    }
                    SmallNote("只保存以上自述字段；备注追加，不代替睡前打卡或起床验证。")
                    Primary("确认保存补记") {
                        try {
                            store.applyCoachDrafts(drafts,originals);RhythmWidget.refresh(a)
                            drafts=emptyList();recording=false
                            append(CoachMessage("assistant","补记已保存，可以在21天页面查看对应日期。",context=false))
                        } catch(e:Exception) { drafts=emptyList();status=e.message ?: "未保存，请重新预览" }
                    }
                    TextButton(onClick={drafts=emptyList();input=draftText;status="尚未保存，可以修改说法后重试。"}) { Text("取消补记") }
                }
            }
            if(status.isNotBlank()) item {
                Column {
                    Text(status,fontSize=13.sp,lineHeight=21.sp,color=Muted)
                    if(voiceFallback && !busy && drafts.isEmpty()) TextButton(onClick={useInputMethodVoice()}) { Text("改用输入法语音") }
                    if(retry!=null && !busy) TextButton(onClick={retry?.let { startGeneration(it) }}) { Text("重试") }
                }
            }
        }
        Surface(color=Color.White,tonalElevation=0.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                if(!keyboardVisible) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick={summary(1)},enabled=!busy && drafts.isEmpty(),label={Text("今日总结")})
                    AssistChip(onClick={summary(7)},enabled=!busy && drafts.isEmpty(),label={Text("近7天复盘")})
                    FilterChip(selected=recording,onClick={recording=!recording;input=input.take(if(recording) 300 else 2000);retry=null;status=""},enabled=!busy && drafts.isEmpty(),
                        label={Text("补记")},leadingIcon={Icon(Icons.Outlined.EditNote,null,Modifier.size(17.dp))})
                }
                if(recording && !keyboardVisible && drafts.isEmpty()) {
                    DateInput("参考日期",referenceDay){referenceDay=it}
                    if(!compact) SmallNote("明确写出日期和作息；发送后核对，确认才保存。")
                }
                Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(input,{input=it.take(if(recording) 300 else 2000)},modifier=Modifier.weight(1f).focusRequester(inputFocus),
                        placeholder={Text(if(recording) "如：今天7:20起床，精神一般" else "说说今天，或问一个问题…",fontSize=14.sp)},
                        shape=RoundedCornerShape(22.dp),singleLine=shortScreen && keyboardVisible,maxLines=if(compact) 2 else 4,enabled=!busy && drafts.isEmpty(),
                        colors=OutlinedTextFieldDefaults.colors(unfocusedBorderColor=Sage,focusedBorderColor=Moss),
                        trailingIcon={IconButton(onClick={
                            if(keyboardVoice) useInputMethodVoice() else {
                                keyboard?.hide();voiceFallback=false
                                try { voice.launch(CoachChat.voiceIntent()) }
                                catch(e:ActivityNotFoundException) { status="手机没有可用的系统语音识别服务，请改用输入法语音。";voiceFallback=true }
                                catch(e:SecurityException) { status="系统语音服务拒绝了请求；可检查该服务的授权，或改用输入法语音。";voiceFallback=true }
                            }
                        },enabled=!busy && drafts.isEmpty()) { Icon(Icons.Outlined.Mic,"语音输入",tint=Moss) }})
                    FilledIconButton(onClick={send()},enabled=input.isNotBlank() && !busy && drafts.isEmpty(),modifier=Modifier.padding(bottom=4.dp).size(48.dp),shape=RoundedCornerShape(16.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.Send,if(recording) "发送补记" else "发送消息")
                    }
                }
                if(!compact) Text(if(keyboardVoice) "语音入口打开键盘，再点键盘麦克风 · 手动发送" else "系统语音转文字后发送 · 建议仅供作息参考",fontSize=10.sp,color=Muted,modifier=Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
    if(showSettings) AlertDialog(onDismissRequest={showSettings=false},title={Text("助手设置")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("优先使用输入法语音",Modifier.weight(1f))
                Switch(keyboardVoice,{keyboardVoice=it;voicePrefs.edit().putBoolean("keyboard",it).apply()},modifier=Modifier.semantics { contentDescription="优先使用输入法语音" })
            }
            SmallNote("默认打开键盘，再点输入法麦克风；识别文字可修改，发送后才提交。关闭后使用手机系统语音弹窗。")
            SettingLink("API 与自动复盘设置","统一管理 DeepSeek 密钥、每周和21天总结") {
                showSettings=false;a.startActivity(Intent(a,MainActivity::class.java).setAction("ai_settings").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }
            SettingLink("复盘看板","按习惯查看周期总结") { showSettings=false;a.startActivity(Intent(a,ReviewActivity::class.java)) }
        }
    },confirmButton={TextButton(onClick={showSettings=false}){Text("完成")}})
    if(showHistory) RecordHistoryDialog(RecordHistory.records(store.logs(),habits.habits(),habits.entries(),store.events(),store.plan,today),store,today,historyFrom,historyThrough,
        onDismiss={showHistory=false},onReports={showHistory=false;showReports=true},onRange={from,through->
            historyFrom=from;historyThrough=through;showHistory=false;status="聊天现在使用 $from 至 $through 的记录。"
        })
    if(showReports) AlertDialog(onDismissRequest={showReports=false},title={Text("历史总结")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            var found=false
            listOf(1,7).forEach { days ->
                val report=runCatching { JSONObject(reports.getString("last_$days","{}")!!) }.getOrDefault(JSONObject())
                if(report.has("answer")) {
                    found=true
                    Text(if(days==1) "最近一次今日总结" else "最近一次7天复盘",fontWeight=FontWeight.Medium)
                    SmallNote(report.optString("source","旧版本地模型")+" · "+coachTime(report.optLong("at")))
                    if(report.optString("facts")!=snapshot(today,days).text) SmallNote("记录或日期已有变化，这是之前的总结。")
                    SelectionContainer { Text(report.optString("answer"),fontSize=14.sp,lineHeight=23.sp) }
                }
            }
            if(!found) Text("还没有生成过总结。可以从聊天框上方开始今日或近7天复盘。")
        }
    },confirmButton={TextButton(onClick={showReports=false}){Text("关闭")}})
}

private fun coachTime(at:Long)=Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

@Composable private fun CoachBubble(message:CoachMessage,stale:Boolean) {
    val mine=message.role=="user"
    Column(Modifier.fillMaxWidth(),horizontalAlignment=if(mine) Alignment.End else Alignment.Start,verticalArrangement=Arrangement.spacedBy(6.dp)) {
        if(!mine) Text("廿一 · 记录助手",fontSize=11.sp,color=Moss,modifier=Modifier.padding(start=4.dp))
        Surface(color=if(mine) Sage else Color.White,shape=RoundedCornerShape(20.dp,20.dp,if(mine) 6.dp else 20.dp,if(mine) 20.dp else 6.dp),
            modifier=Modifier.widthIn(max=340.dp)) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                SelectionContainer { Text(message.text,color=Ink,fontSize=15.sp,lineHeight=25.sp) }
                if(message.days>0) Text(if(message.days==1) "基于当日记录" else "基于近7天记录",fontSize=11.sp,color=Moss)
                if(stale) Text("记录或日期已有变化，以下是之前的总结，请重新生成。",fontSize=12.sp,color=Clay)
            }
        }
        Text(coachTime(message.at),fontSize=10.sp,color=Muted,modifier=Modifier.padding(horizontal=4.dp))
    }
}
