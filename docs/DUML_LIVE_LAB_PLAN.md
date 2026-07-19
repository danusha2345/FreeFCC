# План удалённой DUML-лаборатории FreeFCC

Дата: 2026-07-19.

Статус: FreeFCC `1.5.16` установлен и проверен на RC2. LAN API, UDP discovery,
incremental parser и exact `wire_exchange` работают. Live-проверка `1.5.13`
выявила, что восстановленный четырёхкадровый keepalive успешно писал TCP, но не
переводил радио из CE; ручной полный `Re-apply` (21 frame × 2 rounds) реально
включал FCC. В `1.5.14` service сначала выполняет полный bootstrap и только
после его успешной записи переходит на лёгкий повторяющийся профиль. Пользователь
физически подтвердил FCC в текущей сессии. Outdoor-проверка выявила новый
детерминированный переход: если FCC применён до записи Home Point, в момент
записи Home Point радио возвращается в CE, а текущий четырёхкадровый keepalive
не выполняет полный FCC bootstrap повторно. Indoor transition capture на
Avata 360/O4 подтвердил доступный readback Home Point: passive wrapped `03:44`
на `40007`, modern payload 102 B, `home_state` LE u16 at offset 20 меняется
`0x0046 → 0x0047`, то есть bit `0x01` переходит `0 → 1`.

В release candidate `1.5.16` реализована новая one-shot схема:
`HomePointMonitor` держит одно соединение `40007` только до первого CRC-valid
`03:44` с подтверждённым `cmdType=0x00` и bit `0x01=1`, освобождает порт,
service выполняет полный `fcc.json` и останавливается. Периодический
четырёхкадровый профиль из runtime исключён. Generation/delivery gate не даёт
старому worker завершить новый запрос или стартовать его до доставки
`ACTION_START`; повтор разрешён только при отдельном preflight connect failure,
до входа в frame loop. Startup LED read имеет максимум одну повторную попытку
только при port contention до wire exchange.

Live-проверка `1.5.16` выявила: stale `keepalive_running` восстанавливал monitor
даже при `auto_fcc=false`, а повторное открытие `40007` после разрыва каждые
несколько секунд рвало aircraft/controller link. UI одновременно показывал
write evidence как будто это подтверждённый FCC, хотя пользователь физически
видел CE. В `1.5.17` persistent recovery требует оба флага, Home Point требует
свежий `false → true`, established disconnect завершается fail-closed, а UI
явно оставляет RF mode неизвестным. Повторная RC2-проверка остаётся PENDING до
установки `1.5.17`.

Полный inventory transports, command frequencies, payload evidence и privacy
границы: [DUML_STREAM_MAP.md](DUML_STREAM_MAP.md).

## Release evidence `1.5.17`

| Проверка | Результат |
|---|---|
| Main | app commit `93d12ea7340226a1ac6c6a1809b88bc1e4607a0d` запушен в `origin/main`; tag `v1.5.17` указывает на него |
| Build | Локально: 80 JVM tests, `lintDebug`, `assembleDebug`, `assembleRelease`; GitHub Actions run `29698610828`: build, tests и lint успешно |
| APK metadata | package `com.freefcc.app`, versionCode `34`, versionName `1.5.17`, APK Signature Scheme v3 |
| Совместимость подписи | Certificate SHA-256 совпадает с предыдущим установленным релизом: `1e50efc760a23d71f5ec57f855af4b8c42c21fea6da9122889d59b3b23b890ce` |
| Release artifact | `FreeFCC-1.5.17.apk`, локальный и повторно скачанный SHA-256 `7a7c8135f29e029abcc0b5c34b9c77a103d0868e56e52af53a320fac54cb24f1` |
| Release | <https://github.com/danusha2345/FreeFCC/releases/tag/v1.5.17>; GitHub `releases/latest` возвращает `v1.5.17` и этот APK |
| RC2 update | PENDING: controller не виден через ADB, прежний LAN endpoint `192.168.1.139:8787` недоступен; требуется обновление кнопкой в приложении |
| Auto-FCC runtime | PENDING: Auto-FCC OFF re-entry без monitor/link drops; затем fresh Home Point transition, один full apply и остановка listener |

