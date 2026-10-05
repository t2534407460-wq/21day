package com.twentyone.rhythm

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate
import java.io.File

/** Opt-in, synthetic fixture that runs first on the released old APK, then after cover installation. */
class ReleaseUpgradeTest {
    @Test fun preservesOldAccountDataAndEncryptedKey() {
        val args=InstrumentationRegistry.getArguments()
        Assume.assumeTrue(args.getString("releaseUpgrade") in setOf("seed","verify"))
        Assume.assumeTrue(Build.HARDWARE in listOf("ranchu","goldfish"))
        val c=InstrumentationRegistry.getInstrumentation().targetContext;val s=Store(c)
        val fixture=File(c.filesDir,"0.11.1-upgrade-fixture.json")
        val key="sk-upgrade-0.11.1-not-a-real-key"
        if(args.getString("releaseUpgrade")=="seed") {
            kotlinx.coroutines.runBlocking { ProjectSync.disable(c) }
            s.prefs.edit().clear().commit()
            s.migrateRestSchedule() // Apply the old release's first-open migration before setting user rules.
            s.createPlan(Plan(LocalDate.now().plusDays(2)))
            s.rules=Rules(passMinutes=8,passWaitSeconds=120,blocked=setOf("com.android.chrome"))
            s.nfcId="04A1B2C3D4E5F6";s.qrToken
            s.saveLog(DayLog(LocalDate.now().toString(),note="旧版覆盖升级保留记录",verifiedAt=1234))
            val h=Habit(name="阅读",start=LocalDate.now(),mode=HabitMode.AT_LEAST,unit="分钟",rules=listOf(HabitRule(LocalDate.now(),target=20)))
            HabitStore(c).save(h);HabitStore(c).record(HabitEntry(h.id,LocalDate.now(),25,"升级保留"))
            HabitStore(c).startTimer(h.id)
            s.prefs.edit().putStringSet("cards_widget_9911",setOf(h.id)).putString("pass_night",LocalDate.now().minusDays(1).toString())
                .putLong("pass_until",System.currentTimeMillis()+480_000).putString("emergency_night",LocalDate.now().toString()).apply()
            ProjectPreferences.get(c,"coach_reports").edit().putString("last_7","旧版报告内容").commit()
            ProjectPreferences.get(c,"coach_chat").edit().putString("messages","[{\"id\":\"upgrade\",\"role\":\"user\",\"text\":\"旧版聊天\",\"at\":1234}]").commit()
            CloudCoach.saveKey(c,key)
            val write=ProjectAccount::class.java.getDeclaredMethod("write",android.content.Context::class.java,String::class.java,JSONObject::class.java).apply { isAccessible=true }
            write.invoke(ProjectAccount,c,"session",JSONObject().put("email","upgrade@example.test").put("userId",java.util.UUID.randomUUID().toString()))
            fixture.writeText(JSONObject().put("export",s.export()).put("qr",s.qrToken).put("timer",HabitStore(c).running(h.id)).put("habit",h.id)
                .put("keyFile",File(c.noBackupFilesDir,"deepseek-key.json").readText()).put("sessionFile",File(c.noBackupFilesDir,"account-session.json").readText()).toString())
        } else {
            val old=JSONObject(fixture.readText())
            assertEquals(old.getString("export"),s.export());assertEquals(old.getString("qr"),s.qrToken);assertEquals("04A1B2C3D4E5F6",s.nfcId)
            assertEquals(old.getLong("timer"),HabitStore(c).running(old.getString("habit")))
            assertEquals(setOf(old.getString("habit")),s.prefs.getStringSet("cards_widget_9911",emptySet()))
            assertEquals("旧版报告内容",ProjectPreferences.get(c,"coach_reports").getString("last_7",null))
            assertTrue(ProjectPreferences.get(c,"coach_chat").getString("messages","")!!.contains("旧版聊天"))
            assertEquals(key,CloudCoach.readKey(c));assertEquals("upgrade@example.test",ProjectAccount.email(c))
            assertEquals(old.getString("keyFile"),File(c.noBackupFilesDir,"deepseek-key.json").readText())
            assertEquals(old.getString("sessionFile"),File(c.noBackupFilesDir,"account-session.json").readText())
        }
    }
}
