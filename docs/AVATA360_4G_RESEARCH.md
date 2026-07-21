# Трекер Avata 360: встроенный eSIM и 4G Enhanced Transmission

Дата начала: 2026-07-18.

Цель: проверить, совместим ли встроенный eSIM-модем DJI Avata 360 с текущим
FreeFCC-профилем из 128 DUML/DUSS кадров, не смешивая доступность локального
socket, факт записи кадров и реальную активацию Enhanced Transmission.

## Доказано

| Факт | Уровень | Источник / проверка |
|---|---|---|
| DJI Avata 360 Enhanced Transmission edition имеет встроенный модуль и IoT eSIM | OBSERVED | Официальные DJI Store/FAQ: `https://store.dji.com/cn/product/dji-avata-360`, `https://repair.dji.com/help/content?customId=zh-cn03400008285&lang=zh-CN` |
| С RC2 наземное интернет-плечо Avata 360 использует Wi-Fi hotspot телефона | OBSERVED | Официальный DJI FAQ по Enhanced Transmission |
| Текущий FreeFCC отправляет 128 кадров через abstract socket `/duss/mb/0x205` | OBSERVED | `DumlTransport.sendFramesUnix()`, `profiles/4g.json` |
| Payload каждого кадра: `000000 + ASCII(identity)`, `cmd_set=0x51`, `cmd_id=0..127` | OBSERVED | `Profiles.load4g()`, `profiles/4g.json` |
| Штатный DJI Fly eSIM flow использует `cmd_set=0x18`, request/push `cmd_id=0x4b` и push ACK `cmd_id=0x4c` | OBSERVED | ARM64 disassembly `libdongle_esim_core.so` из DJI Fly 1.21.4 |
| Штатный DJI flow и 128-frame профиль FreeFCC — разные протоколы | DERIVED | Различаются command set, command IDs, payload layout, ACK и lifecycle observer |
| Socket connect не даёт ACK и не доказывает физический тип модема или активацию | DERIVED | API используется как fire-and-forget; response/readback отсутствует |
| На связанном Avata 360 с RC2 endpoint `/duss/mb/0x205` reachable | OBSERVED | Live probe через FreeFCC 1.5.6 и LAN log, 2026-07-18 23:03 MSK |
| В первой live-пробе identity не найден: ни `1581...`, ни `WA/WM` | NEGATIVE | Guard остановил flow до отправки 128 кадров; фактических 4G writes не было |
| LED profile работает на Avata 360; `LED OFF` также гасит индикаторы батареи | OBSERVED | Live sequence `ON`, `OFF`, `OFF`; визуальное подтверждение пользователя и LAN write-log, 2026-07-18 23:04-23:05 MSK |
| Имеющиеся локальные Mavic 4 / Air 3 / Air 3S / WA234 дампы относятся к внешнему cellular path | OBSERVED | Локальные notes и corpus; пользователь подтвердил topology |
| Отдельный Avata 360 eMMC dump в `/mnt/toshiba_6tb/dumps_emmc_dji` по имени не найден | NEGATIVE | Read-only поиск на `homesrv`, 2026-07-18 |

## Гипотезы

| Гипотеза | Что подтвердит | Что опровергнет |
|---|---|---|
| Встроенный eSIM route в итоге проходит через mailbox `0x205` | Inode→PID/static owner evidence либо capture штатного `0x18:4b/4c` на этом mailbox | Тот же штатный exchange наблюдается на другом route |
| 128-frame профиль подходит без изменения | После write burst DJI Fly показывает активный 4G link; желательно capture request + state transition | Кадры записываются, но link/state не меняется или появляются model-specific errors |
| Avata 360 отдаёт полный `1581...` S/N через localhost DUML proxy | `probeSerial()` фиксирует свежий полный S/N | В capture присутствует только другой model identifier, например `FCA188` |
| Для Avata нужен другой activation/auth flow | DJI Fly traffic показывает auth/code/cloud calls или другие `0x51` cmd IDs перед включением | Полный внешний-module flow побайтно совпадает и работает |

## Статический разбор DJI Fly 1.21.4

Проверен artifact, совпадающий с версией на RC2:

| Поле | Значение |
|---|---|
| package / version | `dji.go.v5`, `1.21.4` (`3115357`) |
| APK size | `706 242 028` bytes |
| APK SHA-256 | `26649c10483a090ca184fefe70b3694e5cdb72b538718d502dc1419e706e0a03` |
| Подпись | APK Signature v2 valid, `CN=dji` |
| Certificate SHA-256 | `01a42c94aa87020f41e8c252598df98b86a0acf6c05ff61c75f18fa29cad1dd4` |
| `libdongle_esim_core.so` SHA-256 | `17ed3758be57f2be7886e2b743ab1f2c16ef2efe3cd22f0e0d08f5d5160b11d2` |
| ELF Build ID | `e91e694ee8390748eb6f8a8ef963f17638596fc8` |
| `.text` SHA-256 | `98e388e84b99f92e7c2ddd46f495ab32fc90764cb4840b549e91cfa96a9cf206` |

Executable `.text` этой eSIM-библиотеки побайтно совпадает с доступной
библиотекой из 1.19.4. Поэтому ранее найденные ARM64 call sites подтверждены уже
на точной текущей версии, а не перенесены как cross-version гипотеза.

Восстановленная последовательность штатного flow:

1. `StartEsimActivation(TargetDongleInfo, callback)` формирует структурированный
   request body длиной `17` bytes через `GetSettingEsimPack()`.
2. Конструктор `uav_cmd_base_req<1,24,75,...>` и записи header создают
   `cmd_set=0x18`, `cmd_id=0x4b`; `SendEsimConfigPack()` отправляет один request
   через DJI SDK `send_data()` и получает callback результата.
3. `RegisterObservers()` регистрирует push observer на `cmd_id=0x4b`.
4. `OnDongleEsimActivationProgressPush()` требует не менее `13` bytes,
   разбирает stage/progress/error и уведомляет application observer.
5. `SendActivationPushACK()` строит ответ `cmd_set=0x18`, `cmd_id=0x4c` с
   пятибайтовым body.

Та же библиотека содержит отдельные операции `CancelEsim`,
`SwitchSimCardSlot` и `SwitchTelecomOperator`. Это полноценный stateful eSIM
API, а не перебор 128 command IDs.

Напротив, FreeFCC сейчас повторяет экспериментальный OpenFCC-профиль:

- `128` независимых DUML frames;
- `cmd_set=0x51`, `cmd_id=0x00..0x7f`;
- одинаковый body `000000 + ASCII aircraft identity`;
- fire-and-forget write через abstract `/duss/mb/0x205`;
- нет ACK parser, activation progress и state readback.

Это не доказывает, что OpenFCC-профиль бесполезен: он может быть отдельным
service/exploit provisioning flow. Но называть его реализацией штатной кнопки
DJI Fly нельзя. До live state transition успешная запись 128 frames означает
только transport write completion.

Полный поиск по `15 632` entries DJI Fly 1.21.4 не нашёл hardcoded paths
`/duss/mb*`, `dji_lte_v1`, `wl_lte_v1`, `lte_liveview*` или
`wlm_fmsg_forward`. Следовательно, эти Unix sockets создаются системными
services пульта/aircraft stack. Наличие `/duss/mb/0x205` доказывает route, но не
устанавливает его владельца, тип модема, SIM registration или data session.

## Выполненные изменения FreeFCC

- Термин `dongle detected` заменён на точное `DUSS endpoint reachable`.
- Добавлена read-only кнопка **Probe 4G Endpoint**: она не отправляет 128 кадров.
- Добавлен автоматически запускаемый LAN log bridge на private Wi-Fi IPv4:
  HTTP port `8787`, фиксированный пароль и UDP-маячок discovery на port `8788`.
  Маячок передаёт только magic, IP и HTTP port — без пароля и логов.
- Исправлен найденный на RC2 lifecycle-баг: при повторном входе новый экран
  терял URL уже запущенного socket и показывал `already in use`. Сервер и журнал
  теперь живут на уровне процесса, а ViewModel восстанавливает текущий status.
- Вкладка **Support** скрыта из pager и bottom navigation; её код оставлен для
  возможного возврата.
- После live-находки 1.5.6 исправлена отмена Auto-FCC: выключение toggle отменяет
  активный coroutine на ближайшей безопасной точке и не позволяет старому flow
  включить keepalive/открыть DJI Fly. При оставленном `Auto-FCC ON` штатный
  автозапуск DJI Fly сохранён.
