package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.bridgeJson
import java.io.File
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Where the chat keeps each stored session's queue, so it outlives the process (DC-M5): [load] and [save] by the
 * session's stored key, a pair of functions rather than an interface (§5). [load] answers an empty queue for one never
 * saved; [save] of an empty queue forgets it. Only the items and the pause are kept, never the edit (D7).
 */
class QueueStore(val load: suspend (StoredSessionId) -> QueueUi, val save: suspend (StoredSessionId, QueueUi) -> Unit) {
    companion object {
        /** Queues kept in [kept] for this process only (JVM tests, the default): an empty queue is no entry. */
        fun inMemory(kept: MutableMap<StoredSessionId, QueueUi> = mutableMapOf()): QueueStore = QueueStore(
            load = { key -> synchronized(kept) { kept[key] } ?: QueueUi() },
            save = { key, queue ->
                synchronized(kept) { if (queue.items.isEmpty()) kept.remove(key) else kept[key] = queue.copy(editing = null) }
            })
    }
}

/**
 * One app-private file per stored session in [dir] (never backed up: `allowBackup=false`), named by the key
 * URL-encoded, so a key can never leave [dir]. Written in one rename, so a reader never sees half a file; one that is
 * missing or cannot be read or decoded is an empty queue. Off the main thread (§6, D13).
 */
fun queueFiles(dir: File): QueueStore {
    fun fileOf(key: StoredSessionId) = File(dir, URLEncoder.encode(key.value, "UTF-8") + ".json")
    return QueueStore(
        load = { key ->
            withContext(Dispatchers.IO) {
                runCatching { bridgeJson.decodeFromString(SavedQueue.serializer(), fileOf(key).readText()) }.getOrNull()
                    ?.let { saved -> QueueUi(saved.items.map { Queued(it.id, it.text) }, saved.paused) }
                    ?: QueueUi()
            }
        },
        save = { key, queue ->
            withContext(Dispatchers.IO) {
                val file = fileOf(key)
                if (queue.items.isEmpty()) {
                    file.delete()
                } else {
                    dir.mkdirs()
                    val next = File(dir, "${file.name}.tmp")
                    next.writeText(bridgeJson.encodeToString(SavedQueue.serializer(),
                                                             SavedQueue(queue.items.map { SavedItem(it.id, it.text) }, queue.paused)))
                    next.renameTo(file)
                }
            }
        })
}

/** The queue file's format: the items and the pause only (D7). */
@Serializable
private data class SavedQueue(val items: List<SavedItem>, val paused: Boolean)

@Serializable
private data class SavedItem(val id: Long, val text: String)
