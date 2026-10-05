package com.twentyone.rhythm

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay

@Composable fun EmergencyExitDialog(a:WakeActivity,onDismiss:()->Unit) {
    val gate=remember { EmergencyExitGate(SystemClock.elapsedRealtime()) }
    var elapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val close by rememberUpdatedState(onDismiss)
    val view=LocalView.current
    val wait=gate.waitRemaining(elapsed)
    val held=gate.heldMillis(elapsed)
    DisposableEffect(a) {
        val observer=LifecycleEventObserver { _,event ->
            if(event==Lifecycle.Event.ON_PAUSE) { gate.cancelHold();close() }
        }
        a.lifecycle.addObserver(observer)
        onDispose { a.lifecycle.removeObserver(observer);gate.cancelHold() }
    }
    LaunchedEffect(gate) {
        while(true) {
            elapsed=SystemClock.elapsedRealtime()
            if(gate.canExit(elapsed)) {
                if(Alarms.emergencyStop(a,gate)) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                close();break
            }
            delay(50)
        }
    }
    AlertDialog(onDismissRequest={gate.cancelHold();close()},title={Text("应急退出")},text={Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text("用于身体不适或验证点无法使用。退出只记录为未验证，不会记为已经起床。")
        Text(if(wait>0) "还需等待 ${(wait+999)/1000} 秒" else "现在持续按住下方按钮 5 秒")
        LinearProgressIndicator(progress={if(wait>0) 1f-wait/60_000f else held/5_000f},modifier=Modifier.fillMaxWidth(),color=Clay)
        SmallNote("松手或移出按钮会清空长按进度；关闭此窗口或切到后台后，需重新等待。响铃与后续验证仍按原规则执行。")
    }},confirmButton={
        Box(Modifier.fillMaxWidth().height(64.dp).background(if(wait>0) Sage else Clay,RoundedCornerShape(18.dp))
            .semantics { contentDescription="持续长按 5 秒确认退出";role=Role.Button;if(wait>0)disabled() }
            .pointerInput(gate,wait==0L) {
                awaitEachGesture {
                    val down=awaitFirstDown()
                    if(!gate.beginHold(SystemClock.elapsedRealtime())) return@awaitEachGesture
                    down.consume();view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    try {
                        var pressing=true
                        while(pressing) {
                            val event=awaitPointerEvent()
                            val finger=event.changes.firstOrNull{it.id==down.id}
                            pressing=finger!=null && finger.pressed && !finger.isConsumed &&
                                finger.position.x in 0f..size.width.toFloat() && finger.position.y in 0f..size.height.toFloat() &&
                                event.changes.none{it.id!=down.id && it.pressed}
                            if(pressing) finger?.consume()
                        }
                    } finally { gate.cancelHold() }
                }
            },contentAlignment=Alignment.Center) {
            Text(if(wait>0) "等待后才能长按" else if(held>0) "继续按住 ${((5_000-held+999)/1000).coerceAtLeast(1)} 秒" else "持续长按 5 秒退出",color=if(wait>0) Muted else Paper)
        }
    },dismissButton={TextButton(onClick={gate.cancelHold();close()}){Text("返回起床验证")}})
}
