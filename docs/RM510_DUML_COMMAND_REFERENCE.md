# Справочник DUML-команд из RM510 / DJI RC2

Дата среза: 2026-07-23.

Это статический справочник по командам, которые удалось восстановить из
сохранённых userspace ELF пульта RM510, с cross-check по live DUML-потокам
FreeFCC. Это не полный каталог всего DJI protocol: часть команд формируется
динамически, часть обслуживается отдельными MCU/aircraft components, а не
Android/Linux userspace пульта.

Связанный аудит команд, которые отправляет само приложение:
[`DUML_COMMAND_AUDIT.md`](DUML_COMMAND_AUDIT.md).

## Как читать таблицы

| Метка | Значение |
|---|---|
| `SEND` | В бинарнике найдено формирование/отправка фиксированной пары |
| `QUERY` | Синхронный запрос с ожиданием результата |
| `PUSH` | Отправка состояния/события без доказанного request/response contract |
| `HANDLER` | Входной обработчик или регистрация команды |
| `EXTENDED` | Внутренний DUSS identifier шире обычной пары из двух однобайтовых полей |

Имя функции надёжно показывает контекст, но не всегда полностью раскрывает
payload. Поэтому в колонке «уровень» отдельно отмечены точные и контекстные
выводы.

## Исходный корпус

Сохранённый рабочий corpus:
`fpga_tang_nano_9k_card_reader-spinal/.scratch/rc_rm510_20260723/`.

| Файл | Размер | Build ID | SHA-256 |
|---|---:|---|---|
| `dji_wlm` | 507296 | `e14a06545de716c6332364c4c46cfa21` | `f505f027b09e5fee7eaca0f6089c41a866d630e5e0fe70087ba875543f8dd013` |
| `dji_sdrs_agent` | 252472 | `dc7b9ab48d0e18d593c63ad78a65be1e` | `b1092ec65d76672182cc0c0c1d125be58bf8ab447ad3e3fbfab9eb37019d990e` |
| `dji_link` | 264080 в исходной копии | `370079f72741e7dee0216f75333e0b86` | `60478c1f366675e5baf9194cd5b44ebe26ef2d23ae8c662a773aa29be5ddab31` |
| `libduml_frwk.so` | 1494128 | `b70586902d0f6e7f8f7926af5d89b391` | `0a8ec725aa6b72d4cc087b3b95120928138a25f6694c98d9a441bfd071ad34ce` |
| `libwlm.so` | 294952 | `2d6d64b85a03802c6e10a0b1016d1d69` | `2b63101f2b01aceab6de6e63afc7dd8694710d4ab8e6e154adfb023b73337931` |
| `dji_mb_ctrl` | 20024 | `698984164eff96d0f90a4e415186fd9f` | `d0ae0666c9937afe3ed604576ca7d92b661c7a56b9c6a838572c469326175cb2` |

Для `dji_wlm`, `dji_sdrs_agent` и `dji_link` использована встроенная
`.gnu_debugdata`: символы позволяют привязать константу команды к конкретной
функции. `libduml_frwk.so`, `libwlm.so` и `dji_mb_ctrl` важны как transport/
framework evidence, но в этом проходе новых фиксированных product-команд из
них не извлечено.

Во время извлечения `.gnu_debugdata` `llvm-objcopy` нормализовал рабочую
scratch-копию `dji_link`: текущий контейнер имеет размер 264072 и SHA-256
`cd02dd84f16b4ad1d18c96fefa203740aa07290a547ec429f04ba98e6e77122c`.
Build ID и кодовые адреса не изменились; в таблице сохранены размер и hash
исходной полученной копии. Рядом оставлены производные
`dji_link.gnu_debugdata.xz` и `dji_link.gnu_debugdata.elf`.

## `dji_wlm`

