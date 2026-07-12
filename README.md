# FreeFCC — Open-Source FCC Unlock for DJI Controllers

An open-source Android app that unlocks FCC mode (higher radio power + more
channels), enables 4G transmission, and queries device info on DJI
controllers (RC2, RC Pro, RC Plus). No server, no license, no encryption, no
tracking — just raw DUMPL commands from JSON profile files.

## How the Command Frames Were Obtained

The DUMPL command sequences in this project were obtained through **network
observation of unencrypted loopback traffic** on the controller itself, and by
cross-referencing the **publicly documented DJI DUMPL protocol**.

### Method 1: Loopback Packet Capture

The DUMPL proxy on DJI controllers listens on `127.0.0.1:40009` and accepts
plain, unencrypted TCP connections. Any app running on the controller can
observe the traffic flowing through this local port:

```bash
# On the controller (via adb shell or Termux):
tcpdump -i lo -w /sdcard/duml_capture.pcap port 40009
```

While a licensed FCC unlock tool sends its commands, capture the loopback
traffic. Each DUMPL frame starts with the magic byte `0x55` and can be
extracted from the pcap with Wireshark or a simple parser:

```python
# Parse captured pcap for 0x55-prefixed DUMPL frames
import struct
with open("duml_capture.pcap", "rb") as f:
    data = f.read()
# Walk through pcap records, find TCP payloads starting with 0x55
# Each frame: [0x55][len_lo][len_hi|ver][crc8][sender][dst][seq_lo][seq_hi][cmd_type][cmd_set][cmd_id][payload...][crc16_lo][crc16_hi]
```

The frames are sent in **plaintext** over the loopback interface — there is no
encryption on the local socket. The TCP payload contains the raw DUMPL bytes.

### Method 2: Public Protocol Documentation

