# Live-карта DJI cellular-модемов

Дата проверки: 2026-07-21.

Цель: зафиксировать USB/AT-поведение проверенных DJI cellular-модемов отдельно
от DUML/DUSS 4G-команд FreeFCC. Наличие интернет-соединения у модема не
доказывает, что aircraft link активирован, а успешная запись FreeFCC в
`/duss/mb/0x205` не настраивает SIM, APN или USB network composition.

Полные IMEI, IMSI и ICCID намеренно не сохраняются в репозитории.

## Краткий результат

| Устройство | Уровень | Результат |
|---|---|---|
| Fibocom `NL668T-GL`, physical Yota SIM | OBSERVED | SIM `25011...` выбрана, регистрация в Yota успешна, APN `yota.ru`, PDP CID 1 активен |
| Fibocom USB profile `30` | OBSERVED | Только закрытые/vendor AT-интерфейсы; host network interface отсутствует |
| Fibocom USB profile `31` | OBSERVED | Добавляется CDC ECM (`cdc_ether`); после `GTRNDIS=1,1` работает host networking |
| eSIM-устройство с двумя Quectel/QDC535EA functions | OBSERVED | Одинаковые firmware/USB identity, разные встроенные UICC/eUICC-профили, обе functions были без packet attach в РФ |
| Оба модема внутри RC Pro 2 `rc520` | OBSERVED | `dji_lte` автоматически различает dual/QDC535EA и Fibocom `dji_mini`, создаёт разные UART workers и сохраняет общий DJI LTE service path |
| RC Pro 2 + Fibocom/Yota без модема в aircraft | OBSERVED + PHYSICAL REPORT | Наземная SIM зарегистрирована в LTE, но WLM остаётся `lte_conn=0`; это ожидаемый half-link, а не доказательство отказа модема пульта |
| Air 3S + Fibocom/Yota без модема в RC Pro 2 | OBSERVED + PHYSICAL REPORT | Пульт получил `peer dongle insert 0 -> 1`, затем remote `sig_bar_lte` менялся `0 -> 3`; end-to-end WLM остался `lte_conn=0`, потому что наземный endpoint отсутствовал |
| OpenFCC desktop launcher | OBSERVED | Отдельный controller OTA flow; не является AT-активацией USB-модема |

## Live-интеграция на RC Pro 2

### Scope и физическая топология

Пассивный ADB-съём выполнен 2026-07-21 на RC Pro 2 `rc520`, Android 11,
controller build `V55.31.05.76/576`. Aircraft определялся как Air 3S `WA234`
и был связан с пультом по SDR.

Пользователь отдельно подтвердил физическую топологию: оба проверенных модема
последовательно устанавливались **в пульт**, а в aircraft модема во время этого
съёма не было. В пульт не отправлялись AT, DUML или LTE control commands;
читались только `proc`, `sysfs`, Android services, kernel/logcat и штатные
конфигурации.

### Timeline замены модемов

| Время MSK | Уровень | Событие |
|---|---|---|
| `23:31:09` | OBSERVED | Внутренний hub `1a86:8091` перечислил две функции `2ecc:3001` |
| `23:31:18–19` | OBSERVED | Обе функции сменились на `2ca3:4010`; появились `ttyUSB0..5`, а `dji_lte` создал два набора `modem_dual_send/parse/ping` workers |
| `23:31:25–27` | OBSERVED | Android загрузил два китайских SIM-профиля; оба остались без регистрации и packet data, а per-subscription `mobile_data1/2` были `0` |
| `23:35:43` | OBSERVED | Все шесть dual-modem UART исчезли после физического извлечения первого модема |
| `23:36:18–19` | OBSERVED | Fibocom/Baiwang появился как один `2ca3:4009`: `ttyUSB0..3`, `cdc_ether`, `usb0`; `dji_lte` создал `modem_mini_send/parse/ping` workers |
| `23:36:22` | OBSERVED | Android загрузил физическую Yota SIM как единственную активную подписку и назначил её default data/voice/SMS |
| `23:37:13` | OBSERVED | Примерно через `54.65 s` после USB attach Yota перешла в `IN_SERVICE`; LTE RSSI был около `-83 dBm`, но через `66 ms` framework снова сообщил data `DISCONNECTED` |
| `23:43:41` | OBSERVED | Повторный stable snapshot сохранил `IN_SERVICE`, LTE RSSI `-77 dBm`, но Android data и DJI WLM всё ещё оставались disconnected |

