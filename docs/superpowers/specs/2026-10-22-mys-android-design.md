# МЫС Android — дизайн

Мобильный клиент МЫС для Android: централизованный режим («Центр», сервер
`soufos.ru`) и анонимный P2P 1:1 по протоколу МЫС, **совместимый с десктопом**
(`terrentii/vsc_desktop`) и сервером (`terrentii/vsc_web`). Все три проекта
дорабатываются согласованно — правки в десктопе и сервере входят в объём.

Эталон протокола — `vsc_desktop` (коммит `9d1b464`): `src/mys_crypto/`,
`src/mys_decentralized/`, `src/mys_centralized/`. Правила безопасности и
МЫС-transform из `vsc_desktop/CLAUDE.md` действуют без изменений.

## Решения

| Вопрос | Решение |
|---|---|
| Стек | Kotlin + Jetpack Compose, один Gradle-модуль `app` |
| P2P-транспорт | relay через `wss://soufos.ru/p2p` + апгрейд на прямой WebRTC DataChannel |
| Хранилище | SQLCipher, мастер-пароль (Argon2id) + опциональная биометрия (Keystore) |
| Фон | foreground service, выключается в настройках |
| Стиль | дизайн-система десктопа 1:1 (токены `mys_ui/theme.py`, шрифты, брутализм) |
| Платформы | только Android; minSdk 26, targetSdk 35 |
| Пакет | `ru.mobile.mys` |
| Адрес сервера | настройка (дефолт `soufos.ru`), из неё выводятся `https://`, `wss://…/ws`, `wss://…/p2p`, `stun:…:3478` — домен планируется сменить |

Вне объёма v1: групповые P2P-чаты, офлайн-доставка в P2P, звонки, iOS,
анонимный вход в «Центр», CI.

## 1. Архитектура

Границы — пакетами (правило десктопа: крипто и хранилище не знают о сети и UI;
сеть не знает деталей шифрования).

```
ru.mobile.mys/
  crypto/   primitives, transform, ratchet, envelope, cpace     — чистый Kotlin, JVM-тесты
  storage/  vault (SQLCipher), репозитории, unlock, биометрия
  p2p/      protocol, rendezvous, transport (relay|webrtc), handshake, session,
            filetransfer, P2PService
  central/  RestClient, WsClient, SyncEngine, CentralService
  service/  MysForegroundService — владеет P2PService и CentralService
  ui/       Compose: тема, unlock, список бесед, чат, настройки
```

Зависимости: Bouncy Castle (X25519, ChaCha20, ChaCha20-Poly1305, HKDF-SHA256,
BLAKE2b с personalization, Argon2id), lazysodium-android (ristretto255;
в JVM-тестах lazysodium-java), SQLCipher for Android, OkHttp (REST + оба
WebSocket), kotlinx.coroutines, Compose, `org.webrtc` (под-проект 6).

Конкурентность: корутины в scope foreground-сервиса (при выключенном фоне —
scope процесса, пока приложение открыто). Сервисы публикуют состояние через
`StateFlow`/`SharedFlow`, UI подписывается.

**Риск, проверяется первым шагом:** lazysodium экспонирует
`crypto_core_ristretto255_from_hash`, `crypto_scalarmult_ristretto255`,
`crypto_core_ristretto255_scalar_random`. Если нет — libsodium через JNA
напрямую (как ctypes в десктопе).

### Совместимость байт-в-байт

`vsc_desktop/scripts/gen_vectors.py` прогоняет крипто и протокол десктопа на
фиксированных входах (детерминированные ключи, скаляры, nonce) и пишет JSON.
Он копируется в `app/src/test/resources/vectors/`; Kotlin-тесты обязаны
совпасть байт-в-байт. Покрытие: `derive_room_params` (вкл. NFKC и
нелатинские фразы), HKDF-метки, `transform_encode/decode`, `kdf_rk`/`kdf_ck`/
`derive_message_keys`, `Header`, `seal/open_` на зафиксированном состоянии
ratchet, `serialize_state`, CPace (generator, ISK по фиксированным скалярам),
`_confirm_mac`, `sk`/`bob_dh_seed`, все wire-кадры, `FILE_META`/`FILE_CHUNK`.