## Outdoor evidence: Home Point сбрасывает ранний FCC

| Наблюдение | Уровень | Вывод |
|---|---|---|
| FCC был включён до получения спутников и записи Home Point | OBSERVED пользователем, outdoor | Это ранняя фаза aircraft session, до окончательной GPS/Home Point инициализации |
| При записи/голосовом подтверждении Home Point фактический режим вернулся FCC → CE | OBSERVED пользователем, outdoor | Home Point transition является подтверждённым триггером CE-reset для проверенной связки Avata 360 + RC2 |
| Автозапущенный FreeFCC после Home Point сам FCC не восстановил | OBSERVED пользователем, outdoor | Успешная TCP-запись короткого keepalive не означает повторное установление FCC из CE |
| Ручной полный `Re-Apply` после Home Point возвращает фактический FCC | OBSERVED пользователем | Полный `fcc.json` после Home Point остаётся рабочим recovery path |
| `FccKeepaliveSchedule` после первого успешного полного профиля навсегда выбирает `fcc_keepalive.json` до перезапуска service | OBSERVED в source | Текущее поведение полностью объясняет live-результат: CE-reset после bootstrap не вызывает новый full apply |
| При повторном indoor запуске `03:44 home_state` изменился `0x0046 → 0x0047` | OBSERVED в 27 сохранённых wrapped captures | Для Avata 360/O4 bit 0 at payload offset 20 является рабочим Home Point recorded readback; переход совпал с ростом GPS readiness |
| После US-spoof Home Point фактический режим был FCC | OBSERVED пользователем | Региональная инициализация по GPS/Home Point влияет на итоговый radio mode; US и CE outdoor дают разные результаты |

### Требование к автоматизации

Предпочтительная схема не должна угадывать Home Point только по числу спутников:

1. После старта aircraft session перейти в `WAIT_HOME_POINT`, не объявляя
   физический FCC по одному факту TCP write.
2. Читать passive wrapped `03:44` (`OSD Home Point Get`) на `40007`. На текущем
   102-byte Avata/O4 layout `home_state` подтверждён в payload offset `20`, а
   bit `0x01` означает `Is Home Point Recorded`. Direct request на `40009`
   matching response не дал.
3. После подтверждённого состояния Home Point `1` выполнить полный
   `fcc.json` один раз. Короткий `fcc_keepalive.json` пока исключить из целевой
   схемы: live он не восстановил FCC после CE-reset, а пользовательское
   наблюдение указывает, что после Home Point полный apply сохраняется сам.
4. Независимо проверить read-only `06:21` (`RC Power Mode CE/FCC Get`). Если
   команда реально возвращает CE на RC2, она предпочтительнее косвенного GPS
   условия: при любом последующем CE можно заново выполнить полный bootstrap.
5. Не открывать `40007` socket часто: live polling через новые соединения рвал
   aircraft/controller link. Повторная проверка показала, что даже один
   завершённый `wire_exchange` в секунду рвёт связь. Continuous polling через
   новые `40007` connections запрещён; до отдельной transport-проверки допустим
   только одиночный capture по явной bench-команде. Для production нужно искать
   пассивный источник уже принятой DJI Fly telemetry либо проверить один
   долгоживущий listener без повторных connects.
6. Отдельно проверить outdoor persistence после Home Point: полный apply,
   обычная работа DJI Fly и aircraft session без каких-либо повторных FCC
   команд. Только фактический возврат в CE будет основанием добавлять новый
   повторный apply.

