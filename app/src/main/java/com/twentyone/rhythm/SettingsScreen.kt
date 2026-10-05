package com.twentyone.rhythm

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.nfc.NfcAdapter
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.time.*
import java.time.format.DateTimeFormatter

@Composable fun SettingsScreen(a:MainActivity,s:Store,r:Rules,permissionRevision:Int,category:String,onCategory:(String)->Unit,scrollState:ScrollState,onHabits:()->Unit,onEdit:()->Unit,onMessage:(String)->Unit,onPrivacy:()->Unit) {
    var apps by remember { mutableStateOf<String?>(null) };var consent by remember { mutableStateOf(false) };var qr by remember { mutableStateOf(false) };var nfc by remember { mutableStateOf(false) }
    var limits by remember { mutableStateOf(false) };var special by remember { mutableStateOf(false) };var restoreConfirm by remember { mutableStateOf(false) }
    LaunchedEffect(category) { scrollState.scrollTo(0) }
    BackHandler(category.isNotEmpty() && apps==null && !consent && !qr && !nfc && !limits && !special && !restoreConfirm) { onCategory("") }
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Alarms.schedule(a) }
    val backup=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri-> if(uri!=null) runCatching { a.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(s.export()) } ?: error("无法打开文件") }.onSuccess { onMessage("备份已导出。包含作息、习惯计划及记录，请妥善保管。") }.onFailure { onMessage("备份失败：${it.message}") } }
    val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri-> if(uri!=null) runCatching {
        val text=a.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader -> val chars=CharArray(2_000_001); var used=0;while(used<chars.size){val n=reader.read(chars,used,chars.size-used);if(n<0)break;used+=n};require(used<chars.size){"备份文件过大"};String(chars,0,used) } ?: error("无法读取文件")
        s.import(text);Alarms.schedule(a)
    }.onSuccess { onMessage("计划和日常记录已恢复，请检查闹钟权限和验证点。") }.onFailure { onMessage("没有恢复：${it.message}") } }
    if(category.isEmpty()) {
        Heading("设置")
        SmallNote("把需要的安排，放在容易找到的地方。")
        Sheet {
            listOf("账号" to "邮箱登录、注册与同步", "三端互通" to "时屿关联事项、知识库密钥与查询", "作息计划" to "睡前与起床时间、应用约束", "习惯与复盘" to "管理计划、每周与21天看板", "AI 助手" to "DeepSeek API Key、自动总结、语音", "权限与后台" to "通知、锁屏、自启动与任务卡片", "起床验证" to "二维码、NFC与验证试用", "数据与更新" to "备份、隐私、检查更新").forEach { (title,detail) -> SettingLink(title,detail) { onCategory(title) } }
        }
        SmallNote("廿一 ${BuildConfig.VERSION_NAME}")
        return
    }
    TextButton(onClick={onCategory("")}) { Text("‹ 全部设置") };if(category!="账号")Heading(category)
    if(category=="习惯与复盘") Sheet {
        SettingLink("管理习惯计划","阅读、运动、戒烟与自定义",onHabits)
        SettingLink("复盘看板","每周、21天，按习惯分别查看") { a.startActivity(Intent(a,ReviewActivity::class.java)) }
        SmallNote("长按打卡在今日抽屉中；可选择习惯，区分区间、次数与每日确认。习惯页保留历史与趋势。")
    }
    if(category=="AI 助手") AiSettings(a,onMessage)
    if(category=="账号") AccountSettings(a)
    if(category=="三端互通") InteropScreen(a,onAccount={onCategory("账号")})
    if(category=="作息计划") Sheet {
        Text("作息与约束",fontWeight=FontWeight.SemiBold)
        SettingLink("我的作息时间","${timeText(r.bed)} — ${timeText(r.wake)}",onEdit)
        SmallNote("休息日：${timeText(r.weekendBed)} — ${timeText(r.weekendWake)}；${if(r.weekends) "已包含2026年法定假期与调休" else "尚未启用"}。特殊日期优先。")
        s.plan?.let { Schedule.nextWindDown(it,r,LocalDateTime.now()) }?.let { next -> SmallNote("下次准备提醒：${next.format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))}；${r.bedtimeSource(BedtimeReminders.evening(next.plusMinutes(15)))}。15分钟后到点提醒，仍未打卡时锁屏一次。") }
        SettingLink("提前安排特殊日期","已安排 ${s.editableRules.exceptions.size} 天"){special=true}
        SettingLink("受限应用","已选 ${r.blocked.size} 个"){apps="blocked"}
        SettingLink("额外放行应用","已选 ${r.allowed.size} 个；系统必要功能始终放行"){apps="allowed"}
        SmallNote("每天可临时使用 3 次，每次 5 分钟，点击即生效。所有受限应用共用次数，每天 0 点重置。规则变更仍在当前限制结束后生效。")
        SettingLink("二次起床确认间隔","当前 ${s.editableRules.secondDelay} 分钟"){limits=true}
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){Text("启用二次起床确认");Switch(s.editableRules.secondCheck,{val delayed=s.saveRules(s.editableRules.copy(secondCheck=it));Alarms.schedule(a);if(delayed)onMessage("已安排稍后生效，当前验证规则保持。")})}
        if(r.secondCheck) SmallNote("第一次验证后 ${r.secondDelay} 分钟，再到验证点确认一次。")
        if(s.pendingAt>0) SmallNote("有一份规则调整等待生效："+Instant.ofEpochMilli(s.pendingAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")))
    }
    if(category=="权限与后台") Sheet(color=Sage) {
        Text("权限与可靠性",fontWeight=FontWeight.SemiBold)
        val notifications=if(Build.VERSION.SDK_INT>=33) a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED else a.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        SettingLink("通知提醒",if(notifications) "已授权" else "需要开启") { if(Build.VERSION.SDK_INT>=33 && !notifications) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else a.openSettings(Settings.ACTION_APP_NOTIFICATION_SETTINGS,false) }
        SettingLink("精确闹钟",if(Alarms.exactAllowed(a)) "已授权" else "需要开启") { if(Build.VERSION.SDK_INT>=31) a.openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) }
        val full=Build.VERSION.SDK_INT<34 || a.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        SettingLink("锁屏显示闹钟",if(full) "可用" else "需要开启") { if(Build.VERSION.SDK_INT>=34) a.openSettings(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT) }
        SettingLink("夜间应用拦截",if(NightAccessibilityService.instance!=null) "服务已连接" else "尚未启用") { consent=true }
        val lockReady=remember(permissionRevision) { BedtimeLock.available(a) }
        SettingLink("睡前到点锁屏",if(lockReady) "已授权 · 到睡前时间仍未打卡时锁屏" else "需要开启系统锁屏权限") {
            if(lockReady) a.openSettings(Settings.ACTION_SECURITY_SETTINGS,false)
            else runCatching { BedtimeLock.request(a) }.onFailure { onMessage("此设备没有提供可用的锁屏授权入口。") }
        }
        SmallNote("每晚锁屏一次；先完成睡前打卡则不触发。系统可能要求密码解锁。未设置锁屏密码时只会熄屏；解锁后仍可补打卡。授权后可在系统安全设置中撤销。")
        SettingLink("小米后台运行设置","检查自启动、省电与锁屏显示权限") { a.openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) }
        SmallNote("请在小米 15 上完成锁屏、重启和省电状态测试。强行停止、撤销权限或关机会影响执行。")
        OutlinedButton(onClick={ val service=NightAccessibilityService.instance;if(service==null)onMessage("请先启用夜间应用拦截。") else service.showBlock("",true) },modifier=Modifier.fillMaxWidth()){Text("试一次应用拦截")}
    }
    if(category=="权限与后台") Sheet {
        Text("后台显示",fontWeight=FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("隐藏最近任务卡片",Modifier.weight(1f))
            Switch(s.hideFromRecents,{s.hideFromRecents=it;RecentTasks.apply(a)})
        }
        SmallNote(if(s.hideFromRecents) "已开启隐藏：最近任务列表不显示廿一，可从桌面图标重新打开。" else "最近任务列表显示廿一，可在小米系统中手动上锁。")
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("返回时收起到后台",Modifier.weight(1f))
            Switch(s.keepTaskOnBack,{s.keepTaskOnBack=it})
        }
        SmallNote("退出页面后，已授权的闹钟与应用限制继续按计划执行。如需给任务卡片上锁，可先关闭隐藏，上锁后再开启。")
        OutlinedButton(onClick={a.moveTaskToBack(true)},modifier=Modifier.fillMaxWidth()){Text("立即收起到后台")}
        SmallNote("隐藏卡片不会停止服务，也不能保证进程不被系统回收。请同时设置自启动和电池无限制；隐藏后的锁定保留情况以小米系统为准。")
    }
    if(category=="起床验证") Sheet {
        Text("起床验证点",fontWeight=FontWeight.SemiBold)
        SmallNote("把二维码或 NFC 标签固定在洗漱区。完成一次验证说明到达了验证点，不等于医学上的清醒检测。")
        SettingLink("我的起床二维码","展示或保存，打印后放在洗漱区"){qr=true}
        SettingLink("绑定 NFC 标签",if(s.nfcId.isEmpty()) "尚未绑定 · 手机触碰标签" else "已绑定 · 手机触碰可验证"){
            if(s.wake.active || s.plan?.let { Schedule.night(it,s.rules,LocalDateTime.now()) }!=null) onMessage("请在当前限制和起床验证结束后更换验证点。")
            else if(NfcAdapter.getDefaultAdapter(a)?.isEnabled!=true) onMessage("请先在手机设置中开启 NFC，或使用二维码。") else nfc=true
        }
        Primary("试一次起床验证") {
            if(!Alarms.exactAllowed(a)) onMessage("请先开启精确闹钟权限，才能验证后续提醒。")
            else { Alarms.start(a,true);a.startActivity(Intent(a,WakeActivity::class.java)) }
        }
        SmallNote("试用会响铃，可直接结束且不计入记录。正式闹钟须扫码或 NFC 验证；应急退出需等待 60 秒，再持续长按 5 秒，记为未验证。")
    }
    if(category=="数据与更新") UpdateCard(a)
    if(category=="数据与更新") Sheet {
        Text("数据由你保管",fontWeight=FontWeight.SemiBold)
        SettingLink("导出备份","保存计划和记录到自己选择的位置"){backup.launch("廿一备份-${LocalDate.now()}.json")}
        SettingLink("恢复备份","替换本机计划与日常记录，保留本机验证点") { restoreConfirm=true }
        SettingLink("隐私说明","本地记录与云端助手的数据使用",onPrivacy)
        SmallNote("廿一 ${BuildConfig.VERSION_NAME} · 体验版\n小米 15 的后台行为需实机验证")
    }
    apps?.let { kind->AppPicker(a,s.editableRules,kind,onDismiss={apps=null}){selected-> val changed=if(kind=="blocked") s.editableRules.copy(blocked=selected) else s.editableRules.copy(allowed=selected);val pending=s.saveRules(changed);Alarms.schedule(a);apps=null;if(pending)onMessage("选择已保存，正在执行的规则结束后生效。")} }
    if(limits) LimitsDialog(s.editableRules,{limits=false}){value->val pending=s.saveRules(value);limits=false;Alarms.schedule(a);if(pending)onMessage("新时长已安排稍后生效，当前规则保持。")}
    if(special) SpecialDayDialog(s.editableRules,{special=false}){value->val pending=s.saveRules(value);special=false;Alarms.schedule(a);if(pending)onMessage("特殊日期安排已保存，当前夜间规则保持。")}
    if(restoreConfirm) AlertDialog(onDismissRequest={restoreConfirm=false},title={Text("恢复备份会替换当前记录")},text={Text("建议先导出当前备份。新版备份替换作息、习惯计划及打卡，并取消尚未结束的计时；旧版仅恢复作息，保留已有习惯。权限、验证点和本机事件记录保持。")},confirmButton={TextButton(onClick={restoreConfirm=false;restore.launch(arrayOf("application/json","text/plain"))}){Text("选择备份文件")}},dismissButton={TextButton(onClick={restoreConfirm=false}){Text("取消")}})
    if(consent) AlertDialog(onDismissRequest={consent=false},title={Text("开启应用拦截")},text={Text("廿一需要无障碍服务来识别当前应用的包名，并在你设定的时段显示拦截页面。\n\n不读取屏幕文字、聊天内容、密码，不上传应用使用信息。此权限随时可从系统设置关闭。\n\n同意后，请在系统页面选择「廿一」并启用服务。")},confirmButton={TextButton(onClick={consent=false;a.openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS,false)}){Text("同意并前往设置")}},dismissButton={TextButton(onClick={consent=false}){Text("暂不开启")}})
    if(qr) QrDialog(a,s.qrToken){qr=false}
    if(nfc) {
        DisposableEffect(Unit) { a.nfcListener={id->if(id.isNotBlank()){s.nfcId=id;nfc=false;onMessage("已绑定这个 NFC 标签。请把它固定在洗漱区。")}};onDispose{a.nfcListener=null} }
        AlertDialog(onDismissRequest={nfc=false},title={Text("触碰你的 NFC 标签")},text={Text("将手机背部 NFC 区域靠近标签。只读取标签标识，不写入或修改卡片。建议使用独立标签，不使用门禁或支付卡。")},confirmButton={TextButton(onClick={nfc=false}){Text("取消")}})
    }
}
@Composable fun LimitsDialog(r:Rules,onDismiss:()->Unit,onSave:(Rules)->Unit) {
    var second by remember{mutableIntStateOf(r.secondDelay)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("二次起床确认间隔")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        DurationPicker("二次确认间隔",second,1..15,"分钟"){second=it}
        SmallNote("第一次起床验证后，在这个间隔结束时再次确认。正在执行的验证不会被立即修改。")
    }},confirmButton={TextButton(onClick={onSave(r.copy(secondDelay=second))}){Text("保存")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}
@Composable fun SpecialDayDialog(r:Rules,onDismiss:()->Unit,onSave:(Rules)->Unit) {
    var date by remember{mutableStateOf(LocalDate.now().plusDays(1).toString())};var bed by remember{mutableStateOf(timeText(r.bed))};var wake by remember{mutableStateOf(timeText(r.wake))};var error by remember{mutableStateOf("")}
    AlertDialog(onDismissRequest=onDismiss,title={Text("提前安排一个特殊日期")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        SmallNote("用于出差、聚会等提前安排。起床是所选日期早上；睡前是所选日期晚间，00:00—11:59表示次日凌晨/上午。特殊日期优先于假期与普通作息。")
        if(r.exceptions.isNotEmpty())SmallNote(r.exceptions.toSortedMap().entries.toList().takeLast(7).joinToString("\n"){(d,t)->"$d · 起床 ${timeText(t.wake)} / 晚间 ${timeText(t.bed)}"})
        DateInput("特殊日期",date,minDate=LocalDate.now().plusDays(1)){date=it}
        TimeInput("该日起床",wake){wake=it};TimeInput("该晚睡前（凌晨算次日）",bed){bed=it}
        if(error.isNotEmpty())Text(error,color=Clay)
        TextButton(onClick={onSave(r.copy(exceptions=r.exceptions-date))}){Text("取消这个日期的特殊安排")}
    }},confirmButton={TextButton(onClick={val d=runCatching{LocalDate.parse(date)}.getOrNull();val b=parseTime(bed);val w=parseTime(wake);if(d==null||d.isBefore(LocalDate.now().plusDays(1))||b==null||w==null||b==w)error="请选择明天或之后的日期，并填写不同的有效时间。"else{val value=r.copy(exceptions=r.exceptions+(date to DaySchedule(b,w)));if(value.valid())onSave(value)else error="特殊日期数量已达到上限。"}}){Text("保存安排")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}
@Composable fun SettingLink(title:String,detail:String,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=8.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(title);SmallNote(detail)};Text("›",color=Muted)
    }
}
@Composable fun AppPicker(a:MainActivity,r:Rules,kind:String,onDismiss:()->Unit,onSave:(Set<String>)->Unit) {
    val installed=remember { a.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0).map { it.activityInfo.packageName to it.loadLabel(a.packageManager).toString() }.distinctBy { it.first }.filter { it.first!=a.packageName && it.first!="com.android.settings" && !it.first.contains("deskclock") }.sortedBy { it.second } }
    var selected by remember { mutableStateOf(if(kind=="blocked") r.blocked else r.allowed) };var query by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(kind=="blocked") "选择受限应用" else "额外放行应用")},text={Column{
        SmallNote("按整个应用选择。电话、闹钟、系统设置和桌面始终放行；放行列表优先于受限列表。")
        OutlinedTextField(query,{query=it},label={Text("搜索应用")},singleLine=true)
        LazyColumn(Modifier.heightIn(max=380.dp)) { items(installed.filter{it.second.contains(query,true)||it.first.contains(query,true)}){(pkg,label)->
            Row(Modifier.fillMaxWidth().clickable { selected=if(pkg in selected) selected-pkg else selected+pkg },verticalAlignment=Alignment.CenterVertically){Checkbox(pkg in selected,{selected=if(it)selected+pkg else selected-pkg});Column{Text(label);SmallNote(pkg)}}
        } }
    }},confirmButton={TextButton(onClick={onSave(selected)}){Text("保存 ${selected.size} 个")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}
fun qrBitmap(token:String):Bitmap {
    val matrix=MultiFormatWriter().encode(token,BarcodeFormat.QR_CODE,640,640)
    return Bitmap.createBitmap(640,640,Bitmap.Config.ARGB_8888).apply { for(y in 0 until 640)for(x in 0 until 640)setPixel(x,y,if(matrix[x,y])android.graphics.Color.BLACK else android.graphics.Color.WHITE) }
}
@Composable fun QrDialog(a:MainActivity,token:String,onDismiss:()->Unit) {
    val bitmap=remember(token){qrBitmap(token)}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")){uri->if(uri!=null)runCatching{a.contentResolver.openOutputStream(uri)?.use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}}
    AlertDialog(onDismissRequest=onDismiss,title={Text("放到洗漱区的起床码")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){Image(bitmap.asImageBitmap(),"专属起床二维码",Modifier.fillMaxWidth().aspectRatio(1f));SmallNote("保存后打印，固定在需要离床才能到达的位置。普通二维码可以复制，请把它当作行动约定。")}},confirmButton={TextButton(onClick={export.launch("廿一起床验证.png")}){Text("保存图片")}},dismissButton={TextButton(onClick=onDismiss){Text("关闭")}})
}
@Composable fun PrivacyDialog(onDismiss:()->Unit) {
    AlertDialog(onDismissRequest=onDismiss,title={Text("数据如何保存与使用")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("通用习惯计划、打卡、时段与备注保存在本机，随新版 JSON 备份导出；正在进行的计时不进入备份，恢复备份会结束未保存的计时。趋势在本机计算。开启自动复盘且已填写 API Key 后，每周日和习惯满21天时会在后台向 DeepSeek 发送周期内的习惯名称、目标、日期、数值及作息统计，按你的 API 账户计费。备注、验证点和聊天不用于自动复盘。")
        Text("计划、打卡、起床验证、例外记录、助手总结与聊天保存在本机，没有广告或分析 SDK。打开 App 时最多每6小时静默访问 GitHub 检查新版，手动检查仍可用，不上传记录。使用 DeepSeek 聊天时发送近7天作息统计与最近最多12条聊天消息，快捷总结发送所选日期统计，补记发送本次输入文字和对应日期。请求直接发给 DeepSeek 官方 API，数据处理遵循其服务政策。聊天在本机保留最近60条；聊天与AI复盘结果不进入作息备份。")
        Text("语音输入调用手机的识别服务，可能由该服务联网处理。廿一只接收识别文字，不保存录音；文字可修改，点击发送后才提交给 DeepSeek。没有兼容服务时可使用输入法语音。")
        Text("模型 API Key 和知识库只读密钥使用 Android Keystore 加密保存在本机，不进入 JSON 或系统备份。知识查询只发送你的查询文字，收藏、批注和习惯引用按登录账号保存。停止 AI 请求不能撤回已发送内容，服务端可能已处理并计费。对话补记需确认后保存，建议不会自动调整计划或闹钟。")
        Text("在账号页确认开启同步后，习惯、作息历史、聊天、复盘和普通设置会保存到独立项目服务，以便其他设备恢复。离线修改联网后重试；冲突需要选择保留版本，旧变更留在本机冲突历史。模型密钥和二维码/NFC验证点仅在你主动选择备份保护配置时走独立加密通道；服务器可在授权后解密，不是端到端加密。手机系统权限仍需本机授权。退出账号保留本机数据和归属。")
        Text("应用限制只识别当前应用包名，不获取屏幕内容。相机只在你主动扫码时使用，不保存照片。NFC 只识别你绑定的标签。")
        Text("睡前到点锁屏通过系统设备管理授权执行，只申请锁屏能力，不更改密码、不清除数据。到计划睡前时间仍未打卡时，每晚锁屏一次；授权可在系统安全设置撤销。")
        Text("导出文件由你选择保存位置。清除应用数据或卸载会删除本机记录，手动导出的文件需自行管理。")
    }},confirmButton={TextButton(onClick=onDismiss){Text("知道了")}})
}
