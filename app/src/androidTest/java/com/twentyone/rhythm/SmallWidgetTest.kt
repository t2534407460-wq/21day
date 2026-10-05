package com.twentyone.rhythm

import android.appwidget.*
import android.content.*
import android.os.Bundle
import android.widget.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.*
import androidx.test.uiautomator.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate

class SmallWidgetTest {
    private val ins=InstrumentationRegistry.getInstrumentation();private val c=ins.targetContext
    private val device=UiDevice.getInstance(ins);private val s=Store(c);private val hs=HabitStore(c)
    private val manager=AppWidgetManager.getInstance(c);private val host=AppWidgetHost(c,903);private val ids=mutableListOf<Int>()
    private val day=LocalDate.now()
    private lateinit var count:Habit;private lateinit var timer:Habit
    @Before fun setup() {
        device.wakeUp();device.executeShellCommand("wm dismiss-keyguard");device.pressHome();s.prefs.edit().clear().commit()
        ReviewStore(c).settings.edit().putBoolean("weekly",false).putBoolean("cycle",false).commit()
        count=Habit(name="控烟",mode=HabitMode.AT_MOST,unit="支",rules=listOf(HabitRule(day,target=10)),input=HabitInput.COUNT)
        timer=Habit(name="阅读",mode=HabitMode.AT_LEAST,unit="分钟",rules=listOf(HabitRule(day,target=20)),input=HabitInput.TIMER)
        hs.save(count);hs.save(timer);ins.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
    }
    @After fun cleanup() { ins.runOnMainSync { host.stopListening();ids.forEach { host.deleteAppWidgetId(it) } };ins.uiAutomation.dropShellPermissionIdentity();device.pressHome();s.prefs.edit().clear().commit() }
    private fun bind(type:Class<*>,h:Habit=count):Int {
        val id=host.allocateAppWidgetId();ids+=id
        assertTrue(manager.bindAppWidgetIdIfAllowed(id,ComponentName(c,type),Bundle().apply { putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,160);putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,if(type==DataWidget::class.java) 60 else 160) }))
        HabitCards.save(c,WidgetCards.key(id),setOf(h.id));RhythmWidget.refresh(c);return id
    }
    private fun command(id:Int,phase:String,nonce:String="")=WidgetCards.read(c,id).getJSONObject(0).let { SmallWidgets.command(c,id,it.getString("id"),it.getString("state"),phase,nonce) }
    private fun receive(intent:Intent) { ins.runOnMainSync { WidgetCheckReceiver().onReceive(c,intent) } }
    private fun confirm(id:Int):Intent {
        receive(command(id,"prepare"));val arm=JSONObject(s.prefs.getString("widget_arm_$id",null)!!)
        return command(id,"confirm",arm.getString("nonce"))
    }
    @Test fun confirmIsRequiredAndOldPendingIntentsCannotDoubleCount() {
        val id=bind(CheckWidget::class.java);val start=command(id,"prepare")
        receive(start);assertTrue(hs.entries().isEmpty());val arm=s.prefs.getString("widget_arm_$id",null)
        receive(start);assertEquals(arm,s.prefs.getString("widget_arm_$id",null))
        val done=command(id,"confirm",JSONObject(arm!!).getString("nonce"));receive(done);receive(done);receive(start)
        assertEquals(1,hs.entries().single().value);assertFalse(s.prefs.contains("widget_arm_$id"))
        receive(confirm(id));assertEquals(2,hs.entries().single().value)
    }
    @Test fun cancellationExpiryAndChangedSelectionNeverWrite() {
        val id=bind(HyperOsCheckWidget::class.java);val ready=confirm(id)
        receive(Intent(ready).setAction("cancel"));receive(ready);assertTrue(hs.entries().isEmpty())
        val stale=confirm(id);val key="widget_arm_$id";val arm=JSONObject(s.prefs.getString(key,null)!!).put("at",System.currentTimeMillis()-16000)
        s.prefs.edit().putString(key,arm.toString()).commit();receive(stale);assertTrue(hs.entries().isEmpty())
        val previous=confirm(id);HabitCards.save(c,WidgetCards.key(id),setOf(timer.id));receive(previous)
        assertTrue(hs.entries().isEmpty());assertEquals(0L,hs.running(timer.id))
    }
    @Test fun timerAndDailyZeroUseExistingStoreRules() {
        val id=bind(HyperOsCheckWidget::class.java,timer);receive(confirm(id));assertTrue(hs.running(timer.id)>0)
        val stop=confirm(id);receive(stop);receive(stop);assertEquals(0L,hs.running(timer.id));assertEquals(1,hs.entries().single().sessions.size)
        val quit=count.copy(id=java.util.UUID.randomUUID().toString(),name="戒烟",input=HabitInput.DAILY,rules=listOf(HabitRule(day,target=0)))
        hs.save(quit);HabitCards.save(c,WidgetCards.key(id),setOf(quit.id));val zero=confirm(id);receive(zero);receive(zero)
        assertEquals(0,hs.entries().single { it.habitId==quit.id }.value);assertEquals(2,hs.entries().size)
    }
    @Test fun otherSurfaceEditsAndFutureDaysInvalidateConfirmations() {
        val id=bind(CheckWidget::class.java);val stale=confirm(id);hs.count(count.id);receive(stale);assertEquals(1,hs.entries().single().value)
        val future=count.copy(id=java.util.UUID.randomUUID().toString(),start=day.plusDays(1),rules=listOf(HabitRule(day.plusDays(1),target=10)))
        hs.save(future);HabitCards.save(c,WidgetCards.key(id),setOf(future.id));receive(command(id,"prepare"));assertFalse(s.prefs.contains("widget_arm_$id"));assertEquals(1,hs.entries().size)
        val data=bind(DataWidget::class.java);receive(command(data,"prepare"));assertFalse(s.prefs.contains("widget_arm_$data"))
    }
    @Test fun nativeIdRemapPreservesSelectionButDropsPendingConfirmation() {
        val old=bind(HyperOsCheckWidget::class.java,timer);confirm(old);val fresh=bind(HyperOsCheckWidget::class.java,count)
        receive(Intent(c,WidgetCheckReceiver::class.java).setAction("remap").putExtra("old",intArrayOf(old)).putExtra("new",intArrayOf(fresh)))
        assertEquals(timer.id,SmallWidgets.selected(c,fresh)?.id);assertFalse(s.prefs.contains("widget_arm_$fresh"));assertEquals(0L,hs.running(timer.id))
    }
    @Test fun realSmallWidgetsConfirmInlineAndDataOpensWithoutWriting() {
        val check=bind(HyperOsCheckWidget::class.java);val data=bind(DataWidget::class.java)
        c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("今日打卡")),8000))
        val height=InstrumentationRegistry.getArguments().getString("smallHeight")?.toInt() ?: 176
        val dataHeight=InstrumentationRegistry.getArguments().getString("smallDataHeight")?.toInt() ?: 64
        ins.runOnMainSync {
            val a=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first();val root=LinearLayout(a);root.orientation=LinearLayout.VERTICAL
            val density=c.resources.displayMetrics.density;root.setPadding(24,110,24,0)
            listOf(check to height,data to dataHeight).forEach { (id,h) ->
                val v=host.createView(a,id,manager.getAppWidgetInfo(id));root.addView(v,LinearLayout.LayoutParams((180*density).toInt(),(h*density).toInt()).apply { bottomMargin=30 })
                // Like a launcher, report the content space after the host's default padding.
                v.updateAppWidgetSize(Bundle(),180,h,180,h);SmallWidgets.render(c,id)
            }
            a.setContentView(root);host.startListening()
        }
        assertTrue(device.wait(Until.hasObject(By.text("+1 支")),8000));device.takeScreenshot(java.io.File(c.filesDir,"small-widgets-ready.png"))
        device.findObject(By.text("+1 支")).click();assertTrue(device.wait(Until.hasObject(By.text("确认+1 支")),5000));assertTrue(hs.entries().isEmpty())
        Thread.sleep(150);device.takeScreenshot(java.io.File(c.filesDir,"small-widgets-confirm.png"));device.findObject(By.text("确认+1 支")).click()
        assertTrue(device.wait(Until.hasObject(By.text("1 支")),5000));assertEquals(1,hs.entries().single().value);assertFalse(device.hasObject(By.text("打卡抽屉")))
        Thread.sleep(150);device.takeScreenshot(java.io.File(c.filesDir,"small-widgets-recorded.png"))
        val values=device.findObjects(By.textStartsWith("1 支"));assertEquals(2,values.size);values.last().click()
        assertTrue(device.wait(Until.hasObject(By.text("打卡抽屉")),7000));assertEquals(1,hs.entries().single().value)
    }
    @Test fun visualKindSurvivesRenameBackupAndLegacyDecode() {
        val changed=timer.copy(name="睡前翻书");hs.save(changed);val stored=hs.habits().single { it.id==timer.id };assertEquals(HabitVisual.READING,stored.visual)
        val backup=HabitStore.encode(hs.habits(),hs.entries());assertEquals(HabitVisual.READING,HabitStore.decode(backup).first.single { it.id==timer.id }.visual)
        val legacy=HabitStore.encode(listOf(timer),emptyList());legacy.getJSONArray("plans").getJSONObject(0).remove("visual")
        assertEquals(HabitVisual.READING,HabitStore.decode(legacy).first.single().visual)
    }
    @Test fun smallConfigurationChoosesOneHabitAndDrawerReturnsToSameEditor() {
        val id=bind(CheckWidget::class.java)
        c.startActivity(Intent(c,WidgetDrawerActivity::class.java).setAction("configure").putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("2×2 打卡按钮")),8000));device.findObject(By.text("阅读")).click()
        val until=System.currentTimeMillis()+5000
        while(SmallWidgets.selected(c,id)?.id!=timer.id && System.currentTimeMillis()<until) Thread.sleep(50)
        assertEquals(setOf(timer.id),s.prefs.getStringSet("cards_${WidgetCards.key(id)}",null));device.findObject(By.text("完成设置")).click()
        c.startActivity(Intent(c,WidgetDrawerActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).putExtra("habit_id",timer.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("打卡抽屉")),8000));device.findObject(By.text("选择习惯")).click()
        assertTrue(device.wait(Until.hasObject(By.text("2×2 打卡按钮")),5000));assertFalse(device.hasObject(By.text("保存卡组")))
        assertTrue(hs.entries().isEmpty());device.findObject(By.text("完成设置")).click()
    }
}
