package com.twentyone.rhythm

import android.appwidget.*
import android.content.*
import android.os.Bundle
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.*
import androidx.test.uiautomator.*
import org.junit.*
import org.junit.Assert.*
import java.time.*

class WidgetCardTest {
    private val instrument=InstrumentationRegistry.getInstrumentation();private val c=instrument.targetContext
    private val s=Store(c);private val hs=HabitStore(c);private val today=LocalDate.now()
    private val manager=AppWidgetManager.getInstance(c);private val host=AppWidgetHost(c,902)
    private val device=UiDevice.getInstance(instrument);private val ids=mutableListOf<Int>()
    private val widgetHeight=InstrumentationRegistry.getArguments().getString("widgetHeight")?.toInt() ?: 300
    private lateinit var timer:Habit;private lateinit var count:Habit
    @Before fun setup() {
        device.wakeUp();device.executeShellCommand("wm dismiss-keyguard");device.pressHome();s.prefs.edit().clear().commit()
        ReviewStore(c).settings.edit().putBoolean("weekly",false).putBoolean("cycle",false).commit()
        timer=Habit(name="阅读",mode=HabitMode.AT_LEAST,unit="分钟",rules=listOf(HabitRule(today,target=20)),input=HabitInput.TIMER)
        count=Habit(name="喝水",mode=HabitMode.AT_LEAST,unit="杯",rules=listOf(HabitRule(today,target=8)),input=HabitInput.COUNT)
        hs.save(timer);hs.save(count)
        instrument.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
    }
    @After fun cleanup() {
        instrument.runOnMainSync { host.stopListening();ids.forEach { host.deleteAppWidgetId(it) } }
        instrument.uiAutomation.dropShellPermissionIdentity();device.pressHome();s.prefs.edit().clear().commit();RhythmWidget.refresh(c)
    }
    private fun bind(native:Boolean=false):Int {
        val id=host.allocateAppWidgetId();ids+=id
        assertTrue("Test host binding",manager.bindAppWidgetIdIfAllowed(id,ComponentName(c,if(native) HyperOsWidget::class.java else RhythmWidget::class.java),Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,340);putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,widgetHeight)
        }))
        return id
    }
    @Test fun instancesSelectDifferentHabitsAndDoNotChangeRecords() {
        val one=bind();val two=bind(true)
        HabitCards.save(c,WidgetCards.key(one),setOf(timer.id));HabitCards.save(c,WidgetCards.key(two),setOf(count.id))
        assertEquals(timer.id,WidgetCards.read(c,one).getJSONObject(0).getString("id"));assertEquals(count.id,WidgetCards.read(c,two).getJSONObject(0).getString("id"))
        hs.count(count.id);RhythmWidget.refresh(c)
        assertTrue(WidgetCards.read(c,two).getJSONObject(0).getString("value").contains("1 杯"))
        assertTrue(WidgetCards.read(c,two,today.plusDays(1).atTime(12,0)).getJSONObject(0).getString("value").contains("尚未记录"))
        HabitCards.save(c,WidgetCards.key(two),emptySet());assertEquals("empty",WidgetCards.read(c,two).getJSONObject(0).getString("id"));assertEquals(1,hs.entries().single().value)
    }
    @Test fun nativeProviderFactoryReadsFreshSnapshotAndHolidayDate() {
        val id=bind(true);val day=LocalDate.parse("2026-10-03");s.createPlan(Plan(day));s.rules=Rules(bed=0,wake=510,weekends=true,weekendBed=120,weekendWake=600)
        RhythmWidget.refresh(c)
        val card=WidgetCards.read(c,id,day.plusDays(1).atTime(2,5)).getJSONObject(0)
        assertEquals("sleep",card.getString("id"));assertTrue(card.getString("detail").contains("10.04 02:00"));assertTrue(card.getString("detail").contains("10.04 10:00"))
        val f=CardWidgetService.Factory(c,id);f.onCreate();assertTrue(f.getCount()>=2);hs.count(count.id);RhythmWidget.refresh(c);f.onDataSetChanged();assertNotNull(f.getViewAt(0))
        assertTrue(manager.getAppWidgetOptions(id).getString("miuiEditUri")!!.endsWith("/$id"))
    }
    @Test fun drawerSelectionAndTypeFiltersPersistWithoutChangingHabits() {
        c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("今日打卡")),8000));device.findObject(By.text("打卡抽屉")).click()
        device.wait(Until.findObject(By.text("选择习惯")),5000).click()
        device.wait(Until.findObject(By.text("喝水")),5000).click();device.findObject(By.text("保存卡组")).click()
        assertEquals(listOf(timer.id),HabitCards.selected(c,"today").map { it.id });assertEquals(2,hs.habits().size)
        device.findObject(By.text("次数打卡")).click();assertTrue(device.wait(Until.hasObject(By.text("这个分区还没有卡片")),5000))
        device.findObject(By.text("区间打卡")).click();assertTrue(device.wait(Until.hasObject(By.desc("长按开始")),5000));assertTrue(hs.entries().isEmpty())
        device.takeScreenshot(java.io.File(c.filesDir,"today-drawer-filter.png"))
        device.findObject(By.text("收起")).click();device.wait(Until.findObject(By.text("打卡抽屉")),5000).click()
        assertTrue(device.wait(Until.hasObject(By.text("1 / 1")),5000))
    }
    @Test fun realStackViewRendersTurnsAndOpensDrawerWithoutCounting() {
        val id=bind(true);RhythmWidget.refresh(c)
        lateinit var widgetView:AppWidgetHostView
        c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("今日打卡")),8000))
        instrument.runOnMainSync {
            val activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            val root=FrameLayout(activity);val view=host.createView(activity,id,manager.getAppWidgetInfo(id));widgetView=view
            val density=c.resources.displayMetrics.density
            root.setPadding(20,80,20,0);root.addView(view,FrameLayout.LayoutParams(-1,((widgetHeight+60)*density).toInt()));activity.setContentView(root);host.startListening()
        }
        assertTrue(device.wait(Until.hasObject(By.text("阅读")),10000));Thread.sleep(1800)
        device.takeScreenshot(java.io.File(c.filesDir,"widget-stack.png"))
        val card=device.findObject(By.text("阅读")).visibleBounds
        device.swipe(device.displayWidth/2,card.centerY(),device.displayWidth/2,card.centerY()+350,35)
        Thread.sleep(700);device.waitForIdle();instrument.uiAutomation.clearCache()
        device.takeScreenshot(java.io.File(c.filesDir,"widget-after-turn.png"))
        instrument.runOnMainSync {
            val activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            val stack=widgetView.findViewById<android.widget.StackView>(R.id.widget_stack)
            assertEquals("Vertical swipe must change the front card",1,stack.displayedChild)
        }
        assertTrue(device.wait(Until.hasObject(By.text("喝水")),5000));assertTrue(hs.entries().isEmpty())
        device.findObject(By.text("喝水")).click()
        assertTrue(device.wait(Until.hasObject(By.text("打卡抽屉")),7000));assertTrue(hs.entries().isEmpty())
        instrument.runOnMainSync {
            val activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            assertEquals("Widget click must carry the selected habit",count.id,activity.intent.getStringExtra("habit_id"))
        }
        val button=device.wait(Until.findObject(By.desc("长按 +1 杯")),5000)
        if(button==null) { device.takeScreenshot(java.io.File(c.filesDir,"widget-drawer-failure.png"));device.dumpWindowHierarchy(java.io.File(c.filesDir,"widget-drawer-failure.xml")) }
        assertNotNull(button)
        val b=button!!.visibleBounds;device.swipe(b.centerX(),b.centerY(),b.centerX(),b.centerY(),200)
        assertEquals(1,hs.entries().single().value)
        device.takeScreenshot(java.io.File(c.filesDir,"widget-drawer.png"))
    }

    // RemoteViews rows are root namespaces, so findViewById on their wrapper skips them.
    private fun cardText(view:android.view.View?,id:Int):android.widget.TextView? {
        if(view is android.widget.TextView && view.id==id) return view
        if(view is android.view.ViewGroup) for(i in 0 until view.childCount) cardText(view.getChildAt(i),id)?.let { return it }
        return null
    }
    @Test fun swipedCardsRemainVisibleAfterReturningFromDrawer() {
        s.prefs.edit().remove("habits").commit();hs.save(timer)
        count=count.copy(name="戒烟",unit="支",mode=HabitMode.AT_MOST)
        hs.save(count)
        val id=bind(InstrumentationRegistry.getArguments().getString("widgetProvider")!="legacy");RhythmWidget.refresh(c)
        lateinit var widgetView:AppWidgetHostView
        c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("今日打卡")),8000))
        instrument.runOnMainSync {
            val activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            val root=FrameLayout(activity);widgetView=host.createView(activity,id,manager.getAppWidgetInfo(id))
            root.setPadding(20,80,20,0)
            root.addView(widgetView,FrameLayout.LayoutParams(-1,((widgetHeight+60)*c.resources.displayMetrics.density).toInt()))
            activity.setContentView(root);host.startListening()
        }
        assertTrue(device.wait(Until.hasObject(By.text("阅读")),10000));Thread.sleep(1500)
        device.takeScreenshot(java.io.File(c.filesDir,"widget-return-initial.png"))
        // Exercise both ends of the stack, including a swipe beyond the first/last card.
        for((round,direction) in listOf(-1,-1,1,1,1,-1,-1).withIndex()) {
            lateinit var name:String
            lateinit var bounds:android.graphics.Rect
            instrument.runOnMainSync {
                val stack=widgetView.findViewById<android.widget.StackView>(R.id.widget_stack)
                val current=cardText(stack.currentView,R.id.card_detail)!!
                bounds=android.graphics.Rect();current.getGlobalVisibleRect(bounds)
            }
            device.swipe(device.displayWidth/2,bounds.centerY(),device.displayWidth/2,bounds.centerY()+direction*350,35)
            Thread.sleep(700);device.waitForIdle();instrument.uiAutomation.clearCache()
            instrument.runOnMainSync {
                val stack=widgetView.findViewById<android.widget.StackView>(R.id.widget_stack)
                name=cardText(stack.currentView,R.id.card_detail)!!.text.toString()
                android.util.Log.i("WidgetReturnTest","round=$round child=${stack.displayedChild} count=${stack.count} name=$name")
            }
            device.wait(Until.findObject(By.text(name)),5000).click()
            assertTrue(device.wait(Until.hasObject(By.text("打卡抽屉")),7000))
            if(round==0) assertTrue(hs.entries().isEmpty())
            if(round==1 || round==3) {
                // The desktop host can stop listening while another activity covers it.
                instrument.runOnMainSync { host.stopListening() }
                val button=device.wait(Until.findObject(By.desc(if(name=="戒烟") "长按 +1 支" else "长按开始")),5000)!!
                val b=button.visibleBounds;device.swipe(b.centerX(),b.centerY(),b.centerX(),b.centerY(),200)
            }
            if(round%2==0) device.pressBack() else device.findObject(By.text("收起")).click()
            instrument.runOnMainSync { host.startListening() }
            assertTrue(device.wait(Until.gone(By.text("打卡抽屉")),5000))
            // Exposure refresh happens on HyperOS when returning to the desktop.
            HyperOsWidget().onReceive(c,Intent("miui.appwidget.action.APPWIDGET_UPDATE").putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS,intArrayOf(id)))
            Thread.sleep(1800);device.waitForIdle();instrument.uiAutomation.clearCache()
            device.takeScreenshot(java.io.File(c.filesDir,"widget-return-$round.png"))
            instrument.runOnMainSync {
                val stack=widgetView.findViewById<android.widget.StackView>(R.id.widget_stack)
                assertEquals(2,stack.count)
                val detail=cardText(stack.currentView,R.id.card_detail)
                assertNotNull("Returning on round $round leaves a blank current card (child=${stack.displayedChild}, count=${stack.count})",detail)
                assertEquals("Returning keeps the selected habit",name,detail!!.text.toString())
                if(round==1) assertTrue(cardText(stack.currentView,R.id.card_value)!!.text.contains("开始计时"))
                if(round==3) assertTrue(cardText(stack.currentView,R.id.card_value)!!.text.contains("1 支"))
            }
            assertTrue("Returned card is visible and accessible",device.hasObject(By.text(name)))
        }
        assertEquals(1,hs.entries().single { it.habitId==count.id }.value)
        assertTrue(hs.running(timer.id)>0)
        // Removing the front card must clamp its position, and an empty selection keeps an entry point.
        instrument.runOnMainSync { widgetView.findViewById<android.widget.StackView>(R.id.widget_stack).displayedChild=1 }
        Thread.sleep(700)
        HabitCards.save(c,WidgetCards.key(id),setOf(timer.id));Thread.sleep(1800)
        instrument.runOnMainSync {
            val stack=widgetView.findViewById<android.widget.StackView>(R.id.widget_stack)
            assertEquals(1,stack.count);assertEquals("阅读",cardText(stack.currentView,R.id.card_detail)!!.text.toString())
        }
        HabitCards.save(c,WidgetCards.key(id),emptySet());Thread.sleep(1800)
        assertTrue(device.wait(Until.hasObject(By.text("把习惯放进来")),5000))
        assertEquals(1,hs.entries().single { it.habitId==count.id }.value)
        assertTrue(hs.running(timer.id)>0)
    }
}
