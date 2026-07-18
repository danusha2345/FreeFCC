# Код-ревью коммита a4e4305 — «fix: harden FCC lifecycle and add DUML capture»

Дата: 2026-07-19. Метод: 8 независимых «искателей» (построчный скан диффа, аудит удалённого поведения, межфайловый трейсер, reuse, simplification, efficiency, altitude, конвенции CLAUDE.md) → дедупликация → по одному верификатору на каждого кандидата с вердиктом CONFIRMED / PLAUSIBLE / REFUTED.

Скоуп: `git diff HEAD~1` (9 файлов: DumlTransport.kt, FccKeepaliveService.kt, FccViewModel.kt, LanControl.kt, тесты, docs).

Итог: **8 подтверждённых багов**, 2 PLAUSIBLE, 1 опровергнут, плюс блок cleanup-замечаний.

---

## Подтверждённые баги (по убыванию серьёзности)

### 1. `sendAndReceiveRaw` держит HardwareLock весь таймаут — keepalive голодает
`app/src/main/java/com/freefcc/app/DumlTransport.kt:343`

Новый цикл consume-until-match заменил быстрый возврат `response_validation_failed` после первого кадра на чтение кадров до истечения всего `readWindowMs`. `handleLanDuml` держит `beginHardwareOp()` через весь вызов; `timeout_ms` по умолчанию 3000, до 10000. Keepalive-тик ретраит лок только 10×200мс = 2с — тик пропускается, и DJI Fly получает многосекундное окно, чтобы сбросить радио в CE.

**Сценарий**: LAN-клиент шлёт `duml_request` команде, чей ответ не проходит строгую валидацию (route/seq mismatch) на фоне телеметрии брокера → вызов крутится 3–10с с локом → keepalive-запись пропущена. Вдобавок при истечении возвращается `lastCompleteFrame` (произвольная телеметрия), а не реальный несовпавший ответ.

**Рекомендация**: ограничить время удержания лока (или fast-fail после N несовпавших кадров), возвращать фактический несовпавший ответ.

### 2. Устаревший флаг `fcc_sequence_written` даёт ложный `fcc_enabled`
`app/src/main/java/com/freefcc/app/FccViewModel.kt:271` (также :675)

Флаг сбрасывается ровно в одном месте — при успешном `disableFcc` (FccViewModel.kt:615). Не сбрасывается при disconnect, старте keepalive, перезагрузке дрона.

**Сценарий А**: вчера FCC применён (флаг true), дрон перезагружен и вернулся в CE; сегодня достаточно подключения к прокси — `connect()`/`restoreConnectionIndicator` ставит `status=fcc_enabled` / `isFccEnabled=true` (LAN API отдаёт это на строке 1242), хотя радио в CE.

**Сценарий Б**: `refreshFccWrittenStateAfterKeepaliveStart` (строка 675) читает тот же флаг через 750мс после старта и показывает «FCC sequence written», даже если все отправки этой сессии провалились — вопреки собственному doc-комментарию «only after the service records an actually completed write».

**Рекомендация**: хранить не только факт записи, но и сессионный/временной контекст (например, timestamp записи + сброс при новом подключении), либо явно маркировать состояние как «последняя известная запись», а не «FCC включён».

### 3. `handleLanDumlCapture` без HardwareLock и busy-guard блокирует LAN-пул до 10с
`app/src/main/java/com/freefcc/app/FccViewModel.kt:1484`

В отличие от `handleLanDuml` (`beginHardwareOp` → 409 `hardware_busy`), capture идёт сразу в `transport.captureFrames` и работает параллельно с keepalive/apply на том же порту брокера: вывод capture загрязняется собственным трафиком приложения, а конкурентная запись может потерять ACK в своём 80мс окне. Пул LAN-сервера — `ThreadPoolExecutor(MAX_CLIENTS=4)` + `ArrayBlockingQueue(8)` + `AbortPolicy`: четыре параллельных capture по 10с занимают все воркеры, остальные запросы API (ping, status, keepalive control) отклоняются.

**Рекомендация**: single-flight-гард по образцу `ledOperationBusy` (409 `capture_busy`) + аренда HardwareLock.

### 4. `captureFrames`: busy-spin на 100% CPU после EOF
`app/src/main/java/com/freefcc/app/DumlTransport.kt:422`

После закрытия сокета брокером `read()` мгновенно возвращает -1, `readBytesUntilDeadline` возвращает null без ожидания, а чтение magic-байта делает `?: continue` без детекта EOF и без backoff — цикл крутится на полной скорости до deadline (до 10с) на потоке LAN-обработчика. EOF не бросает исключение, `catch IOException` не спасает.

