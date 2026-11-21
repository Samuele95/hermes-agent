package com.nousresearch.hermes.android.settings

import com.nousresearch.hermes.android.gateway.GatewaySeen
import com.nousresearch.hermes.android.gateway.GatewayStatus
import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.bridgeJson
import com.nousresearch.hermes.bridge.contract.BackupCreateReply
import com.nousresearch.hermes.bridge.contract.BackupInspectReply
import com.nousresearch.hermes.bridge.contract.BackupRestoreReply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The Backup page (settings-24, bet 13): a backup made through the bridge waits for the share sheet (N6, N15); one call
 * at a time, and the page cannot be left while one runs.
 */
@OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher: a launched call runs until it suspends
class BackupControlTest {
    companion object {
        const val CREATED = """{"path":"/cache/exports/hermes-backup-2026-11-17-101500.zip","name":"hermes-backup-2026-11-17-101500.zip","bytes":1201832,"parts":["sessions","skills","config","jobs"]}"""
        const val HOLDS = """{"parts":["sessions","memories","skills","config","jobs"],"skipped":3,"sessions":12}"""
        const val RESTORED = """{"restored":["sessions","memories","skills","config","jobs"],"skipped":3,"sessions":12}"""
    }

    @get:Rule val tmp = TemporaryFolder()

    /** Every bridge call, `function to arg`, in the order sent. */
    private val calls = mutableListOf<Pair<String, String>>()
    /** A function held until its gate completes. */
    private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    /** Each function's answer; one may be `{"error": …}`, or throw. */
    private val replies = mutableMapOf<String, () -> String>("backup_create" to { CREATED })
    private val bridge = AppBridge { function, arg ->
        calls += function to arg
        gates[function]?.await()
        (replies[function] ?: error("unexpected $function")).invoke()
    }

    init {
        replies["backup_inspect"] = { HOLDS }
        replies["backup_restore"] = { RESTORED }
    }

    /** Whether no turn runs in the chat. */
    private var idle = true
    /** The always-on gateway, as the app sees it. */
    private var seen = GatewaySeen(GatewayStatus.STOPPED)
    /** Each restart of the app asked for. */
    private var restarts = 0

    private fun control() = BackupControl(bridge, { idle }, { seen }, { restarts++ })

    private val backup = control()
    private val created = BackupFile.of(bridgeJson.decodeFromString(BackupCreateReply.serializer(), CREATED))
    private val other = created.copy(name = "hermes-backup-other.zip", path = "/cache/exports/hermes-backup-other.zip")

    private fun ui() = checkNotNull(backup.state.value) { "the page is closed" }
    private fun sent(function: String) = calls.count { it.first == function }
    private val holds = BackupContents.of(bridgeJson.decodeFromString(BackupInspectReply.serializer(), HOLDS))
    private val report = RestoreReport.of(bridgeJson.decodeFromString(BackupRestoreReply.serializer(), RESTORED))

    /** A picked copy in the app's cache: a real file, so every "deleted" is a fact. */
    private fun copy(name: String = "picked-1.zip"): File = tmp.newFile(name).apply { writeBytes(byteArrayOf(0x50, 0x4b)) }

    /** The argument `{path}` the bridge is sent for [file]. */
    private fun pathOf(file: File) = """{"path":"${file.path}"}"""

    /** The page open, [copy] picked and read: the confirm shows; nothing recorded yet. */
    private suspend fun shown(copy: File, name: String = "phone.zip") {
        backup.open()
        backup.picked(copy.path, name)
        assertEquals(PickedBackup(copy.path, name, holds), ui().picked)
        calls.clear()
    }

    @Test
    fun openingShowsTheIdlePageAndAsksNothing() = runTest(UnconfinedTestDispatcher()) {
        assertNull(backup.state.value)

        backup.open()

        assertEquals(BackupUi(), ui())
        assertEquals(emptyList<Pair<String, String>>(), calls)
        backup.close()
        assertNull(backup.state.value)
    }

