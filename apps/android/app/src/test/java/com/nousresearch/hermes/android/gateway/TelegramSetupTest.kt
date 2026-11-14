package com.nousresearch.hermes.android.gateway

import com.nousresearch.hermes.android.chat.FakeCoreGateway
import com.nousresearch.hermes.bridge.SecretStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's Telegram bot (D11, D24): token in the keystore, one allowed user, checked with Telegram. */
@OptIn(ExperimentalCoroutinesApi::class)
class TelegramSetupTest {
    private var seenNow = GatewaySeen(GatewayStatus.STOPPED)
    private val timeline = mutableListOf<String>()  // every bridge call and keystore delete, in order
    private val held = mutableMapOf<String, ByteArray>()
    private val handedOut = mutableListOf<ByteArray>()  // every plaintext the store gave out
    private val secrets = object : SecretStore {
        override fun put(name: String, value: ByteArray) { held[name] = value.copyOf() }
        override fun get(name: String) = held[name]?.copyOf()?.also { handedOut += it }  // a fresh copy, as the keystore's
        override fun delete(name: String) { timeline += "delete $name"; held.remove(name) }
        override fun all(): Map<String, ByteArray> = held.toMap()
    }
    private var secretsChanged = 0
    private var enableFails: Exception? = null  // thrown by the bridge on telegram_enable, after the call is recorded
    private val calls = mutableListOf<Pair<String, String>>()
    private val answers = mutableMapOf(
        "telegram_status" to """{"token":false,"allowed_user":null,"sdk":true,"enabled":true,"live":null}""",
        "telegram_configure" to """{"allowed_user":"42"}""",
        "telegram_check" to """{"check":"ok","bot":"@phone_hermes_bot","message":null}""",
    )
    private val telegram = TelegramSetup(secrets, seen = { seenNow }, bridge = { function, arg ->
        timeline += function
        if (function == "secrets_changed") { secretsChanged++; return@TelegramSetup "{}" }
        calls += function to arg
        if (function == "telegram_enable") enableFails?.let { throw it }
        if (function == "telegram_enable" && function !in answers) return@TelegramSetup arg  // {"enabled":<sent>}
        answers.getValue(function)
    }
    )

    @Test
    fun openingShowsWhatIsSetUp() = runTest {
        answers["telegram_status"] = """{"token":true,"allowed_user":"42","sdk":true,"enabled":true,"live":null}"""

        telegram.open()

        assertEquals(TelegramState(tokenHeld = true, allowedUser = "42", live = PlatformLive(PlatformState.GATEWAY_OFF)), telegram.state.value)
    }

    @Test
    fun aBuildWithoutTheTelegramLibrarySaysSo() = runTest {
        answers["telegram_status"] = """{"token":false,"allowed_user":null,"sdk":false,"enabled":true,"live":null}"""

        telegram.open()

        assertEquals(false, telegram.state.value?.sdk)
    }

    @Test
    fun savingKeepsTheTokenInTheKeystoreAllowsTheUserAndNamesTheBot() = runTest {
        telegram.open()
        val token = "123:abc".toByteArray()

        telegram.save(token, "42")

        assertArrayEquals("123:abc".toByteArray(), held["TELEGRAM_BOT_TOKEN"])
        assertTrue("the caller's copy is wiped", token.all { it == 0.toByte() })
        assertEquals(1, secretsChanged)
        assertEquals(listOf("telegram_status", "telegram_configure", "telegram_check"), calls.map { it.first })
        assertEquals(TelegramState(tokenHeld = true, allowedUser = "42", bot = "@phone_hermes_bot", live = PlatformLive(PlatformState.GATEWAY_OFF)),
                     telegram.state.value)
    }

    @Test
    fun aWrongUserIdStoresNothing() = runTest {
        answers["telegram_configure"] = """{"error":"Enter your numeric Telegram user id"}"""
        telegram.open()

        telegram.save("123:abc".toByteArray(), "@me")

        assertNull(held["TELEGRAM_BOT_TOKEN"])
        assertEquals(0, secretsChanged)
        assertEquals("Enter your numeric Telegram user id", telegram.state.value?.error)
    }

