package com.nousresearch.hermes.android.jobs

import com.nousresearch.hermes.android.chat.FakeCoreGateway
import com.nousresearch.hermes.android.gateway.GatewaySeen
import com.nousresearch.hermes.android.gateway.GatewayStatus
import com.nousresearch.hermes.bridge.AppBridge
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The form's create and edit: `cron.manage add` or the app's `cron_update`, then the core's list again (D39 F6, F10). */
@OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher: a launched collector runs until it suspends
class JobsSaveTest {
    private var listed = ONE_JOB
    private var added = """{"success":true,"job_id":"j2","name":"Patched name","job":{"job_id":"j2","name":"Patched name"}}"""
    private var onAdd: () -> Unit = {}
    private val core = FakeCoreGateway { method, params ->
        check(method == "cron.manage") { "unexpected $method" }
        when (val action = (params["action"] as? JsonPrimitive)?.content) {
            null -> listed
            "add" -> { onAdd(); added }
            else -> error("unexpected action $action")
        }
    }
    private var targets = """{"targets":[{"id":"telegram","name":"Telegram","home_set":false}]}"""
    private var targetsFail = false
    private var updated = """{"job_id":"j1"}"""
    private val bridgeCalls = mutableListOf<Pair<String, String>>()
    private val bridge = AppBridge { function, arg ->
        bridgeCalls += function to arg
        when (function) {
            "cron_scheduler" -> FRESH
            "cron_delivery_targets" -> { if (targetsFail) throw IllegalStateException("the bridge broke"); targets }
            "cron_update" -> updated
            else -> error("unexpected $function")
        }
    }
    private val control = JobsControl(core, bridge, seen = { GatewaySeen(GatewayStatus.RUNNING, pid = 42) })

    private val listCalls get() = core.calls.filter { it.first == "cron.manage" && it.second["action"] == null }
    private val addCalls get() = core.calls.filter { it.second["action"] == JsonPrimitive("add") }
    private fun bridgeCalls(function: String) = bridgeCalls.filter { it.first == function }
    private val ui get() = control.state.value!!

    private val local = DeliveryTarget(Delivery.Local, "local", homeSet = true)
    private val telegram = DeliveryTarget(Delivery.Platform("telegram"), "Telegram", homeSet = false)
    private val newJob = JobForm(null, "", "Summarise the news", Schedule.Weekdays(9, 30), Delivery.Local, null)

    @Test
    fun aNewJobOpensWithLocalThenTheCoresTargets() = runTest {
        control.open()
        control.newJob()

        assertEquals(JobForm.blank(), ui.form)
        assertEquals(listOf(local, telegram), ui.targets)
        assertEquals(1, bridgeCalls("cron_delivery_targets").size)
        assertNull(ui.error)
    }

    @Test
    fun unreadableTargetsLeaveLocalAndNoError() = runTest {
        control.open()
        targets = """{"error":"x"}"""
        control.newJob()

        assertEquals(listOf(local), ui.targets)
        assertNull(ui.error)

        control.cancelForm()
        targetsFail = true
        control.newJob()

        assertEquals(listOf(local), ui.targets)
        assertNull(ui.error)
        assertEquals(JobForm.blank(), ui.form)
    }

    @Test
    fun savingANewJobAddsItAndListsTheCoresAnswer() = runTest {
        control.open()
        control.newJob()
        listed = TWO_JOBS

        control.save(newJob)

        val sent = addCalls.single().second
        assertEquals(JsonPrimitive("30 9 * * 1-5"), sent["schedule"])
        assertEquals(JsonPrimitive("local"), sent["deliver"])
        assertEquals(JsonPrimitive("Summarise the news"), sent["prompt"])
        assertFalse("repeat" in sent)
        assertFalse("name" in sent)
        assertEquals(2, listCalls.size)
        // The list shown is the second list's, not the add's own job.
        assertEquals(listOf("Morning news", "Weekday digest"), ui.jobs.map { it.name })
        assertNull(ui.form)
        assertEquals(emptyList<DeliveryTarget>(), ui.targets)
        assertFalse(ui.busy)
        assertNull(ui.error)
    }

    @Test
    fun aRefusedAddKeepsTheFormInTheCoresWords() = runTest {
        control.open()
        control.newJob()
        added = """{"success":false,"error":"Invalid duration: 'banana'."}"""

        control.save(newJob.copy(schedule = Schedule.Custom("every banana")))

        assertEquals("every banana", addCalls.single().second["schedule"]?.let { (it as JsonPrimitive).content })
        assertEquals("Invalid duration: 'banana'.", ui.error)
        assertEquals(JobForm.blank(), ui.form)
        assertEquals(listOf(local, telegram), ui.targets)
        assertFalse(ui.busy)
        assertEquals(1, listCalls.size)
    }