    @Test
    fun aBackupWaitsForTheShareSheetUntilOneOpens() = runTest(UnconfinedTestDispatcher()) {
        backup.open()
        gates["backup_create"] = CompletableDeferred()

        val making = launch { backup.create() }

        assertTrue(ui().busy)
        assertNull(ui().file)
        assertEquals(listOf("backup_create" to "{}"), calls)  // ask(BridgeCalls.BACKUP_CREATE): no params
        gates.getValue("backup_create").complete(Unit)
        making.join()
        assertEquals(created, ui().file)
        assertEquals(created, ui().made)
        assertFalse(ui().busy)

        backup.handOff(created) { false }  // a stopped activity, or Android's block
        assertEquals(created, ui().file)
        backup.handOff(other) { true }  // a file no longer waiting is not offered
        assertEquals(created, ui().file)
        val offered = mutableListOf<BackupFile>()
        backup.handOff(created) { offered += it; true }
        assertEquals(listOf(created), offered)
        assertNull(ui().file)
        assertEquals(created, ui().made)
    }

    @Test
    fun aSecondBackupWaitsUntilTheFirstIsShared() = runTest(UnconfinedTestDispatcher()) {
        backup.open()
        gates["backup_create"] = CompletableDeferred()

        val making = launch { backup.create() }
        backup.create()  // while the first runs
        gates.getValue("backup_create").complete(Unit)
        making.join()
        backup.create()  // while its file waits

        assertEquals(1, sent("backup_create"))
        backup.handOff(created) { true }
        backup.create()
        assertEquals(2, sent("backup_create"))
    }

    @Test
    fun aBackupTheShareSheetCanNeverTakeWaitsNoLonger() = runTest(UnconfinedTestDispatcher()) {
        backup.open()
        backup.create()

        backup.unshareable(other)
        assertEquals(created, ui().file)
        backup.unshareable(created)
        assertNull(ui().file)
        assertEquals(created, ui().made)

        backup.create()
        assertEquals(2, sent("backup_create"))  // N15: a new backup can start
    }

    @Test
    fun aFailedBackupSaysItsWords() = runTest(UnconfinedTestDispatcher()) {
        replies["backup_create"] = { """{"error":"The backup could not be written: No space left on device"}""" }
        backup.open()

        backup.create()

        assertEquals("The backup could not be written: No space left on device", ui().error)
        assertNull(ui().file)
        assertNull(ui().made)
        assertFalse(ui().busy)

        replies["backup_create"] = { throw IllegalStateException("trace") }
        backup.create()
        assertEquals("Something went wrong (IllegalStateException).", ui().error)  // never the message
        assertNull(ui().file)
        assertFalse(ui().busy)
    }

    @Test
    fun thePageCannotBeLeftWhileABackupIsWritten() = runTest(UnconfinedTestDispatcher()) {
        backup.open()
        gates["backup_create"] = CompletableDeferred()
        val making = launch { backup.create() }

        backup.close()
        assertTrue(ui().busy)  // the page stays
        gates.getValue("backup_create").complete(Unit)
        making.join()
        assertEquals(created, ui().file)

        backup.close()
        assertNull(backup.state.value)
    }

    @Test
    fun aPickedArchiveIsReadAndShownBeforeAnythingIsReplaced() = runTest(UnconfinedTestDispatcher()) {
        val copy = copy()
        backup.open()
        gates["backup_inspect"] = CompletableDeferred()

        val reading = launch { backup.picked(copy.path, "phone.zip") }

        assertTrue(ui().busy)
        assertEquals(PickedBackup(copy.path, "phone.zip"), ui().picked)
        assertEquals(listOf("backup_inspect" to pathOf(copy)), calls)
        gates.getValue("backup_inspect").complete(Unit)
        reading.join()
        assertEquals(PickedBackup(copy.path, "phone.zip", holds), ui().picked)
        assertFalse(ui().busy)
        assertNull(ui().error)
        assertEquals(0, sent("backup_restore"))
        assertTrue(copy.exists())
    }

