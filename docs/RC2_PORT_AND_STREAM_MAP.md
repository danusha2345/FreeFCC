# Карта портов и потоков DJI RC2

Этот документ фиксирует результаты живой проверки DJI RC2 `rc331` и отделяет
наблюдаемые факты от интерпретаций. Проверка не ограничена 4G: цель — найти
доступные сетевые, DJI/DUML, diagnostic и системные каналы пульта.

## Статус доказательств

- **OBSERVED** — непосредственно получено сканированием, capture или ответом
  сервиса.
- **DERIVED** — однозначно следует из наблюдаемой структуры данных.
- **HYPOTHESIS** — рабочее объяснение, которое ещё требует проверки.
- **NEGATIVE** — искомое поведение или сигнатура отсутствует в явно указанном
  проверенном corpus; это не утверждение обо всех версиях и устройствах.

Ни один неизвестный порт в этой сессии не получал DJI-команд. Ранняя
TCP-проверка ограничивалась соединением без application payload; после
идентификации `5744` как GNSS debug/command socket даже пустое подключение к
нему считается потенциально влияющим и больше не допускается. `8902` читался
пассивно: клиент ничего не отправлял, а endpoint сам публиковал поток.

## Стенд

| Поле | Значение |
|---|---|
| Дата | 2026-07-21 |
| Пульт | DJI RC2, код `rc331` |
| DJI Fly | 1.21.4 |
| Aircraft | Avata 360, код `WA530` |
| SkylabFCCfree | 1.5.39 во время внешней проверки |
| Сеть | Wi-Fi LAN, адрес пульта `192.168.1.139` в этой сессии |

Адрес выдаётся DHCP и не является постоянным. Factory S/N намеренно не
публикуется в документации.

## Внешние TCP-порты RC2

Выполнен полный connect scan TCP `1..65535` через Wi-Fi с низкой
параллельностью и без отправки payload.

| Порт | Наблюдение | Вывод |
|---:|---|---|
| `53/tcp` | **OBSERVED:** DNS `version.bind TXT CHAOS` получил `REFUSED`, flags `0x8185` | DNS-сервис доступен, но версия скрыта; назначение внутри RC2 пока не установлено |
| `5037/tcp` | **OBSERVED:** ADB smart-socket запросы `host:version`, `host:devices-l`, `host:features` вернули `FAIL001ano devices/emulators found` | **DERIVED:** endpoint понимает ADB framing; usable ADB shell на самом RC2 этим не доказан |
| `8787/tcp` | **OBSERVED:** HTTP API SkylabFCCfree | Это LAN bridge нашего приложения, не системный DJI-порт |
| `8902/tcp` | **OBSERVED:** сразу после connect выдаёт непрерывный бинарный поток без запроса | Пассивный multiplexed telemetry/diagnostic stream; уже найден GNSS/OSD layout |

Снаружи через Wi-Fi не открыты известные localhost-порты `40007`, `40009`,
`8901`, `8903`, `8904`. Это не доказывает, что их нет на loopback: приложение
уже работает с частью этих маршрутов через `127.0.0.1`.

### Порт 5037

Обычный клиент `adb`, направленный на `tcp:192.168.1.139:5037`, получает
`no devices/emulators found`. Это соответствует ADB server/proxy без
зарегистрированного target. Нельзя пока утверждать, что порт даёт удалённый ADB
доступ к Android самого пульта. Следующий безопасный шаг — сравнить ответ при
USB-подключении и при запуске официального USB launcher, не перебирая
аутентификацию и не меняя прошивку.

### Пассивный поток 8902

Шестисекундный capture сохранён локально как
`.scratch/rc2-port-map/8902-passive.bin` и не должен попадать в git.

| Метрика | Значение |
|---|---:|
| Размер | `1 341 440` байт |
| SHA-256 | `a22cb307b54bf1e4ad4d75b77aa1dd6c759439303af3739bf3113af39a777f8b` |
| Полных length-delimited записей | `18 153` |
| Кадров `F6 64` | `10 814` |
| Кадров `F5 64` | `6 865` |
| Кадров `F8 64` | `474` |
| Байтов внутри полных записей | `1 341 378` |
| Неполный prefix/suffix capture | `26 + 36` байт |

**DERIVED framing:** первые два байта записи — `F5 64`, `F6 64` либо `F8 64`,
следующие два байта — little-endian полная длина записи. Все
`18 152 / 18 152` соседних границ точно совпадают с
`next_offset = offset + length`. Прежние `24 473` якобы пропущенных байта были
валидными `F8` records, а не corruption.

Annotated 99-second capture уточнил header: offsets `4..7` — единый
`u32 little-endian` clock в миллисекундах. В исходном коротком capture его
high word случайно оставался равным `3`, поэтому bytes `6..7` выглядели
константой. В новом потоке clock пересёк границу high word `0→1`; на стабильной
F6 family `29.995 s` clock соответствуют `29.997098 s` PCAP wall time.

