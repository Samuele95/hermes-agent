package com.nousresearch.hermes.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermes.android.gateway.GatewayStatus
import com.nousresearch.hermes.android.jobs.Job
import com.nousresearch.hermes.android.jobs.JobActions
import com.nousresearch.hermes.android.jobs.JobFormActions
import com.nousresearch.hermes.android.jobs.JobId
import com.nousresearch.hermes.android.jobs.JobState
import com.nousresearch.hermes.android.jobs.JobsScreen
import com.nousresearch.hermes.android.jobs.JobsUi
import com.nousresearch.hermes.android.jobs.JobsWarning
import com.nousresearch.hermes.android.jobs.LastRun
import com.nousresearch.hermes.android.jobs.RunOutcome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Scheduled jobs page shows what the core lists, says why the jobs will not run, and leads to the gateway. */
@RunWith(AndroidJUnit4::class)
class JobsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show(state: JobsUi) = compose.setContent {
        JobsScreen(state, onOpenGateway = { calls += "gateway" }, onClose = { calls += "close" })
    }

    private val news = Job(JobId("a1b2"), "Morning news", "every 30m", "Summarise the news", JobState.SCHEDULED,
                           nextRunAt = "2026-11-14T09:30:00+01:00",
                           last = LastRun("2026-11-13T09:30:00+01:00", RunOutcome.FAILED, "error", "model timed out"),
                           repeat = "forever")

    @Test
    fun aJobShowsItsScheduleStateNextRunAndLastError() {
        show(JobsUi(listOf(news)))

        compose.onNodeWithTag("job-a1b2").assertExists()
        compose.onNodeWithText("Morning news").assertExists()
        compose.onNodeWithText("every 30m", substring = true).assertExists()
        compose.onNodeWithText("repeats forever", substring = true).assertExists()
        compose.onNodeWithTag("job-state-a1b2").assertTextEquals("Scheduled")
        compose.onNodeWithText("Next: 2026-11-14 09:30", substring = true).assertExists()
        compose.onNodeWithText("Last: 2026-11-13 09:30 · failed", substring = true).assertExists()
        compose.onNodeWithTag("job-error-a1b2").assertTextEquals("model timed out")
    }

    @Test
    fun aPausedJobShowsNoNextRun() {
        show(JobsUi(listOf(news.copy(state = JobState.PAUSED, last = null))))

        compose.onNodeWithTag("job-state-a1b2").assertTextEquals("Paused")
        compose.onNodeWithText("Next:", substring = true).assertDoesNotExist()
    }

    @Test
    fun theGatewayOffWarningLeadsToTheGatewayPage() {
        show(JobsUi(listOf(news), warning = JobsWarning.GatewayOff(GatewayStatus.STOPPED)))

        compose.onNodeWithTag("jobs-warning-gateway-off").assertExists()
        compose.onNodeWithTag("jobs-open-gateway").performClick()

        assertEquals(listOf("gateway"), calls)
    }

    @Test
    fun aStartingGatewayOffersNoShortcut() {
        show(JobsUi(listOf(news), warning = JobsWarning.GatewayOff(GatewayStatus.STARTING)))

        compose.onNodeWithTag("jobs-warning-starting").assertExists()
        compose.onNodeWithTag("jobs-open-gateway").assertDoesNotExist()
    }

    @Test
    fun aStalledSchedulerSaysSinceWhenAndItsError() {
        show(JobsUi(listOf(news), warning = JobsWarning.NotTicking(12, "boom")))

        compose.onNodeWithTag("jobs-warning-not-ticking").assertTextContains("12 min ago", substring = true)
        compose.onNodeWithTag("jobs-ticker-error").assertTextContains("boom", substring = true)
        compose.onNodeWithTag("jobs-open-gateway").assertDoesNotExist()
    }

    @Test
    fun noJobsSaysHowToGetOneAndWarnsOfNothing() {
        var state by mutableStateOf(JobsUi(busy = true))
        compose.setContent { JobsScreen(state, onOpenGateway = {}, onClose = {}) }

        compose.onNodeWithTag("jobs-empty").assertDoesNotExist()  // the list is on its way

        state = JobsUi()

        compose.onNodeWithTag("jobs-empty").assertExists()
        compose.onNodeWithTag("jobs-warning").assertDoesNotExist()
    }

    @Test
    fun aRefusalShowsTheCoresWords() {
        show(JobsUi(error = "cron store unreadable"))

        compose.onNodeWithTag("jobs-error").assertTextEquals("cron store unreadable")
        compose.onNodeWithTag("jobs-empty").assertDoesNotExist()
    }

    @Test
    fun newJobAndATapOnARowAreTheFormsWaysIn() {
        compose.setContent {
            JobsScreen(JobsUi(listOf(news)), onOpenGateway = {}, onClose = {},
                       actions = JobActions(newJob = { calls += "new" }, edit = { calls += "edit:${it.value}" },
                                            form = JobFormActions(save = {}, cancel = {})))
        }

        compose.onNodeWithTag("jobs-new").performClick()
        compose.onNodeWithTag("job-a1b2").performClick()

        assertEquals(listOf("new", "edit:a1b2"), calls)
    }

    @Test
    fun withoutActionsThereIsNoNewJob() {
        show(JobsUi(listOf(news)))

        compose.onNodeWithTag("jobs-new").assertDoesNotExist()
    }

    @Test
    fun backCloses() {
        show(JobsUi())

        compose.onNodeWithTag("jobs-close").performClick()

        assertEquals(listOf("close"), calls)
    }
}
