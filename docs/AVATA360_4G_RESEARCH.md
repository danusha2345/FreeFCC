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
| Socket connect не даёт ACK и не доказывает физический тип модема или активацию | DERIVED | API используется как fire-and-forget; response/readback отсутствует |
| На связанном Avata 360 с RC2 endpoint `/duss/mb/0x205` reachable | OBSERVED | Live probe через FreeFCC 1.5.6 и LAN log, 2026-07-18 23:03 MSK |
| В первой live-пробе identity не найден: ни `1581...`, ни `WA/WM` | NEGATIVE | Guard остановил flow до отправки 128 кадров; фактических 4G writes не было |
| LED profile работает на Avata 360; `LED OFF` также гасит индикаторы батареи | OBSERVED | Live sequence `ON`, `OFF`, `OFF`; визуальное подтверждение пользователя и LAN write-log, 2026-07-18 23:04-23:05 MSK |
| Имеющиеся локальные Mavic 4 / Air 3 / Air 3S / WA234 дампы относятся к внешнему cellular path | OBSERVED | Локальные notes и corpus; пользователь подтвердил topology |
| Отдельный Avata 360 eMMC dump в `/mnt/toshiba_6tb/dumps_emmc_dji` по имени не найден | NEGATIVE | Read-only поиск на `homesrv`, 2026-07-18 |

## Гипотезы

| Гипотеза | Что подтвердит | Что опровергнет |
|---|---|---|
| Встроенный eSIM использует тот же DUSS endpoint `0x205` | Socket reachable только при связанном Avata 360 и соответствующие DUSS/LTE логи | Endpoint отсутствует во всех валидных состояниях Avata 360 |
| 128-frame профиль подходит без изменения | После write burst DJI Fly показывает активный 4G link; желательно capture request + state transition | Кадры записываются, но link/state не меняется или появляются model-specific errors |
| Avata 360 отдаёт полный `1581...` S/N через localhost DUML proxy | `probeSerial()` фиксирует свежий полный S/N | В capture присутствует только другой model identifier, например `FCA188` |
| Для Avata нужен другой activation/auth flow | DJI Fly traffic показывает auth/code/cloud calls или другие `0x51` cmd IDs перед включением | Полный внешний-module flow побайтно совпадает и работает |

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
  подписан как `ON/OFF command sent`. Для реального readback подготовлен безопасный
  raw probe `03:F8`, payload `a259ceed`, direct port `40009`.
- По коду `android-lamp-off` подтверждён альтернативный model-index path:
  `03:E1` metadata -> `03:E2` read -> `03:E3` write -> новый `03:E2` verify.
  Для Avata 360 индекс lamp пока неизвестен, поэтому первым проверяется hash-read.
- Добавлен fallback запуска `com.dji.industry.pilot` (DJI Pilot 2) после
  неизменённых DJI Fly и DJI Go 4 путей для RC Plus.

## План live-проверки

1. Подключить Avata 360 к RC2 через OcuSync и оставить DJI Fly запущенным.
2. В FreeFCC нажать **Connect**, записать найденный identity и controller port.
3. Убедиться, что вкладка **Log** показывает `Ready`; Codex обнаружит RC2 по
   UDP-маячку и подключится к HTTP endpoint с известным паролем.
4. Нажать только **Probe 4G Endpoint**; получить логи через LAN.
5. Сравнить три состояния: дрон выключен, дрон связан без Enhanced Transmission,
   дрон связан и штатный 4G включён в DJI Fly.
6. Только после сохранения baseline и явного подтверждения пользователя отправить
   128-frame burst. Проверить DJI Fly state, HMS errors и сетевой link до/после.

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
| 2026-07-18 | Source indexes | GitNexus + local/global Graphify | n/a | n/a | GitNexus: 913 nodes / 2,209 edges / 78 flows; local Graphify: 292 nodes / 516 edges; global graph: 17,135 nodes / 47,807 edges. Проверены пути `FreeFCC -> DUML:03:E2 -> fpga_tang_nano_9k_card_reader-spinal` и `FreeFCC -> DUML:06:72` |
