package com.twentyone.rhythm

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable fun AccountSettings(a:MainActivity) {
    val scope=rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") };var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") };var mode by rememberSaveable { mutableIntStateOf(0) }
    var challenge by remember { mutableStateOf<AccountChallenge?>(null) };var challengeMode by remember { mutableIntStateOf(0) }
    var challengeEmail by remember { mutableStateOf("") };var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(runCatching { ProjectAccount.email(a) }.getOrNull()) }
    var message by remember { mutableStateOf("") }
    var sync by remember { mutableStateOf(ProjectSync.status(a)) }
    var conflicts by remember { mutableStateOf(ProjectSync.conflicts(a)) }
    var preflight by remember { mutableStateOf<String?>(null) }
    var restoreSecrets by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) };var logoutConfirm by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val focus=LocalFocusManager.current
    LaunchedEffect(Unit) { while(true) { sync=ProjectSync.status(a);conflicts=ProjectSync.conflicts(a);kotlinx.coroutines.delay(2000) } }
    fun run(action:suspend ()->Unit) { if(busy)return;busy=true;failed=false;message="";scope.launch { try { action();status=ProjectAccount.email(a) }catch(e:CancellationException){throw e}catch(e:Exception){failed=true;message=when(e){is java.io.IOException->"无法连接账号服务，请检查网络后重试。";else->e.message ?: "账号操作未完成，请重试。"}}finally{busy=false} } }
    fun changeMode(next:Int) { mode=next;challenge=null;code="";password="";showPassword=false;message="";failed=false;focus.clearFocus() }
    fun submit() {
        if(busy)return
        focus.clearFocus()
        if(!android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) { failed=true;message="请填写有效的邮箱地址。";return }
        if(password.isEmpty()) { failed=true;message="请输入密码。";return }
        run {
            if(mode==0){ProjectAccount.login(a,email.trim(),password);password="";showPassword=false;message="登录成功。可在下方开启账号同步。";ProjectSync.schedule(a)}
            else {challengeMode=mode;challengeEmail=email.trim();challenge=if(mode==1)ProjectAccount.register(a,challengeEmail,password)else ProjectAccount.reset(challengeEmail,password);password="";code="";showPassword=false;message="验证码已发送，10 分钟内有效。"}
        }
    }
    fun confirmCode() { if(code.length!=6 || busy)return;focus.clearFocus();run {
        ProjectAccount.confirm(a,challenge!!,code,challengeEmail,challengeMode==2)
        challenge=null;code="";mode=0
        message=if(challengeMode==2)"密码已重置，请使用新密码登录。"else "账号已注册并登录。可在下方开启同步。"
    } }
    BackHandler(status==null && (mode!=0 || challenge!=null)) { if(!busy) { if(challenge!=null){challenge=null;code="";message=""}else changeMode(0) } }
    if(status!=null) {
        Heading("我的账号")
        Sheet {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(52.dp).background(Sage,CircleShape),contentAlignment=Alignment.Center) { Icon(Icons.Outlined.Person,null,tint=Moss,modifier=Modifier.size(28.dp)) }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("已登录",color=Moss,fontSize=13.sp)
                    Text(status!!,fontWeight=FontWeight.SemiBold)
                }
            }
            SmallNote("同一邮箱可登录廿一与时屿。")
            HorizontalDivider(color=Sage)
            TextButton(enabled=!busy,onClick={logoutConfirm=true}) { Text("退出登录",color=Muted) }
        }
    } else {
        Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Heading(if(challenge!=null)"验证邮箱" else when(mode){1->"创建账号";2->"重置密码";else->"欢迎回来"})
            Text(if(challenge!=null)"验证码已发送至 $challengeEmail" else when(mode){1->"用邮箱开启你的习惯旅程";2->"通过邮箱验证，找回你的账号";else->"登录廿一，让记录在设备间延续。"},color=Muted,fontSize=14.sp)
        }
        Sheet {
            if(challenge==null) {
                OutlinedTextField(email,{email=it},label={Text("邮箱")},placeholder={Text("请输入邮箱地址")},leadingIcon={Icon(Icons.Outlined.MailOutline,null)},singleLine=true,enabled=!busy,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email,imeAction=ImeAction.Next),keyboardActions=KeyboardActions(onNext={focus.moveFocus(FocusDirection.Down)}),shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth())
                OutlinedTextField(password,{password=it},label={Text(if(mode==2)"新密码" else "密码")},placeholder={Text(if(mode==0)"请输入密码" else "至少 9 个字符")},leadingIcon={Icon(Icons.Outlined.Lock,null)},
                    trailingIcon={IconButton(onClick={showPassword=!showPassword},enabled=!busy){Icon(if(showPassword)Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,if(showPassword)"隐藏密码" else "显示密码")}},
                    singleLine=true,enabled=!busy,visualTransformation=if(showPassword)VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={submit()}),shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth())
                if(mode==0) TextButton(onClick={changeMode(2)},enabled=!busy,modifier=Modifier.align(Alignment.End)) { Text("忘记密码？") }
                else SmallNote("至少 9 个字符，包含字母、数字和符号。")
                Primary(if(busy)"处理中…" else if(mode==0)"登录" else "发送验证码",enabled=!busy && email.isNotBlank() && password.isNotEmpty()) { submit() }
                if(mode==0) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
                    Text("还没有账号？",color=Muted,fontSize=14.sp)
                    TextButton(onClick={changeMode(1)},enabled=!busy) { Text("注册账号") }
                } else TextButton(onClick={changeMode(0)},enabled=!busy,modifier=Modifier.align(Alignment.CenterHorizontally)) { Text("返回登录") }
            } else {
                OutlinedTextField(code,{code=it.filter { char->char in '0'..'9' }.take(6)},label={Text("邮箱验证码")},placeholder={Text("6 位数字")},singleLine=true,enabled=!busy,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number,imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={confirmCode()}),shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth())
                SmallNote("验证码 10 分钟内有效，请同时检查垃圾邮件。")
                Primary(if(busy)"正在验证…" else "确认验证码",enabled=!busy && code.length==6) { confirmCode() }
                TextButton(enabled=!busy,onClick={challenge=null;code="";message=""}) { Text("修改邮箱或重新发送") }
            }
        }
        SmallNote("账号通用于廿一与时屿。登录后可自行开启同步。")
    }
    if(message.isNotBlank()) Surface(color=if(failed)MaterialTheme.colorScheme.errorContainer else Sage,shape=RoundedCornerShape(14.dp)) {
        Text(message,Modifier.fillMaxWidth().padding(16.dp),color=if(failed)MaterialTheme.colorScheme.onErrorContainer else Ink,fontSize=14.sp)
    }
    if(logoutConfirm)AlertDialog(onDismissRequest={logoutConfirm=false},title={Text("退出当前账号？")},text={Text("本机记录继续保留，退出后暂停账号同步。重新登录此账号即可继续。")},confirmButton={TextButton(enabled=!busy,onClick={logoutConfirm=false;run {ProjectAccount.logout(a);changeMode(0);message="已退出登录，本机记录已保留。"}}){Text("退出登录")}},dismissButton={TextButton(onClick={logoutConfirm=false}){Text("取消")}})
    if(status!=null)Sheet {
        Text("数据同步",fontWeight=FontWeight.SemiBold)
        SmallNote(sync.message)
        SmallNote("待同步 ${sync.pending} 项 · 冲突 ${sync.conflicts} 项"+(if(sync.lastSuccess>0)"\n最近完成："+java.time.Instant.ofEpochMilli(sync.lastSuccess).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MM.dd HH:mm"))else ""))
        if(sync.enabled)Row {
            Button(enabled=!busy,onClick={run {ProjectSync.run(a);sync=ProjectSync.status(a);conflicts=ProjectSync.conflicts(a)}}) { Text("立即同步") }
            TextButton(enabled=!busy,onClick={run {ProjectSync.disable(a);sync=ProjectSync.status(a)}}) { Text("暂停同步") }
        }else Button(enabled=!busy,onClick={run {preflight=ProjectSync.preflight(a)}}) { Text("检查并开启同步") }
        SmallNote("打卡后自动同步，打开应用时获取最新记录。离线先保存在本机，联网后重试。手机权限需要本机授权。")
        conflicts.forEach { conflict ->
            val type=conflict.getString("type");val id=conflict.getString("id")
            HorizontalDivider()
            Text(conflict.optJSONObject("local")?.optJSONObject("plan")?.optString("name") ?: when(type){"sleep_plans"->"作息计划";"sleep_logs"->"作息记录 $id";"chats"->"聊天记录";"reviews"->"复盘报告";else->"设置"},fontWeight=FontWeight.SemiBold)
            SmallNote("本机："+syncSummary(conflict.optJSONObject("local")))
            SmallNote("云端："+syncSummary(conflict.optJSONObject("remote")))
            Row {
                TextButton(enabled=!busy && !conflict.isNull("remote"),onClick={run {ProjectSync.resolve(a,type,id,true);conflicts=ProjectSync.conflicts(a);message="已保留本机版本，等待同步。"}}) { Text("保留本机") }
                TextButton(enabled=!busy,onClick={run {ProjectSync.resolve(a,type,id,false);conflicts=ProjectSync.conflicts(a);message="已采用云端版本，原变更保留在冲突历史中。"}}) { Text("采用云端") }
            }
        }
    }
    if(status!=null)Sheet {
        Text("密钥与验证点迁移",fontWeight=FontWeight.SemiBold)
        SmallNote("独立保护通道保存模型密钥、二维码和 NFC 绑定。服务器使用加密保险库保存并可在授权后解密；普通同步、JSON备份和聊天不包含这些值。")
        Row {
            TextButton(enabled=!busy,onClick={run {message=CloudSecrets.backup(a)}}){Text("备份保护配置")}
            TextButton(enabled=!busy,onClick={restoreSecrets=true}){Text("恢复保护配置")}
        }
    }
    if(restoreSecrets)AlertDialog(onDismissRequest={restoreSecrets=false},title={Text("恢复当前账号的保护配置？")},text={Text("云端存在的模型密钥和验证点会替换本机对应设置。当前夜间限制或起床验证进行中时不会恢复，手机权限仍需重新授权。")},confirmButton={TextButton(enabled=!busy,onClick={restoreSecrets=false;run {message=CloudSecrets.restore(a)}}){Text("确认恢复")}},dismissButton={TextButton(onClick={restoreSecrets=false}){Text("取消")}})
    preflight?.let { text -> AlertDialog(onDismissRequest={preflight=null},title={Text("同步到当前账号")},text={Text(text)},confirmButton={TextButton(enabled=!busy,onClick={preflight=null;run {ProjectSync.enable(a);sync=ProjectSync.run(a);message="同步检查已完成。"}}){Text("确认归属并同步")}},dismissButton={TextButton(onClick={preflight=null}){Text("暂不开启")}}) }
}

private fun syncSummary(data:org.json.JSONObject?):String {
    if(data==null)return "已删除或尚无记录"
    data.optJSONObject("plan")?.let { plan ->
        val entries=data.getJSONObject("entries")
        val recent=entries.keys().asSequence().toList().sorted().takeLast(3).joinToString("；") { day -> "$day：${entries.getJSONObject(day).getInt("value")} ${plan.optString("unit")}" }
        return plan.optString("name")+" · "+(if(plan.isNull("archivedOn"))"进行中" else "已归档")+"\n"+recent+(if(data.optJSONObject("timer")!=null)"\n有运行中的计时" else "")
    }
    if(data.has("text"))return data.optString("text").take(200)
    if(data.has("value"))return data.optString("value").take(200)
    if(data.has("day"))return "${data.optString("day")} · ${data.optString("status")} · ${data.optString("note")}".take(200)
    return if(data.has("rules"))"作息时间、限制和待生效规则有变化" else "内容已修改"
}
