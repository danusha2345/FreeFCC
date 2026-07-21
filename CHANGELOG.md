# История изменений

## 1.5.29 — 2026-07-21

- Интервал повторного открытия Home Point stream увеличен с 5 до 10 секунд,
  чтобы DJI Fly получал более длинное окно для восстановления controller link.
  Проверки продолжаются до достоверного Home Point либо явной отмены.
- Во время ожидания Home Point появилась кнопка **Cancel Auto FCC**.
  Остановить monitor также можно действием **Cancel Auto FCC** в системном
  уведомлении, не возвращаясь из DJI Fly в FreeFCC.
- Кнопка **Connect** переименована в **Auto FCC** и запускает Connect с
  ожиданием Home Point. Сразу под ней доступна ручная **Send FCC Request**;
  после запуска Auto FCC она скрывается, и до завершения либо отмены monitor
  показывается только **Cancel Auto FCC**.
- После замены батареи дрона новый **Auto FCC** можно запустить и из состояния
  предыдущего успешного FCC request. Старое process-local write evidence больше
  не оставляет пользователя только с ручным re-send.
- После успешного Auto FCC Connect и запуска Home Point monitor приложение
  автоматически переводит фокус в DJI Fly. Ручная отправка и LAN `connect`
  сохраняют прежнее поведение и не запускают flight app.

## 1.5.28 — 2026-07-20

- Все долгоживущие DUML-операции теперь сериализованы по точному localhost-
  порту. Home Point listener, ручной FCC/CE, LAN capture/request и служебные
  probes больше не открывают конкурирующие сессии на одном proxy port.
- После **Connect** приложение один раз читает фактическое состояние LED, а
  service log сразу появляется и в UI, и в LAN. LAN bridge повторно проверяет
  Wi-Fi binding при возвращении в приложение.
- RC2 telemetry suffix вида `FA…` теперь распознаётся и показывается как S/N,
  но намеренно не принимается 4G-профилем без полного `1581…` serial или
  разрешённого `WA/WM` model code.
- Удалены неиспользуемые Boot/Auto-FCC startup coordinator и скрытая Support-
  страница. Основной flow остаётся однозначным: **Connect → Home Point → один
  полный FCC apply**.
- Интерфейс уплотнён для небольших экранов пультов: сокращены верхние поля,
  интервалы и вертикальные padding карточек без уменьшения кнопок. Неактивная
  4G-иконка больше не держит бесконечную анимацию.
- Статус `Session ready` теперь означает живую process-local Connect-сессию,
  а не доказанный физический RF mode. Результат FCC по-прежнему нужно проверять
  в DJI Fly.

## 1.5.27 — 2026-07-20

- RC Pro 2 (`rc520`) Home Point listener теперь использует широкий telemetry
  stream на pinned Connect port `40009`, где live Air 3S capture стабильно
  публикует `03:44`; остальные контроллеры сохраняют path `40007`.
- Relayed route `0xA2 → 0x82` принимается для Home Point только в явно
  выбранном RC Pro 2 path. CRC, push type, `03:44`, payload length и
  `home_state` validation остаются обязательными.
- На всех поддерживаемых пультах listener теперь ждёт до
  `home_state=true` или явной отмены, включая временные разрывы stream.
  Повторное подключение выполняется через 5 секунд; частые открытия `40007`
  физически нарушали radio link в RC2 hardware test.
- Чистый hardware test зафиксировал исходный дефект: listener завершился
  `monitor_failed` до Home Point, затем `03:44=0x0047` появился при AUTO
  attempt `null` и физическом CE. Ручной recovery в той же сессии записал
  `42/42` и сменил SDR config `09:21` с 29 на 31 B.

## 1.5.26 — 2026-07-20

- Успешный ручной FCC recovery теперь очищает старый terminal
  `monitor_failed`: UI показывает результат последней успешной операции, а
  исходный Home Point failure остаётся в process log.
- Live `1.5.25` подтвердил: при уже записанном Home Point три отдельных
  `40007` окна не получили `03:44`; контрольный capture дал 31 валидный frame,
  включая `03:43`, но без `03:44`. Последующий manual apply на pinned `40009`
  записал 42/42 кадра за 2,876 s и физически включил FCC.
- Маркированный same-session CE/FCC capture выявил сильный кандидат readback:
  ground-side `09:43` изменился `0000 → 0200` после полного apply. До
  обратимого повтора и независимой проверки Transmission это документировано
  как корреляция, а не как подтверждённая семантика команды.
- Cross-model Air 3S + RC Pro 2 проверка опровергла универсальность кандидата:
  `09:43=0000` наблюдается и в CE, и в физически подтверждённом FCC. Для этой
  связки `09:21` доказывает смену SDR config shape, но не является отдельным
  RF-power boolean. Добавлена полная live-карта: неоднозначный первый reboot и
  чистый cold-boot failure, где listener завершился до Home Point и AUTO apply
  не начинался.