Это две разные физические модемные конфигурации. Переход
`2ca3:4010 -> 2ca3:4009` нельзя интерпретировать как USB mode switch одного
модема.

### Android bearer и DJI link — разные состояния

После стабилизации Fibocom/Yota одновременно наблюдались:

| Слой | Live state | Вердикт |
|---|---|---|
| SIM | `LOADED`, physical subscription, default subId выбран правильно | Наземная SIM готова |
| Radio registration | LTE `IN_SERVICE`, home network Yota, packet service advertised | Модем зарегистрирован в сети |
| Android setting | `mobile_data=1` | Data не отключена пользовательской настройкой |
| Android data connection | `mDataConnectionState=0`, `maxDataCalls=0`, `maxActiveData=0`; cellular `NetworkAgent` отсутствует | Обычный Android IP bearer не поднят |
| USB network | `usb0`, `192.168.50.1/32`, локальный route `192.168.50.0/24`; default route остаётся на Wi-Fi | Это modem-local interface, не подтверждённый Internet bearer |
| DJI WLM | `lk_state_sdr=1`, `lte_conn=0`, `lte_usable=0`, `use_lte=0`, `dev_lte_conn=disconnect` | End-to-end aircraft LTE link отсутствует |

У старого dual/QDC535EA модема generic `mobile_data=1` сочетался с
`mobile_data1=0` и `mobile_data2=0`; radio log прямо показывал
`DATA_DISABLED`/`isUserDataEnabled=false`. После замены на Yota система создала
новый subId, назначила его default и записала `mobile_data3=1`. Поэтому
состояние data нельзя переносить с одного физического модема на другой только
по generic setting.

DERIVED: при физически отсутствующем aircraft-модеме нулевой DJI LTE link
является ожидаемым half-link состоянием. Этот тест подтверждает распознавание и
сетевую регистрацию модема пульта, но не может проверить pairing, encrypted
session или Enhanced Transmission между двумя LTE endpoints.

NEGATIVE: в проверенном окне не было перехода WLM в LTE, LTE traffic allocation
или переключения liveview/download с SDR. Это не указывает на ошибку Yota SIM,
поскольку обязательный воздушный peer отсутствовал.

HYPOTHESIS: Android bearer может блокироваться vendor RIL policy/capability
(`maxDataCalls=0`/`maxActiveData=0`) либо обслуживаться отдельно внутри
`dji_lte`. Capture доказывает `DISCONNECTED` и отсутствие Cellular
`NetworkAgent`, но не точную внутреннюю ветку vendor RIL.

### Обратный half-link: Fibocom/Yota только в Air 3S

В отдельном пассивном окне `23:48:38–23:51:38` тот же Fibocom/Yota был физически
вынут из RC Pro 2 и установлен в Air 3S. На пульте локальная SIM перешла в
`ABSENT`, исчезли `usb0` и modem-local route; SDR-связь с aircraft сохранилась.

| Время MSK | Уровень | Событие |
|---|---|---|
| `23:49:45.848` | OBSERVED | `dji_wlm` на пульте сообщил `peer dongle insert status changed:0 -> 1` |
| `23:49:58.704` | OBSERVED | Remote поле `sig_bar_lte` впервые изменилось с `0` на `3`; далее в окне оно обновлялось между `0` и `3` |
| Всё окно после attach | OBSERVED | `lk_state_sdr=1`, но `lk_state_lte=0`, `lte_conn_state=0`, `lte_usable=0`, `use_lte=0` |
| `23:56:06.010` | OBSERVED | WLM ability negotiation завершилась с `lte_enable=1`, `lte_comm=1`, `lte_spec=1`, `lte_dynamic=1` |
| Финальный Android snapshot | OBSERVED | На пульте local telephony остался `OUT_OF_SERVICE`, signal unknown, `mDataConnectionState=0`; это ожидаемо, поскольку USB-модема в пульте уже не было |
| DJI Fly UI после attach | OBSERVED | Переключатель `flight_osd_lte_switch` disabled; warning: «Улучшенная передача данных недоступна на этом дроне в текущем регионе» |

DERIVED: aircraft-side USB attach и его LTE signal bars доставляются на RC Pro 2
через SDR/WLM независимо от Android telephony пульта. Это подтверждает штатное
распознавание модема самим Air 3S и peer-state telemetry. Полную LTE pair этот
сценарий проверить не может: один физический модем был переставлен между
сторонами, а не установлен одновременно с наземным модемом.

