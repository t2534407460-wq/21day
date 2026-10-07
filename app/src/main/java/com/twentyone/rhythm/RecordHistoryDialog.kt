package com.twentyone.rhythm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Composable fun RecordHistoryDialog(records:List<HistoryRecord>,store:Store,today:LocalDate,initialFrom:String,initialThrough:String,onDismiss:()->Unit,onReports:()->Unit,onRange:(String,String)->Unit) {
    var from by rememberSaveable { mutableStateOf(initialFrom) }
    var through by rememberSaveable { mutableStateOf(initialThrough) }
    var error by remember { mutableStateOf("") }
    var range by remember { mutableStateOf(false) }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Column(Modifier.fillMaxSize().background(Paper).safeDrawingPadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("历史记录",fontWeight=FontWeight.SemiBold)
                TextButton(onClick=onDismiss) { Text("关闭") }
            }
            Row {
                TextButton(onClick={range=!range}) { Text("选择聊天日期范围") }
                TextButton(onClick=onReports) { Text("历史总结") }
            }
            if(range) {
                DateInput("开始日期",from){from=it}
                DateInput("结束日期",through){through=it}
                Primary("按此范围聊天") {
                    runCatching {
                        val start=LocalDate.parse(from);val end=LocalDate.parse(through)
                        require(start<=end && end<=today) { "请选择不晚于今天的有效日期范围。" }
                        require(ChronoUnit.DAYS.between(start,end)<366) { "一次最多回顾366天，请缩小日期范围。" }
                        onRange(from,through)
                    }.onFailure { error=it.message ?: "请检查日期。" }
                }
                if(error.isNotEmpty()) SmallNote(error)
            }
            SmallNote("以下为全部已保存历史。日总量显示当前结果，操作记录保留过程；旧版未保存的逐次操作无法补造。")
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                if(records.isEmpty()) item { Text("还没有历史记录。完成作息或习惯打卡后会显示在这里。") }
                items(records.groupBy { it.day }.entries.toList()) { (day,rows) ->
                    Sheet {
                        Text(day.toString(),fontWeight=FontWeight.SemiBold)
                        store.plan?.let { plan -> if(plan.contains(day)) SmallNote("这一晚 → ${day.plusDays(1)} 早 · ${store.nightStatus(day)}") }
                        rows.forEach { row ->
                            Text(row.kind,fontWeight=FontWeight.Medium)
                            SmallNote((if(row.time>0) RecordHistory.time(row.time)+" · " else "")+row.detail)
                        }
                    }
                }
            }
        }
    }
}
