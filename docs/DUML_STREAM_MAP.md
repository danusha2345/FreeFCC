# Карта live DUML-потоков FreeFCC

Дата: 2026-07-19.

Scope: DJI RC2 `rc331` + Avata 360/O4, FreeFCC `1.5.14`, linked aircraft,
DJI Fly и indoor GPS spoof. Это evidence-карта текущей связки, а не обещание
одинакового layout на всех DJI моделях.

## Главный результат

Home Point можно определить без догадки по таймеру или числу спутников:

- transport: wrapped telemetry на `127.0.0.1:40007`;
- frame: CRC-valid unencrypted `03:44`, route `0x0e → App (0x02)`;
- Avata/O4 payload length: `102` B;
- `home_state`: LE u16 at payload offset `20`;
- до записи Home Point: `0x0046`, bit `0x01 = 0`;
- после записи Home Point: `0x0047`, bit `0x01 = 1`.

В transition capture sample `25` содержит `0x0046`, sample `26` — оба состояния
`0x0046` и `0x0047`, а все последующие `03:44` — `0x0047`. Одновременно
`03:43` показал satellites `0 → 12 → 15 → 18 → 19`, GPS used `0 → 1` и
GPS level `0 → 2 → 4 → 5`. Это прямое live-подтверждение перехода, а не только
перенос legacy layout из `dji-firmware-tools`.

## Transport topology

| Port | Что реально видно | Назначение |
|---|---|---|
| `40009` | Небольшой direct DUML broker stream к App instance `0x82`: в основном RC frames | FCC writes, request/response experiments, controller identity telemetry |
| `40007` | Широкий component→App telemetry bus; observed tooling extracts inner DUML from both direct-compatible stream and envelope `55 cc 30 75 + u32 LE length + inner DUML` | Flight/GPS/Home Point, camera, gimbal, RC, link и cellular/eSIM telemetry |

Каждый внутренний frame в статистике ниже проверен по encoded length, CRC-8 и
CRC-16. Поэтому это не false-positive scan внутри arbitrary wrapper data.

### Ограничение `40007`

Цикл без паузы, открывавший новые connections после быстрых `hardware_busy`,
физически рвал aircraft/controller link. Повторная проверка с максимум одним
завершённым запросом в секунду также рвала связь. После остановки нормальный link
восстанавливался. Continuous `40007 wire_exchange` polling запрещён.
В 37 сохранённых active capture-сеансах socket жил `0.967–2.205 s`; ни один из
32 active `03:44` не получил matching response, а все 42 найденных `03:44` были
пассивными push. Production-решение использует один read-only socket и
непрерывно читает уже публикуемую telemetry. Live A/B `1.5.23` подтвердил, что
socket без единого write сразу получает полный поток: 128 валидных кадров
примерно за секунду, включая два `03:44` с текущим `home_state=0x0047`. Любой
primer исключён.

## `40009`: узкий broker stream

Пять samples, 58 frames:

| Command | Count | Route/type | Что доказано |
|---|---:|---|---|
| `06:AE` | 42 | `0xA2 → 0x82`, `type=0x00` | Основной RC background payload; семантика неизвестна |
| `00:81` | 5 | `0x0D → 0x82`, `type=0x40` | Controller identity `rc331`; не aircraft S/N |
| `00:82` | 5 | `0x0D → 0x82`, `type=0x40` | Controller identity telemetry |
| `06:A4` | 4 | `0x06 → 0x82`, `type=0x20` | RC telemetry/status, payload `00` |
| `06:77` | 1 | `0x06 → 0x82`, `type=0x20` | RC telemetry/status, payload `00` |
| `06:72` | 1 | `0x06 → 0x82`, `type=0x80` | Настоящий response с payload `00`; вероятный успешный ACK, точный request ещё не сопоставлен |

Direct read-only `03:44` и `06:21` на `40009` matching response не дали: socket
вернул только посторонний `06:AE`. Для Home Point правильный live path оказался
passive wrapped `40007`; radio mode readback остаётся нерешённым.

## `40007`: широкая telemetry-карта

Первые пять независимых samples: 72 922 response bytes, 759 CRC-valid inner
frames, 69 command IDs. Большинство идёт от aircraft components к `App` с
`cmd_type=0x00`, то есть это unencrypted event/telemetry push, а не response на
FreeFCC request.

