# Аудит DUML-команд FreeFCC

Дата среза: 2026-07-23; обновлено 2026-07-24. Базовый commit:
`4cd92277e193ebe328c47cd7a81dd46d21a4726c`.

Этот документ отвечает на два вопроса:

1. какие DUML-команды FreeFCC действительно отправляет;
2. что именно про них доказано, а что осталось исторической гипотезой.

Байты и порядок профилей при этом аудите не менялись. Исправлены только
описательные поля JSON, чтобы приложение не выдавало предположение за
установленную семантику. Статический справочник по командам из бинарников
пульта вынесен отдельно:
[`RM510_DUML_COMMAND_REFERENCE.md`](RM510_DUML_COMMAND_REFERENCE.md).

## Уровни доказательности

| Уровень | Значение |
|---|---|
| `CONFIRMED` | Семантика подтверждается кодом/символами firmware и совпадающим wire format либо воспроизводимым readback |
| `OBSERVED` | Кадр или изменение состояния непосредственно наблюдались на живом устройстве |
| `INFERRED` | Назначение следует из контекста, имени функции или соседнего кода, но payload полностью не декодирован |
| `UNKNOWN` | Известны только `cmd_set`, `cmd_id`, маршрут и payload |

`write()`/`flush()` в локальный proxy или DUSS socket доказывает только передачу
байтов локальному сервису. Это не ACK от дрона и не доказательство изменения
RF-региона, GPS, LED или 4G-состояния.

## Зафиксированные исходные профили

Хэши относятся к файлам до исправления их `description`/`note`; все поля,
влияющие на отправку, сохранены.

| Профиль | SHA-256 исходного JSON |
|---|---|
| `4g.json` | `0800105154e14570d76554cc32280f477325918a2ffc2bd9308e8f64fd078d13` |
| `ce_restore.json` | `65383052b58c81ff0f245133665deeb2f7c7a09ca70a7f5d861054499a2cd428` |
| `device_info.json` | `f074547407527f2e861a147129b05eddbca48619b29a286f2368f3ef7a66827e` |
| `fcc.json` | `e6df4a963256b97424c90378f18f171f2c1c53d2405e54cf0f981809fdd69c15` |
| `fcc_keepalive.json` | `3da1b0db6bbc0921e7cb66e1d76a6cc33ae3f1fd889842b043c3d393afdbda38` |
| `led_off.json` | `80e4aa626cc42233611d2af33c7a6377912c2badc0213e2f34bc2be1f77c7ca7` |
| `led_on.json` | `5287d635d721294a88bdb70acf16b79d4fb049a7285d89e2a281ba6fc358e130` |

## Исторический FCC-профиль и текущий country flow

Таблица ниже фиксирует исходный upstream-профиль из 21 кадра, который
отправлялся в два раунда. На проверенных комбинациях дрона и пульта этот
составной профиль приводил к FCC-результату, но это не доказывает необходимость
каждого кадра и универсальность последовательности.

С 2026-07-24 country setup отделён от повторяемого core:

1. одна `07:30=AU` к `dst=0x09`;
2. один read-only `07:19` к тому же destination;
3. оставшиеся 17 кадров `fcc.json` в два раунда, то есть 34 core writes.

Текущий `fcc.json` имеет SHA-256
`a690302ebb14ae115539be774dc956b1d9de9d607ec20beb677c3d55e61205bf`.
`07:30`, её точный дубликат, `07:18` и прежний слепой `07:19` из core удалены.
Один общий кодовый flow используется ручным, Home Point и periodic bootstrap.

С 1.5.48 порядок внутри country flow изменён на read-first: сначала
выполняется read-only `07:19`. Если он вернул `AU`, `07:30` не отправляется
вообще. При любом другом ответе (включая отсутствие ответа) выполняется
`07:30=AU` и повторный `07:19`; пара повторяется до трёх раз, пока readback не
вернёт `AU`. Неподтверждённый результат логируется как `verified=false,
writes=3` и не блокирует остальную последовательность.

Периодический режим с 1.5.48 состоит из одного `07:19` каждые 5 секунд.
Совпадающий регион не порождает записей; расхождение вызывает country write и
повторное применение полного `fcc.json`. Четыре legacy-кадра
`fcc_keepalive.json` рантаймом больше не отправляются.

