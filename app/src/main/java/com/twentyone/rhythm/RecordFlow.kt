package com.twentyone.rhythm

import android.view.HapticFeedbackConstants
import androidx.compose.animation.*
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
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.*

data class OrbOption(val label:String,val icon:ImageVector,val color:Color)
val ActionOptions=listOf(
    OrbOption("完成",Icons.Outlined.Done,Moss),OrbOption("部分完成",Icons.Outlined.Remove,Color(0xFF87964D)),
    OrbOption("未完成",Icons.Outlined.Close,Clay),OrbOption("未记录",Icons.Outlined.MoreHoriz,Muted)
)
private val SleepinessOptions=listOf(
    OrbOption("很困了",Icons.Outlined.Bedtime,Moss),OrbOption("有点困",Icons.Outlined.DarkMode,Color(0xFF87964D)),
    OrbOption("还不困",Icons.Outlined.WbSunny,Clay)
)
private val MoodOptions=listOf(
    OrbOption("放松",Icons.Outlined.SentimentSatisfied,Moss),OrbOption("平静",Icons.Outlined.SentimentNeutral,Color(0xFF87964D)),
    OrbOption("有压力",Icons.Outlined.SentimentDissatisfied,Clay)
)
private val ReasonOptions=listOf(
    OrbOption("无",Icons.Outlined.Done,Moss),OrbOption("工作",Icons.Outlined.WorkOutline,Muted),
    OrbOption("手机",Icons.Outlined.PhoneAndroid,Clay),OrbOption("社交",Icons.Outlined.PeopleOutline,Moss),
    OrbOption("没有困意",Icons.Outlined.DarkMode,Muted),OrbOption("其他",Icons.Outlined.MoreHoriz,Clay)
)

