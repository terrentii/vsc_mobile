# Каркас + крипто-ядро — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Собираемый Android-проект `ru.mobile.mys` и порт крипто-ядра МЫС на Kotlin, совпадающий с `vsc_desktop/src/mys_crypto` байт-в-байт.

**Architecture:** Два Gradle-модуля: `:crypto` — чистый Kotlin/JVM (Bouncy Castle + ristretto255 из libsodium через интерфейс lazysodium), тестируется на JVM без Android SDK; `:app` — Android-приложение, подключает `:crypto` и `lazysodium-android`. Совместимость доказывается тест-векторами, которые генерирует эталонный Python-код десктопа.

**Tech Stack:** Nix flake (JDK 17, Gradle, Android SDK 35, libsodium), Gradle 8.14 + AGP 8.11.1, Kotlin 2.1.21, Bouncy Castle 1.81, lazysodium 5.2.0 (android) / 5.1.4 (JVM-тесты: 5.2.0 требует JVM 21), JUnit 4 (`kotlin-test`), org.json (только тесты).

Спека: `docs/superpowers/specs/2026-10-22-mys-android-design.md`.

**Отклонения от спеки (сознательные):**
- Модуль `:crypto` вынесен отдельно (в спеке — один модуль): JVM-тесты без SDK и без конфликта `lazysodium-java`/`lazysodium-android` на classpath.
- Генератор векторов лежит в `vsc_mobile/tools/`, а не в `vsc_desktop/scripts/`: не трогаем ветки десктопа, векторы живут рядом с потребителем.
- BLAKE2b (деривация `room_id`, `sid`) и Ed25519 — не здесь: первое нужно в под-проекте 3 (P2P), второе протоколом не используется.

## Структура файлов

```
flake.nix, flake.lock                      — dev-окружение
.gitignore
settings.gradle.kts, build.gradle.kts, gradle.properties, gradlew, gradle/wrapper/*
CLAUDE.md                                  — правила проекта для агентов
tools/gen_vectors.py                       — векторы из vsc_desktop
crypto/build.gradle.kts
crypto/src/main/kotlin/ru/mobile/mys/crypto/
  Bytes.kt        — u32 big-endian, беззнаковое сравнение
  Primitives.kt   — X25519, ChaCha20-Poly1305, HKDF, HMAC, SHA-512, Argon2id
  Transform.kt    — МЫС-transform
  Ratchet.kt      — Header, RatchetState, Double Ratchet, (де)сериализация
  Envelope.kt     — seal/open = ratchet + transform
  Ristretto.kt    — обёртка над Ristretto255.Native (lazysodium)
  CPace.kt        — CPace на ristretto255
crypto/src/test/kotlin/ru/mobile/mys/crypto/
  Vectors.kt, PrimitivesTest.kt, TransformTest.kt, RatchetTest.kt, CPaceTest.kt
crypto/src/test/resources/vectors.json
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/kotlin/ru/mobile/mys/MainActivity.kt
```

Все команды ниже выполняются из корня `vsc_mobile` внутри `nix develop` (или с префиксом `nix develop -c`).

---

### Task 1: Nix-окружение

**Files:**
- Create: `flake.nix`, `.gitignore`

- [ ] **Step 1: Написать `flake.nix`**

```nix
{
  description = "МЫС Android — dev-окружение";
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { nixpkgs, ... }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = { allowUnfree = true; android_sdk.accept_license = true; };
      };
      android = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "35" ];
        buildToolsVersions = [ "35.0.0" ];
        includeEmulator = false;
        includeNDK = false;
      };
      sdk = "${android.androidsdk}/libexec/android-sdk";
    in {
      devShells.${system}.default = pkgs.mkShell {
        packages = [ pkgs.jdk17 pkgs.gradle android.androidsdk pkgs.libsodium ];
        ANDROID_HOME = sdk;
        ANDROID_SDK_ROOT = sdk;
        JAVA_HOME = "${pkgs.jdk17}";
        # aapt2 из Maven динамически слинкован под FHS — на NixOS берём из SDK.
        GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/35.0.0/aapt2";
        # Для tools/gen_vectors.py: десктопный _ristretto ищет libsodium здесь.
        MYS_LIBSODIUM = "${pkgs.libsodium}/lib/libsodium.so";
      };
    };
}
```

- [ ] **Step 2: Написать `.gitignore`**

```
.gradle/
build/
local.properties
.idea/
*.iml
.kotlin/
result
```

- [ ] **Step 3: Проверить окружение**

Run: `git add flake.nix && nix develop -c sh -c 'java -version 2>&1 | head -1; gradle --version | grep Gradle; ls $ANDROID_HOME/platforms; ls $MYS_LIBSODIUM'`
Expected: `openjdk version "17…"`, `Gradle 8.14.x`, `android-35`, путь к `libsodium.so`. (`git add` нужен: flake видит только файлы, известные git.)

- [ ] **Step 4: Commit**

```bash
git add flake.nix flake.lock .gitignore
git commit -m "build: Nix dev-окружение (JDK 17, Gradle, Android SDK 35, libsodium)"
```

---

### Task 2: Gradle-каркас (`:crypto` + `:app`)

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `crypto/build.gradle.kts`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/ru/mobile/mys/MainActivity.kt`, `crypto/src/main/kotlin/ru/mobile/mys/crypto/Bytes.kt`

- [ ] **Step 1: `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "mys"
include(":app", ":crypto")
```

- [ ] **Step 2: `build.gradle.kts` (корень)**

```kotlin
plugins {
    id("com.android.application") version "8.11.1" apply false
    kotlin("android") version "2.1.21" apply false
    kotlin("jvm") version "2.1.21" apply false
    kotlin("plugin.compose") version "2.1.21" apply false
}
```

- [ ] **Step 3: `gradle.properties`**

```
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
```

- [ ] **Step 4: `crypto/build.gradle.kts`**

```kotlin
plugins { kotlin("jvm") }

kotlin { jvmToolchain(17) }

dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    // Только API: на Android реализацию даёт lazysodium-android (тот же пакет
    // com.goterl.lazysodium), в тестах — lazysodium-java с нативами под JVM.
    compileOnly("com.goterl:lazysodium-java:5.1.4")
    testImplementation("com.goterl:lazysodium-java:5.1.4")
    testImplementation("net.java.dev.jna:jna:5.17.0")
    testImplementation("org.json:json:20250517")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnit() }
```

- [ ] **Step 5: `crypto/src/main/kotlin/ru/mobile/mys/crypto/Bytes.kt`**

```kotlin
package ru.mobile.mys.crypto

internal val EMPTY = ByteArray(0)

internal fun Int.u32be(): ByteArray = byteArrayOf(
    (this ushr 24).toByte(), (this ushr 16).toByte(), (this ushr 8).toByte(), this.toByte(),
)

internal fun ByteArray.u32be(off: Int): Int =
    ((this[off].toInt() and 0xff) shl 24) or
        ((this[off + 1].toInt() and 0xff) shl 16) or
        ((this[off + 2].toInt() and 0xff) shl 8) or
        (this[off + 3].toInt() and 0xff)

/** Лексикографическое сравнение как у Python `bytes` (Arrays.compareUnsigned — только API 33+). */
internal fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
        if (d != 0) return d
    }
    return a.size - b.size
}
```

- [ ] **Step 6: `app/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

