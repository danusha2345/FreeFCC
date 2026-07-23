# Карта live DUML-потоков FreeFCC

Дата live capture: 2026-07-20. Статический cross-check RM510: 2026-07-23.

Scope: DJI RC2 `rc331` + Avata 360/O4, FreeFCC `1.5.14`, linked aircraft,
DJI Fly и indoor GPS spoof. Это evidence-карта текущей связки, а не обещание
одинакового layout на всех DJI моделях.

Отдельный размеченный corpus DJI Air 3S `WA234` + RC Pro 2 `rc520` находится в
[`AIR3S_RC_PRO2_LIVE_MAP.md`](AIR3S_RC_PRO2_LIVE_MAP.md). Он доказал, что
transport topology и radio-state candidates различаются между моделями.

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
passive wrapped `40007`; для radio mode найден коррелирующий push-кандидат
`09:43`, но окончательная семантика его bit 1 ещё требует обратимого повтора.

## Маркированный CE → FCC A/B, 2026-07-20

В одной aircraft session сначала был снят пользовательски маркированный CE,
затем FreeFCC отправил полный профиль на pinned `40009`: `42/42` writes,
`ALL_WRITES_FLUSHED`. Следующее одиночное окно `40007` дало изменение одного
radio-side push:

| Frame / поле | CE | После полного FCC apply | Уровень вывода |
|---|---:|---:|---|
| `09:43`, route `0x0e → App`, LE u16 at offset 0 | `0x0000` | `0x0002` | OBSERVED; bit 1 — сильный FCC-state кандидат |
| `19:67`, payload byte offset 2 | `0x01` в доступных paired samples | `0x00` | OBSERVED; secondary cross-check, семантика UNKNOWN |
| `18:40` | `0100` | `0100` | NEGATIVE: не mode bit |
| фон `40009` (`06:AE`, `06:A4`, `06:8E`, `00:81/82`) | byte-identical | byte-identical | NEGATIVE: пассивного FCC-бита не видно |

`09:43` — CRC-valid unencrypted push command set `09` (`HD Link`) от
ground-side HD transmission MCU. В закреплённом `dji-firmware-tools` для
современного `cmdId=0x43` имени и layout нет. Поэтому точная корреляция
`0000 → 0200` является OBSERVED, а название bit 1 как FCC readback пока
HYPOTHESIS: write completion не доказывает фактическую RF power, и текущий
post-apply A/B не был независимо проверен графиком Transmission.

Позднейший Air 3S/RC Pro 2 A/B опроверг универсальность этой гипотезы:
`09:43=0000` получен в CE и в двух физически подтверждённых FCC sessions.
Следовательно, `09:43 bit1` нельзя использовать как общий DJI FCC readback;
Avata-корреляция остаётся model/session-specific наблюдением.

Corpus-wide audit усиливает, но не завершает вывод:

- `09:43=0000`: 54 CRC-valid frames в 21 файле;
- `09:43=0200`: 3 frames — два в старом restart-transition corpus и один сразу
  после нового полного apply;
- старый последовательный поток менялся `0200 → 0000` без reset sequence;
- все 17 доступных `19:67` следовали сразу после `09:43`: для двух paired
  `0200` byte offset 2 был `00`, для 15 paired `0000` — `01`;
- Home Point не объясняет этот бит: `09:43=0000` наблюдался до и после
  `03:44 home_state 0→1`, а новый `0200` пришёл при уже записанном Home Point.

После следующего запуска в физическом CE были выполнены ровно два одиночных
read-only capture. Брокер сам закрыл каждый socket примерно через 2 s; окна
содержали только `18:40=0100` и `06:AE`, без `09:43`. Это NEGATIVE только для
двух коротких окон, не доказательство значения режима. Новые reconnect делать
не стали, потому что их серия ранее физически нарушала aircraft link.

Для перевода кандидата в практический readback нужен ещё один маркированный
обратимый цикл `CE=0000 → FCC=0200` либо `FCC=0200 → CE=0000`, с парой
`09:43 + 19:67` и независимой проверкой Transmission graph. Production polling
через повторные подключения для этого запрещён.

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
| `51:04` | 33 B, route `0xEE → App`; firmware function `wlm_push_dev_osd` | OBSERVED + static symbol context |
| `51:14` | 51 B, route `0xEE → App`; neighbour list `2 + 49 × N`, здесь `N=1`; 23-byte identity region peer содержит полный aircraft `1581...` identity | OBSERVED + static data flow; полный S/N хранится только в ignored raw corpus |
| `00:81/82` | `rc331` controller identity | OBSERVED |
| `00:99` | Строки `cam_lens_state`, `cam_stream_status` | OBSERVED camera service telemetry |
| `02:80` | Legacy name `Camera State Info / Status Push`; current layout надо проверять по длине | OBSERVED + static name |
| `04:05` | Legacy `Gimbal Params / Push Position`; current payload требует size-specific decode | OBSERVED + static name |
| `03:D7` | Крупные flight-record/log chunks, самый частый frame | OBSERVED; semantic/layout UNKNOWN |

