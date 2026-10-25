package com.nousresearch.hermes.bridge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RuntimeInstallerTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("runtime-installer").toFile()
    }

    /** What the root holds besides the installers' shared lock file. */
    private fun installedEntries(): List<String> = root.list()!!.filter { it != ".lock" }.sorted()

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray =
        zipOfBytes(*entries.map { (name, body) -> name to body.toByteArray() }.toTypedArray())

    private fun zipOfBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, body) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private class Payload(override val buildId: String, private val bytes: ByteArray) : PayloadSource {
        var opens = 0
        override fun open(): InputStream {
            opens++
            return ByteArrayInputStream(bytes)
        }
    }

    @Test
    fun installsThePayloadUnderItsBuildId() {
        val dir = RuntimeInstaller(root).install(Payload("b1", zipOf("lib/python3.14/os.py" to "x", "site/hermes.py" to "y")))

        assertEquals(File(root, "b1"), dir)
        assertEquals("x", File(dir, "lib/python3.14/os.py").readText())
        assertEquals("y", File(dir, "site/hermes.py").readText())
    }

    /** Hands its bytes out slowly, so two installers overlap for the whole extraction. */
    private class SlowPayload(override val buildId: String, private val bytes: ByteArray) : PayloadSource {
        val opens = java.util.concurrent.atomic.AtomicInteger()
        override fun open(): InputStream {
            opens.incrementAndGet()
            return object : InputStream() {
                private val inner = ByteArrayInputStream(bytes)
                override fun read(): Int = inner.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    Thread.sleep(5)
                    return inner.read(b, off, minOf(len, 64))
                }
            }
        }
    }

    @Test
    fun twoInstallersAtOnceShareOneExtraction() {
        // The chat's process and the gateway's (`:gateway`) may both start on a fresh install.
        val payload = SlowPayload("b1", zipOfBytes(*(1..20).map { "lib/m$it.py" to Random.nextBytes(512) }.toTypedArray()))
        val installed = java.util.concurrent.ConcurrentLinkedQueue<Result<File>>()
        val threads = (1..2).map { Thread { installed += runCatching { RuntimeInstaller(root).install(payload) } } }

        threads.forEach(Thread::start)
        threads.forEach { it.join(30_000) }

        assertEquals(listOf(File(root, "b1"), File(root, "b1")), installed.map { it.getOrThrow() })
        assertEquals(1, payload.opens.get())
        assertEquals(20, File(root, "b1/lib").listFiles()!!.size)
        assertEquals(listOf("b1"), installedEntries())
    }

    @Test
    fun twoProcessesAtOnceShareOneExtraction() {
        // The in-process monitor cannot order two processes: only the lock file does.
        val zip = File(root.parentFile, "${root.name}.zip").apply {
            writeBytes(zipOfBytes(*(1..20).map { "lib/m$it.py" to Random.nextBytes(512) }.toTypedArray()))
            deleteOnExit()
        }
        val log = File(root.parentFile, "${root.name}.opens").apply { deleteOnExit() }
        val java = File(System.getProperty("java.home"), "bin/java").path
        val children = (1..2).map {
            ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), RuntimeInstallerChild::class.java.name,
                           root.path, zip.path, log.path).inheritIO().start()
        }

        val exits = children.map { if (it.waitFor(60, TimeUnit.SECONDS)) it.exitValue() else -1 }

        assertEquals(listOf(0, 0), exits)
        assertEquals(1, log.readLines().size)
        assertEquals(20, File(root, "b1/lib").listFiles()!!.size)
        assertEquals(listOf("b1"), installedEntries())
    }

    @Test
    fun aSecondInstallOfTheSameBuildDoesNotExtractAgain() {
        val payload = Payload("b1", zipOf("a.py" to "1"))
        val installer = RuntimeInstaller(root)

        installer.install(payload)
        installer.install(payload)

        assertEquals(1, payload.opens)
    }

    @Test
    fun aNewBuildReplacesTheOldOne() {
        val installer = RuntimeInstaller(root)
        installer.install(Payload("b1", zipOf("a.py" to "old")))

        val dir = installer.install(Payload("b2", zipOf("a.py" to "new")))

        assertEquals("new", File(dir, "a.py").readText())
        assertEquals(listOf("b2"), installedEntries())
    }

    @Test
    fun anInterruptedExtractionLeavesNoRuntimeBehind() {
        val broken = object : PayloadSource {
            override val buildId = "b1"
            override fun open(): InputStream = object : InputStream() {
                // Incompressible bytes, so the cut lands inside the entry's data, not after it.
                val head = ByteArrayInputStream(zipOfBytes("a.so" to Random(42).nextBytes(65536)).copyOf(4096))
                override fun read(): Int = head.read().takeIf { it >= 0 } ?: throw IOException("asset stream cut")
            }
        }

        assertThrows(IOException::class.java) { RuntimeInstaller(root).install(broken) }

        assertFalse(File(root, "b1").exists())
        assertTrue(installedEntries().isEmpty())
    }

    @Test
    fun anEntryEscapingTheRuntimeDirIsRejected() {
        // A normal entry first, so the failure comes from the escape check and not from an empty payload.
        val evil = Payload("b1", zipOf("ok.py" to "1", "../outside.py" to "pwned", "../../far.py" to "pwned"))

        assertThrows(IOException::class.java) { RuntimeInstaller(root).install(evil) }

        assertFalse(File(root, "outside.py").exists())
        assertFalse(File(root.parentFile, "far.py").exists())
        assertFalse(File(root, "b1").exists())
    }
}
