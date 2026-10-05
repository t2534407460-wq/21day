package com.twentyone.rhythm

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import java.time.*

object HabitReminders {
    const val CHANNEL="habit_reminders"
    private fun pending(c:Context,at:Long)=PendingIntent.getBroadcast(c,210,Intent(c,HabitReminderReceiver::class.java).putExtra("at",at),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun schedule(c:Context) {
        ReviewScheduler.schedule(c)
        val s=Store(c);val store=HabitStore(c);val all=store.habits();val entries=store.entries();val now=LocalDateTime.now()
        val sent=s.prefs.getStringSet("habit_reminded",emptySet())!!.toSet()
        val old=s.prefs.getLong("habit_alarm_at",0)
        val oldTime=Instant.ofEpochMilli(old).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val preserve=old>0 && oldTime.toLocalDate()==now.toLocalDate() && !oldTime.isAfter(now) && due(all,entries,sent,oldTime).isNotEmpty()
        val next=if(preserve) old else HabitAnalysis.nextReminder(all,entries,sent,now)?.epoch()
        val am=c.getSystemService(AlarmManager::class.java)
        am.cancel(pending(c,0))
        s.prefs.edit().putLong("habit_alarm_at",next ?: 0).apply()
        if(next==null) return
        val at=maxOf(next,System.currentTimeMillis()+1500)
        try {
            if(Alarms.exactAllowed(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pending(c,next))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pending(c,next))
        } catch(_:SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pending(c,next)) }
    }
    private fun due(all:List<Habit>,entries:List<HabitEntry>,sent:Set<String>,time:LocalDateTime)=all.filter { h ->
        val date=time.toLocalDate()
        !h.archived && h.scheduled(date) && h.rule(date).reminder==time.hour*60+time.minute &&
            entries.none { it.habitId==h.id && it.date==date } && HabitAnalysis.key(h.id,date) !in sent
    }
    fun deliver(c:Context,at:Long,now:LocalDateTime=LocalDateTime.now()) {
        if(at<=0 || at>now.epoch()) return
        val dateTime=Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDateTime()
        if(dateTime.toLocalDate()!=now.toLocalDate()) return
        val s=Store(c);val h=HabitStore(c);val sent=s.prefs.getStringSet("habit_reminded",emptySet())!!.toSet()
        val plans=due(h.habits(),h.entries(),sent,dateTime)
        val manager=c.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"习惯打卡提醒",NotificationManager.IMPORTANCE_DEFAULT))
        plans.forEach { plan ->
            if(Build.VERSION.SDK_INT<33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED) {
                val launch=PendingIntent.getActivity(c,211,Intent(c,MainActivity::class.java).setAction("habits").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                manager.notify(plan.id,211,NotificationCompat.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_logo).setContentTitle("${plan.name} · 记录今天")
                    .setContentText("${plan.goal(now.toLocalDate())}，打开廿一留下实际记录。") .setContentIntent(launch).setAutoCancel(true).build())
            }
        }
        val keep=sent.filter { runCatching { LocalDate.parse(it.substringAfter('/'))>=now.toLocalDate().minusDays(30) }.getOrDefault(false) }
        s.prefs.edit().putStringSet("habit_reminded",(keep+plans.map { HabitAnalysis.key(it.id,now.toLocalDate()) }).toSet()).apply()
    }
    fun cancelNotice(c:Context,id:String) { c.getSystemService(NotificationManager::class.java).cancel(id,211) }
}
class HabitReminderReceiver:BroadcastReceiver() {
    override fun onReceive(c:Context,intent:Intent) { HabitReminders.deliver(c,intent.getLongExtra("at",0));HabitReminders.schedule(c) }
}
