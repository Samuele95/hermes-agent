package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.android.gateway.GatewaySeen
import com.nousresearch.hermes.android.gateway.GatewayStatus
import com.nousresearch.hermes.bridge.CoreEvent
import com.nousresearch.hermes.bridge.protocol.Events
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** The core's job tool (`cronjob_manage`, tools/cronjob_tools.py:1192): the design's `cronjob*` (D39 F11). */
private const val JOB_TOOL = "cronjob"

/** The actions that leave a job waiting for the gateway's ticker (V3); the core lower-cases and trims the word. */
private val SCHEDULING = setOf("create", "resume")

/** The gateway states that fire a job now or once the gateway is up (V2). */
private val FIRING = setOf(GatewayStatus.RUNNING, GatewayStatus.STARTING, GatewayStatus.RESTARTING)

/**
 * [event] is the completion of a job-tool call that scheduled a job: a `create` not made paused, or a `resume`,
 * that the core answered `success: true`. Any other event, action or answer, an unreadable payload included, is
 * not (total: never throws).
 */
internal fun schedulesAJob(event: CoreEvent): Boolean = runCatching {
    val done = event.decode(Events.TOOL_COMPLETE) ?: return false
    val args = done.args ?: return false
    // The result's liveness flag and warning are a guess from the chat process with a CLI remedy: never read (DC-J4).
    done.name.startsWith(JOB_TOOL) &&
        (args["action"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() in SCHEDULING &&
        (args["paused"] as? JsonPrimitive)?.booleanOrNull != true &&
        ((done.result as? JsonObject)?.get("success") as? JsonPrimitive)?.booleanOrNull == true
}.getOrDefault(false)

/** The gateway will fire a job now or once it is up: running, starting or restarting (V2, as J5's banner V7). */
internal fun firesJobs(seen: GatewaySeen): Boolean = seen.status in FIRING
