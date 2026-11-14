package com.nousresearch.hermes.android.jobs

import com.nousresearch.hermes.bridge.contract.CronDeliveryTargetsReply
import com.nousresearch.hermes.bridge.contract.CronUpdateParams
import com.nousresearch.hermes.bridge.protocol.CronAction
import com.nousresearch.hermes.bridge.protocol.CronJobRow
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.nousresearch.hermes.bridge.contract.DeliveryTarget as TargetRow

/** The job form's values: presets in the core's grammar, delivery in the core's words, an edit of only what changed (D39 F6, F10). */
class JobFormTest {
    private fun job(schedule: String = "every 30m", prompt: String = "Summarise the news", repeat: String? = "forever",
                    delivery: Delivery = Delivery.Local) =
        Job(JobId("j1"), "Morning news", schedule, prompt, JobState.SCHEDULED, nextRunAt = null, last = null, repeat = repeat,
            delivery = delivery)

    private fun none(id: String = "j1") = CronUpdateParams(id, null, null, null, null, null)

    @Test
    fun eachPresetRendersTheCoresGrammar() {
        // The same literals as tests/android/test_hermes_android_cron.py, where the core parses them.
        assertEquals("every 30m", Schedule.Every(30).wire())
        assertEquals("30 9 * * *", Schedule.Daily(9, 30).wire())
        assertEquals("5 7 * * *", Schedule.Daily(7, 5).wire())
        assertEquals("30 9 * * 1-5", Schedule.Weekdays(9, 30).wire())
        assertEquals("in 45m", Schedule.Once(45).wire())
        assertEquals("every monday 9am", Schedule.Custom("  every monday 9am ").wire())
    }

    @Test
    fun aDraftMakesAScheduleOnlyFromWholeFields() {
        assertEquals(Schedule.Daily(9, 0), ScheduleDraft().schedule)

        val every = ScheduleDraft(SchedulePreset.EVERY)
        assertNull(every.copy(minutes = "").schedule)
        assertNull(every.copy(minutes = "0").schedule)  // the core would take every 0m (V2)
        assertNull(every.copy(minutes = "x").schedule)
        assertEquals(Schedule.Every(15), every.copy(minutes = "15").schedule)

        assertNull(ScheduleDraft(SchedulePreset.DAILY, hour = "24").schedule)
        assertNull(ScheduleDraft(SchedulePreset.DAILY, minute = "60").schedule)
        assertEquals(Schedule.Daily(23, 59), ScheduleDraft(SchedulePreset.DAILY, hour = "23", minute = "59").schedule)
        assertEquals(Schedule.Weekdays(7, 45), ScheduleDraft(SchedulePreset.WEEKDAYS, hour = "7", minute = "45").schedule)
        assertEquals(Schedule.Once(45), ScheduleDraft(SchedulePreset.ONCE, minutes = "45").schedule)
        assertNull(ScheduleDraft(SchedulePreset.ONCE, minutes = "0").schedule)
        assertNull(ScheduleDraft(SchedulePreset.CUSTOM, text = "  ").schedule)
        assertEquals(Schedule.Custom("every 2h"), ScheduleDraft(SchedulePreset.CUSTOM, text = " every 2h ").schedule)
    }

    @Test
    fun aWeekOfMinutesTypedInTheEveryFieldIsEveryTenThousandEighty() {
        val typed = DigitField.COUNT.keep("10080")
        assertEquals("10080", typed)  // never cut to four digits (/improve 20261113T221531Z #1)
        val week = ScheduleDraft(SchedulePreset.EVERY, minutes = typed).schedule
        assertEquals(Schedule.Every(10080), week)
        assertEquals("every 10080m", week!!.wire())  // the core's own display of a weekly job (J5)
        assertEquals(Schedule.Once(10080), ScheduleDraft(SchedulePreset.ONCE, minutes = typed).schedule)
        assertEquals("every 10080m", JobForm.blank().copy(prompt = "Say hello.", schedule = week).addParams()!!.schedule)
    }