- Updater и ссылки переведены на `danusha2345/FreeFCC`.
- Avata 360 в README больше не помечен как модель без cellular hardware.
- В 1.5.8 уплотнён GUI для небольшого экрана RC2: уменьшены page/card padding,
  верхние и межсекционные интервалы, высота кнопок и bottom navigation; длинные
  подсказки сокращены без удаления предупреждений и функций. Live-оценка:
  улучшение есть, но верхняя шапка и MODE всё ещё занимали лишнюю высоту.
- В 1.5.9 шапка FCC сведена в одну строку: название, версия/model и компактный
  connection indicator; верхний отступ уменьшен до `8dp`. После live-оценки
  дополнительно увеличен version/model font и уплотнён MODE в 1.5.10.
- Update changelog нормализует ошибочно опубликованные literal `\\n` в реальные
  переносы и скрывает digest/signing metadata; SHA-256-проверка APK остаётся
  обязательной, но не занимает место в GUI.
- В 1.5.10 download state до первого байта показывает indeterminate
  `Connecting to GitHub...`, а не ложное зависание на `0%`; проценты появляются
  только после начала передачи.
- В 1.5.10 вся видимая палитра заменена: neutral graphite background/cards,
  warm orange primary accent, mint success, gold warning, coral error и более
  контрастные neutral text levels для экрана RC2.
- Для 1.5.11 добавлен LAN Control API поверх существующего bridge: status,
  allowlisted app actions и raw `duml_send`/`duml_request` с сохранением полного
  response frame и строго валидированного payload. Доступ ограничен localhost
  DUML-портами `40007`, `40009`, `8901..8904`; shell, filesystem, ADB и
  произвольные network destinations не экспортируются.
- LAN command endpoint требует password header и form Content-Type; legacy
  query-password оставлен только для `/logs`. HTTP worker pool и очередь
  ограничены, request/header/body имеют реальные byte limits.
- При повторном входе bridge сверяет текущий Wi-Fi IPv4 с адресом bind и делает
  rebind после смены сети, вместо ложного `already running` на старом IP.
- Индикатор connection после повторного входа теперь заново проверяет локальный
  DUML proxy. Это reachability probe, а не постоянная TCP-сессия и не доказательство
  связи с aircraft.
- LED UI больше не выдаёт write completion за физическое состояние: результат
  хранит отдельное фактическое состояние `ON/OFF/PARTIAL/UNKNOWN`. Для readback
  используется одиночный wrapped `03:F8` на `40007`, payload `a259ceed`; после
  write выполняется readback verify, а отсутствие ответа сбрасывает state в
  `UNKNOWN`.
- По коду `android-lamp-off` подтверждён альтернативный model-index path:
  `03:E1` metadata -> `03:E2` read -> `03:E3` write -> новый `03:E2` verify.
  Для Avata 360 индекс lamp пока неизвестен, поэтому первым проверяется hash-read.
  Индексы других моделей не переносятся; полный E1 scan по RC proxy запрещён до
  отдельной проверки нагрузки.
- Добавлен fallback запуска `com.dji.industry.pilot` (DJI Pilot 2) после
  неизменённых DJI Fly и DJI Go 4 путей для RC Plus.
- В 1.5.12 добавлен `duml_capture`: bounded passive capture всех валидных
  кадров, которые localhost broker отдаёт отдельному socket, с raw hex и
  декодированными routing/command fields. Это не root-level loopback sniffing.
- В 1.5.13 добавлен `wire_exchange`: exact bounded wire request/response без
  предположений о DUML wrapper, CRC или encryption. Он закрывает удалённую
  проверку новых transport-вариантов без очередной пересборки APK.
- Диагностические LAN-сессии сериализованы, а FCC/LED write-lease отпускается
  сразу после `flush()`: длинное чтение raw response не блокирует keepalive.
- После Home Point исследования подготовлена unreleased one-shot автоматика:
  один persistent `40007` listener до `03:44 home_state bit0=1`, полный FCC apply
  один раз и остановка service. Старый короткий keepalive больше не исполняется.
  LED использует тот же port lease: один startup read и verify после `ON/OFF`,
  без polling и без параллельного socket с Home Point listener.

## План live-проверки

