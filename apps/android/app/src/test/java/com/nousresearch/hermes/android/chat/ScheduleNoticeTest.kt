package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.android.gateway.GatewaySeen
import com.nousresearch.hermes.android.gateway.GatewayStatus
import com.nousresearch.hermes.bridge.CoreEvent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which completions scheduled a job, and which gateway fires one (D39 F11): the chat's notice is decided from these. */
class ScheduleNoticeTest {
    private fun event(payload: String, type: String = "tool.complete") = CoreEvent(type, "s1", Json.parseToJsonElement(payload))

    /** A `cronjob_manage` completion with [args] and [result] (each a JSON fragment; null leaves it out). */
    private fun job(args: String? = """{"action":"create","schedule":"every 2m","prompt":"Say tick."}""",
                    result: String? = """{"success":true,"job_id":"ab12"}""", name: String = "cronjob_manage", type: String = "tool.complete") =
        event(listOfNotNull(""""tool_id":"t"""", """"name":"$name"""", args?.let { """"args":$it""" }, result?.let { """"result":$it""" })
                  .joinToString(",", "{", "}"), type)

    @Test
    fun aJobTheAgentScheduledCounts() {
        // The core's own liveness guess and its CLI remedy ride along; the app does not read them (DC-J4).
        assertTrue(schedulesAJob(job(result = """{"success":true,"job_id":"ab12","gateway_running":false,"warning":"…hermes gateway start…"}""")))
        assertTrue(schedulesAJob(job(args = """{"action":" Create ","schedule":"every 2m","prompt":"Say tick."}""")))
        assertTrue(schedulesAJob(job(args = """{"action":"resume","job_id":"ab12"}""", result = """{"success":true,"job":{}}""")))
    }

    @Test
    fun nothingElseCounts() {
        for (action in listOf("list", "remove", "pause", "update", "run", "run_now", "trigger", "")) {
            assertFalse(action, schedulesAJob(job(args = """{"action":"$action","job_id":"ab12"}""")))
        }
        assertFalse("paused", schedulesAJob(job(args = """{"action":"create","schedule":"every 2m","prompt":"p","paused":true}""")))
        assertFalse("refused", schedulesAJob(job(result = """{"success":false,"error":"Invalid schedule"}""")))
        assertFalse("text result", schedulesAJob(job(result = """"Error: boom"""")))
        assertFalse("no result", schedulesAJob(job(result = null)))
        assertFalse("no args", schedulesAJob(job(args = null)))
        assertFalse("another tool", schedulesAJob(job(name = "terminal")))
        assertFalse("a start", schedulesAJob(job(type = "tool.start")))
        // Undecodable (no tool_id): false, never a throw.
        assertFalse("undecodable", schedulesAJob(event("""{"name":"cronjob_manage","args":{"action":"create"},"result":{"success":true}}""")))
    }

    @Test
    fun onlyARunningOrRisingGatewayFiresJobs() {
        val rising = setOf(GatewayStatus.RUNNING, GatewayStatus.STARTING, GatewayStatus.RESTARTING)
        for (status in GatewayStatus.entries) assertEquals(status.name, status in rising, firesJobs(GatewaySeen(status)))
        // Only the status matters.
        assertFalse(firesJobs(GatewaySeen(GatewayStatus.FAILED, "x", 7)))
    }
}