**Рекомендация**: различать EOF (`n == -1`) и таймаут в `readBytesUntilDeadline`, при EOF выходить из capture.

### 5. `captureFrames`: 250мс тишины после 0x55 обрывает весь capture
`app/src/main/java/com/freefcc/app/DumlTransport.kt:431` (и :445 для body)

`readBytesUntilDeadline` возвращает null уже после одного `SocketTimeoutException` (`soTimeout = min(remaining, 250)`), не ретраит до deadline. Случайный байт 0x55 + 300мс тишины перед реальной телеметрией → чтение header-tail отдаёт null, `?: break` завершает цикл — API возвращает 0 кадров менее чем за секунду при запрошенных 10с.

**Рекомендация**: здесь нужен `continue`-ресинк, а не `break` (break оправдан только при истечении общего deadline).

### 6. Единый дедлайн на header+body сжал бюджет чтения (регрессия против per-read soTimeout)
`app/src/main/java/com/freefcc/app/DumlTransport.kt:324`

Старый код ставил `soTimeout = readWindowMs` один раз, и каждый blocking read получал свежее окно (эффективный бюджет до N×readWindowMs). Новый `deadlineNanos` один на всё, включая цикл пропуска телеметрии. Самый уязвимый вызов — device-info с `read_window_ms` по умолчанию **80мс**: теперь это 80мс суммарно; поздний header оставляет ~0мс на body → частичный кадр, `validatedPayload=null`, probe не находит серийник.

**Рекомендация**: пересмотреть дефолтные окна вызовов под новую семантику (device-info 80 → ≥250–500) либо давать body отдельный минимальный бюджет.

### 7. Ресинк после ложного 0x55 съедает 10 байт без повторного скана — кадры теряются
`app/src/main/java/com/freefcc/app/DumlTransport.kt:437`

При подключении в середине потока (норма для captureFrames) хвост чужого кадра `...0x55 A1 A2` перед реальным кадром: ложный magic триггерит чтение 10 байт headerTail, в которых лежат первые байты реального кадра; crc8 не сходится, `continue` возобновляет скан после съеденных байтов — заголовок реального кадра потерян. Любой 0x55 в payload/CRC повторяет это: тихая потеря кадров в диагностическом инструменте.

**Рекомендация**: при провале валидации ресканировать уже прочитанные 10 байт на следующий 0x55 (pushback-буфер).

### 8. Частичный фрагмент вытесняет сохранённый `lastCompleteFrame`
`app/src/main/java/com/freefcc/app/DumlTransport.kt:365`

`lastCompleteFrame` возвращается только когда `readBytesUntilDeadline` отдал null (ноль байт); все partial-пути (строки 351, 359, 362, 365, 368) возвращают фрагмент или голый 11-байтовый header, не глядя на `lastCompleteFrame`. Полный чужой кадр пришёл в t=100мс, следующий кадр разрезан дедлайном → LAN API отдаёт бессмысленный фрагмент как `response_hex`, а полный захваченный кадр — улика, ради которой цикл писался — молча выброшен. Каверза: фрагмент может быть и опоздавшим совпадающим ответом (это покрыто тестом `rawExchangeRetainsPartialResponseOnBodyTimeout`) — стоит возвращать оба.

---

## PLAUSIBLE (механизм реален, триггер узкий)

### 9. Неудачный старт foreground-сервиса навсегда стирает интент keepalive
`app/src/main/java/com/freefcc/app/FccViewModel.kt:237`

`start()` использует `startForegroundService` (targetSdk 35); на Android 12+ из фона бросает `ForegroundServiceStartNotAllowedException`. Catch в init вызывает `clearRunRequest` → `PREF_KEEPALIVE=false` навсегда: последующее открытие приложения не восстановит keepalive. Триггер узкий (init ViewModel почти всегда совпадает с выходом в foreground), но широкий `catch (e: Exception)` превращает любую транзиентную ошибку в тихую потерю пользовательского интента.

**Рекомендация**: не стирать преф при транзиентных ошибках старта; переносить откат внутрь `FccKeepaliveService.start` (ставить преф только после успешного старта).

### 10. Рассинхронизация UI при самостоятельной остановке сервиса
`app/src/main/java/com/freefcc/app/FccViewModel.kt:231`

`onStartCommand` может самозавершиться (`cachedFrames == null` → сброс префа + `stopSelf`), но обратного канала к ViewModel нет — UI остаётся с `isKeepaliveRunning=true`, `startKeepalive()` отказывает («already running»). Триггер нереалистичен (требует отсутствующего бандл-ассета `profiles/fcc_keepalive.json`), поведение существовало до этого коммита; `stopKeepalive()` сбрасывает флаг, так что дедлока нет. Латентная несогласованность на будущее: сервису нужен способ сообщать своё фактическое состояние (StateFlow/broadcast/pref-listener).

