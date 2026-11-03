package com.nousresearch.hermes.android.chat

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A todo item's state, in the core's four words (tools/todo_tool.py VALID_STATUSES). */
enum class TodoStatus(val wire: String) {
    PENDING("pending"), IN_PROGRESS("in_progress"), COMPLETED("completed"), CANCELLED("cancelled");

    companion object {
        /** The status the core's [word] names; null for a word the app does not know. */
        fun of(word: String): TodoStatus? = entries.firstOrNull { it.wire == word.trim().lowercase() }
    }
}

/** One item of the agent's plan; [parent] nests it under another item. */
data class Todo(val id: String, val content: String, val status: TodoStatus, val parent: String? = null) {
    companion object {
        /** The core's item, or null when the app cannot read it: not an object, no id or content, an unknown status (EJ-1). */
        fun of(item: JsonElement): Todo? {
            val fields = item as? JsonObject ?: return null
            fun read(key: String) = (fields[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.trim()
            val id = read("id")?.takeIf { it.isNotEmpty() } ?: return null
            val content = read("content")?.takeIf { it.isNotEmpty() } ?: return null
            val status = read("status")?.let(TodoStatus::of) ?: return null
            return Todo(id, content, status, read("parent")?.takeIf { it.isNotEmpty() && it != id })
        }
    }
}

/** The agent's plan as the core last sent it; [revision] orders snapshots (a newer write has a higher one). */
data class TodoList(val items: List<Todo>, val revision: Long) {
    /** Something is left to do: an item pending or in progress. */
    val active: Boolean get() = items.any { it.status == TodoStatus.PENDING || it.status == TodoStatus.IN_PROGRESS }

    /** How many items are completed (a cancelled one is resolved, not done). */
    val done: Int get() = items.count { it.status == TodoStatus.COMPLETED }

    /** The item the agent is on: the first in progress, else the first pending. */
    val current: Todo? get() = items.firstOrNull { it.status == TodoStatus.IN_PROGRESS } ?: items.firstOrNull { it.status == TodoStatus.PENDING }

    /** [next] when it is at least as new as this one (the core's own rule), else this (invariant 2). */
    fun after(next: TodoList): TodoList = if (next.revision >= revision) next else this

    /** Items in reading order with their depth: parents before their subtasks; a dangling parent reads as none, a cycle flat. */
    fun tree(): List<Pair<Todo, Int>> {
        val ids = items.mapTo(HashSet()) { it.id }
        val (nested, roots) = items.partition { it.parent != null && it.parent in ids }
        val kids = nested.groupBy { it.parent }
        val seen = HashSet<String>()
        val out = ArrayList<Pair<Todo, Int>>(items.size)
        fun walk(todo: Todo, depth: Int) {
            if (!seen.add(todo.id)) return
            out += todo to depth
            kids[todo.id].orEmpty().forEach { walk(it, depth + 1) }
        }
        roots.forEach { walk(it, 0) }
        items.filter { seen.add(it.id) }.forEach { out += it to 0 }  // cycle members never reach a root: flat, nothing lost
        return out
    }

    companion object {
        /** The core's snapshot; null for an unused store (no items at revision 0, D3). */
        fun of(items: List<JsonElement>, revision: Long): TodoList? =
            if (items.isEmpty() && revision <= 0) null else TodoList(items.mapNotNull(Todo::of), revision)
    }
}
