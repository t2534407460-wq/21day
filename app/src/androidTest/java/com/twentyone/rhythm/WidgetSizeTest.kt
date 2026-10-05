package com.twentyone.rhythm

import android.appwidget.*
import android.content.*
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.*
import androidx.test.uiautomator.*
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate
import kotlin.math.abs

class WidgetSizeTest {
    private val ins=InstrumentationRegistry.getInstrumentation();private val c=ins.targetContext
    private val device=UiDevice.getInstance(ins);private val manager=AppWidgetManager.getInstance(c);private val host=AppWidgetHost(c,904)
    private val ids=mutableListOf<Int>();private val views=mutableMapOf<Int,AppWidgetHostView>()
    private lateinit var h:Habit
    @Before fun setup() {
        device.wakeUp();device.executeShellCommand("wm dismiss-keyguard");device.pressHome();Store(c).prefs.edit().clear().commit()
        ReviewStore(c).settings.edit().putBoolean("weekly",false).putBoolean("cycle",false).commit()
        h=Habit(name="戒烟",mode=HabitMode.AT_MOST,unit="支",input=HabitInput.COUNT,rules=listOf(HabitRule(LocalDate.now(),target=10)))
        HabitStore(c).save(h);repeat(4) { HabitStore(c).count(h.id) }
        ins.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("今日打卡")),8000))
    }
    @After fun cleanup() { device.pressHome();ins.runOnMainSync { host.stopListening();ids.forEach { host.deleteAppWidgetId(it) } };ins.uiAutomation.dropShellPermissionIdentity();Store(c).prefs.edit().clear().commit() }
    private fun bind(type:Class<*>):Int {
        val id=host.allocateAppWidgetId();ids+=id;assertTrue(manager.bindAppWidgetIdIfAllowed(id,ComponentName(c,type)))
        HabitCards.save(c,WidgetCards.key(id),setOf(h.id));return id
    }
    private fun show(check:Int,data:Int,legacy:Boolean=false) {
        ins.runOnMainSync {
            val a=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            val root=LinearLayout(a);root.orientation=LinearLayout.VERTICAL;root.setPadding(30,100,30,0)
            val density=c.resources.displayMetrics.density
            listOf(check to SizeF(180f,204f),data to SizeF(180f,100f)).forEach { (id,size) ->
                val v=host.createView(a,id,manager.getAppWidgetInfo(id));views[id]=v
                root.addView(v,LinearLayout.LayoutParams((size.width*density).toInt(),(size.height*density).toInt()).apply { bottomMargin=30 })
                if(legacy) v.updateAppWidgetSize(Bundle(),180,100,size.width.toInt(),size.height.toInt())
                else v.updateAppWidgetSize(Bundle(),listOf(size,SizeF(250f,140f)))
            }
            a.setContentView(root);host.startListening();RhythmWidget.refresh(c)
        }
        assertTrue(device.wait(Until.hasObject(By.text("+1 支")),7000));Thread.sleep(400)
    }
    private fun bounds(id:Int):Pair<Int,Int> {
        var result=0 to 0
        ins.runOnMainSync { val card=views.getValue(id).findViewById<View>(android.R.id.background);assertNotNull(card);result=card.width to card.height }
        return result
    }
    @Test fun tallLauncherSlotsRenderSquareAndSlimCardsAndStillCheckIn() {
        val check=bind(HyperOsCheckWidget::class.java);val data=bind(DataWidget::class.java);show(check,data)
        val before=bounds(check);val slim=bounds(data)
        device.takeScreenshot(java.io.File(c.filesDir,"widget-proportions.png"))
        assertTrue("2x2 must be square, actual=$before",abs(before.first-before.second)<=1)
        assertEquals("2x1 ratio",.38,slim.second.toDouble()/slim.first,.01)
        ins.runOnMainSync { assertEquals(View.VISIBLE,views.getValue(check).findViewById<View>(R.id.small_hint).visibility) }
        device.findObject(By.text("+1 支")).click();assertTrue(device.wait(Until.hasObject(By.text("确认+1 支")),5000))
        Thread.sleep(150);assertEquals(before,bounds(check));device.takeScreenshot(java.io.File(c.filesDir,"widget-proportions-confirm.png"))
        device.findObject(By.text("确认+1 支")).click();assertTrue(device.wait(Until.hasObject(By.text("5 支")),5000));assertEquals(5,HabitStore(c).entries().single().value)
        assertEquals(before,bounds(check));assertEquals(slim,bounds(data));assertFalse(device.hasObject(By.text("打卡抽屉")))
        val density=c.resources.displayMetrics.density
        ins.runOnMainSync {
            val view=views.getValue(check);view.layoutParams=view.layoutParams.apply { width=(250*density).toInt();height=(140*density).toInt() }
        }
        Thread.sleep(600);val wide=bounds(check)
        assertTrue("Responsive alternate must also be square: $wide",abs(wide.first-wide.second)<=1)
        assertTrue("Square must fit the shorter side",wide.first<=140*density)
        ins.runOnMainSync {
            val view=views.getValue(check);view.layoutParams=view.layoutParams.apply { width=(180*density).toInt();height=(204*density).toInt() }
        }
        Thread.sleep(600);assertEquals(before,bounds(check))
        ins.runOnMainSync { assertEquals("Resizing back restores the confirmation hint",View.VISIBLE,views.getValue(check).findViewById<View>(R.id.small_hint).visibility) }
        ins.runOnMainSync {
            val view=views.getValue(data);view.updateAppWidgetSize(Bundle(),listOf(SizeF(180f,40f),SizeF(180f,100f)))
            view.layoutParams=view.layoutParams.apply { height=(40*density).toInt() }
        }
        Thread.sleep(600)
        ins.runOnMainSync { assertEquals(View.GONE,views.getValue(data).findViewById<View>(R.id.small_title).visibility) }
        ins.runOnMainSync { val view=views.getValue(data);view.layoutParams=view.layoutParams.apply { height=(100*density).toInt() } }
        Thread.sleep(600);assertEquals(slim,bounds(data))
        ins.runOnMainSync { assertEquals("Resizing back restores the habit title",View.VISIBLE,views.getValue(data).findViewById<View>(R.id.small_title).visibility) }
    }
    @Test fun launcherWithoutSizeListKeepsSquareAndEditEntry() {
        val check=bind(CheckWidget::class.java);val data=bind(DataWidget::class.java);show(check,data,true)
        val b=bounds(check);assertTrue("Range-only launcher must stay square: $b",abs(b.first-b.second)<=1)
        device.findObject(By.desc("选择打卡按钮习惯")).click();assertTrue(device.wait(Until.hasObject(By.text("2×2 打卡按钮")),5000))
        device.findObject(By.text("完成设置")).click();assertEquals(4,HabitStore(c).entries().single().value)
    }
}
