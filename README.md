# ⚛️ Atom

**Прямая передача файлов между Android-устройствами — без интернета, без облака, без аккаунтов.**

Atom отправляет файлы и папки напрямую по Wi-Fi Direct / hotspot: ни серверов, ни рекламы, ни сбора данных. Только два устройства в одной сети — и до 20 МБ/с с проверкой SHA-256 каждого файла.

[📥 Скачать APK](https://github.com/Garickbox/Atom/releases/latest/download/Atom.apk) · [🐛 Issues](https://github.com/Garickbox/Atom/issues) · [📋 Roadmap](#roadmap)

---

## Что это

Atom решает простую задачу: **перекинуть файлы с телефона на телефон без провода**. Bluetooth медленный. Облако требует интернета и сливает данные. Nearby Share требует Google. Atom — это **P2P-передатчик с открытым кодом**, который работает в вашей локальной сети.

- ✅ Прямая связь (Wi-Fi Direct / hotspot)
- ✅ SHA-256 проверка целостности
- ✅ Папки с сохранением структуры
- ✅ Пауза / продолжение с места обрыва
- ✅ Открытый исходный код
- ✅ Без рекламы, без трекинга, без аккаунтов

---

## Возможности

### 📤 Передача файлов

| | |
|---|---|
| **Любые файлы** | Фото, видео, документы, архивы, APK — без ограничения типа |
| **Папки** | С сохранением структуры: `Photos/2024/IMG.jpg` → `Download/Atom/Photos/2024/IMG.jpg` |
| **Multi-file** | Серия файлов подряд, pause / resume / cancel для всей серии |
| **SHA-256** | Проверка каждого файла — не «дошёл ли», а «дошёл без повреждений» |
| **Pause / Resume** | Прерванная передача продолжается с байта N, не с нуля |
| **MediaStore** | Единый корень: `Download/Atom/{Documents,Music,Movies,Pictures}/` |
| **Fallback** | API 29+: MediaStore, API 26-28: прямая запись в публичную папку |

### 📶 Сопряжение

| | |
|---|---|
| **Wi-Fi Direct** | Прямое подключение без роутера |
| **Hotspot + QR** | Хост создаёт точку доступа, гость сканирует QR |
| **Quick Connect** | Список известных peer'ов — tap → connect без discovery |
| **Force Fresh** | Fallback при stale P2P-профиле (peer reboot, gap >30 мин) |

### 🎨 UI

| | |
|---|---|
| **Виджет 4×2** | Toggle приёма + прогресс + Pause / Cancel прямо с главного экрана |
| **QS Tile** | В шторке: тап = приём ON/OFF, 2×тап = file picker |
| **App Shortcuts** | 3 последних peer'а — тап → сразу connect |
| **Direct Share** | В share sheet галереи/Telegram — «Атом → peer» сверху списка |
| **Badge counter** | Цифра непрочитанных передач на иконке |
| **Notification tap** | Тап на уведомление → открыть Атом |
| **History** | 50 последних передач + фильтры + CSV export |
| **Settings** | Имя, известные peers, очистка истории/логов, инфо |
| **Onboarding** | Разрешения + battery + install unknown + HyperOS autostart за один проход |

### 🤖 Автоматизация

Broadcast intents через `am broadcast` или Tasker Action:

    # Write-actions
    am broadcast -a com.garickbox.atom.RECEIVE_ON
    am broadcast -a com.garickbox.atom.RECEIVE_OFF
    am broadcast -a com.garickbox.atom.SEND -e peer_mac "aa:bb:cc:dd:ee:ff" -e peer_name "Redmi"
    am broadcast -a com.garickbox.atom.CHECK_UPDATE

    # Read-only (результат в *_RESULT)
    am broadcast -a com.garickbox.atom.STATUS    # build, device, peers, transferState
    am broadcast -a com.garickbox.atom.PEERS     # list known peers
    am broadcast -a com.garickbox.atom.HISTORY -e limit 5

Результаты ловятся в Tasker → Intent Received → `com.garickbox.atom.STATUS_RESULT` → `%json`.

### 🔄 Обновления

| | |
|---|---|
| **OTA через GitHub** | APK скачивается из Releases, ставится системным installer'ом |
| **Auto-update** | После build success — `/update` на устройстве → download + installer |
| **Version check** | `axiom#85` — не предлагает установить уже установленный APK |

---

## Установка

### Способ 1 — APK из Releases (рекомендуется)

1. Откройте [releases/latest](https://github.com/Garickbox/Atom/releases/latest)
2. Скачайте `Atom.apk`
3. Установите (разрешите установку из этого источника)
4. Откройте Атом → введите имя → разрешите permissions

### Способ 2 — сборка из исходников

    git clone https://github.com/Garickbox/Atom.git
    cd Atom
    # В Android Studio: Build → Build Bundle(s) / APK(s) → Build APK(s)
    # Или через GitHub Actions: push → artifact в Actions

**Требования для сборки:** JDK 17, Android SDK 34, Gradle 8.7.

---

## Быстрый старт

### Отправить файл

**Вариант А — QR (без роутера)**
1. На приёмнике: **📱 QR приём** → создаётся hotspot + QR
2. На отправителе: **🔍 Сканировать QR** → сканируй → передача

**Вариант Б — последний peer**
1. FAB → **📷 Фото / 🎬 Видео / 📄 Файл / 📁 Папка** → выбери файлы
2. Тап на **📱 Redmi Note** (последний peer) → передача

**Вариант В — share sheet**
1. Галерея → фото → **Поделиться** → **Атом → Redmi** (Direct Share)

### Принимать файлы

- **Виджет**: тап «📥 Принимать» → 10 мин активен
- **QS Tile**: тап → приём ON (2×тап = file picker)
- **Shortcut**: долгий тап иконки → «Приём 10 мин»
- **Broadcast**: `am broadcast -a com.garickbox.atom.RECEIVE_ON`

Файлы сохраняются в `Download/Atom/{Documents,Music,Movies,Pictures}/`.

---

## Требования

| | |
|---|---|
| **Android** | 8.0+ (API 26), targetSdk 34 |
| **RAM** | 2 GB+ |
| **Свободное место** | Размер файла + 5 MB буфер |
| **Сеть** | Wi-Fi Direct hardware (опционально — hotspot работает везде) |

**Не поддерживается:**
- iOS, Windows, macOS, Linux (**в roadmap**)
- Android < 8.0

---

## Известные ограничения

### ⚠️ HyperOS / MIUI: hotspot требует выключить Wi-Fi

На HyperOS/MIUI (Xiaomi/Redmi/POCO) **LocalOnlyHotspot заблокирован при активном Wi-Fi тумблере**, если соединение `supplicantState=COMPLETED`. Framework отвечает `reason=2 INCOMPATIBLE_MODE` / `reason=3 NO_CHANNEL` за 5-10 мс.

**Workaround:**
1. Откройте `📱 QR приём`
2. Если hotspot не создался — **выключите Wi-Fi тумблер** в шторке
3. Вернитесь в Атом → hotspot создастся
4. После передачи — Wi-Fi включится сам (или вручную)

Это **ограничение HyperOS** для user-app, не баг Atom.

### 🔒 Нет шифрования (только целостность)

SHA-256 проверяет, что файл дошёл **без повреждений**, но не скрывает его от других в той же сети. Не передавайте через Atom паспортные данные, пароли, приватные ключи.

**В roadmap:** TLS 1.3 + самоподписанные сертификаты + TOFU pinning.

### 📱 Только Android

iOS, Windows, macOS, Linux — **в roadmap**. Пока работает только Android ↔ Android.

### 🚧 Ранняя версия (0.1-mvp)

Без полноценного QA. Возможны:
- Timeouts при pause/resume
- Race conditions при multi-file
- Нестабильный виджет

**Не используйте для критичных данных** без проверки.

---

## Технологии

| | |
|---|---|
| **Язык** | Kotlin 1.9.24 |
| **Сборка** | AGP 8.5.2, Gradle 8.7, JDK 17 |
| **Архитектура** | Activity + Services (FGS) + Coroutines + ViewBinding |
| **Сеть** | UDP (сигналинг) + TCP (данные), порт 47822 |
| **P2P** | `WifiP2pManager` + `LocalOnlyHotspot` |
| **Хранилище** | `MediaStore.Downloads` + `RELATIVE_PATH` (API 29+) / прямая запись (API 26-28) |
| **QR** | `zxing-android-embedded` 4.3.0 |
| **OTA** | OkHttp 4.12 + GitHub Releases API |

**Структура проекта:**

    app/src/main/java/
    ├── com.garickbox.atom/          # UI, Activity, Service, Controller
    │   ├── MainActivity.kt
    │   ├── QrShareActivity.kt       # Host: создаёт AP + QR
    │   ├── QrScanActivity.kt        # Guest: сканирует QR + connect
    │   ├── FileSenderService.kt     # FGS отправки
    │   ├── FileReceiver.kt          # приём (object)
    │   ├── FileReceiverService.kt   # FGS приёма
    │   ├── MediaStoreSaver.kt       # сохранение в Download/Atom/
    │   ├── AtomWidgetProvider.kt    # виджет 4×2
    │   ├── AtomQSTile.kt            # QS Tile
    │   ├── AtomCommandReceiver.kt   # broadcast intents
    │   ├── UpdateChecker.kt         # OTA через GitHub
    │   ├── TestApi.kt               # debug HTTP API
    │   └── ...
    └── com.mesh.core/               # P2P-слой (без зависимости от garickbox)
        ├── MeshNode.kt
        ├── MeshPacket.kt
        ├── WifiDirectManager.kt
        ├── InAppLogger.kt
        ├── CompactLog.kt
        └── PowerLocks.kt

---

## Roadmap

### 🟢 Готово (0.1-mvp)

- [x] Wi-Fi Direct connect + Quick Connect
- [x] Hotspot + QR сопряжение
- [x] Передача файлов, папок, серий
- [x] SHA-256 проверка
- [x] Pause / Resume / Cancel
- [x] MediaStore save (единый корень)
- [x] Виджет 4×2 + QS Tile + App Shortcuts
- [x] Direct Share (Sharing Shortcuts)
- [x] Broadcast intents для Tasker
- [x] OTA через GitHub
- [x] Badge counter
- [x] History + CSV export
- [x] Settings
- [x] Onboarding
- [x] Multi-thread (заготовка)

### 🟡 В работе (0.2)

- [ ] TLS 1.3 шифрование — самоподписанные сертификаты + TOFU pinning
- [ ] Multi-thread активация — 4 потока для size ≥ 4 MB (30-40 МБ/с)
- [ ] Hotspot на HyperOS без ручного Wi-Fi off
- [ ] Ручной QA — 10 файлов × 1 GB × 3 устройства
- [ ] Unit-тесты mesh protocol

### 🔵 В планах (0.3)

- [ ] Desktop companion — Windows / macOS / Linux (KMP)
- [ ] LocalSend compat — fallback LAN transport
- [ ] Transfer history filters
- [ ] Wi-Fi Aware
- [ ] Background auto-receive

### ⚪ Долгосрочно (1.0)

- [ ] iOS-версия
- [ ] F-Droid релиз
- [ ] Play Store релиз
- [ ] Multi-device sync

---

## Лицензия

**TBD** — будет выбрана после стабилизации ядра (0.2). Планируется **GPL-3.0** или **Apache-2.0**.

Пока можно использовать, модифицировать, распространять с указанием авторства.

---

## Контакты

- **Issues / идеи**: [github.com/Garickbox/Atom/issues](https://github.com/Garickbox/Atom/issues)
- **Обсуждения**: [github.com/Garickbox/Atom/discussions](https://github.com/Garickbox/Atom/discussions)
- **Автор**: [@Garickbox](https://github.com/Garickbox)

---

<p align="center">
  <b>⚛️ Atom</b> — передача файлов без облаков и рекламы
</p>
