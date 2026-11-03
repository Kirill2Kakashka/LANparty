# LANparty

**LANparty** — локальный Android-мессенджер для прямой связи двух телефонов без интернета и без сервера. Соединение устанавливается по Wi-Fi (UDP-обнаружение + TCP) или по Bluetooth Classic (RFCOMM), после чего собеседники обмениваются текстовыми сообщениями.

- Пакет: `com.fob.lanparty`
- minSdk: **23** (Android 6.0)
- targetSdk: **36**
- Язык: Kotlin, UI на Jetpack Compose
- Сборка: Gradle Kotlin DSL, AGP **9.4.1**, Kotlin **2.2.10**

## Возможности

- Главное меню: «Поиск устройств» и «Принять подключение».
- Выбор транспорта для поиска и для принятия: Wi-Fi или Bluetooth.
- Поиск устройств по Wi-Fi в той же локальной сети.
- Поиск Bluetooth-устройств, запрос включения Bluetooth и режима видимости.
- Явное подтверждение входящего подключения: «Принять» / «Отклонить».
- Чат в стиле SMS: свои сообщения — в зелёных пузырях с зелёной рамкой, сообщения собеседника — в серых с серой рамкой.
- Уведомление о подключении с кнопкой «Отключиться», пока соединение активно.
- Уведомления о новых сообщениях, работа приложения в фоне только при активном соединении.
- Настройки: имя устройства, тёмная тема, звук уведомлений.
- Обработка отказа, таймаута и потери соединения.

## Сборка

Требуются Android SDK с compileSdk 37, JDK 17 и доступ к интернету для загрузки зависимостей.

```powershell
.\gradlew.bat :app:assembleDebug
```

Готовый APK:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Проверка кода:

```powershell
.\gradlew.bat :app:lintDebug
```

> Проект лежит в папке с кириллицей и пробелами в пути, поэтому в `gradle.properties` включён `android.overridePathCheck=true`.

## Как это работает

### Общая схема

`ConnectionManager` — центральный конечный автомат:

```text
Idle → Waiting → Incoming → Connected
Idle → Connecting → Connected
```

- **Idle** — приложение ничего не слушает.
- **Waiting** — телефон ждёт входящее подключение и (для Wi-Fi) отвечает на UDP-запросы поиска.
- **Incoming** — входящий запрос получен, показывается диалог подтверждения.
- **Connecting** — инициатор подключился к найденному устройству и ждёт ответа.
- **Connected** — открыт чат, запущен foreground-service с уведомлением.

Все сетевые операции выполняются в `Dispatchers.IO`. После установки соединения один корутин читает строки из сокета, а отправка сообщений защищена блокировкой записи.

### Wi-Fi

1. Принимающее устройство открывает TCP-сервер на случайном свободном порту и UDP-сокет на фиксированном порту **48765**.
2. UDP-сокет принимает широковещательный запрос `LANPARTY_DISCOVER_V1` и отвечает JSON-предложением: имя устройства, модель и TCP-порт.
3. Ищущее устройство рассылает UDP-запрос на широковещательные адреса своей сети и собирает ответы. Найденные телефоны показываются в списке.
4. При нажатии на устройство инициатор открывает TCP-соединение на указанный порт и отправляет JSON `hello`.
5. Принимающая сторона показывает запрос «Принять подключение от устройства «…»?». После «Принять» отправляется `accepted`, после «Отклонить» — `rejected`.
6. Дальше по этому же TCP-соединению идут построчные JSON-сообщения.

Оба телефона должны быть в одной Wi-Fi-сети (или один должен быть точкой доступа). Сети с изоляцией клиентов (AP isolation) и запретом broadcast не подойдут.

### Bluetooth — принцип работы

Bluetooth Classic — это радиосвязь на 2,4 ГГц с частотной перестройкой и временным разделением. Для передачи данных Android использует профиль **RFCOMM**, который эмулирует последовательный порт поверх L2CAP. Приложению он доступен как `BluetoothSocket`.

Этапы:

1. **Видимость.** Принимающий телефон должен разрешить обнаружение (в приложении запрашивается системный диалог видимости на 300 секунд).
2. **SDP-запись.** Принимающая сторона вызывает `listenUsingInsecureRfcommWithServiceRecord` и регистрирует сервис с фиксированным UUID `6a2f8e8e-3e57-4b9a-8f95-7b1a5c2d9c41`. По этому UUID другой телефон сможет найти RFCOMM-канал через Service Discovery Protocol.
3. **Поиск.** Ищущий телефон запускает классический inquiry (`BluetoothAdapter.startDiscovery`), получает MAC-адрес, имя и класс найденных устройств. Пользователь выбирает телефон из списка.
4. **Соединение.** Инициатор вызывает `createInsecureRfcommSocketToServiceRecord` с тем же UUID и открывает `BluetoothSocket`. Слово «insecure» означает, что приложение не требует системного PIN-спаривания; подтверждение делает сам пользователь в интерфейсе LANparty.
5. **Подтверждение.** По RFCOMM отправляется JSON `hello` с именем инициатора. Принимающий видит диалог и отвечает `accepted` или `rejected`. При отказе инициатор получает сообщение «Не удалось подключиться.».
6. **Чат.** Сообщения передаются построчно в JSON через тот же сокет. При выходе отправляется `disconnect`, затем сокет закрывается.

Bluetooth-сокеты в Android не поддерживают `setSoTimeout`, поэтому в реализации используется watchdog-корутина: по истечении таймаута она закрывает сокет, и заблокированное чтение завершается ошибкой. Это защищает от вечного ожидания.

