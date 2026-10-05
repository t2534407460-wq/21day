package com.twentyone.rhythm

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.*
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

class DataWidget:RhythmWidget()
class CheckWidget:RhythmWidget()
class HyperOsCheckWidget:RhythmWidget()

object SmallWidgets {
    private fun provider(c:Context,id:Int)=AppWidgetManager.getInstance(c).getAppWidgetInfo(id)?.provider?.className
    fun isData(c:Context,id:Int)=provider(c,id)==DataWidget::class.java.name
    fun isSmall(c:Context,id:Int)=provider(c,id) in setOf(DataWidget::class.java.name,CheckWidget::class.java.name,HyperOsCheckWidget::class.java.name)
    fun selected(c:Context,id:Int):Habit? {
        val ids=Store(c).prefs.getStringSet("cards_${WidgetCards.key(id)}",emptySet()) ?: emptySet()
        return HabitCards.available(c).firstOrNull { it.id in ids }
    }
    fun state(h:Habit,entries:List<HabitEntry>,timer:Long,day:LocalDate):String {
        val data=HabitStore.encode(listOf(h),entries.filter { it.habitId==h.id && it.date==day }).toString()+"/$timer/$day"
        return MessageDigest.getInstance("SHA-256").digest(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun action(h:Habit,entry:HabitEntry?,timer:Long,day:LocalDate)=when {
        timer>0->"结束计时"
        !h.scheduled(day) || h.archived->"今天未安排"
        h.input==HabitInput.MANUAL->"填写记录"
        h.input==HabitInput.DAILY && entry!=null->"今日已记录"
        h.input==HabitInput.TIMER->"开始计时"
        h.input==HabitInput.COUNT->"+1 ${h.unit}"
        h.smoking->"确认零支"
        else->"记录完成"
    }
    private fun canAct(h:Habit,entry:HabitEntry?,timer:Long,day:LocalDate)=!h.archived && h.input!=HabitInput.MANUAL &&
        (timer>0 || (h.scheduled(day) && !(h.input==HabitInput.DAILY && entry!=null)))
    fun command(c:Context,id:Int,habit:String,state:String,phase:String,nonce:String=""):Intent = Intent(c,WidgetCheckReceiver::class.java)
        .setAction(phase).setData(Uri.parse("rhythm-widget://check/$id/$phase/$state/$nonce"))
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).putExtra("habit_id",habit).putExtra("state",state).putExtra("nonce",nonce)
    fun render(c:Context,id:Int) {
        val manager=AppWidgetManager.getInstance(c);val data=isData(c,id);val card=WidgetCards.read(c,id).getJSONObject(0)
        val options=manager.getAppWidgetOptions(id)
        val uri="rhythm-widget://edit/$id"
        if(options.getString("miuiEditUri")!=uri) { options.putString("miuiEditUri",uri);manager.updateAppWidgetOptions(id,options) }
        // Cell counts do not imply square pixels. Use the host's available content sizes.
        val sizes=if(Build.VERSION.SDK_INT>=31) options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)?.filter { it.width>0 && it.height>0 }?.distinct()?.take(16) else null
        val views=if(!sizes.isNullOrEmpty() && Build.VERSION.SDK_INT>=31) {
            RemoteViews(sizes.associateWith { size -> sizedView(c,id,data,card,size) })
        } else {
            val minWidth=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,160).coerceAtLeast(1)
            val minHeight=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,if(data) 64 else 160).coerceAtLeast(1)
            val maxWidth=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,minWidth).coerceAtLeast(minWidth)
            val maxHeight=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,minHeight).coerceAtLeast(minHeight)
            RemoteViews(sizedView(c,id,data,card,SizeF(maxWidth.toFloat(),minHeight.toFloat())),sizedView(c,id,data,card,SizeF(minWidth.toFloat(),maxHeight.toFloat())))
        }
        manager.updateAppWidget(id,views)
    }
    private fun sizedView(c:Context,id:Int,data:Boolean,card:JSONObject,slot:SizeF):RemoteViews {
        val views=RemoteViews(c.packageName,if(data) R.layout.widget_data else R.layout.widget_check)
        val density=c.resources.displayMetrics.density
        // Hosts reapply variants to the same view tree. Reset properties before compact overrides.
        views.setViewPadding(android.R.id.background,((if(data) 12 else 10)*density).toInt(),((if(data) 4 else 10)*density).toInt(),((if(data) 12 else 10)*density).toInt(),((if(data) 4 else 10)*density).toInt())
        views.setViewVisibility(R.id.small_title,View.VISIBLE)
        views.setTextViewTextSize(R.id.small_edit,TypedValue.COMPLEX_UNIT_SP,if(data) 20f else 18f)
        if(data) {
            views.setTextViewTextSize(R.id.small_title,TypedValue.COMPLEX_UNIT_SP,11f)
            views.setTextViewTextSize(R.id.small_value,TypedValue.COMPLEX_UNIT_SP,16f)
        } else {
            views.setViewVisibility(R.id.small_hint,View.VISIBLE)
            views.setInt(R.id.small_edit,"setHeight",(28*density).toInt())
            views.setTextViewTextSize(R.id.small_action,TypedValue.COMPLEX_UNIT_SP,14f)
            views.setInt(R.id.small_action,"setBackgroundResource",R.drawable.widget_button_background)
            views.setOnClickPendingIntent(R.id.small_hint,null)
        }
        // Screenshot reference: the system's 2x1 card is about 136 high / 354 wide.
        val width=if(data) slot.width else minOf(slot.width,slot.height)
        val height=if(data) minOf(slot.height,slot.width*.38f) else width
        if(Build.VERSION.SDK_INT>=31) {
            views.setViewLayoutWidth(android.R.id.background,width,TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutHeight(android.R.id.background,height,TypedValue.COMPLEX_UNIT_DIP)
        }
        val item=card.optString("id");val empty=item=="empty";val now=System.currentTimeMillis();val day=LocalDate.now()
        val tight=height<150
        val edit=Intent(c,WidgetDrawerActivity::class.java).setAction("configure").setData(Uri.parse("rhythm-widget://edit/$id")).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id)
        val open=Intent(c,WidgetDrawerActivity::class.java).setData(Uri.parse("rhythm-widget://open/$id")).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).putExtra("habit_id",item)
        fun launch(intent:Intent)=PendingIntent.getActivity(c,id,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.small_edit,launch(edit));views.setOnClickPendingIntent(R.id.small_title,launch(if(empty) edit else open))
        views.setOnClickPendingIntent(R.id.small_value,launch(if(empty) edit else open))
        if(data) views.setOnClickPendingIntent(android.R.id.background,launch(if(empty) edit else open))
        views.setTextViewText(R.id.small_title,if(empty) "选择一个习惯" else card.optString("detail"))
        views.setTextViewText(R.id.small_value,if(empty) "点编辑开始" else card.optString("value"))
        if(data && card.optLong("timer")>0) views.setTextViewText(R.id.small_value,"计时中")
        views.setContentDescription(R.id.small_value,if(empty) "选择习惯后显示数据" else "${card.optString("detail")}，${card.optString("value")}，${card.optString("goal")}")
        if(data) {
            if(height<=64 || c.resources.configuration.fontScale>1.15f) {
                views.setTextViewTextSize(R.id.small_title,android.util.TypedValue.COMPLEX_UNIT_SP,10f)
                views.setTextViewTextSize(R.id.small_value,android.util.TypedValue.COMPLEX_UNIT_SP,14f)
            }
            if(height<56) {
                val dp=c.resources.displayMetrics.density
                views.setViewPadding(android.R.id.background,(10*dp).toInt(),(2*dp).toInt(),(10*dp).toInt(),(2*dp).toInt())
                views.setViewVisibility(R.id.small_title,View.GONE)
                views.setTextViewTextSize(R.id.small_value,android.util.TypedValue.COMPLEX_UNIT_SP,12f)
                views.setTextViewTextSize(R.id.small_edit,android.util.TypedValue.COMPLEX_UNIT_SP,14f)
                views.setTextViewText(R.id.small_value,if(empty) "选择习惯" else "${if(card.optLong("timer")>0) "计时中" else card.optString("value")} · ${card.optString("detail")}")
            }
        }
        if(!data) {
            if(height<125) {
                val dp=c.resources.displayMetrics.density
                views.setViewPadding(android.R.id.background,(8*dp).toInt(),(6*dp).toInt(),(8*dp).toInt(),(6*dp).toInt())
                views.setInt(R.id.small_edit,"setHeight",(22*dp).toInt())
                views.setTextViewTextSize(R.id.small_action,android.util.TypedValue.COMPLEX_UNIT_SP,12f)
                views.setViewVisibility(R.id.small_hint,View.GONE)
            }
            views.setTextViewTextSize(R.id.small_title,android.util.TypedValue.COMPLEX_UNIT_SP,if(tight) 12f else 14f)
            views.setTextViewTextSize(R.id.small_value,android.util.TypedValue.COMPLEX_UNIT_SP,if(tight) 13f else 18f)
            views.setTextViewText(R.id.small_group,if(empty) "我的打卡按钮" else card.optString("title"))
            views.setViewVisibility(R.id.small_group,if(tight) View.GONE else View.VISIBLE)
            views.setTextViewText(R.id.small_hint,"点按准备 · 再次确认")
            if(empty) { views.setTextViewText(R.id.small_action,"选择习惯");views.setOnClickPendingIntent(R.id.small_action,launch(edit)) }
            else {
                val (all,entries)=HabitStore.decode(card.getJSONObject("habit"));val h=all.single();val entry=entries.find { it.date==day };val timer=card.optLong("timer")
                val arm=card.optJSONObject("arm")?.takeIf { it.optString("state")==card.optString("state") && now-it.optLong("at") in 0..15000 }
                val available=canAct(h,entry,timer,day)
                val text=action(h,entry,timer,day)
                views.setTextViewText(R.id.small_action,if(arm!=null && available) "确认$text" else text)
                views.setContentDescription(R.id.small_action,if(arm!=null && available) "确认${h.name}：$text" else "${h.name}：$text")
                if(available) {
                    val intent=command(c,id,h.id,card.getString("state"),if(arm==null) "prepare" else "confirm",arm?.optString("nonce") ?: "")
                    views.setOnClickPendingIntent(R.id.small_action,PendingIntent.getBroadcast(c,id,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                } else views.setOnClickPendingIntent(R.id.small_action,launch(open))
                if(arm!=null && available) {
                    views.setTextViewText(R.id.small_hint,"取消 · 15秒内确认")
                    views.setOnClickPendingIntent(R.id.small_hint,PendingIntent.getBroadcast(c,id,command(c,id,h.id,card.getString("state"),"cancel",arm.getString("nonce")),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                }
                views.setInt(R.id.small_action,"setBackgroundResource",if(arm!=null && available) R.drawable.widget_confirm_background else R.drawable.widget_button_background)
            }
        }
        return views
    }
    // Main-process receiver is the only writer; the widget process uses the atomic snapshot.
    fun receive(c:Context,intent:Intent) {
        val id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,-1)
        if(!isSmall(c,id) || isData(c,id)) return
        val h=selected(c,id) ?: return
        if(h.id!=intent.getStringExtra("habit_id")) return
        val store=HabitStore(c);val prefs=Store(c).prefs;val key="widget_arm_$id";val day=LocalDate.now();val now=System.currentTimeMillis()
        val entries=store.entries();val timer=store.running(h.id);val entry=entries.find { it.habitId==h.id && it.date==day }
        val state=state(h,entries,timer,day)
        val arm=prefs.getString(key,null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if(state!=intent.getStringExtra("state") || !canAct(h,entry,timer,day)) { prefs.edit().remove(key).commit();RhythmWidget.refresh(c);return }
        when(intent.action) {
            "prepare" -> {
                if(arm!=null && arm.optString("state")==state && now-arm.optLong("at") in 0..15000) return
                val nonce=UUID.randomUUID().toString()
                prefs.edit().putString(key,JSONObject().put("state",state).put("nonce",nonce).put("at",now).toString()).commit()
                Handler(Looper.getMainLooper()).postDelayed({
                    if(prefs.getString(key,null)?.let { JSONObject(it).optString("nonce") }==nonce) { prefs.edit().remove(key).apply();RhythmWidget.refresh(c) }
                },15050)
            }
            "confirm", "cancel" -> {
                if(arm==null || arm.optString("nonce")!=intent.getStringExtra("nonce")) return
                prefs.edit().remove(key).commit()
                if(intent.action=="confirm" && arm.optString("state")==state && now-arm.optLong("at") in 0..15000) {
                    runCatching {
                        when(h.input) { HabitInput.TIMER->if(timer>0) store.finishTimer(h.id) else store.startTimer(h.id);HabitInput.COUNT->store.count(h.id);HabitInput.DAILY->store.confirmDay(h.id);HabitInput.MANUAL->Unit }
                        HabitReminders.cancelNotice(c,h.id);HabitReminders.schedule(c)
                    }.onFailure { Toast.makeText(c,it.message ?: "记录未保存，请重试",Toast.LENGTH_SHORT).show() }
                }
            }
            else->return
        }
        RhythmWidget.refresh(c)
    }
}

class WidgetCheckReceiver:BroadcastReceiver() {
    override fun onReceive(c:Context,intent:Intent) {
        if(intent.action !in setOf("remap","deleted")) { SmallWidgets.receive(c,intent);return }
        val old=intent.getIntArrayExtra("old") ?: return
        val prefs=Store(c).prefs;val edit=prefs.edit()
        if(intent.action=="remap") {
            val fresh=intent.getIntArrayExtra("new") ?: return
            if(old.size!=fresh.size || fresh.any { !WidgetCards.owned(c,it) }) return
            old.zip(fresh).forEach { (from,to) ->
                prefs.getStringSet("cards_${WidgetCards.key(from)}",null)?.let { edit.putStringSet("cards_${WidgetCards.key(to)}",it.toSet()) }
                if(prefs.contains("widget_schedule_$from")) edit.putBoolean("widget_schedule_$to",prefs.getBoolean("widget_schedule_$from",true))
                edit.remove("widget_arm_$to")
            }
            edit.commit()
            val manager=AppWidgetManager.getInstance(c)
            fresh.forEach { id -> val options=manager.getAppWidgetOptions(id);if(options.getBoolean("miuiIdChanged")) { options.putBoolean("miuiIdChangedComplete",true);manager.updateAppWidgetOptions(id,options) } }
        } else {
            old.forEach { edit.remove("cards_${WidgetCards.key(it)}").remove("widget_schedule_$it").remove("widget_arm_$it") };edit.commit()
        }
        RhythmWidget.refresh(c)
    }
}
