# FreeFCC

> **Disclaimer**
>
> This software is provided for educational and research purposes only. Modifying radio transmission parameters may violate laws and regulations in your country or region. In most places, increasing radio power beyond what is legally permitted for your area requires authorization from the relevant regulatory authority.
>
> You are solely responsible for ensuring that your use of this software complies with all applicable local, regional, and national laws. The authors of this project accept no liability for any damage, legal consequences, or regulatory action arising from the use of this tool.
>
> Use only if you have proper authorization to operate in FCC mode in your jurisdiction. If you are unsure whether this is legal where you live, do not use it.
>
> This project is not affiliated with, endorsed by, or sponsored by DJI. Using this tool may void your warranty and DJI Care Refresh coverage.

Free and open source FCC unlock for DJI controllers. Works on RC2, RC Pro, and RC Plus. No server, no license, no tracking. Just raw DUMPL commands sent from JSON profile files.

The app does three things:

1. **FCC unlock** - switches the radio from CE to FCC mode (higher power, more channels)
2. **4G activation** - enables 4G transmission on the aircraft
3. **Device info** - queries the controller for hardware and firmware version

Everything runs offline. The app never talks to any server. The command profiles are plain JSON files in the app's assets folder that you can open, read, and edit.

## Download

Grab the latest APK from the [releases page](https://github.com/doesthings/FreeFCC/releases) or download it directly from [freefcc.duckdns.org](https://freefcc.duckdns.org).

You'll also need the [helper apps](https://freefcc.duckdns.org/downloads/freefcc-helpers.zip) to sideload things onto the RC2.

## Install Guide

Tested on Mini 5 Pro with RC2, latest firmware. No PC needed.

The full guide with screenshots is on [freefcc.duckdns.org](https://freefcc.duckdns.org). Here's the short version:

**1. Prep the SD card**

Download the helper apps zip and the FreeFCC APK. Extract the zip, drop the APK into the extracted folder, then move the whole thing onto a microSD card. Stick the card into your RC2.

The RC2 won't install apps from internal storage, only from the SD card.

**2. Install the helper apps**

Swipe down from the top of the RC2 screen, tap the SD card notification, hit EXPLORE, and open your folder. Install these two without opening them:

- `01_PackageInstaller` - tap it, CONTINUE, INSTALL, DONE
- `02_FileManager` - same thing

**3. Restart**

Hold the power button to shut down, then power back on. This registers the package installer.

**4. Install the launcher**

Back into your folder on the SD card. Install `03_ATVLauncher` but don't open it yet.

**5. Set up Edge Gestures**

Install `04_Edge Gestures` and this time tap OPEN. Follow the prompts and grant the Accessibility service permission. Then:

- Disable the left gesture, keep only the right side
- Scroll down to "Swipe to the left", tap it
- Pick Application, then choose ATV Launcher

Now swiping right-to-left on the screen opens the launcher.

**6. Install FreeFCC**

Swipe from the right edge to open ATV Launcher. Open the Files app, find your folder, tap `FREEFCC.apk`, and install it.

## How to Use

1. Power on the drone and link it to the controller
2. Open FreeFCC and tap **Connect**
3. Tap **Enable FCC Mode** and wait for the green checkmark
4. To verify it worked: open DJI Fly, go to Settings, Transmission tab. If the 1km mark sits above the reference line, you're in FCC mode
5. For 4G: tap **Turn 4G ON** (the drone needs to be connected so the app can read its serial number)
6. To stop: tap **Stop FCC Mode** to restore CE
7. The **Info** tab lets you query the controller's hardware and firmware version

If the signal graph hasn't changed, power cycle the controller and try again. Make sure the drone is powered on and linked before enabling FCC.

## Support

FreeFCC is free and open source under AGPL-3.0. If it saved you some money or just made your day better, consider buying me a coffee:

[![Ko-fi](https://ko-fi.com/img/githubbutton_64.png)](https://ko-fi.com/freefcc)

Every contribution helps cover server costs and keeps development going. Thank you.

---

## How It Works

The app sends DUMPL commands to the controller's local TCP proxy at `127.0.0.1:40009`. DUMPL is DJI's internal command protocol, publicly documented in the [dji-firmware-tools](https://github.com/o-gs/dji-firmware-tools) project.

Each command is a small binary packet with a magic byte (`0x55`), a header with sender and receiver info, a payload, and two CRC checksums. The app builds these packets from JSON profile files and sends them over TCP, one packet per connection.

### The FCC Profile

21 frames sent in 2 rounds with 150ms between each frame. The sequence enters service mode, sets the radio region to FCC, writes channel groups and power limits, commits the change, and exits service mode. The same 21 frames work on every DJI aircraft model we tested.

### The 4G Profile

128 frames sent in a single round with 10ms between each. Each frame carries the aircraft's serial number in its payload. The serial is read from the controller at runtime by listening for telemetry on the DUMPL socket.

### Profile Format

Profiles are JSON files in `app/src/main/assets/profiles/`. Each frame looks like this:

```json
{ "s": 16, "i": 88, "d": 18, "p": "030100", "note": "Enter service mode" }
```

- `s` is the command set (16 = service mode, 6 = radio, 3 = flight controller, etc.)
- `i` is the command ID
- `d` is the destination device
- `p` is the payload as a hex string, sent as raw bytes with no transformation
- `note` is a plain English description of what the frame does

You can open these files in any text editor, read every byte that gets sent, and modify them if you want.

### How the Frames Were Obtained

The DUMPL proxy on DJI controllers listens on `127.0.0.1:40009` and accepts plain unencrypted TCP connections. Any app on the controller can observe this traffic:

```bash
tcpdump -i lo -w /sdcard/capture.pcap port 40009
```

While a licensed FCC unlock tool sends its commands, capture the loopback traffic and extract the `0x55`-prefixed frames from the pcap. The frames are plaintext on the local socket with no encryption.

The wire format, CRC algorithms, command sets, and device types are all documented in the [dji-firmware-tools](https://github.com/o-gs/dji-firmware-tools) repo (GPL-3.0). This project's `DumplBuilder` class uses the same CRC-8 (polynomial 0x8C, init 0x77) and CRC-16 (polynomial 0x1021, init 0x3692) as the reference implementation.

## Project Structure

```
app/src/main/
  assets/profiles/
    fcc.json          21 frames, FCC unlock
    ce_restore.json   1 frame, reset to factory region
    4g.json           128 frames, 4G activation
    device_info.json  1 frame, version inquiry
  java/com/freefcc/app/
    DumplTransport.kt   Frame builder + TCP socket I/O
    Profiles.kt         JSON profile loader
    FccViewModel.kt     State and logic
    MainActivity.kt     UI (Jetpack Compose)
```

## Building

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

cd C:\projects\fcc_opensource
java -classpath gradle\wrapper\gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain assembleRelease --no-daemon
```

Sign the output APK with your own keystore or the debug one.

## License

AGPL-3.0. See [LICENSE](LICENSE).

The DUMPL protocol implementation is based on the publicly documented [dji-firmware-tools](https://github.com/o-gs/dji-firmware-tools) project (GPL-3.0).