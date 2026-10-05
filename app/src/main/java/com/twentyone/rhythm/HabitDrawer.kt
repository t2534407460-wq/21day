package com.twentyone.rhythm

import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import java.time.LocalDate

object HabitCards {
    fun available(c:Context,today:LocalDate=LocalDate.now())=HabitStore(c).let { store ->
        store.habits().filter { !it.archived && (it.end>=today || store.running(it.id)>0) }
    }
    fun selected(c:Context,key:String,today:LocalDate=LocalDate.now()):List<Habit> {
        val ids=Store(c).prefs.getStringSet("cards_$key",null)
        return available(c,today).filter { ids==null || it.id in ids }
    }
    fun save(c:Context,key:String,ids:Set<String>) { Store(c).prefs.edit().putStringSet("cards_$key",ids).commit();RhythmWidget.refresh(c) }
    fun group(h:Habit)=when(h.input) { HabitInput.TIMER->"区间打卡";HabitInput.COUNT->"次数打卡";HabitInput.DAILY->"每日确认";HabitInput.MANUAL->"填写总量" }
}

@Composable fun HabitSelection(c:Context,key:String,onDismiss:()->Unit,onSaved:()->Unit) {
    val all=remember { HabitCards.available(c) }
    var selected by remember { mutableStateOf(HabitCards.selected(c,key).map { it.id }.toSet()) }
    AlertDialog(onDismissRequest=onDismiss,title={Text("选择卡组里的习惯")},text={
        Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            SmallNote("只改变卡片展示；习惯、提醒和历史记录都会保留。")
            if(all.isEmpty()) Text("还没有习惯，请先到习惯页创建。")
            all.groupBy { HabitCards.group(it) }.forEach { (group,habits) ->
                Text(group,color=Moss,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=8.dp))
                habits.forEach { h ->
                    Row(Modifier.fillMaxWidth().clickable { selected=if(h.id in selected) selected-h.id else selected+h.id },verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(h.id in selected,{ selected=if(it) selected+h.id else selected-h.id })
                        Text(h.name,Modifier.weight(1f))
                    }
                }
            }
        }
    },confirmButton={TextButton(onClick={HabitCards.save(c,key,selected);onSaved()}) { Text("保存卡组") }},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}