## 1.5.25 — 2026-07-20

- Автоматический FCC после `Home Point=true` теперь ждёт 2 секунды завершения
  региональной инициализации, заново строит полный профиль `21 × 2` и отправляет
  его строго на DUML-порт, найденный явным Connect. Повторное авто-сканирование
  с возможным уходом на `40007` исключено.
- LAN status и process log различают `HOME_POINT_AUTO` и `MANUAL`, показывают
  порт, `flushed/expected`, число matching ACK и точное время попытки. Даже
  `42/42` означает только запись в proxy; физический RF mode остаётся unknown.
- Кнопка Back теперь сворачивает FreeFCC вместо уничтожения Activity, поэтому
  Connect/ViewModel и LAN controller сохраняются, пока жив процесс Android.
- Зафиксирован protocol verdict: в сохранённом потоке нет доказанного CE/FCC
  бита. `06:21` остаётся неподтверждённым getter-кандидатом; `06:72 payload=00`
  является ACK, а JSON `i:39` кодируется как wire `09:27`, не `09:39`.

## 1.5.24 — 2026-07-20

- Home Point parser теперь принимает CRC-valid `03:44` и из прямого DUML
  stream, и из envelope `55 cc 30 75`. Фрагментированные и смешанные потоки
  покрыты тестами; listener по-прежнему не пишет primer/query/refresh.
- Live `1.5.23` подтвердил короткий пассивный broker window: 19 валидных кадров
  и clean EOF примерно за 2 секунды, при этом в конкретной выборке был `03:43`,
  но не было `03:44`. EOF не считается потерей основного controller Connect.
- Успешный explicit Connect хранится отдельно от lifecycle Home Point monitor.
  После обычного закрытия и повторного входа в Activity состояние Connect
  сохраняется в текущем процессе даже после FAILED/STOPPED monitor; реальный
  неуспешный повторный controller probe очищает это состояние.
- `clearWriteEvidence()` отделён от `beginHardwareSession()`: CE restore очищает
  только доказательство FCC write и больше не может создать ложный Connect.
- Периодический reconnect не добавлен: `03:43` отражает GPS readiness, но не
  доказывает Home Point, а частые открытия `40007` требуют отдельного bench A/B.

## 1.5.23 — 2026-07-20

- Home Point listener теперь полностью пассивный: один socket `40007`, ни
  одного primer/query/refresh write. Live A/B на RC2 показал, что именно
  read-only socket сразу получает полный telemetry stream.
- Пассивный live capture набрал 128 CRC-valid кадров примерно за 1 секунду,
  включая два `03:44` от `0x0E` с payload 102 B и `home_state=0x0047`
  (`Home Point=true`).
- Подтверждена причина регрессии `1.5.22`: записанный primer ограничивал broker
  window примерно двумя секундами и мог не получить ни одного `03:44`.
- Ошибка Home Point monitor больше не превращает успешный controller Connect в
  ложный `Disconnected`. Для отдельного повтора отображается
  `Retry Home Point`; manual/LAN monitor start не может создать Connected без
  успешного transport probe.

## 1.5.22 — 2026-07-20

- Пользовательский flow упрощён: открыть FreeFCC → нажать `Connect`. После
  успешного Connect приложение само ждёт достоверный текущий `Home Point=true`,
  один раз отправляет полный FCC profile и останавливает listener.
- Отдельный Auto-FCC toggle, boot autostart и автоматический запуск DJI Fly
  удалены из пользовательского flow. Старый preference очищается при запуске.
- До явного `Connect` fresh launch не открывает DUML socket. Ошибка listener
  возвращает видимую кнопку `Retry Connect`; сервис работает как non-sticky и
  не восстанавливает незавершённый запрос после перезапуска процесса.
- Предварительный `Home Point=false` больше не требуется. Он только разрешает
  единственный recovery, если уже установленный stream оборвался.
- После отрицательного RC2 A/B-теста `1.5.21` Home Point refresh сохраняет тот
  же TCP socket и cadence 1 Hz, но каждый primer получает следующий DUML
  sequence и пересчитанный CRC. Повтор полностью одинакового frame не продлил
  broker window: соединение снова закрылось примерно через 2 секунды.
- EOF по-прежнему terminal, reconnect не добавлен. Тест проверяет тот же socket,
  последовательный sequence и валидные CRC каждого refresh.

## 1.5.21 — 2026-07-20

- Версия в UI, LAN status, логах и update comparison теперь берётся напрямую
  из `BuildConfig.VERSION_NAME`. Отдельная hardcoded строка больше не может
  отстать от реальной APK metadata.
- Исправлен обнаруженный в `1.5.20` симптом: после успешной установки новый APK
  показывал `1.5.19` и снова предлагал обновление, хотя transport fix уже был
  установлен.

## 1.5.20 — 2026-07-20

