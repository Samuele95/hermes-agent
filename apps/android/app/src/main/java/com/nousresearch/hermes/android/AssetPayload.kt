package com.nousresearch.hermes.android

import android.content.res.AssetManager
import com.nousresearch.hermes.bridge.PayloadSource
import java.io.InputStream
import org.json.JSONObject

/** The runtime payload `scripts/android/payload.py` placed in the APK's assets. */
class AssetPayload(private val assets: AssetManager) : PayloadSource {
    override val buildId: String =
        assets.open("payload.json").bufferedReader().use { JSONObject(it.readText()).getString("build_id") }

    override fun open(): InputStream = assets.open("runtime.zip")
}
