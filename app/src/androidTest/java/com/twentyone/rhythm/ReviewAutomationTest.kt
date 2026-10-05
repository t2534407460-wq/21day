package com.twentyone.rhythm

import android.app.job.JobScheduler
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.*
import java.net.*
import java.time.*
import java.util.concurrent.atomic.AtomicInteger

// Separate instrumentation process: all network responses are synthetic, no API account is used.
class ReviewAutomationTest {
    private val c=InstrumentationRegistry.getInstrumentation().targetContext
    private val s=Store(c);private val hs=HabitStore(c);private val reviews=ReviewStore(c)
    private val scheduler=c.getSystemService(JobScheduler::class.java)
    companion object {
        private val requests=AtomicInteger(0)
        @Volatile private var status=200
        @Volatile private var sent=""
        @BeforeClass @JvmStatic fun mock() {
            URL.setURLStreamHandlerFactory { protocol -> if(protocol!="https") null else object:URLStreamHandler() {
                override fun openConnection(url:URL):URLConnection {
                    check(url.toString()==CloudCoach.endpoint)
                    requests.incrementAndGet()
                    return object:HttpURLConnection(url) {
                        val output=ByteArrayOutputStream()
                        override fun connect() {};override fun disconnect() {};override fun usingProxy()=false
                        override fun getOutputStream()=output
                        override fun getResponseCode():Int { sent=output.toString("UTF-8");return status }
                        override fun getInputStream()=ByteArrayInputStream(JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","stop").put("message",JSONObject().put("content","本期事实：已记录。趋势观察：尚需持续观察。下一周期：继续如实记录。")))).toString().toByteArray())
                    }
                }
            } }
        }
    }
    @Before fun setup() {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressHome();scheduler.cancel(ReviewScheduler.JOB)
        s.prefs.edit().clear().commit();reviews.prefs.edit().clear().commit();reviews.settings.edit().clear().putBoolean("weekly",false).putBoolean("cycle",false).putString("since",LocalDate.now().minusDays(30).toString()).commit()
        CloudCoach.saveKey(c,"sk-summary-test-not-a-real-key");requests.set(0);status=200;sent=""
    }
    @After fun cleanup() { scheduler.cancel(ReviewScheduler.JOB);CloudCoach.removeKey(c);s.prefs.edit().clear().commit();reviews.prefs.edit().clear().commit();reviews.settings.edit().putBoolean("weekly",false).putBoolean("cycle",false).commit() }
    private fun seed():ReviewSpec {
        val start=LocalDate.now().minusDays(21);val h=Habit(name="阅读",start=start,mode=HabitMode.AT_LEAST,unit="分钟",rules=listOf(HabitRule(start,target=20)))
        hs.save(h,start);hs.record(HabitEntry(h.id,start,25,"DO-NOT-SEND-NOTE"))
        return reviews.specs(c).first { it.kind=="cycle" }
    }
    @Test fun cycleUsesScopedFactsAndDoesNotGenerateTwice()=runBlocking {
        val spec=seed();ReviewRunner.generate(c,spec);ReviewRunner.generate(c,spec)
        assertEquals(1,requests.get());assertEquals("done",reviews.get(spec.id)?.status)
        assertTrue(sent.contains("阅读"));assertTrue(sent.contains("25分钟"));assertFalse(sent.contains("DO-NOT-SEND-NOTE"));assertFalse(sent.contains("sk-summary"))
        assertEquals(25,hs.entries().single().value)
    }
    @Test fun failedGenerationWaitsForExplicitRetry()=runBlocking {
        val spec=seed();status=401;ReviewRunner.generate(c,spec);ReviewRunner.generate(c,spec)
        assertEquals("failed",reviews.get(spec.id)?.status);assertEquals(1,requests.get())
        status=200;ReviewRunner.generate(c,spec,true);assertEquals("done",reviews.get(spec.id)?.status);assertEquals(2,requests.get())
    }
    @Test fun disabledAutomationHasNoJobAndFutureScheduleIsPersisted() {
        val today=LocalDate.now();hs.save(Habit(name="计次",start=today,rules=listOf(HabitRule(today))))
        ReviewScheduler.schedule(c);assertNull(scheduler.getPendingJob(ReviewScheduler.JOB))
        reviews.settings.edit().putBoolean("cycle",true).commit();ReviewScheduler.schedule(c)
        val job=scheduler.getPendingJob(ReviewScheduler.JOB)!!
        assertTrue(job.isPersisted);assertEquals(today.plusDays(21).atStartOfDay().epoch(),job.extras.getLong("due"))
        ReviewScheduler.schedule(c);assertEquals(job.extras.getString("id"),scheduler.getPendingJob(ReviewScheduler.JOB)?.extras?.getString("id"))
        reviews.settings.edit().putBoolean("cycle",false).commit();ReviewScheduler.schedule(c);assertNull(scheduler.getPendingJob(ReviewScheduler.JOB))
    }
    @Test fun realJobServiceGeneratesDueReviewAndStopsAfterSuccess() {
        val spec=seed();reviews.settings.edit().putBoolean("cycle",true).commit();ReviewScheduler.schedule(c)
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand("cmd jobscheduler run -f ${c.packageName} ${ReviewScheduler.JOB}")
        val until=System.currentTimeMillis()+20000
        while(reviews.get(spec.id)?.status!="done" && System.currentTimeMillis()<until) Thread.sleep(100)
        assertEquals("done",reviews.get(spec.id)?.status);assertEquals(1,requests.get())
        ReviewScheduler.schedule(c);assertNull(scheduler.getPendingJob(ReviewScheduler.JOB))
    }
}
