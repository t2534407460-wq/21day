package com.twentyone.rhythm

import android.content.Intent
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun readRecoveryQr(c:Context,uri:Uri):String {
    val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
    c.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it,null,bounds) }
    check(bounds.outWidth>0 && bounds.outHeight>0) { "无法读取二维码图片。" }
    val options=BitmapFactory.Options().apply { inSampleSize=1;while(bounds.outWidth/inSampleSize>2048 || bounds.outHeight/inSampleSize>2048) inSampleSize*=2 }
    val bitmap=c.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it,null,options) } ?: error("无法读取二维码图片。")
    return try {
        val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width,bitmap.height,pixels)))).text
    } finally { bitmap.recycle() }
}
@Composable fun EmergencyExitDialog(a:WakeActivity,onDismiss:()->Unit) {
    val scope=rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val email=remember { runCatching { ProjectAccount.email(a) }.getOrNull() }
    suspend fun verify(qr:String) {
        busy=true
        try {
            if(Alarms.emergencyStop(a,qr)) onDismiss() else message="起床任务已变化，请重新申请邮箱二维码。"
        } catch(e:Exception) { message=e.message ?: "验证失败，闹钟将继续响铃。" }
        finally { busy=false }
    }
    val scan=rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let { scope.launch { verify(it) } } }
    val image=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if(uri!=null) scope.launch {
            busy=true
            try {
                val qr=withContext(Dispatchers.IO) { readRecoveryQr(a,uri) }
                verify(qr)
            } catch(e:Exception) { message=if(e is NotFoundException) "图片中未找到二维码，请选择邮件中的原图。" else e.message ?: "无法验证这张图片。" }
            finally { busy=false }
        }
    }
    AlertDialog(onDismissRequest={if(!busy)onDismiss()},title={Text("邮箱紧急验证")},text={Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("仅限身体不适或原验证点无法使用。必须验证邮箱收到的本次二维码，才会停止闹钟；退出不会记为已验证起床。")
        Text(if(email==null) "请先登录已验证邮箱的账号。" else "二维码将发送到：$email")
        SmallNote("二维码10分钟内有效，仅限本机本次起床任务，一次使用后失效。请在另一台设备打开邮件扫码，或保存邮件图片后选择。")
        if(message.isNotBlank()) Text(message)
        if(busy) LinearProgressIndicator()
        if(email==null) TextButton(onClick={a.startActivity(Intent(a,MainActivity::class.java).setAction("account_settings"));onDismiss()}) { Text("打开账号设置") }
        else {
            TextButton(enabled=!busy,onClick={scope.launch {
                busy=true
                try { ProjectAccount.requestWakeRecovery(a);message="验证邮件已发送，请查收（也请检查垃圾邮件）。" }
                catch(e:Exception) { message=e.message ?: "发送失败，请稍后重试。" }
                finally { busy=false }
            }}) { Text("发送紧急验证二维码到邮箱") }
            TextButton(enabled=!busy,onClick={scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("扫描邮箱收到的紧急二维码").setBeepEnabled(false).setCaptureActivity(ScannerActivity::class.java))}) { Text("扫描邮件二维码") }
            TextButton(enabled=!busy,onClick={image.launch("image/*")}) { Text("选择邮件二维码图片") }
        }
    }},confirmButton={TextButton(enabled=!busy,onClick=onDismiss) { Text("返回起床验证") }})
}
