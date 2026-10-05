package com.twentyone.rhythm

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.*

/** Decorative only: animation completion never changes a habit record. */
@Composable fun HabitAnimation(h:Habit,feedback:Int,running:Boolean,press:Float,compact:Boolean) {
    val phase=remember(h.id) { Animatable(0f) };val lifecycle=LocalLifecycleOwner.current.lifecycle
    val reduced=!ValueAnimator.areAnimatorsEnabled()
    LaunchedEffect(h.id,feedback,running,reduced,lifecycle) {
        phase.snapTo(0f)
        if(!reduced && (running || feedback>0)) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if(running) phase.animateTo(1f,infiniteRepeatable(tween(2200,easing=LinearEasing)))
            else { phase.animateTo(1f,tween(1600,easing=LinearEasing));phase.snapTo(0f) }
        }
    }
    val moving=!reduced && (press>0 || phase.value>0)
    HabitIllustration(h.visual,if(press>0 && !reduced) press*.3f else phase.value,moving,h.smoking && h.input==HabitInput.DAILY,
        Modifier.size(if(compact) 88.dp else 126.dp,if(compact) 44.dp else 66.dp))
}

@Composable fun HabitIllustration(kind:HabitVisual,phase:Float,moving:Boolean,abstinent:Boolean,modifier:Modifier) {
    Canvas(modifier) {
        scale(size.width/160f,size.height/84f,Offset.Zero) {
            val dark=Color(0xFF294A3A);val light=Color(0xFFFAF8EB);val accent=Color(0xFFB66B42)
            val motion=if(moving) sin(phase*2*PI).toFloat() else 0f
            fun line(x:Float,y:Float,u:Float,v:Float,color:Color=dark,width:Float=3f)=drawLine(color,Offset(x,y),Offset(u,v),width,StrokeCap.Round)
            fun page(x:Float,y:Float,w:Float,h:Float) { drawRoundRect(light,Offset(x,y),Size(w,h),androidx.compose.ui.geometry.CornerRadius(3f));drawRoundRect(dark,Offset(x,y),Size(w,h),androidx.compose.ui.geometry.CornerRadius(3f),style=Stroke(2f)) }
            when(kind) {
                HabitVisual.SMOKING -> {
                    page(36f,49f,90f,13f)
                    drawRoundRect(accent,Offset(99f,50f),Size(26f,11f),androidx.compose.ui.geometry.CornerRadius(2f))
                    line(99f,50f,99f,61f,dark,1.5f)
                    if(moving && !abstinent) {
                        line(36f,51f,36f,60f,Color(0xFFE57837),5f)
                        if(phase<.35f) {
                            val flame=Path().apply { moveTo(35f,55f);cubicTo(20f,45f,32f,37f,32f,28f);cubicTo(49f,41f,46f,51f,35f,55f) }
                            drawPath(flame,Color(0xFFE99B42))
                        }
                        repeat(3) { i ->
                            val x=35f+i*13;val drift=motion*7
                            val smoke=Path().apply { moveTo(x,43f);cubicTo(x-10+drift,33f,x+13+drift,23f,x-1,7f+i*3) }
                            drawPath(smoke,dark.copy(alpha=.72f-i*.13f),style=Stroke(3.5f,cap=StrokeCap.Round))
                        }
                    } else if(abstinent) {
                        drawCircle(dark,29f,Offset(80f,46f),style=Stroke(2.5f))
                        line(60f,66f,100f,26f,accent,3f)
                        if(moving) { line(116f,24f,121f,29f);line(121f,29f,132f,17f) }
                    }
                }
                HabitVisual.EXERCISE -> {
                    translate(0f,-motion*7) { rotate(motion*7,Offset(80f,42f)) {
                        page(42f,38f,76f,8f)
                        listOf(39f,105f).forEach { x ->
                            drawRoundRect(accent,Offset(x,21f),Size(16f,42f),androidx.compose.ui.geometry.CornerRadius(4f))
                            drawRoundRect(dark,Offset(x,21f),Size(16f,42f),androidx.compose.ui.geometry.CornerRadius(4f),style=Stroke(2f))
                        }
                        page(29f,29f,10f,26f);page(121f,29f,10f,26f)
                        repeat(4) { i -> line(68f+i*8,40f,68f+i*8,44f,dark.copy(alpha=.4f),1f) }
                    }
                    }
                }
                HabitVisual.READING -> {
                    line(29f,73f,131f,73f,accent,4f)
                    page(30f,23f,49f,47f);page(81f,23f,49f,47f);line(80f,24f,80f,74f)
                    repeat(4) { i -> line(39f,34f+i*8,68f,34f+i*8,dark.copy(alpha=.45f),1.5f);line(92f,34f+i*8,121f,34f+i*8,dark.copy(alpha=.45f),1.5f) }
                    if(moving) { val edge=80f+cos(phase*2*PI).toFloat()*49
                        val leaf=Path().apply { moveTo(80f,23f);quadraticTo((80f+edge)/2,7f,edge,19f);lineTo(edge,63f);quadraticTo((80f+edge)/2,58f,80f,70f);close() }
                        drawPath(leaf,Color(0xFFE8D8AF));drawPath(leaf,dark,style=Stroke(1.5f))
                    }
                }
                HabitVisual.WORK -> {
                    page(48f,11f,64f,64f);line(60f,12f,60f,74f,dark.copy(alpha=.5f),1.5f)
                    repeat(5) { i -> line(45f,20f+i*11,53f,20f+i*11,dark,2.5f) }
                    repeat(4) { i -> line(69f,25f+i*10,100f,25f+i*10,dark.copy(alpha=.3f),1.5f) }
                    val tip=90f+motion*9
                    line(tip,64f,tip+16f,34f,accent,5f);line(tip-2,68f,tip,64f,dark,2f)
                    if(moving) line(69f,65f,tip-4,65f,dark.copy(alpha=.65f),1.5f)
                }
            }
        }
    }
}
