package com.nousresearch.hermes.android

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.io.IOException

/** The types the system document picker offers for a restore: zip files only (§3.1, P15 D14). */
val BACKUP_TYPES = arrayOf("application/zip")

/**
 * The system document picker for a backup (§3.1): androidx's `OpenDocument`, plus `CATEGORY_OPENABLE`, which it leaves
 * out, so the picker offers only files that open as a stream (the app copies the archive through one), not virtual ones.
 */
class OpenBackup : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addCategory(Intent.CATEGORY_OPENABLE)
}

/** A picked archive copied into the app's cache: its [path] there and the [name] the user saw. */
data class PickedCopy(val path: String, val name: String)

/**
 * Copies the archive the system document picker answered ([uri]) into [folder] (`cache/imports/`) as
 * `picked-<nanoTime>.zip`, one name per pick (D10), under its display name ("backup.zip" when the provider names
 * none). The picker grants a read of that one URI, so the app holds no storage permission (§3.4). A stream that could
 * not be opened or read, or a refused grant, answers null and leaves no partial file.
 */
fun copyPicked(resolver: ContentResolver, uri: Uri, folder: File): PickedCopy? {
    val name = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { row ->
            if (row.moveToFirst()) row.getString(0) else null
        }
    } catch (_: SecurityException) {
        null
    } ?: "backup.zip"
    folder.mkdirs()
    val target = File(folder, "picked-${System.nanoTime()}.zip")
    return try {
        val input = resolver.openInputStream(uri) ?: throw IOException("no stream")
        input.use { source -> target.outputStream().use { source.copyTo(it) } }
        PickedCopy(target.path, name)
    } catch (_: IOException) {
        target.delete()
        null
    } catch (_: SecurityException) {
        target.delete()
        null
    }
}

/** A fresh task for [MainActivity], the activity the restarted process opens on (§3.1's `restartApp`). */
fun restartIntent(context: Context): Intent = Intent.makeRestartActivityTask(ComponentName(context, MainActivity::class.java))