NEGATIVE: в течение примерно `112 s` после `peer dongle insert` не возникли
`lk_state_lte=1`, `lte_conn_state=1` или `lte_usable=1`. Это ожидаемый обратный
half-link, а не свидетельство отказа aircraft-модема или SIM.

Дополнительный UI snapshot подтвердил отдельный policy/capability gate:

- `flight_osd_lte_warning_tv`: «Улучшенная передача данных недоступна на этом
  дроне в текущем регионе»;
- APK resource: `fpv_basic_flight_topbar_panel_lte_and_sdr_unavailable_`
  `dongle_not_support_tips` (`0x7f1308cf`);
- `flight_osd_lte_switch`: `enabled=false`, `checked=false`;
- Air network и base station одновременно показаны как «Нет доступа»;
- controller properties: `persist.rc.country=RU`,
  `gsm.operator.iso-country=ru`, locale `ru-RU`.

OBSERVED: запрет отображает DJI Fly, несмотря на уже подтверждённые aircraft
dongle attach, ненулевые remote LTE signal bars и успешную WLM negotiation с
`lte_enable=1`. В ресурсах APK сетевой сбой, отсутствие SIM, необходимость
upgrade, отсутствие RC dongle, подписка и aircraft/region support представлены
отдельными ветками. DERIVED: наличие совместимого USB-модема и сотового сигнала
недостаточно для доступа к Enhanced Transmission; поверх hardware/radio state
применяется отдельная проверка aircraft/region. Capture ещё не доказывает,
вычисляется ли запрет локально из capability/country или приходит из DJI
account/backend policy.

Статический анализ защищённого DJI Fly 1.21.4 уточнил status chain:

- `KeyLTEMutualReason` ->
  `uav/sdk/keyvalue/value/flightcontroller/LTEMutualReason`;
- mapper `getFlyValue(LTEMutualReason)` возвращает `WlmLiveInvalidReason`;
- `GroundAirlink.GroundWLM.GroundWLMLiveService` публикует
  `startWlmLiveInvalidReason`;
- `SettingLinkModeLteViewModel` потребляет этот state через
  `getCanEnableLteObservable` и `getLteFeatureObservable`;
- в native SDK присутствуют `liblte_get_worker_region`,
  `lte_check_country_code` и `bool_country_in_lte_black_list`;
- cloud-control модель содержит `LTE_NOT_SUPPORT_COUNTRY_LIST`,
  `LTE_BIND_SUPPORT_COUNTRY_LIST`, `lte_feature_not_support_country_code` и
  `lte_bind_server_support_country_code`.

OBSERVED: SDK имеет отдельный invalid-reason state и отдельные cloud country
lists. DERIVED: выбранная UI-ветка, вероятнее всего, является результатом
aircraft/worker region и DJI country policy, а не Android SIM country. Из-за
AppGuard точный bytecode switch `WlmLiveInvalidReason -> resource` пока не
восстановлен; влияние account country на эту конкретную ветку не доказано.

Следующий полный тест требует двух одновременных модемов **и** снятого
aircraft/region gate. Без выполнения обоих условий pairing и реальный Enhanced
Transmission data path не появятся.

### Штатный LTE service path RC Pro 2

Публичная `/system/etc/lte_cfg.json` и runtime согласуются между собой:

- service role: `gnd`, device: `rc`, product: `RC520`;
- `dji_lte` service host: `0x0e06`, `dji_wlm`: `0x0e07`;
- local router host: `0x0205`, app host: `0x0200`, peer LTE host: `0x0806`;
- разрешены два modem instances и channels `VIDEO0/1`, `CTRL0/1`,
  `DOWNLOAD0`;
- pairing настроен `by_sdr`, поэтому SDR является управляющим каналом для
  сборки LTE pair;
- live Unix endpoints включали `/dev/dji_lte_v1`, `/dev/wl_lte_v1`,
  `/duss/mb/0xe06`, `/duss/mb/0xe07` и `/duss/mb/0x205`;
- process threads подтверждают отдельные `pairing_task`, relay/session workers,
  `gnd_rc_lte_*` workers и modem-specific send/parse/ping workers.
- `dji_lte` ELF содержит точные anchors `usb:v2CA3p4009`, `dji_mini_net`,
  `/dev/ttyUSB2` и `ril.fibocom.NetifName`; literal `2CA3p4010` в этом ELF не
  найден, поэтому поддержка dual-модема приходит через другие runtime/RIL
  компоненты либо таблицу без такого текстового идентификатора.

