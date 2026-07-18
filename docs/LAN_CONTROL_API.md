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
result. The list includes connection, FCC/CE, keepalive, Auto-FCC, LED, device
info, serial and 4G probes, updater actions, and flight-app launch. Busy hardware
returns `409`; commands that require a prior controller connection or update
check return `412`.

## Raw DUML

`duml_request` builds one DUML request, sends it to the exact selected localhost
proxy port, waits for a response, validates CRC/routing/sequence/cmd fields, and
returns all available evidence as hex:

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
model-specific E1/E2 index:

```bash
curl -sS -X POST \
  -H "X-FreeFCC-Password: $FREEFCC_PASSWORD" \
  --data 'command=duml_request' \
  --data 'sender=0x2a' \
  --data 'dst=0x03' \
  --data 'cmd_type=0x40' \
  --data 'cmd_set=0x03' \
  --data 'cmd_id=0xf8' \
  --data 'payload=a259ceed' \
  --data 'port=40009' \
  --data 'timeout_ms=3000' \
  "$FREEFCC_URL/api/command"
```

A validated factory-style response is expected as
`payload_hex=00a259ceedXX`: status `00`, echoed hash, then the current u8 value.
Common captured values are `00` (off), `ef` (default/on), or a partial bitmask
such as `04`/`05`. Treat `504` only as “no response on this transport path,” not
as an LED state. This probe must use direct port `40009`; wrapped response
parsing on `40007` is not implemented.

`duml_send` uses the same fields and also accepts `wrapper=true` for the outer
port-`40007` envelope. It reports only socket write completion. Wrapped response
parsing is intentionally not supported yet.

Allowed ports are `40007`, `40009`, and `8901..8904`. Payload length is limited
to the DUML frame maximum. The API never exposes shell commands, filesystem
access, ADB, or an arbitrary network destination.
