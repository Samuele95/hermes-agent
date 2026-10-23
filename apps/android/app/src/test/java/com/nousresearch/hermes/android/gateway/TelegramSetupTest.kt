package com.nousresearch.hermes.android.gateway

import com.nousresearch.hermes.bridge.SecretStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's Telegram bot (D11, D24): token in the keystore, one allowed user, checked with Telegram. */
class TelegramSetupTest {
    private val held = mutableMapOf<String, ByteArray>()
    private val secrets = object : SecretStore {
        override fun put(name: String, value: ByteArray) { held[name] = value.copyOf() }
        override fun get(name: String) = held[name]
        override fun delete(name: String) { held.remove(name) }
        override fun all(): Map<String, ByteArray> = held.toMap()
    }
    private var secretsChanged = 0
    private val calls = mutableListOf<Pair<String, String>>()
    private val answers = mutableMapOf(
        "telegram_status" to """{"token":false,"allowed_user":null}""",
        "telegram_configure" to """{"allowed_user":"42"}""",
        "telegram_check" to """{"bot":"@phone_hermes_bot"}""",
    )
    private val telegram = TelegramSetup(secrets, { secretsChanged++ }) { function, arg ->
        calls += function to arg
        answers.getValue(function)
    }

    @Test
    fun openingShowsWhatIsSetUp() = runTest {
        answers["telegram_status"] = """{"token":true,"allowed_user":"42"}"""

        telegram.open()

        assertEquals(TelegramState(tokenHeld = true, allowedUser = "42"), telegram.state.value)
    }

    @Test
    fun aBuildWithoutTheTelegramLibrarySaysSo() = runTest {
        answers["telegram_status"] = """{"token":false,"allowed_user":null,"sdk":false}"""

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
        assertEquals(TelegramState(tokenHeld = true, allowedUser = "42", bot = "@phone_hermes_bot"), telegram.state.value)
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
        answers["telegram_check"] = """{"error":"Telegram refused the token (401)"}"""
        telegram.open()

        telegram.save("bad".toByteArray(), "42")

        assertNull(held["TELEGRAM_BOT_TOKEN"])
        assertEquals(2, secretsChanged)  // once to try it, once to forget it
        assertEquals("Telegram refused the token (401)", telegram.state.value?.error)
        assertEquals(false, telegram.state.value?.tokenHeld)
    }

    @Test
    fun savingWithoutANewTokenKeepsTheOneHeld() = runTest {
        held["TELEGRAM_BOT_TOKEN"] = "123:abc".toByteArray()
        answers["telegram_status"] = """{"token":true,"allowed_user":"7"}"""
        telegram.open()

        telegram.save(null, "42")

        assertArrayEquals("123:abc".toByteArray(), held["TELEGRAM_BOT_TOKEN"])
        assertEquals(0, secretsChanged)
        assertEquals("@phone_hermes_bot", telegram.state.value?.bot)
        assertNotNull(calls.firstOrNull { it.first == "telegram_configure" && it.second.contains("\"42\"") })
    }

    @Test
    fun aClosedSetupHasNoState() = runTest {
        telegram.open()
        telegram.close()

        assertNull(telegram.state.value)
    }
}