    @Test
    fun aCountIsBoundOnlyByItsInt() {
        // The core's grammar bounds no count (cron/jobs.py parse_duration's \d*; every/in 2147483647m parse): Int is the only bound.
        val every = ScheduleDraft(SchedulePreset.EVERY)
        assertEquals(Schedule.Every(Int.MAX_VALUE), every.copy(minutes = "2147483647").schedule)
        assertNull(every.copy(minutes = "2147483648").schedule)
        assertNull(every.copy(minutes = DigitField.COUNT.keep("99999999999")).schedule)
        assertNull(ScheduleDraft(SchedulePreset.ONCE, minutes = "2147483648").schedule)

        assertTrue(repeatFits(""))
        assertTrue(repeatFits(" "))
        assertTrue(repeatFits("0"))  // none, as blank
        assertTrue(repeatFits(DigitField.COUNT.keep("10000")))
        assertEquals(10000, repeatOf(Schedule.Every(5), "10000"))
        assertTrue(repeatFits("2147483647"))
        assertFalse(repeatFits("2147483648"))
        assertNull(repeatOf(Schedule.Every(5), "2147483648"))  // read as no limit: why the form must not send it
    }

    @Test
    fun aClockKeepsTwoDigitsAndACountAllOfThem() {
        assertEquals("12", DigitField.CLOCK.keep("123"))
        assertEquals("9", DigitField.CLOCK.keep("9h"))
        assertEquals("123456", DigitField.COUNT.keep("12a 3,456"))
    }

    @Test
    fun deliveryReadsTheCoresWords() {
        for (local in listOf(null, "", "local", " local ")) assertEquals(Delivery.Local, Delivery.parse(local))
        assertEquals(Delivery.Platform("telegram"), Delivery.parse("telegram"))
        for (raw in listOf("origin", "telegram:-100123", "local,telegram", "bot-chat:default")) {
            assertEquals(Delivery.Other(raw), Delivery.parse(raw))
        }
        for (raw in listOf("local", "telegram", "origin", "telegram:-100123", "local,telegram", "bot-chat:default")) {
            val d = Delivery.parse(raw)
            assertEquals(raw, d.wire())
            assertEquals(d, Delivery.parse(d.wire()))
        }
    }

    @Test
    fun theTargetsAreLocalThenTheCoresWithTheirHomeFlag() {
        val local = DeliveryTarget(Delivery.Local, "local", homeSet = true)

        assertEquals(listOf(local, DeliveryTarget(Delivery.Platform("telegram"), "Telegram", homeSet = false)),
                     deliveryTargets(CronDeliveryTargetsReply(listOf(TargetRow("telegram", "Telegram", homeSet = false)))))
        assertEquals(listOf(local), deliveryTargets(null))
        assertEquals(listOf(local, DeliveryTarget(Delivery.Platform("discord"), "Discord", homeSet = true)),
                     deliveryTargets(CronDeliveryTargetsReply(listOf(TargetRow("local", "Local", homeSet = true),
                                                                     TargetRow("discord", "Discord", homeSet = true)))))
    }

    @Test
    fun aRowsDeliveryAndRepeatAreRead() {
        assertEquals(Delivery.Other("origin"), Job.of(CronJobRow(jobId = "j", deliver = "origin"))?.delivery)
        assertEquals(Delivery.Local, Job.of(CronJobRow(jobId = "j"))?.delivery)
        assertEquals(Delivery.Platform("telegram"), Job.of(CronJobRow(jobId = "j", deliver = "telegram"))?.delivery)

        fun timesOf(repeat: String?) = Job.of(CronJobRow(jobId = "j", repeat = repeat?.let(::JsonPrimitive)))?.times
        assertNull(timesOf("forever"))
        assertEquals(1, timesOf("once"))
        assertEquals(1, timesOf("1/1"))
        assertEquals(3, timesOf("3 times"))
        assertEquals(5, timesOf("2/5"))
        assertNull(timesOf("soon"))
        assertNull(timesOf(null))
    }

    @Test
    fun aNewFormAddsInTheCoresWords() {
        val form = JobForm(null, " News ", " Summarise the news ", Schedule.Weekdays(9, 30), Delivery.Local, null)
        val params = form.addParams()!!

        assertEquals(CronAction.ADD, params.action)
        assertEquals("News", params.name)
        assertEquals("30 9 * * 1-5", params.schedule)
        assertEquals("Summarise the news", params.prompt)
        assertEquals("local", params.deliver)
        assertNull(params.repeat)

        assertNull(form.copy(name = "  ").addParams()!!.name)
        assertEquals(JsonPrimitive(3), form.copy(repeat = 3).addParams()!!.repeat)
        assertEquals("telegram", form.copy(delivery = Delivery.Platform("telegram")).addParams()!!.deliver)
        assertNull(form.copy(schedule = null).addParams())
        assertNull(form.copy(prompt = "  ").addParams())
    }

