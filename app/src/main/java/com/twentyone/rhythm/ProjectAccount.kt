package com.twentyone.rhythm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

data class AccountChallenge(val id:String,val expires:String)
object ProjectAccount {
    private const val endpoint="https://zhuisu.leadjet.com.cn/identity/v1/"
    private const val alias="rhythm.project-account"
    private val lock=Mutex()
    private fun file(c:Context,name:String)=AtomicFile(File(c.noBackupFilesDir,"account-$name.json"))
    private fun key(create:Boolean):SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        check(create) { "账号凭据无法读取，请重新登录。" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized private fun write(c:Context,name:String,data:JSONObject) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key(true)) }
        val payload=JSONObject().put("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP))
            .put("data",Base64.encodeToString(cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)).toString().toByteArray(Charsets.UTF_8)
        val target=file(c,name);val out=target.startWrite()
        try { out.write(payload);target.finishWrite(out) } catch(e:Exception) { target.failWrite(out);throw e }
    }
    @Synchronized private fun read(c:Context,name:String):JSONObject? {
        if(!file(c,name).baseFile.exists())return null
        val payload=JSONObject(file(c,name).readFully().toString(Charsets.UTF_8))
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,Base64.decode(payload.getString("iv"),Base64.NO_WRAP))) }
        return JSONObject(cipher.doFinal(Base64.decode(payload.getString("data"),Base64.NO_WRAP)).toString(Charsets.UTF_8))
    }
    fun email(c:Context):String?=read(c,"session")?.getString("email")
    @Synchronized internal fun device(c:Context):String {
        val prefs=c.getSharedPreferences("account_device",Context.MODE_PRIVATE)
        return prefs.getString("id",null) ?: UUID.randomUUID().toString().also { check(prefs.edit().putString("id",it).commit()) }
    }
    internal fun userId(c:Context):String?=read(c,"session")?.getString("userId")
    internal fun knowledgeKey(c:Context):String=read(c,"knowledge")?.optString("key") ?: ""
    internal fun saveKnowledgeKey(c:Context,value:String) { require(value.isEmpty() || value.length in 32..256);if(value.isEmpty())file(c,"knowledge").delete()else write(c,"knowledge",JSONObject().put("key",value)) }
    private fun post(path:String,data:JSONObject):JSONObject? {
        val connection=URL(endpoint+path).openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects=false;connection.connectTimeout=15000;connection.readTimeout=30000
        connection.requestMethod="POST";connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json; charset=utf-8")
        try {
            connection.outputStream.use { it.write(data.toString().toByteArray(Charsets.UTF_8)) }
            val status=connection.responseCode
            if(status in 300..399)error("账号服务重定向，请稍后重试。")
            val stream=if(status in 200..299)connection.inputStream else connection.errorStream
            val bytes=stream?.use { input -> val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096);while(true){val n=input.read(buffer);if(n<0)break;check(out.size()+n<=16384) { "账号响应过大。" };out.write(buffer,0,n)};out.toByteArray() }
            val result=bytes?.takeIf { it.isNotEmpty() }?.let { runCatching { JSONObject(it.toString(Charsets.UTF_8)) }.getOrNull() }
            if(status !in 200..299)error(if(status==404)"账号服务尚未部署。" else result?.optString("message")?.takeIf { it.length in 1..256 } ?: "账号服务暂时不可用，请稍后重试。")
            return result
        } finally { connection.disconnect() }
    }
    private fun accept(c:Context,pair:JSONObject,email:String) {
        val owner=read(c,"owner");val id=pair.getString("userId")
        if(owner!=null && owner.getString("userId")!=id) {
            post("sessions/revoke",JSONObject().put("refreshToken",pair.getString("refreshToken")))
            error("本机数据已绑定另一个账号，请使用独立数据空间，避免合并两个账号的数据。")
        }
        require(UUID.fromString(id)!=UUID(0,0) && pair.getInt("expiresIn") in 1..3600)
        if(owner==null)write(c,"owner",JSONObject().put("userId",id).put("storeId",UUID.randomUUID().toString()))
        write(c,"session",pair.put("email",email.trim().lowercase(java.util.Locale.ROOT)).put("expiresAt",System.currentTimeMillis()+pair.getInt("expiresIn")*1000L))
    }
    suspend fun login(c:Context,email:String,password:String)=withContext(Dispatchers.IO) { lock.withLock {
        accept(c,post("email/login",JSONObject().put("email",email).put("password",password).put("clientId","21day-android").put("deviceId",device(c))) ?: error("账号响应为空。"),email)
    } }
    suspend fun register(c:Context,email:String,password:String):AccountChallenge=withContext(Dispatchers.IO) { lock.withLock {
        val data=post("email/register",JSONObject().put("email",email).put("password",password).put("clientId","21day-android").put("deviceId",device(c))) ?: error("账号响应为空。")
        AccountChallenge(data.getString("challengeId"),data.getString("expiresAt"))
    } }
    suspend fun reset(email:String,password:String):AccountChallenge=withContext(Dispatchers.IO) {
        val data=post("email/reset",JSONObject().put("email",email).put("newPassword",password)) ?: error("账号响应为空。")
        AccountChallenge(data.getString("challengeId"),data.getString("expiresAt"))
    }
    suspend fun confirm(c:Context,challenge:AccountChallenge,code:String,email:String,reset:Boolean)=withContext(Dispatchers.IO) { lock.withLock {
        val result=post("email/confirm",JSONObject().put("challengeId",challenge.id).put("code",code))
        if(!reset)accept(c,result ?: error("账号响应为空。"),email)
    } }
    suspend fun accessToken(c:Context):String=withContext(Dispatchers.IO) { lock.withLock {
        var session=read(c,"session") ?: error("请先登录账号。")
        if(session.getLong("expiresAt")<=System.currentTimeMillis()+30000) {
            val refreshed=post("sessions/refresh",JSONObject().put("refreshToken",session.getString("refreshToken"))) ?: error("账号响应为空。")
            accept(c,refreshed,session.getString("email"));session=read(c,"session")!!
        }
        session.getString("accessToken")
    } }
    suspend fun logout(c:Context)=withContext(Dispatchers.IO) { lock.withLock {
        read(c,"session")?.let { post("sessions/revoke",JSONObject().put("refreshToken",it.getString("refreshToken"))) }
        file(c,"session").delete()
    } }
}