DERIVED: `/duss/mb/0x205` — доступный local router endpoint, но его наличие не
доказывает ни SIM attach, ни наличие aircraft-модема, ни созданную LTE pair.
Именно поэтому успешная запись текущего FreeFCC 4G profile сама по себе не
может считаться активацией Enhanced Transmission.

### Corpus и privacy

Raw corpus хранится только в ignored directory
`.scratch/rc-pro2-modem-20260721/`. Он содержит device/SIM/aircraft identifiers
и не должен публиковаться без редактирования.

| Artifact | SHA-256 |
|---|---|
| RC Pro 2 `lte_cfg.json` | `882249a0b1156b8f49f5a96070db2dd6de165ab43c45e3d68faa7b5b8ef5d06d` |
| RC Pro 2 `dji_lte` ELF | `6788276b77649e6717227ba6d70792d97fdcf135999b425b56af43db1546e8ec` |
| RC Pro 2 `dji_wlm` ELF | `0d62e3b3cf368de1fc7d019debfb60457765c4a68289eeaef48988967a0b7220` |
| Passive local socket inventory | `636234510c3e4c029ff094f2ff1394e1f4d59769e57cd155dfe126ae8a29fa33` |
| Post-swap kernel log | `3cdd27d538bf3c9f63ecaa896bd1efa15f5e22bf175e9308e1bf4b452057de07` |
| Fibocom/Yota telephony snapshot | `309a3d5956e68acdff7a60748b27247b9eca1227891c13e3153ea35ff6ee88bf` |
| Post-swap `dji_lte` log | `72a5ef92b43ea43cadadfef8a0a353760a0fae9945c001ceceb0d02251b0391f` |
| Post-swap `dji_wlm` log | `70bb3b3066d19cba2d765f478adbe4b3840e9298c4880c14f798abe2530b1cbc` |
| Final stable telephony snapshot | `059affcddcc4e3d95166591a163547d9cc9225fe51a1282653663a59c5a42286` |
| Final stable DJI LTE/WLM tail | `d9e84e068aa427893d7f1501fa2a70bb6c9da486ba0bdea7cadc94c69a005fb0` |
| Aircraft-only attach logcat | `670b86349bdac3c94eca86f83c76587aad91a434f1b059edb6a868a229e65366` |
| Aircraft-only final telephony snapshot | `435e8ae08ba81f53863166b7abbebc04fdc07509a0a0a08e76967bcbc7065e06` |
| DJI Fly aircraft/region warning UI | `9cc26804cba0f1ae6cf8cd27dabff11a57d3f1f98d48cc9e9caaf4adc36f8296` |

## Fibocom NL668T-GL с физической Yota SIM

### Идентификация

| Поле | Значение |
|---|---|
| USB VID:PID | `2ca3:4009` |
| USB product | `Baiwang` |
| AT manufacturer/model | `Fibocom Wireless Inc.`, `NL668T-GL` |
| Firmware | `19906.5090.00.02.00.23` |
| SIM/operator | физическая Yota SIM, IMSI prefix `25011`, `COPS: "YOTA"` |
| APN/CID | CID 1, `IPV4V6`, `yota.ru` |

В profile `30` AT отвечал на USB interfaces 2 и 3. После перехода в profile
`31` AT-порты сохранились, а interfaces 4/5 образовали CDC ECM network device.
VID:PID не меняется, поэтому profile нельзя определять только по `lsusb`.

### Исходное состояние

```text
+GTUSBMODE: 30
+GTAUTOCONNECT: 0
+GTRNDIS: 0
+CEREG: 0,1
+CGATT: 1
+CGACT: 1,1
```

Модем уже зарегистрировался и получил carrier-side address, но в profile `30`
Linux не видел сетевого интерфейса. `AT+GTRNDIS=1,1` возвращал `ERROR`, пока
ECM-интерфейс отсутствовал.

### Рабочая последовательность

Перед изменением проверять, что SIM готова, сеть зарегистрирована и CID 1
активен:

```text
AT+CPIN?
AT+COPS?
AT+CEREG?
AT+CGATT?
AT+CGACT?
AT+CGDCONT?
AT+CGPADDR
```

Затем:

```text
AT+GTAUTOCONNECT=1
AT+GTUSBMODE=31
```

`GTUSBMODE=31` применился немедленно и переинициализировал USB. После повторного
открытия AT-порта:

```text
AT+GTRNDIS=1,1
AT+GTRNDIS?
```

Подтверждённый ответ:

```text
+GTRNDIS: 1,1,"<carrier-ip>","<primary-dns>","<secondary-dns>"
```

После `AT+RESET` все три настройки сохранились:

```text
+GTUSBMODE: 31
+GTAUTOCONNECT: 1
+GTRNDIS: 1,1,...
```

NetworkManager получил `192.168.225.2/24` от modem gateway
`192.168.225.1`. Live-проверка через modem interface:

- `77.88.8.8`: 0% packet loss;
- `https://ya.ru/`: HTTP 302;
- `https://vk.com/`: HTTP 302;
- carrier DNS и Yandex DNS разрешают `mail.ru`;
- встроенный DNS proxy `192.168.225.1` один раз дал timeout, поэтому единичный
  DNS timeout не считать потерей LTE attach.

После modem reset ECM MAC меняется, следовательно меняется и Linux interface
name (`enx...`). Автоматизацию нужно привязывать к USB path/VID:PID и
`cdc_ether`, а не к одному имени интерфейса.

На тестовом компьютере modem connection оставлен с route metric `700`, Wi-Fi -
`600`, чтобы cellular link не перехватывал основной default route.

### Откат

Начальные значения зафиксированы, но обратная последовательность на железе не
исполнялась после успешного теста:

```text
AT+GTRNDIS=0,1
AT+GTAUTOCONNECT=0
AT+GTUSBMODE=30
```

Считать этот блок ожидаемым откатом по симметрии AT API, а не live-verified
процедурой. `GTUSBMODE=30` должен снова переинициализировать USB.

## Quectel/QDC535EA eSIM-устройство

Одно eSIM-устройство через внутренний QinHeng USB hub экспонировало две
QDC535EA composite functions. Обе одновременно проходили состояние
`2ecc:3001 WUKONG`, затем перечислялись как `2ca3:4010 Mobile Composite Device
Bus` с RNDIS, diagnostic и двумя AT interfaces.

Общее:

| Поле | Результат |
|---|---|
| Model | `QDC535EA` |
| Firmware | `QDC535EA_ACNR01D08_01.001.01.007` / `1.057.067_Q_DJI` |
| USB serial | одинаковый `200806006809080000` |
| RNDIS MAC | одинаковый `00:0c:29:a3:9b:6d` |
| SIM state | оба `CPIN: READY` |
| Packet state | оба `CGATT: 0`, PDP inactive, IP отсутствует |

Различия:

- один профиль имел China Mobile IoT UICC (`46024...`, APN `cmnet`), ISD-R AID
  не открывался;
- второй имел eUICC/eSIM (`46011...`, APN `ctnet`), ISD-R AID успешно
  открывался;
- оба видели российские LTE-сети, но оставались в `LIMSRV` с reject causes
  `No Suitable Cells In tracking Area` или
  `Roaming not allowed in this tracking area`;
- ручной выбор Yota `25011` не дал attach.

Во время теста вставка внешней Yota SIM в предполагаемый physical-slot unit не
изменила IMSI/ICCID активного профиля. Это доказывает только, что внешний слот
не был выбран в той USB/topology конфигурации; это не доказывает отсутствие
слота или неисправность SIM.

Одинаковые USB serial и RNDIS MAC делают одновременную host-network эксплуатацию
двух QDC535EA functions конфликтной. Для диагностических скриптов их нужно
различать по физическому USB path за внутренним hub.

## Статический анализ RC Pro 2 OTA и регионального 4G-барьера

Анализ выполнен без прошивки пульта. Исходные bundles в `Downloads` не
изменялись; IMAH/Android OTA и разделы извлекались в ignored
`.scratch/rcpro2_4g_ota/`. Ключевые функции независимо проверены Ghidra 12.1.2,
Rizin и GNU ARM64 objdump.

### Ряд внутренних версий `rc520_0205`

| Artifact | Внешняя версия | Внутренний Android build | SHA-256 |
|---|---:|---:|---|
| OpenFCC `firmware_update.zip` | отдельная full A/B OTA | `V55.31.01.39/139` | `182e459ba29fc00aec9c66d547cd3fe4fd14bfa47d7063e486af7b82ba3542f6` |
| `V01.00.0400_rc520_dji_system.bin` | `01.00.0400` | `V55.35.00.05/5` | `830bb932336cfd93a53c16b8d67c6d4de20d4ce456c8251193c661a84ace7afe` |
| `V01.00.0500_rc520_dji_system.bin` | `01.00.0500` | `V55.31.04.40/440` | `f0690fecb67a0262e8b9fe28f96a3c87c1be5cce4d6bde2c2fd8ea367b57cbc1` |
| `V01.00.0600_rc520_dji_system.bin` | `01.00.0600` | `V55.31.05.76/576` | `1c0a57bf53663833250a94ddf88d1ebff1efe57582ed70a98b4f9f047f22bf5b` |
| `V01.00.0700_rc520_dji_system.bin` | `01.00.0700` | `V55.31.05.76/576` | `c9319db7973777046764789c416f7ff6290de0fd73f40718852855d223fdd1e6` |

