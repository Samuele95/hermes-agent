package com.nousresearch.hermes.android

import android.Manifest
import android.app.ActivityManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.gateway.GatewayRecord
import com.nousresearch.hermes.android.gateway.observeGateway
import com.nousresearch.hermes.android.gateway.writeGatewayRecord
import com.nousresearch.hermes.android.jobs.Delivery
import com.nousresearch.hermes.android.jobs.DeliveryTarget
import com.nousresearch.hermes.android.jobs.JobForm
import com.nousresearch.hermes.android.jobs.JobsUi
import com.nousresearch.hermes.android.jobs.Schedule
import com.nousresearch.hermes.android.jobs.repeatOf
import com.nousresearch.hermes.bridge.protocol.CronAction
import com.nousresearch.hermes.bridge.protocol.CronManageParams
import com.nousresearch.hermes.bridge.protocol.Events
import com.nousresearch.hermes.bridge.protocol.Methods
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The job form's create and edit over the real embedded core (no model, no network, no token): each preset is a
 * schedule the core accepts, an edit of a one-shot keeps a schedule whose display does not parse back, a
 * refusal comes back in the core's words, and a week of minutes and a five-digit repeat count are kept as typed.
 * The gateway stays off, so no job is ever fired; every `j6 probe` job a test adds is removed again.
 */
@RunWith(AndroidJUnit4::class)
class JobFormDeviceTest {
    private companion object {
        const val TAG = "JobFormDeviceTest"
        const val PROBE = "j6 probe"
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as HermesApplication
    private val gatewayProcess = "${context.packageName}:gateway"

    private fun <T> waitFor(what: String, seconds: Int = 120, value: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + seconds * 1000L
        while (true) {
            value()?.let { return it }
            check(SystemClock.elapsedRealtime() < deadline) { "timed out waiting for $what (gateway: ${observeGateway(context)})" }
            SystemClock.sleep(200)
        }
    }

    private fun gatewayPid(): Int? = context.getSystemService(ActivityManager::class.java).runningAppProcesses
        ?.firstOrNull { it.processName == gatewayProcess }?.pid

    private val ready by lazy { waitFor("the core") { app.core.value as? CoreState.Ready } }
    private val jobs get() = ready.pages.jobs
    private val ui: JobsUi get() = checkNotNull(jobs.state.value)

    private fun probe(n: Any) = "$PROBE ${System.nanoTime()} $n"

    /** Removes every job a test of this class added, found by name in a fresh list. */
    private fun removeProbes() {
        runCatching {
            runBlocking {
                val listed = ready.gateway.call(Methods.CRON_MANAGE, CronManageParams(includeDisabled = JsonPrimitive(true)))
                listed.jobs.orEmpty().filter { it.name.startsWith(PROBE) }.forEach {
                    ready.gateway.call(Methods.CRON_MANAGE, CronManageParams(action = CronAction.REMOVE, name = it.jobId))
                }
            }
        }
    }

    private fun gatewayOff() {
        if (gatewayPid() == null) return
        app.gateway.turnOff()
        waitFor("no :gateway process", 60) { if (gatewayPid() == null) true else null }
    }

    /** Saves a new job from the form, as the sheet would, and returns the listed job's id. */
    private fun create(name: String, schedule: Schedule): String {
        runBlocking {
            jobs.newJob()
            jobs.save(JobForm(null, name, "Say hello.", schedule, Delivery.Local, repeatOf(schedule, "")))
        }
        assertNull(ui.error)
        assertNull(ui.form)
        return ui.jobs.single { it.name == name }.id.value
    }

    @Before
    fun aStoppedGatewayRecord() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        // A record left by an earlier, interrupted run would revive a gateway into these tests when the app opens.
        if (gatewayPid() == null) writeGatewayRecord(context, GatewayRecord(GatewayRecord.State.STOPPED, 0))
    }

    @After
    fun stopTheGateway() {
        gatewayOff()
    }

    @Test
    fun eachPresetIsAScheduleTheCoreAccepts() {
        gatewayOff()
        val cases = listOf(Schedule.Every(30) to "every 30m", Schedule.Daily(9, 30) to "30 9 * * *",
                           Schedule.Weekdays(9, 30) to "30 9 * * 1-5", Schedule.Once(45) to "once in 45m",
                           Schedule.Custom("every monday 9am") to "every monday 9am")
        try {
            runBlocking { jobs.open() }
            cases.forEachIndexed { n, (schedule, display) ->
                val name = probe(n)
                val id = create(name, schedule)
                val job = ui.jobs.single { it.id.value == id }
                Log.i(TAG, "preset $schedule -> the core's display '${job.schedule}', repeat '${job.repeat}', deliver ${job.delivery}")
                assertEquals(display, job.schedule)
                assertEquals(Delivery.Local, job.delivery)
            }
        } finally {
            removeProbes()
            jobs.close()
        }
    }