Тонкие места, проверяемые векторами: ChaCha20 в transform — 16-байтовый nonce
`cryptography` (4 байта счётчика LE + 12 байт nonce, всё нули) ⇔ RFC 7539 с
нулевым 12-байтовым nonce и счётчиком 0; BLAKE2b `person` дополняется нулями
до 16 байт; HKDF с пустой солью.

## 2. P2P

### Базовый путь (как десктоп)

`HELLO{room_id, []}` → `PAIR{role, peer_candidates}` → `RelayTransport` →
`PAKE`/`CONFIRM` (CPace + HMAC) → `sk`, ratchet по роли → INITIATOR шлёт
prime → `DATA{envelope.seal(...)}`. Содержимое тегируется байтом kind:
`0x00` текст, `0x01` FILE_META, `0x02` FILE_CHUNK. Реконнект без фразы по
сохранённому `prs`, ratchet возобновляется из vault. `PEER_LEFT` ⇒ пир офлайн.
Ожидание пира — до 5 минут.

### Апгрейд на WebRTC (телефон и десктоп)

- Новый kind `KIND_SIGNAL = 0x03`, тело — UTF-8 JSON:
  `{"t":"offer","sdp":...}`, `{"t":"answer","sdp":...}`,
  `{"t":"cand","c":...,"mid":...,"idx":...}`, `{"t":"bye"}`.
  Сигналинг идёт внутри E2E-сессии: сервер не видит IP, MITM без фразы —
  тоже. Wire-`MsgType` не меняется.
- Обратная совместимость: старый клиент отбрасывает неизвестный kind, ratchet
  продвигается — пара остаётся на relay.
- INITIATOR создаёт offer сразу после prime. ICE: `stun:soufos.ru:3478`,
  trickle. Сигнальные сообщения не персистятся и не показываются.
- DataChannel (`ordered`, reliable, label `mys`) открылся ⇒ отправка
  переключается на него. Relay-WS остаётся открытым (fallback + `PEER_LEFT`).
  Приём слушает оба транспорта; ratchet переживает переупорядочивание на стыке
  через skipped keys (≤ `MAX_SKIP`).
- DataChannel закрылся ⇒ отправка обратно на relay; INITIATOR повторяет offer
  через 10 с (затем backoff до 2 мин). Сессия разорвана, только когда мертвы
  оба пути.
- Формат по DataChannel: один wire-кадр `DATA` = одно бинарное сообщение.
- UI показывает путь: «прямой канал» / «через сервер».
- `CHUNK_SIZE` снижается до 64 КиБ во всех клиентах (лимит сообщения WebRTC
  ~256 КиБ). Получатель берёт размер из `FILE_META` — совместимо.

### Изменения в `vsc_desktop`

- `KIND_SIGNAL` + WebRTC-транспорт на `aiortc`, мультитранспортная сессия.
- `CHUNK_SIZE = 64 КиБ`.
- Удалить `DirectTransport`/UDP hole-punch на loopback; `HELLO` с пустым
  списком кандидатов.
- `scripts/gen_vectors.py`.

### Изменения в `vsc_web`

- coturn в режиме STUN-only (`no-auth`, без relay) на `soufos.ru:3478/udp`;
  инструкция по деплою.
- `/p2p` не меняется.

## 3. Хранилище и «Центр»

### Vault

- Схема — миграции десктопа (`mys_storage/schema.py`, v1–актуальная), включая
  `p2p_prs` для реконнекта.
- Ключ БД = Argon2id(пароль, соль); параметры KDF в открытом sidecar (как в
  десктопе), стартовые — десктопные (64 МиБ, t=3, p=4), подбираются под
  устройство при создании.
- Биометрия (опционально): AES-ключ в Android Keystore
  (`setUserAuthenticationRequired`, BiometricPrompt, StrongBox при наличии)
  шифрует ключ БД; шифротекст в `filesDir`. Инвалидация ключа (смена
  отпечатков) или выключение — обёртка удаляется, вход только по паролю.
- Прогрессивная задержка неверных попыток, опц. duress-wipe — как в десктопе.
- Автоблокировка через N минут неактивности: БД закрывается, сервис
  останавливается.

### «Центр»

Порт `mys_centralized`: `RestClient` (`/api/auth/register|login|logout`,
`/api/rooms`, `/api/rooms/<id>/messages?after&limit`, `POST /api/messages`
с `client_msg_id`, медиа upload/download, edit/delete, `reply_to`),
`WsClient` (`auth` → `ready` → `message`, реконнект с backoff и джиттером),
`SyncEngine` (дедуп по серверному id / `client_msg_id`, дозагрузка при каждом
`ready`). Bearer-токен только в vault. Опция «стирать историю при выходе».