    @Test
    fun aRestoreRunsOnlyAfterTheConfirmAndThenOffersTheRestart() = runTest(UnconfinedTestDispatcher()) {
        val copy = copy()
        backup.open()
        backup.picked(copy.path, "phone.zip")

        backup.confirmRestore()

        assertEquals(listOf("backup_inspect", "backup_restore"), calls.map { it.first })
        assertEquals(pathOf(copy), calls.last().second)
        assertEquals(report, ui().report)
        assertNull(ui().picked)
        assertFalse(ui().busy)
        assertEquals(0, restarts)  // not before the user has read the report
        backup.restartNow()
        assertEquals(1, restarts)
    }

    @Test
    fun theAppRestartsOnlyAfterARestore() = runTest(UnconfinedTestDispatcher()) {
        backup.restartNow()  // a closed page
        backup.open()
        backup.restartNow()
        assertEquals(0, restarts)

        shown(copy())
        backup.restartNow()
        assertEquals(0, restarts)

        replies["backup_restore"] = { """{"error":"The backup could not be restored: disk I/O error"}""" }
        backup.confirmRestore()
        backup.restartNow()
        assertEquals(0, restarts)
    }

    @Test
    fun aRestoreWhileATurnRunsIsRefusedInThePythonHalfsWordsAndSendsNothing() = runTest(UnconfinedTestDispatcher()) {
        val first = copy()
        backup.open()
        idle = false

        backup.picked(first.path, "phone.zip")

        assertEquals(BackupControl.BUSY, ui().error)
        assertEquals(emptyList<Pair<String, String>>(), calls)
        assertFalse(first.exists())
        assertNull(ui().picked)

        idle = true
        val second = copy("picked-2.zip")
        shown(second)
        idle = false
        backup.confirmRestore()

        assertEquals(BackupControl.BUSY, ui().error)
        assertEquals(0, sent("backup_restore"))
        assertFalse(second.exists())
        assertNull(ui().picked)
        assertFalse(ui().busy)
    }

    @Test
    fun aRestoreWhileTheGatewayRunsIsRefusedInThePythonHalfsWords() = runTest(UnconfinedTestDispatcher()) {
        backup.open()
        for (status in listOf(GatewayStatus.STARTING, GatewayStatus.RUNNING, GatewayStatus.RESTARTING, GatewayStatus.STOPPING)) {
            seen = GatewaySeen(status, pid = 7)
            val copy = copy("picked-$status.zip")

            backup.picked(copy.path, "phone.zip")

            assertEquals("$status", BackupControl.GATEWAY, ui().error)
            assertEquals("$status", emptyList<Pair<String, String>>(), calls)
            assertFalse("$status", copy.exists())
            assertNull("$status", ui().picked)
        }
        for (status in listOf(GatewayStatus.STOPPED, GatewayStatus.FAILED, GatewayStatus.DIED)) {
            seen = GatewaySeen(status)
            val copy = copy("picked-$status.zip")

            backup.picked(copy.path, "phone.zip")

            assertEquals("$status", listOf("backup_inspect" to pathOf(copy)), calls)
            assertNull("$status", ui().error)
            backup.cancel()
            calls.clear()
        }

        val copy = copy()
        shown(copy)
        seen = GatewaySeen(GatewayStatus.RUNNING, pid = 7)
        backup.confirmRestore()

        assertEquals(BackupControl.GATEWAY, ui().error)
        assertEquals(0, sent("backup_restore"))
        assertFalse(copy.exists())
        assertNull(ui().picked)
    }