Live `rc331`/WA530 на port `8901`: исходный `07:19` вернул
`00 52 55 00` (`RU`), одна `07:30=AU` получила ACK `00 01`, следующий
`07:19` вернул `00 41 55 00` (`AU`). Два отдельных теста `07:18=RU`
(`sender/cmdType` `2A/40` и точный старый `82/20`) не получили matching ACK;
readback после каждого остался `AU`.

На отдельном `rc520` исходный `07:19` вернул `RU`. Точная старая
`07:18=AU` (`sender=82`, `cmdType=20`, `dst=07`) не получила matching ACK ни
на `8901`, ни на основном FCC-port `40009`; оба readback после тестов остались
`RU`. Таким образом, изменение country state через `07:18` имеет отрицательный
live-результат на проверенных `rc331` и `rc520`, но этот вывод не переносится
на остальные DJI-платформы без отдельного A/B. В частности, для заявленного в
compatibility table RC Plus/RM700 обе операции — действие `07:18` и успешный
`07:30=AU` с последующим `07:19=AU` — остаются `UNTESTED` и требуют проверки
владельцем реального пульта.

| № | Команда | Payload | Что известно | Уровень |
|---:|---|---|---|---|
| 1, 21 | `10:58` | `030100` | Получатель `dst=0x12` — `bvision:0` / `perception_service`. Одинаковый кадр стоит в начале и конце, поэтому старые противоположные подписи «вход/выход service mode» не подтверждаются. Точный handler не восстановлен | route `CONFIRMED`; semantics `UNKNOWN` |
| 2 | `06:72` | `00000000000100` | Получатель `dst=0x06` — `rc:0`; RM510 пересылает кадр по UART `/dev/ttyHS2` во внешний RC MCU. Точный handler и значение не декодированы | route `CONFIRMED`; semantics `UNKNOWN` |
| 3 | `03:F9` | `8a237103f401` | Hash `0x0371238a`, значение LE `0x01f4` = 500: запись `max_height`; это побочный flight-limit write, а не FCC primitive | `CONFIRMED` |
| 4 | `00:00` | `000001` | Стандартный device ping к `dst=0x1f` (`all:0`, broadcast). В восстановленных DJI handlers ping отвечает копией request payload; `00 00 01` — трёхбайтный echo token, а не activation/FCC-write | family/route `CONFIRMED`; WM260 live ACK не снимался |
| 5 | `00:32` | `3131000000` | В WM260 `dst=0x6F` — `dji_sec/sec_service` (`s_to_p_air:3`). Aircraft handler принимает `0x31` и возвращает 59-байтное состояние активации; четыре последующих request bytes в этой ветке не используются. Это query, не FCC-write | `CONFIRMED` для WM260 |
| 6 | `03:AF` | `032400000000000000` | Публичное имя семейства — `GetAreaCode`. `dst=0x03` — `flight:0`; WM260 `dji.json` пересылает кадр по ICC в flight-controller MCU. Linux `dji_sys` является router, а не receiver; firmware MCU в корпусе нет | route `CONFIRMED`; family `INFERRED`; semantics `UNKNOWN` |
| 7, 8 | `07:30` | `41550000415500000100` | RM510 handler читает только первые два байта `AU`, пишет vendor country slot `6` и `country.bin`; хвост игнорирует. Два кадра полностью одинаковы, поэтому band-specific смысл не подтверждён | `CONFIRMED` для локального handler contract |
| 9 | `09:27` | `00024800ffff0200000000` | SDR assistant write: address `0xffff0048`, value `2`; публичный DJI-код называет эту операцию `setForceFcc` | `CONFIRMED` |
| 10 | `09:27` | `00026300ffff0300000000` | SDR assistant write: address `0xffff0063`, value `3`; это register write, но точный эффект value `3` не установлен | `CONFIRMED` для register/value, эффект `UNKNOWN` |
| 11 | `07:18` | `ff415500` | Запись country/area с `AU`; prefix `ff` и route зависят от реализации | `CONFIRMED` для family |
| 12 | `07:19` | `c0` | RM510 handler не читает request payload, а возвращает 2-byte alpha-2 country из vendor slot `6`; `c0` в этой реализации игнорируется | `CONFIRMED` для локального handler contract |
| 13, 14 | `03:F9` | `d04aeffb01/00` | Hash `0xfbef4ad0` = `c1_regulatory_restriction`; запись `1`, затем сразу `0` в `bvision:4/perception_agent`. Имя находится в FocusTrack-контексте WM260; точный эффект импульса `1→0` не декодирован | name/hash/value/route `CONFIRMED`; effect `UNKNOWN` |
| 15 | `00:E5` | `323201` | В WM260 получатель `dst=0x6F` — `dji_sec/sec_service`. DJI Care dispatcher принимает только subtype `01/02/04/09/0A/FF`; `0x32` попадает в default-ветку и возвращает error `0xE3`, не запуская действие | handler/rejection `CONFIRMED` для WM260 |
| 16 | `03:F9` | `236b820101` | Hash `0x01826b23` = `sdr_lost_prevent_never_takeoff_en`; boolean write `1` в `flight:0`. В таблицах Air 3S/Mavic 4 default уже равен `1` | `CONFIRMED` |
| 17 | `03:F9` | `8773e68a01` | Hash `0x8ae67387` = `sdr_lost_prevent_has_takeoff_en`; boolean write `1` в `flight:0`. В таблицах Air 3S/Mavic 4 default уже равен `1` | `CONFIRMED` |
| 18, 19 | `06:8C` | `000300`, `000100` | Получатель `dst=0x09` — `vt_air:0`, то есть air-side transmission MCU. Linux/userspace handler в доступном WM260 образе отсутствует; точный параметр не декодирован | route `CONFIRMED`; semantics `UNKNOWN` |
| 20 | `06:72` | `000000000001ff` | Тот же маршрут к `rc:0` через `/dev/ttyHS2`; старая подпись «commit» не подтверждена | route `CONFIRMED`; semantics `UNKNOWN` |