    @Test
    fun aTokenTelegramRefusesIsDeletedAgain() = runTest {
        answers["telegram_check"] = """{"check":"refused","bot":null,"message":"Telegram refused the token (401)"}"""
        telegram.open()

        telegram.save("bad".toByteArray(), "42")

        assertNull(held["TELEGRAM_BOT_TOKEN"])
        assertEquals(2, secretsChanged)  // once to try it, once to forget it
        assertEquals("Telegram refused the token (401)", telegram.state.value?.error)
        assertEquals(false, telegram.state.value?.tokenHeld)
        assertTrue("no plaintext copy left behind", handedOut.all { copy -> copy.all { it == 0.toByte() } })
    }

    @Test
    fun aCheckFailingOnTheTokenAlreadyHeldKeepsItAndLeavesNoPlaintextCopy() = runTest {
        held["TELEGRAM_BOT_TOKEN"] = "123:abc".toByteArray()
        answers["telegram_check"] = """{"check":"unreachable","bot":null,"message":"Telegram could not be reached"}"""
        telegram.open()

        telegram.save(null, "42")

        assertEquals(true, telegram.state.value?.tokenHeld)
        assertArrayEquals("123:abc".toByteArray(), held["TELEGRAM_BOT_TOKEN"])
        assertTrue(handedOut.isNotEmpty() && handedOut.all { copy -> copy.all { it == 0.toByte() } })
    }

    @Test
    fun aNewTokenCheckedWhileTelegramIsOutOfReachIsKept() = runTest {
        answers["telegram_check"] = """{"check":"unreachable","bot":null,"message":"Telegram could not be reached, so the token was kept unchecked"}"""
        telegram.open()

        telegram.save("123:abc".toByteArray(), "42")

        assertArrayEquals("123:abc".toByteArray(), held["TELEGRAM_BOT_TOKEN"])  // a good token pasted offline stays
        assertEquals(true, telegram.state.value?.tokenHeld)
        assertEquals("Telegram could not be reached, so the token was kept unchecked", telegram.state.value?.error)
    }

    @Test
    fun savingWithoutANewTokenKeepsTheOneHeld() = runTest {
        held["TELEGRAM_BOT_TOKEN"] = "123:abc".toByteArray()
        answers["telegram_status"] = """{"token":true,"allowed_user":"7","sdk":true,"enabled":true,"live":null}"""
        telegram.open()

        telegram.save(null, "42")

        assertArrayEquals("123:abc".toByteArray(), held["TELEGRAM_BOT_TOKEN"])
        assertEquals(0, secretsChanged)
        assertEquals("@phone_hermes_bot", telegram.state.value?.bot)
        assertNotNull(calls.firstOrNull { it.first == "telegram_configure" && it.second.contains("\"42\"") })
    }

    @Test
    fun aKeystoreFailureIsShownByItsKindAndTheTokenStillWiped() = runTest {
        val failing = TelegramSetup(object : SecretStore by secrets {
            override fun put(name: String, value: ByteArray) = throw java.io.IOException("disk full")
        }, bridge = { function, _ -> if (function == "secrets_changed") "" else answers.getValue(function) }, seen = { seenNow })
        failing.open()
        val token = "123:abc".toByteArray()

        failing.save(token, "42")

        assertEquals(false, failing.state.value?.busy)
        assertEquals("Something went wrong (IOException).", failing.state.value?.error)
        assertTrue(token.all { it == 0.toByte() })
    }

    @Test
    fun aBridgeFailureWhileOpeningStillOpensTheScreenWithIt() = runTest {
        val failing = TelegramSetup(secrets, bridge = { _, _ -> throw RuntimeException("Traceback ...") }, seen = { seenNow })

        failing.open()

        assertEquals("Something went wrong (RuntimeException).", failing.state.value?.error)
    }

    @Test
    fun aClosedSetupHasNoState() = runTest {
        telegram.open()
        telegram.close()

        assertNull(telegram.state.value)
    }

    private fun holdToken(enabled: Boolean = true, user: String? = "42") {
        held["TELEGRAM_BOT_TOKEN"] = "123:abc".toByteArray()
        answers["telegram_status"] =
            """{"token":true,"allowed_user":${user?.let { "\"$it\"" } ?: "null"},"sdk":true,"enabled":$enabled,"live":null}"""
    }

