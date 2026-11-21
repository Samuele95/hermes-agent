package com.nousresearch.hermes.android.settings

import com.nousresearch.hermes.bridge.contract.RuntimeFactsReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * About names the build a bug report needs (F13), each fact from the record that owns it: the APK's version, the
 * payload's manifest (its build id and the core commit it was packed from, DC-M19) and the core's release through
 * `runtime_facts`.
 */
class AboutTest {
    @Test
    fun eachFactComesFromItsOwnRecord() {
        assertEquals(About("0.1.0", 1, "9031ce3226a542bc", "3a2fcbe0a5b7c31aa5c3908b054a0c938ab4ca92", "2026.9.24", "3.14.7",
                           "3.53.4"),
                     aboutOf(APP, MANIFEST, FACTS))
    }

    @Test
    fun aManifestWithoutACommitHasNone() {
        val commit = """"core_commit": "3a2fcbe0a5b7c31aa5c3908b054a0c938ab4ca92","""
        for (without in listOf("", """"core_commit": null,""", """"core_commit": "",""", """"core_commit": 7,""")) {
            val about = aboutOf(APP, MANIFEST.replace(commit, without), FACTS)

            assertNull(without, about.coreCommit)
            assertEquals(without, "9031ce3226a542bc", about.payload)
        }
    }

    @Test
    fun aManifestThatIsNotAnObjectReadsAsOneWithNoFields() {
        for (manifest in listOf("", "[]", "not json", """{"build_id":7}""")) {
            val about = aboutOf(APP, manifest, FACTS)

            assertEquals(manifest, ABOUT.copy(payload = "", coreCommit = null), about)  // the app's and the facts' fields still read
        }
    }

    @Test
    fun theCopiedBlockNamesEveryFactOnItsOwnLine() {
        assertEquals("""
            App: 0.1.0 (1)
            Hermes release: 2026.9.24
            Core commit: 3a2fcbe0a5b7c31aa5c3908b054a0c938ab4ca92
            Runtime payload: 9031ce3226a542bc
            Python: 3.14.7
            SQLite: 3.53.4
        """.trimIndent(), ABOUT.report())

        val without = ABOUT.copy(coreCommit = null).report()

        assertEquals(without, 5, without.lines().size)
        assertFalse(without, "Core commit" in without)
        assertFalse(without, "null" in without)
    }

    companion object {
        /** A manifest as `scripts/android/payload.py` writes it: `indent=1`, sorted keys. */
        val MANIFEST = """
            {
             "assembler_sha256": "cf73e77c4d312a38599980f0afff01d2249c84e35458ffff1ed99c7f0a05f0df",
             "build_id": "9031ce3226a542bc",
             "core_commit": "3a2fcbe0a5b7c31aa5c3908b054a0c938ab4ca92",
             "needs_4k_pages": [
              "libwebp_hermes.so"
             ],
             "python_sha256": "6d50cc3aa66e414a439594089bcdfb5f1264358155c70c1f00471c24cfb477fb",
             "wheelhouse": {
              "six-1.17.0-py2.py3-none-any.whl": "4721f391ed90541fddacab5acf947aa0d3dc7d27b2e1e8eda2be8970586c3274"
             }
            }
        """.trimIndent()
        val FACTS = RuntimeFactsReply("3.14.7", "3.53.4", true, "", "2026.9.24")
        val APP = AppPackage("0.1.0", 1)
        val ABOUT = About("0.1.0", 1, "9031ce3226a542bc", "3a2fcbe0a5b7c31aa5c3908b054a0c938ab4ca92", "2026.9.24", "3.14.7",
                          "3.53.4")
    }
}