| Команда | Тип | Функция | Call site | Что делает | Уровень |
|---|---|---|---:|---|---|
| `08:65` | `QUERY` | `wlm_bw_bybrid_task` | `0x35fd8` | Синхронный запрос внутри задачи hybrid bandwidth; точный payload contract не декодирован | `INFERRED` |
| `07:A120` | `EXTENDED/PUSH` | `event_track_send` | `0x59170`, `0x59460` | Event tracking/report. `0xA120` — расширенный ID, а не legacy byte `0x20` | `INFERRED` |
| `51:04` | `PUSH` | `wlm_push_dev_osd` | `0x5cc1c` | Device OSD/status push | `CONFIRMED` по symbol context |
| `51:05` | `PUSH` | `wlm_push_link_sw_result2sysmode`; `wlm_inform_route_sw` | `0x5e2c0`, `0x67ec8` | Результат link switch и уведомление route switch | `CONFIRMED` по symbol context |
| `51:07` | `QUERY` | `__wlm_link_ctl` | `0x670e8` | Link control request; payload ещё не декодирован | `INFERRED` |
| `51:0C` | `PUSH` | `wlm_link_state_manage_task` | `0x5b910` | Фиксированный 10-байтный local all-link-mode/composite-link-state report | `CONFIRMED` по data flow |
| `51:14` | `PUSH` | `wlm_link_state_manage_task` | `0x5b6dc` | Переменный neighbour/device-link list: `2 + 49 × N` байт | `CONFIRMED` по data flow и live length |
| `09:21` | `QUERY` | `wlm_lk_ctrl_set_sdr_param` | `0x645bc`, `0x64620`, `0x64684` | Получение текущего SDR/link состояния; до трёх попыток | `CONFIRMED` для control flow |
| `09:EC` | `SEND` | `wlm_lk_ctrl_set_sdr_param` | `0x64900`, `0x64974`, `0x649e8` | Wi-Fi/SDR coexistence: `00 03` silence SDR 2.4G, `00 04` silence SDR 5.8G, `00 00` reset/ordinary branch; до трёх попыток при ошибке | `CONFIRMED` |
| `18:35` | `SEND` | `wlm_select_upgrade_case`; `wlm_capture_dongle_log` | `0x71334`, `0x71564` | Один multiplexed diagnostic/control ID к host `0x0e06`; upgrade-case и capture dongle log различаются payload/action | `CONFIRMED` по двум функциям, payload layout частичный |

`09:EC` вызывается событийно при переключении coexistence/частот. Жёсткого
цикла «каждые 10 секунд» в этой функции не найдено.

### Link-state payload `51:0C` и `51:14`

В `wlm_link_state_manage_task` базовый raw ID равен `0x0051000c`. Вызов по
`0x5b910` отправляет его как `51:0C` с фиксированной длиной 10 байт:
`wlm_get_local_all_lk_mode` и `wlm_get_local_comp_lk_sta` собирают локальные
режимы/сводное состояние SDR, LTE, Wi-Fi и command/data links.

Для neighbour report код прибавляет к ID `8`, получая `0x00510014`, и вызывает
отправку по `0x5b6dc`. Payload имеет layout:

| Offset | Размер | Значение |
|---:|---:|---|
| `0` | 1 | Число соседних устройств `N` |
| `1` | 1 | Ноль/reserved |
| `2 + 49 × i` | 23 | Identity/name region соседнего устройства |
| `+23` | 2 | LE u16 из peer link-state structure |
| `+25` | 1 | Peer state/type byte |
| `+26` | от 3 | Link modes, заполненные `wlm_dev_link_get_lk_mode` |
| `+29`, `+33`, `+37` | 3 × 4 | Три LE u32 timestamp/age values, исходные значения делятся на 1000 |
| `+41` | 8 | Нули/reserved |

Размер записи равен 49 байтам, полный размер — `2 + 49 × N`. Live payload
длиной 51 байт поэтому означает ровно одного соседа. Первые 23 байта записи
копируются из identity/name region peer structure; это объясняет, почему в
живом `51:14` присутствует полный aircraft serial. Строка журнала firmware
называет записи `neighbour` и печатает для них SDR/LTE/Wi-Fi state, receive
timestamps, command/link/video/data modes.

`wlm_forward_msg_send` — общий forward path: вызывающий передаёт ID во время
выполнения. Такие команды нельзя честно добавить в таблицу как фиксированные
пары только по этому wrapper.

## `dji_link`

