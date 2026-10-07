package com.twentyone.rhythm

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal object InteropHttp {
    suspend fun request(c:Context,path:String):JSONObject=withContext(Dispatchers.IO) {
        require(path=="island-api/v1/items/cards")
        val owner=ProjectAccount.userId(c) ?: error("请先在设置 → 账号中登录。")
        val token=ProjectAccount.accessToken(c)
        val connection=URL("https://zhuisu.leadjet.com.cn/$path").openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects=false;connection.requestMethod="GET";connection.connectTimeout=15000;connection.readTimeout=35000
        connection.setRequestProperty("Authorization","Bearer $token")
        try {
            val status=connection.responseCode
            val stream=if(status in 200..299)connection.inputStream else connection.errorStream
            val text=stream?.bufferedReader()?.use {reader->val result=StringBuilder();val buffer=CharArray(4096);while(true){val n=reader.read(buffer);if(n<0)break;check(result.length+n<=3*1024*1024){"响应过大，请缩小查询范围。"};result.append(buffer,0,n)};result.toString()} ?: ""
            check(ProjectAccount.userId(c)==owner){"账号已变化，请重新打开页面。"}
            val result=runCatching{JSONObject(text)}.getOrNull()
            if(status !in 200..299)error(when(status){401->"登录已过期，请重新登录账号。";403->"当前账号无权访问时屿事项。";404->"来源接口或资料暂不可用。";409->result?.optString("message") ?: "内容已变更，请刷新后重试。";else->"来源服务暂不可用（$status），本机记录保留。"})
            result ?: error("来源响应无效。")
        }finally{connection.disconnect()}
    }
}

@Composable fun InteropScreen(a:MainActivity,onAccount:()->Unit) {
    val scope=rememberCoroutineScope();var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf("")}
    var items by remember{mutableStateOf(JSONArray())}
    val signedIn=remember { runCatching { ProjectAccount.email(a) }.getOrNull()!=null }
    if(!signedIn) Sheet(color=Sage) {
        Text("先登录，再连接",fontWeight=FontWeight.SemiBold)
        SmallNote("时屿关联事项需要使用同一账号。")
        Primary("前往登录",click=onAccount)
    }
    Sheet {
        Text("时屿关联事项",fontWeight=FontWeight.SemiBold)
        SmallNote("读取同一账号已同步到时屿的事项，保留来源；在时屿修改后刷新。")
        Button(enabled=!busy && signedIn,onClick={
            busy=true;message=""
            scope.launch {
                try { items=InteropHttp.request(a,"island-api/v1/items/cards").getJSONArray("cards");message=if(items.length()==0)"暂无关联事项，请先在时屿同步。"else"已刷新时屿事项。" }
                catch(e:CancellationException){throw e}
                catch(e:Exception){message=e.message ?: "连接未完成，请重试。"}
                finally{busy=false}
            }
        }){Text("刷新关联事项")}
        for(i in 0 until items.length()){val item=items.getJSONObject(i);Text(item.optString("title","事项"),fontWeight=FontWeight.SemiBold);SmallNote("时屿 · ${item.optString("status")} ${item.optString("dueAt")}")}
    }
    if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    if(message.isNotBlank())SmallNote(message)
}
