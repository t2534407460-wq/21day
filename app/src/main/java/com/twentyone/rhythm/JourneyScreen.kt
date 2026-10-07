package com.twentyone.rhythm

import android.content.Intent
import androidx.compose.foundation.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.*
import java.time.format.DateTimeFormatter

fun stamp(at:Long):String=Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM.dd HH:mm"))

@Composable fun PlanScreen(s:Store,p:Plan,r:Rules,now:LocalDateTime,onDay:(String)->Unit,onEdit:()->Unit,onRenew:()->Unit) {
    val current=BedtimeSchedule.recordDay(p,r,now)
    val currentIndex=(p.day(current ?: now.toLocalDate())-1).coerceIn(0,20)
    var selected by rememberSaveable(p.start.toString()) { mutableIntStateOf(currentIndex) }
    val date=p.start.plusDays(selected.toLong());val morning=date.plusDays(1)
    val eveningLog=s.log(date.toString());val morningLog=s.log(morning.toString())
    val checked=BedtimeSchedule.checked(eveningLog);val verified=morningLog.verifiedAt>0
    val nights=(0L..20L).count { BedtimeSchedule.checked(s.log(p.start.plusDays(it).toString())) }
    val mornings=(1L..21L).count { s.log(p.start.plusDays(it).toString()).verifiedAt>0 }
    val context=LocalContext.current
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
        Column { Eyebrow("21 NIGHTS · 21 MORNINGS");Heading("把早晚，连成习惯。") }
        IconButton(onClick=onEdit){Icon(Icons.Outlined.Tune,"调整计划")}
    }
    Sheet(color=Ink) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Eyebrow(if(current!=null) "正在经历的这一晚" else if(now.toLocalDate().isBefore(p.start)) "准备开始" else "本轮回顾",Lime)
                Row(verticalAlignment=Alignment.Bottom) { LargeNumber("${currentIndex+1}".padStart(2,'0'),Color.White,52);Text(" / 21",color=Lime,modifier=Modifier.padding(bottom=9.dp),fontSize=18.sp) }
            }
            Column(horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("$nights 次睡前打卡",color=Color.White,fontSize=14.sp)
                Text("$mornings 次醒后验证",color=Lime,fontSize=14.sp)
            }
        }
        Text(p.goal,color=Color.White.copy(alpha=.85f),fontSize=14.sp)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(3.dp)) {
            repeat(21) { index ->
                val d=p.start.plusDays(index.toLong())
                Box(Modifier.weight(1f).height(5.dp).background(when(s.nightStatus(d)) { "已完成"->Lime;"部分完成"->Clay;else->Color.White.copy(alpha=.18f) },RoundedCornerShape(3.dp)))
            }
        }
        SmallLight("${p.start.format(DateTimeFormatter.ofPattern("MM.dd"))} 晚 — ${p.start.plusDays(21).format(DateTimeFormatter.ofPattern("MM.dd"))} 早 · 一晚接次晨")
    }
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Text("选择一个夜晚",fontWeight=FontWeight.SemiBold)
            if(current!=null && selected!=currentIndex) TextButton(onClick={selected=currentIndex}){Text("回到当前")}
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            repeat(3) { week -> FilterChip(colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Sage,selectedLabelColor=Ink),selected=selected/7==week,onClick={selected=week*7+(selected%7)},label={Text("第 ${week+1} 周")},modifier=Modifier.weight(1f)) }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            repeat(7) { column ->
                val index=selected/7*7+column;val d=p.start.plusDays(index.toLong());val active=index==selected
                val bedDone=BedtimeSchedule.checked(s.log(d.toString()));val wakeDone=s.log(d.plusDays(1).toString()).verifiedAt>0
                Column(Modifier.weight(1f).background(if(active) Moss else Color.Transparent,RoundedCornerShape(14.dp))
                    .clickable { selected=index }.semantics(mergeDescendants=true) { contentDescription="第 ${index+1} 天，${d} 晚，${if(d.isAfter(now.toLocalDate())) "尚未开始" else s.nightStatus(d)}，睡前${if(bedDone) "已打卡" else "未打卡"}，醒后${if(wakeDone) "已验证" else if(RestCalendar.isRest(d.plusDays(1))) "休息日自然醒" else "未验证"}" }
                    .padding(vertical=10.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    Text("${index+1}".padStart(2,'0'),fontSize=18.sp,fontWeight=FontWeight.Medium,color=if(active) Color.White else Ink)
                    Text("${d.monthValue}.${d.dayOfMonth}",fontSize=10.sp,color=if(active) Color.White.copy(alpha=.75f) else Muted)
                    Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Outlined.DarkMode,null,tint=if(bedDone) (if(active) Lime else Moss) else (if(active) Color.White.copy(alpha=.28f) else Muted.copy(alpha=.3f)),modifier=Modifier.size(12.dp))
                        Icon(Icons.Outlined.WbSunny,null,tint=if(wakeDone) (if(active) Lime else Clay) else (if(active) Color.White.copy(alpha=.28f) else Muted.copy(alpha=.3f)),modifier=Modifier.size(12.dp))
                    }
                }
            }
        }
        SmallNote("月亮是睡前，太阳是醒后；点亮代表已完成。")
    }
    Sheet {
        Eyebrow("DAY ${(selected+1).toString().padStart(2,'0')}  ·  这一晚与次晨")
        Text("${date.format(DateTimeFormatter.ofPattern("MM.dd"))} 晚  →  ${morning.format(DateTimeFormatter.ofPattern("MM.dd"))} 早",fontSize=24.sp,fontWeight=FontWeight.Medium)
        Text(if(date.isAfter(now.toLocalDate())) "尚未开始" else s.nightStatus(date),color=Moss,fontWeight=FontWeight.SemiBold)
        SmallNote(if(RestCalendar.isRest(morning)) "休息日：睡前打卡完成即为已完成。" else "工作日：睡前打卡和次晨验证两项完成才为已完成；一项为部分完成，两项未完成为未完成。")
        HorizontalDivider(color=Sage)
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).background(Sage,CircleShape),contentAlignment=Alignment.Center){Icon(Icons.Outlined.DarkMode,null,tint=Moss)}
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("睡前 · ${date.format(DateTimeFormatter.ofPattern("MM月dd日"))}",fontWeight=FontWeight.SemiBold)
                Text(if(checked) "已打卡 · ${stamp(eveningLog.bedtimeCheckedAt)}" else "计划 ${BedtimeSchedule.bedtime(date,r).format(DateTimeFormatter.ofPattern("MM.dd HH:mm"))} · ${if(current==date) "待打卡" else if(date.isAfter(now.toLocalDate())) "尚未开始" else "未完成打卡"}",color=if(checked) Moss else Muted,fontSize=14.sp)
                if(listOf(eveningLog.sleepiness,eveningLog.bedMood,eveningLog.bedReason).any { it.isNotEmpty() }) SmallNote("困意 ${eveningLog.sleepiness.ifEmpty{"—"}} · 心情 ${eveningLog.bedMood.ifEmpty{"—"}}\n影响原因 ${eveningLog.bedReason.ifEmpty{"—"}}")
                if(current==date) {
                    Primary(if(checked) "查看睡前打卡" else "开始睡前打卡") { onDay(date.toString()) }
                    if(!checked) SmallNote("超过 ${BedtimeSchedule.deadline(date,r).format(DateTimeFormatter.ofPattern("MM.dd HH:mm"))} 仍未完成，执行一次系统锁屏。需授权锁屏和精确闹钟。")
                }
            }
        }
        Box(Modifier.padding(start=19.dp).width(2.dp).height(20.dp).background(Sage))
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).background(Lime.copy(alpha=.6f),CircleShape),contentAlignment=Alignment.Center){Icon(Icons.Outlined.WbSunny,null,tint=Clay)}
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("醒后 · ${morning.format(DateTimeFormatter.ofPattern("MM月dd日"))}",fontWeight=FontWeight.SemiBold)
                Text(if(verified) "已验证 · ${stamp(morningLog.verifiedAt)}" else if(RestCalendar.isRest(morning)) "休息日自然醒 · 参考 ${timeText(r.wakeTime(morning))}" else "计划 ${timeText(r.wakeTime(morning))} · 等待验证",fontSize=14.sp,color=if(verified) Moss else Muted)
                SmallNote(if(verified) "全部起床验证完成后自动保存。" else if(RestCalendar.isRest(morning)) "不响起床闹钟，不要求扫码或 NFC；不会自动记为已起床。" else "扫码或 NFC 完成全部验证后自动记录；试用和应急退出不计入。")
                if(!RestCalendar.isRest(morning) && s.wake.day==morning.toString() && s.wake.active && !s.wake.test) OutlinedButton(onClick={context.startActivity(Intent(context,WakeActivity::class.java))}){Text("继续起床验证")}
            }
        }
        val details=buildList {
            if(eveningLog.status!="未记录") add("旧版小行动自评：${eveningLog.status}（不作为每日完成度）")
            if(eveningLog.bed.isNotBlank()) add("自述上床：${eveningLog.bed}")
            if(morningLog.rise.isNotBlank()) add("晨间时间记录：${morningLog.rise}（验证结果以上方为准）")
            if(morningLog.energy.isNotBlank()) add("自述晨间精神：${morningLog.energy}")
            if(eveningLog.reason.isNotBlank()) add("原影响原因：${eveningLog.reason}")
            if(eveningLog.energy.isNotBlank()) add("${date} 晨间精神（旧记录）：${eveningLog.energy}")
            if(eveningLog.note.isNotBlank()) add("${date} 备注：${eveningLog.note}")
            if(morningLog.note.isNotBlank()) add("${morning} 备注：${morningLog.note}")
        }
        if(details.isNotEmpty()) { HorizontalDivider(color=Sage);SmallNote(details.joinToString("\n")) }
    }
    val stage=when(selected+1){in 1..3->"了解自己" to "先熟悉睡前记录与起床验证，观察真实感受。";in 4..7->"开始行动" to "围绕同一个睡前小行动，给一天一个稳定的收尾。";in 8..14->"重复稳定" to "继续重复，第14天回顾时只调整一件事。";else->"应对变化" to "忙碌的日子也留下一次记录，为下一轮找到节奏。"}
    Row(horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        Text("✦",color=Moss,fontSize=24.sp)
        Column(verticalArrangement=Arrangement.spacedBy(6.dp)) { Text(stage.first,fontWeight=FontWeight.Medium);SmallNote(stage.second);SmallNote("未记录不会清空进度。上床、打卡与验证均不等同于实际睡眠检测。") }
    }
    if(now.isAfter(BedtimeSchedule.morning(p.start.plusDays(20),r)) && !s.wake.active) Primary("开始下一轮 · 保留历史记录",click=onRenew)
}