    private fun sentEnable() = calls.filter { it.first == "telegram_enable" }.map { Json.parseToJsonElement(it.second) }

    private fun afterCheck() = timeline.dropWhile { it != "telegram_check" }.drop(1)

    private fun enabled(on: Boolean) = Json.parseToJsonElement("""{"enabled":$on}""")

    @Test
    fun theSwitchCannotTurnOnWithoutAToken() = runTest {
        telegram.open()

        telegram.setEnabled(true)

        assertEquals("no switch on without a token (D40)", emptyList<Any>(), sentEnable())
        assertEquals(false, telegram.state.value?.busy)
        assertEquals(true, telegram.state.value?.enabled)  // as the core read it: unchanged
        assertEquals(false, telegram.state.value?.tokenHeld)
    }

    @Test
    fun theSwitchTurnsTheBotOnWhileATokenIsHeldAndMarksTheNextStart() = runTest {
        holdToken(enabled = false)
        seenNow = GatewaySeen(GatewayStatus.RUNNING, pid = 42)
        telegram.open()

        telegram.setEnabled(true)

        assertEquals(listOf(enabled(true)), sentEnable())
        val state = telegram.state.value!!
        assertEquals(true, state.enabled)
        assertEquals(true, state.nextStart)
        assertEquals(false, state.busy)
        assertEquals(PlatformLive(PlatformState.PENDING_RESTART), state.live)  // no entry from pid 42
    }

    @Test
    fun theSwitchTurnsTheBotOffWithOrWithoutAToken() = runTest {
        telegram.open()

        telegram.setEnabled(false)

        assertEquals(listOf(enabled(false)), sentEnable())
        val state = telegram.state.value!!
        assertEquals(false, state.enabled)
        assertEquals(PlatformLive(PlatformState.DISABLED), state.live)
        assertEquals("no live gateway: nothing to mark", false, state.nextStart)
    }

    @Test
    fun theMarkStaysWhileTheSameGatewayRunsAndGoesWithItsPid() = runTest {
        holdToken(enabled = false)
        seenNow = GatewaySeen(GatewayStatus.RUNNING, pid = 42)
        telegram.open()
        telegram.setEnabled(true)

        telegram.refreshLive()
        assertEquals(true, telegram.state.value?.nextStart)

        seenNow = GatewaySeen(GatewayStatus.RUNNING, pid = 43)
        telegram.refreshLive()
        assertEquals(false, telegram.state.value?.nextStart)

        seenNow = GatewaySeen(GatewayStatus.RUNNING, pid = 42)
        telegram.refreshLive()
        assertEquals("the mark is gone, not derived again", false, telegram.state.value?.nextStart)
    }

    @Test
    fun aSwitchTheCoreRefusesKeepsWhatWasShownWithItsWords() = runTest {
        holdToken(enabled = false)
        seenNow = GatewaySeen(GatewayStatus.RUNNING, pid = 42)
        answers["telegram_enable"] = """{"error":"config.yaml is locked"}"""
        telegram.open()

        telegram.setEnabled(true)

        val state = telegram.state.value!!
        assertEquals(listOf(enabled(true)), sentEnable())
        assertEquals("config.yaml is locked", state.error)
        assertEquals(false, state.enabled)
        assertEquals(false, state.busy)
        assertEquals(false, state.nextStart)
    }

    @Test
    fun removeAsksFirstAndOnlyForAHeldToken() = runTest {
        telegram.open()
        telegram.askRemove(true)
        assertEquals("no token: nothing to remove", false, telegram.state.value?.confirmRemove)

        holdToken()
        telegram.open()
        telegram.confirmRemove()  // never asked
        assertEquals(emptyList<Any>(), sentEnable())
        assertNotNull(held["TELEGRAM_BOT_TOKEN"])
        assertEquals(true, telegram.state.value?.tokenHeld)

        telegram.askRemove(true)
        assertEquals(true, telegram.state.value?.confirmRemove)
        telegram.askRemove(false)
        assertEquals(false, telegram.state.value?.confirmRemove)
        assertEquals(emptyList<Any>(), sentEnable())
    }

