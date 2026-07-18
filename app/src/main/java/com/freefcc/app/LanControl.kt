package com.freefcc.app

import java.util.Locale

internal object LanJson {
    fun objectOf(vararg values: Pair<String, Any?>): String =
        values.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "${quote(key)}:${encode(value)}"
        }

    private fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean, is Number -> value.toString()
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (key, item) ->
            "${quote(key.toString())}:${encode(item)}"
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { encode(it) }
        else -> quote(value.toString())
    }

    internal fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
        append('"')
    }
}

internal object LanCommandCodec {
    val allowedDumlPorts = setOf(
        DumlTransport.PORT,
        DumlTransport.PORT_LED,
        DumlTransport.PORT_ALT_1,
        DumlTransport.PORT_ALT_2,
        DumlTransport.PORT_ALT_3,
        DumlTransport.PORT_ALT_4
    )

    fun requiredByte(params: Map<String, String>, name: String): Int =
        parseInt(params[name] ?: throw IllegalArgumentException("missing_$name"), name, 0, 0xFF)

    fun optionalByte(params: Map<String, String>, name: String, default: Int): Int =
        params[name]?.let { parseInt(it, name, 0, 0xFF) } ?: default

    fun optionalPort(params: Map<String, String>): Int {
        val port = params["port"]?.let { parseInt(it, "port", 1, 65_535) }
            ?: DumlTransport.PORT
        require(port in allowedDumlPorts) { "unsupported_port" }
        return port
    }

    fun optionalTimeout(params: Map<String, String>): Int =
        params["timeout_ms"]?.let { parseInt(it, "timeout_ms", 100, 10_000) } ?: 3_000

    fun optionalCaptureDuration(params: Map<String, String>): Int =
        params["duration_ms"]?.let { parseInt(it, "duration_ms", 100, 10_000) } ?: 3_000

    fun optionalCaptureMaxFrames(params: Map<String, String>): Int =
        params["max_frames"]?.let { parseInt(it, "max_frames", 1, 128) } ?: 64

    fun optionalMaxBytes(params: Map<String, String>): Int =
        params["max_bytes"]?.let { parseInt(it, "max_bytes", 1, 65_536) } ?: 16_384

    fun optionalBoolean(params: Map<String, String>, name: String, default: Boolean = false): Boolean {
        return when (val value = params[name]?.trim()?.lowercase(Locale.US)) {
            null -> default
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> throw IllegalArgumentException("invalid_$name")
        }
    }

    fun optionalHex(params: Map<String, String>, name: String = "payload"): ByteArray {
        return decodeHex(params[name].orEmpty(), name, MAX_PAYLOAD_BYTES)
    }

    fun requiredWireHex(params: Map<String, String>): ByteArray {
        val raw = params["wire_hex"] ?: throw IllegalArgumentException("missing_wire_hex")
        val decoded = decodeHex(raw, "wire", MAX_WIRE_BYTES)
        require(decoded.isNotEmpty()) { "empty_wire_hex" }
        return decoded
    }

    private fun decodeHex(raw: String, name: String, maxBytes: Int): ByteArray {
        val clean = (if (raw.startsWith("0x", ignoreCase = true)) raw.substring(2) else raw)
            .replace(" ", "")
            .replace(":", "")
            .replace("_", "")
        require(clean.length % 2 == 0) { "odd_${name}_hex" }
        require(clean.length <= maxBytes * 2) { "${name}_too_long" }
        require(clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "invalid_${name}_hex" }
        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString(separator = "") { "%02x".format(Locale.US, it.toInt() and 0xFF) }

    private fun parseInt(raw: String, name: String, min: Int, max: Int): Int {
        val value = raw.trim()
        val parsed = if (value.startsWith("0x", ignoreCase = true)) {
            value.substring(2).toIntOrNull(16)
        } else {
            value.toIntOrNull()
        } ?: throw IllegalArgumentException("invalid_$name")
        require(parsed in min..max) { "invalid_$name" }
        return parsed
    }

    private const val MAX_PAYLOAD_BYTES = 1_010
    private const val MAX_WIRE_BYTES = 4_096
}
