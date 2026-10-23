package com.nousresearch.hermes.android

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The phone's Telegram bot set up through the app's own core (D11, D24): this build carries the Telegram
 * library, the allowed user lands in config.yaml, and a token Telegram does not accept is gone again, from
 * the keystore, from every file of the app, and from the log.
 */
@RunWith(AndroidJUnit4::class)
class TelegramSetupDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication
    private val token = "123456:hermes-device-test-not-a-real-token"

    @Test
    fun aRefusedTokenLeavesNoTraceAndTheAllowedUserIsKept() = runBlocking {
        val telegram = (app.core.first { it !is CoreState.Starting } as CoreState.Ready).telegram
        telegram.open()
        assertTrue("this build carries python-telegram-bot", telegram.state.value!!.sdk)

        telegram.save(token.toByteArray(), "42")

        val state = telegram.state.value!!
        assertNotNull("Telegram refused the made-up token (or could not be reached)", state.error)
        assertFalse(state.tokenHeld)
        assertNull(app.secrets.get("TELEGRAM_BOT_TOKEN"))
        assertEquals("42", state.allowedUser)
        assertTrue(File(app.filesDir, ".hermes/config.yaml").readText().contains("allow_from"))
        val files = app.filesDir.walk().filter { it.isFile } + app.noBackupFilesDir.resolve("secrets").walk().filter { it.isFile }
        assertTrue("the token is in no file", files.none { it.readBytes().decodeToString().contains(token) })
        assertFalse("the token is not in the log", logcat().contains(token))
        telegram.close()
    }

    private fun logcat(): String {
        SystemClock.sleep(500)
        val out = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("logcat -d")
        return ParcelFileDescriptor.AutoCloseInputStream(out).bufferedReader().use { it.readText() }
    }
}