android {
    namespace = "ru.mobile.mys"
    compileSdk = 35
    defaultConfig {
        applicationId = "ru.mobile.mys"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF" }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":crypto"))
    implementation("com.goterl:lazysodium-android:5.2.0@aar")
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
}
```

- [ ] **Step 7: `app/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:label="МЫС"
        android:allowBackup="false"
        android:theme="@android:style/Theme.DeviceDefault.NoActionBar">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`allowBackup="false"` — зашифрованный vault и ключи не должны уезжать в облачный бэкап.

- [ ] **Step 8: `app/src/main/kotlin/ru/mobile/mys/MainActivity.kt`**

```kotlin
package ru.mobile.mys

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("МЫС") }
    }
}
```

- [ ] **Step 9: Сгенерировать wrapper и собрать**

Run: `gradle wrapper --gradle-version 8.14.3 && ./gradlew :crypto:build :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, файл `app/build/outputs/apk/debug/app-debug.apk`.
Если `compose-bom:2025.06.00` не резолвится — взять последнюю доступную версию BOM из `https://maven.google.com/web/index.html#androidx.compose:compose-bom` и записать её.

- [ ] **Step 10: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradlew gradlew.bat gradle/ crypto/ app/
git commit -m "build: Gradle-каркас — модули :crypto (JVM) и :app (Android, ru.mobile.mys)"
```

---

### Task 3: Генератор тест-векторов из десктопа

**Files:**
- Create: `tools/gen_vectors.py`, `crypto/src/test/resources/vectors.json`

- [ ] **Step 1: Написать `tools/gen_vectors.py`**

```python
"""Тест-векторы крипто-ядра МЫС из эталонной реализации vsc_desktop.

Все входы детерминированы (SHAKE-256 от меток), чтобы Kotlin-порт мог
воспроизвести вычисления байт-в-байт. Запуск (из корня vsc_mobile, в nix develop):

    ../vsc_desktop/.venv/bin/python tools/gen_vectors.py ../vsc_desktop/src \
        > crypto/src/test/resources/vectors.json
"""

import hashlib
import json
import sys

sys.path.insert(0, sys.argv[1])

from mys_crypto import _ristretto, pake  # noqa: E402
from mys_crypto import envelope as E  # noqa: E402
from mys_crypto import primitives as P  # noqa: E402
from mys_crypto import ratchet as R  # noqa: E402
from mys_crypto import transform as T  # noqa: E402

H = bytes.hex


def det(label: str, n: int = 32) -> bytes:
    return hashlib.shake_256(label.encode()).digest(n)


def scalar(label: str) -> bytes:
    """Скаляр < 2^252 < L — валиден для ristretto255 без редукции."""
    s = bytearray(det(label))
    s[31] &= 0x0F
    return bytes(s)


v: dict = {}

v["hkdf"] = [
    {"ikm": H(ikm), "salt": H(salt), "info": H(info), "len": n,
     "out": H(P.hkdf(ikm, n, salt, info))}
    for ikm, salt, info, n in [
        (det("ikm1"), b"", b"mys-room-id", 32),
        (det("ikm2"), det("salt2"), b"mys-ratchet-rk", 64),
        (det("ikm3"), bytes(32), b"mys-ratchet-msg", 44),
    ]
]

a_priv, a_pub = P.x25519_keypair_from_seed(det("x-a"))
b_priv, b_pub = P.x25519_keypair_from_seed(det("x-b"))
v["x25519"] = {"a_priv": H(a_priv), "a_pub": H(a_pub), "b_priv": H(b_priv),
               "b_pub": H(b_pub), "shared": H(P.x25519_shared(a_priv, b_pub))}

key, nonce, aad = det("aead-key"), det("aead-nonce", 12), b"header"
pt = "Привет, МЫС".encode()
v["aead"] = {"key": H(key), "nonce": H(nonce), "aad": H(aad), "pt": H(pt),
             "ct": H(P.aead_encrypt(key, nonce, pt, aad))}

pw, salt = "пароль".encode(), det("argon-salt", 16)
v["argon2id"] = [
    {"password": H(pw), "salt": H(salt), "len": 32, "t": t, "m": m, "p": p,
     "out": H(P.argon2id(pw, salt, 32, t, m, p))}
    for t, m, p in [(1, 1024, 1), (3, 65536, 4)]
]

tk = det("transform-key")
v["transform"] = [
    {"key": H(tk), "data": H(d), "enc": H(T.transform_encode(d, tk))}
    for d in [b"", b"\x00", det("tdata", 1000)]
]

rk_in, dh_out = det("rk"), det("dh-out")
rk1, ck1 = R.kdf_rk(rk_in, dh_out)
v["kdf_rk"] = {"rk": H(rk_in), "dh_out": H(dh_out), "rk_out": H(rk1), "ck_out": H(ck1)}
ck_in = det("ck")
new_ck, mk = R.kdf_ck(ck_in)
v["kdf_ck"] = {"ck": H(ck_in), "ck_out": H(new_ck), "mk": H(mk)}
mkey, mnonce = R.derive_message_keys(mk)
v["message_keys"] = {"mk": H(mk), "key": H(mkey), "nonce": H(mnonce)}

# Детерминированная пара: Alice собрана вручную (ratchet_init_alice берёт случайный DH).
sk = det("sk")
bob_kp = P.x25519_keypair_from_seed(det("bob-dh"))
alice_kp = P.x25519_keypair_from_seed(det("alice-dh"))
rk, cks = R.kdf_rk(sk, P.x25519_shared(alice_kp[0], bob_kp[1]))
alice = R.RatchetState(dhs=alice_kp, dhr=bob_kp[1], rk=rk, cks=cks, ckr=None,
                       ns=0, nr=0, pn=0, mkskipped={})
bob = R.ratchet_init_bob(sk, bob_kp)
alice0, bob0 = R.serialize_state(alice), R.serialize_state(bob)
tkey = E.derive_transform_key(sk)
texts = [b"", "первое".encode(), "второе".encode(), "третье".encode()]  # b"" — prime-кадр
blobs = [E.seal(alice, tkey, t) for t in texts]
v["envelope"] = {
    "sk": H(sk), "tk": H(tkey), "alice": H(alice0), "bob": H(bob0),
    "alice_after": H(R.serialize_state(alice)),
    "messages": [{"pt": H(t), "blob": H(b)} for t, b in zip(texts, blobs)],
}
# Bob открывает последнее первым — в состоянии появляются skipped-ключи для 0..2.
assert E.open_(bob, tkey, blobs[3]) == texts[3]
v["state_with_skipped"] = H(R.serialize_state(bob))

prs, sid = det("prs"), det("sid", 16)
ya, yb = scalar("cpace-a"), scalar("cpace-b")
v["cpace"] = []
for ci, ad in [(b"", b""), (b"ci", b"ad")]:
    g = pake.cpace_generator(prs, sid, ci, ad)
    Ya, Yb = _ristretto.scalarmult(ya, g), _ristretto.scalarmult(yb, g)
    isk = pake.cpace_finish(pake.CPaceState(y=ya, Y=Ya, sid=sid), Yb)
    assert isk == pake.cpace_finish(pake.CPaceState(y=yb, Y=Yb, sid=sid), Ya)
    v["cpace"].append({"prs": H(prs), "sid": H(sid), "ci": H(ci), "ad": H(ad),
                       "g": H(g), "ya": H(ya), "yb": H(yb), "Ya": H(Ya), "Yb": H(Yb),
                       "isk": H(isk)})

json.dump(v, sys.stdout, ensure_ascii=False, indent=1)
print()
```

- [ ] **Step 2: Сгенерировать векторы**

Run: `mkdir -p crypto/src/test/resources && ../vsc_desktop/.venv/bin/python tools/gen_vectors.py ../vsc_desktop/src > crypto/src/test/resources/vectors.json && python3 -c "import json;print(sorted(json.load(open('crypto/src/test/resources/vectors.json'))))"`
Expected: `['aead', 'argon2id', 'cpace', 'envelope', 'hkdf', 'kdf_ck', 'kdf_rk', 'message_keys', 'state_with_skipped', 'transform', 'x25519']`

- [ ] **Step 3: Commit**

```bash
git add tools/gen_vectors.py crypto/src/test/resources/vectors.json
git commit -m "test: генератор тест-векторов из vsc_desktop и векторы крипто-ядра"
```

---

### Task 4: Примитивы

**Files:**
- Create: `crypto/src/main/kotlin/ru/mobile/mys/crypto/Primitives.kt`
- Test: `crypto/src/test/kotlin/ru/mobile/mys/crypto/Vectors.kt`, `crypto/src/test/kotlin/ru/mobile/mys/crypto/PrimitivesTest.kt`

- [ ] **Step 1: Хелпер векторов `Vectors.kt`**

```kotlin
package ru.mobile.mys.crypto

import org.json.JSONArray
import org.json.JSONObject

object Vectors {
    val root: JSONObject by lazy {
        JSONObject(Vectors::class.java.getResource("/vectors.json")!!.readText())
    }
    fun obj(name: String): JSONObject = root.getJSONObject(name)
    fun list(name: String): List<JSONObject> = root.getJSONArray(name).objects()
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun String.unhex(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
fun JSONObject.bytes(key: String): ByteArray = getString(key).unhex()
```

- [ ] **Step 2: Падающий тест `PrimitivesTest.kt`**

```kotlin
package ru.mobile.mys.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class PrimitivesTest {
    @Test fun hkdfMatchesDesktop() {
        for (v in Vectors.list("hkdf")) {
            val out = hkdf(v.bytes("ikm"), v.getInt("len"), v.bytes("salt"), v.bytes("info"))
            assertEquals(v.getString("out"), out.hex())
        }
    }

    @Test fun x25519MatchesDesktop() {
        val v = Vectors.obj("x25519")
        val (aPriv, aPub) = x25519KeypairFromSeed(v.bytes("a_priv"))
        val (_, bPub) = x25519KeypairFromSeed(v.bytes("b_priv"))
        assertEquals(v.getString("a_pub"), aPub.hex())
        assertEquals(v.getString("b_pub"), bPub.hex())
        assertEquals(v.getString("shared"), x25519Shared(aPriv, bPub).hex())
        assertEquals(v.getString("shared"), x25519Shared(v.bytes("b_priv"), aPub).hex())
    }

    @Test fun randomX25519PairsAgree() {
        val a = generateX25519Keypair()
        val b = generateX25519Keypair()
        assertEquals(x25519Shared(a.first, b.second).hex(), x25519Shared(b.first, a.second).hex())
    }

    @Test fun aeadMatchesDesktopAndRejectsTamper() {
        val v = Vectors.obj("aead")
        val ct = aeadEncrypt(v.bytes("key"), v.bytes("nonce"), v.bytes("pt"), v.bytes("aad"))
        assertEquals(v.getString("ct"), ct.hex())
        assertEquals(v.getString("pt"), aeadDecrypt(v.bytes("key"), v.bytes("nonce"), ct, v.bytes("aad")).hex())
        ct[0] = (ct[0].toInt() xor 1).toByte()
        assertFails { aeadDecrypt(v.bytes("key"), v.bytes("nonce"), ct, v.bytes("aad")) }
    }

    @Test fun argon2idMatchesDesktop() {
        for (v in Vectors.list("argon2id")) {
            val out = argon2id(v.bytes("password"), v.bytes("salt"), v.getInt("len"),
                v.getInt("t"), v.getInt("m"), v.getInt("p"))
            assertEquals(v.getString("out"), out.hex())
        }
    }
}
```

- [ ] **Step 3: Убедиться, что тест падает**

Run: `./gradlew :crypto:test --tests '*PrimitivesTest*'`
Expected: FAIL — компиляция: `Unresolved reference: hkdf`.

- [ ] **Step 4: Реализация `Primitives.kt`**

```kotlin
package ru.mobile.mys.crypto

import java.security.SecureRandom
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519

/** Пара X25519: (private, public), по 32 байта — как кортеж в десктопе. */
typealias KeyPair = Pair<ByteArray, ByteArray>

private val rng = SecureRandom()

fun generateX25519Keypair(): KeyPair {
    val priv = ByteArray(32)
    X25519.generatePrivateKey(rng, priv)
    return x25519KeypairFromSeed(priv)
}

/** Детерминированная пара из 32-байтового seed (хендшейк выводит DH Боба из ISK). */
fun x25519KeypairFromSeed(seed: ByteArray): KeyPair {
    require(seed.size == 32) { "seed X25519 — 32 байта" }
    val pub = ByteArray(32)
    X25519.generatePublicKey(seed, 0, pub, 0)
    return seed.copyOf() to pub
}

fun x25519Shared(privateKey: ByteArray, peerPublic: ByteArray): ByteArray {
    val out = ByteArray(32)
    require(X25519.calculateAgreement(privateKey, 0, peerPublic, 0, out, 0)) {
        "X25519: нулевой общий секрет (невалидный ключ пира)"
    }
    return out
}

fun aeadEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
    chachaPoly(true, key, nonce, plaintext, aad)

/** Бросает InvalidCipherTextException при провале аутентификации. */
fun aeadDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray =
    chachaPoly(false, key, nonce, ciphertext, aad)

private fun chachaPoly(encrypt: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray, aad: ByteArray): ByteArray {
    val c = ChaCha20Poly1305()
    c.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, aad))
    val out = ByteArray(c.getOutputSize(input.size))
    var n = c.processBytes(input, 0, input.size, out, 0)
    n += c.doFinal(out, n)
    return out.copyOf(n)
}

fun hkdf(ikm: ByteArray, length: Int, salt: ByteArray, info: ByteArray): ByteArray {
    val gen = HKDFBytesGenerator(SHA256Digest())
    gen.init(HKDFParameters(ikm, salt, info))
    val out = ByteArray(length)
    gen.generateBytes(out, 0, length)
    return out
}

fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = HMac(SHA256Digest())
    mac.init(KeyParameter(key))
    mac.update(data, 0, data.size)
    return ByteArray(32).also { mac.doFinal(it, 0) }
}

