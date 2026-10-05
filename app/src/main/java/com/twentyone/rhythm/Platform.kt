package com.twentyone.rhythm

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.*
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.time.*

class RhythmApplication : Application() {
    private var widgetListener:android.content.SharedPreferences.OnSharedPreferenceChangeListener?=null
    override fun onCreate() {
        super.onCreate()
        if(getProcessName().endsWith(":widgetProvider")) return
        Store(this).migrateRestSchedule()
        Notifications.create(this)
        widgetListener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
            if(key in setOf("plan","rules","pending_at","pending_rules","logs","habits","habit_timers") || key?.startsWith("cards_")==true || key?.startsWith("widget_schedule_")==true) RhythmWidget.refresh(this)
        }
        Store(this).prefs.registerOnSharedPreferenceChangeListener(widgetListener)
        RhythmWidget.refresh(this)
        ProjectSync.observeConnectivity(this)
        registerActivityLifecycleCallbacks(object:ActivityLifecycleCallbacks {
            override fun onActivityCreated(a:Activity,state:Bundle?) { RecentTasks.apply(a) }
            override fun onActivityResumed(a:Activity) { RecentTasks.apply(a);ProjectSync.schedule(a) }
            override fun onActivityStarted(a:Activity) {}
            override fun onActivityPaused(a:Activity) {}
            override fun onActivityStopped(a:Activity) {}
            override fun onActivitySaveInstanceState(a:Activity,state:Bundle) {}
            override fun onActivityDestroyed(a:Activity) {}
        })
    }
}
object RecentTasks {
    fun apply(c:Context) {
        val hidden=Store(c).hideFromRecents
        c.getSystemService(ActivityManager::class.java).appTasks.forEach { it.setExcludeFromRecents(hidden) }
    }
}
object Notifications {
    const val ALARM = "wake_alarm"
    const val INFO = "rhythm_info"
    const val BEDTIME_TITLE = "睡前，留下一点状态"
    const val BEDTIME_AT = "bedtime_at"
    fun create(c: Context) {
        val nm=c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ALARM,"起床叫醒",NotificationManager.IMPORTANCE_HIGH).apply { description="起床提醒与二次验证"; setSound(null,null); enableVibration(false) })
        nm.createNotificationChannel(NotificationChannel(INFO,"计划提醒",NotificationManager.IMPORTANCE_DEFAULT))
    }
    fun pending(c: Context, wake: Boolean = false): PendingIntent = PendingIntent.getActivity(c,if(wake) 11 else 12,Intent(c,if(wake) WakeActivity::class.java else MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun info(c: Context,title: String,text: String,id: Int=22,extras:Bundle?=null,bedtime:Boolean=false) {
        if(Build.VERSION.SDK_INT>=33 && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return
        val open=if(bedtime) PendingIntent.getActivity(c,26,Intent(c,MainActivity::class.java).setAction("bedtime_record").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) else pending(c)
        c.getSystemService(NotificationManager::class.java).notify(id,NotificationCompat.Builder(c,INFO).setSmallIcon(R.drawable.ic_logo).setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).apply { if(extras!=null) addExtras(extras) }.build())
    }
    fun clearStaleBedtime(c:Context,bedtime:LocalDateTime?) {
        val manager=c.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.firstOrNull { it.id==22 && it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()==BEDTIME_TITLE }?.let {
            if(bedtime==null || it.notification.extras.getLong(BEDTIME_AT)!=bedtime.epoch()) manager.cancel(22)
        }
    }
}
object Alarms {
    const val WAKE="wake"
    const val CHECK="check"
    const val PREP="prep"
    const val BEDTIME_LOCK="bedtime_lock"
    private fun code(action: String) = when(action){WAKE->100;CHECK->101;BEDTIME_LOCK->103;else->102}
    private fun pending(c: Context,action: String)=PendingIntent.getBroadcast(c,code(action),Intent(c,AlarmReceiver::class.java).setAction(action),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun exactAllowed(c: Context)=Build.VERSION.SDK_INT<31 || c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    fun set(c: Context,action: String,at: Long) {
        val am=c.getSystemService(AlarmManager::class.java)
        if(!exactAllowed(c)) return
        try { if(action==PREP) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pending(c,action))
        else am.setAlarmClock(AlarmManager.AlarmClockInfo(at,Notifications.pending(c,action!=BEDTIME_LOCK)),pending(c,action))
            if(action==WAKE) Store(c).prefs.edit().putLong("scheduled_wake",at).apply()
        } catch (_: SecurityException) { Notifications.info(c,"闹钟权限已关闭","请在权限检查中重新开启精确闹钟。") }
    }
    fun cancel(c: Context,action: String) { c.getSystemService(AlarmManager::class.java).cancel(pending(c,action));if(action==WAKE)Store(c).prefs.edit().remove("scheduled_wake").apply() }
    // Apply the rest-day rule at delivery too: upgrades can leave old wake/check callbacks queued.
    fun clearRestWake(c:Context):Boolean {
        val s=Store(c);val w=s.wake
        if(!RestCalendar.isRest(LocalDate.now()) || w.test) return false
        if(w.active) s.wake=w.copy(phase=WakePhase.STOPPED,dueAt=0)
        cancel(c,CHECK);c.stopService(Intent(c,WakeAlarmService::class.java))
        c.getSystemService(NotificationManager::class.java).cancel(21)
        return true
    }
    fun schedule(c: Context) {
        HabitReminders.schedule(c)
        val s=Store(c); s.applyPending(); val p=s.plan
        val now=LocalDateTime.now()
        BedtimeReminders.preparation(c,now)
        val previouslyScheduled=s.prefs.getLong("scheduled_wake",0)
        cancel(c,WAKE); cancel(c,PREP)
        clearRestWake(c)
        BedtimeLock.schedule(c)
        if(p==null) { RhythmWidget.refresh(c);return }
        val handled=s.wake.day==now.toLocalDate().toString() && !s.wake.test && s.wake.phase!=WakePhase.IDLE
        if(!RestCalendar.isRest(now.toLocalDate()) && Schedule.preserveDueWake(previouslyScheduled,System.currentTimeMillis(),handled)) set(c,WAKE,System.currentTimeMillis()+1500)
        else Schedule.nextWake(p,s.rules,now)?.let { set(c,WAKE,it.epoch()) }
        Schedule.nextWindDown(p,s.rules,now)?.let { set(c,PREP,it.epoch()) }
        val w=s.wake
        if(w.active && w.dueAt>0) {
            // Never ring an obsolete morning session after a later reboot.
            if(w.day!=LocalDate.now().toString()) { s.wake=w.copy(phase=WakePhase.TIMED_OUT,dueAt=0); cancel(c,CHECK) }
            else set(c,CHECK,maxOf(System.currentTimeMillis()+1500,w.dueAt))
        }
        RhythmWidget.refresh(c)
    }
    fun start(c: Context,test: Boolean=false) {
        if(!test && RestCalendar.isRest(LocalDate.now())) { clearRestWake(c);return }
        val s=Store(c); val now=System.currentTimeMillis()
        if(s.wake.active) return
        s.wake=WakeSession(LocalDate.now().toString(),WakePhase.RINGING,now+60_000,1,test=test)
        ring(c)
    }
    fun ring(c: Context) {
        if(clearRestWake(c)) return
        val w=Store(c).wake
        if(!w.active) return
        set(c,CHECK,w.dueAt)
        try { ContextCompat.startForegroundService(c,Intent(c,WakeAlarmService::class.java)) }
        catch (_: IllegalStateException) { Notifications.info(c,"请打开起床验证","后台响铃未能启动，请检查系统权限。",23) }
        catch (_: SecurityException) { Notifications.info(c,"起床提醒需要权限","请打开 APP 的权限检查。",23) }
    }
    fun begin(c: Context) {
        if(clearRestWake(c)) return
        val s=Store(c); s.wake=s.wake.begin(System.currentTimeMillis())
        c.stopService(Intent(c,WakeAlarmService::class.java)); set(c,CHECK,s.wake.dueAt)
    }
    fun verify(c: Context): Boolean {
        if(clearRestWake(c)) return false
        val s=Store(c); val before=s.wake; val after=before.verify(System.currentTimeMillis(),s.rules)
        if(before==after) return false
        s.wake=after; c.stopService(Intent(c,WakeAlarmService::class.java)); cancel(c,CHECK)
        if(after.phase==WakePhase.COMPLETE) {
            if(!after.test) {
                val log=s.log(after.day)
                s.saveLog(log.copy(verifiedAt=after.completedAt,rise=timeText(LocalTime.now().hour*60+LocalTime.now().minute)))
                s.event("起床验证完成","${after.attempts} 次提醒；完成验证不等同睡眠检测")
            }
            RhythmWidget.refresh(c)
        } else set(c,CHECK,after.dueAt)
        return true
    }
    fun stop(c: Context,reason: String) {
        val s=Store(c); val test=s.wake.test
        s.wake=s.wake.copy(phase=WakePhase.STOPPED,dueAt=0)
        cancel(c,CHECK); c.stopService(Intent(c,WakeAlarmService::class.java))
        if(!test) s.event("结束起床验证",reason)
    }
    fun emergencyStop(c:Context,gate:EmergencyExitGate):Boolean {
        val w=Store(c).wake
        if(!w.active || w.test || !gate.canExit(SystemClock.elapsedRealtime())) return false
        stop(c,"应急退出：等待 60 秒并持续长按 5 秒；未完成起床验证")
        return true
    }
}
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context,intent: Intent) {
        Notifications.create(c)
        val s=Store(c)
        when(intent.action) {
            Alarms.WAKE -> {
                s.prefs.edit().remove("scheduled_wake").apply()
                if(RestCalendar.isRest(LocalDate.now())) { Alarms.clearRestWake(c);Alarms.schedule(c);return }
                val w=s.wake
                if(w.day!=LocalDate.now().toString() || w.phase==WakePhase.IDLE || w.test) {
                    if(w.active) Alarms.stop(c,"新一天开始")
                    Alarms.start(c)
                }
                Alarms.schedule(c)
            }
            Alarms.PREP -> {
                s.applyPending()
                Alarms.schedule(c)
            }
            Alarms.BEDTIME_LOCK -> { s.applyPending();BedtimeReminders.due(c);BedtimeLock.enforce(c);Alarms.schedule(c) }
            Alarms.CHECK -> {
                if(Alarms.clearRestWake(c)) return
                val w=s.wake
                if(!w.active || System.currentTimeMillis()+500<w.dueAt) return
                val next=w.retry(System.currentTimeMillis()); s.wake=next
                if(next.active) Alarms.ring(c) else {
                    c.stopService(Intent(c,WakeAlarmService::class.java))
                    if(!w.test) s.event("起床尚未确认","提醒达到上限；不据此判定仍在睡觉")
                    Notifications.info(c,"起床尚未确认","提醒已达到上限。方便时打开 APP 记录今天的情况。")
                }
            }
        }
    }
}
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context,intent: Intent) { if(intent.action in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_MY_PACKAGE_REPLACED,AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) Alarms.schedule(c) }
}
class WakeAlarmService : Service() {
    private var ringtone: Ringtone?=null
    private val handler=Handler(Looper.getMainLooper())
    private var lock: PowerManager.WakeLock?=null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        val s=Store(this)
        if(RestCalendar.isRest(LocalDate.now()) && !s.wake.test) {
            // A queued startForegroundService still needs its foreground acknowledgement before exit.
            startForeground(21,NotificationCompat.Builder(this,Notifications.ALARM).setSmallIcon(R.drawable.ic_logo)
                .setContentTitle("休息日自然醒").setSilent(true).build())
            Alarms.clearRestWake(this);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY
        }
        if(!s.wake.active) { stopSelf(); return START_NOT_STICKY }
        // Old notifications can retain their PendingIntent across a package update.
        if(intent?.action=="stop" && s.wake.test) { Alarms.stop(this,"结束试用闹钟");stopSelf();return START_NOT_STICKY }
        val notification=NotificationCompat.Builder(this,Notifications.ALARM).setSmallIcon(R.drawable.ic_logo).setContentTitle(if(s.wake.firstAt>0) "再确认一次，开始新一天" else "早安，该起床了")
            .setContentText("前往洗漱区，扫码或触碰 NFC 标签完成验证").setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true).setContentIntent(Notifications.pending(this,true)).setFullScreenIntent(Notifications.pending(this,true),true)
        if(s.wake.test) {
            val stop=PendingIntent.getService(this,44,Intent(this,WakeAlarmService::class.java).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            notification.addAction(0,"结束试用",stop)
        } else notification.addAction(0,"前往起床验证",Notifications.pending(this,true))
        startForeground(21,notification.build())
        handler.removeCallbacksAndMessages(null)
        ringtone?.stop()
        ringtone=RingtoneManager.getRingtone(this,RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.apply {
            audioAttributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            if(Build.VERSION.SDK_INT>=28) isLooping=true
            play()
        }
        val vibrator=getSystemService(Vibrator::class.java)
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0,400,600),0))
        lock?.let { if(it.isHeld) it.release() }
        lock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"rhythm:wake").apply { acquire(65_000) }
        handler.postDelayed({
            ringtone?.stop(); vibrator.cancel(); stopSelf()
            // AlarmManager owns retries, so closing the activity does not reset the sequence.
        },55_000)
        return START_NOT_STICKY
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); ringtone?.stop(); getSystemService(Vibrator::class.java).cancel(); lock?.let { if(it.isHeld) it.release() }; super.onDestroy() }
}
fun Context.openSettings(action: String,withPackage: Boolean=true) {
    runCatching { startActivity(Intent(action).apply { if(withPackage) data=android.net.Uri.parse("package:$packageName"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }
        .onFailure { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