    @Test
    fun anEditSendsTheJobsIdAndOnlyWhatChanged() = runTest {
        control.open()
        val job = ui.jobs.single()
        assertEquals("Morning news", job.name)

        control.edit(JobId("j1"))

        val form = ui.form!!
        assertEquals(JobForm.of(job), form)
        assertEquals(listOf(local, telegram), ui.targets)

        control.save(form.copy(prompt = "new"))

        assertEquals("""{"job_id":"j1","name":null,"schedule":null,"prompt":"new","deliver":null,"repeat":null}""",
                     bridgeCalls("cron_update").single().second)
        assertEquals(2, listCalls.size)
        assertNull(ui.form)
        assertFalse(ui.busy)
        assertTrue(addCalls.isEmpty())
    }

    @Test
    fun aRefusedEditKeepsTheForm() = runTest {
        control.open()
        control.edit(JobId("j1"))
        val form = ui.form!!
        updated = """{"error":"No updates provided."}"""

        control.save(form.copy(prompt = "new"))

        assertEquals("No updates provided.", ui.error)
        assertEquals(form, ui.form)
        assertFalse(ui.busy)
        assertEquals(1, listCalls.size)
    }

    @Test
    fun anUnchangedEditSendsNothing() = runTest {
        control.open()
        control.edit(JobId("j1"))

        control.save(ui.form!!)

        assertTrue(bridgeCalls("cron_update").isEmpty())
        assertNull(ui.form)
        assertFalse(ui.busy)
        assertNull(ui.error)
    }

    @Test
    fun anEditOfAJobNoLongerListed() = runTest {
        control.open()
        control.edit(JobId("j1"))
        val form = ui.form!!

        control.save(form.copy(id = JobId("j9"), prompt = "new"))

        assertEquals("That job no longer exists.", ui.error)
        assertEquals(form, ui.form)
        assertFalse(ui.busy)
        assertTrue(bridgeCalls("cron_update").isEmpty())
        assertEquals(1, listCalls.size)
    }

    @Test
    fun editingAJobNotListedOpensNoForm() = runTest {
        control.open()

        control.edit(JobId("j9"))

        assertNull(ui.form)
        assertTrue(bridgeCalls("cron_delivery_targets").isEmpty())
    }

    @Test
    fun aListWhileTheFormIsOpenKeepsIt() = runTest(UnconfinedTestDispatcher()) {
        control.open()
        backgroundScope.launch { control.follow() }
        control.newJob()
        val form = ui.form
        listed = TWO_JOBS

        core.emit("cron.changed", "")

        assertEquals(listOf(JobId("j1"), JobId("j2")), ui.jobs.map { it.id })
        assertEquals(form, ui.form)
        assertEquals(listOf(local, telegram), ui.targets)

        listed = ONE_JOB
        control.refresh()

        assertEquals(listOf(JobId("j1")), ui.jobs.map { it.id })
        assertEquals(form, ui.form)
        assertEquals(listOf(local, telegram), ui.targets)
    }

    @Test
    fun aSecondSaveWhileOneRunsIsNotSent() = runTest {
        control.open()
        control.newJob()
        onAdd = { runBlocking { control.save(newJob) } }

        control.save(newJob)

        assertEquals(1, addCalls.size)
        assertNull(ui.form)
        assertFalse(ui.busy)
    }

    @Test
    fun cancelClosesTheFormOnly() = runTest {
        control.open()
        control.newJob()
        val before = core.calls.size to bridgeCalls.size

        control.cancelForm()

        assertNull(ui.form)
        assertEquals(emptyList<DeliveryTarget>(), ui.targets)
        assertEquals(1, ui.jobs.size)
        assertEquals(before, core.calls.size to bridgeCalls.size)
    }

    @Test
    fun aClosedPageOpensNoForm() = runTest {
        control.newJob()

        assertNull(control.state.value)
        assertTrue(bridgeCalls.isEmpty())
        assertTrue(core.calls.isEmpty())
    }

    private companion object {
        const val SCHEDULED = """{"job_id":"j1","name":"Morning news","schedule":"30 9 * * *","state":"scheduled",
                                 "prompt_preview":"Summarise the news","repeat":"forever","deliver":"local"}"""
        const val DIGEST = """{"job_id":"j2","name":"Weekday digest","schedule":"30 9 * * 1-5","state":"scheduled",
                              "prompt_preview":"Summarise the news","repeat":"forever","deliver":"local"}"""
        const val ONE_JOB = """{"success":true,"count":1,"jobs":[$SCHEDULED]}"""
        const val TWO_JOBS = """{"success":true,"count":2,"jobs":[$SCHEDULED,$DIGEST]}"""
        const val FRESH = """{"heartbeat_age_s":30,"success_age_s":30,"last_error":null,"stale_after_s":200}"""
    }
}