    @Test
    fun anUnchangedEditHasNothingToSave() {
        for (j in listOf(job(), job(delivery = Delivery.Other("origin"), repeat = "2/5"), job(repeat = "once"))) {
            assertNull(JobForm.of(j).changesFrom(j))
        }
    }

    @Test
    fun anEditSendsOnlyWhatChangedByTheJobsId() {
        val forever = job()
        val form = JobForm.of(forever)

        assertEquals(none().copy(prompt = "new"), form.copy(prompt = " new ").changesFrom(forever))
        assertEquals(none().copy(name = "Evening news"), form.copy(name = "Evening news ").changesFrom(forever))
        assertNull(form.copy(name = "  ").changesFrom(forever))
        assertEquals(none().copy(schedule = "every 15m"), form.copy(schedule = Schedule.Every(15)).changesFrom(forever))
        assertEquals(none().copy(deliver = "telegram"), form.copy(delivery = Delivery.Platform("telegram")).changesFrom(forever))
        assertEquals(none().copy(repeat = 5), form.copy(repeat = 5).changesFrom(forever))

        val limited = job(repeat = "2/5")
        assertEquals(none().copy(repeat = 0), JobForm.of(limited).copy(repeat = null).changesFrom(limited))  // 0: forever

        // The id, never the name, even when the name changes (invariant 2).
        assertEquals("j1", form.copy(name = "Other").changesFrom(forever)?.jobId)
    }

    @Test
    fun anUntouchedScheduleIsNeverResent() {
        val oneShot = job(schedule = "once in 45m", repeat = "once")

        val changes = JobForm.of(oneShot).copy(prompt = "Say goodbye.").changesFrom(oneShot)!!

        assertNull(changes.schedule)
        assertEquals("Say goodbye.", changes.prompt)
    }

    @Test
    fun anAgentsDeliveryIsKeptOnEdit() {
        val chat = job(delivery = Delivery.Other("telegram:-100123"))

        val form = JobForm.of(chat)
        val changes = form.copy(prompt = "new").changesFrom(chat)!!

        assertEquals(Delivery.Other("telegram:-100123"), form.delivery)
        assertNull(changes.deliver)
    }

    @Test
    fun aShortenedPromptIsNotSentBack() {
        val cut = job(prompt = "x".repeat(100) + "...")

        assertTrue(cut.promptCut)
        assertEquals("", JobForm.of(cut).prompt)
        assertNull(JobForm.of(cut).changesFrom(cut))
        assertEquals(none().copy(prompt = "A whole new prompt"), JobForm.of(cut).copy(prompt = "A whole new prompt").changesFrom(cut))

        val short = job(prompt = "y".repeat(40))
        assertFalse(short.promptCut)
        assertEquals("y".repeat(40), JobForm.of(short).prompt)
        assertFalse(job(prompt = "z".repeat(103)).promptCut)
    }

    @Test
    fun onceRunsOnceAndABlankLimitIsNone() {
        assertEquals(1, repeatOf(Schedule.Once(5), ""))
        assertEquals(1, repeatOf(Schedule.Once(5), "4"))
        assertNull(repeatOf(Schedule.Every(5), ""))
        assertNull(repeatOf(Schedule.Every(5), "0"))
        assertNull(repeatOf(null, " "))
        assertEquals(4, repeatOf(Schedule.Every(5), " 4 "))
        assertEquals(4, repeatOf(null, "4"))
    }

    @Test
    fun canSaveFollowsTheRules() {
        val blank = JobForm.blank()
        assertEquals(JobForm(null, "", "", Schedule.Daily(9, 0), Delivery.Local, null), blank)
        assertFalse(blank.canSave(null))
        assertTrue(blank.copy(prompt = "Say hello.").canSave(null))
        assertFalse(blank.copy(prompt = "Say hello.", schedule = null).canSave(null))

        val j = job()
        assertFalse(JobForm.of(j).canSave(j))
        assertTrue(JobForm.of(j).copy(prompt = "new").canSave(j))
        assertFalse(JobForm.of(j).copy(prompt = "new").canSave(null))
    }
}
