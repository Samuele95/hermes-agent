package com.nousresearch.hermes.android.settings

import com.nousresearch.hermes.bridge.contract.BackupCreateReply
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
 * The Backup page: [busy] while one call runs; [file] the finished zip, waiting until the share sheet has taken it;
 * [made] the last backup made, still shown once the sheet has it; [error] the words of a failure. A backup never holds
 * a key or a sign-in (D49), and no screen reads a wire type (§5).
 */
data class BackupUi(val busy: Boolean = false, val file: BackupFile? = null, val made: BackupFile? = null, val error: String? = null)
