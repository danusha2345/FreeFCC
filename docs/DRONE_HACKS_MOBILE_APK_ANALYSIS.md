# Статический разбор Drone-Hacks Mobile APK

Дата анализа: 2026-07-24.

## Краткий вывод

Drone-Hacks Mobile и FreeFCC решают одну пользовательскую задачу — включение
FCC, — но технически это разные реализации.

- FreeFCC работает на пульте и отправляет локально хранящийся составной
  DUML-профиль через DJI proxy.
- Drone-Hacks Mobile работает на отдельном Android-устройстве, подключённом
  непосредственно к дрону по USB. Параметры FCC-команды приложение получает
  от сервера Drone-Hacks для конкретного устройства, кэширует и передаёт через
  общий механизм `SendCustomPacket`.

По одному APK нельзя достоверно назвать точные `cmdSet`, `cmdId` и `payload`
One-Shot FCC: эти три значения не зашиты в приложение и приходят в ответе
закрытого API.

## Исследованный артефакт

| Поле | Значение |
|---|---|
| Исходный файл | `/home/danik/Downloads/release-22baaacc.apk` |
| Размер | 89 211 250 байт |
| SHA-256 | `4694dd10fb78d1c317906b4bac15aa8e6aaa9096a941bd7f93388068c6a955dd` |
| Package | `com.dronehacks.client` |
| Version | `1.0.1` (`versionCode=1000001`) |
| Android | `minSdk=24`, `targetSdk=34`, `compileSdk=34` |
| Подпись APK | APK Signature Scheme v2 |
| Subject сертификата | `CN=Drone Hacks, OU=Drone Hacks, O=Drone Hacks` |
| SHA-256 сертификата | `02ecb1acce6743da2dd4cd5a6d0082f46920e76363ad980d5871e5e068d9c7dd` |

Подпись криптографически корректна, но сертификат самоподписанный и
идентифицирует Drone Hacks, а не DJI. Это не DJI-системное приложение и не APK,
подписанный platform key пульта.

Основная логика находится в stripped Rust-библиотеках:

| ABI | Размер | SHA-256 |
|---|---:|---|
| `arm64-v8a/libdrone_hacks_lib.so` | 21 899 512 | `db1c5ef85f69c3ae3fc19620d3a3e88552db5f32e94c92247c50d05eae2b6ea2` |
| `x86_64/libdrone_hacks_lib.so` | 25 240 512 | `73dcb13ec4d6a1ffa5d229552568a66da330698a49af889d416488c7f393cb09` |

Java/Kotlin-часть в основном является оболочкой Tauri и USB-мостом к Rust.

## Android и USB

Manifest запрашивает только `INTERNET` и внутреннее dynamic-receiver
permission. Приложение объявляет `android.hardware.usb.host`, поддерживает USB
Accessory и не содержит intent filter автоматического запуска по
`USB_DEVICE_ATTACHED`.

`UsbService`:

1. ищет USB-устройство по vendor ID, который передаёт Rust;
2. выбирает BULK IN и BULK OUT endpoints;
3. захватывает интерфейс;
4. передаёт входящие байты в Rust через `sendDataToRust`;
5. пишет сформированные Rust байты в BULK OUT с timeout 1 секунда.

Следовательно, это прямой USB-транспорт к дрону. Сам APK не доказывает
автозапуск при подключении: приложение нужно открыть и выдать Android USB
permission.

Публичная инструкция Drone-Hacks независимо подтверждает тот же сценарий:
Android-устройство с запущенным приложением соединяется с дроном качественным
USB-кабелем, после чего пользователь разрешает USB-доступ.

## Восстановленный One-Shot FCC flow

В native-библиотеке найдены следующие связанные элементы:

- Tauri command `send_fcc`;
- endpoint `POST /api/v1/client/mobile/tethered-modifications/fcc`;
- ответ `ClientMobileTetheredModificationsGetFccPayloadResultDto`;
- ровно четыре поля ответа: `message`, `cmdSet`, `cmdId`, `payload`;
- общая DUML-операция `duml::applets::send_custom_packet::SendCustomPacket`;
- entitlement `drone-hacks-v2-mobile.one-time-fcc`;
- ошибки `NoEntitlement`, `NoSubscription`, `DeviceNotBound`;
- cache key вида `payload_{serial}_{model}_fcc`;
- `CachedFccPayload` с полями `value`, `fetched_at`, `expires_at`;
- сообщения `Failed to fetch one time fcc payload`,
  `Failed to warm FCC cache for device` и `Refreshing FCC cache`.

