package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.android.chat.TodoStatus.CANCELLED
import com.nousresearch.hermes.android.chat.TodoStatus.COMPLETED
import com.nousresearch.hermes.android.chat.TodoStatus.IN_PROGRESS
import com.nousresearch.hermes.android.chat.TodoStatus.PENDING
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The agent's plan as the core sends it: items read from the core's words, snapshots ordered by revision. */
class TodoListTest {
    private fun items(json: String): List<JsonElement> = Json.parseToJsonElement(json).jsonArray

    private fun list(vararg todos: Todo, revision: Long = 1) = TodoList(todos.toList(), revision)

    @Test
    fun itemsAreReadFromTheCoresWords() {
        val read = TodoList.of(items("""[{"id":"1","content":"Read","status":"completed"},
                                         {"id":"2","content":"Answer","status":"in_progress","parent":"1"}]"""), 3)

        assertEquals(TodoList(listOf(Todo("1", "Read", COMPLETED), Todo("2", "Answer", IN_PROGRESS, "1")), 3), read)
        assertEquals(listOf(Todo("7", "Count", PENDING)), TodoList.of(items("""[{"id":7,"content":"Count","status":"pending"}]"""), 1)!!.items)
    }

    @Test
    fun anItemTheAppCannotReadIsDroppedAndTheRestKept() {
        val read = TodoList.of(items("""[{"id":"1","content":"Keep","status":"pending"},
                                         {"id":"2","content":"Odd","status":"blocked"},
                                         {"id":"3","content":"  ","status":"pending"},
                                         {"content":"No id","status":"pending"},
                                         "x",
                                         {"id":"self","content":"Own parent","status":"cancelled","parent":"self"}]"""), 4)

        assertEquals(listOf(Todo("1", "Keep", PENDING), Todo("self", "Own parent", CANCELLED, null)), read!!.items)
    }

    @Test
    fun anUnusedStoreIsNoListAndAnEmptyRevisionIsAClear() {
        assertNull(TodoList.of(emptyList(), 0))

        val cleared = TodoList.of(emptyList(), 2)
        assertEquals(TodoList(emptyList(), 2), cleared)
        assertFalse(cleared!!.active)
    }

    @Test
    fun aNewerOrEqualRevisionWins() {
        val a = list(Todo("1", "a", PENDING), revision = 2)
        val b = list(Todo("1", "b", PENDING), revision = 1)
        val c = list(Todo("1", "c", COMPLETED), revision = 2)
        val d = list(Todo("1", "d", PENDING), revision = 5)

        assertEquals(a, a.after(b))
        assertEquals(c, a.after(c))
        assertEquals(d, a.after(d))
    }

    @Test
    fun activeDoneAndCurrentReadTheStatuses() {
        val resolved = list(Todo("1", "a", COMPLETED), Todo("2", "b", CANCELLED), Todo("3", "c", COMPLETED))
        assertFalse(resolved.active)
        assertEquals(2, resolved.done)
        assertNull(resolved.current)

        val waiting = list(Todo("1", "a", COMPLETED), Todo("2", "b", PENDING), Todo("3", "c", PENDING))
        assertTrue(waiting.active)
        assertEquals(1, waiting.done)
        assertEquals(Todo("2", "b", PENDING), waiting.current)

        val working = list(Todo("1", "a", PENDING), Todo("2", "b", IN_PROGRESS), Todo("3", "c", CANCELLED))
        assertTrue(working.active)
        assertEquals(0, working.done)
        assertEquals(Todo("2", "b", IN_PROGRESS), working.current)
    }

    @Test
    fun subtasksFollowTheirParent() {
        val a = Todo("a", "A", PENDING)
        val b = Todo("b", "B", PENDING, "a")
        val c = Todo("c", "C", PENDING, "zz")
        val x = Todo("x", "X", PENDING, "y")
        val y = Todo("y", "Y", PENDING, "x")

        assertEquals(listOf(a to 0, b to 1, c to 0, x to 0, y to 0), list(b, a, c, x, y).tree())
    }
}
