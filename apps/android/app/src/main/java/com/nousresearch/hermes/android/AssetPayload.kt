package com.nousresearch.hermes.android

import android.content.res.AssetManager
import com.nousresearch.hermes.bridge.PayloadSource
import java.io.InputStream
import org.json.JSONObject

/**
 * The payload's manifest as packed (`payload.py:197-202`): the installer reads its build id, About its build id and
 * core commit (F13).
 */
fun AssetManager.payloadManifest(): String = open("payload.json").bufferedReader().use { it.readText() }

/** The runtime payload `scripts/android/payload.py` placed in the APK's assets. */
class AssetPayload(private val assets: AssetManager) : PayloadSource {
    override val buildId: String = JSONObject(assets.payloadManifest()).getString("build_id")

    override fun open(): InputStream = assets.open("runtime.zip")
}
