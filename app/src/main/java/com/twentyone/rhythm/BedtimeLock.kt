package com.twentyone.rhythm

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.*
import android.content.pm.PackageManager
import java.time.LocalDateTime

class BedtimeAdminReceiver:DeviceAdminReceiver() {
    override fun onEnabled(context:Context,intent:Intent) { Alarms.schedule(context) }
    override fun onDisabled(context:Context,intent:Intent) { BedtimeLock.schedule(context) }
}

object BedtimeLock {
    private fun admin(c:Context)=ComponentName(c,BedtimeAdminReceiver::class.java)
    fun available(c:Context)=c.packageManager.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN) &&
        c.getSystemService(DevicePolicyManager::class.java).isAdminActive(admin(c))
    fun request(c:Context) {
        c.startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,admin(c))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,"到计划睡前时间仍未完成睡前打卡时，廿一会锁屏一次。只申请锁屏能力；解锁后可以继续完成打卡。"))
    }
    private fun locked(s:Store)=s.prefs.getStringSet("bedtime_locked_nights",emptySet())!!.toSet()
    fun schedule(c:Context) {
        Alarms.cancel(c,Alarms.BEDTIME_LOCK)
        val s=Store(c);val p=s.plan ?: return;val now=LocalDateTime.now()
        val lockDay=if(available(c)) BedtimeSchedule.nextLockDay(p,s.rules,s.logs(),locked(s),now) else null
        val day=listOfNotNull(lockDay,BedtimeReminders.nextDay(s,now)).minOrNull() ?: return
        Alarms.set(c,Alarms.BEDTIME_LOCK,maxOf(BedtimeSchedule.deadline(day,s.rules).epoch(),now.epoch()+1500))
    }
    fun enforce(c:Context) {
        val s=Store(c);val p=s.plan ?: return;val now=LocalDateTime.now()
        val day=BedtimeSchedule.nextLockDay(p,s.rules,s.logs(),locked(s),now) ?: return
        if(now.isBefore(BedtimeSchedule.deadline(day,s.rules)) || !available(c)) return
        try {
            c.getSystemService(DevicePolicyManager::class.java).lockNow()
            s.prefs.edit().putStringSet("bedtime_locked_nights",locked(s)+day.toString()).commit()
            s.event("睡前超时锁屏","$day 晚间：已到计划睡前时间仍未完成打卡，已执行一次系统锁屏")
        } catch(_:SecurityException) {
            Notifications.info(c,"睡前锁屏权限不可用","请在设置中检查睡前超时锁屏授权。",24)
        }
    }
}