fun sha512(data: ByteArray): ByteArray {
    val d = SHA512Digest()
    d.update(data, 0, data.size)
    return ByteArray(64).also { d.doFinal(it, 0) }
}

fun argon2id(
    password: ByteArray, salt: ByteArray, length: Int,
    timeCost: Int = 3, memoryCostKb: Int = 65536, parallelism: Int = 4,
): ByteArray {
    val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
        .withVersion(Argon2Parameters.ARGON2_VERSION_13)
        .withIterations(timeCost)
        .withMemoryAsKB(memoryCostKb)
        .withParallelism(parallelism)
        .withSalt(salt)
        .build()
    val gen = Argon2BytesGenerator()
    gen.init(params)
    return ByteArray(length).also { gen.generateBytes(password, it) }
}
```

- [ ] **Step 5: Тест проходит**

Run: `./gradlew :crypto:test --tests '*PrimitivesTest*'`
Expected: PASS, 5 тестов. Если не сошёлся `hkdf` с пустой солью — сверить: в Python пустой ключ HMAC эквивалентен 32 нулевым байтам; BC с `salt.size == 0` должен вести себя так же (иначе передавать `ByteArray(32)` при пустой соли).

- [ ] **Step 6: Commit**

```bash
git add crypto/
git commit -m "feat(crypto): примитивы — X25519, ChaCha20-Poly1305, HKDF, HMAC, SHA-512, Argon2id"
```

---

### Task 5: МЫС-transform

**Files:**
- Create: `crypto/src/main/kotlin/ru/mobile/mys/crypto/Transform.kt`
- Test: `crypto/src/test/kotlin/ru/mobile/mys/crypto/TransformTest.kt`

- [ ] **Step 1: Падающий тест**

```kotlin
package ru.mobile.mys.crypto

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class TransformTest {
    @Test fun encodeMatchesDesktop() {
        for (v in Vectors.list("transform")) {
            val enc = transformEncode(v.bytes("data"), v.bytes("key"))
            assertEquals(v.getString("enc"), enc.hex())
            assertEquals(v.getString("data"), transformDecode(enc, v.bytes("key")).hex())
        }
    }

    @Test fun decodeInvertsEncode() {
        val rnd = Random(42)
        for (size in listOf(0, 1, 40, 255, 256, 4097)) {
            val key = rnd.nextBytes(32)
            val data = rnd.nextBytes(size)
            assertEquals(data.hex(), transformDecode(transformEncode(data, key), key).hex())
        }
    }
}
```

- [ ] **Step 2: Убедиться, что падает**

Run: `./gradlew :crypto:test --tests '*TransformTest*'`
Expected: FAIL — `Unresolved reference: transformEncode`.

- [ ] **Step 3: Реализация `Transform.kt`**

```kotlin
package ru.mobile.mys.crypto

import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV

/*
 * МЫС-transform: обратимая ключевая биекция над байтами AEAD-шифротекста
 * (правила — vsc_desktop/CLAUDE.md). Порт mys_crypto/transform.py.
 *
 * Python берёт ChaCha20 из `cryptography` с 16-байтовым nonce = 4 байта счётчика
 * (LE) + 12 байт nonce; при нулевом nonce это RFC 7539 ChaCha20 с 12 нулевыми
 * байтами nonce и счётчиком 0 — ровно ChaCha7539Engine.
 */