    @Test
    fun removingTheBotSwitchesItOffBeforeTheTokenGoesAndKeepsTheUser() = runTest {
        holdToken(enabled = true, user = "42")
        seenNow = GatewaySeen(GatewayStatus.RUNNING, pid = 42)
        telegram.open()
        timeline.clear()

        telegram.askRemove(true)
        telegram.confirmRemove()

        assertEquals("Remove must switch the bot off before its token goes (D40)",
                     listOf("telegram_enable", "delete TELEGRAM_BOT_TOKEN", "secrets_changed"), timeline)
        assertEquals(listOf(enabled(false)), sentEnable())
        assertNull(held["TELEGRAM_BOT_TOKEN"])
        val state = telegram.state.value!!
        assertEquals(false, state.tokenHeld)
        assertEquals(false, state.enabled)
        assertEquals("42", state.allowedUser)
        assertNull(state.bot)
        assertEquals(false, state.confirmRemove)
        assertEquals(false, state.busy)
        assertEquals(true, state.nextStart)
        assertEquals(PlatformLive(PlatformState.DISABLED), state.live)
        assertFalse(calls.any { it.first == "telegram_configure" })
    }

    @Test
    fun aRemovalWhoseSwitchOffFailsDeletesNothing() = runTest {
        holdToken()
        answers["telegram_enable"] = """{"error":"x"}"""
        telegram.open()

        telegram.askRemove(true)
        telegram.confirmRemove()

        assertNotNull(held["TELEGRAM_BOT_TOKEN"])
        assertFalse(timeline.any { it.startsWith("delete") })
        assertEquals(0, secretsChanged)
        assertEquals("x", telegram.state.value?.error)
        assertEquals(true, telegram.state.value?.tokenHeld)
        assertEquals(false, telegram.state.value?.busy)
    }

    @Test
    fun aSecondConfirmRemovesOnce() = runTest {
        holdToken()
        telegram.open()

        telegram.askRemove(true)
        telegram.confirmRemove()
        telegram.confirmRemove()

        assertEquals(1, sentEnable().size)
        assertEquals(1, timeline.count { it == "delete TELEGRAM_BOT_TOKEN" })
    }

    @Test
    fun aRefusedTokenReplacingAHeldOneGoesFirstAndTheBotIsSwitchedOff() = runTest {
        holdToken(enabled = true)
        answers["telegram_check"] = """{"check":"refused","bot":null,"message":"$REFUSED_WORDS"}"""
        telegram.open()

        telegram.save("bad".toByteArray(), "42")

        assertEquals("the refused token goes first, then the switch-off, then the core is told: $timeline",
                     listOf("delete TELEGRAM_BOT_TOKEN", "telegram_enable", "secrets_changed"), afterCheck())
        assertEquals(listOf(enabled(false)), sentEnable())
        assertNull(held["TELEGRAM_BOT_TOKEN"])
        assertEquals(2, secretsChanged)  // once to try it, once to forget it
        val state = telegram.state.value!!
        assertEquals(false, state.tokenHeld)
        assertEquals(false, state.enabled)
        assertEquals(false, state.busy)
        assertEquals("Telegram's words alone: the switch-off worked", REFUSED_WORDS, state.error)
        assertEquals(PlatformLive(PlatformState.DISABLED), state.live)
    }

    @Test
    fun aRefusedReplacementWhoseSwitchOffThrowsStillLosesTheToken() = runTest {
        holdToken(enabled = true)
        answers["telegram_check"] = """{"check":"refused","bot":null,"message":"$REFUSED_WORDS"}"""
        enableFails = java.io.IOException("binder died")
        telegram.open()
        val token = "bad".toByteArray()

        telegram.save(token, "42")

        assertFalse("a refused token stays when its switch-off throws (/architect #3)", secrets.contains("TELEGRAM_BOT_TOKEN"))
        assertEquals("the switch-off was attempted and the core still told: $timeline",
                     listOf("delete TELEGRAM_BOT_TOKEN", "telegram_enable", "secrets_changed"), afterCheck())
        assertEquals(2, secretsChanged)
        val state = telegram.state.value!!
        assertEquals(false, state.tokenHeld)
        assertEquals(false, state.busy)
        assertEquals("still on, as the core last read it: the switch-off failed", true, state.enabled)
        assertEquals(PlatformLive(PlatformState.NOT_CONFIGURED), state.live)
        assertEquals("$REFUSED_WORDS The bot could not be switched off and is still on without a token: " +
                     "Something went wrong (IOException).", state.error)
        assertTrue("the caller's copy is wiped", token.all { it == 0.toByte() })
        assertTrue("no plaintext copy left behind", handedOut.all { copy -> copy.all { it == 0.toByte() } })
    }

