package com.twentyone.rhythm

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import java.time.*
import java.time.format.DateTimeFormatter

@Composable fun HabitDeck(a:MainActivity,now:LocalDateTime,revision:Int,onManage:()->Unit) {
    val store=remember { HabitStore(a) }
    val habits=remember(revision,now.toLocalDate()) { HabitCards.selected(a,"today",now.toLocalDate()) }
    var open by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text("今日打卡",fontSize=21.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
        TextButton(onClick=onManage) { Text(if(store.habits().isEmpty()) "添加习惯" else "管理习惯") }
    }
    Surface(onClick={open=true},shape=RoundedCornerShape(22.dp),color=Sage,modifier=Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Outlined.Inventory2,null,tint=Moss)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text("打卡抽屉",fontSize=19.sp,fontWeight=FontWeight.Medium)
                SmallNote("区间 ${habits.count { it.input==HabitInput.TIMER }} · 次数 ${habits.count { it.input==HabitInput.COUNT }} · 共 ${habits.size} 个习惯")
                Text(if(habits.isEmpty()) "选择习惯，放进你的卡组" else habits.take(3).joinToString(" · ") { it.name },fontSize=13.sp,color=Moss,maxLines=1)
            }
            Icon(Icons.Outlined.ExpandLess,"打开打卡抽屉",tint=Moss)
        }
    }
    if(open) HabitDrawer(a,"today",onDismiss={open=false},onManage={open=false;onManage()})
}

@Composable fun HabitAction(a:android.content.Context,store:HabitStore,h:Habit,entry:HabitEntry?,now:LocalDateTime,enabled:Boolean=true,compact:Boolean=false,onManual:()->Unit,onMessage:(String)->Unit) {
    val started=store.running(h.id);val today=now.toLocalDate();val due=h.scheduled(today) && !h.archived
    val elsewhere=store.otherDevice(h.id);val syncScope=rememberCoroutineScope()
    var cancel by remember { mutableStateOf(false) };var feedback by remember(h.id) { mutableIntStateOf(0) }
    val elapsed=if(started>0) ((now.epoch()-started)/1000).coerceAtLeast(0) else 0L
    val done=h.input==HabitInput.DAILY && entry!=null
    val label=when {
        started>0 && elsewhere->"长按接管"
        started>0->"长按结束"
        !due->if(today<h.start) "尚未开始" else "今天休息"
        done->"今日已记录"
        h.input==HabitInput.TIMER->"长按开始"
        h.input==HabitInput.COUNT->"长按 +1 ${h.unit}"
        h.smoking->"长按确认零支"
        h.input==HabitInput.DAILY->"长按打卡"
        else->"填写记录"
    }
    val value=if(started>0) "%02d:%02d:%02d".format(elapsed/3600,elapsed/60%60,elapsed%60)
        else if(entry==null) "尚未记录" else if(h.input==HabitInput.DAILY) if(h.smoking && entry.value>0) "已如实记录" else "已记录"
        else "${entry.value} ${h.unit}"+(if(entry.remainderSeconds>0) " ${entry.remainderSeconds} 秒" else "")
    if(h.input==HabitInput.MANUAL) OutlinedButton(onClick=onManual,enabled=due && enabled) { Text("填写今日总量") }
    else HoldHabitButton(label,value,enabled && (started>0 || (due && !done)),h,feedback,started>0,compact) {
        runCatching {
            when(h.input) {
                HabitInput.TIMER->if(started>0 && elsewhere) {store.takeOverTimer(h.id);syncScope.launch {runCatching{ProjectSync.run(a)}.onSuccess{onMessage(if(ProjectSync.timerClaimPending(a,h.id))"接管尚未确认，请检查同步状态。"else "已接管，继续计时或长按结束。")}.onFailure{onMessage("接管等待同步，原计时和记录已保留。")}}} else if(started>0) store.finishTimer(h.id) else store.startTimer(h.id)
                HabitInput.COUNT->store.count(h.id)
                HabitInput.DAILY->store.confirmDay(h.id)
                HabitInput.MANUAL->Unit
            }
            HabitReminders.cancelNotice(a,h.id);HabitReminders.schedule(a)
        }.onSuccess { feedback++ }.onFailure { onMessage(it.message ?: "未能保存，请重试") }
    }
    if(!compact || started>0) SmallNote(when {
        started>0->"${Instant.ofEpochMilli(started).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm:ss"))} 开始 · "+if(elsewhere)"由另一台设备负责，接管需联网确认"else"离开页面仍计时"
        !due->if(today<h.start) "${h.start} 开始这轮计划" else "未安排打卡，已有记录会保留"
        h.input==HabitInput.TIMER->"每天可多次累计，时段归开始日。短按不会开始。"
        h.smoking && h.input==HabitInput.DAILY->"建议睡前确认今天是否零支；如有吸烟，请调整为实际支数。"
        h.input==HabitInput.COUNT->if(h.mode==HabitMode.AT_MOST && entry!=null && entry.value>h.rule(today).target) "已超过自定上限，继续如实记录。" else "松开后再次长按才会增加；长按不连记。"
        else->"短按不记录；可在下方调整真实情况。"
    })
    if(started>0 && !elsewhere) TextButton(onClick={cancel=true}) { Text("取消本次计时",color=Muted) }
    if(cancel) AlertDialog(onDismissRequest={cancel=false},title={Text("取消本次计时？")},text={Text("本次未结束的时段不计入总量，以前的记录保留。")},confirmButton={TextButton(onClick={runCatching{store.cancelTimer(h.id)}.onSuccess{cancel=false}.onFailure{cancel=false;onMessage(it.message ?: "取消未完成，请重试")}}){Text("确认取消")}},dismissButton={TextButton(onClick={cancel=false}){Text("继续计时")}})
}