Главный непосредственно распознанный FCC-write здесь — первый `09:27`
`setForceFcc`. В текущей реализации country state меняет отдельная одиночная
`07:30`, а `07:19` подтверждает результат перед core.
`00:00 / 00 00 01` оказался обычным broadcast ping и не меняет настройки.
`00:E5 / 32 32 01` на WM260 отвергается без действия. Два
`sdr_lost_prevent_*` оказались штатными flight-safety flags, а
`c1_regulatory_restriction` относится к perception/FocusTrack. Семантика
`10:58`, `03:AF`, `06:72` и `06:8C` всё ещё не доказана. Полный RF-эффект
составного профиля необходимо проверять графиком Transmission или независимым
RF/readback evidence.

### Как восстановлены маршруты и PM-хэши

DUML destination byte кодируется как
`(index << 5) | (module_type & 0x1f)`. Таблицы маршрутов RM510 и WM260 дают
следующее сопоставление:

| Кадры | Destination | Получатель | Где находится handler |
|---|---:|---|---|
| `00:00` | `0x1f` | `all:0` | Специальная broadcast-цель; стандартный ping handler есть у принимающих DJI system services |
| `03:AF` | `0x03` | `flight:0` | Flight-controller MCU за WM260 ICC `/dev/icc_dev`, channel `ap0-mcu0-1.0` / `mcu0-ap0-1.0` |
| `06:72` | `0x06` | `rc:0` | Внешний RC MCU за `/dev/ttyHS2`, protocol `v1`; не Android `dji_link` |
| `06:8C` | `0x09` | `vt_air:0` | Air-side transmission MCU; не `dji_wlm` (`vt_air:7`) и не `dji_sdrs_agent` (`vt_air:4`) |
| `10:58` | `0x12` | `bvision:0` | WM260 и WA530: локальный `dji_perception` / `perception_service` |
| `03:F9 d04aeffb…` | `0x92` | `bvision:4` | WM260 `dji_perception` / `perception_agent` |
| остальные `03:F9` | `0x03` | `flight:0` | Flight controller parameter manager |

