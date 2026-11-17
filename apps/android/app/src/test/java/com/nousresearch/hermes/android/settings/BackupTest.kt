package com.nousresearch.hermes.android.settings

import com.nousresearch.hermes.bridge.bridgeJson
import com.nousresearch.hermes.bridge.contract.BackupCreateReply
import org.junit.Assert.assertEquals
import org.junit.Test
import com.nousresearch.hermes.bridge.contract.BackupPart as WireBackupPart

/** The backup's values read the bridge's replies; D49's five parts are a closed set, in the contract's order. */
class BackupTest {
    @Test
    fun everyWirePartIsOneOfTheFiveInTheContractsOrder() {
        assertEquals(BackupPart.entries, WireBackupPart.entries.map(BackupPart::of))
        assertEquals(listOf(BackupPart.SESSIONS, BackupPart.MEMORIES, BackupPart.SKILLS, BackupPart.CONFIG, BackupPart.JOBS),
                     BackupPart.entries)
    }

    @Test
    fun aBackupFileReadsTheBridgesReply() {
        val reply = bridgeJson.decodeFromString(BackupCreateReply.serializer(),
            """{"path":"/cache/exports/hermes-backup-x.zip","name":"hermes-backup-x.zip","bytes":1201832,"parts":["config","sessions"]}""")

        val file = BackupFile.of(reply)

        assertEquals("/cache/exports/hermes-backup-x.zip", file.path)
        assertEquals("hermes-backup-x.zip", file.name)
        assertEquals(1_201_832L, file.bytes)
        assertEquals(listOf(BackupPart.SESSIONS, BackupPart.CONFIG), file.parts.toList())  // in BackupPart order
    }
}
