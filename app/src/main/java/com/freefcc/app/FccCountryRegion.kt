package com.freefcc.app

import java.util.Locale

internal data class FccCountryRegionResult(
    val writeCompleted: Boolean,
    val writeAckMatched: Boolean,
    val readCompleted: Boolean,
    val readAckMatched: Boolean,
    val observedCountry: String?
) {
    val verified: Boolean
        get() = observedCountry == FccCountryRegion.TARGET_COUNTRY
}

internal object FccCountryRegion {
    const val TARGET_COUNTRY = "AU"

    private const val SENDER = 0x2A
    private const val DESTINATION = 0x09
    private const val COMMAND_TYPE = 0x40
    private const val COMMAND_SET = 0x07
    private const val WRITE_COMMAND_ID = 0x30
    private const val READ_COMMAND_ID = 0x19
    private const val READ_WINDOW_MS = 500

    private val writePayload = byteArrayOf(
        0x41, 0x55, 0x00, 0x00, 0x41, 0x55, 0x00, 0x00, 0x01, 0x00
    )

    fun apply(transport: DumlTransport, port: Int): FccCountryRegionResult =
        execute { frame, readWindowMs ->
            transport.sendAndReceiveRaw(
                frame = frame,
                readWindowMs = readWindowMs,
                port = port,
                autoDetectPort = port == DumlTransport.PORT
            )
        }

    internal fun execute(
        exchange: (frame: ByteArray, readWindowMs: Int) -> DumlRawExchange
    ): FccCountryRegionResult {
        val builder = DumlBuilder()
        val writeExchange = exchange(
            builder.buildFrame(
                DumlFrame(
                    sender = SENDER,
                    cmdType = COMMAND_TYPE,
                    cmdSet = COMMAND_SET,
                    cmdId = WRITE_COMMAND_ID,
                    dst = DESTINATION,
                    payload = writePayload
                )
            ),
            READ_WINDOW_MS
        )
        val readExchange = exchange(
            builder.buildFrame(
                DumlFrame(
                    sender = SENDER,
                    cmdType = COMMAND_TYPE,
                    cmdSet = COMMAND_SET,
                    cmdId = READ_COMMAND_ID,
                    dst = DESTINATION,
                    payload = byteArrayOf()
                )
            ),
            READ_WINDOW_MS
        )
        return FccCountryRegionResult(
            writeCompleted = writeExchange.writeCompleted,
            writeAckMatched = writeExchange.matchedFrame != null,
            readCompleted = readExchange.writeCompleted,
            readAckMatched = readExchange.matchedFrame != null,
            observedCountry = parseReadback(readExchange.validatedPayload)
        )
    }

    internal fun parseReadback(payload: ByteArray?): String? {
        if (payload == null) return null
        val offset = when {
            payload.size >= 3 && payload[0] == 0.toByte() -> 1
            payload.size >= 2 -> 0
            else -> return null
        }
        val first = payload[offset].toInt() and 0xFF
        val second = payload[offset + 1].toInt() and 0xFF
        if (first !in 'A'.code..'Z'.code || second !in 'A'.code..'Z'.code) return null
        return byteArrayOf(first.toByte(), second.toByte())
            .toString(Charsets.US_ASCII)
            .uppercase(Locale.US)
    }
}