    @Test
    fun aRefusedReplacementWhoseSwitchOffTheCoreRefusesShowsBothWords() = runTest {
        holdToken(enabled = true)
        answers["telegram_check"] = """{"check":"refused","bot":null,"message":"$REFUSED_WORDS"}"""
        answers["telegram_enable"] = """{"error":"config.yaml is locked"}"""
        telegram.open()

        telegram.save("bad".toByteArray(), "42")

        assertFalse("the refused token is gone though the core kept the bot on", secrets.contains("TELEGRAM_BOT_TOKEN"))
        val state = telegram.state.value!!
        assertEquals(true, state.enabled)
        assertTrue("the core's own words follow Telegram's: ${state.error}",
                   state.error.orEmpty().startsWith(REFUSED_WORDS) && state.error.orEmpty().endsWith(": config.yaml is locked"))
    }

    @Test
    fun aHeldTokenRefusedOnARecheckGoesFirstAndTheBotIsSwitchedOff() = runTest {
        holdToken(enabled = true)
        answers["telegram_check"] = """{"check":"refused","bot":null,"message":"$REFUSED_WORDS"}"""
        telegram.open()

        telegram.save(null, "42")

        assertEquals("a held token refused on a re-check goes first, then the switch-off, then the core is told: $timeline",
                     listOf("delete TELEGRAM_BOT_TOKEN", "telegram_enable", "secrets_changed"), afterCheck())
        assertEquals(listOf(enabled(false)), sentEnable())
        assertNull(held["TELEGRAM_BOT_TOKEN"])
        assertEquals(1, secretsChanged)  // no new token to try, one refused to forget
        val state = telegram.state.value!!
        assertEquals(false, state.tokenHeld)
        assertEquals(false, state.enabled)
        assertEquals(false, state.busy)
        assertEquals("Telegram's words alone: the switch-off worked", REFUSED_WORDS, state.error)
        assertEquals(PlatformLive(PlatformState.DISABLED), state.live)
        assertTrue("no plaintext copy left behind", handedOut.all { copy -> copy.all { it == 0.toByte() } })
    }

    @Test
    fun aHeldTokenRefusedOnARecheckWhoseSwitchOffThrowsStillGoes() = runTest {
        holdToken(enabled = true)
        answers["telegram_check"] = """{"check":"refused","bot":null,"message":"$REFUSED_WORDS"}"""
        enableFails = java.io.IOException("binder died")
        telegram.open()

        telegram.save(null, "42")

        assertFalse("a held token Telegram refused stays when its switch-off throws (K2 FOUND)", secrets.contains("TELEGRAM_BOT_TOKEN"))
        assertEquals("the switch-off was attempted and the core still told: $timeline",
                     listOf("delete TELEGRAM_BOT_TOKEN", "telegram_enable", "secrets_changed"), afterCheck())
        assertEquals(1, secretsChanged)
        val state = telegram.state.value!!
        assertEquals(false, state.tokenHeld)
        assertEquals(false, state.busy)
        assertEquals("still on, as the core last read it: the switch-off failed", true, state.enabled)
        assertEquals(PlatformLive(PlatformState.NOT_CONFIGURED), state.live)
        assertEquals("$REFUSED_WORDS The bot could not be switched off and is still on without a token: " +
                     "Something went wrong (IOException).", state.error)
    }

