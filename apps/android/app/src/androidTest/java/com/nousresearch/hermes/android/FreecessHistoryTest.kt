package com.nousresearch.hermes.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Which Freecess history line decides whether Samsung holds the gateway's sticky restart (L21, D38). */
@RunWith(AndroidJUnit4::class)
class FreecessHistoryTest {
    private fun history(lines: String) = "ACTIVITY MANAGER MARs HISTORY (dumpsys activity mars history)\n$lines"

    private fun last(dump: String) = FreecessHistory.lastRecord(dump, PKG, UID)

    @Test
    fun aSignalUnfreezeHoldsStickyRestarts() {
        val record = last(history("""
            [11/02 15:32:04.720] [FRZ] [Bg com.nousresearch.hermes.debug 10344]
            [11/02 15:58:23.304] [UFZ] [Signal com.nousresearch.hermes.debug 10344 11/02 15:32:04.719]
        """.trimIndent()))

        assertTrue(record!!.holdsStickyRestarts)
        assertEquals(SIGNAL, record.line)
    }

    @Test
    fun aLaterUnfreezeForAnotherReasonLetsTheTestRun() {
        val record = last(history("""
            [11/02 15:32:04.720] [FRZ] [Bg com.nousresearch.hermes.debug 10344]
            $SIGNAL
            [11/03 10:43:28.445] [LEV] [[FRZ] 10344 10364 ]
            $TOP_APP
        """.trimIndent()))

        assertFalse(record!!.holdsStickyRestarts)
        assertEquals("TopApp-ScreenOn", record.reason)
    }

    @Test
    fun aBatchFreezeListingTheUidHolds() {
        val record = last(history("""
            $TOP_APP
            [11/03 10:40:00.764] [LEV] [[FRZ] 10158 10355 10344 10347 10112 10352 10293 10292 10132 10216 10244 10350 10377 10299 10254 10369 10364 10139 10354 10368 [DIS] 10304 10315 10370 10297 10290 10074 10362]
        """.trimIndent()))

        assertTrue(record!!.holdsStickyRestarts)
        assertTrue(record.frozen)
        assertNull(record.reason)
    }

    @Test
    fun aUidListedOnlyAsDisabledIsNotFrozen() {
        val record = last(history("""
            $TOP_APP
            [11/03 10:45:00.000] [LEV] [[FRZ] 10158 10355 [DIS] 10344 10304]
        """.trimIndent()))

        assertEquals(TOP_APP, record?.line)
    }

    @Test
    fun otherPackagesAndProcessLinesDoNotCount() {
        val record = last(history("""
            $TOP_APP
            [11/02 23:34:02.581] [FRZ] [Bg com.nousresearch.hermes.debug.test 10375]
            [11/02 23:40:00.000] [UFZ] [Signal com.nousresearch.hermes 10377 11/02 15:56:05.882]
            [11/02 15:32:34.011] [FRZ] [CAO 8251(10344)]
            [11/03 11:00:00.000] [FRZ] [Bg com.nousresearch.hermes.debug 1010344]
        """.trimIndent()))

        assertEquals(TOP_APP, record?.line)
    }

    @Test
    fun onlyTheExactSignalReasonHolds() {
        val record = last(history("""
            [11/02 15:32:04.720] [FRZ] [Bg com.nousresearch.hermes.debug 10344]
            [11/02 15:58:23.304] [UFZ] [OLAF:P-CancelPolicy com.nousresearch.hermes.debug 10344 11/02 15:56:05.882]
        """.trimIndent()))

        assertFalse(record!!.holdsStickyRestarts)
    }

    @Test
    fun noHistoryMeansNoRecord() {
        assertNull(last(""))
        assertNull(last("""
            mMARsTargetPackages --- size 66
            -RST 2026/11/03 10:23:29.556-PT     39-ST  1-SC F-DT -1-DR  0-DD F-Uid    10344(-)-curLv 0-maxLv 1-FAS N/   0(-)-BU   0,000000-Pkg com.nousresearch.hermes.debug--(R)
        """.trimIndent()))
        assertNull(last("Bad activity command, or no activities match: mars"))
    }

    private companion object {
        const val PKG = "com.nousresearch.hermes.debug"
        const val UID = 10344
        const val SIGNAL = "[11/02 15:58:23.304] [UFZ] [Signal com.nousresearch.hermes.debug 10344 11/02 15:32:04.719]"
        const val TOP_APP =
            "[11/03 10:43:40.812] [UFZ] [TopApp-ScreenOn com.nousresearch.hermes.debug 10344 11/03 10:43:28.439]"
    }
}
