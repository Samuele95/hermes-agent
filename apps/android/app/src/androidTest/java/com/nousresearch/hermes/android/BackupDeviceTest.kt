package com.nousresearch.hermes.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.IntentCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.chat.StoredSessionId
import com.nousresearch.hermes.android.chat.exportSession
import com.nousresearch.hermes.android.settings.BackupControl
import com.nousresearch.hermes.android.settings.BackupFile
import com.nousresearch.hermes.android.gateway.GatewaySeen
import com.nousresearch.hermes.android.gateway.GatewayStatus
import com.nousresearch.hermes.android.gateway.observeGateway
import com.nousresearch.hermes.android.settings.BackupPart
import com.nousresearch.hermes.android.settings.alive
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.EmbeddedBridge
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.SessionCloseParams
import com.nousresearch.hermes.bridge.protocol.SessionCreateParams
import com.nousresearch.hermes.bridge.protocol.SessionCreateResult
import com.nousresearch.hermes.bridge.protocol.SessionDeleteParams
import com.nousresearch.hermes.bridge.protocol.SessionListParams
import com.nousresearch.hermes.bridge.protocol.SessionTitleParams
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipFile
import kotlin.system.measureTimeMillis
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A backup on the phone (settings-24, D49): the app's `backup_create` zips this debug app's five parts, and no member
 * holds a keystore value, a canary key or an `auth.json` token; the zip is served through the export `FileProvider` as
 * `application/zip` and handed to a share sheet that is caught as it starts and never shown. No model is involved:
 * the canary session gets only a title. The test leaves no session, key, zip or lock behind, logs no secret value and
 * no secret name, and wipes every value it reads from the keystore. It never drives the composed `pages.backup`
 * through a restore, since its restart would end this process.
 */
@RunWith(AndroidJUnit4::class)
class BackupDeviceTest {
    companion object {
        private const val TAG = "BackupDeviceTest"
        private const val CANARY = "P15_CANARY_API_KEY"
        private val ready: CoreState.Ready by lazy {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication
            val state = runBlocking { withTimeout(120_000) { app.core.first { it !is CoreState.Starting } } }
            state as? CoreState.Ready ?: error("the core did not start: $state")
        }
        private val core: CoreGateway get() = ready.gateway
    }

    /** Each chooser an activity starts, kept and never shown; any other start passes. */
    private class ShareSheet : Instrumentation.ActivityMonitor() {
        val started = CopyOnWriteArrayList<Intent>()
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.action != Intent.ACTION_CHOOSER) return null
            started += intent
            return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext
    private val app = ctx.applicationContext as HermesApplication
    private val home = File(ctx.filesDir, ".hermes")
    private val lock = File(home, ".backup.lock")

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

    /** Closes and deletes [created], leaving nothing on the shared phone. */
    private suspend fun cleanUp(created: SessionCreateResult) {
        runCatching { core.call(Methods.SESSION_CLOSE, SessionCloseParams(sessionId = created.sessionId)) }
        runCatching { core.call(Methods.SESSION_DELETE, SessionDeleteParams(sessionId = created.storedSessionId)) }
    }

    /** A titled canary session (the title writes the stored row, M1). */
    private suspend fun canarySession(): Pair<SessionCreateResult, String> {
        val created = settledSession()
        val title = "p15 backup ${System.nanoTime()}"
        core.call(Methods.SESSION_TITLE, SessionTitleParams(sessionId = created.sessionId, title = title))
        return created to title
    }