Самые частые полные длины: `82`, `46`, `64`, `232`, `32`, `28` байт. Частые
четырёхбайтовые значения на offsets `14..17`:

| Значение | Количество |
|---|---:|
| `80b44160` | `4 734` |
| `0b0e1b52` | `2 227` |
| `80b41e70` | `1 584` |
| `80b452c4` | `1 562` |
| `0b0e0753` | `988` |
| `0b0e0c53` | `583` |

Поток не является обычным DUML broker: среди `4 028` байтов `0x55` не найдено
ни одного полного кадра с валидными DJI CRC8+CRC16. Entropy `3.10..4.04`,
fixed layouts и zero padding также исключают объяснение «весь поток зашифрован
или сжат» в проверенном capture.

Первый семантический якорь уже найден. `78` записей `F5` длиной `71` байт
позиционно совпадают с ранее размеченным и CRC-valid DUML `03:43` GNSS/OSD:

| Поле | Offset в record `8902` | Результат этого capture |
|---|---:|---|
| GPS state word, `u32 LE` | `48` | `0x00805100` |
| satellites, `u8` | `52` | `0` во всех 78 samples |
| GPS state low nibble | `55` | `0` во всех 78 samples |
| structural marker | `61` | `0f8a207f` |

Таким образом, `8902` уже даёт полностью пассивный GNSS readiness path без
подключения к `40007` или `5744`. В этом коротком capture GPS ещё не был готов,
а эквивалент `03:44 home_state` не найден. Поэтому readiness нельзя выдавать
за факт записи Home Point.

Отдельное семейство `F6/80b452c4` содержит два массива `int16[50]` с `32` или
`36` активными bins и отрицательными значениями. Это сильный кандидат на
RF/CSI/spectrum diagnostics, но единицы и связь с CE/FCC остаются
**HYPOTHESIS** до размеченного A/B capture.

### Annotated переход GNSS → Home Point

Второй capture начат при включённом RC2 и выключенном aircraft, продолжен без
переподключения через включение aircraft и остановлен после подтверждённого
пользователем Home Point.

| Artifact | Размер | SHA-256 |
|---|---:|---|
| `8902-home-transition.bin` | `19 867 648` B | `2f317cf09b034c5a09dc7cdc57a3070e7cf5f6d4c01cabdba09a4778326366dc` |
| `8902-home-transition.pcap` | `20 257 422` B | `cdbdc821b0e6f988eca26cfd7d749ac3974cca725054bd233a38de8fc696382c` |

PCAP после de-overlap побайтно восстанавливает BIN: `2 557` server payload
segments, `3 224` B retransmission overlap, sequence gaps `0`. Framing даёт
`271 777` полных records одной точной цепочкой: F5=`84 604`, F6=`180 574`,
F8=`6 599`. У всех `6 598/6 598` соседних F8 sequence delta равна
`+1 mod 256`, поэтому потери F8 не обнаружены.

Относительно пользовательской отметки Home Point:

| Событие из `03:43` layout | Время до отметки Home Point |
|---|---:|
| Первые satellites `>0` | `18.444 s` |
| `gps_used=true` | `14.547 s` |
| GPS level `2` | `13.468 s` |
| GPS level `4` | `11.335 s` |
| Первый level `5`, satellites `13` | `10.132 s` |

Это прямо доказывает, что GNSS readiness и Home Point — разные события.
Количество спутников, `gps_used` или level `5` нельзя использовать как точный
триггер Auto FCC.

Поиск Home Point mirror дал отрицательный результат:

- `42` локальных CRC-valid `03:44` reference payloads (`0x0046/0x0047`) —
  `0` exact signatures в annotated `8902` capture;
- устойчивый byte/bit search по old pre-GNSS, run3 pre/post и run2 post-Home
  Point не дал ни одного persistent candidate;
- `F6/80b40760[67]`, ранее похожий на `0→1`, в настоящем переходе равен `0`
  до и после отметки;
- близкие переключения других полей оказались rollover счётчиков либо дали
  обратное значение в независимом post-Home-Point capture.

Следовательно, `8902` пригоден для безопасного пассивного GNSS readiness, но
не содержит доказанного Home Point trigger в проверенном формате. Для Auto FCC
точным источником остаётся текстовое событие оригинального DJI Fly.

## Известные внутренние маршруты приложения

