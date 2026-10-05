package com.twentyone.rhythm

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class AppRelease(val version:String,val url:String,val size:Long,val sha256:String,val notes:String)
data class UpdateDownload(val version:String,val status:Int,val percent:Int,val file:File?)

object AppUpdates {
    const val REPOSITORY="t2534407460-wq/21day"
    const val RELEASES_URL="https://github.com/$REPOSITORY/releases"
    private const val MAX_APK=250_000_000L
    fun newer(candidate:String,current:String):Boolean {
        fun parts(v:String):List<Int>?=v.removePrefix("v").takeIf{it.matches(Regex("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}"))}?.split('.')?.map{it.toInt()}
        val a=parts(candidate)?:return false;val b=parts(current)?:return false
        return a.zip(b).firstOrNull{it.first!=it.second}?.let{it.first>it.second}?:false
    }
    fun parse(json:JSONObject,current:String):AppRelease? {
        if(json.optBoolean("draft") || json.optBoolean("prerelease")) return null
        val version=json.getString("tag_name").removePrefix("v")
        if(!newer(version,current))return null
        val assets=json.getJSONArray("assets")
        val asset=(0 until assets.length()).map{assets.getJSONObject(it)}.firstOrNull{it.getString("name")=="21day-$version-debug.apk"}
            ?:error("新版尚未提供安装包，请稍后再试。")
        val url=asset.getString("browser_download_url")
        val uri=Uri.parse(url)
        require(uri.scheme=="https" && uri.host=="github.com" && uri.userInfo==null && uri.path?.startsWith("/$REPOSITORY/releases/download/")==true){"更新地址不属于官方发布仓库。"}
        val size=asset.getLong("size");require(size in 1..MAX_APK){"安装包大小无效。"}
        val digest=asset.optString("digest").removePrefix("sha256:")
        require(digest.matches(Regex("[a-fA-F0-9]{64}"))){"发布包缺少校验信息，请稍后再试。"}
        return AppRelease(version,url,size,digest.lowercase(),json.optString("body").take(1600))
    }
    suspend fun check():AppRelease?=withContext(Dispatchers.IO) {
        val connection=URL("https://api.github.com/repos/$REPOSITORY/releases/latest").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout=15000;connection.readTimeout=15000;connection.instanceFollowRedirects=false
            connection.setRequestProperty("Accept","application/vnd.github+json")
            connection.setRequestProperty("User-Agent","TwentyOne/${BuildConfig.VERSION_NAME}")
            when(connection.responseCode){
                404->error("更新仓库还没有可用的公开版本。")
                403,429->error("检查过于频繁，请稍后再试。")
                200->Unit
                else->error("暂时无法检查更新（${connection.responseCode}）。")
            }
            val text=connection.inputStream.bufferedReader().use { reader ->
                val buffer=CharArray(500_001);var used=0
                while(used<buffer.size){val count=reader.read(buffer,used,buffer.size-used);if(count<0)break;used+=count}
                require(used<buffer.size){"更新信息过大。"};String(buffer,0,used)
            }
            parse(JSONObject(text),BuildConfig.VERSION_NAME)
        } finally {connection.disconnect()}
    }
    fun start(context:Context,release:AppRelease) {
        val manager=context.getSystemService(DownloadManager::class.java)
        val prefs=context.getSharedPreferences("updates",Context.MODE_PRIVATE)
        val old=prefs.getLong("download_id",-1)
        if(old!=-1L)manager.remove(old)
        val folder=File(context.getExternalFilesDir(null)?:error("下载目录不可用。"),"updates").apply{mkdirs()}
        val file=File(folder,"update.apk")
        if(file.exists())require(file.delete()){"旧下载文件无法替换。"}
        val request=DownloadManager.Request(Uri.parse(release.url)).setTitle("廿一 ${release.version}")
            .setDescription("正在下载更新，原有记录会保留")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(file))
        val id=manager.enqueue(request)
        prefs.edit().putLong("download_id",id).putString("version",release.version).putString("sha256",release.sha256).putLong("size",release.size).apply()
    }
    fun download(context:Context):UpdateDownload? {
        val prefs=context.getSharedPreferences("updates",Context.MODE_PRIVATE)
        val id=prefs.getLong("download_id",-1)
        if(id==-1L)return null
        if(!newer(prefs.getString("version","")!!,BuildConfig.VERSION_NAME))return null
        context.getSystemService(DownloadManager::class.java).query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if(cursor==null || !cursor.moveToFirst())return null
            val status=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return UpdateDownload(prefs.getString("version","")!!,status,if(total>0)((done*100/total).toInt().coerceIn(0,100))else 0,File(context.getExternalFilesDir(null),"updates/update.apk"))
        }
    }
    suspend fun verify(context:Context,file:File)=withContext(Dispatchers.IO) {
        val prefs=context.getSharedPreferences("updates",Context.MODE_PRIVATE)
        require(file.isFile && file.length()==prefs.getLong("size",-1)){"安装包下载不完整，请重新下载。"}
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input->val buffer=ByteArray(65536);while(true){val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count)} }
        require(digest.digest().joinToString(""){"%02x".format(it)}==prefs.getString("sha256",null)){"安装包校验失败，请重新下载。"}
        val pm=context.packageManager
        val archive=pm.getPackageArchiveInfo(file.absolutePath,PackageManager.GET_SIGNING_CERTIFICATES)?:error("安装包无效。")
        val current=pm.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
        require(archive.packageName==context.packageName && archive.longVersionCode>current.longVersionCode && archive.versionName==prefs.getString("version",null)){"安装包与当前应用或版本不匹配。"}
        val installedSigners=current.signingInfo?.apkContentsSigners?.map{it.toCharsString()}?.toSet()
        val downloadedSigners=archive.signingInfo?.apkContentsSigners?.map{it.toCharsString()}?.toSet()
        require(!installedSigners.isNullOrEmpty() && installedSigners==downloadedSigners){"安装包签名不一致，已停止安装。"}
    }
    fun install(context:Context,file:File) {
        val uri=FileProvider.getUriForFile(context,context.packageName+".updates",file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun allowInstall(context:Context) {context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")))}
}
