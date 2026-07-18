# План удалённой DUML-лаборатории FreeFCC

Дата: 2026-07-19.

Статус: FreeFCC `1.5.12` установлен на DJI RC2 `rc331`. После команды
пользователя code freeze снят; локальный draft `1.5.13` исправляет findings
Claude и добавляет reviewed `wire_exchange`. Объединённая версия прошла 48 JVM
unit-тестов, `lintDebug` и `assembleRelease`. Три независимых финальных review
дали `GO` по parser/transport, LAN locking и FCC lifecycle. Commit, push,
подписанный release, install и live-проверка ещё не завершены.

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

## Live evidence на текущей версии

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