### Высокоценные frames

| Command | Live layout / значение | Уровень |
|---|---|---|
| `03:43` | 85 B; prefix даёт coordinates, GPS used/level, satellite count и GPS state; transition подтверждает динамику полей | OBSERVED + prefix DERIVED |
| `03:44` | 102 B; coordinates + altitude prefix, `home_state` at offset 20; переход `0x46 → 0x47` | OBSERVED, CONFIRMED transition |
| `51:04` | 33 B, route `0xEE → App`; штатная cellular/eSIM telemetry | OBSERVED, semantic per-ID UNKNOWN |
| `51:14` | 51 B, route `0xEE → App`; содержит свежий полный aircraft `1581...` identity | OBSERVED; полный S/N хранится только в ignored raw corpus |
| `00:81/82` | `rc331` controller identity | OBSERVED |
| `00:99` | Строки `cam_lens_state`, `cam_stream_status` | OBSERVED camera service telemetry |
| `02:80` | Legacy name `Camera State Info / Status Push`; current layout надо проверять по длине | OBSERVED + static name |
| `04:05` | Legacy `Gimbal Params / Push Position`; current payload требует size-specific decode | OBSERVED + static name |
| `03:D7` | Крупные flight-record/log chunks, самый частый frame | OBSERVED; semantic/layout UNKNOWN |

`51:14` особенно важен для 4G: направление противоположно FreeFCC activation
burst (`0xEE → App`, а не `App → 0xEE`) и identity приходит в штатном live
cellular потоке. Это новый источник свежей aircraft identity, но точное значение
command ID `0x14` ещё не названо по firmware evidence.

### Полная частотная картина первых пяти samples

```text
00:10 1   00:51 1   00:77 10  00:81 3   00:82 2   00:88 2
00:99 55  00:b5 1   00:b6 2   00:b7 2   00:b8 5   00:d5 1   00:f1 8
02:80 23  02:81 7   02:87 7   02:8a 7   02:dc 7   02:e8 1   02:ec 3
03:09 12  03:0f 3   03:1a 3   03:42 4   03:43 22  03:44 5
03:53 26  03:55 4   03:67 2   03:ad 2   03:ce 4   03:d7 161 03:f8 1
04:05 21  04:12 1   04:1c 4   04:66 5
06:1e 2   06:84 4   06:ae 22
08:66 4
09:08 4   09:21 2   09:43 5   09:45 2   09:75 2
0a:5a 21  0a:bb 3   0a:bc 24  0a:ea 2
0d:02 2   0f:a3 3
11:14 4   11:1c 2
18:40 5   18:50 5   19:67 2   1f:1d 11
21:06 13  21:08 6   22:21 22
23:64 2   23:65 1   23:a1 10  23:b2 113
24:71 11  51:04 11  51:14 11
```

Нельзя переносить имя по одному `cmd_id` между разными sets: например,
`23:A1` не является `03:A1`, а `22:21` не является `06:21`.

## FCC/Home Point state machine: подтверждённая часть

```text
aircraft session starts
  -> 03:44 home_state bit0 = 0
  -> satellites/GPS readiness растут
  -> regional initialization records Home Point
  -> 03:44 home_state bit0 = 1
  -> итоговый radio region уже выбран
  -> выполнить полный fcc.json один раз
  -> не повторять команды, пока persistence test не докажет новый CE-reset
```

Outdoor в CE-регионе подтвердил: FCC, отправленный до Home Point, сбрасывается в
CE в момент записи точки; текущий lightweight keepalive его не восстанавливает.
Indoor US-spoof подтвердил итоговый FCC после US Home Point. Поэтому Auto-FCC
должен применять полный профиль после `home_state bit0=1`, а не сразу при boot.
Реализация использует один долгоживущий `40007` socket только до этого события;
после обнаружения Home Point listener закрывается до FCC write.

Что ещё не доказано: настоящий radio-mode readback. Legacy name `06:21 RC Power
Mode CE/FCC Get` найден, но direct `40009` route не ответил и payload layout
неизвестен. Пока физический FCC/CE проверяется в DJI Fly.

### Дополнительный country/region readback