- Home Point monitor больше не ждёт неподтверждённый matching response на
  active `03:44`: он принимает только пассивные CRC-valid push-фреймы
  `0x0e → 0x02`, `cmdType=0x00`.
- Один wrapped `03:44` теперь повторяется раз в секунду в том же TCP-сокете
  `40007`, чтобы проверить и поддержать broker telemetry window без опасного
  цикла новых connections. DUML sequence и весь wire-frame переиспользуются.
- EOF и write failure остаются terminal fail-closed: скрытого reconnect loop
  нет. После `Home Point=true`, Auto-FCC OFF или service yield сокет закрывается
  до FCC write.
- JVM-тесты проверяют refresh на том же соединении как при непрерывной
  телеметрии, так и после read timeout.

## 1.5.19 — 2026-07-20

- Auto-FCC больше не открывает Home Point listener до UI/serial probe и запуска
  DJI Fly. При успешном foreground launch monitor ждёт фактический
  `MainActivity.onStop()`; при LAN-команде из уже фонового FreeFCC стартует без
  ожидания повторного lifecycle callback.
- Persistent marker считается живым monitor только вместе с process-local
  `STARTING/RUNNING`; stale marker проходит новый flight-app handoff.
- Проверка `auto_fcc` и service start атомарны с `stop()`, поэтому поздний
  background callback не может воскресить monitor после выключения Auto-FCC.
- Автоматические serial/LED probes исключены из Auto-FCC startup. Startup LED
  gate атомарно блокирует новые и отложенные readback после включения Auto-FCC;
  ручные LED-команды не изменены.
- Terminal Home Point error теперь содержит номер соединения, состояние
  `armed` и факт расходования recovery budget для следующего live-теста.

## 1.5.18 — 2026-07-19

- После подтверждённого `Home Point=false` Auto-FCC может один раз восстановить
  разорванный `40007` stream, сохраняя тот же session gate. Это закрывает live
  случай, когда broker разорвал stream около перехода Home Point и `1.5.17` не
  отправил FCC.
- Recovery budget расходуется до переподключения: второй disconnect либо
  `CONNECT_FAILED` после него завершается fail-closed без третьего socket open.
- Initial `recorded=true` по-прежнему не принимается без ранее увиденного
  `false`; unarmed disconnect не переподключается.
- EOF во время контролируемой уступки порта LED/serial теперь считается
  cooperative stop и не расходует reconnect budget.
- Выключение Auto-FCC сразу очищает terminal-сообщение в UI. Persistent toggle
  остаётся durable autostart-настройкой; повторный вход в FreeFCC является
  явной новой попыткой после terminal failure.
- Устранена гонка старого loopback-теста частичной отправки профиля.

## 1.5.17 — 2026-07-19

- `keepalive_running` больше не запускает Home Point monitor, если
  пользовательский toggle `auto_fcc` выключен. Устаревший marker очищается при
  входе в приложение и при sticky restart сервиса.
- Home Point принимается только как свежий переход `not recorded → recorded`
  текущего запроса. Начальный snapshot `recorded=true` больше не запускает FCC.
- Неожиданный разрыв установленного `40007` stream завершает monitor
  fail-closed. Бесконечного reconnect-цикла больше нет; только initial connect
  имеет одну медленную повторную попытку.
- Listener запускается сразу, чтобы не потерять быстрый Home Point; один session
  gate сохраняется при контролируемой уступке порта LED/serial probe.
- UI больше не называет TCP-write подтверждённым FCC и не утверждает `DEFAULT`
  без readback: отображаются `FCC REQUEST SENT` либо `RF MODE UNKNOWN`.
- Terminal error Home Point monitor больше не затирается поздним
  startup-сообщением.

## 1.5.16 — 2026-07-19

- Auto-FCC ждёт подтверждённый Home Point в одном долгоживущем `40007`
  listener, затем один раз отправляет полный FCC profile и завершает service.
- Старый Auto-FCC worker больше не может остановить, перезапустить или затереть
  состояние нового запроса; worker запускается только после доставленного
  Android `ACTION_START`.
- Автоматический повтор разрешён только если preflight connection не состоялся
  до начала profile frame loop. Возможная частичная запись не повторяется
  скрытно.
- Home Point parser принимает только подтверждённый live-потоком
  `cmdType=0x00`.
- Startup LED read повторяется один раз только при конфликте за port до начала
  wire exchange. После LED ON/OFF выполняется отдельный readback.
- LAN `wire_exchange` теперь отличает `EOF`, deadline, byte limit и mid-stream
  I/O error; при read error сохраняется полученный partial prefix.
- Ошибка запуска foreground service из `BootReceiver` больше не завершает boot
  receiver и сохраняет Auto-FCC request для следующего foreground retry.

Физический RF mode по-прежнему не читается через доступный localhost proxy:
успешная запись profile не равна подтверждённому FCC. Проверяйте Transmission
graph в DJI Fly.