private fun keystream(key: ByteArray, length: Int): ByteArray {
    if (length == 0) return EMPTY
    val subkey = hkdf(key, 32, EMPTY, "mys-transform-ks-key".toByteArray())
    val engine = ChaCha7539Engine()
    engine.init(true, ParametersWithIV(KeyParameter(subkey), ByteArray(12)))
    val out = ByteArray(length)
    engine.processBytes(ByteArray(length), 0, length, out, 0)
    return out
}

private fun buildSbox(key: ByteArray): Pair<IntArray, IntArray> {
    val stream = hkdf(key, 256, EMPTY, "mys-transform-sbox".toByteArray())
    val sbox = IntArray(256) { it }
    for (i in 255 downTo 1) {
        val j = (stream[255 - i].toInt() and 0xff) % (i + 1)
        val t = sbox[i]; sbox[i] = sbox[j]; sbox[j] = t
    }
    val inv = IntArray(256)
    for (index in 0 until 256) inv[sbox[index]] = index
    return sbox to inv
}

fun transformEncode(data: ByteArray, key: ByteArray): ByteArray {
    val ks = keystream(key, data.size)
    val (sbox, _) = buildSbox(key)
    return ByteArray(data.size) { i -> sbox[(data[i].toInt() + ks[i].toInt()) and 0xff].toByte() }
}

fun transformDecode(data: ByteArray, key: ByteArray): ByteArray {
    val ks = keystream(key, data.size)
    val (_, inv) = buildSbox(key)
    return ByteArray(data.size) { i -> (inv[data[i].toInt() and 0xff] - ks[i].toInt()).and(0xff).toByte() }
}
```

- [ ] **Step 4: Тест проходит**

Run: `./gradlew :crypto:test --tests '*TransformTest*'`
Expected: PASS, 2 теста.

- [ ] **Step 5: Commit**

```bash
git add crypto/
git commit -m "feat(crypto): МЫС-transform (совместим с десктопом)"
```

---

### Task 6: Double Ratchet + envelope

**Files:**
- Create: `crypto/src/main/kotlin/ru/mobile/mys/crypto/Ratchet.kt`, `crypto/src/main/kotlin/ru/mobile/mys/crypto/Envelope.kt`
- Test: `crypto/src/test/kotlin/ru/mobile/mys/crypto/RatchetTest.kt`

- [ ] **Step 1: Падающий тест**

```kotlin
package ru.mobile.mys.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class RatchetTest {
    private val env = Vectors.obj("envelope")
    private val messages = env.getJSONArray("messages").objects()
    private val tk = env.bytes("tk")

    @Test fun kdfsMatchDesktop() {
        val rk = Vectors.obj("kdf_rk")
        val (rkOut, ckOut) = kdfRk(rk.bytes("rk"), rk.bytes("dh_out"))
        assertEquals(rk.getString("rk_out"), rkOut.hex())
        assertEquals(rk.getString("ck_out"), ckOut.hex())
        val ck = Vectors.obj("kdf_ck")
        val (newCk, mk) = kdfCk(ck.bytes("ck"))
        assertEquals(ck.getString("ck_out"), newCk.hex())
        assertEquals(ck.getString("mk"), mk.hex())
        val mkv = Vectors.obj("message_keys")
        val (key, nonce) = deriveMessageKeys(mkv.bytes("mk"))
        assertEquals(mkv.getString("key"), key.hex())
        assertEquals(mkv.getString("nonce"), nonce.hex())
    }

    @Test fun transformKeyMatchesDesktop() {
        assertEquals(env.getString("tk"), deriveTransformKey(env.bytes("sk")).hex())
    }

    @Test fun sealMatchesDesktopByteForByte() {
        val alice = deserializeState(env.bytes("alice"))
        for (m in messages) assertEquals(m.getString("blob"), seal(alice, tk, m.bytes("pt")).hex())
        assertEquals(env.getString("alice_after"), serializeState(alice).hex())
    }

    @Test fun opensDesktopBlobsInOrder() {
        val bob = deserializeState(env.bytes("bob"))
        for (m in messages) assertEquals(m.getString("pt"), open(bob, tk, m.bytes("blob")).hex())
    }

    @Test fun opensFromDesktopSkippedKeys() {
        // Состояние Боба из десктопа после приёма №3 первым: 0..2 лежат в mkskipped.
        val bob = deserializeState(Vectors.root.getString("state_with_skipped").unhex())
        assertEquals(3, bob.mkskipped.size)
        for (m in messages.take(3)) assertEquals(m.getString("pt"), open(bob, tk, m.bytes("blob")).hex())
        assertTrue(bob.mkskipped.isEmpty())
    }

    @Test fun stateSerializationRoundTrips() {
        val blob = Vectors.root.getString("state_with_skipped").unhex()
        assertEquals(blob.hex(), serializeState(deserializeState(blob)).hex())
    }

    @Test fun replayAndTamperRejectedWithoutStateChange() {
        val bob = deserializeState(env.bytes("bob"))
        val first = messages[1].bytes("blob")
        open(bob, tk, messages[0].bytes("blob"))
        open(bob, tk, first)
        val before = serializeState(bob).hex()
        assertFails { open(bob, tk, first) } // replay
        val bad = messages[2].bytes("blob").also { it[it.size - 1] = (it.last().toInt() xor 1).toByte() }
        assertFails { open(bob, tk, bad) }
        assertEquals(before, serializeState(bob).hex())
    }

    @Test fun conversationBothWaysWithFreshKeys() {
        val sk = ByteArray(32) { it.toByte() }
        val bobKp = generateX25519Keypair()
        val alice = ratchetInitAlice(sk, bobKp.second)
        val bob = ratchetInitBob(sk, bobKp)
        val t = deriveTransformKey(sk)
        assertEquals("a1", open(bob, t, seal(alice, t, "a1".toByteArray())).decodeToString())
        assertEquals("b1", open(alice, t, seal(bob, t, "b1".toByteArray())).decodeToString())
        val a2 = seal(alice, t, "a2".toByteArray())
        val a3 = seal(alice, t, "a3".toByteArray())
        assertEquals("a3", open(bob, t, a3).decodeToString()) // вне порядка
        assertEquals("a2", open(bob, t, a2).decodeToString())
    }

    @Test fun tooManySkippedRejected() {
        val sk = ByteArray(32)
        val bobKp = generateX25519Keypair()
        val alice = ratchetInitAlice(sk, bobKp.second)
        val bob = ratchetInitBob(sk, bobKp)
        val t = deriveTransformKey(sk)
        open(bob, t, seal(alice, t, "x".toByteArray()))
        repeat(MAX_SKIP + 1) { seal(alice, t, "skip".toByteArray()) }
        assertFails { open(bob, t, seal(alice, t, "late".toByteArray())) }
    }
}
```

- [ ] **Step 2: Убедиться, что падает**

Run: `./gradlew :crypto:test --tests '*RatchetTest*'`
Expected: FAIL — `Unresolved reference: kdfRk`.

- [ ] **Step 3: Реализация `Ratchet.kt`**

```kotlin
package ru.mobile.mys.crypto