1. Подключить Avata 360 к RC2 через OcuSync и оставить DJI Fly запущенным.
2. В FreeFCC нажать **Connect**, записать найденный identity и controller port.
3. Убедиться, что вкладка **Log** показывает `Ready`; Codex обнаружит RC2 по
   UDP-маячку и подключится к HTTP endpoint с известным паролем.
4. Нажать только **Probe 4G Endpoint**; получить логи через LAN.
5. Сравнить три состояния: дрон выключен, дрон связан без Enhanced Transmission,
   дрон связан и штатный 4G включён в DJI Fly.
6. Не отправлять 128-frame burst в baseline-сессии. Сначала получить отдельный
   пассивный capture штатного DJI `0x18:4b/4c` flow либо owner evidence для
   mailbox `0x205`. Экспериментальный burst запускать только отдельным тестом с
   явной проверкой DJI Fly state, HMS errors и сетевого link до/после.

## Журнал результатов

| Дата/время | Модель/firmware | Состояние | Identity | `/duss/mb/0x205` | Результат |
|---|---|---|---|---|---|
| 2026-07-18 | Avata 360, firmware TBD | Подготовка | TBD | TBD | Ожидается live test |
| 2026-07-18 | DJI RC2 | FreeFCC 1.5.5 runtime | n/a | n/a | OBSERVED: после повторного входа UI потерял status/URL и сообщил, что bridge уже запущен; исправлено в 1.5.6 |
| 2026-07-18 23:03 MSK | Avata 360 + RC2 `rc331` | Связь активна, FreeFCC 1.5.6 | Не найден | reachable | 4G send нажат, но guard остановил flow до кадров: `probe did not return ... identity` |
| 2026-07-18 23:02 MSK | DJI RC2 `rc331` | Auto-FCC выключен во время apply | n/a | n/a | OBSERVED: старый flow после disable всё равно включил keepalive и открыл DJI Fly; исправлено в 1.5.7 |
| 2026-07-18 23:04-23:05 MSK | Avata 360 + RC2 `rc331` | LED test: `ON`, `OFF`, `OFF` | n/a | n/a | Все три writes успешны и физически сработали; `OFF` гасит также battery LEDs |
| 2026-07-18 23:25-23:26 MSK | Avata 360 + RC2 `rc331`, FreeFCC 1.5.8 | Device Info x2, 4G probe | Не найден | reachable | Version request дважды без response; 4G endpoint повторно reachable |
| 2026-07-18 23:20-23:30 MSK | DJI RC2 `rc331` | UI review 1.5.8 -> 1.5.9 | n/a | n/a | 1.5.8 hardware-installed; выявлены лишняя высота header/MODE и literal `\\n`/digest в changelog |
| 2026-07-18 23:29-23:30 MSK | DJI RC2 `rc331` | Auto-update 1.5.8 -> 1.5.9 | n/a | n/a | Update успешен; до первого байта UI мог долго показывать `0%`, затем download продолжался |
| 2026-07-18 23:39 MSK | DJI RC2 `rc331` | FreeFCC 1.5.10 installed | n/a | n/a | LAN подтверждает старт 1.5.10 и `Up to date`; итоговая визуальная оценка новой палитры ожидается |
| 2026-07-18 | Source cross-check | `android-lamp-off` + factory DLL | n/a | n/a | OBSERVED: lamp state доступен через hash read `03:F8`; response layout `[status][hash LE][u8 value]`; live Avata 360 probe ещё не выполнен |
| 2026-07-18 | FreeFCC 1.5.11 release candidate | JVM/lint/release build | n/a | n/a | 33 unit tests passed; `lintDebug` и `assembleRelease` успешны; подписанный APK SHA-256 `05e1a2df47dce1097a9d81ce556f1c0cf1ae206cb2260558aba41206e9d7f143` |
| 2026-07-18 | Source indexes | GitNexus + local/global Graphify | n/a | n/a | GitNexus: 913 nodes / 2,209 edges / 78 flows; local Graphify: 292 nodes / 516 edges; global graph: 17,135 nodes / 47,808 edges. Проверены пути `FreeFCC -> DUML:03:E2 -> fpga_tang_nano_9k_card_reader-spinal` и `FreeFCC -> DUML:06:72` |
| 2026-07-19 01:00-01:08 MSK | Avata 360 + RC2 `rc331`, FreeFCC 1.5.11 | LAN Control live test | Не найден | reachable | OBSERVED: UDP discovery, auth, status, command list, ping/connect, LED OFF/ON и 4G endpoint probe работают; device info и serial response не получены |
| 2026-07-19 01:00-01:08 MSK | Avata 360 + RC2 `rc331`, FreeFCC 1.5.11 | DJI Fly + повторный FCC apply | n/a | n/a | OBSERVED пользователем: после запуска DJI Fly и повторного `Enable FCC` режим FCC физически включился на дроне |
| 2026-07-19 01:04 MSK | Avata 360 + RC2 `rc331`, FreeFCC 1.5.11 | Raw `03:F8` read | n/a | n/a | Runtime-дефект: proxy присылает посторонний telemetry frame раньше matching response, а reader 1.5.11 завершался на первом frame с `response_validation_failed`; исправлено фильтрацией до общего timeout в 1.5.12 |
| 2026-07-19 01:04-01:08 MSK | Avata 360 + RC2 `rc331`, FreeFCC 1.5.11 | Keepalive lifecycle | n/a | n/a | Во время диагностического `keepalive_stop` DJI Fly вернул CE; повторный FCC apply восстановил FCC. Также подтверждено, что после re-entry UI восстанавливал keepalive flag, но терял `isFccEnabled`; в 1.5.12 service start reasserted, первый tick немедленный, а keepalive request отделён от last-known успешной FCC write |
| 2026-07-19 | FreeFCC 1.5.12 release candidate | JVM/lint/release build | n/a | n/a | 37 unit tests passed; `lintDebug` и `assembleRelease` успешны; подписанный APK SHA-256 `0d0711684ef990ca573c993abdca8b55199324b0c2ae3d8a8c87b7e9961f37ce` |
| 2026-07-19 | Avata 360 + RC2 `rc331`, FreeFCC 1.5.12 | `duml_capture` + `03:F8` live | Не найден | reachable | OBSERVED: passive capture на `40009` получил валидные `06:AE`, `06:A4`, `00:81`; direct `03:F8` с sender `0x2a` и `0x02` matching response не дал; wrapped F8 write на `40007` успешен, но 1.5.12 не возвращает same-socket raw response — добавлен `wire_exchange` в 1.5.13 |
| 2026-07-19 | Avata 360 + RC2, FreeFCC 1.5.13 | `wire_exchange` + identity/4G probes | Не найден | reachable | OBSERVED: `40007` same-socket response содержит валидный `03:F8` payload `00a259ceedef`; passive `00:81/82` содержит controller string `rc3331`; `device_info` и aircraft serial не ответили; eSIM compatibility не доказана |
| 2026-07-19 | Avata 360 + RC2, FreeFCC 1.5.14 | Full FCC bootstrap + lightweight keepalive | n/a | reachable | OBSERVED пользователем: FCC фактически включён в текущей сессии. Persistence после полного cold boot и outdoor-проверка ещё не выполнены; результат не считается доказательством сохранения режима между перезагрузками |
| 2026-07-19, outdoor | Avata 360 + RC2, FreeFCC 1.5.14 | Ранний Auto-FCC → получение спутников → запись Home Point | n/a | reachable | OBSERVED пользователем: FCC, включённый до Home Point, в момент записи Home Point фактически сбросился в CE; автозапущенное приложение не восстановило его, а ручной полный `Re-Apply` после Home Point снова включил FCC |
| 2026-07-19 14:15 MSK, indoor | Avata 360 + RC2 `rc331`, FreeFCC 1.5.14 | Повторный запуск FreeFCC при persistent Auto-FCC + keepalive | n/a | reachable | OBSERVED через LAN: восстановился keepalive, а Auto-FCC завершился `skipped — another hardware operation is already running`; status одновременно показывал `fcc_sequence_written=true`, хотя physical `fcc_enabled=null`. Это отдельная startup race, способная пропустить полный apply |
| 2026-07-19 14:16 MSK, indoor | Avata 360 + RC2 `rc331`, FreeFCC 1.5.14 | Passive `40009`, direct read-only `03:44` и `06:21` | n/a | reachable | NEGATIVE в проверенном route: capture дал только `06:AE` и `06:77`; оба запроса получили посторонний `06:AE`, но не matching response. Home Point в помещении отсутствовал; нужны wrapper/другой route и отдельный transition capture |
| 2026-07-19, indoor restart + US spoof | Avata 360 + RC2 `rc331`, FreeFCC 1.5.14 | Aircraft restart, GPS acquisition и повторная запись Home Point | Найден свежий полный `1581...` через `51:14` (в public docs редактирован) | reachable | OBSERVED: wrapped `40007` stream дал точный переход `03:44 home_state 0x0046 → 0x0047`; параллельно `03:43` показал satellites `0→12→15→18→19`, GPS used `0→1`, level `0→2→4→5`. Пользователь подтвердил итоговый FCC для US Home Point |
| 2026-07-19, indoor transport stress | Avata 360 + RC2 `rc331`, FreeFCC 1.5.14 | Повторяющиеся новые `40007 wire_exchange` connections | n/a | reachable | OBSERVED пользователем: и цикл без паузы, и повторная проверка с максимум 1 Hz рвали aircraft/controller link; после остановки связь восстанавливалась. Continuous polling запрещён, разрешены только одиночные bench captures до нового transport design |
| 2026-07-19, indoor LED readback | Avata 360 + RC2 `rc331`, FreeFCC 1.5.14 | Visual LED ON: `03:F8` x3; LAN `LED OFF`; post-read; manual UI OFF; ещё один post-read | n/a | reachable | NEGATIVE/OBSERVED: matching `00a259ceedXX` не пришёл. LAN OFF сообщил write success, но физически не сработал; следующий tap кнопки в приложении физически выключил LED. Значит `anySuccess` даёт ложноположительный transport result, а readback пока невоспроизводим |
| 2026-07-21 21:33 MSK | Avata 360 + RC2 `rc331`, FreeFCC 1.5.38 | Fresh passive `40007` capture, 33 valid frames | Полный `1581...` S/N получен в `51:14`; UI cache ещё показывал `WA530` | reachable | OBSERVED: aircraft link активен при внутреннем UI status `Disconnected`; activation frames ещё не отправлялись. Для 1.5.39 удалены model allowlist и зависимость 4G flow от FCC connection flag |
| 2026-07-19 21:46–21:52 MSK, indoor | Avata 360 + RC2 `rc331`, FreeFCC 1.5.16 | Re-entry при `auto_fcc=false`, stale `keepalive_running=true`, Home Point monitor и повторные `40007` reconnect | n/a | reachable | OBSERVED: aircraft/controller link обрывался каждые несколько секунд и стабилизировался после остановки monitor; UI имел write evidence, но физически FCC не был включён. Hotfix 1.5.17 запрещает marker-only recovery, требует fresh Home Point edge и останавливается fail-closed после established-stream disconnect |
| 2026-07-19 22:38–22:52 MSK, indoor | Avata 360 + RC2 `rc331`, FreeFCC 1.5.17 | Auto-FCC OFF re-entry, затем fresh Home Point и manual FCC recovery | n/a | reachable | OBSERVED: четыре re-entry не resurrect monitor и не создали reconnect loop. При fresh Home Point единственный stream disconnect завершил monitor до FCC (`fcc_sequence_written=false`). Ручной полный профиль после Home Point завершил 42/42 writes и физически включил FCC. В 1.5.18 добавлен ровно один armed-session reconnect без periodic loop |
| 2026-07-21 | Static RE, DJI Fly 1.21.4 (`3115357`) | Точная установленная версия; APK v2 signature valid, `CN=dji` | n/a | route не активировался | OBSERVED: штатный eSIM flow — stateful `0x18:4b` request/push + `0x18:4c` ACK; executable `.text` подтверждён exact hash. DERIVED: текущий FreeFCC `0x51:00..7f` burst — другой experimental protocol и не имеет штатного readback |
| 2026-07-21 23:16–23:18 MSK | Avata 360 + RC2 `rc331`, passive `8902` | Один socket: aircraft off → link → GNSS → подтверждённый Home Point | n/a | 4G не трогался | OBSERVED: первые satellites появились за 18.444 s, `gps_used` за 14.547 s, GPS level 5 за 10.132 s до пользовательской отметки Home Point. NEGATIVE: 42 reference `03:44` signatures и persistent bit search не нашли Home Point mirror в `8902`; GNSS readiness не используется как Auto FCC trigger |
