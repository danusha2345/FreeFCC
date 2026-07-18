package com.freefcc.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanControlTest {

    @Test
    fun jsonEscapesControlCharacters() {
        val json = LanJson.objectOf(
            "ok" to true,
            "message" to "line 1\n\"line 2\"",
            "commands" to listOf("ping", "duml_request")
        )

        assertEquals(
            "{\"ok\":true, \"message\":\"line 1\\n\\\"line 2\\\"\", \"commands\":[\"ping\", \"duml_request\"]}",
            json
        )
    }

    @Test
    fun jsonEncodesCapturedFrameMaps() {
        val json = LanJson.objectOf(
            "frames" to listOf(linkedMapOf("cmd_set" to "0x03", "seq" to 42))
        )

        assertEquals("{\"frames\":[{\"cmd_set\":\"0x03\", \"seq\":42}]}", json)
    }

    @Test
    fun rawDumlParametersAcceptHexAndKnownPorts() {
        val params = mapOf(
            "dst" to "0x03",
            "cmd_set" to "3",
            "cmd_id" to "0xE2",
            "payload" to "00:00_01 00",
            "port" to "40009"
        )

        assertEquals(3, LanCommandCodec.requiredByte(params, "dst"))
        assertEquals(0xE2, LanCommandCodec.requiredByte(params, "cmd_id"))
        assertArrayEquals(byteArrayOf(0, 0, 1, 0), LanCommandCodec.optionalHex(params))
        assertEquals(40009, LanCommandCodec.optionalPort(params))
        assertEquals(3000, LanCommandCodec.optionalCaptureDuration(emptyMap()))
        assertEquals(64, LanCommandCodec.optionalCaptureMaxFrames(emptyMap()))
        assertEquals(16_384, LanCommandCodec.optionalMaxBytes(emptyMap()))
        assertArrayEquals(
            byteArrayOf(0x55, 0xCC.toByte()),
            LanCommandCodec.requiredWireHex(mapOf("wire_hex" to "55cc"))
        )
    }

    @Test
    fun rawDumlParametersRejectUnknownPort() {
        val result = runCatching {
            LanCommandCodec.optionalPort(mapOf("port" to "22"))
        }

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }
}
