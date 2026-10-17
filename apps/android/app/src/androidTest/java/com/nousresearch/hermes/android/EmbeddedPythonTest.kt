package com.nousresearch.hermes.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.bridge.EmbeddedPython
import com.nousresearch.hermes.bridge.HermesPaths
import com.nousresearch.hermes.bridge.RuntimeInstaller
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The core, embedded in the app process, answers over the in-process transport. */
@RunWith(AndroidJUnit4::class)
class EmbeddedPythonTest {
    companion object {
        // One interpreter per process: every test talks to the same one.
        private val frames = LinkedBlockingQueue<String>()
        private val ready: String by lazy {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val runtime = RuntimeInstaller(File(context.noBackupFilesDir, "runtime")).install(AssetPayload(context.assets))
            val scratch = File(context.cacheDir, "embedded-python-test").apply { deleteRecursively(); mkdirs() }
            EmbeddedPython.start(HermesPaths(runtime, File(scratch, "home"), File(scratch, "home/.hermes"), scratch)) { frames.put(it) }
            next { it.has("method") }.toString()
        }

        private fun next(match: (JSONObject) -> Boolean): JSONObject {
            while (true) {
                val frame = JSONObject(frames.poll(60, TimeUnit.SECONDS) ?: throw AssertionError("no frame within 60 s"))
                if (match(frame)) return frame
            }
        }
    }

    @Test
    fun theCoreGreetsTheAppAndAnswersARequest() {
        assertEquals("gateway.ready", JSONObject(ready).getJSONObject("params").getString("type"))

        EmbeddedPython.submit("""{"jsonrpc":"2.0","id":1,"method":"ping"}""")

        assertTrue(next { it.optInt("id") == 1 }.getJSONObject("result").getBoolean("pong"))
    }

    @Test
    fun framesCrossTheBridgeAsUtf8BothWays() {
        ready
        EmbeddedPython.submit("""{"jsonrpc":"2.0","id":2,"method":"no.such.😀"}""")

        assertTrue(next { it.optInt("id") == 2 }.getJSONObject("error").getString("message").contains("no.such.😀"))
    }

    @Test
    fun theInterpreterHasFts5AndOffersNoExecutable() {
        ready
        val facts = JSONObject(EmbeddedPython.call("runtime_facts", ""))

        assertEquals("3.14.7", facts.getString("python"))
        assertTrue("sqlite ${facts.getString("sqlite")} lacks FTS5", facts.getBoolean("fts5"))
        assertEquals("", facts.getString("executable"))
    }
}
