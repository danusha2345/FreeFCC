# LAN Control API

FreeFCC exposes a small diagnostic HTTP API on the controller's private Wi-Fi
address. It is intended for bench testing: live logs, state inspection,
allowlisted application actions, and raw DUML request/response without rebuilding
the APK for every frame.

The server binds only to the controller's private `wlan*`/`wifi*` IPv4 address.
It does not listen on cellular or wildcard interfaces. Every endpoint requires
the fixed project password. Control endpoints accept it only through the
`X-FreeFCC-Password` header. Disable **LAN Control Bridge** in the Log tab when
using an untrusted Wi-Fi network: the password is shared and published, and HTTP
does not encrypt traffic.

## Connection

Default HTTP port: `8787`. UDP discovery beacons are sent to port `8788` and
contain only the magic value, controller IP, and HTTP port.

```bash
export FREEFCC_URL=http://192.168.1.139:8787
export FREEFCC_PASSWORD=c0dec0de8787fcc0
```

The IP can change when the controller reconnects to Wi-Fi.
Reopening FreeFCC revalidates the bound address and restarts the server if the
Wi-Fi IP changed. The bridge lives in the FreeFCC process; Android may stop it
after the process is killed, so reopen the app before a bench session.

## Read endpoints

```bash
curl -sS -H "X-FreeFCC-Password: $FREEFCC_PASSWORD" \
  "$FREEFCC_URL/api/status"

curl -sS -H "X-FreeFCC-Password: $FREEFCC_PASSWORD" \
  "$FREEFCC_URL/api/commands"

curl -sS -H "X-FreeFCC-Password: $FREEFCC_PASSWORD" \
  "$FREEFCC_URL/logs"
```

For backward compatibility, `/logs?password=...` is also accepted. Query-string
authentication is not accepted by `/api/*`. Prefer the header even for logs so
the password does not appear in the URL or ordinary access logs.

## Application commands

Commands use `POST application/x-www-form-urlencoded`:

```bash
curl -sS -X POST \
  -H "X-FreeFCC-Password: $FREEFCC_PASSWORD" \
  --data 'command=connect' \
  "$FREEFCC_URL/api/command"
```

Available actions are returned by `/api/commands`. Long-running application
actions return HTTP `202 Accepted`; poll `/api/status` and `/logs` for their
result. A successful LAN `connect` starts the default DJI Fly Home Point text
wait; the Accessibility service must already be enabled. Explicit wait
start/stop actions and the legacy `keepalive_start/stop` aliases select the same
default text mode. The five-second legacy mode is currently a local UI action.
The list also includes FCC/CE, LED,
device info, serial and 4G probes, updater actions, and flight-app launch. Busy
hardware returns `409`; commands that require a prior controller connection or
update check return `412`.

`fcc_enabled` in `/api/status` is always `null`: the localhost proxy does not
provide a physical RF-region readback. `fcc_sequence_written` and
`fcc_last_write_at_ms` describe only a successful profile write in the current
app process. The `fcc_last_attempt_*` fields report origin, exact port,
timestamps, expected/flushed writes and matching ACK count; the
`fcc_auto_*` fields retain the last automatic attempt even after a manual retry.
The historical `home_point_monitor_*` and `keepalive_*` fields are compatibility
aliases for the active foreground Auto FCC lifecycle; they no longer imply a
DUML Home Point listener.

## Raw DUML

`duml_request` builds one DUML request, sends it to the exact selected localhost
proxy port, waits for a response on that same socket, validates
CRC/routing/sequence/cmd fields, and returns the evidence separately as a
matching frame, the last complete unmatched frame, and a bounded partial tail:

```bash
curl -sS -X POST \
  -H "X-FreeFCC-Password: $FREEFCC_PASSWORD" \
  --data 'command=duml_request' \
  --data 'sender=0x2a' \
  --data 'dst=0x03' \
  --data 'cmd_type=0x40' \
  --data 'cmd_set=0x03' \
  --data 'cmd_id=0xe2' \
  --data 'payload=000001009205' \
  --data 'port=40009' \
  --data 'timeout_ms=3000' \
  "$FREEFCC_URL/api/command"
```

The example is an index-based `03:E2` parameter read payload
`[table=0][type=1][index=0x0592]`; the index is model-specific and must not be
assumed valid for another aircraft.

### Read the current lamp parameter by hash

The first hardware probe should be the read-only `03:F8` form. It uses the same
parameter hash as the existing `03:F9` LED write profiles and does not require a
model-specific E1/E2 index. The application-level command performs the required
port-`40007` wrapping and strict inner DUML response validation:

