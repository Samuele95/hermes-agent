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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermes.android.chat.StoredSessionId
import com.nousresearch.hermes.android.jobs.Job
import com.nousresearch.hermes.android.jobs.JobActions
import com.nousresearch.hermes.android.jobs.JobFormActions
import com.nousresearch.hermes.android.jobs.JobId
import com.nousresearch.hermes.android.jobs.JobRowActions
import com.nousresearch.hermes.android.jobs.JobRun
import com.nousresearch.hermes.android.jobs.JobRunsActions
import com.nousresearch.hermes.android.jobs.JobState
import com.nousresearch.hermes.android.jobs.JobsScreen
import com.nousresearch.hermes.android.jobs.JobsUi
import com.nousresearch.hermes.android.jobs.LastRun
import com.nousresearch.hermes.android.jobs.RunOutcome
import com.nousresearch.hermes.android.jobs.RunsView
import com.nousresearch.hermes.android.jobs.runTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A job's Past runs (automation-07): offered in every job's ⋮ menu, a sheet listing the runs in the core's order with
 * each start, whether it still runs, its messages and its first prompt; reading, empty and failed told apart; a tap
 * opens a run, and nothing can be tapped while one opens.
 */
@RunWith(AndroidJUnit4::class)
class JobRunsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private val row = JobRowActions(pause = { calls += "pause:${it.value}" }, resume = { calls += "resume:${it.value}" },
                                    runNow = { calls += "run:${it.value}" }, askDelete = { calls += "ask:${it?.value}" },
                                    confirmDelete = { calls += "confirm" })
    private val runs = JobRunsActions(show = { calls += "runs:${it?.value}" }, open = { calls += "open:${it.value}" })

    private var state by mutableStateOf(JobsUi())

    private fun show(initial: JobsUi, withRuns: Boolean = true) {
        state = initial
        compose.setContent { screen(withRuns) }
    }

    @Composable
    private fun screen(withRuns: Boolean) {
        JobsScreen(state, {}, {}, actions = JobActions({ calls += "new" }, { calls += "edit:${it.value}" },
                                                       JobFormActions({}, {}), row = row, runs = runs.takeIf { withRuns }))
    }

    private val news = Job(JobId("a1b2"), "Morning news", "every 30m", "Summarise the news", JobState.SCHEDULED,
                           nextRunAt = "2026-11-14T09:30:00+01:00",
                           last = LastRun("2026-11-13T09:30:00+01:00", RunOutcome.OK, "ok", null), repeat = "forever")
    private val paused = news.copy(id = JobId("c3d4"), name = "Evening digest", state = JobState.PAUSED)
    private val done = news.copy(id = JobId("e5f6"), name = "Reminder", state = JobState.COMPLETED, repeat = "1/1")

    private val running = JobRun(StoredSessionId(RUNNING), 1794562200L, null, 3, "Summarise the news", active = true)
    private val ended = JobRun(StoredSessionId(ENDED), 1794475800L, 1794475920L, 4, null, active = false)
    private val unfinished = JobRun(StoredSessionId(UNFINISHED), 1794389400L, null, 1, "Summarise the news", active = false)
    private val three = listOf(running, ended, unfinished)

    private fun menuOf(id: String) = compose.onNodeWithTag("job-menu-$id").performClick()
    private fun item(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    @Test
    fun everyJobsMenuOffersPastRuns() {
        show(JobsUi(listOf(news, paused, done), gatewayRunning = true))

        for (id in listOf("c3d4", "e5f6", "a1b2")) {
            menuOf(id)
            item("job-runs").assertExists()
            item("job-runs").performClick()  // closes the menu, then shows the runs
            compose.waitForIdle()
        }

        assertEquals(listOf("runs:c3d4", "runs:e5f6", "runs:a1b2"), calls)
        item("job-runs").assertDoesNotExist()  // the menu has closed
        item("job-delete").assertDoesNotExist()
    }

    @Test
    fun withoutRunsActionsThereIsNoPastRuns() {
        show(JobsUi(listOf(news), gatewayRunning = true), withRuns = false)

        menuOf("a1b2")
        item("job-delete").assertExists()
        item("job-runs").assertDoesNotExist()
    }

    @Test
    fun theSheetListsTheRunsInTheCoresOrder() {
        show(JobsUi(listOf(news), runs = RunsView(news.id, three, loaded = true)))

        compose.onNodeWithTag("job-runs").assertExists()
        compose.onNodeWithText("Past runs of “Morning news”").assertExists()
        compose.onNode(hasTestTag("job-run-$RUNNING") and hasText(runTime(1794562200), substring = true)).assertExists()
        compose.onNode(hasTestTag("job-run-$RUNNING") and hasText("3 messages", substring = true)).assertExists()
        item("job-run-running-$RUNNING").assertTextEquals("Running")
        item("job-run-unfinished-$RUNNING").assertDoesNotExist()
        item("job-run-unfinished-$UNFINISHED").assertTextEquals("No end recorded")
        item("job-run-running-$UNFINISHED").assertDoesNotExist()
        item("job-run-running-$ENDED").assertDoesNotExist()
        item("job-run-unfinished-$ENDED").assertDoesNotExist()
        compose.onNode(hasTestTag("job-run-$ENDED") and hasText("4 messages", substring = true)).assertExists()
        compose.onNode(hasTestTag("job-run-$UNFINISHED") and hasText("1 message", substring = true)).assertExists()
        // The core's order: newest first, as given.
        val tops = listOf(RUNNING, ENDED, UNFINISHED).map { compose.onNodeWithTag("job-run-$it").fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)
        item("job-runs-empty").assertDoesNotExist()
        item("job-runs-reading").assertDoesNotExist()
    }

    @Test
    fun aTapOnARunOpensIt() {
        show(JobsUi(listOf(news), runs = RunsView(news.id, three, loaded = true)))

        compose.onNodeWithTag("job-run-$ENDED").performClick()

        assertEquals(listOf("open:$ENDED"), calls)
    }

    @Test
    fun whileReadingTheSheetSaysSo() {
        show(JobsUi(listOf(news), runs = RunsView(news.id)))

        item("job-runs-reading").assertTextEquals("Reading the runs…")
        item("job-runs-empty").assertDoesNotExist()
    }

    @Test
    fun aJobThatNeverRanSaysNoRunsYet() {
        show(JobsUi(listOf(news), runs = RunsView(news.id, emptyList(), loaded = true)))

        item("job-runs-empty").assertTextEquals("No runs yet")
        item("job-runs-reading").assertDoesNotExist()
    }

    @Test
    fun aRunThatCouldNotOpenShowsWhy() {
        show(JobsUi(listOf(news), runs = RunsView(news.id, emptyList(), loaded = true, error = "session not found")))

        item("job-runs-error").assertTextEquals("session not found")
        item("job-runs-empty").assertDoesNotExist()
    }

    @Test
    fun whileARunOpensNothingCanBeTapped() {
        show(JobsUi(listOf(news), busy = true, runs = RunsView(news.id, three, loaded = true)))

        compose.onNodeWithTag("job-run-$ENDED").assertIsNotEnabled()
        compose.onNodeWithTag("job-runs-close").assertIsNotEnabled()
        compose.onNodeWithTag("job-run-$ENDED").performClick()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun closeClosesTheSheet() {
        show(JobsUi(listOf(news), runs = RunsView(news.id, three, loaded = true)))

        compose.onNodeWithTag("job-runs-close").performClick()

        assertEquals(listOf("runs:null"), calls)
    }

    private companion object {
        const val RUNNING = "cron_a1b2_20261113_093000"
        const val ENDED = "cron_a1b2_20261112_093000"
        const val UNFINISHED = "cron_a1b2_20261111_093000"
    }
}
