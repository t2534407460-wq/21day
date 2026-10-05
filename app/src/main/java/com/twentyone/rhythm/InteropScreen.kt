package com.twentyone.rhythm

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

internal object InteropHttp {
    fun query(value:String)=URLEncoder.encode(value,"UTF-8")
    suspend fun request(c:Context,path:String,method:String="GET",body:JSONObject?=null):JSONObject=withContext(Dispatchers.IO) {
        require(path.startsWith("knowledge/v2/") || path.startsWith("island-api/v1/items/"))
        val owner=ProjectAccount.userId(c) ?: error("请先在设置 → 账号中登录。")
        val token=ProjectAccount.accessToken(c)
        val connection=URL("https://zhuisu.leadjet.com.cn/$path").openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects=false;connection.requestMethod=method;connection.connectTimeout=15000;connection.readTimeout=35000
        connection.setRequestProperty("Authorization","Bearer $token")
        if(path.startsWith("knowledge/")) { val key=ProjectAccount.knowledgeKey(c);check(key.isNotEmpty()) {"请先填写知识库只读密钥。"};connection.setRequestProperty("X-Knowledge-Key",key) }
        try {
            if(body!=null){connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json; charset=utf-8");connection.outputStream.use{it.write(body.toString().toByteArray())}}
            val status=connection.responseCode
            val stream=if(status in 200..299)connection.inputStream else connection.errorStream
            val text=stream?.bufferedReader()?.use {reader->val result=StringBuilder();val buffer=CharArray(4096);while(true){val n=reader.read(buffer);if(n<0)break;check(result.length+n<=3*1024*1024){"响应过大，请缩小查询范围。"};result.append(buffer,0,n)};result.toString()} ?: ""
            check(ProjectAccount.userId(c)==owner){"账号已变化，请重新打开页面。"}
            val result=runCatching{JSONObject(text)}.getOrNull()
            if(status !in 200..299)error(when(status){401->"登录已过期，请重新登录账号。";403->"知识密钥无效或无权访问所选版本，请检查设置。";404->"来源接口或资料暂不可用。";409->result?.optString("message") ?: "内容已变更，请刷新后重试。";else->"来源服务暂不可用（$status），本机记录保留。"})
            result ?: error("来源响应无效。")
        }finally{connection.disconnect()}
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun InteropScreen(a:MainActivity,onAccount:()->Unit) {
    val scope=rememberCoroutineScope();var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf("")}
    var key by remember{mutableStateOf("")};var configured by remember{mutableStateOf(ProjectAccount.knowledgeKey(a).isNotEmpty())}
    var version by remember{mutableStateOf("7.0")};var versions by remember{mutableStateOf(listOf<String>())};var question by remember{mutableStateOf("")}
    var results by remember{mutableStateOf(JSONArray())};var snapshot by remember{mutableStateOf("")};var notes by remember{mutableStateOf(JSONArray())}
    var document by remember{mutableStateOf<JSONObject?>(null)};var note by remember{mutableStateOf("")};var bookmark by remember{mutableStateOf(true)}
    var noteId by remember{mutableStateOf(UUID.randomUUID().toString())};var revision by remember{mutableLongStateOf(0)};var linked by remember{mutableStateOf<String?>(null)}
    var items by remember{mutableStateOf(JSONArray())}
    val signedIn=remember { runCatching { ProjectAccount.email(a) }.getOrNull()!=null }
    fun run(block:suspend()->Unit){if(busy)return;busy=true;message="";scope.launch{try{block()}catch(e:CancellationException){throw e}catch(e:Exception){message=e.message ?: "连接未完成，请重试。"}finally{busy=false}}}
    suspend fun read(path:String,id:String){
        val doc=InteropHttp.request(a,"knowledge/v2/read?project=sMES&version=${InteropHttp.query(version)}&snapshotId=${InteropHttp.query(id)}&path=${InteropHttp.query(path)}")
        notes=InteropHttp.request(a,"knowledge/v2/notes").getJSONArray("notes")
        val saved=(0 until notes.length()).map{notes.getJSONObject(it)}.find{it.getJSONObject("data").getString("path")==path}
        noteId=saved?.getString("id") ?: UUID.randomUUID().toString();revision=saved?.getLong("revision") ?: 0;note=saved?.getJSONObject("data")?.optString("note") ?: "";bookmark=saved?.getJSONObject("data")?.optBoolean("bookmark") ?: true
        linked=saved?.getJSONObject("data")?.takeIf{it.optString("sourceProject")=="21day"}?.optString("sourceId")?.takeIf{it.isNotBlank() && it!="null"};document=doc
    }
    if(!signedIn) Sheet(color=Sage) {
        Text("先登录，再连接",fontWeight=FontWeight.SemiBold)
        SmallNote("知识查询和时屿事项需要同一账号。只读密钥可以先保存在本机。")
        Primary("前往登录",click=onAccount)
    }
    Sheet {
        Text("知识库",fontWeight=FontWeight.SemiBold)
        SmallNote("登录账号后填写只读密钥。密钥在本机加密保存；查询不会发送习惯记录。")
        OutlinedTextField(key,{key=it},label={Text(if(configured)"替换知识库密钥" else "知识库密钥")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
        FlowRow {
            TextButton(enabled=!busy && key.isNotBlank(),onClick={run{require(key.trim().length in 32..256){"请填写完整只读密钥。"};ProjectAccount.saveKnowledgeKey(a,key.trim());key="";configured=true;message="密钥已保存在本机。"}}){Text("保存密钥")}
            TextButton(enabled=!busy && configured,onClick={ProjectAccount.saveKnowledgeKey(a,"");configured=false;document=null;results=JSONArray();notes=JSONArray();versions=emptyList()}){Text("移除密钥")}
            TextButton(enabled=!busy && configured && signedIn,onClick={run{val access=InteropHttp.request(a,"knowledge/v2/access").getJSONArray("grants");versions=(0 until access.length()).map{access.getJSONObject(it).getString("version")}.distinct();if(version !in versions)version=versions.firstOrNull() ?: "";message="可访问版本：${versions.joinToString()}"}}){Text("验证访问")}
        }
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){versions.forEach{v->FilterChip(version==v,{version=v;results=JSONArray()},label={Text(v)})}}
        OutlinedTextField(version,{version=it},label={Text("sMES 版本")},singleLine=true)
        OutlinedTextField(question,{question=it},label={Text("查找知识")},modifier=Modifier.fillMaxWidth())
        Row {
            Button(enabled=!busy && question.isNotBlank() && configured && signedIn,onClick={run{val result=InteropHttp.request(a,"knowledge/v2/search","POST",JSONObject().put("project","sMES").put("version",version).put("question",question));snapshot=result.getString("snapshotId");results=result.getJSONArray("sources");message=if(results.length()==0)"当前版本未找到匹配内容。" else "找到 ${results.length()} 段来源"+(if(result.optBoolean("limited"))"，本次检索达到读取限制。" else "")}}){Text("查询")}
            TextButton(enabled=!busy && configured && signedIn,onClick={run{notes=InteropHttp.request(a,"knowledge/v2/notes").getJSONArray("notes");message="已读取 ${notes.length()} 条收藏与批注。"}}){Text("收藏与批注")}
        }
        for(i in 0 until results.length()){val source=results.getJSONObject(i)
            TextButton(enabled=!busy,onClick={run{read(source.getString("relativePath"),snapshot)}}){Text(source.getString("heading"))}
            SmallNote(source.getString("relativePath")+" · ${source.getInt("startLine")}–${source.getInt("endLine")} 行")
            Text(source.getString("text").take(300))
        }
        for(i in 0 until notes.length()){val saved=notes.getJSONObject(i).getJSONObject("data")
            if(saved.optBoolean("bookmark") || saved.optString("note").isNotBlank())TextButton(enabled=!busy,onClick={run{version=saved.getString("version");read(saved.getString("path"),saved.getString("snapshotId"))}}){Text(saved.getString("path").substringAfterLast('/')+" · "+saved.optString("note").take(60))}
        }
    }
    Sheet {
        Text("时屿关联事项",fontWeight=FontWeight.SemiBold)
        SmallNote("读取同一账号已同步到时屿的事项，保留来源；在时屿修改后刷新。")
        Button(enabled=!busy && signedIn,onClick={run{items=InteropHttp.request(a,"island-api/v1/items/cards").getJSONArray("cards");message=if(items.length()==0)"暂无关联事项，请先在时屿同步。"else"已刷新时屿事项。"}}){Text("刷新关联事项")}
        for(i in 0 until items.length()){val item=items.getJSONObject(i);Text(item.optString("title","事项"),fontWeight=FontWeight.SemiBold);SmallNote("时屿 · ${item.optString("status")} ${item.optString("dueAt")}")}
    }
    if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    if(message.isNotBlank())SmallNote(message)
    document?.let{doc->AlertDialog(onDismissRequest={document=null},title={Text(doc.getString("relativePath").substringAfterLast('/'))},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        SmallNote("sMES $version · 快照 ${doc.getString("snapshotId").take(8)}"+(if(!doc.optBoolean("current"))" · 历史版本" else ""))
        SelectionContainer{Text(doc.getString("text"))}
        Row{Checkbox(bookmark,{bookmark=it});Text("收藏")}
        OutlinedTextField(note,{note=it.take(5000)},label={Text("个人批注")})
        Text("关联习惯")
        TextButton(onClick={linked=null}){Text(if(linked==null)"✓ 不关联" else "不关联")}
        HabitStore(a).habits().forEach{habit->TextButton(onClick={linked=habit.id}){Text((if(linked==habit.id)"✓ "else"")+habit.name)}}
    }},confirmButton={TextButton(enabled=!busy,onClick={run{
        val data=JSONObject().put("baseRevision",revision).put("project","sMES").put("version",version).put("path",doc.getString("relativePath")).put("snapshotId",doc.getString("snapshotId")).put("contentHash",doc.getString("contentHash")).put("bookmark",bookmark).put("note",note).put("sourceProject",if(linked==null)JSONObject.NULL else "21day").put("sourceId",linked ?: JSONObject.NULL)
        revision=InteropHttp.request(a,"knowledge/v2/notes/$noteId","PUT",data).getLong("revision");document=null;message="收藏与批注已保存到当前账号。"
    }}){Text("保存")}},dismissButton={TextButton(onClick={document=null}){Text("关闭")}})}
}
