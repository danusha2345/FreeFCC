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
