package com.nousresearch.hermes.android

import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.chat.ExportFile
import com.nousresearch.hermes.android.chat.ExportUi
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
import java.util.concurrent.CopyOnWriteArrayList
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A session's export on the phone (sessions-12, F8): the app's `session_export` writes the stored session's file into
 * `cache/exports/`, and the app's `FileProvider` serves it as a `content://` URI with a read grant (§3.4's device check).
 * The sessions get no turn, only a title, so no model is involved; the test deletes its sessions and its exports. The
 * activity's share sheet is caught as it starts and never shown (N6, D6), and no file is left waiting.
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
        // The app's own composition: its `.pages.export` is the SessionExport the activity hands to the share sheet.
        private val ready: CoreState.Ready by lazy {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication
            val state = runBlocking { withTimeout(120_000) { app.core.first { it !is CoreState.Starting } } }
            state as? CoreState.Ready ?: error("the core did not start: $state")
        }
    }

    /** Each chooser the activity starts, kept and not shown; the first [refuse] starts throw [refusal] (Android's SecurityException by default). */
    private class ShareSheet(
        private var refuse: Int = 0,
        private val refusal: () -> RuntimeException = { SecurityException("background activity start blocked (N6 test)") },
    ) : Instrumentation.ActivityMonitor() {
        val started = CopyOnWriteArrayList<Intent>()
        val others = CopyOnWriteArrayList<String>()  // for the report: ActivityScenario's own starts, let through
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.action != Intent.ACTION_CHOOSER) {  // ActivityScenario's own starts pass
                others += "${intent.action} ${intent.component?.shortClassName}"
                return null
            }
            started += intent
            if (refuse-- > 0) throw refusal()
            return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

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

    /** Polls [done] on the test thread every 100 ms; [label] fails the test after [ms]. */
    private fun until(label: String, ms: Long = 10_000, done: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + ms
        while (!done()) {
            if (SystemClock.uptimeMillis() > end) throw AssertionError(label)
            Thread.sleep(100)
        }
    }

    /** [chooser] shares [file]: `ACTION_SEND` of `application/json`, its stream the export provider's URI for it. */
    private fun assertSharesFile(chooser: Intent, file: ExportFile) {
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/json", send.type)
        assertEquals(exportUri(ctx, file), IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java))
    }

    /** A titled session's export through the app's own [SessionExport]; the file it leaves waiting. */
    private fun exportTitled(created: SessionCreateResult): ExportFile = runBlocking {
        val title = "n6 export ${System.nanoTime()}"
        core.call(Methods.SESSION_TITLE, SessionTitleParams(sessionId = created.sessionId, title = title))  // writes the row (M1)
        ready.pages.export.export(StoredSessionId(created.storedSessionId))
        val written = ready.pages.export.state.value.file
        assertEquals("${safe(title)}-${safe(created.storedSessionId).take(8)}.json", written?.name)
        written!!
    }

    /** No file left waiting (the shared phone's debug app opens no sheet later), the monitor gone, nothing stored. */
    private fun tidy(sheet: ShareSheet, created: SessionCreateResult?) {
        ready.pages.export.state.value.file?.let(ready.pages.export::shared)
        instrumentation.removeMonitor(sheet)
        Log.i(TAG, "starts let through: ${sheet.others}")
        created?.let { runBlocking { cleanUp(it) } }
        exports.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun aFinishedExportWaitsWhileTheAppIsAwayAndOpensTheShareSheetWhenItComesBack() {
        assertEquals(ExportUi(), ready.pages.export.state.value)
        val created = runBlocking { settledSession() }
        val sheet = ShareSheet()
        instrumentation.addMonitor(sheet)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.moveToState(Lifecycle.State.CREATED)  // onStop, as Home does (D7)

                val written = exportTitled(created)

                Thread.sleep(3_000)
                assertEquals(0, sheet.started.size)
                assertEquals(written, ready.pages.export.state.value.file)

                scenario.moveToState(Lifecycle.State.RESUMED)

                until("one share sheet, and the file waits no longer") {
                    sheet.started.size == 1 && ready.pages.export.state.value == ExportUi()
                }
                assertSharesFile(sheet.started[0], written)
                Thread.sleep(2_000)
                assertEquals(1, sheet.started.size)
            }
        } finally {
            tidy(sheet, created)
        }
    }

    @Test
    fun aRefusedHandoffKeepsTheFileAndIsOfferedAgainAtTheNextStart() {
        assertEquals(ExportUi(), ready.pages.export.state.value)
        val created = runBlocking { settledSession() }
        val sheet = ShareSheet(refuse = 1)
        instrumentation.addMonitor(sheet)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val written = exportTitled(created)

                until("the first share sheet, refused") { sheet.started.size == 1 }
                Thread.sleep(1_000)
                assertEquals("the file still waits", written, ready.pages.export.state.value.file)

                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)

                until("a second share sheet at the next start, and the file waits no longer") {
                    sheet.started.size == 2 && ready.pages.export.state.value == ExportUi()
                }
                assertSharesFile(sheet.started[1], written)
            }
        } finally {
            tidy(sheet, created)
        }
    }

    @Test
    fun aStoppedActivityStartsNoShareAndAPathOutsideTheExportsRootIsRefused() {
        assertEquals(ExportUi(), ready.pages.export.state.value)
        exports.mkdirs()
        val direct = File(exports, "n6-direct.json").apply { writeText("{}") }
        val file = ExportFile(direct.path, "n6-direct.json", 2)
        val sheet = ShareSheet()
        instrumentation.addMonitor(sheet)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun share(f: ExportFile): Boolean {
                    var handed: Boolean? = null
                    scenario.onActivity { handed = it.shareExport(f) }
                    return handed!!
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                assertFalse("a stopped activity started a share", share(file))
                assertEquals(0, sheet.started.size)

                scenario.moveToState(Lifecycle.State.RESUMED)
                assertTrue("a started activity started no share", share(file))
                assertEquals(1, sheet.started.size)
                assertSharesFile(sheet.started[0], file)

                assertFalse("a path outside the exports root was shared", share(ExportFile("/data/local/tmp/n6.json", "n6.json", 2)))
                assertEquals(1, sheet.started.size)
            }
        } finally {
            tidy(sheet, null)
        }
    }

    /** A share no app can take fails for good (N15): the file waits no longer, is not offered again, and the next export starts. */
    @Test
    fun aShareNoAppCanTakeDropsTheFileOnceAndTheNextExportStarts() {
        assertEquals(ExportUi(), ready.pages.export.state.value)
        val created = runBlocking { settledSession() }
        val sheet = ShareSheet(refuse = 1, refusal = { ActivityNotFoundException("no share target (N15 test)") })
        instrumentation.addMonitor(sheet)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.moveToState(Lifecycle.State.CREATED)  // the file is read here before any handoff
                exportTitled(created)

                scenario.moveToState(Lifecycle.State.RESUMED)
                until("the first share sheet, failed for good, and the file waits no longer") {
                    sheet.started.size == 1 && ready.pages.export.state.value == ExportUi()
                }

                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                Thread.sleep(2_000)
                assertEquals("a file no app can take was offered again", 1, sheet.started.size)

                scenario.moveToState(Lifecycle.State.CREATED)
                val again = exportTitled(created)  // a new title on the same session: the export is admitted

                scenario.moveToState(Lifecycle.State.RESUMED)
                until("the next export reaches the share sheet") {
                    sheet.started.size == 2 && ready.pages.export.state.value == ExportUi()
                }
                assertSharesFile(sheet.started[1], again)
            }
        } finally {
            tidy(sheet, created)
        }
    }

    /** The activity's share call tells [shareExport]'s failed only for good: not when stopped, not on Android's refusal (N15). */
    @Test
    fun aFailureForGoodIsToldOnceAndARefusalOrAStoppedActivityIsNot() {
        assertEquals(ExportUi(), ready.pages.export.state.value)
        exports.mkdirs()
        val direct = File(exports, "n15-direct.json").apply { writeText("{}") }
        val file = ExportFile(direct.path, "n15-direct.json", 2)
        val outside = ExportFile("/data/local/tmp/n15.json", "n15.json", 2)
        val told = CopyOnWriteArrayList<ExportFile>()
        val sheet = ShareSheet(refuse = 1)  // N6's SecurityException, once
        instrumentation.addMonitor(sheet)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun share(f: ExportFile): Boolean {
                    var handed: Boolean? = null
                    scenario.onActivity { activity -> handed = activity.shareExport(f) { told += it } }
                    return handed!!
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                assertFalse("a stopped activity started a share", share(file))
                assertEquals(0, sheet.started.size)
                assertEquals("a stopped activity told a failure", emptyList<ExportFile>(), told)

                scenario.moveToState(Lifecycle.State.RESUMED)
                assertFalse("a refused share was started", share(file))
                assertEquals(1, sheet.started.size)
                assertEquals("Android's refusal told a failure", emptyList<ExportFile>(), told)

                assertFalse("a path outside the exports root was shared", share(outside))
                assertEquals(1, sheet.started.size)
                assertEquals("a path outside the exports root was not told once", listOf(outside), told)

                assertTrue("a started activity started no share", share(file))
                assertEquals(2, sheet.started.size)
                assertEquals(listOf(outside), told)
                assertSharesFile(sheet.started[1], file)
            }
        } finally {
            tidy(sheet, null)
        }
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