@Composable fun RecordOrb(options:List<OrbOption>,label:String,current:String,onSelect:(String)->Unit) {
    var expanded by remember(options) { mutableStateOf(false) }
    var pressed by remember(options) { mutableStateOf(false) }
    var hovered by remember(options) { mutableStateOf<Int?>(null) }
    val select by rememberUpdatedState(onSelect)
    val view=LocalView.current
    val expansion by animateFloatAsState(if(expanded) 1f else 0f,spring(dampingRatio=.78f,stiffness=420f),label="展开选项")
    val scale by animateFloatAsState(if(pressed && !expanded) .93f else if(expanded) .82f else 1f,spring(dampingRatio=.65f,stiffness=420f),label="圆球按压")
    val progress by animateFloatAsState(if(pressed) 1f else 0f,tween(if(pressed) 500 else 180),label="长按进度")
    val tint by animateColorAsState(hovered?.let{options[it].color} ?: Moss,tween(140),label="选中颜色")
    fun commit(index:Int) {
        expanded=false;pressed=false;hovered=null
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        select(options[index].label)
    }
    Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(316.dp),contentAlignment=Alignment.Center) {
            val radius=minOf(108.dp,(maxWidth-80.dp)/2)
            val density=LocalDensity.current
            val radiusPx=with(density){radius.toPx()}
            val centreTouch=with(density){68.dp.toPx()}
            Box(Modifier.fillMaxSize().pointerInput(options) {
                awaitEachGesture {
                    val down=awaitFirstDown(requireUnconsumed=false)
                    val centre=Offset(size.width/2f,size.height/2f)
                    if((down.position-centre).getDistance()>centreTouch) return@awaitEachGesture
                    pressed=true
                    try {
                        val hold=awaitLongPressOrCancellation(down.id)
                        if(hold!=null) {
                            expanded=true;hovered=null
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            val released=drag(hold.id) { change ->
                                val point=change.position-centre
                                val target=OrbSelection.target(point.x,point.y,radiusPx,options.size)
                                if(target!=hovered) {
                                    hovered=target
                                    if(target!=null) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                                change.consume()
                            }
                            if(released) hovered?.let { commit(it) }
                        }
                    } finally { pressed=false;hovered=null;expanded=false }
                }
            },contentAlignment=Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    if(expansion>0f) {
                        drawCircle(tint.copy(alpha=.06f*expansion.coerceIn(0f,1f)),radiusPx+22.dp.toPx())
                        options.forEachIndexed { i,_ ->
                            val angle=-PI/2+2*PI*i/options.size
                            val end=center+Offset(cos(angle).toFloat()*radiusPx,sin(angle).toFloat()*radiusPx)*expansion
                            drawLine(tint.copy(alpha=if(hovered==i) .4f else .09f),center,end,if(hovered==i) 2.dp.toPx() else 1.dp.toPx())
                        }
                    }
                }
                Box(Modifier.size(136.dp).graphicsLayer{scaleX=scale;scaleY=scale}
                    .background(Sage,CircleShape)
                    .border(1.dp,tint.copy(alpha=.18f),CircleShape)
                    .semantics {
                        contentDescription="$label 圆球"
                        stateDescription=if(expanded) "滑动选择，松手确认" else current
                        role=Role.Button
                        onClick(label="展开点选"){expanded=true;true}
                        customActions=options.mapIndexed { i,o->CustomAccessibilityAction(o.label){commit(i);true} }
                    },contentAlignment=Alignment.Center) {
                    Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Icon(hovered?.let{options[it].icon} ?: Icons.Outlined.TouchApp,null,tint=tint,modifier=Modifier.size(28.dp))
                        Text(hovered?.let{options[it].label} ?: if(expanded) "滑动选择" else "长按$label",color=Ink,fontSize=17.sp,fontWeight=FontWeight.Medium)
                        Text(if(expanded) "松手确认" else current.ifEmpty{"留一点感受"},color=Muted,fontSize=11.sp)
                    }
                    Canvas(Modifier.fillMaxSize().padding(5.dp)) {
                        if(progress>0) drawArc(tint,-90f,progress*360,false,Offset.Zero,Size(size.width,size.height),style=Stroke(2.dp.toPx(),cap=StrokeCap.Round))
                    }
                }
                if(expansion>.01f) options.forEachIndexed { i,option ->
                    val angle=-PI/2+2*PI*i/options.size
                    val selected=hovered==i
                    val optionScale by animateFloatAsState(if(selected) 1.12f else 1f,spring(dampingRatio=.7f),label="选项吸附")
                    Surface(onClick={commit(i)},enabled=expanded,shape=CircleShape,color=if(selected) option.color else Paper,
                        border=BorderStroke(1.dp,if(selected) option.color else MaterialTheme.colorScheme.outline),shadowElevation=0.dp,
                        modifier=Modifier.offset { IntOffset((cos(angle)*radiusPx*expansion).roundToInt(),(sin(angle)*radiusPx*expansion).roundToInt()) }
                            .size(70.dp).graphicsLayer{alpha=expansion.coerceIn(0f,1f);scaleX=optionScale;scaleY=optionScale}) {
                        Column(Modifier.padding(4.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                            Icon(option.icon,null,tint=if(selected) Color.White else option.color,modifier=Modifier.size(23.dp))
                            Text(option.label,fontSize=12.sp,color=if(selected) Color.White else Ink,textAlign=TextAlign.Center)
                        }
                    }
                }
            }
        }
        Text(if(expanded) "滑回球心取消 · 松手确认" else "长按展开，滑向选项后松手",fontSize=12.sp,color=Muted)
        TextButton(onClick={expanded=!expanded;hovered=null}){Text(if(expanded) "收起选项" else "也可以点选",fontSize=12.sp)}
    }
}