Модули `rc520_0205_v55.31.05.76_20260410.pro.fw.sig` внутри bundles `0600`
и `0700` побайтно совпадают: SHA-256
`86d1b308479f48b76619e61ebbeeaaaa2c71bf5d918f5d391c8dd0267a901cdb`.
То есть формальные версии `0600` и `0700` не дают двух разных состояний
controller Android/LTE-кода. Полезная промежуточная точка — `0500/440`.

OpenFCC artifact является полной DJI-signed A/B OTA, а не небольшим 4G-патчем:
в metadata указаны `pre-device=rc520` и
`post-build=DJI/rc520/rc520:11/V55.31.01.39/139:user/release-keys`.
Сам ZIP не имеет JAR/SignApk-подписи, но обе отдельные Android A/B signatures —
metadata и полный payload без signature blobs — успешно проверены RSA-ключом из
`META-INF/com/android/otacert` (`openssl: Verified OK`). Certificate self-signed
на `O=DJI, CN=DJI`, SHA-256 fingerprint
`52:32:A1:44:C8:4A:04:EA:34:02:A0:27:BF:AD:76:B4:F3:21:E0:C3:5D:0F:F5:C8:82:6E:D7:76:39:85:54:EB`.
Файл certificate побайтно совпадает с `otacert` официальных builds `440` и
`576` (SHA-256 файла
`b176a297c6bcc436b8149a7311627484d18c8e9d3ed2bc67c68b2af5347670e1`).
Сравнение разделов с `V55.31.05.76` показало изменение всех partition images,
кроме `dsp.img`. Поэтому действие launcher корректнее описывать как замену
полной системной OTA на старый build, а не как открытие одного socket.

Launcher commit `3fc7a7a99c734eab1c4b1be8a62b93674c47bc4a` выполняет swap так:

1. скачивает строго заданный artifact и проверяет SHA-256;
2. передаёт его через ADB в `/sdcard/update.zip` и повторно сверяет remote hash;
3. вызывает `update_engine_client --reset_status`, затем
   `update_engine_client --path=/sdcard/update.zip --update`;
4. принимает успех только по `UPDATED_NEED_REBOOT` или completion code `0`;
5. удаляет OTA, перезагружает пульт и ждёт `sys.boot_completed=1`.

Launcher не выполняет `setprop`, не редактирует `build.prop`/version database и
не патчит DJI Fly. Он передаёт inner Android OTA непосредственно штатному A/B
`update_engine`, минуя внешний DJI bundle `rc520.cfg.sig`, в котором находится
формальная версия пакета и поле `antirollback`. В текущем официальном build
`576` задано `ro.ota.allow_downgrade=true`, поэтому старый timestamp сам по себе
не блокирует этот путь. Бинарник `update_engine` из `576` явно читает это
property и содержит success branch
`The current OS build allows downgrade, continuing to apply the payload with an older timestamp.`
При этом signature/hash/device checks сохраняются.

Внутри установленной OTA нет маскировки под свежий system build:
`ro.build.id=V55.31.01.39`, `ro.build.version.incremental=139`, build date
`2025-03-18`. Строк `V55.31.05.76`, `576` или `01.00.0700` в проверенных
version properties нет. Если DJI UI после swap продолжает показывать
`01.00.0700`, наиболее вероятно, что он показывает формальную версию всего
controller bundle/version database: launcher заменил только Android `sys_app`
составляющую `0205`, а модули `0200`, `0206`, `0600`, `1400` и внешний package
record не переустанавливал. Это mixed-version state, а не свежая версия внутри
OTA. Точный UI source можно окончательно подтвердить после swap парой снимков:
`getprop ro.build.id`, `getprop ro.build.version.incremental` и экран firmware
version в DJI Fly.