| Команда | Тип | Функция | Call site | Что делает | Уровень |
|---|---|---|---:|---|---|
| `07:A120` | `EXTENDED/QUERY` | `dji_init_task_entry` | `0x134b8` | Внутренняя синхронизация/event-track при запуске сервиса | `INFERRED` |
| `08:32` | `PUSH` | `dji_command_live_view_push_to_rc` | `0x1c270` | Live-view state push на RC | `CONFIRMED` по symbol context |
| `00:32` | `PUSH` | `dji_command_active_push` | `0x1c3b8` | Activation state push | `CONFIRMED` по symbol context |
| `00:32` | `PUSH` | `dji_command_active_auth_push` | `0x1c514` | Activation authorization push; тот же ID, другой payload path | `CONFIRMED` по symbol context |
| `06:C4` | `SEND` | `dji_command_deactive_wipe_data` | `0x1c6a8` | Deactivation/data-wipe command | `CONFIRMED` по symbol context |
| `06:A5` | `QUERY` | `dji_command_set_mcu_active` | `0x1c7d4` | Установка MCU active state/flag | `CONFIRMED` по symbol context |
| `18:39` | `SEND` | `send_fusion_info_to_lte` | `0x1cb8c` | 27-байтный sparse report с тремя WLM link-ratio в LTE service, destination `0x0e06` | `CONFIRMED` по data flow |
| `51:10` | `SEND` | `send_lte_info_to_wlm` | `0x1ccb4` | 34-байтный LTE channel-state block в WLM, destination `0x0e07` | `CONFIRMED` по data flow |
| `0200:0D05` | `EXTENDED/QUERY` | `secure_open_debug_auth` | `0x1e844` | Внутренняя secure debug authorization | `CONFIRMED` по symbol context; wire layout расширенный |

`dlink_forward_message_with_no_ack` и `duss_send_pack` также принимают
команду во время выполнения. Наличие вызова wrapper не раскрывает полный набор
пересылаемых ID.

`18:39` формируется после успешного `libwlm_channel_get_param`. Payload заранее
обнуляется, затем три ratio записываются в offsets `0`, `6` и `3`, а ещё один
state byte — в offset `25`; длина всегда 27 байт. Функция вызывается из
`sys_process_cb` и `dji_stream_recv_task`, то есть это event/stream-side
синхронизация WLM → LTE, а не периодический FCC keepalive.

`51:10` копирует ровно 32 последовательных байта из LTE stream structure и
добавляет LE u16, получая длину 34 байта. В начале блока firmware отдельно
логирует `chan_num` и четыре соседних u16 values. Вызов находится в
`dji_send_stream_via_localsocket` и выполняется только для активного LTE
stream path. Точные имена всех полей без type information пока не
восстановлены.

## `dji_sdrs_agent`

| Команда | Тип | Функция | Call site | Что делает | Уровень |
|---|---|---|---:|---|---|
| `00:0100` | `EXTENDED/HANDLER` | `sa_event_ping` | registration `0x229a0`, create `0x229ac` | Эхо входного payload без изменения | `CONFIRMED`; raw ID `0x00000100` |
| `07:A120` | `EXTENDED/QUERY` | `sa_relay_task_entry` | `0x1f574` | Общий relay task operation | `INFERRED` |
| `07:A120` | `EXTENDED/QUERY` | `sa_relay_get_profile` | `0x1fcb0` | Получение relay profile | `CONFIRMED` по symbol context |
| `07:A120` | `EXTENDED/QUERY` | `sa_relay_route_switch` | `0x1fe94` | Relay route switch | `CONFIRMED` по symbol context |
| `07:A120` | `EXTENDED/QUERY` | `sa_relay_shutdown_pigeon` | `0x2001c` | Shutdown relay/pigeon path | `CONFIRMED` по symbol context |

Разные операции multiplexed через один расширенный ID и различаются payload.
`sa_heartbeat_task` отправляет команду, полученную в runtime (`w21`), на
destination `0x20:0e00`; фиксировать для неё выдуманную пару нельзя.

Кроме зарегистрированного ping, в ELF есть ещё четыре именованные служебные
функции без восстановленной числовой DUML-пары:

| Handler | Контекст |
|---|---|
| `sa_event_ping` | Возвращает входной payload; единственный handler с восстановленным raw ID `0x00000100` |
| `sa_event_sysreboot` | Не перезагружает Android: выполняет reset modem; при втором payload byte `0x02` удерживает modem reset |
| `sa_event_amt_nvram_rw` | Сегментированное AMT NVRAM read/write с file match и проверкой offset/length; активный вызов потенциально разрушителен |
| `sa_event_common_query_device_info` | Формирует строку build/device info и отвечает без отдельного retcode |
| `sa_event_rt_control_by_name` | По имени `/dev/...` включает или выключает соответствующий route-table item через `duss_mb_control_route_item` |

