# CLAUDE.md — МЫС Android

Android-клиент МЫС (Kotlin + Compose). Обязан работать вместе с
`terrentii/vsc_desktop` (эталон протокола) и `terrentii/vsc_web` (сервер);
их можно и нужно править ради совместимости. Дизайн:
`docs/superpowers/specs/2026-10-22-mys-android-design.md`.

## Правила

- Безопасность и правила МЫС-transform — как в `vsc_desktop/CLAUDE.md`
  (только проверенные примитивы; transform поверх AEAD; БД всегда зашифрована).
- Модуль `:crypto` — чистый Kotlin/JVM: не знает о сети, хранилище и Android.
- Любое изменение формата байтов сверяется с десктопом через тест-векторы:
  `tools/gen_vectors.py` → `crypto/src/test/resources/vectors.json`.
- Адрес сервера не хардкодить (домен сменится) — только через настройку.

## Команды (внутри `nix develop`)

- Тесты крипто: `./gradlew :crypto:test`
- APK: `./gradlew :app:assembleDebug`
- Векторы: `../vsc_desktop/.venv/bin/python tools/gen_vectors.py ../vsc_desktop/src > crypto/src/test/resources/vectors.json`