Источник названий и начального layout: локальный checkout
`o-gs/dji-firmware-tools`, `dji-dumlv1-flyc.lua` (`03:44`) и
`dji-dumlv1-proto.lua` (`06:21`). Совместимость prefix `03:44` с Avata 360/O4
теперь подтверждена live transition; `06:21` всё ещё требует рабочего route и
payload decode.

### Сравнение с upstream

`upstream/main` на commit `3887bc0bdae95ad20c6ceea04ed9b9729c2d9f83`
имеет ту же архитектурную проблему: Auto-FCC немедленно отправляет полный
`fcc.json` без ожидания GPS/Home Point, после чего service повторяет только
четырёхкадровый `fcc_keepalive.json`. Home Point и `06:21` не читаются.
Следовательно, одинаковый дефект upstream является DERIVED из его source и
нашего live evidence; оригинальный APK отдельно не устанавливался.

Вторая опубликованная ветка upstream, `fix/release-signing-security` на
`0b17aa0a00386c7ea25fdd3a821f20f03c069851`, не является новой реализацией:
она на 27 commits позади `upstream/main` и вообще не содержит foreground
keepalive/`BootReceiver`. После Home Point она может восстановить FCC только
повторным ручным запуском полного apply.

## Release evidence `1.5.16`

| Проверка | Результат |
|---|---|
| Main | app commit `de26d32fef4db30fabc60817a51dc6982c10c343` запушен в `origin/main`; tag `v1.5.16` указывает на него |
| Build | Локально: 73 JVM tests, `lintDebug`, `assembleDebug` и `assembleRelease`; GitHub Actions run `29691793556`: build, tests и lint успешно |
| APK metadata | package `com.freefcc.app`, versionCode `33`, versionName `1.5.16`, APK Signature Scheme v3 |
| Совместимость подписи | Certificate SHA-256 совпадает с предыдущим установленным релизом: `1e50efc760a23d71f5ec57f855af4b8c42c21fea6da9122889d59b3b23b890ce` |
| Release artifact | `FreeFCC-1.5.16.apk`, SHA-256 `d46e8a5f90ce19794ff04c62b99c06fc31a6b12d45e02bb17e59076504e205f0` |
| Release | <https://github.com/danusha2345/FreeFCC/releases/tag/v1.5.16> |
| RC2 update | PENDING: ADB device отсутствует, прежний `192.168.1.139:8787` недоступен, UDP beacon за 15 s не получен |
| Auto-FCC runtime | PENDING: exact Home Point transition, один full apply, listener/service остановлены без повторных writes |
| LED runtime | PENDING: один startup read, readback после ON/OFF, без polling |

### Live regression `1.5.16`, исправляемая в `1.5.17`

| Время / среда | Наблюдение | Вывод |
|---|---|---|
| 2026-07-19 21:46–21:52 MSK, Avata 360 + RC2 `rc331` | При `auto_fcc=false` приложение восстановило `Home Point monitor requested state`; связь с aircraft каждые несколько секунд пропадала и возвращалась | Stale `keepalive_running` ошибочно считался durable intent, а reconnect loop на `40007` физически мешал каналу |
| Та же сессия | После завершения monitor периодические обрывы прекратились | Causal связь с monitor/reconnect подтверждена пользователем |
| Та же сессия | `/api/status`: `fcc_sequence_written=true`, `fcc_enabled=null`; UI сообщал FCC write, но пользователь физически видел CE | TCP write evidence не является readback фактического RF mode |
| Hotfix `1.5.17` | Recovery только при `auto_fcc && keepalive_running`; stale marker очищается; initial true игнорируется до свежего false→true; established disconnect fail-closed | PENDING live retest после in-app update |

## Release evidence `1.5.14`