`0400` не является промежуточным build без регионального барьера. В её
`/system/etc/lte_cfg.json` уже задано `dongle_display_control=true`, а
`dji_lte` уже содержит `DONGLE_INFO_TAG_DONGLE_DISPLAY_CONTROL_INFO`, поле
`need_display` и обработчик `set_liblte_auth_info()`. Внутренняя OTA имеет
`post-build=DJI/rc520/rc520:11/V55.35.00.05/5:user/release-keys`, собрана
2025-09-08 и подписана тем же DJI OTA certificate, что builds `139`, `440` и
`576`. Таким образом, в доступном ряду последний подтверждённый build без
этого механизма — OpenFCC `139`; уже в официальной `0400` механизм присутствует.

### Почему нельзя тем же способом прошить изменённую свежую OTA

Прямой вызов `update_engine_client` обходит внешний DJI bundle/version layer,
но не отключает криптографическую проверку внутренней Android A/B OTA. Любое
изменение `dji_lte`, `lte_cfg.json` или образа `system` меняет одновременно:

- hash соответствующего раздела в payload manifest;
- `FILE_HASH` полного `payload.bin`;
- metadata и full-payload RSA signatures;
- для изменения готового partition image также AVB descriptors/hash tree.

В firmware присутствует только публичный DJI OTA certificate. Закрытого ключа,
необходимого для пересчёта signatures, в проверенном corpus нет. Опубликованные
`PRAK-*` в `dji-firmware-tools` также являются публичными ключами проверки
внешнего IMAH-контейнера; приватные community `SLAK-*` не являются доверенным
DJI OTA-ключом. Поэтому launcher может установить старую неизменённую и
правильно подписанную OTA, но не может штатно установить самостоятельно
изменённую свежую OTA. Для такого варианта сначала потребовался бы отдельный
доказанный bypass `update_engine`/AVB либо доверенный приватный ключ DJI.

### Доказанный auth gate

Уже в официальной `01.00.0400` (`V55.35.00.05`) в `lte_cfg.json` задано:

```json
"dongle_display_control": true
```

В `V55.31.01.39` ключ и обслуживающий его код отсутствуют. В builds
`V55.31.04.40` и `V55.31.05.76` ключ сохраняется. Ghidra независимо показала
в `V55.35.00.05` и `V55.31.04.40` одно и то же дополнительное условие отказа
в `set_liblte_auth_info()`:

```c
dongle_display_control != 0 && lte_get_country_region() == 1
```

`lte_get_country_region()` возвращает `2`, когда текущий country code равен
`CN`, `1` для любого другого непустого country code и `0`, пока код не получен.
При штатном `dongle_display_control=true` функция поэтому не передаёт
`AUTH_PARAM` в `liblte` вне Китая. В build `139` эта проверка отсутствует;
остаются остальные причины отказа, включая пустой country code, LTE blacklist,
отсутствие peer dongle и неготовый SIM state.

Параллельно уже в `V55.35.00.05` присутствуют TLV tag `9`, строка
`DONGLE_INFO_TAG_DONGLE_DISPLAY_CONTROL_INFO` и поле `need_display`:

- parser начинает принимать dongle subtag `0..9`, тогда как build `139`
  принимает только `0..8`;
- `construct_dongle_info()` добавляет tag `9`, length `1`, со значением
  `need_display`;
- значение передаётся в event `0x1850` в сторону app host.

Это объясняет сразу две наблюдаемые части нового поведения: отказ LTE auth вне
`CN` и явную передачу приложению признака, нужно ли показывать dongle.

| Claim | Level | Artifact/location | Evidence | Confidence |
|---|---|---|---|---|
| OpenFCC устанавливает старый полный controller build `139` | OBSERVED | OTA metadata и payload manifest | full A/B OTA, 29 partitions, DJI certificate | High |
| Region gate появился после build `139`, но не позднее официальной `0400` | OBSERVED | `dji_lte:set_liblte_auth_info` | ветка отсутствует в `139`, присутствует в `V55.35.00.05`, `440` и `576` | High |
| Gate блокирует auth вне `CN` | DERIVED | `set_liblte_auth_info` + `lte_get_country_region` | `true && region==1` ведёт к error path до `liblte_set_param(AUTH_PARAM)` | High |
| Новая прошивка сообщает app поле `need_display` | OBSERVED | dongle TLV parser/constructor, event `0x1850` | новый tag `9`, length `1`, parser range `0..9` | High |
| Одного изменения JSON на `false` достаточно для штатной работы 4G | HYPOTHESIS | read-only `/system/etc/lte_cfg.json` | auth gate выключится, но остаются pairing, SIM, peer dongle и certificate checks | Medium |

