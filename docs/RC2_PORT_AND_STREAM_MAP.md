# Карта портов и потоков DJI RC2

Этот документ фиксирует результаты живой проверки DJI RC2 `rc331` и отделяет
наблюдаемые факты от интерпретаций. Проверка не ограничена 4G: цель — найти
доступные сетевые, DJI/DUML, diagnostic и системные каналы пульта.

## Статус доказательств

- **OBSERVED** — непосредственно получено сканированием, capture или ответом
  сервиса.
- **DERIVED** — однозначно следует из наблюдаемой структуры данных.
- **HYPOTHESIS** — рабочее объяснение, которое ещё требует проверки.

Ни один неизвестный порт в этой сессии не получал DJI-команд. TCP-проверка
ограничивалась соединением без application payload; `8902` читался пассивно.

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
| `8902/tcp` | **OBSERVED:** сразу после connect выдаёт непрерывный бинарный поток без запроса | Пассивный publish stream; семантика сообщений ещё неизвестна |

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
| Валидных length-delimited записей | `17 679` |
| Кадров `F6 64` | `10 814` |
| Кадров `F5 64` | `6 865` |
| Байтов внутри распознанных записей | `1 316 964` |
| Пропущено до синхронизации/между записями | `24 473` байта |

**DERIVED framing:** первые два байта записи — `F5 64` либо `F6 64`, следующие
два байта — little-endian полная длина записи. Позиция следующего marker для
распознанных записей точно совпадает с `offset + length`.

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

В capture нет читаемых строк `WA530`, `4G`, `LTE`, `cell`, `eSIM`, `GPS` или
`home`. Поэтому привязывать поток только к модему, GNSS либо Home Point пока
нельзя. Следующий этап классификации — bounded capture в нескольких состояниях
пульта/дрона и дифференциальное сравнение частот и изменяющихся полей.

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
| `5744` | `127.0.0.1` | `1021` | **DERIVED:** принадлежит Android GPS daemon UID; wire protocol не определён |
| `8787` | Wi-Fi IPv4 RC2 | `10025` | SkylabFCCfree LAN API |
| `8901` | `127.0.0.1` | `0` | DJI internal endpoint |
| `8902` | IPv4 wildcard | `0` | Подтверждённый внешний passive high-rate stream |
| `40007` | `127.0.0.1` | `0` | DJI wrapped telemetry/control proxy |
| `40008` | `127.0.0.1` | `0` | **OBSERVED:** ранее не учтённый DJI/root listener; протокол неизвестен |
| `40009` | `127.0.0.1` | `0` | DJI direct DUML broker |

Android AOSP определяет UID `1021` как
[`AID_GPS`, GPS daemon](https://android.googlesource.com/platform/system/core/+/master/libcutils/include/private/android_filesystem_config.h#72).
Поэтому связь `5744` с GNSS-подсистемой основана на владельце socket, но
назначение самого TCP-протокола ещё не доказано.

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
| `/dev/socket/dji_fpv`, `fpv_sock*` | **OBSERVED:** DJI/FPV-named local endpoints |

Наличие LTE-named sockets доказывает загруженные локальные компоненты/IPC
маршруты, но само по себе не доказывает SIM registration, data session или
совместимость 128-frame профиля с `WA530`.

### Поведение команды

Начиная с 1.5.41 команда:

- запускается только вручную;
- вообще не подключается к найденным TCP-сервисам;
- читает доступные `/proc/net/tcp*`, `/proc/net/udp*`, `/proc/net/unix`;
- блокируется во время Auto FCC и не работает в фоне;
- возвращает TCP listeners, UDP sockets, Unix type/state/inode/name,
  доступные proc sources, ошибки и признак полноты snapshot.

Пример запуска приведён в [LAN Control API](LAN_CONTROL_API.md#localhost-socket-inventory).

## Матрица следующих captures

Чтобы определить назначение неизвестных потоков без влияния на DJI transport,
нужны одиночные captures с одинаковой длительностью:

1. Пульт включён, aircraft выключен.
2. Aircraft включён и связан, Home Point ещё не записан.
3. Home Point записан, Auto FCC выключен.
4. После ручной отправки FCC.
5. С подключённым модемом и без него, если конкретная модель поддерживает 4G.

Для `8902` на каждом шаге достаточно пассивного чтения. Для `40007` нельзя
делать периодический reconnect или polling: аппаратный тест уже показал, что
это может рвать связь и совпадало с ошибкой GNSS. Любые state-changing команды
должны запускаться отдельно и явно.

## Текущий итог

- **OBSERVED:** RC2 публикует наружу неизвестный high-rate stream на `8902`.
- **OBSERVED:** `5037` понимает ADB smart-socket protocol, но сейчас не даёт
  target для shell.
- **OBSERVED:** внешняя и внутренняя карты портов различаются.
- **OBSERVED:** отдельный root listener `40008` существует, но ещё не
  классифицирован.
- **DERIVED:** loopback listener `5744` принадлежит GPS daemon по Android UID
  `1021`; это возможный read-only GNSS observation path, но протокол неизвестен.
- **OBSERVED:** локальные LTE и LTE-liveview sockets существуют независимо от
  доказательства активной cellular session.
- **HYPOTHESIS:** `8902` может быть внутренней телеметрией, IPC bridge или
  multiplexed event bus; данных для более узкого названия пока нет.
- 4G, GNSS, Home Point и общий control transport необходимо классифицировать по
  отдельным событиям, а не приписывать всему найденному потоку одну функцию.
