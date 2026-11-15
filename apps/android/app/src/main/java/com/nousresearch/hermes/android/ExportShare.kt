package com.nousresearch.hermes.android

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.nousresearch.hermes.android.chat.ExportFile
import java.io.File

/** The export `FileProvider`'s authority, as the manifest declares it (`${applicationId}.exports`, sessions-12). */
fun exportAuthority(context: Context) = "${context.packageName}.exports"

/**
 * [file]'s `content://` URI through the app's export `FileProvider` (§3.4, D13): its one root is `cache/exports/`, so a
 * path outside it throws [IllegalArgumentException]. The activity and the device test both build it here.
 */
fun exportUri(context: Context, file: ExportFile): Uri = FileProvider.getUriForFile(context, exportAuthority(context), File(file.path))

/**
 * The share sheet for [file] at [uri] (D14): `ACTION_SEND` of `application/json`, the URI as the stream with a read grant,
 * and as the intent's `ClipData`, which carries the grant through the chooser; the receiving app may read that one URI only.
 */
fun shareIntent(uri: Uri, file: ExportFile): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newRawUri(file.name, uri)
    }
    return Intent.createChooser(send, null)
}