    private fun ByteArray.holds(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    /** Every string leaf of [value] at least [min] characters long (an `auth.json`'s tokens). */
    private fun leaves(value: Any?, min: Int, into: MutableList<String> = mutableListOf()): List<String> {
        when (value) {
            is JSONObject -> value.keys().forEach { leaves(value.get(it), min, into) }
            is JSONArray -> for (i in 0 until value.length()) leaves(value.get(i), min, into)
            is String -> if (value.length >= min) into += value
        }
        return into
    }

    /** The phone's secrets a backup must never carry: the keystore's values (each at least 8 bytes) and `auth.json`'s tokens. */
    private fun secretsOfThisPhone(): List<ByteArray> {
        val held = app.secrets.all().values.filter { value -> (value.size >= 8).also { if (!it) value.fill(0) } }
        val auth = File(home, "auth.json").takeIf { it.exists() }?.readText()?.let { leaves(JSONObject(it), 16) }.orEmpty()
        return held + auth.map { it.toByteArray() }
    }

    @Test
    fun aBackupOfThisPhoneHoldsItsSessionsAndNoKeyOrToken() = runBlocking<Unit> {
        val lockWas = lock.exists()
        val (created, _) = canarySession()
        val canary = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }.toByteArray()
        app.secrets.put(CANARY, canary.copyOf())
        EmbeddedBridge.secretsChanged()  // the core's env holds it now
        val backup = BackupControl(EmbeddedBridge, idle = { true }, seen = { observeGateway(ctx) }, restart = {})
        var file: BackupFile? = null
        val secrets = mutableListOf<ByteArray>()
        val sheet = ShareSheet()
        try {
            backup.open()
            val ms = measureTimeMillis { backup.create() }
            val ui = backup.state.value!!
            assertNull(ui.error)
            file = checkNotNull(ui.file) { "no zip waits for the share sheet" }
            val zip = File(file.path)
            assertEquals(File(ctx.cacheDir, "exports").canonicalFile, zip.canonicalFile.parentFile)
            assertTrue(file.name, file.name.startsWith("hermes-backup-") && file.name.endsWith(".zip"))
            assertEquals(zip.name, file.name)
            assertEquals(zip.length(), file.bytes)
            assertTrue("${file.parts}", file.parts.containsAll(setOf(BackupPart.SESSIONS, BackupPart.SKILLS, BackupPart.CONFIG)))
            assertEquals(file, ui.made)

            secrets += canary
            secrets += secretsOfThisPhone()
            val roots = listOf("memories/", "skills/", "cron/")
            var members = 0
            ZipFile(zip).use { archive ->
                for (entry in archive.entries()) {
                    members++
                    val name = entry.name
                    assertTrue(name, name == "state.db" || name == "config.yaml" || roots.any(name::startsWith))
                    assertFalse(name, name.substringAfterLast('/') in setOf(".env", "auth.json", "vault.key", "vault.json.enc"))
                    assertFalse(name, "mcp-tokens" in name.split('/'))
                    val bytes = archive.getInputStream(entry).use { it.readBytes() }
                    secrets.forEachIndexed { i, secret -> assertFalse("$name holds secret #$i", bytes.holds(secret)) }  // never the value
                    bytes.fill(0)
                }
            }

            val uri = exportUri(ctx, file.path)
            assertEquals("content", uri.scheme)
            assertEquals("${ctx.packageName}.exports", uri.authority)
            assertEquals("application/zip", ctx.contentResolver.getType(uri))
            val served = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertArrayEquals(zip.readBytes(), served)
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)!!.use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(file.name, row.getString(0))
                assertEquals(file.bytes, row.getLong(1))
            }
            val intent = shareIntent(uri, file.name, "application/zip")
            val send = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("application/zip", send.type)
            assertEquals(uri, IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java))
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

            // The activity's own handoff (M13's path, N6): the chooser is caught as it starts, never shown.
            instrumentation.addMonitor(sheet)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var handed: Boolean? = null
                val shared = file
                scenario.onActivity { handed = it.shareBackup(shared) }
                assertEquals(true, handed)
            }
            val chooser = sheet.started.single()
            val sent = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
            assertEquals("application/zip", sent.type)
            assertEquals(uri, IntentCompat.getParcelableExtra(sent, Intent.EXTRA_STREAM, Uri::class.java))
            assertTrue(sent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            backup.handOff(file) { true }
            assertNull(backup.state.value?.file)

            Log.i(TAG, "backup_create ms=$ms bytes=${file.bytes} members=$members parts=${file.parts}")
        } finally {
            instrumentation.removeMonitor(sheet)
            secrets.forEach { it.fill(0) }
            canary.fill(0)
            app.secrets.delete(CANARY)
            EmbeddedBridge.secretsChanged()
            cleanUp(created)
            file?.let { File(it.path).delete() }
            if (!lockWas) lock.delete()  // the core's backup slot file
            backup.close()
        }
    }

    private val config = File(home, "config.yaml")
    private val imports = File(ctx.cacheDir, "imports")

    private fun sha(bytes: ByteArray?): String =
        bytes?.let { MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) } } ?: "absent"

    /** Every stored session id the core lists (a window wide enough for this phone). */
    private suspend fun listed(): Set<String> =
        core.call(Methods.SESSION_LIST, SessionListParams(limit = 100_000, includeHidden = true)).sessions.map { it.id }.toSet()

    /** The picked copies in `cache/imports/` (the app's own picks). */
    private fun picks(): List<String> = imports.list().orEmpty().filter { it.startsWith("picked-") }.sorted()

    @Test
    fun theSameBackupRestoresIntoTheDebugAppAndItsSessionsAreStillThere() = runBlocking<Unit> {
        var restarts = 0
        val backup = BackupControl(EmbeddedBridge, idle = { true }, seen = { observeGateway(ctx) }, restart = { restarts++ })
        val gateway = observeGateway(ctx)
        Log.i(TAG, "gateway ${gateway.status}")
        if (gateway.alive()) {
            val probe = File(imports.apply { mkdirs() }, "picked-gateway-${System.nanoTime()}.zip").apply { writeText("x") }
            backup.open()
            backup.picked(probe.path, "probe.zip")
            assertEquals(BackupControl.GATEWAY, backup.state.value?.error)
            assertFalse(probe.exists())
            backup.close()
            Log.i(TAG, "restore not run (gateway on)")
            assumeTrue("the always-on gateway is on: the restore is refused, and the test never turns it off", false)
        }
        val lockWas = lock.exists()
        val original = config.takeIf { it.exists() }?.readBytes()
        val originalSha = sha(original)
        val before = listed()
        val (created, title) = canarySession()
        var file: BackupFile? = null
        var restoredSha: String? = null
        try {
            backup.open()
            val createMs = measureTimeMillis { backup.create() }
            file = checkNotNull(backup.state.value?.file) { "no backup: ${backup.state.value?.error}" }
            backup.handOff(file) { true }  // the share sheet is the activity's; here the zip is picked back at once

            val copy = checkNotNull(copyPicked(ctx.contentResolver, exportUri(ctx, file.path), imports)) { "the zip could not be copied" }
            assertEquals(imports.canonicalFile, File(copy.path).canonicalFile.parentFile)
            assertTrue(copy.path, File(copy.path).name.startsWith("picked-") && copy.path.endsWith(".zip"))
            assertEquals(file.name, copy.name)
            assertArrayEquals(File(file.path).readBytes(), File(copy.path).readBytes())

            val inspectMs = measureTimeMillis { backup.picked(copy.path, copy.name) }
            val holds = checkNotNull(backup.state.value?.picked?.holds) { "not read: ${backup.state.value?.error}" }
            assertEquals(file.parts, holds.parts)
            assertTrue("${holds.sessions} sessions", holds.sessions >= 1)
            assertTrue(File(copy.path).exists())

            val restoreMs = measureTimeMillis { backup.confirmRestore() }  // the live state.db page copy under this process's open core (§8)
            val ui = backup.state.value!!
            assertNull(ui.error)
            val report = checkNotNull(ui.report)
            assertEquals(file.parts, report.restored)
            assertEquals(holds.sessions, report.sessions)
            assertEquals(0, restarts)
            assertFalse("the picked copy is still there", File(copy.path).exists())
            restoredSha = sha(config.takeIf { it.exists() }?.readBytes())

            backup.close()
            assertNotNull("a restored page was left", backup.state.value)
            backup.restartNow()
            assertEquals(1, restarts)

            val canary = core.call(Methods.SESSION_LIST, SessionListParams(title = title)).sessions.map { it.id }
            assertTrue("$canary", created.storedSessionId in canary)
            val after = listed()
            assertTrue("${(before - after).size} earlier sessions are gone", after.containsAll(before))
            Log.i(TAG, "backup_create ms=$createMs bytes=${file.bytes} parts=${file.parts}; backup_inspect ms=$inspectMs " +
                       "sessions=${holds.sessions} skipped=${holds.skipped}; backup_restore ms=$restoreMs restored=${report.restored} " +
                       "skipped=${report.skipped}; sessions before=${before.size} after=${after.size}")
        } finally {
            cleanUp(created)
            val now = config.takeIf { it.exists() }?.readBytes()
            Log.i(TAG, "config.yaml before=$originalSha restored=$restoredSha differed=${restoredSha != null && restoredSha != originalSha}")
            if (!original.contentEquals(now)) {
                if (original != null) config.writeBytes(original) else config.delete()
            }
            file?.let { File(it.path).delete() }
            imports.listFiles()?.filter { it.name.startsWith("picked-") }?.forEach { it.delete() }
            if (!lockWas) lock.delete()
            assertEquals(originalSha, sha(config.takeIf { it.exists() }?.readBytes()))
        }
    }

    private fun exportsDir() = File(ctx.cacheDir, "exports")

    @Test
    fun aSessionExportLeavesTheBackupWaitingForTheShareSheet() = runBlocking<Unit> {
        val lockWas = lock.exists()
        val (created, _) = canarySession()
        val backup = BackupControl(EmbeddedBridge, idle = { true }, seen = { observeGateway(ctx) }, restart = {})
        var waiting: BackupFile? = null
        var exportedPath: String? = null
        try {
            backup.open()
            backup.create()
            val file = checkNotNull(backup.state.value?.file) { "no backup: ${backup.state.value?.error}" }
            waiting = file
            val zip = File(file.path)
            val before = sha(zip.readBytes())

            val exported = EmbeddedBridge.exportSession(StoredSessionId(created.storedSessionId))  // before sharing the zip
            exportedPath = exported.path

            assertTrue("the backup zip is gone", zip.exists())
            assertEquals(before, sha(zip.readBytes()))
            assertEquals(file, backup.state.value?.file)  // still waiting for the share sheet
            val served = ctx.contentResolver.openInputStream(exportUri(ctx, file.path))!!.use { it.readBytes() }
            assertArrayEquals(zip.readBytes(), served)
            assertEquals(exportsDir().canonicalFile, File(exported.path).canonicalFile.parentFile)
            val names = exportsDir().list().orEmpty().toSet()
            assertTrue("$names", file.name in names && exported.name in names)
            Log.i(TAG, "backup bytes=${file.bytes} export bytes=${exported.bytes}")
        } finally {
            waiting?.let { backup.handOff(it) { true } }  // nothing left waiting
            cleanUp(created)
            waiting?.let { File(it.path).delete() }
            exportedPath?.let { File(it).delete() }
            if (!lockWas) lock.delete()
            backup.close()
        }
    }

    @Test
    fun aPickedFileThatIsNoBackupIsRefusedAndRemoved() = runBlocking<Unit> {
        // backup_inspect writes nothing and is never gated by the gateway in the Python half; the app's gate is held off
        // here so the file's own refusal is what answers, whatever the gateway does on the shared phone.
        ready  // the interpreter starts with the core
        val backup = BackupControl(EmbeddedBridge, idle = { true }, seen = { GatewaySeen(GatewayStatus.STOPPED) }, restart = {})
        val notes = File(exportsDir().apply { mkdirs() }, "notes.txt").apply { writeText("not a backup\n") }
        val shaBefore = sha(config.takeIf { it.exists() }?.readBytes())
        val picksBefore = picks()
        try {
            val copy = checkNotNull(copyPicked(ctx.contentResolver, exportUri(ctx, notes.path), imports))
            assertEquals("notes.txt", copy.name)
            backup.open()

            val ms = measureTimeMillis { backup.picked(copy.path, copy.name) }

            assertEquals("This file is not a zip archive.", backup.state.value?.error)
            assertNull(backup.state.value?.picked)
            assertFalse(File(copy.path).exists())
            assertEquals(picksBefore, picks())
            assertEquals(shaBefore, sha(config.takeIf { it.exists() }?.readBytes()))
            Log.i(TAG, "backup_inspect of a text file ms=$ms")
        } finally {
            notes.delete()
            backup.close()
        }
    }

    @Test
    fun thePickerAndTheRestartAreThePlatformsOwn() {
        val pick = OpenBackup().createIntent(ctx, BACKUP_TYPES)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, pick.action)
        assertTrue(pick.hasCategory(Intent.CATEGORY_OPENABLE))
        assertArrayEquals(arrayOf("application/zip"), pick.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        Log.i(TAG, "picker intent type=${pick.type} extras=${pick.extras?.keySet()}")
        // The app declares no <queries> for the picker, so package visibility hides it from the app's own resolveActivity
        // (startActivity is not filtered); the shell, which sees every package, asks for the same intent, opening nothing.
        val asked = instrumentation.uiAutomation.executeShellCommand(
            "cmd package query-activities --brief -a ${Intent.ACTION_OPEN_DOCUMENT} -c ${Intent.CATEGORY_OPENABLE} -t ${pick.type}")
        val answer = ParcelFileDescriptor.AutoCloseInputStream(asked).use { it.readBytes().decodeToString() }
        Log.i(TAG, "picker: ${answer.lines().map(String::trim).filter { "/" in it && "=" !in it }} (app-visible: " +
                   "${ctx.packageManager.resolveActivity(pick, 0) != null})")
        assertTrue("no document picker answers on this phone: $answer", "activities found" in answer && "No activities" !in answer)

        val restart = restartIntent(ctx)
        assertEquals(MainActivity::class.java.name, restart.component?.className)
        assertEquals(ctx.packageName, restart.component?.packageName)
        assertTrue(restart.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(restart.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
        // restartApp() itself is not called: it would end this test's process.
    }
}
