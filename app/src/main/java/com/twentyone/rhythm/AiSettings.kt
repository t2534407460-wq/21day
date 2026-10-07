package com.twentyone.rhythm

import android.content.*
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable fun AiSettings(c:Context,onMessage:(String)->Unit) {
    val store=remember { ReviewStore(c) };val voice=remember { ProjectPreferences.get(c,"coach_voice") }
    var weekly by remember { mutableStateOf(store.settings.getBoolean("weekly",true)) }
    var cycle by remember { mutableStateOf(store.settings.getBoolean("cycle",true)) }
    var enabled by remember { mutableStateOf(CoachChat.voiceEnabled(c)) }
    var key by remember { mutableStateOf("") };var ready by remember { mutableStateOf(CloudCoach.configured(c)) };var busy by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_PAUSE) key="" }
        lifecycle.addObserver(observer);onDispose { lifecycle.removeObserver(observer) }
    }
    Sheet {
        Text("DeepSeek 连接",fontWeight=FontWeight.SemiBold)
        SmallNote(if(ready) "API Key 已加密保存，聊天与自动复盘共用。" else "填写 API Key 后，聊天与到期复盘才可联网生成。")
        OutlinedTextField(key,{key=it.take(512)},label={Text(if(ready) "替换 API Key" else "DeepSeek API Key")},singleLine=true,modifier=Modifier.fillMaxWidth(),visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),enabled=!busy)
        Primary("保存 API Key",enabled=!busy && key.isNotBlank()) {
            busy=true;val value=key
            scope.launch {
                try { withContext(Dispatchers.IO) { CloudCoach.saveKey(c,value) };key="";ready=true;ReviewScheduler.schedule(c);onMessage("API Key 已加密保存。") }
                catch(e:CancellationException) { throw e }
                catch(e:Exception) { onMessage("密钥保存失败，请检查填写内容。") }
                finally { busy=false }
            }
        }
        if(ready) TextButton(onClick={busy=true;scope.launch {
            try { withContext(Dispatchers.IO) { CloudCoach.removeKey(c) };ready=false;key="";ReviewScheduler.schedule(c);onMessage("API Key 已移除，已有记录与总结保留。") }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { onMessage("移除失败，请重试。") }
            finally { busy=false }
        }},enabled=!busy) { Text("移除 API Key") }
        TextButton(onClick={runCatching { c.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(CloudCoach.platform))) }.onFailure { onMessage("没有可用浏览器") }}) { Text("前往 DeepSeek 官方平台") }
    }
    Sheet {
        Text("自动复盘",fontWeight=FontWeight.SemiBold)
        Row(verticalAlignment=Alignment.CenterVertically) { Text("每周总结",Modifier.weight(1f));Switch(weekly,{weekly=it;store.settings.edit().putBoolean("weekly",it).apply();ReviewScheduler.schedule(c)}) }
        SmallNote("每周日 00:00 安排此前7天的总结，按作息及每个习惯分别生成。")
        Row(verticalAlignment=Alignment.CenterVertically) { Text("21 天习惯总结",Modifier.weight(1f));Switch(cycle,{cycle=it;store.settings.edit().putBoolean("cycle",it).apply();ReviewScheduler.schedule(c)}) }
        SmallNote("每个习惯第21天结束后生成一份独立总结；提前归档的短周期不冒充21天。")
        SmallNote("自动复盘向 DeepSeek 发送对应周期的习惯名称、目标、日期和实际数值，作息发送该周期统计；不发送备注、验证码或聊天。使用你的 API 账户计费，可随时关闭。")
        SmallNote("后台任务需联网，省电、关机或系统限制可能延后执行。失败不会反复请求，可在复盘看板重试；关闭任务不会撤回已经发送的数据。")
        SettingLink("复盘看板","按习惯查看每周与21天总结") { c.startActivity(Intent(c,ReviewActivity::class.java)) }
    }
    Sheet {
        Text("聊天输入",fontWeight=FontWeight.SemiBold)
        Row(verticalAlignment=Alignment.CenterVertically) { Text("启用语音输入",Modifier.weight(1f));Switch(enabled,{enabled=it;voice.edit().putBoolean("enabled",it).apply()}) }
        SmallNote("开启后使用输入法麦克风；关闭后隐藏助手的语音入口，仍可打字。识别文字可修改，发送后才提交。")
    }
}
