package com.nousresearch.hermes.android.settings

import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.bridgeJson
import com.nousresearch.hermes.bridge.contract.BackupCreateReply
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

    private fun control() = BackupControl(bridge)

    private val backup = control()
    private val created = BackupFile.of(bridgeJson.decodeFromString(BackupCreateReply.serializer(), CREATED))
    private val other = created.copy(name = "hermes-backup-other.zip", path = "/cache/exports/hermes-backup-other.zip")

    private fun ui() = checkNotNull(backup.state.value) { "the page is closed" }
    private fun sent(function: String) = calls.count { it.first == function }

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
}
