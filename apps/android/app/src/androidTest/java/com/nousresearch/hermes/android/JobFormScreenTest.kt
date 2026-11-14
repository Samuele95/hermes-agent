package com.nousresearch.hermes.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermes.android.jobs.Delivery
import com.nousresearch.hermes.android.jobs.DeliveryTarget
import com.nousresearch.hermes.android.jobs.Job
import com.nousresearch.hermes.android.jobs.JobActions
import com.nousresearch.hermes.android.jobs.JobActivity
import com.nousresearch.hermes.android.jobs.JobForm
import com.nousresearch.hermes.android.jobs.JobFormActions
import com.nousresearch.hermes.android.jobs.JobId
import com.nousresearch.hermes.android.jobs.JobState
import com.nousresearch.hermes.android.jobs.JobsScreen
import com.nousresearch.hermes.android.jobs.JobsUi
import com.nousresearch.hermes.android.jobs.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The job form sheet: presets in the core's grammar, the delivery targets with their home flag, an edit that keeps what it did not touch. */
@RunWith(AndroidJUnit4::class)
class JobFormScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private var saved: JobForm? = null

    private val local = DeliveryTarget(Delivery.Local, "local", homeSet = true)
    private val telegramNoHome = DeliveryTarget(Delivery.Platform("telegram"), "Telegram", homeSet = false)

    private fun job(schedule: String = "every 120m", prompt: String = "Summarise the news", delivery: Delivery = Delivery.Local,
                    repeat: String = "forever") =
        Job(JobId("a1b2"), "Morning news", schedule, prompt, JobState.SCHEDULED, nextRunAt = null, last = null, repeat = repeat,
            delivery = delivery)

    private fun show(form: JobForm, jobs: List<Job> = emptyList(), busy: Boolean = false, error: String? = null) = compose.setContent {
        JobsScreen(JobsUi(jobs, activity = if (busy) JobActivity.Saving else JobActivity.Idle, error = error, form = form,
                          targets = listOf(local, telegramNoHome)),
                   onOpenGateway = {}, onClose = { calls += "close" },
                   actions = JobActions(newJob = { calls += "new" }, edit = { calls += "edit:${it.value}" },
                                        form = JobFormActions(save = { saved = it }, cancel = { calls += "cancel" })))
    }

    private fun tag(t: String) = compose.onNodeWithTag(t).performScrollTo()

    private fun save(): JobForm {
        tag("job-form-save").assertIsEnabled().performClick()
        return checkNotNull(saved).also { saved = null }
    }

    @Test
    fun aNewJobIsDailyAtNineDeliveredLocallyUntilChanged() {
        show(JobForm.blank())

        compose.onNodeWithTag("job-form").assertExists()
        tag("job-schedule-daily").assertIsSelected()
        tag("job-deliver-local").assertIsSelected()
        tag("job-form-save").assertIsNotEnabled()

        tag("job-form-prompt").performTextInput("Summarise the news")

        assertEquals(JobForm(null, "", "Summarise the news", Schedule.Daily(9, 0), Delivery.Local, null), save())
    }

    @Test
    fun eachPresetMakesItsSchedule() {
        show(JobForm.blank())
        tag("job-form-prompt").performTextInput("Say hello.")

        tag("job-schedule-weekdays").performClick()
        tag("job-form-hour").performTextReplacement("7")
        tag("job-form-minute").performTextReplacement("45")
        assertEquals(Schedule.Weekdays(7, 45), save().schedule)

        tag("job-schedule-every").performClick()
        tag("job-form-minutes").performTextReplacement("15")
        assertEquals(Schedule.Every(15), save().schedule)

        tag("job-schedule-once").performClick()
        tag("job-form-minutes").performTextReplacement("45")
        compose.onNodeWithTag("job-form-repeat").assertDoesNotExist()
        save().let {
            assertEquals(Schedule.Once(45), it.schedule)
            assertEquals(1, it.repeat)
        }

        tag("job-schedule-custom").performClick()
        tag("job-form-custom").performTextInput("every monday 9am")
        assertEquals(Schedule.Custom("every monday 9am"), save().schedule)
    }

    @Test
    fun anIncompleteScheduleCannotBeSaved() {
        show(JobForm.blank())
        tag("job-form-prompt").performTextInput("Say hello.")
        tag("job-form-save").assertIsEnabled()

        tag("job-schedule-every").performClick()
        tag("job-form-minutes").performTextReplacement("")
        tag("job-form-save").assertIsNotEnabled()

        tag("job-schedule-daily").performClick()
        tag("job-form-hour").performTextReplacement("24")
        tag("job-form-save").assertIsNotEnabled()
    }

    @Test
    fun aWeekOfMinutesIsTypedInFull() {
        show(JobForm.blank())
        tag("job-form-prompt").performTextInput("Say hello.")

        tag("job-schedule-every").performClick()
        tag("job-form-minutes").performTextReplacement("10080")
        tag("job-form-minutes").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("10080")))
        tag("job-form-repeat").performTextReplacement("10000")
        save().let {
            assertEquals(Schedule.Every(10080), it.schedule)  // not every 1008m (/improve 20261113T221531Z #1)
            assertEquals(10000, it.repeat)
        }

        tag("job-schedule-once").performClick()
        tag("job-form-minutes").performTextReplacement("10080")
        assertEquals(Schedule.Once(10080), save().schedule)
    }

    @Test
    fun aCountPastItsIntIsRedInFullAndCannotBeSaved() {
        val red = SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)
        val plain = SemanticsMatcher.keyNotDefined(SemanticsProperties.Error)
        show(JobForm.blank())
        tag("job-form-prompt").performTextInput("Say hello.")
        tag("job-schedule-every").performClick()

        tag("job-form-minutes").performTextReplacement("2147483648")
        tag("job-form-minutes").assert(red)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("2147483648")))  // never cut
        tag("job-form-save").assertIsNotEnabled()
        tag("job-form-minutes").performTextReplacement("2147483647")
        tag("job-form-minutes").assert(plain)
        tag("job-form-save").assertIsEnabled()

        tag("job-form-repeat").assert(plain).performTextReplacement("2147483648")
        tag("job-form-repeat").assert(red)
        tag("job-form-save").assertIsNotEnabled()  // it would be saved as no limit

        tag("job-schedule-once").performClick()  // a one-shot runs once: the hidden field blocks nothing
        compose.onNodeWithTag("job-form-repeat").assertDoesNotExist()
        save().let {
            assertEquals(Schedule.Once(Int.MAX_VALUE), it.schedule)
            assertEquals(1, it.repeat)
        }

        tag("job-schedule-every").performClick()
        tag("job-form-save").assertIsNotEnabled()  // the red count is still there
        tag("job-form-repeat").performTextReplacement("")
        tag("job-form-repeat").assert(plain)
        assertEquals(Schedule.Every(Int.MAX_VALUE), save().schedule)
    }

    @Test
    fun aPlatformWithoutAHomeChatIsFlaggedAndStillChosen() {
        show(JobForm.blank())
        tag("job-form-prompt").performTextInput("Say hello.")

        // Its second line, inside the row's merged semantics.
        compose.onNodeWithTag("job-deliver-telegram-no-home", useUnmergedTree = true).assertExists()
        tag("job-deliver-telegram").performClick()
        tag("job-deliver-telegram").assertIsSelected()

        assertEquals(Delivery.Platform("telegram"), save().delivery)
    }

    @Test
    fun anEditKeepsTheScheduleUntilAPresetIsPicked() {
        val oneShot = job(schedule = "once in 45m", repeat = "once")
        show(JobForm.of(oneShot), listOf(oneShot))

        tag("job-schedule-keep").assertIsSelected().assertTextContains("once in 45m", substring = true)
        tag("job-form-save").assertIsNotEnabled()  // nothing changed
        compose.onNodeWithTag("job-form-repeat").assertDoesNotExist()  // a one-shot runs once

        tag("job-form-prompt").performTextReplacement("Say goodbye.")

        save().let {
            assertNull(it.schedule)
            assertEquals("Say goodbye.", it.prompt)
            assertEquals(JobId("a1b2"), it.id)
        }
    }

    @Test
    fun anAgentsDeliveryIsOfferedAsKept() {
        val agents = job(delivery = Delivery.Other("origin"))
        show(JobForm.of(agents), listOf(agents))

        tag("job-deliver-keep").assertIsSelected().assertTextContains("origin", substring = true)
    }

    @Test
    fun aShortenedPromptStartsEmpty() {
        val long = job(prompt = "x".repeat(100) + "...")
        show(JobForm.of(long), listOf(long))

        tag("job-form-prompt-was").assertExists()
        tag("job-form-prompt").assertTextContains("New prompt", substring = true)  // its label
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))  // and no text
        assertEquals("", JobForm.of(long).prompt)
        tag("job-form-save").assertIsNotEnabled()
    }

    @Test
    fun theCoresRefusalShowsInTheSheet() {
        show(JobForm.blank(), error = "Invalid duration: 'banana'.")

        tag("job-form-error").assertTextContains("Invalid duration: 'banana'.")
    }

    @Test
    fun whileSavingNothingCanBeTapped() {
        val j = job()
        show(JobForm.of(j).copy(prompt = "changed"), listOf(j), busy = true)

        tag("job-form-save").assertIsNotEnabled()
        tag("job-form-cancel").assertIsNotEnabled()
        tag("job-form-prompt").assertIsNotEnabled()
    }

    @Test
    fun cancelCloses() {
        show(JobForm.blank())

        tag("job-form-cancel").performClick()

        assertEquals(listOf("cancel"), calls)
    }
}
