# Аудит DUML-команд FreeFCC

Дата среза: 2026-07-23. Базовый commit: `4cd92277e193ebe328c47cd7a81dd46d21a4726c`.

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

## Полный FCC-профиль

`fcc.json` содержит 21 кадр и отправляется в два раунда. На проверенных
комбинациях дрона и пульта полный составной профиль приводил к FCC-результату,
но это не доказывает необходимость каждого кадра и универсальность
последовательности.

| № | Команда | Payload | Что известно | Уровень |
|---:|---|---|---|---|
| 1, 21 | `10:58` | `030100` | Одинаковый запрос стоит в начале и конце; старые подписи «вход/выход service mode» друг друга не подтверждают | `UNKNOWN` |
| 2 | `06:72` | `00000000000100` | Радио-запрос; точный параметр и значение не декодированы | `UNKNOWN` |
| 3 | `03:F9` | `8a237103f401` | Hash `0x0371238a`, значение LE `0x01f4` = 500: запись `max_height`; это побочный flight-limit write, а не FCC primitive | `CONFIRMED` |
| 4 | `00:00` | `000001` | Общий запрос к `dst=0x1f`; точная функция не установлена | `UNKNOWN` |
| 5 | `00:32` | `3131000000` | В WM260 `dst=0x6F` — `dji_sec/sec_service` (`s_to_p_air:3`). Aircraft handler принимает `0x31` и возвращает 59-байтное состояние активации; четыре последующих request bytes в этой ветке не используются. Это query, не FCC-write | `CONFIRMED` для WM260 |
| 6 | `03:AF` | `032400000000000000` | Публичное имя семейства — `GetAreaCode`; значение этого payload не декодировано | `INFERRED` |
| 7, 8 | `07:30` | `41550000415500000100` | RM510 handler читает только первые два байта `AU`, пишет vendor country slot `6` и `country.bin`; хвост игнорирует. Два кадра полностью одинаковы, поэтому band-specific смысл не подтверждён | `CONFIRMED` для локального handler contract |
| 9 | `09:27` | `00024800ffff0200000000` | SDR assistant write: address `0xffff0048`, value `2`; публичный DJI-код называет эту операцию `setForceFcc` | `CONFIRMED` |
| 10 | `09:27` | `00026300ffff0300000000` | SDR assistant write: address `0xffff0063`, value `3`; это register write, но точный эффект value `3` не установлен | `CONFIRMED` для register/value, эффект `UNKNOWN` |
| 11 | `07:18` | `ff415500` | Запись country/area с `AU`; prefix `ff` и route зависят от реализации | `CONFIRMED` для family |
| 12 | `07:19` | `c0` | RM510 handler не читает request payload, а возвращает 2-byte alpha-2 country из vendor slot `6`; `c0` в этой реализации игнорируется | `CONFIRMED` для локального handler contract |
| 13, 14 | `03:F9` | `d04aeffb01/00` | Запись неизвестного hash `0xfbef4ad0` в `1`, затем `0` | `CONFIRMED` для hash/value, имя `UNKNOWN` |
| 15 | `00:E5` | `323201` | В WM260 получатель `dst=0x6F` — `dji_sec/sec_service`; `00:E5` обслуживает DJI Care binding/pairing/status. Точный смысл subtype `32 32 01` не восстановлен, но признаков прямого RF/FCC-действия нет | target/family `CONFIRMED`; subtype `UNKNOWN` |
| 16 | `03:F9` | `236b820101` | Запись неизвестного hash `0x01826b23` в `1` | `CONFIRMED` для hash/value, имя `UNKNOWN` |
| 17 | `03:F9` | `8773e68a01` | Запись неизвестного hash `0x8ae67387` в `1` | `CONFIRMED` для hash/value, имя `UNKNOWN` |
| 18, 19 | `06:8C` | `000300`, `000100` | Два opaque radio request; параметр не декодирован | `UNKNOWN` |
| 20 | `06:72` | `000000000001ff` | Радио-запрос; старая подпись «commit» не подтверждена | `UNKNOWN` |

Главный непосредственно распознанный FCC-write здесь — первый `09:27`
`setForceFcc`. Country-команды также реально меняют country/area state, но
полный RF-эффект составного профиля необходимо проверять графиком Transmission
или независимым RF/readback evidence.

## Пятисекундный periodic-профиль

`fcc_keepalive.json` повторяет четыре кадра:

| Команда | Payload | Статус |
|---|---|---|
| `10:58` | `030100` | `UNKNOWN`; первый и последний кадры идентичны |
| `06:72` | `00000000000100` | `UNKNOWN` |
| `06:72` | `000000000001ff` | `UNKNOWN` |
| `10:58` | `030100` | `UNKNOWN`; идентичен первому |

При периоде 5 секунд это 48 DUML writes в минуту и 2880 в час. CPU-трафик
невелик, но цикл постоянно будит процесс, открывает транспортные операции и
пишет четыре команды с недоказанной необходимостью. Поэтому periodic mode
оставлен отдельным явно выбираемым режимом; Home Point mode вместо этого
пассивно слушает telemetry и применяет полный профиль по событию.

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
| 4G | abstract Unix `/duss/mb/0x205`, `51:00..7F` | 128 fire-and-forget кадров `000000 + ASCII(serial)`, без ACK/readback | Формат `CONFIRMED`, activation semantics `UNKNOWN` |

В persistent Home Point mode при разрыве потока приложение снова подключается
с задержкой 5 секунд и продолжает ждать следующую запись Home Point. Пока оно
ждёт, DUML-запросы не отправляются: читается уже существующая telemetry.

Штатный eSIM flow, найденный в DJI Fly 1.21.4, использует семейство
`18:4B/4C`. Поэтому текущий sweep `51:00..7F` нельзя называть эквивалентом
штатной активации встроенной eSIM без отдельного live-подтверждения.

RM510 firmware отдельно раскрыла один ID, попадающий внутрь sweep:
исходящий `51:14` от `dji_wlm` — это не activation response, а переменный
neighbour/device-link list длиной `2 + 49 × N`. В live capture его 51-байтный
payload означает одного peer и штатно содержит identity region с полным
aircraft serial. Совпадение serial и command ID со sweep поэтому не доказывает
4G-активацию; направление и payload contract у наблюдаемого push другие.

## Команды, которые FreeFCC не отправляет

| Команда | Что найдено | Вывод |
|---|---|---|
| `09:EC` | В RM510 `dji_wlm`, Build ID `e14a06545de716c6332364c4c46cfa21`, функция `wlm_lk_ctrl_set_sdr_param` по адресу `0x6450c`. Перед отправкой до трёх раз запрашивает `09:21`; затем до трёх попыток `09:EC`. Payload `00 03` соответствует silence SDR 2.4G, `00 04` — silence SDR 5.8G, `00 00` — обычной/reset-ветке | Это событийная Wi-Fi/SDR coexistence-настройка. Фиксированного таймера 10 секунд не найдено; добавлять её в keepalive оснований нет |
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
