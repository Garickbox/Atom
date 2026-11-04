# ⚛️ Atom — P2P file transfer for Android

<div align="center">

**Прямая передача файлов между Android — без интернета, без облака, без аккаунтов.**

[![Build](https://github.com/Garickbox/Atom/actions/workflows/build.yml/badge.svg)](https://github.com/Garickbox/Atom/actions/workflows/build.yml)
[![Latest](https://img.shields.io/github/v/release/Garickbox/Atom?label=build&color=blue)](https://github.com/Garickbox/Atom/releases/latest)
[![minSdk](https://img.shields.io/badge/Android-8.0%2B-green?logo=android)](https://developer.android.com)

[📥 Скачать APK](https://github.com/Garickbox/Atom/releases/latest/download/Atom.apk) · [🐛 Issues](https://github.com/Garickbox/Atom/issues) · [📖 Roadmap](#-roadmap)

</div>

---

## Почему Atom

Bluetooth — 1 MB/s и обрывы. Облако — трафик и приватность. Nearby Share — только Google. LocalSend — через роутер, но без Wi-Fi Direct.

Atom — **напрямую**: два устройства в одной сети, **26+ MB/s**, SHA-256 проверка.

| | Atom | LocalSend | Quick Share | Bluetooth |
|---|:-:|:-:|:-:|:-:|
| Без роутера (Wi-Fi Direct) | ✅ | ❌ | ✅ | ✅ |
| Android 8.0+ | ✅ | ✅ | ⚠️ | ✅ |
| Открытый код | ✅ | ✅ | ❌ | ⚠️ |
| Скорость (P2P) | **26-38 MB/s** | — | ~30 | 1-3 |
| Папки с структурой | ✅ | ✅ | ❌ | ❌ |
| SHA-256 | ✅ | ✅ | ⚠️ | ❌ |

**Verified: 10.6 GB / 13 файлов за 6 минут.**

---

## Что умеет

### 📤 Передача
- Любые файлы и папки (фото, видео, документы, APK)
- Сохранение структуры: `Photos/2024/IMG.jpg` → `Download/Atom/Photos/2024/IMG.jpg`
- 4 TCP-потока для файлов ≥ 4 MB (**26.9 MB/s avg, 38 MB/s пик**)
- SHA-256 каждого файла
- Pause / Resume с байта N
- Multi-file серии до 100 файлов

### 📶 Сопряжение
- Wi-Fi Direct (5 GHz prefer на Android 10+)
- Hotspot + QR
- Quick Connect (известные пиры в 1 тап)
- Force Fresh (reconnect при stale профиле)

### 🎨 UI
- Виджет 4×2 (toggle, прогресс, Pause/Cancel)
- QS Tile (приём из шторки)
- Direct Share («Атом → Redmi» в share sheet)
- Badge counter, History + search + CSV, Settings + selftest


### 🤖 Tasker / Automate

~~~bash
am broadcast -a com.garickbox.atom.RECEIVE_ON
am broadcast -a com.garickbox.atom.SEND -e peer_mac "aa:bb:cc:dd:ee:ff"
am broadcast -a com.garickbox.atom.CHECK_UPDATE
am broadcast -a com.garickbox.atom.STATUS   # → STATUS_RESULT extra json
am broadcast -a com.garickbox.atom.PEERS
am broadcast -a com.garickbox.atom.HISTORY -e limit 5
~~~

Ловятся в Tasker → Intent Received → `com.garickbox.atom.STATUS_RESULT` → `%json`.

---

## Установка

### Способ 1 — APK

1. [releases/latest](https://github.com/Garickbox/Atom/releases/latest) → `Atom.apk`
2. Установи (разреши источник)
3. Запусти → имя → permissions

### Способ 2 — сборка

~~~bash
git clone https://github.com/Garickbox/Atom.git
cd Atom
# Android Studio: Build → Build APK(s)
# Или: push → artifact в GitHub Actions
~~~

**JDK 17 + Android SDK 34 + Gradle 8.7.**

---

## Быстрый старт

**📱 QR (без роутера, ~15 сек)**
1. Приёмник: Атом → 📱 Показать QR
2. Отправитель: 🔍 Сканировать QR
3. Подключается → передача

**📶 Quick Connect**
1. FAB → файлы → «📱 Redmi Note» (последний peer)

**📤 Direct Share**
1. Галерея → фото → Поделиться → **Атом → Redmi**

**Принимать:**
- Виджет → 📥 Принимать (10 мин)
- QS Tile → тап
- Shortcut → долгий тап → «Приём 10 мин»
- Broadcast → `am broadcast -a com.garickbox.atom.RECEIVE_ON`

Файлы → `Download/Atom/{Documents,Music,Movies,Pictures}/`


---

## Производительность (verified)

POCO X5 5G (API 34) → MiTV (API 28), Wi-Fi Direct:

| Файл | Размер | Время | Скорость |
|------|--------|-------|----------|
| 1/13 (прогрев) | 787 MB | 50 сек | 15.6 MB/s |
| 2/13 | 697 MB | 36 сек | 19.1 MB/s |
| 3/13 | 808 MB | 34 сек | 23.9 MB/s |
| **13/13 (финал)** | **819 MB** | **24 сек** | **33.5 MB/s** |
| **Итого** | **10.6 GB** | **6 мин** | **26.9 MB/s avg** |

На 5 GHz Wi-Fi Direct — до **40-70 MB/s**.

---

## Требования

| | |
|---|---|
| Android | 8.0+ (API 26), targetSdk 34 |
| RAM | 2 GB+ |
| Место | Размер файла + 5 MB |
| Сеть | Wi-Fi Direct (опц. — hotspot работает) |
| Только Android | iOS / Win / mac / Linux — roadmap |

---

## Известные ограничения

### ⚠️ HyperOS / MIUI: hotspot требует выключить Wi-Fi

На Xiaomi / Redmi / POCO LocalOnlyHotspot блокирован при активном Wi-Fi (`reason=2 INCOMPATIBLE_MODE`).

**Workaround:** QR приём → если ошибка → **«Открыть Wi-Fi»** → выключи тумблер → вернись → AP создастся. Это ограничение HyperOS.

### 🔒 Нет шифрования (только целостность)

SHA-256 проверяет целостность, но не скрывает файл в сети. **TLS 1.3 + TOFU** в roadmap.

### 🚧 Early MVP (0.1)

Race conditions под нагрузкой, виджет нестабилен на HyperOS. **Не для критичных данных.**

---

## Roadmap

### ✅ 0.1-mvp (build-292)
- [x] Wi-Fi Direct + Quick Connect + QR
- [x] Файлы, папки, серии, SHA-256, Pause/Resume
- [x] Multi-thread (26.9 MB/s avg)
- [x] Виджет, QS Tile, Shortcuts, Direct Share
- [x] Tasker broadcasts, OTA, Badge, History, Settings
- [x] 5 GHz detect + prefer (B121)

### 🟡 0.2 — В работе
- [ ] TLS 1.3 + TOFU pinning
- [ ] SHA FILE_DONE ack (sender ждёт hash)
- [ ] Broadcast custom permission
- [ ] Unit-тесты mesh protocol
- [ ] QA 10 GB × 3 устройства

### 🔵 0.3 — Планы
- [ ] Desktop (Win/mac/Linux, KMP)
- [ ] LAN fallback (Ktor HTTP)
- [ ] Wi-Fi Aware (NAN)
- [ ] Background auto-receive

### ⚪ 1.0 — Долгосрочно
- [ ] iOS, F-Droid, Play Store
- [ ] LocalSend protocol compat

---

## Технологии

Kotlin 1.9.24 · AGP 8.5.2 · Gradle 8.7 · JDK 17 · Coroutines 1.8.1 · OkHttp 4.12 · zxing 4.3.0

Сеть: UDP (signal) + TCP (4 threads), порт 47822 · P2P: WifiP2pManager + LocalOnlyHotspot · Storage: MediaStore.Downloads (API 29+) / direct (26-28)

---

## Лицензия

TBD (GPL-3.0 или Apache-2.0 после 0.2). Пока — использовать, модифицировать, распространять с указанием авторства.

<div align="center">

**⚛️ Atom** — без облаков, аккаунтов и рекламы.

[⭐ Star](https://github.com/Garickbox/Atom) · [📥 Download](https://github.com/Garickbox/Atom/releases/latest)

</div>