@Composable fun RecordFlow(initial:DayLog,onDismiss:()->Unit,onChange:((DayLog)->DayLog)->Unit,onComplete:()->Unit) {
    var step by rememberSaveable(initial.day) { mutableIntStateOf(if(BedtimeSchedule.checked(initial)) 3 else 0) }
    var details by rememberSaveable { mutableStateOf(false) }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(color=Paper,modifier=Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    Eyebrow(initial.day.replace("-"," / ")+" 晚间")
                    IconButton(onClick=onDismiss){Icon(Icons.Outlined.Close,"关闭记录")}
                }
                Spacer(Modifier.height(24.dp))
                AnimatedContent(targetState=step,transitionSpec={
                    (fadeIn(tween(220))+slideInVertically(tween(260)){it/12}) togetherWith (fadeOut(tween(120))+slideOutVertically(tween(180)){-it/12})
                },label="连续记录") { page ->
                    Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
                        Eyebrow(if(page<3) "${page+1} / 3  ·  睡前，听听自己的状态" else "确认这晚的睡前记录")
                        Spacer(Modifier.height(16.dp))
                        Text(listOf("现在，有困意了吗？","睡前，心情怎么样？","有什么影响了作息？","今晚的睡前记录")[page],fontSize=27.sp,fontWeight=FontWeight.SemiBold,textAlign=TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Text(when(page){0->"准备睡觉时，留下一点真实感受。";1->"不用评价好坏，选贴近此刻的状态。";2->"没有特别的影响，也可以选择「无」。";else->"确认完成后，这一晚才算完成睡前打卡。"},color=Muted,textAlign=TextAlign.Center,fontSize=14.sp)
                        if(page<3) {
                            val options=when(page){0->SleepinessOptions;1->MoodOptions;else->ReasonOptions}
                            RecordOrb(options,"选择",when(page){0->initial.sleepiness;1->initial.bedMood;else->initial.bedReason}) { value ->
                                onChange { log -> when(page){0->log.copy(sleepiness=value);1->log.copy(bedMood=value);else->log.copy(bedReason=value)} }
                                step=page+1
                            }
                            if(page>0) TextButton(onClick={step=page-1}){Text("上一步")}
                        } else {
                            Spacer(Modifier.height(32.dp))
                            Box(Modifier.size(90.dp).background(Sage,CircleShape),contentAlignment=Alignment.Center){Icon(Icons.Outlined.DarkMode,"睡前状态已记录",tint=Moss,modifier=Modifier.size(42.dp))}
                            Spacer(Modifier.height(28.dp))
                            Sheet {
                                SummaryLine("困意",initial.sleepiness)
                                SummaryLine("心情",initial.bedMood)
                                SummaryLine("影响原因",initial.bedReason)
                                if(initial.bed.isNotEmpty()) SummaryLine("上床",initial.bed)
                                SmallNote("睡前小行动 · 可选回顾")
                                ActionOptions.chunked(2).forEach { row -> Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    row.forEach { option -> FilterChip(colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Sage,selectedLabelColor=Ink),selected=initial.status==option.label,onClick={onChange{it.copy(status=option.label)}},label={Text(option.label)}) }
                                } }
                                if(initial.note.isNotBlank()) SmallNote(initial.note)
                                if(BedtimeSchedule.checked(initial)) SmallNote("已完成 · ${stamp(initial.bedtimeCheckedAt)}")
                            }
                            Spacer(Modifier.height(20.dp))
                            Primary(if(BedtimeSchedule.checked(initial)) "保存并关闭" else "完成睡前打卡",enabled=BedtimeSchedule.answered(initial),click=onComplete)
                            TextButton(onClick={details=true}){Text("补充作息时间 / 一句话")}
                            TextButton(onClick={step=0}){Text("重新选择")}
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                SmallNote("每一步即时保存；三项回答后确认完成，才算睡前打卡。")
            }
        }
    }
    if(details) LogDetails(initial,onDismiss={details=false},onChange=onChange)
}

@Composable private fun SummaryLine(label:String,value:String) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(label,color=Muted);Text(value,fontWeight=FontWeight.Medium)}
}

@Composable private fun LogDetails(initial:DayLog,onDismiss:()->Unit,onChange:((DayLog)->DayLog)->Unit) {
    val original=remember { initial }
    var bed by rememberSaveable { mutableStateOf(initial.bed) }
    var note by rememberSaveable { mutableStateOf(initial.note) }
    AlertDialog(onDismissRequest=onDismiss,title={Text("补充一点细节")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        TimeInput("上床准备休息",bed,allowEmpty=true){bed=it}
        SmallNote("可以留空。上床时间不代表真正入睡；醒后完成验证会自动记录。")
        OutlinedTextField(note,{note=it.take(300)},label={Text("留一句话")})
    }},confirmButton={TextButton(onClick={
        onChange { latest -> latest.copy(bed=if(bed!=original.bed) bed else latest.bed,note=if(note!=original.note) note else latest.note) }
        onDismiss()
    }){Text("保存补充")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}