/* Double Ratchet — порт mys_crypto/ratchet.py (формат заголовка и состояния совпадает). */

class Header(val dh: ByteArray, val pn: Int, val n: Int) {
    fun serialize(): ByteArray = dh + pn.u32be() + n.u32be()

    companion object {
        const val SIZE = 40
        fun deserialize(blob: ByteArray) = Header(blob.copyOfRange(0, 32), blob.u32be(32), blob.u32be(36))
    }
}

/** Ключ словаря пропущенных: (DH пира, номер). Сравнение по содержимому массива. */
class SkippedId(val dh: ByteArray, val n: Int) {
    override fun equals(other: Any?) = other is SkippedId && n == other.n && dh.contentEquals(other.dh)
    override fun hashCode() = 31 * dh.contentHashCode() + n
}

/** Байтовые массивы внутри никогда не мутируются — только заменяются, поэтому copy() поверхностный по ним. */
class RatchetState(
    var dhs: KeyPair,
    var dhr: ByteArray?,
    var rk: ByteArray,
    var cks: ByteArray?,
    var ckr: ByteArray?,
    var ns: Int,
    var nr: Int,
    var pn: Int,
    val mkskipped: LinkedHashMap<SkippedId, ByteArray> = LinkedHashMap(),
) {
    fun copy() = RatchetState(dhs, dhr, rk, cks, ckr, ns, nr, pn, LinkedHashMap(mkskipped))

    internal fun assign(o: RatchetState) {
        dhs = o.dhs; dhr = o.dhr; rk = o.rk; cks = o.cks; ckr = o.ckr
        ns = o.ns; nr = o.nr; pn = o.pn
        mkskipped.clear(); mkskipped.putAll(o.mkskipped)
    }
}

const val MAX_SKIP = 1000
const val MAX_SKIP_SESSION = 2000

fun kdfRk(rk: ByteArray, dhOut: ByteArray): Pair<ByteArray, ByteArray> {
    val out = hkdf(dhOut, 64, rk, "mys-ratchet-rk".toByteArray())
    return out.copyOfRange(0, 32) to out.copyOfRange(32, 64)
}

/** Возвращает (новый ck, mk). */
fun kdfCk(ck: ByteArray): Pair<ByteArray, ByteArray> =
    hmacSha256(ck, byteArrayOf(2)) to hmacSha256(ck, byteArrayOf(1))

/** Возвращает (ключ AEAD 32 байта, nonce 12 байт). */
fun deriveMessageKeys(mk: ByteArray): Pair<ByteArray, ByteArray> {
    val out = hkdf(mk, 44, ByteArray(32), "mys-ratchet-msg".toByteArray())
    return out.copyOfRange(0, 32) to out.copyOfRange(32, 44)
}

fun ratchetInitAlice(sk: ByteArray, bobDhPub: ByteArray): RatchetState {
    val dhs = generateX25519Keypair()
    val (rk, cks) = kdfRk(sk, x25519Shared(dhs.first, bobDhPub))
    return RatchetState(dhs, bobDhPub, rk, cks, null, 0, 0, 0)
}

fun ratchetInitBob(sk: ByteArray, bobDhKeypair: KeyPair): RatchetState =
    RatchetState(bobDhKeypair, null, sk, null, null, 0, 0, 0)

fun ratchetEncrypt(state: RatchetState, plaintext: ByteArray, ad: ByteArray = EMPTY): Pair<Header, ByteArray> {
    val (ck, mk) = kdfCk(checkNotNull(state.cks) { "нет отправляющей цепочки" })
    state.cks = ck
    val header = Header(state.dhs.second, state.pn, state.ns)
    state.ns += 1
    val (key, nonce) = deriveMessageKeys(mk)
    return header to aeadEncrypt(key, nonce, plaintext, ad + header.serialize())
}

/** Атомарно: при любой ошибке состояние не меняется. */
fun ratchetDecrypt(state: RatchetState, header: Header, ciphertext: ByteArray, ad: ByteArray = EMPTY): ByteArray {
    val work = state.copy()
    val plaintext = decryptInto(work, header, ciphertext, ad)
    state.assign(work)
    return plaintext
}

private fun decryptInto(state: RatchetState, header: Header, ciphertext: ByteArray, ad: ByteArray): ByteArray {
    trySkipped(state, header, ciphertext, ad)?.let { return it }
    if (state.dhr?.contentEquals(header.dh) != true) {
        skipMessageKeys(state, header.pn)
        dhRatchet(state, header)
    }
    skipMessageKeys(state, header.n)
    val (ck, mk) = kdfCk(state.ckr!!)
    state.ckr = ck
    state.nr += 1
    val (key, nonce) = deriveMessageKeys(mk)
    return aeadDecrypt(key, nonce, ciphertext, ad + header.serialize())
}

private fun trySkipped(state: RatchetState, header: Header, ciphertext: ByteArray, ad: ByteArray): ByteArray? {
    val mk = state.mkskipped.remove(SkippedId(header.dh, header.n)) ?: return null
    val (key, nonce) = deriveMessageKeys(mk)
    return aeadDecrypt(key, nonce, ciphertext, ad + header.serialize())
}

