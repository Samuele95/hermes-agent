package com.nousresearch.hermes.android

import android.provider.OpenableColumns
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.chat.StoredSessionId
import com.nousresearch.hermes.android.chat.exportSession
import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.EmbeddedBridge
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.SessionCloseParams
import com.nousresearch.hermes.bridge.protocol.SessionCreateParams
import com.nousresearch.hermes.bridge.protocol.SessionCreateResult
import com.nousresearch.hermes.bridge.protocol.SessionDeleteParams
import com.nousresearch.hermes.bridge.protocol.SessionTitleParams
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A session's export on the phone (sessions-12, F8): the app's `session_export` writes the stored session's file into
 * `cache/exports/`, and the app's `FileProvider` serves it as a `content://` URI with a read grant (§3.4's device check).
 * The sessions get no turn, only a title, so no model is involved; the test deletes its sessions and its exports.
 */
@RunWith(AndroidJUnit4::class)
class SessionExportDeviceTest {
    companion object {
        private const val TAG = "SessionExportDeviceTest"
        private const val NOT_SAVED = "Nothing to export yet: Hermes has not saved this conversation."
        // One interpreter per process, owned by the application: every test talks to its core.
        private val core: CoreGateway by lazy {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication
            val state = runBlocking { withTimeout(120_000) { app.core.first { it !is CoreState.Starting } } }
            (state as? CoreState.Ready ?: error("the core did not start: $state")).gateway
        }
    }

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val exports = File(ctx.cacheDir, "exports")

    private fun exported(): List<String> = exports.list().orEmpty().sorted()

    /** Desktop's sanitizeFilenamePart, to name the expected file. */
    private fun safe(value: String) = value.trim().lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').take(48)

    /** A new session whose pre-warm has settled (its outcome would otherwise reach python.stdout after the close). */
    private suspend fun settledSession(): SessionCreateResult = coroutineScope {
        val settled = Channel<String>(Channel.UNLIMITED)
        val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            core.events.collect { if (it.type == "session.info" || it.type == "error") it.sessionId?.let(settled::trySend) }
        }
        try {
            val created = core.call(Methods.SESSION_CREATE, SessionCreateParams(source = "android"))
            withTimeout(120_000) { while (settled.receive() != created.sessionId) Unit }
            created
        } finally {
            watcher.cancel()
        }
    }

    /** Closes and deletes [created], leaving nothing on the shared phone; a row the core never wrote answers 4007 (M1). */
    private suspend fun cleanUp(created: SessionCreateResult) {
        runCatching { core.call(Methods.SESSION_CLOSE, SessionCloseParams(sessionId = created.sessionId)) }
        runCatching { core.call(Methods.SESSION_DELETE, SessionDeleteParams(sessionId = created.storedSessionId)) }
    }

    @Test
    fun aTitledSessionExportsAndItsContentUriReadsTheSameFile() = runBlocking<Unit> {
        val created = settledSession()
        val stored = created.storedSessionId
        try {
            val title = "m13 export ${System.nanoTime()}"
            core.call(Methods.SESSION_TITLE, SessionTitleParams(sessionId = created.sessionId, title = title))  // writes the row (M1)

            val file = EmbeddedBridge.exportSession(StoredSessionId(stored))

            assertEquals(exports.canonicalFile, File(file.path).canonicalFile.parentFile)
            assertEquals("${safe(title)}-${safe(stored).take(8)}.json", file.name)
            assertEquals(File(file.path).length(), file.bytes)
            val json = JSONObject(File(file.path).readText())
            assertEquals(stored, json.getString("id"))
            assertEquals(title, json.getString("title"))
            assertTrue(json.get("messages") is JSONArray)

            val uri = exportUri(ctx, file)
            assertEquals("content", uri.scheme)
            assertEquals("${ctx.packageName}.exports", uri.authority)
            val served = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertArrayEquals(File(file.path).readBytes(), served)
            val type = ctx.contentResolver.getType(uri)
            Log.i(TAG, "content type $type")
            assertEquals("application/json", type)
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)!!.use { row ->
                assertTrue(row.moveToFirst())
                Log.i(TAG, "display name ${row.getString(0)}, size ${row.getLong(1)}")
                assertEquals(file.name, row.getString(0))
                assertEquals(file.bytes, row.getLong(1))
            }

            EmbeddedBridge.exportSession(StoredSessionId(stored))  // a second export replaces the first

            assertEquals(1, exported().size)
        } finally {
            cleanUp(created)
            exports.listFiles()?.forEach { it.delete() }
        }
    }

    @Test
    fun aSessionWithNoStoredRowIsRefusedInTheAppsWords() = runBlocking<Unit> {
        val before = exported()

        val refused = assertThrows(CoreError::class.java) {
            runBlocking { EmbeddedBridge.exportSession(StoredSessionId("m13-no-row-${System.nanoTime()}")) }
        }

        assertEquals(NOT_SAVED, refused.message)
        assertEquals(before, exported())

        // For the report only: whether a fresh untitled, unanswered session has a stored row yet (M1 said no).
        val fresh = settledSession()
        try {
            val outcome = runCatching { EmbeddedBridge.exportSession(StoredSessionId(fresh.storedSessionId)) }
            Log.i(TAG, "a fresh untitled session: " + outcome.fold({ "exported, ${it.bytes} bytes" }, { "refused: ${it.message}" }))
        } finally {
            cleanUp(fresh)
            exports.listFiles()?.forEach { it.delete() }
        }
    }
}
