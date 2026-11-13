package com.nousresearch.hermes.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermes.android.jobs.Job
import com.nousresearch.hermes.android.jobs.JobActions
import com.nousresearch.hermes.android.jobs.JobFormActions
import com.nousresearch.hermes.android.jobs.JobId
import com.nousresearch.hermes.android.jobs.JobRowActions
import com.nousresearch.hermes.android.jobs.JobState
import com.nousresearch.hermes.android.jobs.JobsScreen
import com.nousresearch.hermes.android.jobs.JobsUi
import com.nousresearch.hermes.android.jobs.LastRun
import com.nousresearch.hermes.android.jobs.RunMark
import com.nousresearch.hermes.android.jobs.RunOutcome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A job row's ⋮ menu (automation-03, -04, -05): Run now only while the gateway runs and the job is scheduled and not
 * queued, Pause for a job that could still fire, Resume for a paused one, Delete always, asking first in the job's name.
 */
@RunWith(AndroidJUnit4::class)
class JobActionsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private val row = JobRowActions(pause = { calls += "pause:${it.value}" }, resume = { calls += "resume:${it.value}" },
                                    runNow = { calls += "run:${it.value}" }, askDelete = { calls += "ask:${it?.value}" },
                                    confirmDelete = { calls += "confirm" })

    private var state by mutableStateOf(JobsUi())

    private fun show(initial: JobsUi, withRow: Boolean = true) {
        state = initial
        compose.setContent { screen(withRow) }
    }

    @Composable
    private fun screen(withRow: Boolean) {
        JobsScreen(state, {}, {}, actions = JobActions({ calls += "new" }, { calls += "edit:${it.value}" },
                                                       JobFormActions({}, {}), row = row.takeIf { withRow }))
    }

    private val news = Job(JobId("a1b2"), "Morning news", "every 30m", "Summarise the news", JobState.SCHEDULED,
                           nextRunAt = "2026-11-14T09:30:00+01:00",
                           last = LastRun("2026-11-13T09:30:00+01:00", RunOutcome.OK, "ok", null), repeat = "forever")
    private val paused = news.copy(id = JobId("c3d4"), name = "Evening digest", state = JobState.PAUSED)
    private val done = news.copy(id = JobId("e5f6"), name = "Reminder", state = JobState.COMPLETED, repeat = "1/1")

    private fun menuOf(id: String) = compose.onNodeWithTag("job-menu-$id").performClick()
    private fun item(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    @Test
    fun aScheduledJobsMenuOffersRunNowPauseAndDelete() {
        show(JobsUi(listOf(news), gatewayRunning = true))

        menuOf("a1b2")
        item("job-run-now").assertExists()
        item("job-pause").assertExists()
        item("job-delete").assertExists()
        item("job-resume").assertDoesNotExist()

        item("job-run-now").performClick()
        compose.waitForIdle()

        assertEquals(listOf("run:a1b2"), calls)
        item("job-run-now").assertDoesNotExist()  // the menu has closed
        item("job-pause").assertDoesNotExist()
    }

    @Test
    fun aPausedJobOffersResumeAndNoRunNow() {
        show(JobsUi(listOf(paused), gatewayRunning = true))

        menuOf("c3d4")
        item("job-pause").assertDoesNotExist()
        item("job-run-now").assertDoesNotExist()
        item("job-delete").assertExists()
        item("job-resume").performClick()

        assertEquals(listOf("resume:c3d4"), calls)
    }

    @Test
    fun aCompletedJobOffersOnlyDelete() {
        show(JobsUi(listOf(done), gatewayRunning = true))

        menuOf("e5f6")
        item("job-run-now").assertDoesNotExist()
        item("job-pause").assertDoesNotExist()
        item("job-resume").assertDoesNotExist()
        item("job-delete").assertExists()
    }

    @Test
    fun withTheGatewayOffThereIsNoRunNow() {
        show(JobsUi(listOf(news), gatewayRunning = false))

        menuOf("a1b2")
        item("job-run-now").assertDoesNotExist()
        item("job-pause").performClick()

        assertEquals(listOf("pause:a1b2"), calls)
    }

    @Test
    fun aQueuedJobSaysSoAndOffersNoRunNow() {
        show(JobsUi(listOf(news), queued = mapOf(news.id to RunMark(null, 0)), gatewayRunning = true))

        item("job-queued-a1b2").assertTextEquals("Queued: the gateway runs it within a minute")
        menuOf("a1b2")
        item("job-run-now").assertDoesNotExist()
        item("job-pause").assertExists()
    }

    @Test
    fun deleteAsksFirstInTheJobsName() {
        show(JobsUi(listOf(news), gatewayRunning = true))

        menuOf("a1b2")
        item("job-delete").performClick()

        assertEquals(listOf("ask:a1b2"), calls)
        compose.waitForIdle()
        item("job-delete-dialog").assertDoesNotExist()  // the state names no job yet

        state = state.copy(confirmDelete = news.id)  // as the controller answers the ask
        compose.waitForIdle()
        compose.onNode(hasTestTag("job-delete-dialog")).assertExists()
        compose.onNode(hasText("Morning news", substring = true) and hasText("permanently", substring = true)).assertExists()
        item("job-delete-confirm").performClick()
        item("job-delete-cancel").performClick()

        assertEquals(listOf("ask:a1b2", "confirm", "ask:null"), calls)
    }

    @Test
    fun whileBusyNoMenuOpens() {
        show(JobsUi(listOf(news), busy = true, gatewayRunning = true))

        compose.onNodeWithTag("job-menu-a1b2").assertIsNotEnabled()
    }

    @Test
    fun withoutRowActionsThereIsNoMenu() {
        show(JobsUi(listOf(news), gatewayRunning = true), withRow = false)

        compose.onNodeWithTag("job-menu-a1b2").assertDoesNotExist()
        compose.onNodeWithTag("job-a1b2").performClick()

        assertEquals(listOf("edit:a1b2"), calls)
    }
}
