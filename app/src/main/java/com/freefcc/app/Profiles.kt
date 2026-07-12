package com.freefcc.app

import android.content.Context
import org.json.JSONObject

/**
 * Loads DUMPL command profiles from JSON files in app/src/main/assets/profiles/.
 *
 * Profiles are plain JSON — no encryption, no obfuscation. Each file defines
 * a sequence of DUMPL frames with timing parameters. The app reads them at
 * runtime, builds wire-format frames via [DumplBuilder], and sends the raw
 * bytes to the controller.
 */
object Profiles {

    /**
     * A command profile loaded from a JSON file.
     *
     * @property sender       Sender byte (type + index)
     * @property cmdType      Command type byte (packet_type + ack_type + encrypt_type)
     * @property rounds       Number of times to repeat the full frame sequence
     * @property interFrameDelay  Delay between frames within a round (ms)
     * @property interRoundDelay  Delay between rounds (ms)
     * @property readWindowMs     How long to wait for an ACK after each write
     * @property needsResponse    True if this command expects a response payload
     * @property frames       List of wire-ready frames (built from the JSON definition)
     */
    data class Profile(
        val sender: Int,
        val cmdType: Int,
        val rounds: Int,
        val interFrameDelay: Long,
        val interRoundDelay: Long,
        val readWindowMs: Int,
        val needsResponse: Boolean,
        val port: Int,
        val frames: List<ByteArray>
    )

    /** Loads a static profile (FCC, CE restore, device info) from a JSON asset. */
    fun load(context: Context, fileName: String): Profile {
        val json = readAsset(context, "profiles/$fileName")
        val obj = JSONObject(json)

        val sender = obj.getInt("sender")
        val cmdType = obj.getInt("cmd_type")
        val rounds = obj.getInt("rounds")
        val interFrame = obj.optLong("inter_frame_delay_ms", 0)
        val interRound = obj.optLong("inter_round_delay_ms", 0)
        val readWindow = obj.optInt("read_window_ms", 80)
        val needsResponse = obj.optBoolean("needs_response", false)
        val port = obj.optInt("port", 40009)

        val framesArray = obj.getJSONArray("frames")
        val builder = DumplBuilder()
        val frames = (0 until framesArray.length()).map { i ->
            val f = framesArray.getJSONObject(i)
            builder.buildFrame(DumplFrame(
                sender = sender,
                cmdType = cmdType,
                cmdSet = f.getInt("s"),
                cmdId = f.getInt("i"),
                dst = f.getInt("d"),
                payload = hexToBytes(f.optString("p", ""))
            ))
        }

        return Profile(sender, cmdType, rounds, interFrame, interRound, readWindow, needsResponse, port, frames)
    }

    /**
     * Builds the 4G activation profile at runtime. Unlike static profiles,
     * 4G frames include the aircraft serial in each payload.
     */
    fun load4g(context: Context, aircraftSerial: String): Profile {
        val json = readAsset(context, "profiles/4g.json")
        val obj = JSONObject(json)

        val sender = obj.getInt("sender")
        val cmdType = obj.getInt("cmd_type")
        val rounds = obj.getInt("rounds")
        val interFrame = obj.optLong("inter_frame_delay_ms", 10)
        val interRound = obj.optLong("inter_round_delay_ms", 0)
        val readWindow = obj.optInt("read_window_ms", 80)

        val frameCount = obj.getInt("frame_count")
        val cmdSet = obj.getInt("cmd_set")
        val cmdIdStart = obj.getInt("cmd_id_start")
        val dst = obj.getInt("dst")
        val prefix = hexToBytes(obj.getString("payload_prefix_hex"))

        // Build the payload: prefix + ASCII serial
        val serialBytes = aircraftSerial.toByteArray(Charsets.US_ASCII)
        val payload = ByteArray(prefix.size + serialBytes.size)
        System.arraycopy(prefix, 0, payload, 0, prefix.size)
        System.arraycopy(serialBytes, 0, payload, prefix.size, serialBytes.size)

        // Generate all 128 frames
        val builder = DumplBuilder()
        val frames = (0 until frameCount).map { i ->
            builder.buildFrame(DumplFrame(
                sender = sender,
                cmdType = cmdType,
                cmdSet = cmdSet,
                cmdId = cmdIdStart + i,
                dst = dst,
                payload = payload
            ))
        }

        return Profile(sender, cmdType, rounds, interFrame, interRound, readWindow, false, 40009, frames)
    }

    // --- Helpers ---

    private fun readAsset(context: Context, path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "").replace("\n", "")
        if (clean.isEmpty()) return ByteArray(0)
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}