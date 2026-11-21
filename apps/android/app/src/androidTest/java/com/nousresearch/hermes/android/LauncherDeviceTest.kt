package com.nousresearch.hermes.android

import android.app.usage.StorageStatsManager
import android.os.Process.myUserHandle
import android.os.storage.StorageManager
import android.system.Os
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Python launcher on the phone (R1, D54 row 1): the installer extracted `libhermes_pyexec.so`, the app's interpreter
 * pointed `$HOME/bin/python3` at it, and a child started through that symlink, with only the env keys the core passes a
 * stdio MCP server, runs Python with every native module. The child is started from the app process (same uid and
 * SELinux domain as the core's spawn). Measures what a child and the extraction cost under the tag R1Probe.
 */
@RunWith(AndroidJUnit4::class)
class LauncherDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val launcher get() = File(context.applicationInfo.nativeLibraryDir, "libhermes_pyexec.so")
    private val python3 get() = File(context.filesDir, "bin/python3")

    private class Ran(val exit: Int, val ms: Long, val stdout: String, val stderr: String)

    @Before
    fun theCoreIsReady() = runBlocking {
        // prepare() runs before the core: once it is Ready, ensure() has written the links.
        val app = context.applicationContext as HermesApplication
        val state = withTimeout(120_000) { app.core.first { it !is CoreState.Starting } }
        assertTrue("the core did not start: $state", state is CoreState.Ready)
    }

    /** Starts [command] with the core's safe env keys only (`_build_safe_env`): no LD_LIBRARY_PATH, no PYTHON*. */
    private fun run(vararg command: String): Ran {
        val builder = ProcessBuilder(*command)
        builder.environment().clear()
        builder.environment() += mapOf(
            "HOME" to context.filesDir.path, "PATH" to "/system/bin", "TMPDIR" to context.cacheDir.path, "LANG" to "C.UTF-8",
        )
        val started = System.nanoTime()
        val process = builder.start()
        val watchdog = thread { if (!process.waitFor(60, TimeUnit.SECONDS)) process.destroyForcibly() }
        var stderr = ""
        val errors = thread { stderr = process.errorStream.bufferedReader().readText() }
        val stdout = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        val ms = (System.nanoTime() - started) / 1_000_000
        errors.join()
        watchdog.join()
        return Ran(exit, ms, stdout, stderr)
    }

    @Test
    fun theLauncherIsExtractedIntoTheNativeLibraryDir() {
        val libs = File(context.applicationInfo.nativeLibraryDir).listFiles().orEmpty()
        Log.i("R1Probe", "nativeLibraryDir ${libs.size} files, ${libs.sumOf { it.length() }} bytes")

        assertTrue("the launcher is in nativeLibraryDir (${launcher.path})", launcher.isFile)
        assertTrue("the launcher is executable", launcher.canExecute())
    }

    @Test
    fun homeBinPython3PointsAtTheLauncherAndTheRuntime() {
        val runtime = File(context.noBackupFilesDir, "runtime").listFiles()!!.single { it.name != ".lock" }

        assertEquals(launcher.canonicalPath, python3.canonicalPath)
        assertEquals(launcher.canonicalPath, File(context.filesDir, "bin/python").canonicalPath)
        assertEquals(runtime.path, File(context.filesDir, "bin/.hermes-runtime").readText().trim())
    }

    @Test
    fun aChildThroughTheSymlinkImportsTheNativeModules() {
        val ran = run(python3.path, "-c", SCRIPT)
        val why = "exit ${ran.exit}, stdout [${ran.stdout}], stderr [${ran.stderr}]"

        assertEquals(why, 0, ran.exit)
        val report = JSONObject(ran.stdout.trim().lines().lastOrNull().orEmpty().ifEmpty { error("nothing on the pipe: $why") })
        Log.i("R1Probe", "child report $report")
        assertEquals(why, python3.path, report.getString("executable"))
        assertEquals(why, true, report.get("fts5"))
        assertTrue(why, report.getBoolean("fernet"))
        assertTrue(why, report.getBoolean("cert_file_exists"))
    }

    @Test
    fun aChildNamedByABareArgv0StillWritesToItsPipe() {
        // A spawner that execs it by name (argv[0] "python3", no path) and a PATH without it: CPython alone would find
        // no executable, and python.org's _android_support sends an empty-executable process's stdout to logcat (L8).
        val ran = run("/system/bin/sh", "-c", "cd '${python3.parent}' && exec -a python3 ./python3 -c " +
            "'import json, sys; print(json.dumps({\"executable\": sys.executable}))'")
        val why = "exit ${ran.exit}, stdout [${ran.stdout}], stderr [${ran.stderr}]"

        assertEquals(why, 0, ran.exit)
        assertEquals(why, "python3", JSONObject(ran.stdout.trim().ifEmpty { error("nothing on the pipe: $why") }).getString("executable"))
    }

    @Test
    fun aChildStartsInMeasuredTime() {
        run(python3.path, "-c", "pass") // warm-up: the page cache
        val bare = (1..5).map { run(python3.path, "-c", "pass").also { assertEquals(it.stderr, 0, it.exit) } }
        val full = (1..5).map { run(python3.path, "-c", SCRIPT).also { assertEquals(it.stderr, 0, it.exit) } }
        val hwm = full.maxOf { JSONObject(it.stdout.trim().lines().last()).getInt("vm_hwm_kb") }
        val median = { runs: List<Ran> -> runs.map { it.ms }.sorted()[runs.size / 2] }

        Log.i("R1Probe", "child -c pass median ${median(bare)} ms ${bare.map { it.ms }}; import script median " +
            "${median(full)} ms ${full.map { it.ms }}; largest VmHWM $hwm kB")
        assertTrue("-c pass took ${median(bare)} ms", median(bare) < 10_000)
    }

    @Test
    fun withoutItsRuntimePointerTheLauncherRefusesInOneLine() {
        val dir = File(context.cacheDir, "r1").apply { deleteRecursively(); mkdirs() }
        try {
            Os.symlink(launcher.path, File(dir, "python3").path)

            val ran = run(File(dir, "python3").path, "-c", "print('ran')")

            assertEquals(ran.stderr, 2, ran.exit)
            assertEquals("", ran.stdout)
            assertEquals(ran.stderr, 1, ran.stderr.trim().lines().size)
            assertTrue(ran.stderr, ".hermes-runtime" in ran.stderr)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun whatTheInstallCosts() {
        val stats = context.getSystemService(StorageStatsManager::class.java)
            .queryStatsForPackage(StorageManager.UUID_DEFAULT, context.packageName, myUserHandle())
        val libs = File(context.applicationInfo.nativeLibraryDir).listFiles().orEmpty()

        Log.i("R1Probe", "apk ${File(context.applicationInfo.sourceDir).length()} bytes; nativeLibraryDir " +
            "${libs.sumOf { it.length() }} bytes; appBytes ${stats.appBytes}; dataBytes ${stats.dataBytes}")
        assertTrue(stats.appBytes > 0)
    }

    private companion object {
        /** One JSON line on stdout: what the child's interpreter is and which native modules it loaded. */
        val SCRIPT = """
            import importlib.util, json, os, sqlite3, ssl, sys
            import pydantic_core
            from cryptography.fernet import Fernet
            try:
                sqlite3.connect(":memory:").execute("create virtual table t using fts5(x)")
                fts5 = True
            except Exception as e:
                fts5 = repr(e)
            f = Fernet(Fernet.generate_key())
            rpds = "absent (R2)"
            if importlib.util.find_spec("rpds"):
                import rpds
                rpds = "present"
            status = dict(l.split(":", 1) for l in open("/proc/self/status") if l.startswith(("VmRSS", "VmHWM")))
            print(json.dumps({
                "executable": sys.executable, "openssl": ssl.OPENSSL_VERSION, "fts5": fts5,
                "pydantic_core": pydantic_core.__version__, "fernet": f.decrypt(f.encrypt(b"r1")) == b"r1", "rpds": rpds,
                "ssl_cert_file": os.environ.get("SSL_CERT_FILE"),
                "cert_file_exists": os.path.isfile(os.environ.get("SSL_CERT_FILE", "")),
                "vm_rss_kb": int(status["VmRSS"].split()[0]), "vm_hwm_kb": int(status["VmHWM"].split()[0]),
            }))
        """.trimIndent()
    }
}