В `dji_link_event_start` локальные RM510 handler tables создаются для cmdsets
`00`, `07` и `18`; cmdset `06` туда не входит. Поэтому issue-гипотеза
«`06:72` — RC stick lock» остаётся правдоподобной, но не подтверждённой
имеющимся userspace: для доказательства нужен firmware RC MCU либо live
request/ACK capture с контролируемым состоянием стиков.

Хэши `03:F9` проверены тем же алгоритмом, который восстановлен из
`compute_hash_value_by_name` в DJI firmware:

```text
hash(name) = fold(name + "_0", h = ((h << 8) | byte) mod 0xfffffffb)
```

Все три совпадения точные:

| Hash | Имя |
|---:|---|
| `0xfbef4ad0` | `c1_regulatory_restriction` |
| `0x01826b23` | `sdr_lost_prevent_never_takeoff_en` |
| `0x8ae67387` | `sdr_lost_prevent_has_takeoff_en` |

В WM260 `dji_perception` параметр `c1_regulatory_restriction` расположен по
`0x10eb861`, а его descriptor — по `0x1338d40`. Это единственный элемент
группы `FocusTrackParams`; рядом находятся `FocusTrack init/exit`,
`stopToSpotlight`, `stopToMot`, yaw-control и obstacle-avoidance callbacks.
Контекст FocusTrack поэтому подтверждён сильнее, чем только совпадением hash,
но descriptor не раскрывает внешнее действие короткого импульса `1→0`.

Для второго `09:27` literal register address `0xffff0063` не найден в
проверенных RM510 `dji_sdrs_agent`, `dji_wlm`, `dji_link` и WM260
`dji_sys`/`dji_perception`. Это согласуется с маршрутом в `vt_air:0`: handler
находится в отсутствующей firmware transmission MCU. Историческую подпись
«5.8 GHz power limit» текущий локальный корпус не подтверждает.

### Закрытые и оставшиеся opaque-команды

| Команда | Текущая граница доказательства | Что нужно для полного закрытия |
|---|---|---|
| `00:00 / 000001` | `CLOSED`: broadcast device ping; payload используется как echo token | Ничего для классификации; live ACK нужен только для проверки конкретной модели |
| `03:AF / 0324…` | Route до `flight:0` доказан; `GetAreaCode` остаётся client-side family name | Firmware flight-controller MCU с handler table либо контролируемый request/ACK |
| `09:27 / ffff0063=3` | Register/value и route до `vt_air:0` доказаны | Firmware air transmission MCU |
| `06:72` | Route до RC MCU доказан | Firmware RC MCU |
| `06:8C` | Route до air transmission MCU доказан | Firmware air transmission MCU |
| `10:58 / 030100` | Receiver `bvision:0/perception_service` присутствует в WM260 и WA530, но registration не локализована; WA530 `gesture_control_*` — параметры, не DUML handler | Дальнейший разбор callback path либо соответствующий request/ACK |

Для `00:00` использован WA341 `dji_sys` (Build ID
`ed5cc566fae4ef008a19727014ca2c00`, SHA-256
`beb09425c85e5725aef7a29971dfc18b2d7e93102f3ad2a706dce1be5c958234`):
slot `cmd_set=0/cmd_id=0` указывает на `sys_event_dev_ping` (`0xbe650`), а
ветка ответа по `0xbe6e8–0xbe724` передаёт в `duss_event_resp_data_v2/v3`
request data и request length. Это cross-generation handler evidence, а не
заявление о снятом ACK именно на WM260.

Для `03:AF` route evidence — WM260 `dji.json`, SHA-256
`849f60e02f4eb07c3cd1a1ecece4eb167deca82ab217bc48fb28dac9930b4789`:
`flight:0` имеет raw host `0x30` и физический ICC-route через
`ap0-mcu0-1.0` / `mcu0-ap0-1.0`. DUML raw destination `0x03` декодируется в
тот же `flight:0`; имеющийся Linux `dji_sys` только пересылает пакет.

## Пятисекундный periodic-профиль

`fcc_keepalive.json` повторяет четыре кадра:

| Команда | Payload | Статус |
|---|---|---|
| `10:58` | `030100` | Route к `bvision:0/perception_service` подтверждён; handler semantics `UNKNOWN` |
| `06:72` | `00000000000100` | Route к RC MCU подтверждён; handler semantics `UNKNOWN` |
| `06:72` | `000000000001ff` | Route к RC MCU подтверждён; handler semantics `UNKNOWN` |
| `10:58` | `030100` | Тот же opaque request |

При периоде 5 секунд это 48 DUML requests в минуту и 2880 в час. CPU и трафик
невелики, но цикл постоянно будит процесс и transport; половина кадров при этом
дублирует один и тот же `10:58`, а два `06:72` обращаются к RC MCU с пока
недоказанной необходимостью. Поэтому periodic mode оставлен отдельным явно
выбираемым режимом; Home Point mode вместо этого пассивно слушает telemetry и
по событию выполняет одну country write/readback и полный core.

## Остальные команды приложения

| Функция | Команды/transport | Реальное поведение | Уровень |
|---|---|---|---|
| Device info | `00:01` | Version inquiry / GetVersion | `CONFIRMED` |
| LED read | wrapped `03:F8`, hash `a259ceed` | Читает `g_config.misc_cfg.forearm_lamp_ctrl`, response содержит status, echoed hash и byte value | `CONFIRMED` |
| LED write | wrapped `03:F9`, `a259ceed00/ef` | Запрашивает OFF/ON. Один tap: 10 writes; при отсутствии подходящего readback ещё один цикл, максимум 20 | `CONFIRMED` для write/readback protocol; физический результат требует readback/визуальной проверки |
| GPS metadata | wrapped `03:F7`, hash `829542c5` | Только diagnostic probe параметра `g_config.gps_cfg.gps_enable`; production toggle его не использует | `OBSERVED` |
| GPS read | wrapped `03:F8`, hash `829542c5` | Текущее byte value `0/1` | `CONFIRMED` |
| GPS write | wrapped `03:F9`, `829542c500/01` | Пять bounded writes, затем новый `03:F8` read | `CONFIRMED` для protocol; physical persistence отдельно не доказана |
| Home Point | passive wrapped push `03:44` | Долгоживущее read-only соединение; `home_state` bit 0 сигнализирует запись Home Point | `CONFIRMED` на проверенном RC2/Avata layout |
| 4G legacy sweep | abstract Unix `/duss/mb/0x205`, `51:00..7F` | `0x205` — local router; destination `0xEE` приходит в `dji_wlm`. Таблица имеет только ID `00..51`; `51:1A` имеет SDR-only liveview branch, а `52..7F` не имеют table slots. В пользовательском live-тесте sweep не дал видимого эффекта | Структура `CONFIRMED`; activation не подтверждена, наблюдаемый эффект `NEGATIVE` |

В persistent Home Point mode при разрыве потока приложение снова подключается
с задержкой 5 секунд и продолжает ждать следующую запись Home Point. Пока оно
ждёт, DUML-запросы не отправляются: читается уже существующая telemetry.

Штатный eSIM flow, найденный в DJI Fly 1.21.4, использует семейство
`18:4B/4C`. Дополнительный разбор ранее скачанных RC Pro 2 build 139 и 576
показал, что текущий sweep `51:00..7F` не просто не имеет readback: `51:1A`
разбирает ведущие нули как `SERVICE_LIVEVIEW + LIVEVIEW_SDR`; в `51:19` первый
символ serial попадает в `cmd_type` и отвергается; `51:22` получает
`status=unknown` и снимает bind flag. Ещё 46 старших ID находятся вне
зарегистрированной таблицы. При этом реальная отправка всего sweep,
выполненная пользователем, не дала видимого эффекта. Поэтому профиль
сохраняется только как явно названный legacy research sweep, а не как
доказанная 4G activation.

RM510 firmware отдельно раскрыла один ID, попадающий внутрь sweep:
исходящий `51:14` от `dji_wlm` — это не activation response, а переменный
neighbour/device-link list длиной `2 + 49 × N`. В live capture его 51-байтный
payload означает одного peer и штатно содержит identity region с полным
aircraft serial. Совпадение serial и command ID со sweep поэтому не доказывает
4G-активацию; направление и payload contract у наблюдаемого push другие.