    @Test
    fun aRefusalOfTheRestoreIsShownInItsWordsAndNothingRestarts() = runTest(UnconfinedTestDispatcher()) {
        for (words in listOf(BackupControl.BUSY, "The backup could not be restored: disk I/O error")) {
            replies["backup_restore"] = { """{"error":"$words"}""" }
            shown(copy("picked-${words.length}.zip"))

            backup.confirmRestore()

            assertEquals(words, ui().error)
            assertNull(ui().report)
            assertNull(ui().picked)
            assertFalse(ui().busy)
            assertTrue(ui().leavable())
            backup.restartNow()
            assertEquals(0, restarts)
        }
    }

    @Test
    fun anArchiveThatHoldsNothingToRestoreIsDroppedWithItsWords() = runTest(UnconfinedTestDispatcher()) {
        backup.open()
        for (words in listOf("This backup holds no sessions, memories, skills, settings or jobs.", "This file is not a zip archive.")) {
            replies["backup_inspect"] = { """{"error":"$words"}""" }
            val copy = copy("picked-${words.length}.zip")

            backup.picked(copy.path, "notes.zip")

            assertEquals(words, ui().error)
            assertNull(ui().picked)
            assertFalse(ui().busy)
            assertFalse(copy.exists())
        }
        assertEquals(0, sent("backup_restore"))
    }

    @Test
    fun cancellingOrClosingDropsThePickedCopyAndSendsNothing() = runTest(UnconfinedTestDispatcher()) {
        val first = copy()
        shown(first)

        backup.cancel()

        assertNull(ui().picked)
        assertFalse(first.exists())
        assertEquals(emptyList<Pair<String, String>>(), calls)

        val second = copy("picked-2.zip")
        shown(second)
        backup.close()

        assertNull(backup.state.value)
        assertFalse(second.exists())
        assertEquals(emptyList<Pair<String, String>>(), calls)
    }

    @Test
    fun oneThingAtATime() = runTest(UnconfinedTestDispatcher()) {
        val first = copy()
        val second = copy("picked-2.zip")
        backup.open()
        gates["backup_inspect"] = CompletableDeferred()
        val reading = launch { backup.picked(first.path, "phone.zip") }

        backup.picked(second.path, "other.zip")

        assertFalse(second.exists())
        assertTrue(first.exists())
        assertEquals(1, sent("backup_inspect"))
        gates.getValue("backup_inspect").complete(Unit)
        reading.join()
        assertEquals(PickedBackup(first.path, "phone.zip", holds), ui().picked)

        gates["backup_restore"] = CompletableDeferred()
        val restoring = launch { backup.confirmRestore() }
        val again = launch { backup.confirmRestore() }  // launched: a second call admitted would wait on the gate too
        backup.create()
        backup.cancel()

        assertEquals(1, sent("backup_restore"))
        assertEquals(0, sent("backup_create"))
        assertTrue(first.exists())
        assertEquals(PickedBackup(first.path, "phone.zip", holds), ui().picked)
        gates.getValue("backup_restore").complete(Unit)
        restoring.join()
        again.join()
        assertEquals(report, ui().report)
    }

    @Test
    fun aRestoredPageCannotBeLeftButByTheRestart() = runTest(UnconfinedTestDispatcher()) {
        shown(copy())
        backup.confirmRestore()
        assertEquals(report, ui().report)
        calls.clear()

        backup.close()
        assertEquals(report, backup.state.value?.report)  // the page stays
        backup.create()
        val third = copy("picked-3.zip")
        backup.picked(third.path, "third.zip")
        assertFalse(third.exists())
        backup.cancel()

        assertEquals(emptyList<Pair<String, String>>(), calls)
        assertEquals(report, ui().report)
        assertNull(ui().picked)
        assertFalse(ui().leavable())
    }

    @Test
    fun aFileThatCouldNotBeCopiedSaysSo() = runTest(UnconfinedTestDispatcher()) {
        backup.unreadable()
        assertNull(backup.state.value)

        backup.open()
        backup.unreadable()

        assertEquals(BackupControl.UNREADABLE, ui().error)
        assertEquals(emptyList<Pair<String, String>>(), calls)
    }
}
