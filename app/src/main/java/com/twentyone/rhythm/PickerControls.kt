package com.twentyone.rhythm

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.*

@Composable private fun PickerField(label:String,value:String,calendar:Boolean=false,onClick:()->Unit) {
    Surface(onClick=onClick,shape=RoundedCornerShape(18.dp),color=Sage.copy(alpha=.65f),modifier=Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Icon(if(calendar) Icons.Outlined.CalendarMonth else Icons.Outlined.Schedule,null,tint=Moss)
            Column(Modifier.weight(1f)) { SmallNote(label);Text(value,fontSize=21.sp,fontWeight=FontWeight.Medium) }
            Icon(Icons.Outlined.ExpandMore,null,tint=Muted)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TimeInput(label:String,value:String,allowEmpty:Boolean=false,onValue:(String)->Unit) {
    var open by remember { mutableStateOf(false) }
    PickerField(label,value.ifEmpty { "选择时间" }){open=true}
    if(open) {
        val minutes=parseTime(value) ?: (LocalTime.now().hour*60+LocalTime.now().minute)
        val state=rememberTimePickerState(initialHour=minutes/60,initialMinute=minutes%60,is24Hour=true)
        Dialog(onDismissRequest={open=false},properties=DialogProperties(usePlatformDefaultWidth=false)) {
            Surface(shape=RoundedCornerShape(28.dp),color=Paper,modifier=Modifier.widthIn(max=400.dp).padding(16.dp)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Text(label,fontWeight=FontWeight.SemiBold)
                    TimePicker(state=state)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                        if(allowEmpty) TextButton(onClick={onValue("");open=false}){Text("清除")}
                        TextButton(onClick={open=false}){Text("取消")}
                        TextButton(onClick={onValue(timeText(state.hour*60+state.minute));open=false}){Text("确定时间")}
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DateInput(label:String,value:String,minDate:LocalDate?=null,onValue:(String)->Unit) {
    var open by remember { mutableStateOf(false) }
    PickerField(label,value.replace("-"," / "),calendar=true){open=true}
    if(open) {
        val initial=runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())
        val selectable=remember(minDate) { object:SelectableDates {
            override fun isSelectableDate(utcTimeMillis:Long)=minDate==null || !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isBefore(minDate)
            override fun isSelectableYear(year:Int)=minDate==null || year>=minDate.year
        } }
        val state=rememberDatePickerState(initialSelectedDateMillis=initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),selectableDates=selectable)
        DatePickerDialog(onDismissRequest={open=false},confirmButton={
            TextButton(enabled=state.selectedDateMillis!=null,onClick={
                state.selectedDateMillis?.let { onValue(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) };open=false
            }){Text("确定日期")}
        },dismissButton={TextButton(onClick={open=false}){Text("取消")}}) {
            DatePicker(state=state,showModeToggle=false,title={Text(label,Modifier.padding(start=24.dp,top=20.dp))})
        }
    }
}

@Composable fun DurationPicker(label:String,value:Int,range:IntRange,unit:String,onValue:(Int)->Unit) {
    Column {
        Text(label,fontWeight=FontWeight.Medium)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            OutlinedIconButton(onClick={onValue(value-1)},enabled=value>range.first){Icon(Icons.Outlined.Remove,"减少$label")}
            Text("$value $unit",fontSize=23.sp,color=Moss)
            OutlinedIconButton(onClick={onValue(value+1)},enabled=value<range.last){Icon(Icons.Outlined.Add,"增加$label")}
        }
        Slider(value=value.toFloat(),onValueChange={onValue(it.toInt().coerceIn(range))},valueRange=range.first.toFloat()..range.last.toFloat(),steps=range.last-range.first-1)
    }
}