Наиболее обоснованная последовательность:

```text
вход в аккаунт / проверка подписки и binding
                    |
                    v
определение модели и серийного номера подключённого дрона
                    |
                    v
POST /api/v1/client/mobile/tethered-modifications/fcc
                    |
                    v
{ message, cmdSet, cmdId, payload }
                    |
                    +----> cache payload_{serial}_{model}_fcc
                    |
                    v
SendCustomPacket -> Rust/Android USB bridge -> дрон
```

Это доказывает одну логическую custom DUML-команду в DTO One-Shot FCC. Оно не
доказывает, что на USB физически бывает ровно одна запись: транспорт может
добавлять framing, запросы идентификации, ACK и повторы.

Наличие `expires_at` подтверждает ограниченный offline-сценарий после
успешного авторизованного получения payload. Срок жизни кэша задаётся не APK,
поэтому считать One-Shot FCC бессрочно автономным нельзя.

Публичная документация Drone-Hacks относит One-Shot FCC и offline usage к
Mobile Subscription. Это согласуется со статически найденными entitlement и
cache-механизмом.

## Сравнение с FreeFCC

| Свойство | FreeFCC | Drone-Hacks Mobile 1.0.1 |
|---|---|---|
| Где работает | На DJI-пульте | На отдельном Android-устройстве |
| Подключение к дрону | Через штатный канал пульта и локальные DJI proxy ports | Прямой USB host/accessory |
| Источник команды | Локальные JSON-профили в APK | Ответ закрытого Drone-Hacks API |
| FCC-операция | Одна `07:30=AU`, один `07:19` readback, затем 17 кадров × 2 раунда; распознанный primitive — `09:27` write `0xffff0048 = 2` | Один server-provided `cmdSet`/`cmdId`/`payload`, отправленный как `SendCustomPacket` |
| Поддержание режима | Опциональный 4-кадровый keepalive каждые 5 секунд либо Home Point trigger | Постоянный keepalive в APK не доказан; обнаружен One-Shot flow |
| Аккаунт/подписка | Не нужны | Проверяются entitlement, subscription и device binding |
| Offline | Полностью локальная логика | Кэш ранее авторизованного payload с expiration |
| Точные байты открыты | Да, в `app/src/main/assets/profiles/` | Нет, получаются с сервера |

## Проверка на совпадение с профилем FreeFCC

В `arm64-v8a/libdrone_hacks_lib.so` искались полные payload из
`app/src/main/assets/profiles/fcc.json`. Следующие достаточно длинные и
характерные последовательности не найдены:

| FreeFCC command | Payload | Совпадения в Drone-Hacks native |
|---|---|---:|
| `03:F9` | `8a237103f401` | 0 |
| `03:AF` | `032400000000000000` | 0 |
| `07:30` | `41550000415500000100` | 0 |
| `09:27` | `00024800ffff0200000000` | 0 |
| `09:27` | `00026300ffff0300000000` | 0 |
| `07:18` | `ff415500` | 0 |
| `03:F9` | `d04aeffb01` и `d04aeffb00` | 0 |

Короткие 1–3-байтовые последовательности дают случайные совпадения в большой
native-библиотеке и доказательством общего алгоритма не являются.

Отсутствие локального профиля согласуется с API DTO: сервер возвращает готовые
`cmdSet`, `cmdId` и `payload`. Поэтому APK не является перепакованной копией
текущего FreeFCC-профиля.

## Что пока не доказано

Статический APK не отвечает на следующие вопросы:

- какая именно DUML-команда возвращается для каждой модели;
- одинаков ли payload для двух дронов одной модели;
- срок действия FCC cache;
- меняет ли One-Shot только runtime-состояние SDR или постоянный параметр;
- сколько служебных и повторных пакетов реально проходит по USB;
- что делает сервер при смене firmware или серийного номера.

Точные байты можно получить только одним из разрешённых способов:

1. сохранить авторизованный JSON-ответ API;
2. извлечь расшифрованную запись `offline_cache.bin` из работающего
   приложения;
3. снять контролируемый USB-трафик при нажатии One-Shot FCC.

Без одного из этих артефактов присваивать One-Shot конкретную пару вроде
`09:27` было бы предположением.