private fun skipMessageKeys(state: RatchetState, until: Int) {
    var ckr = state.ckr ?: return
    check(state.nr + MAX_SKIP >= until) { "too many skipped messages" }
    while (state.nr < until) {
        val (ck, mk) = kdfCk(ckr)
        ckr = ck
        state.ckr = ck
        storeSkipped(state, SkippedId(state.dhr!!, state.nr), mk)
        state.nr += 1
    }
}

private fun storeSkipped(state: RatchetState, id: SkippedId, mk: ByteArray) {
    state.mkskipped[id] = mk
    val it = state.mkskipped.keys.iterator()
    while (state.mkskipped.size > MAX_SKIP_SESSION) { it.next(); it.remove() }
}

private fun dhRatchet(state: RatchetState, header: Header) {
    state.pn = state.ns
    state.ns = 0
    state.nr = 0
    state.dhr = header.dh
    kdfRk(state.rk, x25519Shared(state.dhs.first, header.dh)).let { (rk, ck) -> state.rk = rk; state.ckr = ck }
    state.dhs = generateX25519Keypair()
    kdfRk(state.rk, x25519Shared(state.dhs.first, header.dh)).let { (rk, ck) -> state.rk = rk; state.cks = ck }
}

private const val STATE_VERSION: Byte = 1

fun serializeState(state: RatchetState): ByteArray {
    val buf = java.io.ByteArrayOutputStream()
    fun put(b: ByteArray) = buf.write(b)
    fun putOpt(b: ByteArray?) { if (b == null) buf.write(0) else { buf.write(1); put(b) } }
    buf.write(STATE_VERSION.toInt())
    put(state.dhs.first); put(state.dhs.second)
    putOpt(state.dhr)
    put(state.rk)
    putOpt(state.cks); putOpt(state.ckr)
    put(state.ns.u32be()); put(state.nr.u32be()); put(state.pn.u32be())
    put(state.mkskipped.size.u32be())
    for ((id, mk) in state.mkskipped) { put(id.dh); put(id.n.u32be()); put(mk) }
    return buf.toByteArray()
}

fun deserializeState(blob: ByteArray): RatchetState {
    require(blob[0] == STATE_VERSION) { "unsupported ratchet state version" }
    var pos = 1
    fun take(n: Int) = blob.copyOfRange(pos, pos + n).also { pos += n }
    fun takeOpt(): ByteArray? = if (blob[pos++].toInt() == 0) null else take(32)
    fun takeInt() = blob.u32be(pos).also { pos += 4 }
    val dhs = take(32) to take(32)
    val dhr = takeOpt()
    val rk = take(32)
    val cks = takeOpt()
    val ckr = takeOpt()
    val ns = takeInt(); val nr = takeInt(); val pn = takeInt()
    val skipped = LinkedHashMap<SkippedId, ByteArray>()
    repeat(takeInt()) {
        val dh = take(32)
        val n = takeInt()
        skipped[SkippedId(dh, n)] = take(32)
    }
    return RatchetState(dhs, dhr, rk, cks, ckr, ns, nr, pn, skipped)
}
```

- [ ] **Step 4: Реализация `Envelope.kt`**

```kotlin
package ru.mobile.mys.crypto

/* Конверт: ratchet-шифротекст (header ‖ AEAD) под МЫС-transform. Порт mys_crypto/envelope.py. */

fun deriveTransformKey(sk: ByteArray): ByteArray = hkdf(sk, 32, EMPTY, "mys-transform-master".toByteArray())

fun seal(state: RatchetState, transformKey: ByteArray, plaintext: ByteArray, ad: ByteArray = EMPTY): ByteArray {
    val (header, ct) = ratchetEncrypt(state, plaintext, ad)
    return transformEncode(header.serialize() + ct, transformKey)
}

fun open(state: RatchetState, transformKey: ByteArray, blob: ByteArray, ad: ByteArray = EMPTY): ByteArray {
    val wire = transformDecode(blob, transformKey)
    require(wire.size >= Header.SIZE) { "конверт короче заголовка" }
    return ratchetDecrypt(state, Header.deserialize(wire), wire.copyOfRange(Header.SIZE, wire.size), ad)
}
```

- [ ] **Step 5: Тест проходит**

Run: `./gradlew :crypto:test --tests '*RatchetTest*'`
Expected: PASS, 9 тестов.

- [ ] **Step 6: Commit**

```bash
git add crypto/
git commit -m "feat(crypto): Double Ratchet и envelope (байт-в-байт с десктопом)"
```

---

### Task 7: ristretto255 + CPace

**Files:**
- Create: `crypto/src/main/kotlin/ru/mobile/mys/crypto/Ristretto.kt`, `crypto/src/main/kotlin/ru/mobile/mys/crypto/CPace.kt`
- Modify: `app/src/main/kotlin/ru/mobile/mys/MainActivity.kt`
- Test: `crypto/src/test/kotlin/ru/mobile/mys/crypto/CPaceTest.kt`

- [ ] **Step 1: Падающий тест**

```kotlin
package ru.mobile.mys.crypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class CPaceTest {
    private val r = Ristretto(LazySodiumJava(SodiumJava()))

    @Test fun matchesDesktopWithFixedScalars() {
        for (v in Vectors.list("cpace")) {
            val (prs, sid, ci, ad) = listOf("prs", "sid", "ci", "ad").map { v.bytes(it) }
            assertEquals(v.getString("g"), cpaceGenerator(r, prs, sid, ci, ad).hex())
            val (a, ya) = cpaceMsg(r, prs, sid, ci, ad, y = v.bytes("ya"))
            val (b, yb) = cpaceMsg(r, prs, sid, ci, ad, y = v.bytes("yb"))
            assertEquals(v.getString("Ya"), ya.hex())
            assertEquals(v.getString("Yb"), yb.hex())
            assertEquals(v.getString("isk"), cpaceFinish(r, a, yb).hex())
            assertEquals(v.getString("isk"), cpaceFinish(r, b, ya).hex())
        }
    }

    @Test fun samePrsAgreesDifferentPrsDiffers() {
        val sid = ByteArray(16)
        val (a, ya) = cpaceMsg(r, "фраза".toByteArray(), sid)
        val (b, yb) = cpaceMsg(r, "фраза".toByteArray(), sid)
        assertEquals(cpaceFinish(r, a, yb).hex(), cpaceFinish(r, b, ya).hex())
        val (c, yc) = cpaceMsg(r, "другая".toByteArray(), sid)
        assertNotEquals(cpaceFinish(r, a, yc).hex(), cpaceFinish(r, c, ya).hex())
    }

    @Test fun identityPointRejected() {
        val (a, _) = cpaceMsg(r, "фраза".toByteArray(), ByteArray(16))
        assertFailsWith<RistrettoException> { cpaceFinish(r, a, ByteArray(32)) }
    }
}
```

- [ ] **Step 2: Убедиться, что падает**

Run: `./gradlew :crypto:test --tests '*CPaceTest*'`
Expected: FAIL — `Unresolved reference: Ristretto`.

- [ ] **Step 3: Реализация `Ristretto.kt`**

```kotlin
package ru.mobile.mys.crypto

