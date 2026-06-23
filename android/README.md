# TG WS Proxy — Android

Android-обёртка над пакетом [`proxy/`](../proxy) из корня репозитория. Python-код запускается
внутри APK через [Chaquopy](https://chaquo.com/chaquopy/); сам прокси не изменён.

## Что делает приложение

Поднимает локальный MTProto-прокси на `127.0.0.1:<порт>` в foreground-сервисе. Telegram на том
же устройстве настраивается на этот прокси (ссылка `tg://proxy?...`) и продолжает работать,
проксируя трафик к дата-центрам Telegram через WebSocket.

## Сборка APK

Предусловия: JDK 17, Android SDK (platform 34/35, build-tools, NDK 27.x). Путь к SDK задан в
[`local.properties`](local.properties).

```sh
cd android
./gradlew assembleDebug
```

Результат: `app/build/outputs/apk/debug/app-debug.apk` (~38 МБ; содержит CPython 3.12 + OpenSSL
для `arm64-v8a` и `x86_64`).

## Установка и проверка

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. Открыть приложение → порт `1443` → **Запустить прокси**.
   В `adb logcat -s python.stdout` появится баннер `Listening on 127.0.0.1:1443`.
2. **Открыть в Telegram** (или **Скопировать ссылку** и открыть её в Telegram) → подтвердить
   подключение MTProto-прокси.
3. Telegram показывает «Подключено», чаты грузятся.
   При проблемах с фото/видео оставьте в поле DC только `4:149.154.167.220`.
4. **Остановить прокси** — сервис и уведомление исчезают.

## Релизная сборка (для Google Play)

Подпись берётся из `keystore.properties` (не коммитится; см. `.gitignore`).
Ключ: `upload-keystore.jks`, alias `upload`. **Обязательно сделай бэкап ключа** —
без него нельзя выпускать обновления (при включённом Play App Signing upload-ключ
можно сбросить через поддержку, но лучше хранить).

```sh
cd android
./gradlew bundleRelease     # app/build/outputs/bundle/release/app-release.aab  -> загружать в Play
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk     -> для проверки на устройстве
```

Проверка подписи и 16 KB-выравнивания:

```sh
$SDK/build-tools/35.0.0/apksigner verify --print-certs app-release.apk
$SDK/ndk/27.1.12297006/.../llvm-readelf -l lib/arm64-v8a/libpython3.12.so   # LOAD align = 0x4000
```

Что уже соответствует требованиям Play:
- AAB-формат, подписанный релиз, `debuggable=false`.
- `targetSdk 35`, `minSdk 24`.
- 64-бит (`arm64-v8a`); `x86_64` — для эмуляторов/Chromebook.
- Нативные библиотеки выровнены по 16 KB (NDK 27 + Chaquopy 16.1).

Что нужно сделать в Play Console руками (не код):
- **Foreground service**: заполнить декларацию для `FOREGROUND_SERVICE_DATA_SYNC`.
  У `dataSync` на Android 14+ есть суточный лимит времени; для постоянно живущего
  прокси Google может потребовать обоснование или тип `specialUse`.
- **Privacy policy**: прокси обрабатывает пользовательский трафик — нужен URL политики.
- **applicationId**: сейчас `com.flowseal.tgwsproxy`. Перед публикацией под своим
  аккаунтом смени на свой домен (ID не меняется после первой публикации).
- **Иконка/название**: избегай логотипа/названия, намекающих на официальный Telegram
  (риск по товарным знакам).

> Политический риск: приложения-прокси/обход блокировок Google проверяет строже.
> Возможен ручной ревью или отказ. Для приватного использования достаточно
> `assembleRelease` + sideload, без публикации.

## Структура

| Путь | Назначение |
|------|-----------|
| `app/src/main/python/proxy/` | копия пакета `proxy/` (без изменений) |
| `app/src/main/python/android_runner.py` | единственный новый Python: start/stop/ссылки |
| `app/src/main/java/.../App.kt` | инициализация Chaquopy |
| `app/src/main/java/.../ProxyService.kt` | foreground-сервис |
| `app/src/main/java/.../MainActivity.kt` | минимальный UI |

## Обновление кода прокси

Папка `app/src/main/python/proxy/` — статическая копия. После изменений в корневом `proxy/`
пересинхронизируйте:

```sh
cp ../proxy/*.py app/src/main/python/proxy/
```

## Заметки

- `targetSdk 34`, тип foreground-сервиса `dataSync`. На Android 14 у `dataSync` есть суточный
  лимит времени работы; для долго живущего прокси позже можно перейти на `specialUse`.
- Fake TLS / PROXY-protocol намеренно отключены (серверные функции, на телефоне не нужны).
