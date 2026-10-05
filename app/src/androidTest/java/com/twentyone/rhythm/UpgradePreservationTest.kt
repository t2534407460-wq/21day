package com.twentyone.rhythm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Opt-in check for synthetic upgrade fixtures; never run on personal data. */
@RunWith(AndroidJUnit4::class)
class UpgradePreservationTest {
    @Test fun oldRecordsAndKeystoreKeyRemainReadableAfterCoverInstall() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("upgradeFixture")=="true")
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val s=Store(c)
        assertEquals("rhythm://wake/0.5.0-upgrade-test-point",s.qrToken)
        assertEquals("04A1B2C3D4E5F6",s.nfcId)
        assertEquals("sk-upgrade-test-only-not-a-real-key",CloudCoach.readKey(c))
        assertTrue(s.hideFromRecents);assertTrue(s.keepTaskOnBack)
        assertEquals(3,s.logs().size)
        assertTrue(s.logs().all { it.verifiedAt>0 && it.energy=="一般" && it.note=="覆盖升级用旧版中文记录，需完整保留。" })
        if(InstrumentationRegistry.getArguments().getString("bedtimeFixture")=="true") {
            val bedtime=s.log("2026-10-02")
            assertEquals(1790953500000,bedtime.bedtimeCheckedAt)
            assertEquals("有点困",bedtime.sleepiness);assertEquals("平静",bedtime.bedMood);assertEquals("手机",bedtime.bedReason)
        } else assertTrue(s.logs().all { it.bedtimeCheckedAt==0L && it.sleepiness.isEmpty() && it.bedMood.isEmpty() })
        assertTrue(ProjectPreferences.get(c,"coach_reports").getString("last_7","")!!.contains("旧版摘要，仅为升级测试"))
        if(InstrumentationRegistry.getArguments().getString("chatFixture")=="true") {
            assertEquals(listOf("升级前的语音问题","这是一条保留的回复"),CoachChat.load(c).map { it.text })
            assertTrue(ProjectPreferences.get(c,"coach_voice").getBoolean("keyboard",true))
        }
        if(InstrumentationRegistry.getArguments().getString("habitFixture")=="true") {
            val habits=HabitStore(c)
            assertEquals(HabitInput.TIMER,habits.habits().single().input)
            assertEquals(20,habits.habits().single().rules.single().target)
            assertEquals(25,habits.entries().single().value)
            assertEquals("升级前阅读记录",habits.entries().single().note)
            assertTrue(habits.entries().single().sessions.isEmpty())
        }
    }
}