    @Test
    fun aBotLeftOnWithoutATokenCanStillBeSwitchedOffAndNotBackOn() = runTest {
        holdToken(enabled = true)
        seenNow = GatewaySeen(GatewayStatus.RUNNING, pid = 42)
        answers["telegram_check"] = """{"check":"refused","bot":null,"message":"$REFUSED_WORDS"}"""
        enableFails = java.io.IOException("binder died")
        telegram.open()
        telegram.save("bad".toByteArray(), "42")
        assertEquals("K2's stuck state: still on", true, telegram.state.value?.enabled)
        assertEquals("K2's stuck state: no token", false, telegram.state.value?.tokenHeld)

        enableFails = null
        telegram.setEnabled(false)

        assertEquals("the failed switch-off, then this one", listOf(enabled(false), enabled(false)), sentEnable())
        val state = telegram.state.value!!
        assertEquals(false, state.enabled)
        assertEquals(PlatformLive(PlatformState.DISABLED), state.live)
        assertEquals(true, state.nextStart)
        assertNull(state.error)
        assertEquals(false, state.busy)

        telegram.setEnabled(true)

        assertEquals("no switch on without a token (D40)", 2, sentEnable().size)
        assertEquals(false, telegram.state.value?.enabled)
    }

    @Test
    fun aRefusedFirstTokenLeavesTheSwitchAlone() = runTest {
        answers["telegram_check"] = """{"check":"refused","bot":null,"message":"Telegram refused the token (401)"}"""
        telegram.open()

        telegram.save("bad".toByteArray(), "42")

        assertFalse(calls.any { it.first == "telegram_enable" })
    }

    @Test
    fun openingJudgesTheCoresEntryAgainstTheGatewaySeen() = runTest {
        answers["telegram_status"] = """{"token":true,"allowed_user":"42","sdk":true,"enabled":true,"live":$FATAL_42}"""
        seenNow = GatewaySeen(GatewayStatus.RUNNING, pid = 42)

        telegram.open()

        assertEquals(PlatformLive(PlatformState.FATAL, "Conflict: terminated by other getUpdates request",
                                  "2026-11-13T15:48:13.243611+00:00"), telegram.state.value?.live)

        seenNow = GatewaySeen(GatewayStatus.RUNNING, pid = 43)
        telegram.refreshLive()

        assertEquals(PlatformLive(PlatformState.PENDING_RESTART), telegram.state.value?.live)
    }

    @Test
    fun platformsChangedReadsTheBotAgainAndOtherSignalsDoNot() = runTest(UnconfinedTestDispatcher()) {
        val core = FakeCoreGateway { _, _ -> "{}" }
        answers["telegram_status"] = """{"token":true,"allowed_user":"42","sdk":true,"enabled":true,"live":null}"""
        seenNow = GatewaySeen(GatewayStatus.RUNNING, pid = 42)
        telegram.open()
        backgroundScope.launch { telegram.follow(core.events) }
        val before = telegram.state.value?.live
        answers["telegram_status"] = """{"token":true,"allowed_user":"42","sdk":true,"enabled":true,"live":$CONNECTED_42}"""

        core.emit("cron.changed", "")

        assertEquals(1, calls.count { it.first == "telegram_status" })
        assertEquals(before, telegram.state.value?.live)

        core.emit("platforms.changed", "")

        assertEquals(2, calls.count { it.first == "telegram_status" })
        assertEquals(PlatformState.CONNECTED, telegram.state.value?.live?.state)
    }

    @Test
    fun aClosedSetupReadsNothingOnRefresh() = runTest {
        telegram.open()
        telegram.close()
        val before = timeline.size

        telegram.refreshLive()

        assertEquals(before, timeline.size)
        assertNull(telegram.state.value)
    }

    private companion object {
        const val REFUSED_WORDS = "Telegram refused the token (401): check it with @BotFather."
        const val FATAL_42 = """{"state":"fatal","error_message":"Conflict: terminated by other getUpdates request","writer_pid":42,
            "updated_at":"2026-11-13T15:48:13.243611+00:00"}"""
        const val CONNECTED_42 = """{"state":"connected","error_message":null,"writer_pid":42,
            "updated_at":"2026-11-13T15:48:13.243611+00:00"}"""
    }
}
