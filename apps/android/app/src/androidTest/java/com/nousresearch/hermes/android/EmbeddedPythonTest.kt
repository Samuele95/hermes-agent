package com.nousresearch.hermes.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.EmbeddedPython
import com.nousresearch.hermes.bridge.HermesPaths
import com.nousresearch.hermes.bridge.RuntimeInstaller
import com.nousresearch.hermes.bridge.protocol.ClientCapabilitiesParams
import com.nousresearch.hermes.bridge.protocol.Method
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.PingParams
import com.nousresearch.hermes.bridge.protocol.PingResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The core, embedded in the app process, answers the app's gateway over the in-process transport. */
@RunWith(AndroidJUnit4::class)
class EmbeddedPythonTest {
    companion object {
        // One interpreter per process: every test talks to the same one.
        private val core: CoreGateway by lazy {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val runtime = RuntimeInstaller(File(context.noBackupFilesDir, "runtime")).install(AssetPayload(context.assets))
            val scratch = File(context.cacheDir, "embedded-python-test").apply { deleteRecursively(); mkdirs() }
            runBlocking { EmbeddedPython.connect(HermesPaths(runtime, File(scratch, "home"), File(scratch, "home/.hermes"), scratch)) }
        }
    }

    @Test
    fun theCoreAnswersATypedRequest() = runBlocking {
        assertEquals(PingResult(pong = true), core.call(Methods.PING, PingParams))
    }

    @Test
    fun theAppIsAClientThatAnswersInTurnPrompts() = runBlocking {
        val asked = core.call(Methods.CLIENT_CAPABILITIES, ClientCapabilitiesParams(serverRequests = true)).serverRequests

        assertTrue("$asked", asked.containsAll(listOf("approval", "clarify", "secret")))
    }

    @Test
    fun framesCrossTheBridgeAsUtf8BothWays() {
        val unknown = Method("no.such.😀", PingParams.serializer(), PingResult.serializer())

        val error = assertThrows(CoreError::class.java) { runBlocking { core.call(unknown, PingParams) } }

        assertEquals(-32601, error.code)
        assertTrue(error.message, error.message!!.contains("no.such.😀"))
    }

    @Test
    fun theInterpreterHasFts5AndOffersNoExecutable() {
        core
        val facts = JSONObject(EmbeddedPython.call("runtime_facts", ""))

        assertEquals("3.14.7", facts.getString("python"))
        assertTrue("sqlite ${facts.getString("sqlite")} lacks FTS5", facts.getBoolean("fts5"))
        assertEquals("", facts.getString("executable"))
    }
}