## Команды, которые FreeFCC не отправляет

| Команда | Что найдено | Вывод |
|---|---|---|
| `09:EC` | RM510: `dji_wlm`, Build ID `e14a06545de716c6332364c4c46cfa21`, `wlm_lk_ctrl_set_sdr_param` `0x6450c`. WA530: Build ID `44cbbdf500c75ce413333428c435b78d`, та же функция `0x17dde0`. Обе реализации выбирают `00 03` = silence SDR 2.4G, `00 04` = silence SDR 5.8G, `00 00` = обычная/reset-ветка и повторяют send до трёх раз | Cross-generation и ground/air evidence подтверждает событийную Wi-Fi/SDR coexistence-настройку. Фиксированного таймера не найдено; добавлять её в keepalive оснований нет |
| `06:77` | Один passive frame на `40009`, route `0x06 → App`, payload `00`; обработчик в проверенных RM510 userspace-бинарниках не найден | Это наблюдаемая RC telemetry/status, но точная семантика и тезис о периоде 2.5 секунды не подтверждены |

Подробные адреса вызовов и остальные команды из этих ELF находятся в
[`RM510_DUML_COMMAND_REFERENCE.md`](RM510_DUML_COMMAND_REFERENCE.md).

## Источники статических имён

Публичная cross-check база зафиксирована на commit
[`4c7bb40e5cc5daec67b39cc093235afb959a4bfe`](https://github.com/ctomichael/fpv_live/tree/4c7bb40e5cc5daec67b39cc093235afb959a4bfe):

- [`CmdIdCommon`](https://github.com/ctomichael/fpv_live/blob/4c7bb40e5cc5daec67b39cc093235afb959a4bfe/src/main/java/dji/midware/data/config/P3/CmdIdCommon.java);
- [`CmdIdFlyc`](https://github.com/ctomichael/fpv_live/blob/4c7bb40e5cc5daec67b39cc093235afb959a4bfe/src/main/java/dji/midware/data/config/P3/CmdIdFlyc.java);
- [`CmdIdWifi`](https://github.com/ctomichael/fpv_live/blob/4c7bb40e5cc5daec67b39cc093235afb959a4bfe/src/main/java/dji/midware/data/config/P3/CmdIdWifi.java);
- [`CmdIdOsd`](https://github.com/ctomichael/fpv_live/blob/4c7bb40e5cc5daec67b39cc093235afb959a4bfe/src/main/java/dji/midware/data/config/P3/CmdIdOsd.java);
- [`DataOsdSetSdrAssitantWrite`](https://github.com/ctomichael/fpv_live/blob/4c7bb40e5cc5daec67b39cc093235afb959a4bfe/src/main/java/dji/midware/data/model/P3/DataOsdSetSdrAssitantWrite.java);
- [`OcuSyncFrequencyBand`](https://github.com/ctomichael/fpv_live/blob/4c7bb40e5cc5daec67b39cc093235afb959a4bfe/src/main/java/dji/common/airlink/OcuSyncFrequencyBand.java);
- [`DataAbstractGetPushActiveStatus`](https://github.com/ctomichael/fpv_live/blob/4c7bb40e5cc5daec67b39cc093235afb959a4bfe/src/main/java/dji/midware/data/model/common/DataAbstractGetPushActiveStatus.java).

Исторические upstream issues
[#24](https://github.com/Drone-Hacks/free-fcc/issues/24),
[#26](https://github.com/Drone-Hacks/free-fcc/issues/26) и
[#28](https://github.com/Drone-Hacks/free-fcc/issues/28) учитываются как
наводки, а не как доказательство семантики.

## Итог

Составной FCC-профиль имеет подтверждённый практический результат на части
оборудования, но его прежняя покадровая расшифровка была слишком уверенной.
Для дальнейшего сокращения последовательности нужен обратимый A/B: по одному
убирать opaque-группы, сохранять exact capture и независимо фиксировать
Transmission graph/RF state. До такого теста безопаснее сохранять байты
профиля, но не приписывать каждому кадру недоказанную функцию.