import com.goterl.lazysodium.interfaces.Ristretto255

class RistrettoException(message: String) : Exception(message)

/**
 * ristretto255 из libsodium. Реализацию `Native` даёт платформа:
 * `LazySodiumAndroid(SodiumAndroid())` в приложении, `LazySodiumJava(SodiumJava())` в тестах.
 */
class Ristretto(private val sodium: Ristretto255.Native) {
    fun fromHash(hash: ByteArray): ByteArray {
        require(hash.size == 64) { "from_hash ждёт 64 байта" }
        val out = ByteArray(32)
        if (!sodium.cryptoCoreRistretto255FromHash(out, hash)) throw RistrettoException("from_hash провалился")
        return out
    }

    /** scalar·point; отказ на identity/невалидной точке. */
    fun scalarmult(scalar: ByteArray, point: ByteArray): ByteArray {
        require(scalar.size == 32 && point.size == 32) { "неверная длина скаляра или точки" }
        val out = ByteArray(32)
        if (!sodium.cryptoScalarmultRistretto255(out, scalar, point)) {
            throw RistrettoException("scalarmult: identity или невалидная точка")
        }
        return out
    }

    fun scalarRandom(): ByteArray = ByteArray(32).also { sodium.cryptoCoreRistretto255ScalarRandom(it) }
}
```

- [ ] **Step 4: Реализация `CPace.kt`**

```kotlin
package ru.mobile.mys.crypto

/*
 * CPace на ristretto255 — порт mys_crypto/pake.py (упрощённое lv-кодирование
 * транскрипта с DSI-метками МЫС; совместимость только с десктопом МЫС).
 */

private val DSI_GEN = "MYS-CPace-ristretto255-gen".toByteArray()
private val DSI_ISK = "MYS-CPace-ristretto255-ISK".toByteArray()

private fun lv(chunk: ByteArray): ByteArray {
    require(chunk.size <= 0xFFFF) { "поле CPace длиннее 65535 байт" }
    return byteArrayOf((chunk.size ushr 8).toByte(), chunk.size.toByte()) + chunk
}

/** Эфемерное состояние стороны между cpaceMsg и cpaceFinish. */
class CPaceState(val y: ByteArray, val bigY: ByteArray, val sid: ByteArray)

fun cpaceGenerator(r: Ristretto, prs: ByteArray, sid: ByteArray, ci: ByteArray = EMPTY, ad: ByteArray = EMPTY): ByteArray =
    r.fromHash(sha512(DSI_GEN + lv(prs) + lv(sid) + lv(ci) + lv(ad)))

/** `y` переопределяется только в тестах (фиксированные скаляры из векторов). */
fun cpaceMsg(
    r: Ristretto, prs: ByteArray, sid: ByteArray, ci: ByteArray = EMPTY, ad: ByteArray = EMPTY,
    y: ByteArray = r.scalarRandom(),
): Pair<CPaceState, ByteArray> {
    val bigY = r.scalarmult(y, cpaceGenerator(r, prs, sid, ci, ad))
    return CPaceState(y, bigY, sid) to bigY
}

/** 64-байтовый ISK; RistrettoException на identity/битой точке пира. */
fun cpaceFinish(r: Ristretto, state: CPaceState, peerY: ByteArray): ByteArray {
    val k = r.scalarmult(state.y, peerY)
    val (low, high) = if (compareUnsigned(state.bigY, peerY) <= 0) state.bigY to peerY else peerY to state.bigY
    return sha512(DSI_ISK + lv(state.sid) + lv(low) + lv(high) + lv(k))
}
```

- [ ] **Step 5: Тест проходит**

Run: `./gradlew :crypto:test --tests '*CPaceTest*'`
Expected: PASS, 3 теста. Если `SodiumJava()` не находит нативную библиотеку на NixOS — использовать системную из devShell: `SodiumJava(System.getenv("MYS_LIBSODIUM"))` в тесте.

- [ ] **Step 6: Смоук ristretto255 в приложении — `MainActivity.kt`**

```kotlin
package ru.mobile.mys

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import ru.mobile.mys.crypto.Ristretto

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ponytail: временный смоук libsodium на устройстве, уйдёт с UI под-проекта 5.
        val ok = runCatching { Ristretto(LazySodiumAndroid(SodiumAndroid())).scalarRandom() }.isSuccess
        setContent { Text("МЫС · ristretto255: ${if (ok) "ok" else "нет"}") }
    }
}
```

- [ ] **Step 7: Сборка APK**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add crypto/ app/
git commit -m "feat(crypto): CPace на ristretto255 (lazysodium), смоук в приложении"
```

---

### Task 8: CLAUDE.md и финальная проверка

**Files:**
- Create: `CLAUDE.md`

- [ ] **Step 1: Написать `CLAUDE.md`**

```markdown
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
```

- [ ] **Step 2: Полная проверка**

Run: `./gradlew :crypto:test :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`; в `crypto/build/test-results/test/*.xml` — 19 тестов, 0 failures.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: CLAUDE.md для мобильного проекта"
```