| Endpoint | Подтверждённое назначение в проекте | Ограничения |
|---|---|---|
| `127.0.0.1:40007` | wrapped telemetry/control, GPS и LED read/write, aircraft identity | Частые новые соединения нарушали aircraft link; background polling запрещён |
| `127.0.0.1:40009` | direct DUML broker, FCC/CE и passive frames | Не каждый запрос получает matching response |
| `127.0.0.1:8901` | На RC Pro 2 наблюдались identity frames | Для текущего RC2 ещё нужен localhost inventory/capture |
| `127.0.0.1:8902..8904` | Альтернативные DJI endpoints | Старый отрицательный capture относился к RC Pro 2; внешний RC2 `8902` уже доказан как активный stream |
| abstract Unix `/duss/mb/0x205` | Endpoint принимает captured 4G-profile writes | Reachability и write completion не доказывают физическую 4G-активацию |

## Живая localhost-инвентаризация

После установки SkylabFCCfree 1.5.40 команда `local_socket_inventory` была один
раз запущена на RC2. Все пять `/proc/net` sources оказались доступны. TCP
connect-проход дошёл до порта `45771` и остановился по deadline `20 s`; payload
не отправлялся. Так как proc tables уже дают полный список listeners, начиная с
1.5.41 connect-проход полностью удалён и инвентаризация стала только пассивной.

### TCP listeners

| Порт | Bind | UID | Текущий вывод |
|---:|---|---:|---|
| `5037` | IPv6 wildcard | `0` | ADB-shaped system endpoint |
| `5744` | `127.0.0.1` | `1021` | **DERIVED:** Unicore uDriver GNSS debug/raw/command/firmware-upgrade socket; подключаться нельзя |
| `8787` | Wi-Fi IPv4 RC2 | `10025` | SkylabFCCfree LAN API |
| `8901` | `127.0.0.1` | `0` | DJI internal endpoint |
| `8902` | IPv4 wildcard | `0` | Подтверждённый внешний passive high-rate stream |
| `40007` | `127.0.0.1` | `0` | DJI wrapped telemetry/control proxy |
| `40008` | `127.0.0.1` | `0` | **OBSERVED:** ранее не учтённый DJI/root listener; протокол неизвестен |
| `40009` | `127.0.0.1` | `0` | DJI direct DUML broker |

