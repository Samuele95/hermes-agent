package com.nousresearch.hermes.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.bridge.RuntimeInstaller
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The payload works where it will run: inside the app process, under the app's linker namespace
 * and W^X policy, rather than as the adb shell user the CP3 probes ran as.
 */
@RunWith(AndroidJUnit4::class)
class PayloadInAppTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val runtime by lazy { RuntimeInstaller(File(context.noBackupFilesDir, "runtime")).install(AssetPayload(context.assets)) }

    @Test
    fun everyExtensionModuleLoadsFromTheExtractedRuntime() {
        System.loadLibrary("python3.14")
        val modules = runtime.walkTopDown().filter { it.isFile && it.name.endsWith(".so") && "/app/" !in it.path }.toList()

        val failures = modules.mapNotNull { module ->
            runCatching { System.load(module.path) }.exceptionOrNull()?.let { "${module.relativeTo(runtime)}: ${it.message}" }
        }

        assertTrue("found only ${modules.size} extension modules", modules.size > 50)
        assertEquals("", failures.joinToString("\n"))
    }

    @Test
    fun theSystemShellRunsFromTheAppProcess() {
        val shell = ProcessBuilder("/system/bin/sh", "-c", "echo ok").redirectErrorStream(true).start()

        assertEquals("ok", shell.inputStream.bufferedReader().readText().trim())
        assertEquals(0, shell.waitFor())
    }
}
