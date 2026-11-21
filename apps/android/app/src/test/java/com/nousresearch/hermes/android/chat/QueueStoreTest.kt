package com.nousresearch.hermes.android.chat

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// The queue file outlives the process: read back by a fresh store.
class QueueStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val db1 = StoredSessionId("db1")

    @Test
    fun aSavedQueueIsReadBackByAFreshStore() = runBlocking {
        val dir = File(folder.root, "queue")
        val saved = QueueUi(listOf(Queued(0, "a"), Queued(3, "b\nc")), paused = true, editing = 3)

        queueFiles(dir).save(db1, saved)

        assertEquals(saved.copy(editing = null), queueFiles(dir).load(db1))  // D7: the edit is not kept
        assertEquals(QueueUi(), queueFiles(dir).load(StoredSessionId("db2")))
    }

    @Test
    fun anEmptyQueueLeavesNoFileAndAnIdStaysInsideTheFolder() = runBlocking {
        val dir = folder.root
        val store = queueFiles(dir)
        store.save(db1, QueueUi(listOf(Queued(0, "a"))))

        store.save(db1, QueueUi())

        assertEquals(emptyList<File>(), dir.listFiles()!!.toList())

        val odd = StoredSessionId("../x/y")
        val queue = QueueUi(listOf(Queued(1, "b")))
        store.save(odd, queue)

        assertEquals(1, dir.listFiles()!!.size)
        assertEquals(dir, dir.listFiles()!!.single().parentFile)
        assertEquals(1, folder.root.walk().count { it.isFile })
        assertEquals(queue, queueFiles(dir).load(odd))
    }

    @Test
    fun aFileItCannotReadIsAnEmptyQueue() = runBlocking {
        val dir = folder.root
        val store = queueFiles(dir)
        store.save(db1, QueueUi(listOf(Queued(0, "a"))))
        val file = dir.listFiles()!!.single()

        file.writeText("{not json")

        assertEquals(QueueUi(), store.load(db1))
    }
}