### Вступление по коду (все три проекта)

- `vsc_web`: `_room_dict` + поле `code` (10-значный `room_id`).
  `POST /api/rooms/join {"code":"<10 цифр>"}`: открытая — добавить участника;
  закрытая — только если уже участник, иначе `403 {"error":"forbidden"}`;
  нет комнаты — `404 {"error":"not_found"}`. Ответ — комната по контракту.
- Десктоп: `RestClient.join_room`, «Войти по коду», показ кода комнаты.
- Телефон: то же + «Поделиться кодом» (share sheet).

## 4. UI, фон, ошибки

### Стиль

Дизайн-система десктопа 1:1: токены `DARK`/`LIGHT` из
`vsc_desktop/src/mys_ui/theme.py` переносятся в Kotlin-объект токенов;
шрифты Museo Cyrl и ГОСТ (`mys_ui/fonts`, лицензии — `COPYRIGHT.txt`);
брутализм — жёсткие рамки, блочная смещённая тень (`Npx Npx 0 0`, рисуется
смещённым прямоугольником), моно-капс заголовков, кобальтовый акцент.
Тёмная/светлая тема с переключателем. Material 3 — только каркас компонентов,
визуал полностью из токенов.

### Экраны

- Разблокировка: создание пароля / ввод / биометрия.
- Главный: переключатель `ЦЕНТР | P2P`, список бесед; в P2P — точка онлайн
  и метка пути.
- Чат: текст, файлы, превью картинок; в «Центре» — ответ, правка, удаление.
- P2P: «+ Новый канал» (фраза, окно ожидания), «Выйти на связь».
- «Центр»: вход/регистрация, создать комнату, войти по коду.
- Настройки: тема, фон, биометрия, очистка истории «Центра» при выходе,
  выход из аккаунта, duress, таймаут автоблокировки.
- `FLAG_SECURE` на всех окнах (нет скриншотов, превью в «Недавних» скрыто).

### Foreground service

Тип `dataSync`, стартует после разблокировки, если фон включён. Держит WS
«Центра» и активные P2P-сессии. Уведомление о сообщении — без текста и
отправителя («Новое сообщение»). FCM не используется.

### Ошибки

- Провал PAKE ⇒ «Неверная фраза или перехват», разрыв, ratchet не трогается.
- Нет пира за таймаут ⇒ «Собеседник не на связи».
- `401` в «Центре» ⇒ сброс сессии, форма входа.
- Сеть ⇒ backoff, статус «нет связи».
- Битый/повторный `DATA` ⇒ отбросить, сессия жива.

## 5. Тесты

- JVM-юнит: крипто и протокол по векторам десктопа; roundtrip transform;
  ratchet (out-of-order, skipped, replay); кадры; негативные (битый кадр,
  неверная фраза, identity-точка).
- Интеграция: два Kotlin-клиента через фейковый rendezvous в процессе.
- e2e: эмулятор ↔ десктоп через `soufos.ru` — relay, затем WebRTC;
  «Центр» — синк и real-time между телефоном и десктопом.
- `vsc_web`: pytest на `/api/rooms/join` и `code`. `vsc_desktop`: тесты
  `KIND_SIGNAL`, апгрейда, нового `CHUNK_SIZE`.

## 6. Окружение

`flake.nix` (NixOS): JDK 17, Android SDK (platform 35, build-tools),
Gradle wrapper. Сборка `./gradlew assembleDebug`, тесты `./gradlew test`.

## 7. Под-проекты (порядок)

Каждый — цикл спека → план → реализация.

1. Каркас + крипто-ядро (flake, Gradle, порт `mys_crypto`, CPace, векторы).
2. Хранилище (SQLCipher, мастер-пароль, биометрия, схема).
3. P2P через relay (протокол, rendezvous, хендшейк, сессия, файлы,
   реконнект, онлайн) — цель: переписка телефон ↔ десктоп.
4. «Центр» + вступление по коду (все три проекта).
5. UI в стиле десктопа + foreground service + уведомления.
6. WebRTC-апгрейд во всех трёх проектах + STUN на сервере.