```bash
curl -sS -X POST \
  -H "X-FreeFCC-Password: $FREEFCC_PASSWORD" \
  --data 'command=led_read' \
  "$FREEFCC_URL/api/command"
```

The command is asynchronous (`202 accepted`). Read `/api/status` after it
finishes; `led_state` is `on`, `off`, `partial`, or `unknown`, and `led_value`
contains the current u8 mask when verified. A validated factory-style response is
`payload_hex=00a259ceedXX`: status `00`, echoed hash, then the current u8 value.
Common captured values are `00` (off), `ef` (default/on), or a partial bitmask
such as `04`/`05`. Treat `504` only as “no response on this transport path,” not
as an LED state. The app performs one read on demand or after a write; it does
not poll `40007`, because repeated proxy connections disrupted the aircraft link
in live testing.

`duml_send` uses the same fields and also accepts `wrapper=true` for the outer
port-`40007` envelope. It reports only socket write completion. Generic wrapped
response parsing remains intentionally unavailable through `duml_request`; use
`led_read` for the supported lamp readback or `wire_exchange` when the raw outer
reply must be retained without interpretation.

Allowed ports are `40007`, `40009`, and `8901..8904`. Payload length is limited
to the DUML frame maximum. The API never exposes shell commands, filesystem
access, ADB, or an arbitrary network destination.

## Passive DUML capture

`duml_capture` listens on a fresh connection to one allowlisted localhost proxy
port and returns the bounded set of structurally valid frames observed before
EOF, the global deadline, or `max_frames`, as raw hex plus decoded
routing/command fields:

```bash
curl -sS -X POST \
  -H "X-FreeFCC-Password: $FREEFCC_PASSWORD" \
  --data 'command=duml_capture' \
  --data 'port=40009' \
  --data 'duration_ms=5000' \
  --data 'max_frames=64' \
  "$FREEFCC_URL/api/command"
```

`duration_ms` is limited to `100..10000`, `max_frames` to `1..128`. This is a
rootless broker capture: it sees unsolicited/forwarded frames published to this
socket. It does not claim to packet-sniff private loopback TCP streams belonging
to DJI Fly or other processes; a complete `tcpdump -i lo` capture still requires
privileged access.

Only one LAN diagnostic operation (`duml_send`, `duml_request`, `duml_capture`,
or `wire_exchange`) may run at a time; another returns `409 diagnostic_busy`.
For request/response operations the hardware write gate is released immediately
after `flush()`, while the response continues on the original socket. The Home
Serial/identity probes and supported LED actions additionally share a
process-wide port-`40007` lease. Every long-lived localhost operation also owns
a lease for its exact DUML port, so diagnostics never overlap another serial,
LED, or FCC/CE operation on the same proxy. A conflicting request returns
`409 duml_port_busy`.

## Exact raw wire exchange

`wire_exchange` writes exact bytes to one allowlisted localhost port and returns
a bounded prefix of the raw bytes received on that same socket. It performs no
DUML, CRC, wrapper, encryption, routing, or response assumptions, so an unknown
transport can be iterated over LAN without rebuilding the APK:

```bash
curl -sS -X POST \
  -H "X-FreeFCC-Password: $FREEFCC_PASSWORD" \
  --data 'command=wire_exchange' \
  --data 'port=40007' \
  --data 'wire_hex=55cc307511000000551104920203ad104003f8a259ceed3a72' \
  --data 'duration_ms=3000' \
  --data 'max_bytes=16384' \
  "$FREEFCC_URL/api/command"
```

`wire_hex` is required and limited to 4096 bytes; `max_bytes` is limited to
65536. Every result includes `termination`: `eof`, `deadline`, `max_bytes`, or
`io_error`. HTTP `502 send_failed` distinguishes a connect/write failure from a
completed write with an empty response. If the peer closes the stream with an
I/O error after returning some bytes, the API returns HTTP `502 read_failed`,
keeps the bounded partial prefix in `response_hex`, and marks it as truncated.
An empty HTTP `200` response may mean clean EOF or no bytes before the deadline;
raw mode intentionally does not infer a protocol-level result.

The same fixed-password/trusted-Wi-Fi warning applies. Ports and byte limits
remain allowlisted, but these raw endpoints can still carry state-changing DUML
commands. They expose no shell, filesystem, ADB, arbitrary network destination,
or privileged packet capture; use them only on an isolated, trusted bench LAN.
