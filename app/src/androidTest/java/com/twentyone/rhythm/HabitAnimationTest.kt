package com.twentyone.rhythm

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.*
import androidx.test.uiautomator.*
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate

class HabitAnimationTest {
    private val ins=InstrumentationRegistry.getInstrumentation();private val c=ins.targetContext;private val device=UiDevice.getInstance(ins)
    @Test fun fourScenesMoveWithoutWritingAndReducedMotionStaysStill() {
        device.wakeUp();device.executeShellCommand("wm dismiss-keyguard");device.pressHome()
        val s=Store(c);s.prefs.edit().clear().commit();ReviewStore(c).settings.edit().putBoolean("weekly",false).putBoolean("cycle",false).commit()
        val day=LocalDate.now();val habits=listOf("控烟","运动","阅读","工作笔记").map { name ->
            Habit(name=name,mode=HabitMode.AT_LEAST,unit=if(name=="控烟") "支" else "分钟",rules=listOf(HabitRule(day)),input=if(name=="控烟") HabitInput.COUNT else HabitInput.TIMER)
        }
        val original=device.executeShellCommand("settings get global animator_duration_scale").trim()
        val reduced=InstrumentationRegistry.getArguments().getString("reducedMotion")=="true"
        try {
            device.executeShellCommand("settings put global animator_duration_scale ${if(reduced) 0 else 1}")
            c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue(device.wait(Until.hasObject(By.text("今日打卡")),8000))
            var event by mutableIntStateOf(0)
            ins.runOnMainSync {
                val a=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first() as ComponentActivity
                a.setContent { RhythmTheme { Column(Modifier.fillMaxSize().background(Paper).padding(top=64.dp,start=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                    Text(if(reduced) "已关闭系统动画" else "四种习惯动作")
                    habits.forEach { h -> Row(verticalAlignment=Alignment.CenterVertically) { Text(h.name,Modifier.width(100.dp));HabitAnimation(h,event,false,0f,false) } }
                } } }
            }
            Thread.sleep(500)
            assertEquals(!reduced,android.animation.ValueAnimator.areAnimatorsEnabled())
            ins.runOnMainSync { event=1 };Thread.sleep(260)
            val first=ins.uiAutomation.takeScreenshot()!!
            Thread.sleep(400);val second=ins.uiAutomation.takeScreenshot()!!
            java.io.File(c.filesDir,if(reduced) "habit-animations-off.png" else "habit-animations-active.png").outputStream().use { second.compress(Bitmap.CompressFormat.PNG,100,it) }
            fun body(b:Bitmap)=Bitmap.createBitmap(b,0,100,b.width,b.height-240)
            assertEquals("Animation setting must control motion",reduced,body(first).sameAs(body(second)))
            assertTrue(HabitStore(c).entries().isEmpty());assertTrue(HabitStore(c).habits().isEmpty())
        } finally { device.executeShellCommand("settings put global animator_duration_scale $original");device.pressHome();s.prefs.edit().clear().commit() }
    }
}
