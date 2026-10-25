package com.nousresearch.hermes.bridge

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/**
 * One process of [RuntimeInstallerTest.twoProcessesAtOnceShareOneExtraction]: installs the zip at
 * `args[1]` into `args[0]`, slowly, and logs each extraction it starts to `args[2]`.
 */
object RuntimeInstallerChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val (root, zip, log) = args.map(::File)
        RuntimeInstaller(root).install(object : PayloadSource {
            override val buildId = "b1"

            override fun open(): InputStream {
                log.appendText("${ProcessHandle.current().pid()}\n")
                return object : FilterInputStream(zip.inputStream()) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        Thread.sleep(5)
                        return super.read(b, off, minOf(len, 64))
                    }
                }
            }
        })
    }
}