| Проверка | Результат |
|---|---|
| Main | `0292426f7c2af6df5b20ab5af51830bc560e9ef4` запушен в `origin/main` |
| Build | 50 JVM tests, `lintDebug` и `assembleRelease` завершены успешно |
| APK metadata | package `com.freefcc.app`, versionCode `31`, versionName `1.5.14`, APK Signature Scheme v3 |
| Совместимость подписи | Certificate SHA-256 совпадает с предыдущим установленным релизом: `1e50efc760a23d71f5ec57f855af4b8c42c21fea6da9122889d59b3b23b890ce` |
| Release artifact | `FreeFCC-1.5.14.apk`, SHA-256 `76749a6984df562c6d61882f4b465c053867d72bba18e7c880faa4a147df39e2` |
| Release | <https://github.com/danusha2345/FreeFCC/releases/tag/v1.5.14> |
| RC2 update | In-app updater скачал APK, Android installer выполнил обновление, LAN bridge после запуска сообщил `app_version=1.5.14` |
| FCC runtime | Полный bootstrap завершён, затем timestamp лёгкого keepalive обновлялся примерно каждые 2,23 s; пользователь физически подтвердил FCC |
| Outdoor / Home Point | Ранний FCC сбрасывается в CE при записи Home Point; Auto-FCC 1.5.14 сам его не восстанавливает; ручной полный `Re-Apply` после Home Point работает |
| Home Point readback | CONFIRMED: passive wrapped `03:44` на `40007`, payload 102 B, offset 20 `0x0046 → 0x0047`; continuous `wire_exchange` polling запрещён, потому что даже 1 Hz рвёт link |
| Осталось проверить | Radio mode через `06:21`/другой O4 signal; затем реализовать и повторить полный cold boot с автоматическим post-Home-Point recovery |

## Release evidence `1.5.13`

| Проверка | Результат |
|---|---|
| Main | `cb51159bcfc89a5d17690a912b63c9da51d97ee4` запушен в `origin/main` |
| GitHub Actions | run `29664093013`: build, 48 tests и Android lint завершены успешно |
| CI signing | Repository signing secrets отсутствуют; CI загрузил только `app-release-unsigned.apk` |
| Совместимость подписи | Release подписан локальным `~/.android/debug.keystore`; certificate SHA-256 совпадает с `v1.5.12`: `1e50efc760a23d71f5ec57f855af4b8c42c21fea6da9122889d59b3b23b890ce` |
| APK metadata | package `com.freefcc.app`, versionCode `30`, versionName `1.5.13`, APK Signature Scheme v3 |
| Release artifact | `FreeFCC-1.5.13.apk`, SHA-256 `d5be20bfac263cf8cb9a004576a9c74eec052b4505b1c82075fd8eb7e3f3db38` |
| Release | <https://github.com/danusha2345/FreeFCC/releases/tag/v1.5.13> |
| RC2 reachability после release | ADB device отсутствует; старый `192.168.1.139:8787` недоступен; UDP beacon за 15 s не получен |

## Live evidence `1.5.13`

| Проверка | Результат | Вывод |
|---|---|---|
| Install/discovery | `app_version=1.5.13`, `192.168.1.139:8787`, UDP beacon получен | Обновление и LAN bridge работают |
| Восстановленный keepalive | `keepalive_status=running`, successful-write timestamp обновлялся примерно каждые 2,24 s, но пользователь физически видел CE | TCP write сокращённого профиля не доказывает и не устанавливает FCC из CE |
| Ручной `Re-apply` | 42/42 writes completed; пользователь физически подтвердил FCC | Полный `fcc.json` работает на текущем aircraft/controller session |
| Сравнение профилей | keepalive: 4 frames × 1; manual: 21 frames × 2 | При старте keepalive нужен полный bootstrap; короткий профиль допустим только после него |
| Passive `40009`, 3 s | 26 CRC-valid frames; `06:AE`, `06:77`, `06:A4`, `00:81`, `00:82` | Новый parser устойчиво разбирает живой поток |
| Identity telemetry | `00:81` и `00:82` содержат ASCII `rc3331` | Это runtime controller telemetry; не aircraft serial |
| Direct `03:F8` на `40009` | write completed, matching response нет, получен unmatched `06:AE` | Direct path по-прежнему не возвращает lamp read response |
| Wrapped `03:F8` на `40007` | `wire_exchange` вернул 1989 B; внутри response `551304030302ad108003f800a259ceedefaec0` | Валидный payload `00 a259ceed ef`: lamp parameter прочитан, значение default/on |
| Device info / serial | `No response from controller`; serial пуст | Текущие structured routes не подтверждены |
| 4G probe | abstract socket `/duss/mb/0x205` reachable | Endpoint есть; modem type/eSIM и activation не доказаны |

