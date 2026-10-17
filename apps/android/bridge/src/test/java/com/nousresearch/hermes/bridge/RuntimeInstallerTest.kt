package com.nousresearch.hermes.bridge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
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
        assertEquals(listOf("b2"), root.list()!!.sorted())
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
        assertTrue(root.list()!!.isEmpty())
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