The DUMPL wire format, CRC algorithms, command sets, and device types are
publicly documented in the [dji-firmware-tools](https://github.com/o-gs/dji-firmware-tools)
project (GPL-3.0 licensed):

- [`comm_dat2pcap.py`](https://github.com/o-gs/dji-firmware-tools/blob/master/comm_dat2pcap.py) — DUML packet parser with CRC-8 and CRC-16 implementations
- [`comm_mkdupc.py`](https://github.com/o-gs/dji-firmware-tools/blob/master/comm_mkdupc.py) — DUML packet builder with all command set and device type enums
- [`comm_og_service_tool.py`](https://github.com/o-gs/dji-firmware-tools/blob/master/comm_og_service_tool.py) — Service tool demonstrating parameter read/write, gimbal calibration
- [`comm_dissector/`](https://github.com/o-gs/dji-firmware-tools/tree/master/comm_dissector) — Wireshark Lua dissectors for the full protocol

This project's `DumplBuilder` class implements the same CRC-8 (polynomial
0x8C, init 0x77) and CRC-16 (polynomial 0x1021, init 0x3692) algorithms
documented in the reference implementation above.

### Verification

The captured frames were verified by:
1. Recomputing the CRC-8 and CRC-16 checksums using the dji-firmware-tools
   reference implementation — all checksums match
2. Testing on real hardware (RC2 + Mini 5 Pro) — the frames successfully
   switch the radio from CE to FCC mode

The FCC profile is **universal** — the same 21-frame sequence works on all
tested DJI aircraft models (Mini 5 Pro, Mavic 4 Pro, Inspire 3, etc.).

## The DUMPL Protocol

DUMPL (DJI Unified Packet Layer) is DJI's internal command protocol, publicly
documented in [dji-firmware-tools](https://github.com/o-gs/dji-firmware-tools).
It runs over a local TCP proxy (`127.0.0.1:40009`) on DJI controllers.

### Wire Format

```
Byte  0       Magic (0x55)
Byte  1-2     Length (bits 0-9, LE) + Version (bits 10-15, always 1)
Byte  3       CRC-8 (poly 0x8C reflected, init 0x77) of bytes 0-2
Byte  4       Sender: type (bits 0-4) + index (bits 5-7)
Byte  5       Receiver: type (bits 0-4) + index (bits 5-7)
Byte  6-7     Sequence number (LE)
Byte  8       Command type: packet_type (bit 7) + ack_type (bits 5-6) + encrypt (bits 0-2)
Byte  9       Command set
Byte  10      Command ID
Byte  11..N   Payload
Byte  N+1..N+2  CRC-16 (poly 0x1021 reflected, init 0x3692) of all preceding bytes
```

### Command Sets

| Set | Name | Controls |
|-----|------|----------|
| 0 | GENERAL | Version inquiry, region codes, encryption pairing |
| 3 | FLYCONTROLLER | Flight parameters (altitude, distance, NFZ) |
| 4 | ZENMUSE | Gimbal calibration |
| 6 | RADIO | Radio region (FCC/CE), power parameters |
| 7 | WIFI | Channel maps, frequency groups |
| 9 | OFDM | Power limits, hardware registers |
| 16 | AUTOTEST | Service mode enter/exit |
| 81 | 4G | 4G transmission activation |

### Device Types

| Value | Name | Notes |
|-------|------|-------|
| 0 | ANY | Broadcast |
| 2 | MOBILE_APP | The app (sender=130 = type 2, index 4) |
| 3 | FLYCONTROLLER | Flight controller |
| 4 | GIMBAL | Gimbal |
| 6 | REMOTE_RADIO | Radio module |
| 7 | WIFI | WiFi module |
| 9 | LB_MCU_SKY | Lightbridge MCU |
| 14 | OFDM_GROUND | OFDM ground unit |
| 18 | SVO | Service Video Output |

## Profile Files

Profiles are plain JSON in `app/src/main/assets/profiles/`:

| File | Frames | Purpose |
|------|--------|---------|
| `fcc.json` | 21 | Switch radio from CE to FCC (2 rounds, 150ms) |
| `ce_restore.json` | 1 | Reset radio to factory default region |
| `4g.json` | 128 | Enable 4G (serial embedded at runtime) |
| `device_info.json` | 1 | Query controller version info |

Each frame is defined as:
```json
{ "s": 16, "i": 88, "d": 18, "p": "030100", "note": "Enter service mode" }
```
- `s` = command set
- `i` = command ID
- `d` = destination (device type + index)
- `p` = payload hex string (sent as raw bytes — no encryption, no transformation)
- `note` = human-readable description

## Project Structure

```
fcc_opensource/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/profiles/
│       │   ├── fcc.json           # FCC unlock (21 frames)
│       │   ├── ce_restore.json    # CE restore (1 frame)
│       │   ├── 4g.json            # 4G activation template
│       │   └── device_info.json   # Version inquiry
│       ├── res/
│       │   ├── drawable/          # Launcher icon
│       │   ├── mipmap-anydpi-v26/ # Adaptive icon wrapper
│       │   └── values/            # Theme
│       └── java/com/freefcc/app/
│           ├── DumplTransport.kt  # Frame builder (CRC-8/16) + TCP socket I/O
│           ├── Profiles.kt        # JSON profile loader
│           ├── FccViewModel.kt    # State management + business logic
│           └── MainActivity.kt    # Compose UI
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## Building the APK

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

cd C:\projects\fcc_opensource
java -classpath gradle\wrapper\gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain assembleRelease --no-daemon

# Sign
$zipalign = "C:\Android\Sdk\build-tools\35.0.1\zipalign.exe"
$apksigner = "C:\Android\Sdk\build-tools\35.0.1\apksigner.bat"
& $zipalign -f -p 4 app\build\outputs\apk\release\app-release.apk app\build\outputs\apk\release\app-release-aligned.apk
& $apksigner sign --ks <keystore> --ks-pass pass:<password> --out FreeFCC.apk app\build\outputs\apk\release\app-release-aligned.apk
```

## How to Use

1. Install the APK on your DJI controller (via microSD card + helper apps)
2. Power on the drone and link it to the controller
3. Open FreeFCC — tap **Connect**
4. Tap **Enable FCC Mode** — watch the progress bar
5. Verify: DJI Fly → Settings → Transmission → the 1km mark should be above
   the reference line
6. For 4G: tap **Turn 4G ON** (requires aircraft connected for serial)
7. To stop: tap **Stop FCC Mode** to restore CE
8. **Info tab**: tap refresh to query the controller's hardware/firmware version
9. **Support tab**: if the app helped you, consider supporting development

## Support This Project

FreeFCC is free and open source. If it helped you, consider supporting
development:

**[Ko-fi →](https://ko-fi.com/freefcc)**

## No Internet, No Server, No Tracking

Everything is self-contained. The app never contacts any server. The profiles
are plain JSON files you can inspect, modify, or replace. The DUMPL protocol
implementation is based on the open-source
[dji-firmware-tools](https://github.com/o-gs/dji-firmware-tools) project.

## License

This project uses the DUMPL protocol as publicly documented by the
[dji-firmware-tools](https://github.com/o-gs/dji-firmware-tools) project
(GPL-3.0). The command frames were obtained through network observation of
unencrypted loopback traffic on the controller.

## Not Affiliated with DJI

Use at your own risk. Modifying radio parameters may violate regulations in
your country.