@Composable private fun HoldHabitButton(label:String,value:String,enabled:Boolean,h:Habit,feedback:Int,running:Boolean,compact:Boolean=false,onHold:()->Unit) {
    val latest by rememberUpdatedState(onHold);val progress=remember { Animatable(0f) }
    val scope=rememberCoroutineScope();val haptic=LocalHapticFeedback.current
    Box(Modifier.size(if(compact) 132.dp else 196.dp),contentAlignment=Alignment.Center) {
        Box(Modifier.size(if(compact) 116.dp else 170.dp).background(if(enabled) Sage else Paper,CircleShape).border(1.dp,Moss.copy(alpha=.2f),CircleShape)
            .semantics(mergeDescendants=true) {
                contentDescription=label;stateDescription=value;role=Role.Button
                if(!enabled) disabled() else onLongClick(label) { latest();true }
            }.pointerInput(enabled,label) {
                if(enabled) awaitEachGesture {
                    awaitFirstDown(requireUnconsumed=false)
                    val job=scope.launch { progress.snapTo(0f);progress.animateTo(1f,tween(650,easing=LinearEasing)) }
                    val held=withTimeoutOrNull(650L) { waitForUpOrCancellation();false } ?: true
                    job.cancel();scope.launch { progress.snapTo(0f) }
                    if(held) { haptic.performHapticFeedback(HapticFeedbackType.LongPress);latest();waitForUpOrCancellation() }
                }
            },contentAlignment=Alignment.Center) {
            Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(3.dp)) {
                HabitAnimation(h,feedback,running,progress.value,compact)
                Text(value,fontSize=if(compact) 14.sp else if(value.length>12) 16.sp else 21.sp,fontWeight=FontWeight.Medium,color=Ink)
                Text(label,fontSize=13.sp,color=Moss)
            }
        }
        Canvas(Modifier.size(if(compact) 126.dp else 182.dp)) { if(progress.value>0) drawArc(Moss,-90f,360f*progress.value,false,style=Stroke(3.dp.toPx())) }
    }
}