    @Test
    fun anEditOfAOneShotChangesItsPromptAndKeepsItsSchedule() {
        gatewayOff()
        try {
            runBlocking { jobs.open() }
            val id = create(probe("once"), Schedule.Once(45))

            runBlocking { jobs.edit(ui.jobs.single { it.id.value == id }.id) }
            val form = checkNotNull(ui.form)
            assertNull(form.schedule)
            runBlocking { jobs.save(form.copy(prompt = "Say goodbye.")) }

            assertNull(ui.error)
            assertNull(ui.form)
            val job = ui.jobs.single { it.id.value == id }
            Log.i(TAG, "one-shot after edit: '${job.schedule}', prompt '${job.prompt}', repeat '${job.repeat}'")
            assertEquals("once in 45m", job.schedule)
            assertEquals("Say goodbye.", job.prompt)
        } finally {
            removeProbes()
            jobs.close()
        }
    }

    @Test
    fun aScheduleTheCoreRefusesIsShownInItsWords() {
        gatewayOff()
        val name = probe("banana")
        try {
            runBlocking {
                jobs.open()
                jobs.newJob()
                jobs.save(JobForm(null, name, "Say hello.", Schedule.Custom("every banana"), Delivery.Local, null))
            }

            val error = checkNotNull(ui.error)
            Log.i(TAG, "every banana: $error")
            assertTrue(error, "Invalid duration" in error)
            assertNotNull(ui.form)
            assertTrue(ui.jobs.none { it.name == name })  // this job only: a probe left by another run is not this one
        } finally {
            removeProbes()
            jobs.close()
        }
    }

    @Test
    fun aWeekOfMinutesAndTenThousandRunsAreWhatTheCoreLists() {
        gatewayOff()
        val name = probe("week")
        val week = Schedule.Every(10080)
        try {
            runBlocking {
                jobs.open()
                jobs.newJob()
                jobs.save(JobForm(null, name, "Say hello.", week, Delivery.Local, repeatOf(week, "10000")))
            }
            assertNull(ui.error)
            val job = ui.jobs.single { it.name == name }
            Log.i(TAG, "a week: '${job.schedule}', repeat '${job.repeat}' (times ${job.times})")
            assertEquals("every 10080m", job.schedule)
            assertEquals("10000 times", job.repeat)
            assertEquals(10000, JobForm.of(job).repeat)  // an edit starts from the same count
        } finally {
            removeProbes()
            jobs.close()
        }
    }

    @Test
    fun theTargetsStartWithLocal() {
        gatewayOff()
        val changes = AtomicInteger()
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        val watcher = GlobalScope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            ready.gateway.events.collect { if (it.decode(Events.CRON_CHANGED) != null) changes.incrementAndGet() }
        }
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        val follower = GlobalScope.launch(Dispatchers.IO) { jobs.follow() }  // as the page runs it while it shows
        try {
            runBlocking {
                jobs.open()
                jobs.newJob()
            }
            val targets = ui.targets
            assertEquals(DeliveryTarget(Delivery.Local, "local", homeSet = true), targets.first())
            Log.i(TAG, "cron_delivery_targets after Local: ${targets.drop(1)}")

            // A job added elsewhere (as the agent would) while the sheet is open: the list follows, the sheet stays.
            val form = ui.form
            val name = probe("elsewhere")
            runBlocking {
                ready.gateway.call(Methods.CRON_MANAGE, CronManageParams(action = CronAction.ADD, name = name,
                                                                         schedule = "every 10080m", prompt = "Say hello.",
                                                                         deliver = "local"))
            }
            val listed = runCatching { waitFor("the job listed", 10) { ui.jobs.find { it.name == name } } }.isSuccess
            Log.i(TAG, "with the sheet open: cron.changed ${changes.get()}, the new job listed $listed, form kept ${ui.form == form}")
            assertEquals(form, ui.form)
            assertEquals(targets, ui.targets)
        } finally {
            follower.cancel()
            watcher.cancel()
            removeProbes()
            jobs.close()
        }
    }
}