Радиус действия зависит от класса устройства (обычно около 10 метров), скорость ниже, чем у Wi-Fi, а поиск занимает несколько секунд.

#### English summary (for Gemini)

Bluetooth Classic is a 2.4 GHz radio link. On Android, app data is carried over the **RFCOMM** profile, which emulates a serial port on top of L2CAP and is exposed as a `BluetoothSocket`.

The connection works in these stages:

1. The receiving phone makes itself discoverable and registers an SDP service record with a fixed UUID using `listenUsingInsecureRfcommWithServiceRecord`.
2. The searching phone runs a classic Bluetooth inquiry (`startDiscovery`) and obtains nearby devices' MAC addresses and names.
3. The user selects a device. The initiator opens an RFCOMM socket with `createInsecureRfcommSocketToServiceRecord` and the same UUID. "Insecure" means no system PIN pairing is forced; the app asks the user for explicit approval instead.
4. After the socket is connected, the initiator sends a JSON `hello`. The receiver shows an accept/reject dialog and replies with `accepted` or `rejected`. If rejected, the initiator shows "Connection failed."
5. Chat messages are newline-delimited JSON objects sent over the same socket. A `disconnect` object and socket close end the session.

Android Bluetooth sockets do not support `setSoTimeout`, so the implementation uses a watchdog coroutine that closes the socket after a timeout, unblocking the read. Range is typically about 10 meters; discovery takes several seconds and is slower than Wi-Fi.

### Протокол

Транспорт — построчный JSON (UTF-8, одна строка — один объект). Основные типы:

| Тип | Назначение |
| --- | --- |
| `hello` | Инициатор представляется: имя, модель, версия протокола |
| `accepted` | Принимающая сторона разрешила подключение |
| `rejected` | Принимающая сторона отклонила подключение |
| `message` | Текстовое сообщение: `id`, `text`, `timestamp` |
| `disconnect` | Корректное завершение сессии |

Для обнаружения по Wi-Fi используется UDP-запрос `LANPARTY_DISCOVER_V1` и JSON-ответ типа `offer` с TCP-портом. Пакеты приложения помечаются `app = "LANparty"` и версией протокола.

### Уведомления и фон

- Foreground-service запускается только после успешного подключения.
- Постоянное уведомление: «Устройство [имя] подключено.» с действием «Отключиться».
- Для Wi-Fi дополнительно удерживается Wi-Fi lock, для обоих транспортов — partial wake lock, пока сервис жив.
- При уходе приложения в фон поиск и ожидание останавливаются; активный чат продолжает работать.
- Новые сообщения в фоне показываются в уведомлении. Звук можно отключить в настройках: используются два канала уведомлений — со звуком и без.

### Разрешения и версии Android

- `INTERNET` и сетевые разрешения — для UDP/TCP.
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` — для уведомления о соединении.
- `POST_NOTIFICATIONS` — запрашивается на Android 13+.
- `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` — на Android 12+.
- На Android 6–11 для Bluetooth-поиска используются `BLUETOOTH`, `BLUETOOTH_ADMIN` и `ACCESS_FINE_LOCATION`.
- `targetSdk = 36` выбран намеренно: на Android 17 (API 37) доступ к локальной сети ужесточается, но для приложений с targetSdk 36 он сохраняется в прежнем виде. Разрешение `ACCESS_LOCAL_NETWORK` не запрашивается, пока приложение не начнёт целиться в API 37.

## Ограничения

- Только соединение «один на один»; групповых чатов нет.
- История сообщений хранится в памяти и исчезает после завершения сессии.
- Приложение не шифрует сообщения на уровне приложения. Bluetooth может шифровать радиоканал средствами стека, но для Wi-Fi это обычный TCP в локальной сети. Подтверждение подключения защищает от случайного подключения, но не от прослушивания сети злоумышленником.
- Нет передачи файлов, изображений и звонков.
- Wi-Fi-поиск не работает в сетях с изоляцией клиентов или запретом broadcast.
- Bluetooth-поиск зависит от видимости устройства и ограничений производителя.
- Сквозное тестирование на двух физических телефонах не выполнялось: в среде сборки не было ни подключённого устройства, ни эмулятора. Проверены компиляция, сборка APK и Android Lint (0 ошибок).

## Структура проекта

```text
app/src/main/java/com/fob/lanparty/
├── MainActivity.kt              — Activity, обработка intent из уведомлений
├── LanPartyApplication.kt       — создание репозитория, уведомлений и менеджера
├── data/SettingsRepository.kt   — SharedPreferences: имя, тема, звук
├── model/Models.kt              — состояния сессии, сообщения, события
├── net/
│   ├── ConnectionManager.kt     — автомат состояний и жизненный цикл сессии
│   ├── BluetoothTransport.kt    — Bluetooth Classic: поиск, RFCOMM, SDP
│   ├── WifiTransport.kt         — UDP-обнаружение и TCP-соединение
│   ├── LanProtocol.kt           — формат JSON-сообщений
│   ├── SocketLink.kt            — построчное чтение/запись с блокировкой
│   └── TransportTypes.kt        — общие типы транспортов
├── service/
│   ├── ConnectionService.kt     — foreground-service и блокировки
│   └── NotificationHelper.kt    — каналы и уведомления
└── ui/
    ├── LanPartyApp.kt           — навигация, диалоги, разрешения Bluetooth
    ├── screens/                 — Home, выбор транспорта, поиск, ожидание, чат, настройки
    ├── components/              — общие карточки и верхняя панель
    └── theme/                   — цвета, типографика, темы
```
