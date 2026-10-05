package com.twentyone.rhythm

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable fun UpdateCard(activity:MainActivity) {
    val scope=rememberCoroutineScope()
    var busy by remember {mutableStateOf(false)}
    var release by remember {mutableStateOf<AppRelease?>(null)}
    var message by remember {mutableStateOf("当前版本 ${BuildConfig.VERSION_NAME}")}
    var download by remember {mutableStateOf(AppUpdates.download(activity))}
    LaunchedEffect(Unit){while(true){download=AppUpdates.download(activity);delay(1000)}}
    val downloading=download?.status in listOf(DownloadManager.STATUS_PENDING,DownloadManager.STATUS_RUNNING,DownloadManager.STATUS_PAUSED)
    Sheet {
        Text("应用更新",fontWeight=FontWeight.SemiBold)
        Text(message)
        if(downloading) {
            LinearProgressIndicator(progress={ (download?.percent?:0)/100f },modifier=Modifier.fillMaxWidth())
            SmallNote(if(download?.status==DownloadManager.STATUS_PAUSED) "等待网络恢复，系统会继续下载。" else "正在下载 ${download?.percent?:0}% · 可以收起页面")
        }
        if(download?.status==DownloadManager.STATUS_FAILED) SmallNote("下载未完成，请检查网络后重新检查更新。")
        OutlinedButton(enabled=!busy && !downloading,onClick={busy=true;scope.launch {
            try {release=AppUpdates.check();message=release?.let{"发现新版本 ${it.version}"}?:"已经是最新版本 ${BuildConfig.VERSION_NAME}"}
            catch(e:Exception){message=e.localizedMessage?:"检查失败，请稍后再试。"}
            finally{busy=false}
        }},modifier=Modifier.fillMaxWidth()){Text(if(busy) "正在处理…" else "检查更新")}
        release?.let { next ->
            if(next.notes.isNotBlank()) SmallNote(next.notes)
            if(!downloading && (download?.status!=DownloadManager.STATUS_SUCCESSFUL || download?.version!=next.version)) Primary("下载新版 · ${next.size/1_000_000} MB") {
                runCatching {AppUpdates.start(activity,next);download=AppUpdates.download(activity)}.onFailure{message=it.localizedMessage?:"无法开始下载。"}
            }
            if(download?.status==DownloadManager.STATUS_SUCCESSFUL && !busy) TextButton(onClick={
                runCatching {AppUpdates.start(activity,next);download=AppUpdates.download(activity)}.onFailure{message=it.localizedMessage?:"无法重新下载。"}
            }){Text("重新下载")}
        }
        if(download?.status==DownloadManager.STATUS_SUCCESSFUL) Primary("安装更新 ${download?.version}",enabled=!busy) {
            if(!activity.packageManager.canRequestPackageInstalls()) {
                message="请允许廿一安装更新，返回后再次点击「安装更新」。"
                runCatching {AppUpdates.allowInstall(activity)}.onFailure{message="请在系统设置中允许廿一安装应用。"}
            } else {busy=true;scope.launch {
                try {val file=download?.file?:error("未找到安装包。");AppUpdates.verify(activity,file);AppUpdates.install(activity,file)}
                catch(e:Exception){message=e.localizedMessage?:"安装包校验失败。"}
                finally{busy=false}
            }}
        }
        TextButton(onClick={runCatching{activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(AppUpdates.RELEASES_URL)))}}){Text("查看版本发布页")}
        SmallNote("每次打开主界面或返回前台时检查更新；没有新版不弹窗，联网失败会在下次打开时重试。从 GitHub 下载，校验后由系统确认安装，保留记录。")
    }
}

@Composable fun AutomaticUpdatePrompt(activity:MainActivity,onOpenUpdates:()->Unit) {
    var release by remember { mutableStateOf<AppRelease?>(null) };var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activity) {
        activity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                val next=AppUpdates.check()
                error=null;release=next
            } catch(e:CancellationException) { throw e } catch(_:Exception) { /* Retry on the next foreground entry without interrupting normal use. */ }
        }
    }
    release?.let { next -> AlertDialog(onDismissRequest={release=null},title={Text("发现新版本 ${next.version}")},text={
        Column(Modifier.heightIn(max=280.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("是否下载更新？现有计划和记录会保留。")
            if(next.notes.isNotBlank()) SmallNote(next.notes)
            error?.let { Text(it) }
        }
    },confirmButton={TextButton(onClick={
        runCatching { AppUpdates.start(activity,next) }.onSuccess { release=null;onOpenUpdates() }.onFailure { error=it.message ?: "暂时无法下载" }
    }){Text("下载更新")}},dismissButton={TextButton(onClick={release=null}){Text("稍后")}}) }
}