`51:14` особенно важен для 4G: направление противоположно FreeFCC activation
burst (`0xEE → App`, а не `App → 0xEE`) и identity приходит в штатном live
cellular/link потоке. RM510 `dji_wlm` подтверждает назначение: это
`wlm_push_neigh_info`, переменный список соседних устройств с их
SDR/LTE/Wi-Fi state, receive timestamps и link modes. Поэтому наличие serial в
этом кадре не является признаком срабатывания FreeFCC 4G sweep — это штатное
описание текущего peer/link topology.

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
Реализация использует выбранный для модели passive stream только до этого
события; после обнаружения Home Point listener закрывается до FCC write. Начиная
с `1.5.27`, временный разрыв не прекращает ожидание: stream переподключается
через 5 s, чтобы не повторять подтверждённый RC2 link disruption от частых
открытий.

Что ещё не доказано: настоящий radio-mode readback. Legacy name `06:21 RC Power
Mode CE/FCC Get` найден, но direct `40009` route не ответил и payload layout
неизвестен. `09:43 bit1` сохраняется только как Avata-specific correlation и
не переносится на Air 3S; физический FCC/CE по-прежнему проверяется в DJI Fly.

Дополнительный corpus audit подтвердил, что `06:72 payload=00` — только
ACK/status успеха, а не значение CE/FCC. Наблюдаемые `09:08`, `09:20`, `09:36`
и `09:3A` относятся к quality/energy/link metrics. `09:21` и современный
`09:43` остаются кандидатами только для синхронного помеченного CE↔FCC A/B.
Важно: поле `i:39` в `fcc.json` десятичное и попадает на провод как
`cmdId=0x27`, то есть `09:27`, а не `09:39`; это write-only команда.

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

## GPS master switch live probe

RC2 `rc331` + Avata 360/O4, одиночные wrapped-запросы на `40007`:

- `03:F7`, hash LE `82 95 42 c5`, вернул metadata `gps_enable`: `u8`, size 1,
  attribute 3, min `0`, max `1`, default `1`;
- `03:F8` вернул `00 829542c5 01`, то есть текущий GPS был включён;
- одиночные `03:F9 ... 00` при DJI Fly и при FreeFCC в foreground дали только
  socket-write evidence; пользователь не увидел отключения GPS, а matching
  post-write response не пришёл;
- серия диагностических reconnect на `40007` один раз совпала с потерей GNSS
  module/link и потребовала aircraft restart, поэтому polling и полный E1 scan
  остаются запрещены.

Итог: hash и readback доказаны, hash-write пока experimental. UI и LAN API
делают не больше одной записи и одного verify-read, не объявляют физическое
состояние по одному факту flush и требуют проверки в DJI Fly. RC Pro 2 должен
сначала пройти отдельный read-only port probe (`40007` против `8901..8904`).

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
| Same-session CE `40007` A/B | `572607d12a33d2794a7326603b87e00b72e9a6bfe1661f1d8271165d55dc6deb` |
| Full FCC apply status `42/42` | `227936f390dc9ec34d5a3ab011cd99b64af20cf557ce5721ffee4ca4335a5d21` |
| Same-session post-apply `40007` A/B | `cf5601b35ee74ea676042dadd2973f3247151ac4eb8ed5ef53f6c8b42b5a65cb` |
| Reversal CE short window 1 | `b4a538247e3ab21d265d28bb78a1661a3079ff56b87cbca4a93f82ba6454817e` |
| Reversal CE short window 2 | `6d6c3a104a010b5ad7dd5cea508bd7d545d2d9e0154fc6df3dae3d6914a40a4d` |

Raw corpus содержит coordinates и полный aircraft serial, поэтому не должен
попадать в git/release artifacts. В tracked docs identity намеренно редактирован.

Статическая сверка: `o-gs/dji-firmware-tools` commit
`3b440f8b45264f48e86cdc304aa5d7470e5b7a8b`. Legacy command names применяются
как подсказки; current Avata/O4 payload считается декодированным только там, где
layout подтверждён live transition или независимым payload evidence.
