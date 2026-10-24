package com.nousresearch.hermes.android.setup

import com.nousresearch.hermes.bridge.SecretStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The SSH host setup (D22): an identity kept in the keystore, a host trusted only after its fingerprint is seen. */
class SshSetupTest {
    private val held = mutableMapOf<String, ByteArray>()
    private val handedOut = mutableListOf<ByteArray>()  // every plaintext the store gave out
    private val secrets = object : SecretStore {
        override fun put(name: String, value: ByteArray) { held[name] = value.copyOf() }
        override fun get(name: String) = held[name]?.copyOf()?.also { handedOut += it }  // a fresh copy, as the keystore's
        override fun delete(name: String) { held.remove(name) }
        override fun all(): Map<String, ByteArray> = held.toMap()
    }
    private var secretsChanged = 0
    private val calls = mutableListOf<Pair<String, String>>()
    private var pinned = "no"
    private val answers = mutableMapOf(
        "ssh_new_key" to """{"private":"-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----\n","public":"ssh-ed25519 AAAAPUB hermes-android"}""",
        "ssh_public_key" to """{"public":"ssh-ed25519 AAAAPUB hermes-android"}""",
        "ssh_trust_host" to """{"pinned":"yes"}""",
        "ssh_configure" to """{"backend":"ssh"}""",
        "ssh_check_login" to """{"ok":true}""",
    )
    private val ssh = SshSetup(secrets) { function, arg ->
        if (function == "secrets_changed") { secretsChanged++; return@SshSetup "" }
        calls += function to arg
        if (function == "ssh_host_key") """{"key":"ssh-ed25519 AAAAHOST","fingerprint":"SHA256:abc","pinned":"$pinned"}"""
        else answers.getValue(function)
    }
    private val target = SshTarget("host.example", "delta", 22)

    private fun arg(function: String) = Json.parseToJsonElement(calls.last { it.first == function }.second).jsonObject

    @Test
    fun aFirstLookCreatesTheIdentityInTheKeystoreAndShowsTheHostFingerprint() = runTest {
        ssh.open()

        ssh.look(target)

        assertEquals("-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----\n",
                     held["TERMINAL_SSH_PRIVATE_KEY"]?.decodeToString())
        assertEquals(1, secretsChanged)
        assertEquals(SshStep.Fingerprint(target, "ssh-ed25519 AAAAHOST", "SHA256:abc"), ssh.state.value?.step)
        assertTrue("nothing is trusted or configured before the user confirms",
                   calls.none { it.first in setOf("ssh_trust_host", "ssh_configure") })
    }

    @Test
    fun aBridgeFailureIsShownByItsKindAndTheAppGoesOn() = runTest {
        val failing = SshSetup(secrets) { _, _ -> throw RuntimeException("Traceback (most recent call last): ... sk-not-for-the-screen") }
        failing.open()

        failing.look(target)

        assertEquals(false, failing.state.value?.busy)
        assertEquals("Something went wrong (RuntimeException).", failing.state.value?.error)
    }

    @Test
    fun anExistingIdentityIsKept() = runTest {
        held["TERMINAL_SSH_PRIVATE_KEY"] = "existing".toByteArray()
        ssh.open()

        ssh.look(target)

        assertEquals("existing", held["TERMINAL_SSH_PRIVATE_KEY"]?.decodeToString())
        assertTrue(calls.none { it.first == "ssh_new_key" })
        assertTrue("checking for the identity leaves no plaintext copy", handedOut.all { copy -> copy.all { it == 0.toByte() } })
    }

    @Test
    fun confirmingTheFingerprintPinsThatKeyAndPointsTheTerminalAtTheHost() = runTest {
        ssh.open()
        ssh.look(target)

        ssh.trust()

        assertEquals("ssh-ed25519 AAAAHOST", arg("ssh_trust_host").getValue("key").jsonPrimitive.content)
        assertEquals("delta", arg("ssh_configure").getValue("user").jsonPrimitive.content)
        assertEquals(SshStep.Ready(target, "ssh-ed25519 AAAAPUB hermes-android"), ssh.state.value?.step)
    }

    @Test
    fun aHostAlreadyPinnedToThisKeyNeedsNoSecondConfirmation() = runTest {
        pinned = "yes"
        ssh.open()

        ssh.look(target)

        assertTrue(calls.none { it.first == "ssh_trust_host" })
        assertEquals(SshStep.Ready(target, "ssh-ed25519 AAAAPUB hermes-android"), ssh.state.value?.step)
    }

    @Test
    fun aHostWhoseKeyChangedIsRefusedAndNothingIsTrusted() = runTest {
        pinned = "changed"
        ssh.open()

        ssh.look(target)

        assertNotNull(ssh.state.value?.error)
        assertEquals(SshStep.Host, ssh.state.value?.step)
        assertTrue(calls.none { it.first in setOf("ssh_trust_host", "ssh_configure") })
    }

    @Test
    fun theCoresWordsReachTheUserWhenAStepFails() = runTest {
        answers["ssh_check_login"] = """{"error":"host.example refused the app's key for delta"}"""
        ssh.open()
        ssh.look(target)
        ssh.trust()

        ssh.checkLogin()

        assertEquals("host.example refused the app's key for delta", ssh.state.value?.error)
        assertEquals(false, (ssh.state.value?.step as SshStep.Ready).loginOk)
    }

    @Test
    fun aSuccessfulLoginCheckIsShown() = runTest {
        ssh.open()
        ssh.look(target)
        ssh.trust()

        ssh.checkLogin()

        assertNull(ssh.state.value?.error)
        assertEquals(true, (ssh.state.value?.step as SshStep.Ready).loginOk)
    }
}