## Трекер исправлений `1.5.13`

| Область | Состояние в worktree | Проверка |
|---|---|---|
| Incremental DUML parser | Исправлено: EOF завершает чтение, 250 ms timeout не заменяет общий deadline, false `0x55` делает shift-by-one resync | Synthetic EOF/pause/resync tests |
| Evidence model | Исправлено: `matchedFrame`, `lastCompleteUnmatchedFrame`, `partialTail` раздельно | Regression test с unmatched + partial |
| Keepalive starvation | Исправлено: hardware/LED lease освобождается сразу после same-socket `flush()` | Test берёт `HardwareLock` до прихода response |
| LAN pool starvation | Исправлено: process-wide single-flight для send/request/capture/wire, конкурентный probe получает `409 diagnostic_busy` | Independent LAN review: GO |
| False wire success | Исправлено: `writeCompleted`/`failureStage`, HTTP `502 send_failed` при connect/write failure | Transport + LAN reviews: GO |
| Stale FCC state | Исправлено: старый persistent `fcc_sequence_written` удаляется; write evidence process-local с timestamp, explicit Connect начинает UNKNOWN session | `FccRuntimeTrackerTest` |
| Keepalive UI/lifecycle | Исправлено: runtime `StateFlow`, actual starting/running/stopped/failed; start/stop/callback используют общий monitor и stale intents сверяются с latest desired state | Lifecycle review: GO; Android live pending |
| FGS start intent | Исправлено: новый persistent intent пишется только после принятого `startForegroundService`; transient restore failure не стирает существующий intent | Lifecycle review: GO; Android live pending |
| Exact raw wire | Реализовано: allowlisted localhost ports, TX/RX/deadline bounds, same-socket raw response | Synthetic exact-byte test; RC2 live pending |

## Triage `CODE_REVIEW_a4e4305.md`

| Finding Claude | Вердикт после сверки | Решение для плана |
|---|---|---|
| 1. Long `duml_request` держит `HardwareLock` | CONFIRMED, release blocker | Разделить короткую write-critical section и ожидание ответа; matching read не должен блокировать keepalive 3-10 s |
| 2. Stale `fcc_sequence_written` | CONFIRMED, state-model blocker | Не называть persistent last write `fcc_enabled`; перейти на runtime state + timestamp/UNKNOWN после новой hardware session |
| 3. Capture без guard занимает LAN pool | CONFIRMED для pool exhaustion и отсутствия single-flight; ACK loss не доказан | Один capture одновременно; не держать общий `HardwareLock` весь capture, иначе повторится finding 1 |
| 4. EOF busy-spin | CONFIRMED | Parser должен возвращать отдельные `EOF`, `TIMEOUT`, `PARTIAL`, а capture на EOF завершаться |
| 5. 250 ms тишины обрывают capture | CONFIRMED | Локальный read timeout не равен global deadline; timeout делает resync/continue до общего deadline |
| 6. Один deadline сжал read budget | PARTIAL | Изменение семантики реально, но `device_info.json` задаёт 500 ms, не 80 ms. Сохранить bounded overall deadline; tuning делать по live latency evidence |
| 7. False `0x55` съедает реальный header | CONFIRMED | Incremental buffer parser; при invalid candidate сдвигать scan на один byte, не выбрасывать весь 11-byte candidate |
| 8. Partial вытесняет last complete frame | CONFIRMED | Response model должен отдельно хранить `matching`, `lastCompleteUnmatched`, `partialTail` и counters |
| 9. Transient FGS start стирает intent | PLAUSIBLE | `FccKeepaliveService.start()` записывает pref только после успешного `startForegroundService`; catch в ViewModel не меняет persistent intent |
| 10. Service/UI desync | PLAUSIBLE/latent | Service публикует runtime `StateFlow`; UI не выводит running только из SharedPreferences |