---

## Опровергнуто

### «Бёрст» keepalive-записей после удаления стартовой задержки — REFUTED
`app/src/main/java/com/freefcc/app/FccKeepaliveService.kt:154`

Механика реальна (leading `delay(INTERVAL_MS)` удалён, init шлёт ACTION_START, onStartCommand отменяет и перезапускает job), но вреда нет: каждый `sendFrames` гейтится `HardwareLock.tryBegin()` с ретраем 200мс, отменённый job освобождает лизу в `finally` только после завершения блокирующей отправки — записи полностью сериализованы. Немедленная первая запись — намеренное изменение с явным комментарием (закрыть окно, в котором DJI Fly восстанавливает CE); `init()` защищён флагом `initialized` и срабатывает один раз на инстанс ViewModel.

---

## Cleanup (не вошло в основной отчёт из-за лимита, но стоит сделать)

- **Мёртвый код**: приватный `readBytes` в `DumlTransport.kt:657` — ноль вызовов после миграции на `readBytesUntilDeadline`. Удалить, иначе следующий контрибьютор возьмёт его и вернёт баг с продлением дедлайна.
- **Тройное дублирование wire-формата DUML**: структурная валидация кадра (magic, 11-битная длина, crc8 заголовка, crc16 хвоста) теперь живёт в `DumlBuilder.validateResponse` (:175-202), в цикле `sendAndReceiveRaw` и в `captureFrames` (:434). Причём пути уже расходятся: sendAndReceiveRaw принимает кадры без CRC-проверки, captureFrames требует crc8+crc16. Вынести общий `readOneFrame(input, deadline, validate)`-хелпер.
- **Разбор заголовка в ViewModel**: `handleLanDumlCapture` (FccViewModel.kt:1493-1500) декодирует поля кадра хардкод-офсетами (`frame[4]`, `frame[5]`, ...) и пять раз копипастит `"0x%02x".format(...)`. Добавить `DumlBuilder.parseHeader(frame)` и хелпер `byteToHex` рядом с `bytesToHex`.
- **Тройная конверсия nanos→ms**: `remainingTimeoutMs()` в sendAndReceiveRaw, расчёт `timeoutMs` в captureFrames и внутренности `readBytesUntilDeadline` повторяют одну и ту же формулу `(nanos + 999_999) / 1_000_000` с коэрсингом. Оставить одну — внутри `readBytesUntilDeadline`; pre-check'и `if (timeout <= 0) return` дублируют её проверку и могут разъехаться.
- **`optionalCaptureDuration` — клон `optionalTimeout`** (LanControl.kt:71): тот же диапазон 100..10_000, тот же дефолт 3000, отличается только имя параметра; границы 100..10000 / 1..128 ещё раз повторены в `require()` внутри `captureFrames` и в docs. Один generic `optionalInt(params, name, min, max, default)` + общие константы границ.
- **Копипаст try/start/refresh/catch вокруг `FccKeepaliveService.start`** в init (:232-240) и `startFccKeepalive` (:653-661), плюс `clearRunRequest()` — дословная половина `stop()`. Один приватный хелпер; ещё лучше — перенести откат префа внутрь `start()` (см. пункт 9).
- **Дублированная проекция состояния fcc_enabled/сообщений**: логика `isFccEnabled`/status/message из `wasFccSequenceWritten` повторена с вариациями в init (:215), connect (:271) и refresh (:672) и уже расходится (init не ставит message). Один `applyFccWrittenState(connected)`.
- **`setFccSequenceWritten(true)` на каждом успешном тике** (FccKeepaliveService.kt:178): каждые 2с — editor + `apply()` + enqueue в QueuedWork (диск не трогается, AOSP скипает неизменённые значения, но работа и дренаж QueuedWork при остановке сервиса лишние). Локальный once-флаг.
- **По-байтовый ресинк в captureFrames** (:416): setsockopt + read + аллокация 1-байтового массива на каждый входной байт. `BufferedInputStream` или переиспользуемый буфер + обновление soTimeout только при изменении значения.
- **`delay(750)` + одноразовый опрос префа** в `refreshFccWrittenStateAfterKeepaliveStart`: первая запись keepalive может легитимно занять до ~2с (10×200мс ретраев лока) — одноразовый опрос через 750мс часто промахивается. Наблюдаемое состояние (StateFlow/OnSharedPreferenceChangeListener) вместо магического сна.

## Нарушения конвенций CLAUDE.md

Не найдено.
