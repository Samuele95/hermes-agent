package com.nousresearch.hermes.android

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import com.nousresearch.hermes.android.chat.ExportFile
import com.nousresearch.hermes.android.settings.BackupFile
import java.io.File

/** The export `FileProvider`'s authority, as the manifest declares it (`${applicationId}.exports`, sessions-12). */
fun exportAuthority(context: Context) = "${context.packageName}.exports"

/**
 * [file]'s `content://` URI through the app's export `FileProvider` (§3.4, D13): its one root is `cache/exports/`, so a
 * path outside it throws [IllegalArgumentException]. The activity and the device test both build it here.
 */
fun exportUri(context: Context, file: ExportFile): Uri = exportUri(context, file.path)

/** The `content://` URI of the file at [path] through the export `FileProvider` (a session export or a backup zip, P15 D9). */
fun exportUri(context: Context, path: String): Uri = FileProvider.getUriForFile(context, exportAuthority(context), File(path))

/**
 * The share sheet for [file] at [uri] (D14): `ACTION_SEND` of `application/json`, the URI as the stream with a read grant,
 * and as the intent's `ClipData`, which carries the grant through the chooser; the receiving app may read that one URI only.
 */
fun shareIntent(uri: Uri, file: ExportFile): Intent = shareIntent(uri, file.name, "application/json")

/** The share sheet for the file [name] at [uri], of MIME [type], with the one-URI read grant through the chooser (P15 D9). */
fun shareIntent(uri: Uri, name: String, type: String): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        this.type = type
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newRawUri(name, uri)
    }
    return Intent.createChooser(send, null)
}

/**
 * One try at the share sheet ([start]): true only when it started an activity (architect #2). No app to take the file
 * ([ActivityNotFoundException]) or a path outside the provider's root ([IllegalArgumentException]) fails for good, since
 * no later start repairs it: [failed] is told, once (N15). Android's refusal ([SecurityException]) only defers it: nothing
 * is told, and the next start tries again (N6). Any other failure is not caught here.
 */
fun tryShare(start: () -> Unit, failed: () -> Unit): Boolean = try {
    start()
    true
} catch (_: ActivityNotFoundException) {
    failed()
    false
} catch (_: IllegalArgumentException) {
    failed()
    false
} catch (_: SecurityException) {
    false
}

/**
 * Hands [file] to the share sheet (sessions-12, F8): true only when an activity was started (architect #2). A stopped
 * activity starts none, since Android blocks a background start without a word, and a refusal (`SecurityException`)
 * starts none: the file then waits for the next start. No target, or a path outside the provider's root: Desktop's
 * "Could not export session", once, and [failed] is told so the file waits no longer ([tryShare], N15).
 */
fun ComponentActivity.shareExport(file: ExportFile, failed: (ExportFile) -> Unit = {}): Boolean {
    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return false
    return tryShare({ startActivity(shareIntent(exportUri(this, file), file)) }) {
        Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show()
        failed(file)
    }
}

/**
 * Hands the backup zip [file] to the share sheet as `application/zip` (settings-24, DC-P29): M13's `FileProvider` path,
 * under N6's and N15's rules through the one [tryShare]. True only when an activity was started; a stopped activity
 * starts none and the zip waits for the next start. No app to take it: "Could not share the backup", once, and [failed]
 * is told so the zip waits no longer.
 */
fun ComponentActivity.shareBackup(file: BackupFile, failed: (BackupFile) -> Unit = {}): Boolean {
    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return false
    return tryShare({ startActivity(shareIntent(exportUri(this, file.path), file.name, "application/zip")) }) {
        Toast.makeText(this, R.string.backup_share_failed, Toast.LENGTH_LONG).show()
        failed(file)
    }
}