Cleanup-находки Claude принимаются: удалить мёртвый `readBytes`, вынести общий
frame parser/header decode/time helpers, убрать магический `delay(750)`, не писать
неизменившийся pref каждые 2 s.

Дополнительный finding для незакоммиченного draft `1.5.13`: текущий
`handleLanWireExchange()` держит hardware/LED lock всё response window до 10 s,
то есть наследует starvation из finding 1. До release разделить exact write и
bounded raw read; добавить single-flight. Тест с assertion внутри server thread
тоже переделать: исключение worker thread сейчас не гарантированно валит JUnit
test на вызывающем потоке.

### Порядок исправлений после снятия code freeze

1. Один incremental DUML stream parser с bounded buffer и исходами
   `FRAME`, `TIMEOUT`, `EOF`, `PARTIAL`.
2. Перевести `sendAndReceiveRaw` и `captureFrames` на него; вернуть matching,
   unmatched evidence и partial tail раздельно.
3. Ввести single-flight для capture/wire exchange и не удерживать FCC hardware
   lock во время пассивного ожидания ответа.
4. Заменить persistent `fcc_sequence_written` на runtime FCC/keepalive state с
   last-success timestamp и честным состоянием `UNKNOWN` после новой session.
5. Исправить lifecycle foreground service и убрать polling через `delay(750)`.
6. Только затем довести `wire_exchange`, тесты, docs, version bump и release.

## Исторический live evidence на `1.5.12`

| Проверка | Результат | Вывод |
|---|---|---|
| UDP discovery + LAN API | `192.168.1.139:8787`, auth/status/commands работают | Удалённый bench channel подтверждён |
| FCC state после re-entry | `fcc_enabled=true`, `keepalive_running=true`; пользователь физически подтвердил FCC после повторного apply | UI хранит last-known write, физический режим всё равно проверяется в DJI Fly |
| Passive capture `40009`, 2 s | 6 валидных frames | `duml_capture` работает и возвращает raw hex + routing fields |
| Passive capture `40009`, 10 s | `06:AE` x6, `06:A4` x1 | На текущем broker socket это основной фоновый трафик |
| Ранее пойманный `00:81` | payload начинается `rc331` | Содержит controller identity; это не aircraft serial |
| Device Info | `No response from controller` | Текущий `00:01` request/routing на этом path не подтверждён |
| Serial probe | пусто | В passive ASCII telemetry нет `1581...` или `W[AM][0-9]{3}` |
| `03:F8`, hash `a259ceed`, direct `40009` | matching response нет для sender `0x2a` и `0x02` | Hash-read не работает на проверенном direct path либо требует другой route/wrapper |
| Wrapped `03:F8` send на `40007` | exact write завершён | Read-only request дошёл до local wrapper proxy; ответ `1.5.12` не возвращает вызывающему |
| 4G endpoint | `/duss/mb/0x205` reachable | DUSS route существует; тип modem и совместимость не доказаны |
| 4G activation | остановлен guard: no usable identity | 128 activation frames не отправлялись |

### Фоновые DUML frames

`06:AE`:

```text
sender=0xa2 dst=0x82 cmd_type=0x00
payload=0000010000000400040004000400040004
```

`06:A4`:

```text
sender=0x06 dst=0x82 seq=0 cmd_type=0x20 payload=00
```

`00:81` sample:

```text
sender=0x0d dst=0x82 cmd_type=0x40
payload prefix ASCII: rc331
```

Не назначать этим payload семантические имена без сверки с firmware/dump.

На `40007` passive capture также выдаёт structurally CRC-valid frames с
`cmd_type=0x6d/0x6e`. Поля после encryption bits могут быть неприменимы для
обычного DUML decode; не считать увиденные там `cmd_set/cmd_id` реальными
командами до разбора wrapper/encryption.

## Что должен закрыть review draft `1.5.13`

1. `wire_exchange` принимает exact `wire_hex` только для allowlisted localhost
   ports `40007`, `40009`, `8901..8904`.
2. Возвращает все bounded raw bytes с того же TCP socket без предположений о
   DUML, wrapper, CRC, routing или encryption.
3. Глобальный deadline пересчитывается перед каждым read; trickle traffic не
   удерживает HTTP worker сверх лимита.
4. Limits: TX <= 4096 B, RX <= 65536 B, window <= 10 s; без shell/filesystem/ADB
   и arbitrary network destination.
5. Hardware/LED locks не допускают пересечения exact writes с конфликтующим
   app operation.
6. Tests должны доказывать exact byte preservation, RX bound, deadline и
   malformed input rejection.

После GO и установки один exact wrapped `03:F8` request даст same-socket raw
response. Только тогда разбирать outer envelope и проверять ожидаемый payload
`00a259ceedXX`.

## Следующие protocol probes

### Version и identity

1. Сверить `00:01`, `00:81`, `00:40/41` и serial-related команды по локальным
   dumps/PCAP/декомпилированным DJI apps; для каждой записать доказанный route.
2. Отправлять по одной read-only команде через exact wire exchange.
3. После exact request отдельно запускать `duml_capture` на `40009`, чтобы
   видеть forwarded response/broadcast; LAN single-flight намеренно запрещает
   параллельные diagnostic sessions.
4. Matching response валидировать по sequence, reversed route, cmd set/id и
   CRC; ASCII strings искать только внутри уже валидного payload.
5. Не использовать cached serial для 4G, пока identity не подтверждён в текущей
   aircraft session.

### LED readback

1. Baseline exact wrapped `03:F8`, hash `a259ceed`.
2. LED OFF write `03:F9`, затем новый `F8` read.
3. LED ON write `03:F9`, затем новый `F8` read.
4. Сопоставить `XX` с визуальным состоянием; не переносить значения между
   моделями без live evidence.
5. Если F8 не отвечает, искать model-specific index через `03:E1`, затем
   read-only `03:E2`; `03:E3` не нужен для чтения.

### Встроенный eSIM / 4G

1. Зафиксировать три baseline: aircraft off, linked без Enhanced Transmission,
   linked со штатно включённым Enhanced Transmission.
2. Для каждого состояния проверить endpoint, capture и системные DUSS/LTE logs.
3. Добавлять отдельный allowlisted exact DUSS exchange для abstract socket
   `/duss/mb/0x205` только после review; TCP `wire_exchange` этот transport не
   покрывает.
4. Сначала искать read/status/auth commands и identity. Не отправлять 128-frame
   burst с выдуманным serial/model code.
5. Activation burst допустим после получения текущей identity либо после
   подтверждения точного Avata 360 payload из штатного DJI traffic.
6. Успех означает изменение реального Enhanced Transmission state/link, а не
   только 128 успешных socket writes.

## Критерии закрытия

- Любой новый TCP wrapper можно проверить через LAN без APK rebuild.
- Version и controller/aircraft identity получены из валидного response и
  воспроизводятся минимум дважды.
- LED state читается до/после writes и совпадает с физическим состоянием.
- Re-entry не теряет last-known FCC write, keepalive request повторно запускает
  service, а UI не выдаёт persistent intent за physical readback.
- Для Avata 360 4G отдельно доказаны endpoint, identity, writes и фактический
  Enhanced Transmission state.
