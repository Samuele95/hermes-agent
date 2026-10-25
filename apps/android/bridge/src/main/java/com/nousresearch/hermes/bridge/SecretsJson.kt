package com.nousresearch.hermes.bridge

/**
 * The keystore's secrets as the UTF-8 JSON object the core's `android_keystore` secret source reads, written
 * straight into one byte array of the exact size: no value ever becomes a String (immutable, so unwipeable), and
 * no growing buffer leaves copies behind. Each value is wiped once written; the caller wipes the result.
 * Values are UTF-8 bytes and pass through as such; quotes, backslashes and control characters are escaped.
 */
internal object SecretsJson {
    private val HEX = "0123456789abcdef".toByteArray()

    fun encode(held: Map<String, ByteArray>): ByteArray {
        val names = held.keys.map { it.toByteArray(Charsets.UTF_8) }
        var size = 2 + maxOf(0, held.size - 1)  // braces and commas
        names.zip(held.values).forEach { (name, value) -> size += quoted(name) + 1 + quoted(value) }
        val out = ByteArray(size)
        var at = 0
        out[at++] = '{'.code.toByte()
        names.zip(held.values).forEachIndexed { i, (name, value) ->
            if (i > 0) out[at++] = ','.code.toByte()
            at = write(name, out, at)
            out[at++] = ':'.code.toByte()
            at = write(value, out, at)
            value.fill(0)
        }
        out[at] = '}'.code.toByte()
        return out
    }

    /** The length of [bytes] as a JSON string, quotes included. */
    private fun quoted(bytes: ByteArray): Int = 2 + bytes.sumOf { b -> escape(b).let { if (it == null) 1 else it.size } }

    private fun write(bytes: ByteArray, out: ByteArray, from: Int): Int {
        var at = from
        out[at++] = '"'.code.toByte()
        for (b in bytes) {
            val escaped = escape(b)
            if (escaped == null) out[at++] = b else escaped.forEach { out[at++] = it }
        }
        out[at++] = '"'.code.toByte()
        return at
    }

    /** The escape for one byte, or null when it stands as is (every byte of a multi-byte UTF-8 sequence does). */
    private fun escape(b: Byte): ByteArray? {
        val c = b.toInt() and 0xff
        return when {
            c == '"'.code -> byteArrayOf('\\'.code.toByte(), '"'.code.toByte())
            c == '\\'.code -> byteArrayOf('\\'.code.toByte(), '\\'.code.toByte())
            c < 0x20 -> byteArrayOf('\\'.code.toByte(), 'u'.code.toByte(), '0'.code.toByte(), '0'.code.toByte(),
                                    HEX[c shr 4], HEX[c and 0xf])
            else -> null
        }
    }
}
