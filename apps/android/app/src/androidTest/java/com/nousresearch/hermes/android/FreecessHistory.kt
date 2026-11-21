package com.nousresearch.hermes.android

/** One Freecess history line about a package: a freeze, or an unfreeze with its reason. */
internal data class FreecessRecord(val line: String, val frozen: Boolean, val reason: String?) {
    /** Samsung's killServicesLocked keeps a dead process's started services un-restarted in this state (L21, D38). */
    val holdsStickyRestarts: Boolean get() = frozen || reason == "Signal"
}

/**
 * Reads `dumpsys activity mars` (Samsung MARs/Freecess). Order of appearance is time order: dates carry no year.
 *
 * Test support only: it decides whether a device test can expect Android to re-create a killed sticky service.
 * Package records are `[FRZ] [<reason> <package> <uid>]`, `[UFZ] [<reason> <package> <uid> <freeze date>]` and
 * `[LEV] [[FRZ] <uids> [DIS] <uids>]` (only uids before `[DIS]` are frozen); the freezer's per-process
 * `[CAO pid(uid)]` lines are not package records.
 */
internal object FreecessHistory {
    private val named = Regex("""^\[\d\d/\d\d [\d:.]+\] \[(FRZ|UFZ)\] \[(\S+) (\S+) (\d+)(?: [^\]]*)?\]$""")
    private val batch = Regex("""^\[\d\d/\d\d [\d:.]+\] \[LEV\] \[\[FRZ\](.*)\]$""")

    fun lastRecord(dump: String, packageName: String, uid: Int): FreecessRecord? {
        val uidToken = uid.toString()
        var last: FreecessRecord? = null
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            val record = named.matchEntire(line)?.let { match ->
                val (kind, reason, pkg, lineUid) = match.destructured
                if (pkg == packageName && lineUid == uidToken) FreecessRecord(line, kind == "FRZ", reason) else null
            } ?: batch.matchEntire(line)?.let { match ->
                val frozenUids = match.groupValues[1].substringBefore("[DIS]").split(Regex("\\s+"))
                if (uidToken in frozenUids) FreecessRecord(line, frozen = true, reason = null) else null
            }
            if (record != null) last = record
        }
        return last
    }
}
