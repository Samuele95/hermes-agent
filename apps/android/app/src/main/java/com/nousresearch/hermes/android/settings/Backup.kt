package com.nousresearch.hermes.android.settings

import com.nousresearch.hermes.android.gateway.GatewaySeen
import com.nousresearch.hermes.android.gateway.GatewayStatus
import com.nousresearch.hermes.bridge.contract.BackupCreateReply
import com.nousresearch.hermes.bridge.contract.BackupInspectReply
import com.nousresearch.hermes.bridge.contract.BackupRestoreReply
import com.nousresearch.hermes.bridge.contract.BackupPart as WireBackupPart

/**
 * What a backup carries off the phone: D49's five parts, in the contract's order, and nothing else (bet 13, F3,
 * DC-P26). The set is closed (EJ-34): a sixth wire value fails to compile in [of], so no file the core adds later can
 * enter a backup unreviewed.
 */
enum class BackupPart {
    SESSIONS, MEMORIES, SKILLS, CONFIG, JOBS;

    companion object {
        /** The bridge's part, read here only (§5). Exhaustive, with no `else`. */
        fun of(wire: WireBackupPart): BackupPart = when (wire) {
            WireBackupPart.SESSIONS -> SESSIONS
            WireBackupPart.MEMORIES -> MEMORIES
            WireBackupPart.SKILLS -> SKILLS
            WireBackupPart.CONFIG -> CONFIG
            WireBackupPart.JOBS -> JOBS
        }
    }
}

/**
 * A backup zip in the app's cache (`cache/exports/`, §2.5), written by `backup_create`, handed to the share sheet,
 * replaced by the next: its [path], [name], size in [bytes] and the [parts] it holds, in [BackupPart] order.
 */
data class BackupFile(val path: String, val name: String, val bytes: Long, val parts: Set<BackupPart>) {
    companion object {
        /** The bridge's reply, read here only (§5), as `ExportFile.of`. */
        fun of(reply: BackupCreateReply): BackupFile =
            BackupFile(reply.path, reply.name, reply.bytes, reply.parts.map(BackupPart::of).toSortedSet())
    }
}

/**
 * What a picked archive would restore, read by `backup_inspect` before anything is replaced (P15 D1, D5): the
 * [parts] it holds (in [BackupPart] order), the members a restore would [skipped] (keys, sign-ins, other files) and
 * the [sessions] its database holds.
 */
data class BackupContents(val parts: Set<BackupPart>, val skipped: Long, val sessions: Long) {
    companion object {
        /** The bridge's reply, read here only (§5). */
        fun of(reply: BackupInspectReply): BackupContents =
            BackupContents(reply.parts.map(BackupPart::of).toSortedSet(), reply.skipped, reply.sessions)
    }
}

/**
 * What a restore replaced (§2.3): the parts [restored] (a subset of [BackupPart], in its order), the members
 * [skipped] (outside the five: keys and sign-ins among them) and the [sessions] the restored database holds.
 */
data class RestoreReport(val restored: Set<BackupPart>, val skipped: Long, val sessions: Long) {
    companion object {
        /** The bridge's reply, read here only (§5). */
        fun of(reply: BackupRestoreReply): RestoreReport =
            RestoreReport(reply.restored.map(BackupPart::of).toSortedSet(), reply.skipped, reply.sessions)
    }
}

/**
 * An archive the user picked, copied into the app's cache at [path] (`cache/imports/picked-*.zip`, D10) under its
 * own [name]; [holds] what it would restore, null while it is read (D4).
 */
data class PickedBackup(val path: String, val name: String, val holds: BackupContents? = null)

/**
 * The Backup page: [busy] while one call runs; [file] the finished zip, waiting until the share sheet has taken it;
 * [made] the last backup made, still shown once the sheet has it; [picked] the archive picked for a restore, shown
 * with what it holds until confirmed or cancelled; [report] what a restore replaced, after which only the restart
 * leaves the page; [error] the words of a failure or refusal. A backup never holds a key or a sign-in (D49), and no
 * screen reads a wire type (§5).
 */
data class BackupUi(val busy: Boolean = false, val file: BackupFile? = null, val made: BackupFile? = null, val error: String? = null,
                    val picked: PickedBackup? = null, val report: RestoreReport? = null)

/**
 * Whether the page can be left (or a new call start): not while a call runs, and never once a restore has replaced
 * this phone's data, since the chat process's sessions, caches and config are older than the restored files (D7). Pure.
 */
fun BackupUi.leavable(): Boolean = !busy && report == null

/**
 * Whether the always-on gateway is alive enough to hold `state.db` and its runtime lock: starting, running,
 * restarting or stopping. A stopped, failed or dead gateway holds neither (DC-P28). Pure.
 */
fun GatewaySeen.alive(): Boolean = status in ALIVE

private val ALIVE = setOf(GatewayStatus.STARTING, GatewayStatus.RUNNING, GatewayStatus.RESTARTING, GatewayStatus.STOPPING)