В современных Air 3/Air 3S PCAP и DJI Fly `libsdk_jni.so` независимо
подтверждён read-only `07:19`: empty request к destination `0x07` и `0x09`,
response `[status][ASCII country x2][00]`. В captures наблюдались
`00 54 52 00` (`TR`) и `00 42 4f 00` (`BO`), после чего DJI software отправлял
`07:18`/`07:30` country writes. Это country/area state, не прямой RF-power
readback, и на Avata 360 route ещё не проверен.

DJI Fly 1.19.4 также содержит отдельные keys `KeyIsHomeLocationSetPush`,
`KeyHomeLocationPush`, `KeyHomeLocationTypePush` и поле `is_set_homepoint`.
WA341 flight records дают порядок `GPS good first time → auto set homepoint →
offline_country_code update`. Вместе с нашим `03:44 0→1` это подтверждает, что
ожидать надо Home Point/region initialization, а не произвольный delay.

## LED state readback

Read-only hash request: `03:F8`, payload `a259ceed`. Hash model-independent:
это LE `0xedce59a2` для `g_config.misc_cfg.forearm_lamp_ctrl_0`; factory DLL
использует `03:F8` для read и `03:F9` для write.

- ранее один wrapped `40007` exchange вернул валидный payload
  `00 a259ceed ef`: success, echoed hash, value `ef` (default/on);
- в текущей session при визуально включённых LED три одиночных reads записались,
  но matching hash response не получили;
- затем LAN `LED OFF` сообщил socket-write success, но пользователь физически
  подтвердил, что LED не выключились; следующий ручной tap той же кнопки в
  FreeFCC физически выключил LED;
- два post-OFF reads, включая один при открытом FreeFCC, также не получили
  matching hash response;
- следовательно, `03:F8` route найден, но воспроизводимость readback не доказана;
  `setLed()` не должен считать `anySuccess` физической доставкой. Разница между
  LAN и последующим успешным UI tap может быть timing/foreground/proxy state,
  но конкретный фактор пока не изолирован. Нужен ACK/readback, альтернативный
  `03:E1 → 03:E2` model-index path либо точное условие, при котором `40007`
  возвращает hash response. Индекс Avata 360/O4 не найден; индексы других
  моделей переносить нельзя. Полный E1 scan запрещён до отдельного transport
  test из-за риска перегрузки link.

Текущий working tree добавляет strict wrapped readback: inner response обязан
совпасть по CRC, sequence, reverse route, `03:F8` и payload
`00 a259ceed XX`. UI хранит `ON/OFF/PARTIAL/UNKNOWN`, сбрасывает stale state при
ошибке и читает LED один раз после connection и после каждого write. Hardware
validation этой реализации ещё не выполнена.

## Corpus и privacy

Raw JSON, summaries и analyzer находятся только в ignored `.scratch/`:

- `.scratch/live/20260719-homepoint/`;
- `.scratch/live/20260719-restart-transition/`;
- `.scratch/analyze_wrapped_stream.py`.

Опорные SHA-256:

| Artifact | SHA-256 |
|---|---|
| Первый 4 500-byte `40007` sample JSON | `fb469e0290803fa19ee2fe25d42b27e7089ff26bd25cc92abdd5a8f66520e0c2` |
| Его decoded summary | `2db4b83046e30e707f9627aaaab5c4aee65da64ec472e1d909ce6135a294e500` |
| Первый `40009` sample JSON | `d05996e9d11bd202fe0d0297c1d275728928cbeae21eb1b2dad384c429814ee6` |
| Home Point before-transition sample 25 | `a105f2387ae852c62bdc030ccacd15c10a3e3bf4b7783f08d629254c8334a5dc` |
| Home Point transition sample 26 | `6954c466d6c35b4ee008026ec6e6ccd72e2479851c13f269ecf42b475e8f0fa7` |

Raw corpus содержит coordinates и полный aircraft serial, поэтому не должен
попадать в git/release artifacts. В tracked docs identity намеренно редактирован.

Статическая сверка: `o-gs/dji-firmware-tools` commit
`3b440f8b45264f48e86cdc304aa5d7470e5b7a8b`. Legacy command names применяются
как подсказки; current Avata/O4 payload считается декодированным только там, где
layout подтверждён live transition или независимым payload evidence.
