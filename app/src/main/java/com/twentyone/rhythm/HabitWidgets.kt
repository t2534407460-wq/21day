package com.twentyone.rhythm

import android.app.PendingIntent
import android.appwidget.*
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.util.AtomicFile
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter

/** Only the app process writes this atomic display snapshot. The Xiaomi widget process never reads cached preferences. */
object WidgetCards {
    private fun file(c:Context)=AtomicFile(File(c.filesDir,"widget-cards.json"))
    fun key(id:Int)="widget_$id"
    fun providers(c:Context)=listOf(RhythmWidget::class.java,HyperOsWidget::class.java,DataWidget::class.java,CheckWidget::class.java,HyperOsCheckWidget::class.java).map { ComponentName(c,it) }
    fun owned(c:Context,id:Int)=AppWidgetManager.getInstance(c).getAppWidgetInfo(id)?.provider in providers(c)
    @Synchronized fun publish(c:Context) {
        val now=LocalDateTime.now();val s=Store(c);val hs=HabitStore(c);val entries=hs.entries()
        val root=JSONObject();val manager=AppWidgetManager.getInstance(c)
        providers(c).flatMap { manager.getAppWidgetIds(it).toList() }.forEach { id ->
            val cards=JSONArray()
            val small=SmallWidgets.isSmall(c,id)
            if(!small && s.prefs.getBoolean("widget_schedule_$id",true)) s.plan?.let { p ->
                cards.put(JSONObject().put("id","sleep").put("plan",JSONObject().put("start",p.start)).put("rules",Store.encodeRules(s.rules))
                    .put("pendingAt",s.pendingAt).put("pendingRules",Store.encodeRules(s.editableRules))
                    .put("checked",JSONArray(s.logs().filter { BedtimeSchedule.checked(it) }.map { it.day })).put("title","作息")
                    .put("detail","作息计划").put("value","").put("action","打开睡前打卡"))
            }
            (if(small) SmallWidgets.selected(c,id)?.let { listOf(it) } ?: emptyList() else HabitCards.selected(c,key(id))).forEach { h ->
                val entry=entries.find { it.habitId==h.id && it.date==now.toLocalDate() };val start=hs.running(h.id)
                val value=when { start>0->"${Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))} 开始计时";entry!=null->"${entry.value} ${h.unit}";!h.scheduled(now.toLocalDate())->"今天未安排";else->"尚未记录" }
                cards.put(JSONObject().put("id",h.id).put("habit",HabitStore.encode(listOf(h),entries.filter { it.habitId==h.id }.map { it.copy(note="") })).put("timer",start).put("title",HabitCards.group(h)).put("detail",h.name).put("value","$value · ${h.goal(now.toLocalDate())}")
                    .put("state",SmallWidgets.state(h,entries,start,now.toLocalDate())).put("arm",s.prefs.getString("widget_arm_$id",null)?.let { JSONObject(it) })
                    .put("action",if(start>0) "打开抽屉 · 长按结束" else "打开抽屉 · 长按打卡"))
            }
            if(cards.length()==0) cards.put(JSONObject().put("id","empty").put("title","我的打卡卡组").put("detail","把习惯放进来").put("value","区间计时 · 次数打卡 · 每日确认").put("action","选择习惯"))
            root.put(id.toString(),cards)
        }
        val atomic=file(c);val out=atomic.startWrite()
        try { out.write(root.toString().toByteArray(Charsets.UTF_8));atomic.finishWrite(out) } catch(e:Exception) { atomic.failWrite(out);throw e }
    }
    fun read(c:Context,id:Int,now:LocalDateTime=LocalDateTime.now()):JSONArray {
        val raw=runCatching { file(c).openRead().bufferedReader().use { JSONObject(it.readText()).optJSONArray(id.toString()) } }.getOrNull() ?: JSONArray()
        val result=JSONArray()
        for(i in 0 until raw.length()) {
            val card=raw.getJSONObject(i)
            if(card.has("plan")) {
                val p=Plan(LocalDate.parse(card.getJSONObject("plan").getString("start")))
                val r=Store.decodeRules(card.getJSONObject(if(card.optLong("pendingAt") in 1..now.epoch()) "pendingRules" else "rules"))
                val day=BedtimeSchedule.recordDay(p,r,now) ?: continue
                val checked=card.getJSONArray("checked").let { a -> (0 until a.length()).any { a.getString(it)==day.toString() } }
                val rest=RestCalendar.isRest(day.plusDays(1))
                card.put("title","作息 · ${r.bedtimeSource(day)}").put("detail","${BedtimeSchedule.bedtime(day,r).format(DateTimeFormatter.ofPattern("MM.dd HH:mm"))} 睡前\n${BedtimeSchedule.morning(day,r).format(DateTimeFormatter.ofPattern("MM.dd HH:mm"))} ${if(rest) "参考起床" else "起床"}")
                    .put("value",if(checked) "睡前已打卡" else "到点提醒 · 未打卡锁屏一次")
                if(rest) card.put("goal","自然醒 · 不响起床闹钟")
            } else if(card.has("habit")) {
                val (all,entries)=HabitStore.decode(card.getJSONObject("habit"));val h=all.single();val start=card.optLong("timer")
                if(h.end<now.toLocalDate() && start==0L) continue
                val entry=entries.find { it.date==now.toLocalDate() }
                val value=when { start>0->"${Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))} 开始计时";entry!=null->"${entry.value} ${h.unit}";!h.scheduled(now.toLocalDate())->"今天未安排";else->"尚未记录" }
                card.put("value",value).put("goal",h.goal(now.toLocalDate()))
            }
            result.put(card)
        }
        if(result.length()==0) result.put(JSONObject().put("id","empty").put("title","廿一 · 打卡卡组").put("detail","选择你的习惯").put("value","打开后配置这张桌面卡组").put("action","选择习惯"))
        return result
    }
    fun render(c:Context,id:Int) {
        if(SmallWidgets.isSmall(c,id)) { SmallWidgets.render(c,id);return }
        val manager=AppWidgetManager.getInstance(c)
        val options=manager.getAppWidgetOptions(id)
        val compact=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) in 1..209
        if(compact) {
            val views=RemoteViews(c.packageName,R.layout.widget_compact)
            val open=Intent(c,WidgetDrawerActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).setData(Uri.parse("rhythm-widget://open/$id"))
            views.setOnClickPendingIntent(R.id.widget_open,PendingIntent.getActivity(c,id,open,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            manager.updateAppWidget(id,views)
            return
        }
        val views=RemoteViews(c.packageName,R.layout.widget)
        val service=Intent(c,CardWidgetService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).apply { data=Uri.parse("rhythm-widget://cards/$id") }
        views.setRemoteAdapter(R.id.widget_stack,service)
        val open=Intent(c,WidgetDrawerActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).apply { data=Uri.parse("rhythm-widget://open/$id") }
        views.setPendingIntentTemplate(R.id.widget_stack,PendingIntent.getActivity(c,id,open,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
        val edit=Intent(c,WidgetDrawerActivity::class.java).setAction("configure").putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).apply { data=Uri.parse("rhythm-widget://edit/$id") }
        views.setOnClickPendingIntent(R.id.widget_edit,PendingIntent.getActivity(c,id,edit,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        val editUri="rhythm-widget://edit/$id"
        if(options.getString("miuiEditUri")!=editUri) { options.putString("miuiEditUri",editUri);manager.updateAppWidgetOptions(id,options) }
        manager.updateAppWidget(id,views)
        manager.notifyAppWidgetViewDataChanged(id,R.id.widget_stack)
    }
}

open class RhythmWidget:AppWidgetProvider() {
    override fun onUpdate(c:Context,manager:AppWidgetManager,ids:IntArray) { ids.filter { WidgetCards.owned(c,it) }.forEach { WidgetCards.render(c,it) } }
    override fun onAppWidgetOptionsChanged(c:Context,manager:AppWidgetManager,id:Int,options:Bundle) {
        if(options.getBoolean("miuiIdChanged") && !options.getBoolean("miuiIdChangedComplete")) {
            c.sendBroadcast(Intent(c,WidgetCheckReceiver::class.java).setAction("remap").putExtra("old",options.getIntArray("miuiOldIds")).putExtra("new",options.getIntArray("miuiNewIds")))
        } else WidgetCards.render(c,id)
    }
    override fun onRestored(c:Context,oldIds:IntArray,newIds:IntArray) {
        c.sendBroadcast(Intent(c,WidgetCheckReceiver::class.java).setAction("remap").putExtra("old",oldIds).putExtra("new",newIds))
    }
    override fun onReceive(c:Context,intent:Intent) {
        if(intent.action=="miui.appwidget.action.APPWIDGET_UPDATE") {
            val manager=AppWidgetManager.getInstance(c)
            (intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS) ?: manager.getAppWidgetIds(ComponentName(c,javaClass))).filter { WidgetCards.owned(c,it) }.forEach { WidgetCards.render(c,it) }
        } else super.onReceive(c,intent)
    }
    override fun onDeleted(c:Context,ids:IntArray) {
        c.sendBroadcast(Intent(c,WidgetCheckReceiver::class.java).setAction("deleted").putExtra("old",ids))
    }
    companion object {
        fun refresh(c:Context) {
            WidgetCards.publish(c)
            val manager=AppWidgetManager.getInstance(c)
            WidgetCards.providers(c).flatMap { manager.getAppWidgetIds(it).toList() }.forEach { WidgetCards.render(c,it) }
        }
    }
}
class HyperOsWidget:RhythmWidget()

class CardWidgetService:RemoteViewsService() {
    override fun onGetViewFactory(intent:Intent):RemoteViewsFactory=Factory(applicationContext,intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,-1))
    class Factory(private val c:Context,private val id:Int):RemoteViewsFactory {
        private var cards=JSONArray()
        override fun onCreate() { onDataSetChanged() }
        override fun onDataSetChanged() { cards=WidgetCards.read(c,id) }
        override fun onDestroy() {}
        override fun getCount()=cards.length()
        override fun getViewAt(position:Int):RemoteViews? {
            val card=cards.optJSONObject(position) ?: return null
            return RemoteViews(c.packageName,R.layout.widget_card).apply {
                val options=AppWidgetManager.getInstance(c).getAppWidgetOptions(id);val density=c.resources.displayMetrics.density
                setInt(R.id.card_root,"setMinimumWidth",((options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,300)-40).coerceAtLeast(220)*density).toInt())
                setInt(R.id.card_root,"setMinimumHeight",((options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,250)-80).coerceAtLeast(150)*density).toInt())
                val alternate=position%2==1
                val ink=c.getColor(if(alternate) R.color.widget_ink_alt else R.color.widget_ink)
                val muted=c.getColor(if(alternate) R.color.widget_muted_alt else R.color.widget_muted)
                setInt(R.id.card_root,"setBackgroundResource",if(alternate) R.drawable.widget_card_alt_background else R.drawable.widget_card_background)
                setInt(R.id.card_divider,"setBackgroundColor",c.getColor(if(alternate) R.color.widget_edge_alt else R.color.widget_edge))
                listOf(R.id.card_detail,R.id.card_value,R.id.card_action).forEach { setTextColor(it,ink) }
                listOf(R.id.card_title,R.id.card_index,R.id.card_goal).forEach { setTextColor(it,muted) }
                setTextViewText(R.id.card_title,card.optString("title"));setTextViewText(R.id.card_index,"${position+1} / ${cards.length()}")
                val small=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,300)<290
                val value=card.optString("value")
                setTextViewTextSize(R.id.card_detail,android.util.TypedValue.COMPLEX_UNIT_SP,if(small) 18f else 22f)
                setTextViewTextSize(R.id.card_value,android.util.TypedValue.COMPLEX_UNIT_SP,if(small || value.length>8) 13f else if(value.firstOrNull()?.isDigit()==true) 28f else 16f)
                setViewVisibility(R.id.card_goal,if(small || !card.has("goal")) android.view.View.GONE else android.view.View.VISIBLE)
                setInt(R.id.card_value,"setMaxLines",if(small) 2 else 1)
                setTextViewText(R.id.card_detail,card.optString("detail"));setTextViewText(R.id.card_value,if(small && card.has("goal")) "$value · ${card.optString("goal")}" else value);setTextViewText(R.id.card_goal,card.optString("goal"))
                setTextViewText(R.id.card_action,when(card.optString("id")) { "sleep"->"打开睡前打卡  →";"empty"->"选择我的习惯  →";else->"打开抽屉 · 长按打卡  →" })
                setOnClickFillInIntent(R.id.card_root,Intent().putExtra("habit_id",card.optString("id")))
            }
        }
        override fun getLoadingView():RemoteViews?=null
        override fun getViewTypeCount()=1
        override fun getItemId(position:Int)=cards.optJSONObject(position)?.optString("id")?.hashCode()?.toLong() ?: position.toLong()
        override fun hasStableIds()=false
    }
}
