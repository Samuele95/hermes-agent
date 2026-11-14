package com.nousresearch.hermes.android

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.EmbeddedPython
import com.nousresearch.hermes.bridge.protocol.Method
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.ModelOptionsParams
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A provider key stored in the app's keystore reaches the embedded core, and only through its environment.
 * Each test stores the key only after the chat's own start-up build has settled (D42).
 */
@RunWith(AndroidJUnit4::class)
class CoreSecretsTest {
    private val key = "DEEPSEEK_API_KEY"
    private val dummy = "sk-hermes-android-test-not-a-real-key"
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication
    private val ready: CoreState.Ready by lazy {
        val state = runBlocking { withTimeout(120_000) { app.core.first { it !is CoreState.Starting } } }
        state as? CoreState.Ready ?: error("the core did not start: $state")
    }
    private val core: CoreGateway get() = ready.gateway

    private companion object {
        const val TAG = "CoreSecretsTest"
        /** The core's words while a session's agent is still being built (`tui_gateway/user_messages.py:118`, code 5032). */
        const val STILL_STARTING = "Hermes is still starting this session"
    }

    /**
     * `approval.pending` waits, in the core, for its session's agent build (`_sess` → `_wait_agent`, up to 30 s a call).
     * Only this test needs it, so it is declared here rather than in the app's generated subset.
     */
    private val pendingApprovals = Method("approval.pending", JsonObject.serializer(), JsonObject.serializer())

    private fun deepseekAuthenticated(): Boolean? = runBlocking {
        core.call(Methods.MODEL_OPTIONS, ModelOptionsParams(includeUnconfigured = true)).providers.single { it.slug == "deepseek" }.authenticated
    }

    private fun store(value: String?) {
        if (value == null) app.secrets.delete(key) else app.secrets.put(key, value.toByteArray())
        EmbeddedPython.call("secrets_changed", "")
    }

    /**
     * The chat's start-up session (`ChatSession.start`: `session.resume` or `session.create`) builds an agent, and that
     * build reads the provider keys in the environment: one that lands while [dummy] is stored writes an
     * `env:DEEPSEEK_API_KEY` row into auth.json. The key is stored only once that build has settled.
     */
    @Before
    fun theChatsStartUpBuildHasSettled() = runBlocking<Unit> {
        val session = JsonObject(mapOf("session_id" to JsonPrimitive(ready.chat.sessionId.value)))
        val since = SystemClock.elapsedRealtime()
        val outcome = withTimeout(180_000) {
            var settled: String
            do {
                settled = try {
                    core.call(pendingApprovals, session)
                    "built"
                } catch (refused: CoreError) {
                    if (refused.code == 5032 && refused.message.orEmpty().startsWith(STILL_STARTING)) "starting" else "failed ${refused.code}"
                }
            } while (settled == "starting")
            settled
        }
        Log.i(TAG, "the chat's start-up build settled after ${SystemClock.elapsedRealtime() - since} ms: $outcome")
    }

    @After
    fun forgetTheKey() = store(null)

    @Test
    fun aStoredKeyAuthenticatesItsProviderAndDeletingItTakesItBack() {
        store(null)
        assertEquals(false, deepseekAuthenticated())

        store(dummy)
        assertEquals(true, deepseekAuthenticated())

        store(null)
        assertEquals(false, deepseekAuthenticated())
    }

    @Test
    fun theKeyReachesNeitherDotEnvNorTheAppsLog() {
        store(dummy)
        deepseekAuthenticated()

        val dotenv = File(app.filesDir, ".hermes/.env")
        assertFalse(dotenv.isFile && dotenv.readText().contains(dummy))
        val log = Runtime.getRuntime().exec(arrayOf("logcat", "-d")).inputStream.bufferedReader().readText()
        assertFalse("the key is in the app's logcat", log.contains(dummy))
    }
}
