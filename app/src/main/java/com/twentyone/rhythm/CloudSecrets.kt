package com.twentyone.rhythm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Explicit protected migration. Clear credentials never enter the ordinary sync journal or JSON backup. */
object CloudSecrets {
    private const val endpoint="https://zhuisu.leadjet.com.cn/21day-api/v1/vault/"
    private suspend fun request(c:Context,path:String,body:JSONObject?=null):JSONObject?=withContext(Dispatchers.IO) {
        val connection=URL(endpoint+path).openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects=false;connection.connectTimeout=15000;connection.readTimeout=30000
        connection.setRequestProperty("Authorization","Bearer "+ProjectAccount.accessToken(c));connection.setRequestProperty("Accept","application/json")
        try {
            if(body!=null){connection.requestMethod="PUT";connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json; charset=utf-8");connection.outputStream.use {it.write(body.toString().toByteArray(Charsets.UTF_8))}}
            val status=connection.responseCode
            if(status==404 && body==null)return@withContext null
            check(status in 200..299){when(status){409->"云端保护配置已有修改，请先恢复或核对。";401,403->"请重新登录后操作保护配置。";else->"保护配置服务暂不可用，本机内容保留。"}}
            val bytes=connection.inputStream.use { input -> val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(1024);while(true){val n=input.read(buffer);if(n<0)break;check(output.size()+n<=8192);output.write(buffer,0,n)};output.toByteArray() }
            JSONObject(bytes.toString(Charsets.UTF_8))
        }finally{connection.disconnect()}
    }
    suspend fun ready(c:Context)=request(c,"status")?.optBoolean("ready")==true
    suspend fun backup(c:Context):String=withContext(Dispatchers.IO) {
        check(ready(c)){"保护配置服务尚未启用。"}
        val user=ProjectAccount.userId(c) ?: error("请先登录。")
        val values=linkedMapOf("verification-points" to JSONObject().put("qrToken",Store(c).qrToken).put("nfcId",Store(c).nfcId))
        if(CloudCoach.configured(c))values["deepseek"]=JSONObject().put("key",CloudCoach.readKey(c))
        var count=0
        PreferenceDatabase(c,ProjectPreferences.databaseName).use { helper -> values.forEach { (kind,value) ->
            check(ProjectAccount.userId(c)==user){"账号已退出。"}
            val revision=SyncJournal.metadata(helper.readableDatabase,"vault_$kind")?.toLongOrNull() ?: 0L
            val result=request(c,kind,JSONObject().put("baseRevision",revision).put("value",value)) ?: error("保护配置响应为空。")
            SyncJournal.metadata(helper.writableDatabase,"vault_$kind",result.getLong("revision").toString());count++
        } }
        "已通过保护通道备份 $count 类配置。"
    }
    suspend fun restore(c:Context):String=withContext(Dispatchers.IO) {
        val s=Store(c)
        check(!s.wake.active && s.plan?.let { Schedule.night(it,s.rules,java.time.LocalDateTime.now()) }==null){"请在当前夜间限制和起床验证结束后恢复验证点。"}
        val user=ProjectAccount.userId(c) ?: error("请先登录。")
        val points=request(c,"verification-points");val model=request(c,"deepseek")
        check(ProjectAccount.userId(c)==user){"账号已退出，未恢复。"}
        points?.getJSONObject("value")?.let { require(it.getString("qrToken").startsWith("rhythm://wake/") && it.getString("nfcId").length<=256) }
        model?.getJSONObject("value")?.getString("key")?.let { require(it.length in 16..512 && it.all { ch->ch.code in 33..126 }) }
        // Write the model key via Keystore; only then apply verification values transactionally.
        model?.getJSONObject("value")?.getString("key")?.let { CloudCoach.saveKey(c,it) }
        ProjectPreferences.atomic(c) { points?.getJSONObject("value")?.let { s.prefs.edit().putString("qr_token",it.getString("qrToken")).putString("nfc_id",it.getString("nfcId")).apply() } }
        PreferenceDatabase(c,ProjectPreferences.databaseName).use { helper ->
            points?.let { SyncJournal.metadata(helper.writableDatabase,"vault_verification-points",it.getLong("revision").toString()) }
            model?.let { SyncJournal.metadata(helper.writableDatabase,"vault_deepseek",it.getLong("revision").toString()) }
        }
        if(points==null && model==null)"云端尚未备份保护配置。" else "保护配置已恢复。系统权限仍需在本机授权，请核对实体二维码和 NFC。"
    }
}
