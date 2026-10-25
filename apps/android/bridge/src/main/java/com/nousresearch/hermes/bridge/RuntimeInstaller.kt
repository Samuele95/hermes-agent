package com.nousresearch.hermes.bridge

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.ZipInputStream

/** The runtime payload (stdlib, site-packages, core source) shipped in the APK as one zip. */
interface PayloadSource {
    /** Identity of this payload's contents; the install directory is named after it. */
    val buildId: String

    fun open(): InputStream
}

/**
 * Extracts the runtime payload into `root/<buildId>`, read-only for the core once installed.
 *
 * Installing is idempotent per build id and atomic: the payload is unpacked into a temporary
 * sibling and renamed into place, so a process killed mid-extraction never leaves a half runtime
 * that a later start would mistake for a complete one. Directories of other builds are removed.
 *
 * The app's processes (the chat's, and the optional gateway's) may install at the same time: one
 * lock file under [root] makes them take turns, and the second finds the runtime already there.
 */
class RuntimeInstaller(private val root: File) {
    private companion object {
        const val LOCK = ".lock"

        /** File locks are per process: threads of one process also take turns on this monitor first. */
        val inProcess = Any()
    }

    fun install(source: PayloadSource): File = synchronized(inProcess) {
        root.mkdirs()
        RandomAccessFile(File(root, LOCK), "rw").use { file -> file.channel.lock().use { installLocked(source) } }
    }

    private fun installLocked(source: PayloadSource): File {
        val target = File(root, source.buildId)
        if (!target.isDirectory) {
            root.mkdirs()
            val staging = File(root, ".staging-${source.buildId}")
            staging.deleteRecursively()
            try {
                source.open().use { extract(it, staging) }
                if (!staging.renameTo(target)) throw IOException("could not move $staging to $target")
            } finally {
                staging.deleteRecursively()
            }
        }
        root.listFiles()?.filter { it != target && it.name != LOCK }?.forEach { it.deleteRecursively() }
        return target
    }

    private fun extract(input: InputStream, into: File) {
        val base = into.canonicalFile
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(base, entry.name).canonicalFile
                if (!out.toPath().startsWith(base.toPath())) throw IOException("payload entry escapes the runtime dir: ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
    }
}