Android AOSP определяет UID `1021` как
[`AID_GPS`, GPS daemon](https://android.googlesource.com/platform/system/core/+/master/libcutils/include/private/android_filesystem_config.h#72).
Независимый vendor anchor даёт [официальное руководство Unicore uDriver
R1.2.1](https://en.unicore.com/uploads/file/uDriver_User%20Manual_EN_R1.2.1.pdf):
оно описывает ровно `127.0.0.1:5744` как канал raw GNSS/debug interaction,
передачи команд модулю и firmware upgrade. Руководство также предупреждает,
что новый client сверх лимита заменяет `client0`. Поэтому даже connect без
payload может вытеснить штатного клиента. Окончательная привязка конкретного
RC2 inode к Unicore ELF ещё требует inode→PID, но использовать `5744` для
опроса, Home Point или «пассивного» чтения запрещено уже сейчас.

### UDP sockets

Найдены DHCP client `68/udp` на Wi-Fi, root loopback sockets `6698/udp` и
`8471/udp`, а также wildcard UDP6 socket приложения на динамическом порту.
Назначение `6698` и `8471` пока неизвестно; пакеты не отправлялись.

### Значимые Unix sockets

| Имя | Что доказано |
|---|---|
| `/dev/dji_lte_v1`, `/dev/wl_lte_v1` | **OBSERVED:** LTE-named endpoints присутствуют на RC2 даже без доказанной активной 4G-сессии |
| `/dev/lte_liveview`, `/dev/lte_liveview-1173` | **OBSERVED:** два LTE liveview endpoint |
| `/dev/lte_liveview_v2`, `/dev/lte_liveview_v2-1173` | **OBSERVED:** два LTE liveview v2 endpoint |
| `@/duss/mb/0x0` | **OBSERVED:** DUSS endpoint; назначение не классифицировано |
| `@/duss/mb/0x1d03` | **OBSERVED:** DUSS endpoint; назначение не классифицировано |
| `@/duss/mb/0x1f06` | **OBSERVED:** DUSS endpoint; назначение не классифицировано |
| `@/duss/mb/0x205` | Endpoint, используемый текущим experimental 4G profile |
| `@/duss/mb/0xd00` | **OBSERVED:** DUSS endpoint; назначение не классифицировано |
| `@/duss/mb/0xe04`, `0xe06`, `0xe07` | **OBSERVED:** DUSS endpoints; назначение не классифицировано |
| `@/duss/wlm_fmsg_forward` | **OBSERVED:** DUSS/WLM forward endpoint; формат сообщений неизвестен |
| `/dev/socket/dji_fpv`, `fpv_sock*` | **OBSERVED:** DJI/FPV-named local endpoints; в snapshot они не listening и не connected |

Наличие LTE-named sockets доказывает загруженные локальные компоненты/IPC
маршруты, но само по себе не доказывает SIM registration, data session или
совместимость 128-frame профиля с `WA530`.

Все LTE/DUSS entries имеют `type=0002` (`SOCK_DGRAM`), `state=01`
(`SS_UNCONNECTED`) и flags `0`. Для bound datagram receiver это штатно и не
означает ошибку. `/dev/...` здесь pathname Unix sockets, а `@/...` — abstract
namespace; ни то ни другое не является доказательством активного модема.

Статический поиск по точному официальному DJI Fly `1.21.4` не нашёл hardcoded
LTE/DUSS paths. Значит имена создаются системными controller services. При этом
Fly содержит eSIM SDK-команды `cmd_set=0x18`, `cmd_id=0x4b/0x4c`: приложение
управляет eSIM через DUSS command layer, а не прямым открытием перечисленных
pathname sockets.

### Поведение команды

Начиная с 1.5.41 команда:

- запускается только вручную;
- вообще не подключается к найденным TCP-сервисам;
- читает доступные `/proc/net/tcp*`, `/proc/net/udp*`, `/proc/net/unix`;
- блокируется во время Auto FCC и не работает в фоне;
- возвращает TCP listeners, UDP sockets, Unix type/state/inode/name,
  доступные proc sources, ошибки и признак полноты snapshot.

Пример запуска приведён в [LAN Control API](LAN_CONTROL_API.md#localhost-socket-inventory).

### Проверка SkylabFCCfree 1.5.41

После установки 1.5.41 повторный snapshot на том же RC2 подтвердил безопасную
реализацию и устойчивость карты:

| Поле | Результат |
|---|---|
| `inventory_method` | `proc_net_passive` |
| `probe_attempted` | `false` |
| `scanned_ports` | `0` |
| `probe_payload_bytes` | `0` |
| `inventory_complete` | `true` |
| `errors` | `[]` |
| `duration_ms` | `143` |

TCP listeners совпали с первым snapshot: `5037`, `5744`, `8787`, `8901`,
`8902`, `40007`, `40008`, `40009`. Изменился только динамический UDP6-порт
SkylabFCCfree, что ожидаемо после переустановки/перезапуска процесса.

Структурированные Unix metadata уточнили transport type:

- LTE, LTE liveview и все `@/duss/*` endpoints имеют `type=0002`
  (`SOCK_DGRAM`);
- `/dev/socket/dji_fpv` и `fpv_sock*` имеют `type=0001` (`SOCK_STREAM`);
- `/dev/socket/adbd` имеет `type=0005` (`SOCK_SEQPACKET`).

Для datagram endpoints обычное подключение нового клиента не является
пассивным перехватом уже существующего обмена: сообщения адресуются владельцу
конкретного socket. Поэтому следующим безопасным объектом остаётся внешний
publish stream `8902`, который сам отдаёт данные после TCP connect без запроса.
К DUSS/LTE sockets нельзя подключать параллельный listener и называть его
наблюдением чужого трафика без отдельного privileged capture или firmware
evidence.

## Выполненная матрица Home Point capture

Проверка Home Point выполнена одним непрерывным `8902` connection:

1. Пульт включён, aircraft выключен.
2. Не разрывая capture, включить aircraft и дождаться link.
3. Не разрывая capture, дождаться голосового/UI события Home Point.
4. Сохранить raw bytes вместе с PCAP timestamps и внешней отметкой моментов
   link/Home Point.

Во время capture Auto/Manual FCC не нажимались. Несмотря на чистый размеченный
переход, Home Point mirror в `8902` не найден. Для `40007` нельзя делать
периодический reconnect или polling: аппаратный тест уже показал, что это может
рвать связь. К `5744` нельзя подключаться вообще. Любые state-changing команды
должны запускаться отдельно и явно.

## Текущий итог

- **DERIVED:** RC2 публикует на `8902` multiplexed telemetry/diagnostic stream;
  framing полностью восстановлен, а GNSS/OSD `03:43` layout уже
  классифицирован.
- **OBSERVED:** `5037` понимает ADB smart-socket protocol, но сейчас не даёт
  target для shell.
- **OBSERVED:** внешняя и внутренняя карты портов различаются.
- **OBSERVED:** отдельный root listener `40008` существует, но ещё не
  классифицирован.
- **DERIVED:** `5744` — опасный GNSS debug/raw/command/upgrade path; даже пустой
  connect может вытеснить штатного клиента. Для наблюдения он запрещён.
- **OBSERVED:** локальные LTE и LTE-liveview sockets существуют независимо от
  доказательства активной cellular session.
- **NEGATIVE:** даже annotated aircraft-off → GNSS → Home Point capture не дал
  доказанного Home Point bit/family. GNSS readiness нельзя подменять Home Point.
- Все выводы этой карты относятся к RC2 `rc331`. RC Pro 2 и другой пульт нужно
  картировать отдельным corpus, даже если номера некоторых listeners совпадают.