@Composable fun HabitDrawer(c:Context,key:String,initialId:String?=null,onDismiss:()->Unit,onManage:()->Unit,onChoose:(()->Unit)?=null) {
    val store=remember { HabitStore(c) };val prefs=remember { Store(c).prefs };val now=clock()
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,_->revision++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val all=remember(revision,now.toLocalDate()) { HabitCards.selected(c,key,now.toLocalDate()) }
    var group by rememberSaveable { mutableStateOf("全部") }
    var select by remember { mutableStateOf(false) };var edit by remember { mutableStateOf<Habit?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val habits=all.filter { group=="全部" || HabitCards.group(it)==group }
    // Recreate the pager together with its key lookup when the selected IDs change.
    // Otherwise its old key map can read the new, shorter list during recomposition.
    androidx.compose.runtime.key(habits.map { it.id }) {
    val pager=rememberPagerState(initialPage=habits.indexOfFirst { it.id==initialId }.coerceAtLeast(0)) { habits.size }
    val scope=rememberCoroutineScope()
    val shortHeight=LocalConfiguration.current.screenHeightDp<480
    LaunchedEffect(group,habits.map { it.id }) { if(habits.isNotEmpty() && pager.currentPage>=habits.size) pager.scrollToPage(habits.lastIndex) }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.BottomCenter) {
            Surface(Modifier.heightIn(max=620.dp).fillMaxWidth().fillMaxHeight(if(shortHeight) 1f else .9f).navigationBarsPadding(),shape=RoundedCornerShape(topStart=28.dp,topEnd=28.dp),color=Paper) {
                Column(Modifier.padding(horizontal=20.dp,vertical=if(shortHeight) 4.dp else 12.dp),verticalArrangement=Arrangement.spacedBy(if(shortHeight) 0.dp else 10.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text("打卡抽屉",Modifier.weight(1f),fontSize=23.sp,fontWeight=FontWeight.Medium)
                        TextButton(onClick={if(onChoose!=null) onChoose() else select=true}) { Text("选择习惯") }
                        TextButton(onClick=onDismiss) { Text("收起") }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf("全部","区间打卡","次数打卡","每日确认","填写总量").forEach { name ->
                            FilterChip(group==name,{group=name;scope.launch { pager.scrollToPage(0) }},label={Text(name)},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Sage,selectedLabelColor=Ink))
                        }
                    }
                    if(habits.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                        Text("这个分区还没有卡片")
                        TextButton(onClick={if(onChoose!=null) onChoose() else select=true}) { Text("从我的习惯中选择") }
                        TextButton(onClick=onManage) { Text("创建 / 管理习惯") }
                    } else {
                        HorizontalPager(pager,modifier=Modifier.weight(1f).fillMaxWidth(),pageSpacing=12.dp,key={habits[it].id},flingBehavior=PagerDefaults.flingBehavior(pager,snapPositionalThreshold=.2f)) { page ->
                            val h=habits[page];val entry=remember(revision,h.id,now.toLocalDate()) { store.entries().find { it.habitId==h.id && it.date==now.toLocalDate() } }
                            Surface(shape=RoundedCornerShape(22.dp),color=Color.White,border=BorderStroke(1.dp,Sage),modifier=Modifier.fillMaxWidth()) {
                                if(shortHeight) Row(Modifier.verticalScroll(rememberScrollState()).padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                        Text(h.name,fontSize=22.sp,fontWeight=FontWeight.Medium)
                                        SmallNote("${HabitCards.group(h)} · ${h.goal(now.toLocalDate())}")
                                        TextButton(onClick={edit=h}) { Text("查看 / 调整今日记录") }
                                    }
                                    Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally) {
                                        HabitAction(c,store,h,entry,now,enabled=!pager.isScrollInProgress,compact=true,onManual={edit=h},onMessage={message=it})
                                    }
                                } else Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Text(HabitCards.group(h),color=Moss,fontSize=12.sp)
                                    Text(h.name,fontSize=24.sp,fontWeight=FontWeight.Medium)
                                    SmallNote(h.goal(now.toLocalDate()))
                                    HabitAction(c,store,h,entry,now,enabled=!pager.isScrollInProgress,compact=true,onManual={edit=h},onMessage={message=it})
                                    TextButton(onClick={edit=h}) { Text("查看 / 调整今日记录") }
                                }
                            }
                        }
                        if(!shortHeight || habits.size>1) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                            TextButton(enabled=habits.size>1,onClick={scope.launch { pager.animateScrollToPage((pager.currentPage-1+habits.size)%habits.size) }}) { Text("上一张") }
                            Text("${pager.currentPage+1} / ${habits.size}",color=Muted)
                            TextButton(enabled=habits.size>1,onClick={scope.launch { pager.animateScrollToPage((pager.currentPage+1)%habits.size) }}) { Text("下一张") }
                        }
                        if(!shortHeight) SmallNote("左右切卡 · 长按打卡 · 松开后才可再次记录")
                    }
                }
            }
        }
    }
    }
    if(select) HabitSelection(c,key,{select=false},{select=false})
    edit?.let { h ->
        val date=now.toLocalDate()
        if(!h.scheduled(date)) AlertDialog(onDismissRequest={edit=null},text={Text("今天未安排打卡，已有记录保留。")},confirmButton={TextButton(onClick={edit=null}){Text("知道了")}})
        else HabitRecordDialog(h,date,store.entries().find { it.habitId==h.id && it.date==date },onDismiss={edit=null},onSave={e->
            runCatching { store.record(e);HabitReminders.schedule(c);RhythmWidget.refresh(c) }.onSuccess { edit=null }.onFailure { message=it.message }
        },onRemove={store.removeEntry(h.id,date);HabitReminders.schedule(c);RhythmWidget.refresh(c);edit=null})
    }
    message?.let { AlertDialog(onDismissRequest={message=null},text={Text(it)},confirmButton={TextButton(onClick={message=null}) { Text("知道了") }}) }
}
