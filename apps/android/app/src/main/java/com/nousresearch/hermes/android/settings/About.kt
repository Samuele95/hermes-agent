package com.nousresearch.hermes.android.settings

import com.nousresearch.hermes.bridge.contract.RuntimeFactsReply
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The APK's version as `PackageInfo` holds it, read at the edge so [aboutOf] runs on the JVM without Android (D1). */
data class AppPackage(val versionName: String, val versionCode: Long)

/**
 * What a bug report names (F13): the build, each fact from the record that owns it (DDD). [appVersion] and [appBuild]
 * from the APK; [payload] (the build id) and [coreCommit] (the commit the payload was packed from, null when the
 * manifest names none) from the payload's manifest (`payload.py:197-202`, DC-M19: the phone's core has no stamp and
 * no git); [coreRelease], [python] and [sqlite] from the core and its interpreter through `runtime_facts`.
 */
data class About(val appVersion: String, val appBuild: Long, val payload: String, val coreCommit: String?,
                 val coreRelease: String, val python: String, val sqlite: String) {
    /** The block Copy details hands on (D8): one fixed English `Label: value` line per fact, no commit line without one. */
    fun report(): String =
        listOfNotNull("App: $appVersion ($appBuild)", "Hermes release: $coreRelease", coreCommit?.let { "Core commit: $it" },
                      "Runtime payload: $payload", "Python: $python", "SQLite: $sqlite").joinToString("\n")
}

/**
 * About from its three records (§5: the wire type stops here). Total (D3): a manifest field absent, null, blank or not
 * a string reads as no build id (`""`) and no commit; text that is not a JSON object reads as an object with no fields.
 */
fun aboutOf(packageInfo: AppPackage, payloadJson: String, facts: RuntimeFactsReply): About {
    val manifest = runCatching { Json.parseToJsonElement(payloadJson) }.getOrNull() as? JsonObject ?: JsonObject(emptyMap())
    return About(packageInfo.versionName, packageInfo.versionCode, manifest.text("build_id").orEmpty(),
                 manifest.text("core_commit")?.takeIf { it.isNotBlank() }, facts.coreRelease, facts.python, facts.sqlite)
}

/** The string at [key], or null when absent or not a string. */
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