Свойство `persist.rc.country.is_in_eu`, также появившееся в новых builds, не
является этим барьером. Его проверенные call sites подавляют отправку части
диагностических сообщений в blackbox; LTE initialization оно не запрещает.

Для точного определения первого затронутого релиза нужен официальный
`rc520_0205` build старше `01.00.0400`, но новее датированного 2025-03-18 build
`139`. Для проверки bypass без полной замены OTA наиболее информативен
контролируемый runtime-тест с тем же build `576`, но с
`dongle_display_control=false`; он требует отдельного безопасного способа
подмены read-only config/verified system и здесь не выполнялся.

## Предварительный анализ RC 2 (`rc331`)

Отдельно начат анализ скачанного официального bundle RC 2; его данные нельзя
переносить на RC Pro 2 (`rc520`) без проверки.

| Поле | Значение |
|---|---|
| Исходный artifact | `V10.00.0700_rc331_dji_system.bin` |
| SHA-256 bundle | `1778183d5a742bbacd77567bf7b33ec9d6927c0edc963437da684767e401d058` |
| Внешняя версия | `10.00.0700`, `antirollback=7`, `enforce=0` |
| Модуль Android | `rc331_0205_v00.01.14.99_20260609.pro.fw.sig` |
| SHA-256 signed module | `f707cf3dc0be2894b111ce4973d0206e896a2c7e9c4ebe43de1040b528cf49ce` |
| Внутренняя OTA | `DJI/rc331/rc331:11/V00.01.14.99/11499:user/test-keys` |
| OTA timestamp | `2026-06-08T16:59:09Z`, metadata Unix timestamp `1780937949` |
| OTA layout | полная A/B OTA, 29 partitions, `pre-device=rc331` |

Внешняя IMAH signature модуля `0205` успешно проверена опубликованным
`PRAK-2020-01`; checksum зашифрованных и распакованных данных совпал. Внутри
находится стандартная A/B OTA с `payload.bin`, metadata и
`META-INF/com/android/otacert`. Файл `otacert` побайтно совпадает с RC Pro 2
builds `139/5/440/576`:

```text
SHA256 b176a297c6bcc436b8149a7311627484d18c8e9d3ed2bc67c68b2af5347670e1
subject O=DJI, CN=DJI
fingerprint 52:32:A1:44:C8:4A:04:EA:34:02:A0:27:BF:AD:76:B4:F3:21:E0:C3:5D:0F:F5:C8:82:6E:D7:76:39:85:54:EB
```

Маркер `user/test-keys` заметно отличается от RC Pro 2
`user/release-keys`, но сам по себе не доказывает, что RC 2 примет OTA,
подписанную произвольным test key: доверенные OTA/AVB keys и runtime policy
нужно проверять отдельно. На следующий сеанс остаются извлечение partitions,
проверка `ro.ota.allow_downgrade`, `ro.debuggable`, AVB key descriptors,
`dji_lte`/init services и доступных способов временного bind/overlay.

## Граница с FreeFCC и OpenFCC

Текущий FreeFCC не выполняет перечисленные Fibocom AT-команды. Его experimental
4G profile строит 128 DUML frames и пишет их в abstract DUSS socket
`/duss/mb/0x205` без ACK/readback. Это aircraft/controller command path, а не
настройка cellular bearer.

Исследованный OpenFCC Windows launcher имеет отдельный `fourg_swap` flow:
скачивает DJI-signed controller `update.zip`, передаёт его на пульт и применяет
через `update_engine_client`. После этого OpenFCC APK отправляет защищённые 4G
activation/heartbeat frames. Controller OTA нельзя смешивать с AT-настройкой
Fibocom.

Скачанный пользователем `OpenFCC-Setup-Windows.exe` побайтно совпал с
исследованным официальным artifact:

```text
SHA256 50bc1e3a1ac532af4ed54a0057960610e619ddfe0246233f134a5dd02d26b741
```

## Источники

- Fibocom Technical FAQ: `https://www.fibocom.com/en/problems/index_itemid_977_page_%7B%24wtl_pager%7D.html`
- FIBOCOM NL668 AT Commands User Manual, mirror:
  `https://device.report/m/a5e1582fe8b5d9da1208e235921c7255f45bbb07b4b6cb3751e9c282033b1574`
- OpenFCC Apps: `https://openfcc.app/apps`
- FreeFCC 4G code: `Profiles.load4g()`, `DumlTransport.sendFramesUnix()`,
  `FccViewModel.send4gActivationFrames()`.
