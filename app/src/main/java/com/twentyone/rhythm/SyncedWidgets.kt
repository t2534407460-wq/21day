package com.twentyone.rhythm

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import org.json.JSONObject

internal data class SavedWidget(val names:String,val ids:Set<String>,val schedule:Boolean)
internal fun savedWidgets(c:Context,single:Boolean):List<SavedWidget> {
    val available=HabitCards.available(c).associateBy {it.id};val own=ProjectAccount.device(c)
    return PreferenceDatabase(c,ProjectPreferences.databaseName).use { helper ->
        val layouts=mutableListOf<JSONObject>()
        helper.readableDatabase.rawQuery("SELECT data FROM sync_remote WHERE entity_type='ui_layouts' AND data IS NOT NULL",null).use { rows -> while(rows.moveToNext())layouts.add(JSONObject(rows.getString(0))) }
        layouts.mapNotNull { data ->
            val key=data.optString("key");val device=data.optString("device")
            if(device==own || !key.startsWith("cards_widget_") || data.optString("type")!="set")return@mapNotNull null
            @Suppress("UNCHECKED_CAST") val ids=SyncCodec.value(data).decoded() as Set<String>
            val retained=ids.intersect(available.keys)
            if(retained.isEmpty() || single && retained.size!=1)return@mapNotNull null
            val oldId=key.removePrefix("cards_widget_")
            val schedule=layouts.find {it.optString("device")==device && it.optString("key")=="widget_schedule_$oldId"}?.let {SyncCodec.value(it).decoded() as? Boolean} ?: true
            SavedWidget(retained.mapNotNull {available[it]?.name}.joinToString("、"),retained,schedule)
        }.distinctBy {it.ids to it.schedule}
    }
}
@Composable internal fun RestoreWidgetSettings(c:Context,id:Int,single:Boolean,onRestored:()->Unit) {
    val saved=remember {savedWidgets(c,single)};var show by remember {mutableStateOf(false)}
    if(saved.isNotEmpty())TextButton(onClick={show=true}) {Text("恢复其他设备的卡片选择")}
    if(show)AlertDialog(onDismissRequest={show=false},title={Text("选一组已同步的习惯")},text={
        Column {
            SmallNote("应用到这个新组件，手机桌面的大小和位置仍由系统管理。")
            saved.forEach { selection -> TextButton(onClick={
                ProjectPreferences.atomic(c) {
                    val edit=Store(c).prefs.edit().putStringSet("cards_${WidgetCards.key(id)}",selection.ids.toMutableSet()).remove("widget_arm_$id")
                    if(!single)edit.putBoolean("widget_schedule_$id",selection.schedule)
                    edit.apply()
                };RhythmWidget.refresh(c);show=false;onRestored()
            }) {Text(selection.names)} }
        }
    },confirmButton={TextButton(onClick={show=false}){Text("返回")}})
}
