package com.twentyone.rhythm

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class WidgetDrawerActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        val id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,intent.data?.lastPathSegment?.toIntOrNull() ?: -1)
        if(!WidgetCards.owned(this,id)) { finish();return }
        val item=intent.getStringExtra("habit_id")
        val small=SmallWidgets.isSmall(this,id)
        if(item=="sleep") { startActivity(Intent(this,MainActivity::class.java).setAction("bedtime_record"));finish();return }
        val configuring=intent.action==AppWidgetManager.ACTION_APPWIDGET_CONFIGURE || intent.action=="configure" || intent.data?.host=="edit" || item=="empty"
        setContent { RhythmTheme {
            var choosing by remember { mutableStateOf(configuring) }
            var showSelection by remember { mutableStateOf(false) }
            var revision by remember { mutableIntStateOf(0) }
            DisposableEffect(Unit) {
                val prefs=Store(this@WidgetDrawerActivity).prefs
                val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,_->revision++ }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }
            fun done() { RhythmWidget.refresh(this);setResult(Activity.RESULT_OK,Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id));finish() }
            if(choosing) Scaffold(containerColor=Paper) { padding ->
                Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                    Heading(if(small) if(SmallWidgets.isData(this@WidgetDrawerActivity,id)) "2×1 习惯数据卡" else "2×2 打卡按钮" else "桌面打卡卡组")
                    if(small) {
                        SmallNote(if(SmallWidgets.isData(this@WidgetDrawerActivity,id)) "选一个已有习惯。桌面显示今日数据，点击查看和打卡。" else "选一个已有习惯。点击按钮准备，再在15秒内确认，直接在桌面记录；点习惯名查看详情。桌面长按仍用于移动或编辑。")
                        var selected by remember { mutableStateOf(SmallWidgets.selected(this@WidgetDrawerActivity,id)?.id) }
                        RestoreWidgetSettings(this@WidgetDrawerActivity,id,true){selected=SmallWidgets.selected(this@WidgetDrawerActivity,id)?.id}
                        val habits=remember(revision) { HabitCards.available(this@WidgetDrawerActivity) }
                        if(habits.isEmpty()) SmallNote("还没有可选习惯，请先在习惯页面添加。")
                        habits.groupBy { HabitCards.group(it) }.forEach { (group,list) ->
                            Text(group)
                            list.forEach { h ->
                                fun choose() { selected=h.id;Store(this@WidgetDrawerActivity).prefs.edit().remove("widget_arm_$id").apply();HabitCards.save(this@WidgetDrawerActivity,WidgetCards.key(id),setOf(h.id)) }
                                Row(Modifier.fillMaxWidth().clickable { choose() },verticalAlignment=Alignment.CenterVertically) { RadioButton(selected==h.id,{choose()});Text(h.name,Modifier.weight(1f)) }
                            }
                        }
                        Primary("完成设置") { done() }
                        TextButton(onClick={startActivity(Intent(this@WidgetDrawerActivity,MainActivity::class.java).setAction("habits"))}) { Text("管理习惯") }
                    } else {
                    SmallNote("每个组件可以选择不同的习惯；卡组内上下滑动切换，点开后长按打卡。")
                    var schedule by remember { mutableStateOf(Store(this@WidgetDrawerActivity).prefs.getBoolean("widget_schedule_$id",true)) }
                    RestoreWidgetSettings(this@WidgetDrawerActivity,id,false){schedule=Store(this@WidgetDrawerActivity).prefs.getBoolean("widget_schedule_$id",true)}
                    Row(verticalAlignment=Alignment.CenterVertically) { Text("显示作息卡片",Modifier.weight(1f));Switch(schedule,{ schedule=it;Store(this@WidgetDrawerActivity).prefs.edit().putBoolean("widget_schedule_$id",it).apply() }) }
                    Primary("选择习惯") { showSelection=true }
                    OutlinedButton(onClick={choosing=false},modifier=Modifier.fillMaxWidth()) { Text("打开打卡抽屉") }
                    Primary("完成设置") { done() }
                    SmallNote("桌面长按用于移动或编辑组件。卡片中的持续长按打卡在抽屉内完成，避免误记。")
                    }
                    SmallNote("小米小部件中心的展示由系统与平台审核决定；当前也可从 Android 小部件列表添加「廿一 · 打卡卡组」。")
                }
            } else HabitDrawer(this,WidgetCards.key(id),item,onDismiss={done()},onManage={startActivity(Intent(this,MainActivity::class.java).setAction("habits"));done()},onChoose=if(small) ({ choosing=true }) else null)
            if(showSelection) HabitSelection(this,WidgetCards.key(id),{showSelection=false},{showSelection=false})
        } }
    }
}
