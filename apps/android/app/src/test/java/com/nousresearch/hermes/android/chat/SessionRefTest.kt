package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.CoreEvent
import com.nousresearch.hermes.bridge.protocol.SessionListRow
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** A session's two identities: the runtime id its frames carry, and the stored key its row goes by. */
class SessionRefTest {
    private val ref = SessionRef(RuntimeSessionId("s1"), StoredSessionId("db1"))

    private fun event(type: String, payload: String) = CoreEvent(type, "s1", Json.parseToJsonElement(payload))

    @Test
    fun aSessionInfoNamingAKeyMovesOnlyTheStoredKey() {
        assertEquals(SessionRef(RuntimeSessionId("s1"), StoredSessionId("db1b")),
                     ref.seen(event("session.info", """{"stored_session_id":"db1b"}""")))
    }

    @Test
    fun aBlankKeyOrAnotherEventKeepsTheRef() {
        assertEquals(ref, ref.seen(event("session.info", """{"stored_session_id":""}""")))
        assertEquals(ref, ref.seen(event("message.complete", """{"text":"hi","stored_session_id":"db-other"}""")))
    }

    @Test
    fun aDrawerRowIsKnownByItsStoredKeyAndTheTwoIdsNeverMeet() {
        assertEquals(StoredSessionId("db-1"), SessionListRow(id = "db-1", title = "Calendar", messageCount = 3).stored)
        val runtime: Any = RuntimeSessionId("x")
        val stored: Any = StoredSessionId("x")
        assertNotEquals(runtime, stored)  // the same text in two roles is two values
    }
}