`sa_event_start` явно создаёт service client с `sa_event_ping` и raw ID
`0x00000100`. Для четырёх остальных функций прямой DUML registration site не
найден: они используются как служебные callbacks/helpers в других
service/parameter paths.

Отдельная статическая таблица по адресу `0x3abc8` содержит 16 регистраций
встроенного parameter manager. `duss_register_cmd` читает из каждой 24-байтной
записи `cmd_set`, `cmd_id`, request handler и общий ACK handler, затем вызывает
`duss_event_register_dynamic_command` по `0x34e3c`:

| Команда | Request handler | Назначение |
|---|---|---|
| `03:F3` | `reset_cfg_item_value_func` | Reset config item value |
| `03:F7` | `get_cfg_item_info_by_hash_func` | Metadata config item по hash |
| `03:F8` | `read_cfg_item_value_by_hash_func` | Read config item по hash |
| `03:F9` | `write_cfg_item_value_by_hash_func` | Write config item по hash |
| `03:FA` | `reset_cfg_item_value_by_hash_func` | Reset config item по hash |
| `03:FB` | `recv_fixed_send_cfg_by_hash_func` | Receive fixed-send config |
| `03:FC` | `req_fixed_send_cfg_by_hash_func` | Request fixed-send config |
| `03:E0` | `api_user_ask_table_func` | Запрос parameter table |
| `03:E1` | `api_user_ask_param_by_index_func` | Запрос metadata по index |
| `03:E2` | `api_usr_get_param_by_index_func` | Read parameter по index |
| `03:E3` | `api_usr_set_param_by_index_func` | Write parameter по index |
| `03:E4` | `api_usr_def_param_by_index_func` | Default/reset parameter по index |
| `01:40` | `get_cfg_item_info_by_hash_func` | Common-set alias для `03:F7` |
| `01:41` | `read_cfg_item_value_by_hash_func` | Common-set alias для `03:F8` |
| `01:42` | `write_cfg_item_value_by_hash_func` | Common-set alias для `03:F9` |
| `01:43` | `reset_cfg_item_value_by_hash_func` | Common-set alias для `03:FA` |

Эта таблица относится к parameter manager, а не к четырём оставшимся SDRS
handlers. Поэтому назначать `sa_event_sysreboot`, `sa_event_amt_nvram_rw`,
`sa_event_common_query_device_info` или `sa_event_rt_control_by_name` ID по
соседству нельзя.

## Cross-check с live-потоком RC2

Ниже перечислены пары, реально наблюдавшиеся приложением. Это transport
evidence, а не доказательство наличия handler именно в трёх ELF выше.

| Команда | Наблюдение |
|---|---|
| `03:43` | GPS/flight telemetry, поля спутников и GPS state |
| `03:44` | Home Point telemetry; на проверенном layout `home_state` at offset 20 |
| `06:77` | Один passive RC status frame, payload `00`; точная семантика неизвестна |
| `06:A4` | RC telemetry/status, payload `00` |
| `06:AE` | Основной background RC payload на `40009`; семантика неизвестна |
| `00:81`, `00:82` | Controller identity telemetry |
| `07:18`, `07:30` | Country/area write family |
| `07:19` | Country-code query family |
| `51:04` | Cellular/device OSD telemetry; совпадает со статическим `wlm_push_dev_osd` |
| `51:14` | Neighbour/device-link list `2 + 49 × N`; live length 51 означает одного peer, а identity region его записи содержит полный aircraft serial |

Полная частотная карта live capture сохранена в
[`DUML_STREAM_MAP.md`](DUML_STREAM_MAP.md).

## Граница полноты

Таблица включает все фиксированные пары, которые в текущем проходе удалось
привязать к именованным функциям `dji_wlm`, `dji_link` и `dji_sdrs_agent`.
Она заведомо не включает:

- ID, передаваемые в generic forward/send wrappers только во время выполнения;
- команды remote MCU, aircraft, camera и flight controller, которых нет в
  userspace ELF пульта;
- handler tables, числовые поля которых ещё требуют data-flow reconstruction;
- зашифрованные/упакованные компоненты, не входившие в сохранённый corpus.

При следующем расширении справочника надо добавлять не просто найденный
hex-number, а минимум: файл и Build ID, функцию, call site/registration site,
направление, payload evidence и уровень уверенности.
