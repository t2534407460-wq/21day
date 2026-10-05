package com.twentyone.rhythm

import android.nfc.NfcAdapter
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ScannerActivity:CaptureActivity()
class WakeActivity:ComponentActivity() {
    private var nfcMessage by mutableStateOf("")
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if(Alarms.clearRestWake(this)) { finish();return }
        enableEdgeToEdge();setShowWhenLocked(true);setTurnScreenOn(true)
        setContent { RhythmTheme { WakeScreen(this,nfcMessage) } }
    }
    override fun onResume() {
        super.onResume()
        if(Alarms.clearRestWake(this)) { finish();return }
        NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(this,{tag->
            val id=tag.id.joinToString(""){"%02X".format(it)}
            runOnUiThread { val s=Store(this);nfcMessage=if(id.isNotEmpty()&&id==s.nfcId){if(Alarms.verify(this))"验证已收到" else "尚未到下一次验证时间，或当前没有起床任务"}else "这不是你绑定的起床标签" }
        },NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,null)
    }
    override fun onPause(){NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this);super.onPause()}
}
@Composable fun WakeScreen(a:WakeActivity,nfcMessage:String) {
    val now=clock();val s=remember{Store(a)};var refresh by remember{mutableIntStateOf(0)}
    DisposableEffect(Unit){val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener{_,_->refresh++};s.prefs.registerOnSharedPreferenceChangeListener(listener);onDispose{s.prefs.unregisterOnSharedPreferenceChangeListener(listener)}}
    val wake=remember(refresh,now){s.wake};var message by remember{mutableStateOf("")}
    var emergency by remember{mutableStateOf(false)}
    val scan=rememberLauncherForActivityResult(ScanContract()){result->
        if(result.contents!=null) message=if(result.contents==s.qrToken){if(Alarms.verify(a))"验证已收到"else"请等到下一次确认时间再扫描。"}else"这不是你的起床二维码，请到设置中查看。"
    }
    Column(Modifier.fillMaxSize().background(Paper).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(28.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Eyebrow(if(wake.test)"起床验证 · 试用" else "WAKE UP WITH INTENTION");Text("廿一",color=Moss)}
        Spacer(Modifier.height(10.dp));LargeNumber(now.format(DateTimeFormatter.ofPattern("HH:mm")),size=64)
        val title=when(wake.phase){WakePhase.COMPLETE->"新的一天，\n从这里开始。";WakePhase.SECOND_WAIT->"已经离床，\n再给自己几分钟。";WakePhase.SECOND_READY->"再确认一次，\n然后好好开始。";WakePhase.STOPPED->"本次验证已停止。";WakePhase.TIMED_OUT->"还没有确认起床。";WakePhase.IDLE->"还没有起床任务。";else->"离开床铺，\n走向新的早晨。"}
        Heading(title);DawnArt(Modifier.fillMaxWidth().height(130.dp))
        if(wake.active) {
            Sheet(color=Sage) {
                Eyebrow(if(wake.firstAt>0) "第二次确认" else "第一次确认")
                Text(if(wake.phase==WakePhase.SECOND_WAIT)"先洗漱、喝水。到时间后，再扫一次码或触碰标签。"else "拿起手机，到洗漱区扫描起床码，或触碰已绑定的 NFC 标签。",fontSize=17.sp)
                val seconds=((wake.dueAt-System.currentTimeMillis())/1000).coerceAtLeast(0)
                if(wake.phase==WakePhase.SECOND_WAIT) LargeNumber("${seconds/60}:${(seconds%60).toString().padStart(2,'0')}",size=34)
                else SmallNote("提醒 ${wake.attempts} / 3 · 完成验证前，不会直接记为已起床。")
            }
            if(wake.phase==WakePhase.RINGING || wake.phase==WakePhase.SECOND_READY) Primary("开始起床 · 安静验证 3 分钟"){Alarms.begin(a)}
            Primary("扫描洗漱区的起床码",enabled=wake.phase!=WakePhase.SECOND_WAIT || System.currentTimeMillis()>=wake.dueAt){
                scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("扫描放在洗漱区的廿一起床码").setBeepEnabled(false).setOrientationLocked(true).setCaptureActivity(ScannerActivity::class.java))
            }
            if(s.nfcId.isNotEmpty()) SmallNote("NFC 已绑定：保持这个页面打开，用手机背部触碰标签即可。")
            else SmallNote("还没有 NFC 标签？可以使用设置中保存的起床二维码。")
            if(message.isNotEmpty()) Text(message,color=Clay)
            if(nfcMessage.isNotEmpty()) Text(nfcMessage,color=Moss)
            TextButton(onClick={if(wake.test)Alarms.stop(a,"结束试用闹钟") else emergency=true},modifier=Modifier.align(Alignment.CenterHorizontally)){Text(if(wake.test) "结束试用" else "紧急停止本次验证",color=Clay)}
        } else {
            Text(when(wake.phase){WakePhase.COMPLETE->if(wake.test)"试用完成，不会计入正式记录。"else"醒后记录已自动保存，无需再次打卡。已记录验证时间。洗漱之后，给自己一个从容的早晨。";WakePhase.STOPPED->if(wake.test)"试用已结束，不计入正式记录。"else"本次记为未验证，没有记录为已经起床。";WakePhase.TIMED_OUT->"提醒已达到上限。没有收到验证，不代表判断你仍在睡觉。";else->"之前的进度仍然保留。"},color=Muted)
            Primary("回到今天"){a.startActivity(Intent(a,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));a.finish()}
        }
    }
    if(emergency && wake.active && !wake.test) EmergencyExitDialog(a){emergency=false}
}
