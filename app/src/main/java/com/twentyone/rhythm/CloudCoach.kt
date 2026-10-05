package com.twentyone.rhythm

import android.app.DownloadManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object CloudCoach {
    const val model="deepseek-flash"
    const val endpoint="https://api.deepseek.com/chat/completions"
    const val platform="https://platform.deepseek.com/api_keys"
    private const val alias="rhythm.deepseek.key"
    private fun file(c:Context)=AtomicFile(File(c.noBackupFilesDir,"deepseek-key.json"))
    fun configured(c:Context)=file(c).baseFile.exists()
    private fun key(create:Boolean):SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        check(create) { "密钥无法解密，请重新填写 API Key" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun saveKey(c:Context,value:String) {
        val plain=value.trim()
        require(plain.length in 16..512 && plain.all { it.code in 33..126 }) { "请填写完整的 API Key，不要包含空格或换行" }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key(true)) }
        val encrypted=cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val data=JSONObject().put("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP))
            .put("data",Base64.encodeToString(encrypted,Base64.NO_WRAP)).toString().toByteArray(Charsets.UTF_8)
        val target=file(c);val out=target.startWrite()
        try { out.write(data);target.finishWrite(out) }
        catch(e:Exception) { target.failWrite(out);throw IllegalStateException("密钥保存失败，请重试") }
    }
    @Synchronized fun readKey(c:Context):String {
        check(configured(c)) { "请先在助手设置中填写 DeepSeek API Key" }
        return try {
            val data=JSONObject(file(c).readFully().toString(Charsets.UTF_8))
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,Base64.decode(data.getString("iv"),Base64.NO_WRAP)))
            }
            cipher.doFinal(Base64.decode(data.getString("data"),Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch(e:Exception) { throw IllegalStateException("密钥无法读取，请重新填写 API Key") }
    }
    @Synchronized fun removeKey(c:Context) {
        file(c).delete()
        check(!configured(c)) { "密钥暂时无法移除，请重试" }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry(alias) }
    }
    // Only the previous assistant's private model, cache and download are removed.
    // Called on IO at app entry; failures remain retryable on the next entry.
    @Synchronized fun clearLegacyModel(c:Context) {
        val old=c.getSharedPreferences("coach_model",Context.MODE_PRIVATE)
        val id=old.getLong("download",-1)
        if(id>=0) c.getSystemService(DownloadManager::class.java).remove(id)
        val directory=File(c.noBackupFilesDir,"coach")
        check(!directory.exists() || directory.deleteRecursively()) { "旧模型未能完全清理，下次打开助手时会重试" }
        c.getExternalFilesDir("coach")?.let {
            val download=File(it,"model.download")
            check(!download.exists() || download.delete()) { "旧模型下载未能清理，下次打开助手时会重试" }
        }
        old.edit().clear().commit()
    }
}

object CoachInference {
    suspend fun generate(context:Context,prompt:String,json:Boolean=false):String=withTimeout(90_000) {
        withContext(Dispatchers.IO) {
            val key=CloudCoach.readKey(context)
            request(URL(CloudCoach.endpoint).openConnection() as HttpURLConnection,key,prompt,json)
        }
    }
    // The connection parameter lets contract tests exercise cancellation and errors without real credentials.
    internal suspend fun request(connection:HttpURLConnection,key:String,prompt:String,json:Boolean=false):String {
        require(prompt.length in 1..12000) { "本次内容过长，请缩短后重试" }
        return requestMessages(connection,key,JSONArray().put(JSONObject().put("role","user").put("content",prompt)),json)
    }
    suspend fun chat(context:Context,history:List<CoachMessage>,facts:CoachFacts):String=withTimeout(90_000) {
        withContext(Dispatchers.IO) {
            val messages=CoachChat.requestMessages(history,facts)
            requestMessages(URL(CloudCoach.endpoint).openConnection() as HttpURLConnection,CloudCoach.readKey(context),messages)
        }
    }
    internal suspend fun requestMessages(connection:HttpURLConnection,key:String,messages:JSONArray,json:Boolean=false):String = withContext(Dispatchers.IO) {
        val data=JSONObject().put("model",CloudCoach.model).put("stream",false).put("temperature",0)
            .put("thinking",JSONObject().put("type","disabled")).put("max_tokens",1024)
            .put("messages",messages)
        if(json) data.put("response_format",JSONObject().put("type","json_object"))
        val bytes=data.toString().toByteArray(Charsets.UTF_8)
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                continuation.context.ensureActive()
                connection.apply {
                    requestMethod="POST";connectTimeout=15_000;readTimeout=60_000
                    instanceFollowRedirects=false;useCaches=false;doOutput=true
                    setRequestProperty("Authorization","Bearer $key")
                    setRequestProperty("Content-Type","application/json; charset=utf-8")
                    setRequestProperty("Accept","application/json")
                    setFixedLengthStreamingMode(bytes.size)
                }
                connection.outputStream.use { it.write(bytes) }
                val code=connection.responseCode
                check(code==200) { when(code) {
                    401,403->"DeepSeek 密钥无效或无权限，请检查 API Key"
                    402->"DeepSeek 账户余额不足，请到官方平台查看"
                    429->"DeepSeek 请求过于频繁，请稍后重试"
                    500,502,503,504->"DeepSeek 服务暂时不可用，请稍后重试"
                    in 300..399->"服务返回了重定向，已停止请求，请稍后重试"
                    else->"DeepSeek 请求失败（$code），请稍后重试或检查应用更新"
                } }
                val body=ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer=ByteArray(4096)
                    while(true) {
                        continuation.context.ensureActive()
                        val n=input.read(buffer);if(n<0) break
                        check(body.size()+n<=131072) { "服务返回的内容过长，请重试" }
                        body.write(buffer,0,n)
                    }
                }
                val answer=parse(body.toString("UTF-8"))
                if(continuation.isActive) continuation.resume(answer)
            } catch(e:Exception) {
                val failure=when(e) {
                    is CancellationException->e
                    is SocketTimeoutException->IllegalStateException("连接或等待 DeepSeek 超时，请检查网络后重试")
                    is IOException->IllegalStateException("无法连接 DeepSeek，请检查网络后重试")
                    is IllegalStateException->e
                    else->IllegalStateException("服务返回的内容无法识别，请重试")
                }
                if(continuation.isActive) continuation.resumeWithException(failure)
            } finally { connection.disconnect() }
        }
    }
    internal fun parse(body:String):String {
        val choice=JSONObject(body).getJSONArray("choices").getJSONObject(0)
        check(choice.optString("finish_reason")=="stop") { "本次回答未完整生成，请重试；没有保存补记" }
        val message=choice.getJSONObject("message")
        check(!message.has("tool_calls") || message.optJSONArray("tool_calls")?.length()==0) { "服务返回了不支持的内容，请重试" }
        val content=message.opt("content") as? String
        check(content!=null && content.isNotBlank() && content.length<=8000) { "模型没有生成有效内容，请换一种说法重试" }
        return content.trim()
    }
}
