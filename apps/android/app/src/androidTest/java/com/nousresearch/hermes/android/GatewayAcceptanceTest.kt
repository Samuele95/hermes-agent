package com.nousresearch.hermes.android

import android.util.Log
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermes.android.chat.Entry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The CP6g acceptance's setup step (`scripts/gateway_acceptance.sh`): on a clean install, the endpoint set up
 * on the screen, then a job the gateway will run every two minutes, scheduled the way a user would, in a chat
 * turn. The gateway itself is turned on afterwards from the app's own screen, outside the instrumentation
 * (its end stops the package, gateway included).
 */
@Smoke
@RunWith(AndroidJUnit4::class)
class GatewayAcceptanceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    private val steps = SmokeSteps(compose)

    @Before
    fun allowNotifications() = steps.allowNotifications()

    @Test
    fun aJobScheduledInAChatTurn() {
        steps.setUpEndpoint()

        val turn = steps.say("Use the cronjob tool to create a job named gateway-check with the schedule \"every 2m\" " +
                             "and the prompt: Reply with the single word tick.")

        assertTrue("the cronjob tool ran: $turn", turn.any { it is Entry.Tool && it.name.startsWith("cronjob") })
        val jobs = File(steps.app.filesDir, ".hermes/cron/jobs.json").readText()
        Log.i(SmokeSteps.TAG, "jobs $jobs")
        assertTrue("a job is stored: $jobs", jobs.contains("gateway-check"))
    }
}
