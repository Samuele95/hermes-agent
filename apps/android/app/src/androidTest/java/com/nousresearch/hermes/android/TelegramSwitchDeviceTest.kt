package com.nousresearch.hermes.android

import android.app.ActivityManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.gateway.GatewayStatus
import com.nousresearch.hermes.android.gateway.PlatformState
import com.nousresearch.hermes.android.gateway.observeGateway
import com.nousresearch.hermes.bridge.EmbeddedBridge
import com.nousresearch.hermes.bridge.ask
import com.nousresearch.hermes.bridge.contract.BridgeCalls
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D40 against the phone's own core, through the app's composed Telegram setup: the bot turns on only with a token,
 * and removing it leaves config.yaml with `enabled: false`, never `true` without a token (which would stop the whole
 * gateway at its next start). Nothing here starts, stops or restarts the gateway; config.yaml is put back as it was.
 */
@RunWith(AndroidJUnit4::class)
class TelegramSwitchDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication
    private val token = "123456:hermes-j9-device-test-not-a-real-token"

    @Test
    fun theBotTurnsOnOnlyWithATokenAndRemovingItLeavesItOff() = runBlocking {
        assumeFalse("the phone holds a bot token; this test never touches it", app.secrets.contains("TELEGRAM_BOT_TOKEN"))
        val telegram = (app.core.first { it !is CoreState.Starting } as CoreState.Ready).telegram
        val config = File(app.filesDir, ".hermes/config.yaml")
        val original = config.takeIf { it.exists() }?.readBytes()
        val was = EmbeddedBridge.ask(BridgeCalls.TELEGRAM_STATUS)
        Log.i("J9Probe", "telegram_status: token=${was.token} enabled=${was.enabled} " +
            "live=${was.live?.let { "state=${it.state} writerPid=${it.writerPid} updatedAt=${it.updatedAt}" }}")
        Log.i("J9Probe", "observeGateway: ${observeGateway(app)}")
        val gatewayPid = app.getSystemService(ActivityManager::class.java).runningAppProcesses
            ?.firstOrNull { it.processName == "${app.packageName}:gateway" }?.pid
        Log.i("J9Probe", ":gateway pid=$gatewayPid")
        Log.i("J9Probe", "config.yaml had platforms.telegram.enabled before: " +
            "${original?.decodeToString()?.contains(Regex("(?m)^  telegram:\\n(?:    .*\\n)*?    enabled:"))}")
        try {
            // 1. No token: nothing is sent, nothing written.
            telegram.open()
            telegram.setEnabled(true)
            assertEquals(false, telegram.state.value!!.tokenHeld)
            assertTrue("switching on without a token wrote config.yaml (D40)",
                       (config.takeIf { it.exists() }?.readBytes() ?: ByteArray(0)).contentEquals(original ?: ByteArray(0)))

            // 2. A made-up token: the switch can turn on.
            app.secrets.put("TELEGRAM_BOT_TOKEN", token.toByteArray())
            EmbeddedBridge.secretsChanged()
            telegram.open()
            assertEquals(true, telegram.state.value!!.tokenHeld)
            val user = telegram.state.value!!.allowedUser

            // 3. On, as the core reads it.
            telegram.setEnabled(true)
            assertEquals(true, EmbeddedBridge.ask(BridgeCalls.TELEGRAM_STATUS).enabled)

            // 4. Bet 7 on the phone: a leftover entry from an earlier process is not believed.
            val seen = observeGateway(app)
            if (seen.status != GatewayStatus.RUNNING) {
                assertEquals(PlatformState.GATEWAY_OFF, telegram.state.value!!.live!!.state)
            } else {
                Log.i("J9Probe", "gateway running ($seen): live=${telegram.state.value!!.live}, not asserted")
            }

            // 5. Remove: switched off, the token gone, the user kept.
            telegram.askRemove(true)
            telegram.confirmRemove()
            assertNull(telegram.state.value!!.error)
            assertNull(app.secrets.get("TELEGRAM_BOT_TOKEN"))
            val after = EmbeddedBridge.ask(BridgeCalls.TELEGRAM_STATUS)
            assertEquals(false, after.token)
            assertEquals("Remove left enabled: true behind (D40)", false, after.enabled)
            val text = config.readText()
            assertTrue("Remove left enabled: true behind (D40)", text.contains("enabled: false"))
            assertFalse("Remove left enabled: true behind (D40)",
                        Regex("(?m)^  telegram:\\n(?:    .*\\n)*?    enabled: true\\b").containsMatchIn(text))
            assertEquals(user, after.allowedUser)
            assertEquals(PlatformState.DISABLED, telegram.state.value!!.live!!.state)
            assertEquals(false, telegram.state.value!!.tokenHeld)

            // 6. The made-up token is in no file.
            val files = app.filesDir.walk().filter { it.isFile } + app.noBackupFilesDir.resolve("secrets").walk().filter { it.isFile }
            assertTrue("the token is in no file", files.none { it.readBytes().decodeToString().contains(token) })
        } finally {
            // Only this test put a token: the guard proved none was held.
            if (app.secrets.contains("TELEGRAM_BOT_TOKEN")) {
                app.secrets.delete("TELEGRAM_BOT_TOKEN")
                EmbeddedBridge.secretsChanged()
            }
            // The shared phone never keeps `enabled: true` with no token (device.md 6).
            if (original != null) config.writeBytes(original) else config.delete()
            if (original != null) assertArrayEquals(original, config.readBytes()) else assertFalse(config.exists())
            telegram.close()
        }
    }
}
