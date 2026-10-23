# МЫС Mesh — этап 1 (ядро протокола): план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ядро mesh-сети МЫС на Python (эталон, vsc_desktop) и Kotlin (vsc_mobile): шифрование линка, пакеты, маршрутизация по запросу, `find(tagKey) → (роль, транспорт)`.

**Architecture:** три слоя — Wire (провод: память/TCP) → Link (crypto_kx + secretstream) → MeshNode (RREQ/RREP/DATA/RERR, таблицы в памяти). Ядро не знает о радио/UI/хранилище и не зависит от P2P-сессии: адаптер к `Transport` — в тестах (в этапе 2 — в P2PService). Спецификация: `docs/superpowers/specs/2026-10-23-mys-mesh-core-design.md`.

**Tech Stack:** Python 3.13 asyncio + ctypes libsodium; Kotlin/JVM + coroutines + lazysodium (KeyExchange/SecretStream) + BouncyCastle (BLAKE2b, HKDF).

Пути ниже — относительно `~/VS_Projects`. Блок кода после строки `<!-- file: PATH -->` — полное содержимое файла (так их извлекает `tools/plan_files.py`). Команды тестов:

- десктоп: `S=$SCRATCH bash $SCRATCH/dtest.sh -q tests/test_mesh_*.py` (venv десктопа + `MYS_LIBSODIUM`);
- телефон: `nix develop -c ./gradlew :mesh:test`.

---

### Task 1: libsodium для линка (Python)

**Files:** Create `vsc_desktop/src/mys_crypto/linkcrypto.py`, `vsc_desktop/tests/test_mesh_linkcrypto.py`

- [ ] **Step 1: тест**

<!-- file: vsc_desktop/tests/test_mesh_linkcrypto.py -->
```python
import pytest

from mys_crypto import linkcrypto as L


def _pair():
    a_pk, a_sk = L.kx_keypair()
    b_pk, b_sk = L.kx_keypair()
    return L.kx_session_keys(a_pk, a_sk, b_pk), L.kx_session_keys(b_pk, b_sk, a_pk)


def test_kx_keys_match_crosswise():
    (a_rx, a_tx), (b_rx, b_tx) = _pair()
    assert a_tx == b_rx and a_rx == b_tx and a_tx != a_rx


def test_stream_roundtrip_and_replay_rejected():
    (_, tx), (rx, _) = _pair()
    push = L.Pusher(tx)
    pull = L.Puller(rx, push.header)
    c1, c2 = push.push(b"one"), push.push(b"")
    assert pull.pull(c1) == b"one"
    assert pull.pull(c2) == b""
    with pytest.raises(L.LinkCryptoError):
        pull.pull(c2)


def test_tampered_rejected():
    (_, tx), (rx, _) = _pair()
    push = L.Pusher(tx)
    pull = L.Puller(rx, push.header)
    c = bytearray(push.push(b"secret"))
    c[-1] ^= 1
    with pytest.raises(L.LinkCryptoError):
        pull.pull(bytes(c))


def test_same_or_bad_key_rejected():
    pk, sk = L.kx_keypair()
    with pytest.raises(L.LinkCryptoError):
        L.kx_session_keys(pk, sk, pk)
    with pytest.raises(L.LinkCryptoError):
        L.kx_session_keys(pk, sk, b"short")
```

- [ ] **Step 2:** `dtest.sh -q tests/test_mesh_linkcrypto.py` → FAIL (нет модуля).
- [ ] **Step 3: реализация**

<!-- file: vsc_desktop/src/mys_crypto/linkcrypto.py -->
```python
"""Шифрование линка mesh: crypto_kx (X25519) + secretstream XChaCha20-Poly1305.

Только вызовы libsodium (тот же экземпляр, что у ristretto255): своей
криптографии и I/O здесь нет. Используется :mod:`mys_mesh.link`.
"""

import ctypes

from ._ristretto import _lib

KX_PUBLICKEYBYTES = 32
KX_SESSIONKEYBYTES = 32
HEADERBYTES = 24
ABYTES = 17
TAG_MESSAGE = 0


class LinkCryptoError(Exception):
    """Сбой обмена ключами или расшифровки кадра линка."""


_P = ctypes.c_char_p
_ULL = ctypes.c_ulonglong


def _fn(name, argtypes, restype=ctypes.c_int):
    f = getattr(_lib, name)
    f.argtypes = argtypes
    f.restype = restype
    return f


_kx_keypair = _fn("crypto_kx_keypair", [_P, _P])
_kx_client = _fn("crypto_kx_client_session_keys", [_P] * 5)
_kx_server = _fn("crypto_kx_server_session_keys", [_P] * 5)
_statebytes = _fn("crypto_secretstream_xchacha20poly1305_statebytes", [], ctypes.c_size_t)
_init_push = _fn("crypto_secretstream_xchacha20poly1305_init_push", [_P, _P, _P])
_push = _fn("crypto_secretstream_xchacha20poly1305_push",
            [_P, _P, ctypes.c_void_p, _P, _ULL, _P, _ULL, ctypes.c_ubyte])
_init_pull = _fn("crypto_secretstream_xchacha20poly1305_init_pull", [_P, _P, _P])
_pull = _fn("crypto_secretstream_xchacha20poly1305_pull",
            [_P, _P, ctypes.c_void_p, _P, _P, _ULL, _P, _ULL])
_STATEBYTES = _statebytes()


def _buf(n: int):
    return ctypes.create_string_buffer(max(n, 1))


def kx_keypair() -> tuple[bytes, bytes]:
    """Временная пара X25519 для одного линка: (pk, sk)."""
    pk, sk = _buf(KX_PUBLICKEYBYTES), _buf(KX_PUBLICKEYBYTES)
    if _kx_keypair(pk, sk) != 0:  # pragma: no cover
        raise LinkCryptoError("crypto_kx_keypair")
    return pk.raw, sk.raw


def kx_session_keys(pk: bytes, sk: bytes, peer_pk: bytes) -> tuple[bytes, bytes]:
    """(rx, tx). «Клиент» crypto_kx — сторона с меньшим pk (побайтово)."""
    if len(peer_pk) != KX_PUBLICKEYBYTES or peer_pk == pk:
        raise LinkCryptoError("неверный ключ соседа")
    rx, tx = _buf(KX_SESSIONKEYBYTES), _buf(KX_SESSIONKEYBYTES)
    fn = _kx_client if pk < peer_pk else _kx_server
    if fn(rx, tx, pk, sk, peer_pk) != 0:
        raise LinkCryptoError("crypto_kx отверг ключ соседа")
    return rx.raw, tx.raw


class Pusher:
    """Шифрующая сторона secretstream; ``header`` отправляется соседу первым."""

    def __init__(self, key: bytes):
        self._state = _buf(_STATEBYTES)
        header = _buf(HEADERBYTES)
        if _init_push(self._state, header, key) != 0:  # pragma: no cover
            raise LinkCryptoError("init_push")
        self.header = header.raw

    def push(self, message: bytes) -> bytes:
        c = _buf(len(message) + ABYTES)
        if _push(self._state, c, None, message, len(message), None, 0, TAG_MESSAGE) != 0:
            raise LinkCryptoError("push")  # pragma: no cover
        return c.raw[: len(message) + ABYTES]


class Puller:
    """Расшифровывающая сторона: подмена, повтор, перестановка — ``LinkCryptoError``."""

    def __init__(self, key: bytes, header: bytes):
        self._state = _buf(_STATEBYTES)
        if len(header) != HEADERBYTES or _init_pull(self._state, header, key) != 0:
            raise LinkCryptoError("неверный заголовок потока")

    def pull(self, ciphertext: bytes) -> bytes:
        n = len(ciphertext) - ABYTES
        if n < 0:
            raise LinkCryptoError("кадр короче ABYTES")
        m, tag = _buf(n), _buf(1)
        if _pull(self._state, m, None, tag, ciphertext, len(ciphertext), None, 0) != 0:
            raise LinkCryptoError("кадр не расшифрован")
        if tag.raw[0] != TAG_MESSAGE:
            raise LinkCryptoError("неожиданный тег потока")
        return m.raw[:n]
```

- [ ] **Step 4:** тесты → PASS. **Step 5:** commit `feat(mesh): crypto_kx + secretstream для линка`.

### Task 2: метки и пакеты (Python)

**Files:** Create `vsc_desktop/src/mys_mesh/__init__.py`, `tags.py`, `packet.py`, `tests/test_mesh_packet.py`

<!-- file: vsc_desktop/tests/test_mesh_packet.py -->
```python
import pytest

from mys_mesh import packet as P
from mys_mesh.tags import hour_of, tag_for_hour, tag_key

ID, TAG, N1, N2 = bytes(range(16)), bytes(range(32)), b"\x01" * 16, b"\x02" * 16


@pytest.mark.parametrize("p", [
    P.Packet(P.RREQ, ID, ttl=8, hops=3, tag=TAG, nonce=N1),
    P.Packet(P.RREP, ID, hops=1, route_id=N2, nonce=N1),
    P.Packet(P.DATA, ID, payload=b"mys"),
    P.Packet(P.DATA, ID),
    P.Packet(P.RERR, ID),
])
def test_roundtrip(p):
    assert P.decode(P.encode(p)) == p


@pytest.mark.parametrize("raw", [
    b"",
    bytes([2, P.RERR, 0, 0]) + ID,                       # версия
    bytes([1, 9, 0, 0]) + ID,                            # тип
    bytes([1, P.RREQ, 8, 0]) + ID + TAG,                 # короткий RREQ
    bytes([1, P.RREQ, 0, 0]) + ID + TAG + N1,            # ttl 0
    bytes([1, P.RREQ, 9, 0]) + ID + TAG + N1,            # ttl > max
    bytes([1, P.RREP, 0, 0]) + ID + N1,                  # короткий RREP
    bytes([1, P.RERR, 0, 0]) + ID + b"x",                # RERR с телом
    bytes([1, P.DATA, 1, 0]) + ID,                       # ttl в DATA
    bytes([1, P.DATA, 0, 0]) + ID + bytes(P.MAX_DATA + 1),
])
def test_malformed(raw):
    with pytest.raises(P.MeshProtocolError):
        P.decode(raw)


def test_tags_rotate_hourly_and_need_key():
    key = tag_key(b"\x07" * 32)
    assert len(key) == 32 and key != tag_key(b"\x08" * 32)
    assert tag_for_hour(key, 5) != tag_for_hour(key, 6)
    assert len(tag_for_hour(key, 5)) == 32
    assert hour_of(7200.5) == 2
```

<!-- file: vsc_desktop/src/mys_mesh/__init__.py -->
```python
"""Mesh-сеть МЫС: маршрутизация P2P-кадров через соседние узлы (этап 1 — ядро).

Спецификация: vsc_mobile/docs/superpowers/specs/2026-10-23-mys-mesh-core-design.md.
"""
```

<!-- file: vsc_desktop/src/mys_mesh/tags.py -->
```python
"""Метки каналов: ключ из prs, метка меняется каждый час (ретрансляторы не следят за каналом)."""

import hashlib

from mys_crypto.primitives import hkdf


def tag_key(prs: bytes) -> bytes:
    """Ключ меток из prs — не из room_id: room_id видит сервер."""
    return hkdf(prs, 32, salt=b"", info=b"mys-mesh-tag-key")


def tag_for_hour(key: bytes, hour: int) -> bytes:
    return hashlib.blake2b(hour.to_bytes(8, "big"), digest_size=32, key=key,
                           person=b"mys-mesh-tag").digest()


def hour_of(unix_seconds: float) -> int:
    return int(unix_seconds // 3600)
```

<!-- file: vsc_desktop/src/mys_mesh/packet.py -->
```python
"""Mesh-пакет: ``version | type | ttl | hops | id(16) | body`` (big-endian)."""

from dataclasses import dataclass

VERSION = 1
RREQ, RREP, DATA, RERR = 1, 2, 3, 4
ID_LEN, TAG_LEN, NONCE_LEN = 16, 32, 16
HEADER_LEN = 4 + ID_LEN
MAX_DATA = 65_600
MAX_PACKET = HEADER_LEN + MAX_DATA
TTL_MAX = 8


class MeshProtocolError(Exception):
    """Неверный пакет/линк — соединение с этим соседом рвётся."""


@dataclass(frozen=True)
class Packet:
    type: int
    id: bytes
    ttl: int = 0
    hops: int = 0
    tag: bytes = b""
    nonce: bytes = b""
    route_id: bytes = b""
    payload: bytes = b""


def encode(p: Packet) -> bytes:
    if p.type == RREQ:
        body = p.tag + p.nonce
    elif p.type == RREP:
        body = p.route_id + p.nonce
    elif p.type == DATA:
        body = p.payload
    else:
        body = b""
    return bytes([VERSION, p.type, p.ttl, p.hops]) + p.id + body


def decode(raw: bytes, ttl_max: int = TTL_MAX) -> Packet:
    if not HEADER_LEN <= len(raw) <= MAX_PACKET:
        raise MeshProtocolError("неверная длина пакета")
    version, ptype, ttl, hops = raw[0], raw[1], raw[2], raw[3]
    pid, body = bytes(raw[4:HEADER_LEN]), bytes(raw[HEADER_LEN:])
    if version != VERSION:
        raise MeshProtocolError("неизвестная версия")
    if ptype == RREQ:
        if len(body) != TAG_LEN + NONCE_LEN or not 1 <= ttl <= ttl_max:
            raise MeshProtocolError("неверный RREQ")
        return Packet(RREQ, pid, ttl, hops, tag=body[:TAG_LEN], nonce=body[TAG_LEN:])
    if ttl != 0:
        raise MeshProtocolError("ttl только у RREQ")
    if ptype == RREP:
        if len(body) != ID_LEN + NONCE_LEN:
            raise MeshProtocolError("неверный RREP")
        return Packet(RREP, pid, 0, hops, route_id=body[:ID_LEN], nonce=body[ID_LEN:])
    if ptype == DATA:
        return Packet(DATA, pid, 0, hops, payload=body)
    if ptype == RERR:
        if body:
            raise MeshProtocolError("RERR с телом")
        return Packet(RERR, pid, 0, hops)
    raise MeshProtocolError("неизвестный тип")
```

- [ ] Тест → FAIL, файлы → PASS, commit `feat(mesh): метки каналов и формат пакетов`.

### Task 3: провода и линк (Python)

**Files:** Create `vsc_desktop/src/mys_mesh/wire.py`, `link.py`, `tests/test_mesh_link.py`

<!-- file: vsc_desktop/tests/test_mesh_link.py -->
```python
import asyncio

import pytest

from mys_mesh.link import Link
from mys_mesh.packet import MeshProtocolError
from mys_mesh.wire import MAX_FRAME, MemoryWire, TcpWire, WireClosed


async def _links(wa, wb):
    return await asyncio.gather(Link.open(wa), Link.open(wb))


async def test_handshake_and_exchange():
    la, lb = await _links(*MemoryWire.pair())
    await la.send(b"ping")
    await lb.send(b"pong")
    assert await lb.recv() == b"ping"
    assert await la.recv() == b"pong"


async def test_garbage_frame_breaks_link():
    wa, wb = MemoryWire.pair()
    la, lb = await _links(wa, wb)
    await wa.send(b"x" * 40)
    with pytest.raises(MeshProtocolError):
        await lb.recv()


async def test_bad_magic_rejected():
    wa, wb = MemoryWire.pair()
    opening = asyncio.create_task(Link.open(wb))
    await wa.send(b"XXXX\x01" + bytes(32))
    with pytest.raises(MeshProtocolError):
        await opening


async def test_oversize_frame_closes_wire():
    wa, wb = MemoryWire.pair()
    await wa.send(bytes(MAX_FRAME + 1))
    with pytest.raises(WireClosed):
        await wb.recv()


async def test_close_reaches_both_sides():
    wa, wb = MemoryWire.pair()
    await wa.close()
    for w in (wa, wb):
        with pytest.raises(WireClosed):
            await w.recv()


async def test_tcp_wire_link():
    accepted = asyncio.get_running_loop().create_future()
    server = await asyncio.start_server(
        lambda r, w: accepted.set_result(TcpWire(r, w)), "127.0.0.1", 0)
    port = server.sockets[0].getsockname()[1]
    client = await TcpWire.connect("127.0.0.1", port)
    la, lb = await _links(client, await accepted)
    await la.send(b"tcp")
    assert await lb.recv() == b"tcp"
    await la.close()
    with pytest.raises(WireClosed):
        await lb.recv()
    server.close()
```

<!-- file: vsc_desktop/src/mys_mesh/wire.py -->
```python
"""Провода: упорядоченный надёжный поток кадров к соседу (память, TCP).

Шифрования здесь нет — его делает :mod:`.link`. BLE/Wi-Fi Direct — этап 3.
"""

import asyncio
from abc import ABC, abstractmethod

MAX_FRAME = 72 * 1024


class WireClosed(Exception):
    """Провод закрыт или оборвался."""


class Wire(ABC):
    @abstractmethod
    async def send(self, frame: bytes) -> None: ...

    @abstractmethod
    async def recv(self) -> bytes: ...

    @abstractmethod
    async def close(self) -> None: ...


_CLOSED = object()


class MemoryWire(Wire):
    """Пара очередей; ``close`` любой стороны обрывает ``recv`` обеих."""

    def __init__(self, inbox: asyncio.Queue, outbox: asyncio.Queue):
        self._inbox, self._outbox, self._closed = inbox, outbox, False

    @classmethod
    def pair(cls) -> tuple["MemoryWire", "MemoryWire"]:
        ab, ba = asyncio.Queue(), asyncio.Queue()
        return cls(ba, ab), cls(ab, ba)

    async def send(self, frame: bytes) -> None:
        if self._closed:
            raise WireClosed("провод закрыт")
        self._outbox.put_nowait(bytes(frame))

    async def recv(self) -> bytes:
        item = await self._inbox.get()
        if item is _CLOSED:
            self._inbox.put_nowait(_CLOSED)
            raise WireClosed("провод закрыт")
        if len(item) > MAX_FRAME:
            raise WireClosed("кадр больше MAX_FRAME")
        return item

    async def close(self) -> None:
        if not self._closed:
            self._closed = True
            self._outbox.put_nowait(_CLOSED)
            self._inbox.put_nowait(_CLOSED)


class TcpWire(Wire):
    """Кадры ``u32be длина ‖ байты`` поверх TCP."""

    def __init__(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        self._r, self._w = reader, writer

    @classmethod
    async def connect(cls, host: str, port: int) -> "TcpWire":
        r, w = await asyncio.open_connection(host, port)
        return cls(r, w)

    async def send(self, frame: bytes) -> None:
        try:
            self._w.write(len(frame).to_bytes(4, "big") + frame)
            await self._w.drain()
        except (ConnectionError, OSError) as e:
            raise WireClosed(str(e)) from e

    async def recv(self) -> bytes:
        try:
            n = int.from_bytes(await self._r.readexactly(4), "big")
            if n > MAX_FRAME:
                raise WireClosed("кадр больше MAX_FRAME")
            return await self._r.readexactly(n)
        except (asyncio.IncompleteReadError, ConnectionError, OSError) as e:
            raise WireClosed(str(e)) from e

    async def close(self) -> None:
        self._w.close()
        try:
            await self._w.wait_closed()
        except (ConnectionError, OSError):
            pass
```

<!-- file: vsc_desktop/src/mys_mesh/link.py -->
```python
"""Линк: временные X25519-ключи → crypto_kx → secretstream в обе стороны.

Постоянной личности у узла нет; содержимое защищено сквозным шифрованием
канала, линк скрывает метки и маршруты от наблюдателя эфира/сети.
"""

import asyncio

from mys_crypto.linkcrypto import LinkCryptoError, Puller, Pusher, kx_keypair, kx_session_keys

from .packet import MeshProtocolError
from .wire import Wire, WireClosed

MAGIC = b"MYSM"
VERSION = 1
HANDSHAKE_TIMEOUT = 10.0


class Link:
    def __init__(self, wire: Wire, push: Pusher, pull: Puller):
        self._wire, self._push, self._pull = wire, push, pull

    @classmethod
    async def open(cls, wire: Wire, timeout: float = HANDSHAKE_TIMEOUT) -> "Link":
        async def run() -> "Link":
            pk, sk = kx_keypair()
            await wire.send(MAGIC + bytes([VERSION]) + pk)
            hello = await wire.recv()
            if len(hello) != 37 or hello[:4] != MAGIC or hello[4] != VERSION:
                raise MeshProtocolError("неверное приветствие линка")
            rx, tx = kx_session_keys(pk, sk, hello[5:])
            push = Pusher(tx)
            await wire.send(push.header)
            return cls(wire, push, Puller(rx, await wire.recv()))

        try:
            return await asyncio.wait_for(run(), timeout)
        except LinkCryptoError as e:
            await wire.close()
            raise MeshProtocolError(str(e)) from e
        except BaseException:
            await wire.close()
            raise

    async def send(self, packet: bytes) -> None:
        await self._wire.send(self._push.push(packet))

    async def recv(self) -> bytes:
        c = await self._wire.recv()
        try:
            return self._pull.pull(c)
        except LinkCryptoError as e:
            raise MeshProtocolError(f"линк: {e}") from e

    async def close(self) -> None:
        await self._wire.close()


__all__ = ["Link", "WireClosed"]
```

- [ ] Тест → FAIL, файлы → PASS, commit `feat(mesh): провода (память, TCP) и шифрованный линк`.

### Task 4: узел и маршрутизация (Python)

**Files:** Create `vsc_desktop/src/mys_mesh/node.py`, `tests/test_mesh_node.py`

<!-- file: vsc_desktop/tests/test_mesh_node.py -->
```python
import asyncio
import os

import pytest

from mys_decentralized.errors import TransportError
from mys_decentralized.handshake import handshake
from mys_decentralized.protocol import Role
from mys_decentralized.transport import Transport
from mys_mesh import packet as P
from mys_mesh.link import Link
from mys_mesh.node import MeshClosed, MeshConfig, MeshNode, MeshRole
from mys_mesh.tags import tag_key
from mys_mesh.wire import MemoryWire, TcpWire

PRS = bytes(range(32))
KEY = tag_key(PRS)


def fast(**kw) -> MeshConfig:
    return MeshConfig(rreq_retry=0.2, sweep=0.2, **kw)


async def link(a: MeshNode, b: MeshNode) -> tuple[int, int]:
    wa, wb = MemoryWire.pair()
    return await asyncio.gather(a.attach(wa), b.attach(wb))


async def chain(n: int) -> list[MeshNode]:
    nodes = [MeshNode(fast()) for _ in range(n)]
    for x, y in zip(nodes, nodes[1:]):
        await link(x, y)
    return nodes


async def close_all(nodes):
    for n in nodes:
        await n.close()


class Adapter(Transport):
    """MeshTransport → Transport P2P-сессии (в этапе 2 — в P2PService)."""

    def __init__(self, t):
        self.t = t

    async def send(self, data):
        try:
            await self.t.send(data)
        except MeshClosed as e:
            raise TransportError(str(e)) from e

    async def recv(self):
        try:
            return await self.t.recv()
        except MeshClosed as e:
            raise TransportError(str(e)) from e

    async def close(self):
        await self.t.close()


async def test_chain_finds_and_handshakes():
    nodes = await chain(6)
    try:
        (ra, ta), (rb, tb) = await asyncio.gather(nodes[0].find(KEY, 10), nodes[-1].find(KEY, 10))
        assert {ra, rb} == {MeshRole.INITIATOR, MeshRole.RESPONDER}
        ha, hb = await asyncio.gather(handshake(Adapter(ta), PRS, Role(int(ra))),
                                      handshake(Adapter(tb), PRS, Role(int(rb))))
        assert ha.sk == hb.sk
        await ta.send(b"privet")
        assert await tb.recv() == b"privet"
        await tb.send(b"otvet")
        assert await ta.recv() == b"otvet"
        assert nodes[2].stats()["relayed_bytes"] > 0
    finally:
        await close_all(nodes)


async def test_grid_relays_each_rreq_once():
    nodes = [MeshNode(MeshConfig(rreq_retry=60, sweep=60)) for _ in range(9)]
    for i in range(9):
        if i % 3 < 2:
            await link(nodes[i], nodes[i + 1])
        if i < 6:
            await link(nodes[i], nodes[i + 3])
    try:
        with pytest.raises(TimeoutError):
            await nodes[0].find(KEY, 0.5)
        counts = [n.stats()["rreq_forwarded"] for n in nodes]
        assert counts[0] == 0 and all(c == 1 for c in counts[1:]), counts
    finally:
        await close_all(nodes)


async def test_reroute_after_break():
    a, b, c, d = (MeshNode(fast()) for _ in range(4))
    ab, _ = await link(a, b)
    ac, _ = await link(a, c)
    await link(b, d)
    await link(c, d)
    try:
        (_, ta), (_, td) = await asyncio.gather(a.find(KEY, 10), d.find(KEY, 10))
        await a.detach(ab if b.stats()["routes"] else ac)
        for t in (ta, td):
            with pytest.raises(MeshClosed):
                await asyncio.wait_for(t.recv(), 5)
        (_, ta), (_, td) = await asyncio.gather(a.find(KEY, 10), d.find(KEY, 10))
        await ta.send(b"x")
        assert await td.recv() == b"x"
    finally:
        await close_all([a, b, c, d])


async def test_simultaneous_search_makes_one_route():
    a, b = MeshNode(fast()), MeshNode(fast())
    await link(a, b)
    try:
        (ra, _), (rb, _) = await asyncio.gather(a.find(KEY, 5), b.find(KEY, 5))
        assert {ra, rb} == {MeshRole.INITIATOR, MeshRole.RESPONDER}
        await asyncio.sleep(0.5)
        assert a.stats()["routes"] == 1 and b.stats()["routes"] == 1
    finally:
        await close_all([a, b])


async def test_no_peer_times_out():
    nodes = await chain(3)
    try:
        with pytest.raises(TimeoutError):
            await nodes[0].find(KEY, 0.5)
    finally:
        await close_all(nodes)


async def test_flood_is_rate_limited():
    a, b = MeshNode(fast()), MeshNode(fast())
    await link(a, b)
    wa, wx = MemoryWire.pair()
    _, evil = await asyncio.gather(a.attach(wa), Link.open(wx))
    try:
        for _ in range(500):
            await evil.send(P.encode(P.Packet(P.RREQ, os.urandom(16), ttl=8,
                                              tag=os.urandom(32), nonce=os.urandom(16))))
        await asyncio.sleep(0.3)
        assert a.stats()["rreq_forwarded"] <= 15
        (ra, _), (rb, _) = await asyncio.gather(a.find(KEY, 5), b.find(KEY, 5))
        assert {ra, rb} == {MeshRole.INITIATOR, MeshRole.RESPONDER}
    finally:
        await close_all([a, b])


async def test_relay_off_blocks_transit_but_not_own():
    a, r, b = MeshNode(fast()), MeshNode(fast(relay=False)), MeshNode(fast())
    await link(a, r)
    await link(r, b)
    try:
        res = await asyncio.gather(a.find(KEY, 1), b.find(KEY, 1), return_exceptions=True)
        assert all(isinstance(x, TimeoutError) for x in res), res
        (x, _), (y, _) = await asyncio.gather(a.find(KEY, 5), r.find(KEY, 5))
        assert {x, y} == {MeshRole.INITIATOR, MeshRole.RESPONDER}
    finally:
        await close_all([a, r, b])


async def test_find_over_tcp():
    accepted = asyncio.get_running_loop().create_future()
    server = await asyncio.start_server(
        lambda r, w: accepted.set_result(TcpWire(r, w)), "127.0.0.1", 0)
    port = server.sockets[0].getsockname()[1]
    a, b = MeshNode(fast()), MeshNode(fast())
    try:
        client = await TcpWire.connect("127.0.0.1", port)
        await asyncio.gather(a.attach(client), b.attach(await accepted))
        (_, ta), (_, tb) = await asyncio.gather(a.find(KEY, 5), b.find(KEY, 5))
        await ta.send(b"tcp")
        assert await tb.recv() == b"tcp"
    finally:
        await close_all([a, b])
        server.close()
```

<!-- file: vsc_desktop/src/mys_mesh/node.py -->
```python
"""Узел mesh: соседи, маршрутизация по запросу (RREQ/RREP/DATA/RERR), точка встречи.

Всё состояние — в памяти и живёт минуты: чужие узлы ничего не хранят.
Отвечает на поиск только сам собеседник; ретрансляторы не кэшируют метки.
"""

import asyncio
import os
import time
from collections import OrderedDict
from dataclasses import dataclass, field, replace
from enum import IntEnum
from typing import Callable

from . import packet as P
from .link import Link
from .tags import hour_of, tag_for_hour
from .wire import Wire, WireClosed


class MeshRole(IntEnum):
    INITIATOR = 0  # чей запрос получил ответ: шлёт «прайм», Alice в ratchet
    RESPONDER = 1


class MeshClosed(Exception):
    """Маршрут закрыт или оборвался."""


@dataclass
class MeshConfig:
    relay: bool = True
    ttl_max: int = P.TTL_MAX
    dedup_ttl: float = 120.0
    dedup_max: int = 4096
    route_idle: float = 120.0
    route_max: int = 1024
    keepalive: float = 30.0
    rreq_retry: float = 5.0
    rreq_rate: float = 10.0
    relay_rate: float = 256 * 1024
    sweep: float = 5.0
    clock: Callable[[], float] = time.time


class _Bucket:
    """Токен-бакет: ``rate`` в секунду, запас — на одну секунду."""

    def __init__(self, rate: float):
        self._rate, self._tokens, self._t = rate, rate, time.monotonic()

    def take(self, n: float = 1.0) -> bool:
        now = time.monotonic()
        self._tokens = min(self._rate, self._tokens + (now - self._t) * self._rate)
        self._t = now
        if self._tokens >= n:
            self._tokens -= n
            return True
        return False


@dataclass(eq=False)
class _Search:
    tags: set
    nonce: bytes
    future: asyncio.Future


@dataclass(eq=False)
class _Route:
    a: int | None                      # сосед; None — этот узел конечная точка
    b: int
    endpoint: "MeshTransport | None" = None
    last: float = field(default_factory=time.monotonic)


class MeshNode:
    def __init__(self, config: MeshConfig | None = None):
        self.config = config or MeshConfig()
        self._links: dict[int, Link] = {}
        self._rreq_buckets: dict[int, _Bucket] = {}
        self._next = 1
        self._seen: OrderedDict[bytes, tuple[int | None, float]] = OrderedDict()
        self._routes: dict[bytes, _Route] = {}
        self._by_tag: dict[bytes, _Search] = {}
        self._by_req: dict[bytes, _Search] = {}
        self._relay_bucket = _Bucket(self.config.relay_rate)
        self._relayed = 0
        self._rreq_forwarded = 0
        self._tasks: set[asyncio.Task] = set()
        self._sweeper: asyncio.Task | None = None

    # -- соседи ------------------------------------------------------------

    async def attach(self, wire: Wire) -> int:
        link = await Link.open(wire)
        nid = self._next
        self._next += 1
        self._links[nid] = link
        self._rreq_buckets[nid] = _Bucket(self.config.rreq_rate)
        self._spawn(self._read_loop(nid, link))
        self._ensure_sweeper()
        return nid

    async def detach(self, nid: int) -> None:
        link = self._links.get(nid)
        if link is not None:
            await link.close()  # цикл чтения увидит обрыв и уберёт соседа

    def stats(self) -> dict:
        return {"neighbors": len(self._links), "routes": len(self._routes),
                "relayed_bytes": self._relayed, "rreq_forwarded": self._rreq_forwarded}

    async def close(self) -> None:
        for link in list(self._links.values()):
            await link.close()
        for t in list(self._tasks):
            t.cancel()

    # -- поиск собеседника ---------------------------------------------------

    async def find(self, key: bytes, timeout: float) -> tuple[MeshRole, "MeshTransport"]:
        hour = hour_of(self.config.clock())
        cur = tag_for_hour(key, hour)
        s = _Search({cur, tag_for_hour(key, hour - 1)}, os.urandom(P.NONCE_LEN),
                    asyncio.get_running_loop().create_future())
        for t in s.tags:
            self._by_tag[t] = s
        self._ensure_sweeper()
        asker = asyncio.create_task(self._ask(s, cur))
        try:
            return await asyncio.wait_for(asyncio.shield(s.future), timeout)
        finally:
            asker.cancel()
            if not s.future.done():
                s.future.cancel()  # поздний RREP получит RERR
            for t in s.tags:
                if self._by_tag.get(t) is s:
                    del self._by_tag[t]

    async def _ask(self, s: _Search, tag: bytes) -> None:
        while not s.future.done():
            rid = os.urandom(P.ID_LEN)
            self._remember(rid, None)
            self._by_req[rid] = s
            await self._broadcast(P.encode(P.Packet(P.RREQ, rid, ttl=self.config.ttl_max,
                                                    tag=tag, nonce=s.nonce)), exclude=None)
            await asyncio.sleep(self.config.rreq_retry)

    # -- приём -----------------------------------------------------------------

    async def _read_loop(self, nid: int, link: Link) -> None:
        try:
            while True:
                raw = await link.recv()
                await self._handle(nid, P.decode(raw, self.config.ttl_max), raw)
        except (WireClosed, P.MeshProtocolError):
            pass
        finally:
            await self._drop_neighbor(nid)

    async def _handle(self, nid: int, p: P.Packet, raw: bytes) -> None:
        if p.type == P.RREQ:
            await self._on_rreq(nid, p)
        elif p.type == P.RREP:
            await self._on_rrep(nid, p)
        elif p.type == P.DATA:
            await self._on_data(nid, p, raw)
        else:
            await self._on_rerr(nid, p, raw)

    async def _on_rreq(self, nid: int, p: P.Packet) -> None:
        if p.id in self._seen or not self._rreq_buckets[nid].take():
            return
        self._remember(p.id, nid)
        s = self._by_tag.get(p.tag)
        if s is not None:  # ищут нас: отвечает тот, у кого nonce меньше
            if not s.future.done() and s.nonce < p.nonce and len(self._routes) < self.config.route_max:
                rid = os.urandom(P.ID_LEN)
                t = MeshTransport(self, rid, nid)
                self._routes[rid] = _Route(None, nid, t)
                await self._send(nid, P.encode(P.Packet(P.RREP, p.id, route_id=rid, nonce=s.nonce)))
                s.future.set_result((MeshRole.RESPONDER, t))
                t._start()
            return
        if self.config.relay and p.ttl > 1:
            self._rreq_forwarded += 1
            await self._broadcast(P.encode(replace(p, ttl=p.ttl - 1, hops=min(p.hops + 1, 255))),
                                  exclude=nid)

    async def _on_rrep(self, mid: int, p: P.Packet) -> None:
        s = self._by_req.get(p.id)
        if s is not None:
            if p.route_id in self._routes:
                return  # повтор уже принятого ответа
            if s.future.done():
                await self._send(mid, P.encode(P.Packet(P.RERR, p.route_id)))
                return
            t = MeshTransport(self, p.route_id, mid)
            self._routes[p.route_id] = _Route(None, mid, t)
            s.future.set_result((MeshRole.INITIATOR, t))
            t._start()
            return
        entry = self._seen.get(p.id)
        if not self.config.relay or entry is None:
            return
        nid = entry[0]
        if (nid is None or nid == mid or nid not in self._links or p.route_id in self._routes
                or len(self._routes) >= self.config.route_max):
            return
        self._routes[p.route_id] = _Route(nid, mid)
        await self._send(nid, P.encode(replace(p, hops=min(p.hops + 1, 255))))

    async def _on_data(self, nid: int, p: P.Packet, raw: bytes) -> None:
        r = self._routes.get(p.id)
        if r is None:
            return
        if r.endpoint is not None:
            if nid == r.b:
                r.last = time.monotonic()
                if p.payload:
                    r.endpoint._deliver(p.payload)
            return
        other = r.b if nid == r.a else r.a if nid == r.b else None
        if other is None or not self._relay_bucket.take(len(p.payload)):
            return
        r.last = time.monotonic()
        self._relayed += len(p.payload)
        await self._send(other, raw)

    async def _on_rerr(self, nid: int, p: P.Packet, raw: bytes) -> None:
        r = self._routes.get(p.id)
        if r is None or nid not in (r.a, r.b):
            return
        del self._routes[p.id]
        if r.endpoint is not None:
            r.endpoint._fail()
        else:
            await self._send(r.b if nid == r.a else r.a, raw)

    # -- служебное ---------------------------------------------------------------

    async def _drop_neighbor(self, nid: int) -> None:
        link = self._links.pop(nid, None)
        self._rreq_buckets.pop(nid, None)
        if link is not None:
            await link.close()
        for rid, r in list(self._routes.items()):
            if nid not in (r.a, r.b):
                continue
            del self._routes[rid]
            if r.endpoint is not None:
                r.endpoint._fail()
            else:
                await self._send(r.b if nid == r.a else r.a, P.encode(P.Packet(P.RERR, rid)))

    async def _send(self, nid: int, raw: bytes) -> bool:
        link = self._links.get(nid)
        if link is None:
            return False
        try:
            await link.send(raw)
            return True
        except WireClosed:
            await link.close()
            return False

    async def _broadcast(self, raw: bytes, exclude: int | None) -> None:
        for nid in list(self._links):
            if nid != exclude:
                await self._send(nid, raw)

    def _remember(self, rid: bytes, nid: int | None) -> None:
        self._seen[rid] = (nid, time.monotonic())
        while len(self._seen) > self.config.dedup_max:
            self._seen.popitem(last=False)

    def _touch(self, rid: bytes) -> None:
        r = self._routes.get(rid)
        if r is not None:
            r.last = time.monotonic()

    def _spawn(self, coro) -> None:
        t = asyncio.create_task(coro)
        self._tasks.add(t)
        t.add_done_callback(self._tasks.discard)

    def _ensure_sweeper(self) -> None:
        if self._sweeper is None or self._sweeper.done():
            self._sweeper = asyncio.create_task(self._sweep_loop())
            self._tasks.add(self._sweeper)

    async def _sweep_loop(self) -> None:
        while True:
            await asyncio.sleep(self.config.sweep)
            now = time.monotonic()
            while self._seen:
                _, (_, t) = next(iter(self._seen.items()))
                if now - t < self.config.dedup_ttl:
                    break
                self._seen.popitem(last=False)
            for rid in [r for r in self._by_req if r not in self._seen]:
                del self._by_req[rid]
            for rid, r in list(self._routes.items()):
                if now - r.last > self.config.route_idle:
                    del self._routes[rid]
                    if r.endpoint is not None:
                        r.endpoint._fail()


class MeshTransport:
    """Конечная точка маршрута: кадры сессии в DATA, keepalive, RERR при закрытии."""

    def __init__(self, node: MeshNode, route_id: bytes, neighbor: int):
        self._node, self.route_id, self._nb = node, route_id, neighbor
        self._q: asyncio.Queue = asyncio.Queue()
        self._closed = False
        self._ka: asyncio.Task | None = None

    def _start(self) -> None:
        self._ka = asyncio.create_task(self._keepalive())

    async def send(self, frame: bytes) -> None:
        if self._closed:
            raise MeshClosed("маршрут закрыт")
        if len(frame) > P.MAX_DATA:
            raise ValueError("кадр больше MAX_DATA")
        self._node._touch(self.route_id)
        if not await self._node._send(self._nb, P.encode(P.Packet(P.DATA, self.route_id, payload=frame))):
            raise MeshClosed("сосед отключился")

    async def recv(self) -> bytes:
        item = await self._q.get()
        if item is None:
            self._q.put_nowait(None)
            raise MeshClosed("маршрут оборвался")
        return item

    async def close(self) -> None:
        if self._closed:
            return
        self._fail()
        if self._node._routes.pop(self.route_id, None) is not None:
            await self._node._send(self._nb, P.encode(P.Packet(P.RERR, self.route_id)))

    def _deliver(self, payload: bytes) -> None:
        if not self._closed:
            self._q.put_nowait(payload)

    def _fail(self) -> None:
        if self._ka is not None and self._ka is not asyncio.current_task():
            self._ka.cancel()
        if not self._closed:
            self._closed = True
            self._q.put_nowait(None)

    async def _keepalive(self) -> None:
        try:
            while True:
                await asyncio.sleep(self._node.config.keepalive)
                await self.send(b"")
        except MeshClosed:
            pass
```

- [ ] Тест → FAIL, файл → PASS; полный прогон десктопа (320+ passed); commit `feat(mesh): узел — маршрутизация по запросу и точка встречи`.

### Task 5: тест-векторы

**Files:** Modify `vsc_mobile/tools/gen_vectors.py` (перед `json.dump`), regenerate `vsc_mobile/crypto/src/test/resources/vectors.json`

```python
MT = importlib.import_module("mys_mesh.tags")
MP = importlib.import_module("mys_mesh.packet")
mesh_prs = det("mesh-prs")
mesh_key = MT.tag_key(mesh_prs)
mesh_packets = [
    MP.Packet(MP.RREQ, det("rreq-id", 16), ttl=8, hops=2, tag=det("mesh-tag"), nonce=det("n1", 16)),
    MP.Packet(MP.RREP, det("rreq-id", 16), hops=1, route_id=det("route", 16), nonce=det("n2", 16)),
    MP.Packet(MP.DATA, det("route", 16), payload=b"\x00\x01mys"),
    MP.Packet(MP.RERR, det("route", 16)),
]
v["mesh"] = {
    "prs": H(mesh_prs), "tag_key": H(mesh_key),
    "tags": [{"hour": h, "tag": H(MT.tag_for_hour(mesh_key, h))} for h in (0, 494_000, 2**40)],
    "packets": [{"type": p.type, "id": H(p.id), "ttl": p.ttl, "hops": p.hops, "tag": H(p.tag),
                 "nonce": H(p.nonce), "route_id": H(p.route_id), "payload": H(p.payload),
                 "hex": H(MP.encode(p))} for p in mesh_packets],
}
```

Run: `$DESKTOP_PYTHON tools/gen_vectors.py ../vsc_desktop/src > crypto/src/test/resources/vectors.json`; `git diff --stat` — только добавлен ключ `mesh`. `./gradlew :crypto:test :p2p:test` — PASS. Commit `test(vectors): векторы mesh`.

### Task 6: модуль `:mesh` — криптография линка, метки, пакеты (Kotlin)

**Files:** Modify `vsc_mobile/settings.gradle.kts` (`include(":app", ":crypto", ":p2p", ":central", ":mesh")`); Create build, `LinkCrypto.kt`, `Tags.kt`, `Packet.kt`, tests `TestSupport.kt`, `VectorsTest.kt`, `LinkCryptoTest.kt`

<!-- file: vsc_mobile/mesh/build.gradle.kts -->
```kotlin
plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":crypto"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    // Как в :crypto: API lazysodium; реализацию даёт платформа (android/java).
    compileOnly("com.goterl:lazysodium-java:5.1.4")
    testImplementation(project(":p2p")) // настоящий handshake поверх mesh в тестах
    testImplementation("com.goterl:lazysodium-java:5.1.4")
    testImplementation("net.java.dev.jna:jna:5.17.0")
    testImplementation("org.json:json:20250517")
    testImplementation(kotlin("test-junit"))
}
sourceSets.test { resources.srcDir("../crypto/src/test/resources") }
tasks.test {
    useJUnit()
    systemProperty("mys.root", rootDir.path)
}
```

<!-- file: vsc_mobile/mesh/src/main/kotlin/ru/mobile/mys/mesh/LinkCrypto.kt -->
```kotlin
package ru.mobile.mys.mesh

import com.goterl.lazysodium.interfaces.KeyExchange
import com.goterl.lazysodium.interfaces.SecretStream

class LinkCryptoException(message: String) : Exception(message)

/** crypto_kx + secretstream XChaCha20-Poly1305 из libsodium (порт mys_crypto/linkcrypto.py). */
class LinkCrypto(private val kx: KeyExchange.Native, private val ss: SecretStream.Native) {
    fun keypair(): Pair<ByteArray, ByteArray> {
        val pk = ByteArray(KX_PK)
        val sk = ByteArray(KX_PK)
        if (!kx.cryptoKxKeypair(pk, sk)) throw LinkCryptoException("crypto_kx_keypair")
        return pk to sk
    }

    /** (rx, tx); «клиент» crypto_kx — сторона с меньшим pk (побайтово без знака). */
    fun sessionKeys(pk: ByteArray, sk: ByteArray, peer: ByteArray): Pair<ByteArray, ByteArray> {
        if (peer.size != KX_PK || peer.contentEquals(pk)) throw LinkCryptoException("неверный ключ соседа")
        val rx = ByteArray(KX_PK)
        val tx = ByteArray(KX_PK)
        val ok = if (compareUnsigned(pk, peer) < 0) kx.cryptoKxClientSessionKeys(rx, tx, pk, sk, peer)
        else kx.cryptoKxServerSessionKeys(rx, tx, pk, sk, peer)
        if (!ok) throw LinkCryptoException("crypto_kx отверг ключ соседа")
        return rx to tx
    }

    inner class Pusher(key: ByteArray) {
        private val state = SecretStream.State()
        val header = ByteArray(HEADERBYTES)

        init {
            if (!ss.cryptoSecretStreamInitPush(state, header, key)) throw LinkCryptoException("init_push")
        }

        fun push(m: ByteArray): ByteArray {
            val c = ByteArray(m.size + ABYTES)
            if (!ss.cryptoSecretStreamPush(state, c, m, m.size.toLong(), SecretStream.TAG_MESSAGE)) throw LinkCryptoException("push")
            return c
        }
    }

    inner class Puller(key: ByteArray, header: ByteArray) {
        private val state = SecretStream.State()

        init {
            if (header.size != HEADERBYTES || !ss.cryptoSecretStreamInitPull(state, header, key)) {
                throw LinkCryptoException("неверный заголовок потока")
            }
        }

        fun pull(c: ByteArray): ByteArray {
            if (c.size < ABYTES) throw LinkCryptoException("кадр короче ABYTES")
            val m = ByteArray(c.size - ABYTES)
            val tag = ByteArray(1)
            if (!ss.cryptoSecretStreamPull(state, m, tag, c, c.size.toLong())) throw LinkCryptoException("кадр не расшифрован")
            if (tag[0] != SecretStream.TAG_MESSAGE) throw LinkCryptoException("неожиданный тег потока")
            return m
        }
    }

    companion object {
        const val KX_PK = 32
        const val HEADERBYTES = 24
        const val ABYTES = 17
    }
}

internal fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
        if (d != 0) return d
    }
    return a.size - b.size
}
```

<!-- file: vsc_mobile/mesh/src/main/kotlin/ru/mobile/mys/mesh/Tags.kt -->
```kotlin
package ru.mobile.mys.mesh

import java.nio.ByteBuffer
import org.bouncycastle.crypto.digests.Blake2bDigest
import ru.mobile.mys.crypto.hkdf

/** Ключ меток из prs — не из room_id: room_id видит сервер. */
fun tagKey(prs: ByteArray): ByteArray = hkdf(prs, 32, ByteArray(0), "mys-mesh-tag-key".toByteArray())

/** Метка канала на час: ретрансляторы не могут связать канал между часами. */
fun tagForHour(key: ByteArray, hour: Long): ByteArray {
    val d = Blake2bDigest(key, 32, null, "mys-mesh-tag".toByteArray().copyOf(16))
    val data = ByteBuffer.allocate(8).putLong(hour).array()
    d.update(data, 0, data.size)
    return ByteArray(32).also { d.doFinal(it, 0) }
}

fun hourOf(unixMillis: Long): Long = Math.floorDiv(unixMillis, 3_600_000L)
```

<!-- file: vsc_mobile/mesh/src/main/kotlin/ru/mobile/mys/mesh/Packet.kt -->
```kotlin
package ru.mobile.mys.mesh

const val MESH_VERSION = 1
const val RREQ = 1
const val RREP = 2
const val DATA = 3
const val RERR = 4
const val ID_LEN = 16
const val TAG_LEN = 32
const val NONCE_LEN = 16
const val HEADER_LEN = 4 + ID_LEN
const val MAX_DATA = 65_600
const val MAX_PACKET = HEADER_LEN + MAX_DATA
const val TTL_MAX = 8

private val EMPTY = ByteArray(0)

/** Неверный пакет/линк — соединение с этим соседом рвётся. */
class MeshProtocolException(message: String) : Exception(message)

/** Mesh-пакет: `version | type | ttl | hops | id(16) | body` (порт mys_mesh/packet.py). */
class Packet(
    val type: Int, val id: ByteArray, val ttl: Int = 0, val hops: Int = 0,
    val tag: ByteArray = EMPTY, val nonce: ByteArray = EMPTY, val routeId: ByteArray = EMPTY, val payload: ByteArray = EMPTY,
) {
    fun encode(): ByteArray {
        val body = when (type) {
            RREQ -> tag + nonce
            RREP -> routeId + nonce
            DATA -> payload
            else -> EMPTY
        }
        return byteArrayOf(MESH_VERSION.toByte(), type.toByte(), ttl.toByte(), hops.toByte()) + id + body
    }

    fun with(ttl: Int = this.ttl, hops: Int = this.hops) = Packet(type, id, ttl, hops, tag, nonce, routeId, payload)

    companion object {
        fun rreq(id: ByteArray, tag: ByteArray, nonce: ByteArray, ttl: Int) = Packet(RREQ, id, ttl, tag = tag, nonce = nonce)
        fun rrep(reqId: ByteArray, routeId: ByteArray, nonce: ByteArray) = Packet(RREP, reqId, routeId = routeId, nonce = nonce)
        fun data(routeId: ByteArray, payload: ByteArray) = Packet(DATA, routeId, payload = payload)
        fun rerr(routeId: ByteArray) = Packet(RERR, routeId)

        fun decode(raw: ByteArray, ttlMax: Int = TTL_MAX): Packet {
            if (raw.size !in HEADER_LEN..MAX_PACKET) throw MeshProtocolException("неверная длина пакета")
            val version = raw[0].toInt() and 0xff
            val type = raw[1].toInt() and 0xff
            val ttl = raw[2].toInt() and 0xff
            val hops = raw[3].toInt() and 0xff
            val id = raw.copyOfRange(4, HEADER_LEN)
            val body = raw.copyOfRange(HEADER_LEN, raw.size)
            if (version != MESH_VERSION) throw MeshProtocolException("неизвестная версия")
            if (type == RREQ) {
                if (body.size != TAG_LEN + NONCE_LEN || ttl !in 1..ttlMax) throw MeshProtocolException("неверный RREQ")
                return Packet(RREQ, id, ttl, hops, tag = body.copyOfRange(0, TAG_LEN), nonce = body.copyOfRange(TAG_LEN, body.size))
            }
            if (ttl != 0) throw MeshProtocolException("ttl только у RREQ")
            return when (type) {
                RREP -> {
                    if (body.size != ID_LEN + NONCE_LEN) throw MeshProtocolException("неверный RREP")
                    Packet(RREP, id, 0, hops, routeId = body.copyOfRange(0, ID_LEN), nonce = body.copyOfRange(ID_LEN, body.size))
                }
                DATA -> Packet(DATA, id, 0, hops, payload = body)
                RERR -> {
                    if (body.isNotEmpty()) throw MeshProtocolException("RERR с телом")
                    Packet(RERR, id, 0, hops)
                }
                else -> throw MeshProtocolException("неизвестный тип")
            }
        }
    }
}
```

<!-- file: vsc_mobile/mesh/src/test/kotlin/ru/mobile/mys/mesh/TestSupport.kt -->
```kotlin
package ru.mobile.mys.mesh

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.json.JSONObject
import ru.mobile.mys.crypto.Ristretto
import ru.mobile.mys.p2p.Transport
import ru.mobile.mys.p2p.TransportException

val sodium by lazy { LazySodiumJava(SodiumJava()) }
val crypto by lazy { LinkCrypto(sodium, sodium) }
val ristretto by lazy { Ristretto(sodium) }

object Vectors {
    val root: JSONObject by lazy { JSONObject(Vectors::class.java.getResource("/vectors.json")!!.readText()) }
}

fun String.unhex(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

/** MeshTransport → Transport P2P-сессии (в этапе 2 — в P2PService). */
class Adapter(private val t: MeshTransport) : Transport {
    override suspend fun send(data: ByteArray) = try { t.send(data) } catch (e: MeshClosed) { throw TransportException(e.message ?: "mesh") }
    override suspend fun recv(): ByteArray = try { t.recv() } catch (e: MeshClosed) { throw TransportException(e.message ?: "mesh") }
    override suspend fun close() = t.close()
}
```

<!-- file: vsc_mobile/mesh/src/test/kotlin/ru/mobile/mys/mesh/VectorsTest.kt -->
```kotlin
package ru.mobile.mys.mesh

import kotlin.test.assertEquals
import org.junit.Test

class VectorsTest {
    private val v = Vectors.root.getJSONObject("mesh")

    @Test fun tagKeyAndTags() {
        val key = tagKey(v.getString("prs").unhex())
        assertEquals(v.getString("tag_key"), key.hex())
        val tags = v.getJSONArray("tags")
        for (i in 0 until tags.length()) {
            val t = tags.getJSONObject(i)
            assertEquals(t.getString("tag"), tagForHour(key, t.getLong("hour")).hex())
        }
    }

    @Test fun packetsEncodeAndDecode() {
        val ps = v.getJSONArray("packets")
        for (i in 0 until ps.length()) {
            val j = ps.getJSONObject(i)
            val p = Packet(
                j.getInt("type"), j.getString("id").unhex(), j.getInt("ttl"), j.getInt("hops"),
                j.getString("tag").unhex(), j.getString("nonce").unhex(), j.getString("route_id").unhex(), j.getString("payload").unhex(),
            )
            assertEquals(j.getString("hex"), p.encode().hex())
            assertEquals(j.getString("hex"), Packet.decode(j.getString("hex").unhex()).encode().hex())
        }
    }

    @Test fun hourOfFloors() = assertEquals(2L, hourOf(7_200_500L))
}
```

<!-- file: vsc_mobile/mesh/src/test/kotlin/ru/mobile/mys/mesh/LinkCryptoTest.kt -->
```kotlin
package ru.mobile.mys.mesh

import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class LinkCryptoTest {
    private fun keys(): Pair<Pair<ByteArray, ByteArray>, Pair<ByteArray, ByteArray>> {
        val (apk, ask) = crypto.keypair()
        val (bpk, bsk) = crypto.keypair()
        return crypto.sessionKeys(apk, ask, bpk) to crypto.sessionKeys(bpk, bsk, apk)
    }

    @Test fun keysMatchCrosswise() {
        val (a, b) = keys()
        assertContentEquals(a.second, b.first)
        assertContentEquals(a.first, b.second)
    }

    @Test fun streamRoundtripRejectsReplayAndTamper() {
        val (a, b) = keys()
        val push = crypto.Pusher(a.second)
        val pull = crypto.Puller(b.first, push.header)
        val c1 = push.push("one".toByteArray())
        val c2 = push.push("two".toByteArray())
        assertContentEquals("one".toByteArray(), pull.pull(c1))
        assertFailsWith<LinkCryptoException> { pull.pull(c2.copyOf().also { it[it.size - 1] = (it.last().toInt() xor 1).toByte() }) }
    }

    @Test fun replayRejected() {
        val (a, b) = keys()
        val push = crypto.Pusher(a.second)
        val pull = crypto.Puller(b.first, push.header)
        val c = push.push("x".toByteArray())
        pull.pull(c)
        assertFailsWith<LinkCryptoException> { pull.pull(c) }
    }

    @Test fun ownKeyRejected() {
        val (pk, sk) = crypto.keypair()
        assertFailsWith<LinkCryptoException> { crypto.sessionKeys(pk, sk, pk) }
    }

    @Test fun malformedPacketsRejected() {
        val id = ByteArray(16)
        for (raw in listOf(
            ByteArray(0),
            byteArrayOf(2, RERR.toByte(), 0, 0) + id,
            byteArrayOf(1, 9, 0, 0) + id,
            byteArrayOf(1, RREQ.toByte(), 0, 0) + id + ByteArray(48),
            byteArrayOf(1, RREQ.toByte(), 9, 0) + id + ByteArray(48),
            byteArrayOf(1, DATA.toByte(), 1, 0) + id,
            byteArrayOf(1, RERR.toByte(), 0, 0) + id + byteArrayOf(1),
        )) assertFailsWith<MeshProtocolException> { Packet.decode(raw) }
    }
}
```

- [ ] `./gradlew :mesh:test` → PASS (векторы из Task 5). Commit `feat(mesh): модуль :mesh — линк-крипто, метки, пакеты`.

### Task 7: провода и линк (Kotlin)

<!-- file: vsc_mobile/mesh/src/main/kotlin/ru/mobile/mys/mesh/Wire.kt -->
```kotlin
package ru.mobile.mys.mesh

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

const val MAX_FRAME = 72 * 1024

class WireClosed(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Упорядоченный надёжный поток кадров к соседу; шифрование — в [Link]. */
interface Wire {
    suspend fun send(frame: ByteArray)
    suspend fun recv(): ByteArray
    suspend fun close()
}

/** Пара каналов; close() любой стороны обрывает recv() обеих. */
class MemoryWire private constructor(private val inbox: Channel<ByteArray>, private val outbox: Channel<ByteArray>) : Wire {
    override suspend fun send(frame: ByteArray) {
        try { outbox.send(frame) } catch (e: ClosedSendChannelException) { throw WireClosed("провод закрыт") }
    }

    override suspend fun recv(): ByteArray {
        val f = try { inbox.receive() } catch (e: ClosedReceiveChannelException) { throw WireClosed("провод закрыт") }
        if (f.size > MAX_FRAME) throw WireClosed("кадр больше MAX_FRAME")
        return f
    }

    override suspend fun close() {
        outbox.close()
        inbox.close()
    }

    companion object {
        fun pair(): Pair<MemoryWire, MemoryWire> {
            val ab = Channel<ByteArray>(Channel.UNLIMITED)
            val ba = Channel<ByteArray>(Channel.UNLIMITED)
            return MemoryWire(ba, ab) to MemoryWire(ab, ba)
        }
    }
}

/** Кадры `u32be длина ‖ байты` поверх TCP. */
class TcpWire(private val socket: Socket) : Wire {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
    private val writeLock = Mutex()

    override suspend fun send(frame: ByteArray) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            try {
                output.writeInt(frame.size)
                output.write(frame)
                output.flush()
            } catch (e: IOException) { throw WireClosed("TCP: ${e.message}", e) }
        }
    }

    override suspend fun recv(): ByteArray = withContext(Dispatchers.IO) {
        try {
            val n = input.readInt()
            if (n < 0 || n > MAX_FRAME) throw WireClosed("кадр больше MAX_FRAME")
            ByteArray(n).also { input.readFully(it) }
        } catch (e: IOException) { throw WireClosed("TCP: ${e.message}", e) }
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) { runCatching { socket.close() } }
    }

    companion object {
        suspend fun connect(host: String, port: Int): TcpWire =
            withContext(Dispatchers.IO) { TcpWire(Socket(host, port).apply { tcpNoDelay = true }) }
    }
}
```

<!-- file: vsc_mobile/mesh/src/main/kotlin/ru/mobile/mys/mesh/Link.kt -->
```kotlin
package ru.mobile.mys.mesh

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Линк: временные X25519 → crypto_kx → secretstream (порт mys_mesh/link.py). */
class Link private constructor(private val wire: Wire, private val push: LinkCrypto.Pusher, private val pull: LinkCrypto.Puller) {
    private val sendLock = Mutex()

    suspend fun send(packet: ByteArray) = sendLock.withLock { wire.send(push.push(packet)) }

    suspend fun recv(): ByteArray {
        val c = wire.recv()
        return try { pull.pull(c) } catch (e: LinkCryptoException) { throw MeshProtocolException("линк: ${e.message}") }
    }

    suspend fun close() = wire.close()

    companion object {
        private val MAGIC = "MYSM".toByteArray()
        private const val VERSION = 1
        const val HANDSHAKE_TIMEOUT_MS = 10_000L

        suspend fun open(wire: Wire, crypto: LinkCrypto, timeoutMs: Long = HANDSHAKE_TIMEOUT_MS): Link = try {
            withTimeout(timeoutMs) {
                val (pk, sk) = crypto.keypair()
                wire.send(MAGIC + byteArrayOf(VERSION.toByte()) + pk)
                val hello = wire.recv()
                if (hello.size != 37 || !hello.copyOfRange(0, 4).contentEquals(MAGIC) || hello[4].toInt() != VERSION) {
                    throw MeshProtocolException("неверное приветствие линка")
                }
                val (rx, tx) = crypto.sessionKeys(pk, sk, hello.copyOfRange(5, 37))
                val push = crypto.Pusher(tx)
                wire.send(push.header)
                Link(wire, push, crypto.Puller(rx, wire.recv()))
            }
        } catch (e: Throwable) {
            wire.close()
            throw if (e is LinkCryptoException) MeshProtocolException(e.message ?: "линк") else e
        }
    }
}
```

<!-- file: vsc_mobile/mesh/src/test/kotlin/ru/mobile/mys/mesh/LinkTest.kt -->
```kotlin
package ru.mobile.mys.mesh

import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test

class LinkTest {
    private suspend fun links(a: Wire, b: Wire) = kotlinx.coroutines.coroutineScope {
        listOf(async { Link.open(a, crypto) }, async { Link.open(b, crypto) }).awaitAll()
    }

    @Test fun handshakeAndExchange() = runBlocking {
        val (wa, wb) = MemoryWire.pair()
        val (la, lb) = links(wa, wb)
        la.send("ping".toByteArray())
        lb.send("pong".toByteArray())
        assertEquals("ping", lb.recv().decodeToString())
        assertEquals("pong", la.recv().decodeToString())
    }

    @Test fun garbageBreaksLink() = runBlocking {
        val (wa, wb) = MemoryWire.pair()
        val (_, lb) = links(wa, wb)
        wa.send(ByteArray(40))
        assertFailsWith<MeshProtocolException> { lb.recv() }
        Unit
    }

    @Test fun badMagicRejected() = runBlocking {
        val (wa, wb) = MemoryWire.pair()
        val opening = async { runCatching { Link.open(wb, crypto) } }
        wa.send("XXXX".toByteArray() + byteArrayOf(1) + ByteArray(32))
        assertTrue(opening.await().exceptionOrNull() is MeshProtocolException)
    }

    @Test fun oversizeAndCloseEndWire() = runBlocking {
        val (wa, wb) = MemoryWire.pair()
        wa.send(ByteArray(MAX_FRAME + 1))
        assertFailsWith<WireClosed> { wb.recv() }
        wa.close()
        assertFailsWith<WireClosed> { wb.recv() }
        assertFailsWith<WireClosed> { wa.recv() }
        Unit
    }

    @Test fun tcpWire() = runBlocking {
        ServerSocket(0).use { server ->
            val accepted = async(Dispatchers.IO) { TcpWire(server.accept()) }
            val client = TcpWire.connect("127.0.0.1", server.localPort)
            val (la, lb) = links(client, accepted.await())
            la.send("tcp".toByteArray())
            assertEquals("tcp", lb.recv().decodeToString())
            la.close()
            assertFailsWith<WireClosed> { lb.recv() }
        }
        Unit
    }
}
```

- [ ] `./gradlew :mesh:test` → PASS. Commit `feat(mesh): провода и шифрованный линк (Kotlin)`.

### Task 8: узел и маршрутизация (Kotlin)

<!-- file: vsc_mobile/mesh/src/main/kotlin/ru/mobile/mys/mesh/MeshNode.kt -->
```kotlin
package ru.mobile.mys.mesh

import java.security.SecureRandom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class MeshRole { INITIATOR, RESPONDER }

class MeshClosed(message: String) : Exception(message)

data class MeshConfig(
    val relay: Boolean = true,
    val ttlMax: Int = TTL_MAX,
    val dedupTtlMs: Long = 120_000,
    val dedupMax: Int = 4096,
    val routeIdleMs: Long = 120_000,
    val routeMax: Int = 1024,
    val keepaliveMs: Long = 30_000,
    val rreqRetryMs: Long = 5_000,
    val rreqRate: Double = 10.0,
    val relayRate: Double = 256.0 * 1024,
    val sweepMs: Long = 5_000,
    val clock: () -> Long = System::currentTimeMillis,
)

/** Токен-бакет: rate в секунду, запас — на одну секунду. */
private class Bucket(private val rate: Double) {
    private var tokens = rate
    private var t = System.nanoTime()

    fun take(n: Double = 1.0): Boolean {
        val now = System.nanoTime()
        tokens = minOf(rate, tokens + (now - t) / 1e9 * rate)
        t = now
        return if (tokens >= n) { tokens -= n; true } else false
    }
}

/** ByteArray как ключ хэш-таблицы. */
internal class Key(val b: ByteArray) {
    override fun equals(other: Any?) = other is Key && other.b.contentEquals(b)
    override fun hashCode() = b.contentHashCode()
}

internal val ByteArray.k get() = Key(this)

private class Search(val tags: List<ByteArray>, val nonce: ByteArray) {
    val result = CompletableDeferred<Pair<MeshRole, MeshTransport>>()
}

internal class Route(val a: Int?, val b: Int, val endpoint: MeshTransport? = null) {
    var last = System.nanoTime()
}

/**
 * Узел mesh (порт mys_mesh/node.py): соседи, RREQ/RREP/DATA/RERR, точка встречи.
 * Состояние — только на однопоточном контексте узла (как в asyncio), в памяти, минуты.
 */
class MeshNode(private val crypto: LinkCrypto, val config: MeshConfig = MeshConfig()) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val ctx = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + ctx)
    private val random = SecureRandom()
    private val links = HashMap<Int, Link>()
    private val rreqBuckets = HashMap<Int, Bucket>()
    private var nextId = 1
    private val seen = LinkedHashMap<Key, Pair<Int?, Long>>()
    internal val routes = HashMap<Key, Route>()
    private val byTag = HashMap<Key, Search>()
    private val byReq = HashMap<Key, Search>()
    private val relayBucket = Bucket(config.relayRate)
    private var relayed = 0L
    private var rreqForwarded = 0L
    private var sweeper: Job? = null

    internal suspend fun <T> onNode(block: suspend () -> T): T = withContext(ctx) { block() }

    // -- соседи

    suspend fun attach(wire: Wire): Int {
        val link = Link.open(wire, crypto)
        return withContext(ctx) {
            val id = nextId++
            links[id] = link
            rreqBuckets[id] = Bucket(config.rreqRate)
            scope.launch { readLoop(id, link) }
            ensureSweeper()
            id
        }
    }

    suspend fun detach(id: Int) {
        withContext(ctx) { links[id] }?.close()
    }

    suspend fun stats(): Map<String, Long> = withContext(ctx) {
        mapOf("neighbors" to links.size.toLong(), "routes" to routes.size.toLong(),
            "relayed_bytes" to relayed, "rreq_forwarded" to rreqForwarded)
    }

    suspend fun close() {
        withContext(ctx) { links.values.toList() }.forEach { it.close() }
        scope.cancel()
    }

    // -- поиск собеседника

    suspend fun find(key: ByteArray, timeoutMs: Long): Pair<MeshRole, MeshTransport> = withContext(ctx) {
        val hour = hourOf(config.clock())
        val cur = tagForHour(key, hour)
        val s = Search(listOf(cur, tagForHour(key, hour - 1)), bytes(NONCE_LEN))
        s.tags.forEach { byTag[it.k] = s }
        ensureSweeper()
        val asker = scope.launch { ask(s, cur) }
        try {
            withTimeout(timeoutMs) { s.result.await() }
        } finally {
            asker.cancel()
            s.result.cancel() // поздний RREP получит RERR
            s.tags.forEach { if (byTag[it.k] === s) byTag.remove(it.k) }
        }
    }

    private suspend fun ask(s: Search, tag: ByteArray) {
        while (!s.result.isCompleted) {
            val rid = bytes(ID_LEN)
            remember(rid, null)
            byReq[rid.k] = s
            broadcast(Packet.rreq(rid, tag, s.nonce, config.ttlMax).encode(), exclude = null)
            delay(config.rreqRetryMs)
        }
    }

    // -- приём

    private suspend fun readLoop(id: Int, link: Link) {
        try {
            while (true) {
                val raw = link.recv()
                handle(id, Packet.decode(raw, config.ttlMax), raw)
            }
        } catch (e: WireClosed) {
        } catch (e: MeshProtocolException) {
        } finally {
            withContext(NonCancellable) { dropNeighbor(id) }
        }
    }

    private suspend fun handle(id: Int, p: Packet, raw: ByteArray) = when (p.type) {
        RREQ -> onRreq(id, p)
        RREP -> onRrep(id, p)
        DATA -> onData(id, p, raw)
        else -> onRerr(id, p, raw)
    }

    private suspend fun onRreq(id: Int, p: Packet) {
        if (p.id.k in seen || rreqBuckets[id]?.take() != true) return
        remember(p.id, id)
        val s = byTag[p.tag.k]
        if (s != null) { // ищут нас: отвечает тот, у кого nonce меньше
            if (!s.result.isCompleted && compareUnsigned(s.nonce, p.nonce) < 0 && routes.size < config.routeMax) {
                val rid = bytes(ID_LEN)
                val t = MeshTransport(this, rid, id)
                routes[rid.k] = Route(null, id, t)
                sendTo(id, Packet.rrep(p.id, rid, s.nonce).encode())
                s.result.complete(MeshRole.RESPONDER to t)
                t.start(scope)
            }
            return
        }
        if (config.relay && p.ttl > 1) {
            rreqForwarded++
            broadcast(p.with(ttl = p.ttl - 1, hops = minOf(p.hops + 1, 255)).encode(), exclude = id)
        }
    }

    private suspend fun onRrep(mid: Int, p: Packet) {
        val s = byReq[p.id.k]
        if (s != null) {
            if (p.routeId.k in routes) return // повтор уже принятого ответа
            if (s.result.isCompleted) {
                sendTo(mid, Packet.rerr(p.routeId).encode())
                return
            }
            val t = MeshTransport(this, p.routeId, mid)
            routes[p.routeId.k] = Route(null, mid, t)
            s.result.complete(MeshRole.INITIATOR to t)
            t.start(scope)
            return
        }
        val entry = seen[p.id.k]
        if (!config.relay || entry == null) return
        val nid = entry.first
        if (nid == null || nid == mid || nid !in links || p.routeId.k in routes || routes.size >= config.routeMax) return
        routes[p.routeId.k] = Route(nid, mid)
        sendTo(nid, p.with(hops = minOf(p.hops + 1, 255)).encode())
    }

    private suspend fun onData(id: Int, p: Packet, raw: ByteArray) {
        val r = routes[p.id.k] ?: return
        val ep = r.endpoint
        if (ep != null) {
            if (id == r.b) {
                r.last = System.nanoTime()
                if (p.payload.isNotEmpty()) ep.deliver(p.payload)
            }
            return
        }
        val other = when (id) { r.a -> r.b; r.b -> r.a; else -> null } ?: return
        if (!relayBucket.take(p.payload.size.toDouble())) return
        r.last = System.nanoTime()
        relayed += p.payload.size
        sendTo(other, raw)
    }

    private suspend fun onRerr(id: Int, p: Packet, raw: ByteArray) {
        val r = routes[p.id.k] ?: return
        if (id != r.a && id != r.b) return
        routes.remove(p.id.k)
        val ep = r.endpoint
        if (ep != null) ep.fail() else sendTo(if (id == r.a) r.b else r.a!!, raw)
    }

    // -- служебное

    private suspend fun dropNeighbor(id: Int) {
        val link = links.remove(id)
        rreqBuckets.remove(id)
        link?.close()
        for ((rid, r) in routes.entries.toList()) {
            if (id != r.a && id != r.b) continue
            routes.remove(rid)
            val ep = r.endpoint
            if (ep != null) ep.fail() else sendTo(if (id == r.a) r.b else r.a!!, Packet.rerr(rid.b).encode())
        }
    }

    internal suspend fun sendTo(id: Int, raw: ByteArray): Boolean {
        val link = links[id] ?: return false
        return try {
            link.send(raw)
            true
        } catch (e: WireClosed) {
            link.close()
            false
        }
    }

    private suspend fun broadcast(raw: ByteArray, exclude: Int?) {
        for (id in links.keys.toList()) if (id != exclude) sendTo(id, raw)
    }

    private fun remember(rid: ByteArray, id: Int?) {
        seen[rid.k] = id to System.nanoTime()
        while (seen.size > config.dedupMax) seen.remove(seen.keys.first())
    }

    internal fun touch(rid: ByteArray) {
        routes[rid.k]?.last = System.nanoTime()
    }

    private fun bytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    private fun ensureSweeper() {
        if (sweeper?.isActive == true) return
        sweeper = scope.launch {
            while (isActive) {
                delay(config.sweepMs)
                val now = System.nanoTime()
                val it = seen.entries.iterator()
                while (it.hasNext()) {
                    if ((now - it.next().value.second) / 1_000_000 < config.dedupTtlMs) break
                    it.remove()
                }
                byReq.keys.retainAll(seen.keys)
                for ((rid, r) in routes.entries.toList()) {
                    if ((now - r.last) / 1_000_000 > config.routeIdleMs) {
                        routes.remove(rid)
                        r.endpoint?.fail()
                    }
                }
            }
        }
    }
}
```

<!-- file: vsc_mobile/mesh/src/main/kotlin/ru/mobile/mys/mesh/MeshTransport.kt -->
```kotlin
package ru.mobile.mys.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Конечная точка маршрута: кадры сессии в DATA, keepalive, RERR при закрытии. */
class MeshTransport internal constructor(private val node: MeshNode, val routeId: ByteArray, private val neighbor: Int) {
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    @Volatile private var closed = false
    private var keepalive: Job? = null

    internal fun start(scope: CoroutineScope) {
        keepalive = scope.launch {
            try {
                while (true) {
                    delay(node.config.keepaliveMs)
                    send(ByteArray(0))
                }
            } catch (e: MeshClosed) {
            }
        }
    }

    suspend fun send(frame: ByteArray) {
        if (closed) throw MeshClosed("маршрут закрыт")
        require(frame.size <= MAX_DATA) { "кадр больше MAX_DATA" }
        val ok = node.onNode {
            node.touch(routeId)
            node.sendTo(neighbor, Packet.data(routeId, frame).encode())
        }
        if (!ok) throw MeshClosed("сосед отключился")
    }

    suspend fun recv(): ByteArray =
        try { inbox.receive() } catch (e: ClosedReceiveChannelException) { throw MeshClosed("маршрут оборвался") }

    suspend fun close() {
        if (closed) return
        fail()
        node.onNode { if (node.routes.remove(routeId.k) != null) node.sendTo(neighbor, Packet.rerr(routeId).encode()) }
    }

    internal fun deliver(payload: ByteArray) {
        if (!closed) inbox.trySend(payload)
    }

    internal fun fail() {
        closed = true
        keepalive?.cancel()
        inbox.close()
    }
}
```

<!-- file: vsc_mobile/mesh/src/test/kotlin/ru/mobile/mys/mesh/MeshNodeTest.kt -->
```kotlin
package ru.mobile.mys.mesh

import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import ru.mobile.mys.p2p.Role
import ru.mobile.mys.p2p.handshake

class MeshNodeTest {
    private val prs = ByteArray(32) { it.toByte() }
    private val key = tagKey(prs)

    private fun fast(relay: Boolean = true) = MeshConfig(relay = relay, rreqRetryMs = 200, sweepMs = 200)

    private suspend fun link(a: MeshNode, b: MeshNode): Pair<Int, Int> = coroutineScope {
        val (wa, wb) = MemoryWire.pair()
        val x = async { a.attach(wa) }
        val y = async { b.attach(wb) }
        x.await() to y.await()
    }

    private suspend fun chain(n: Int) = List(n) { MeshNode(crypto, fast()) }.also { ns -> ns.zipWithNext().forEach { (x, y) -> link(x, y) } }

    private suspend fun both(a: MeshNode, b: MeshNode, ms: Long = 10_000) = coroutineScope {
        listOf(async { a.find(key, ms) }, async { b.find(key, ms) }).awaitAll()
    }

    private suspend fun closeAll(ns: List<MeshNode>) = ns.forEach { it.close() }

    @Test fun chainFindsAndHandshakes() = runBlocking {
        val ns = chain(6)
        try {
            val (a, b) = both(ns.first(), ns.last())
            assertEquals(setOf(MeshRole.INITIATOR, MeshRole.RESPONDER), setOf(a.first, b.first))
            val (ha, hb) = coroutineScope {
                listOf(
                    async { handshake(Adapter(a.second), prs, Role.valueOf(a.first.name), ristretto) },
                    async { handshake(Adapter(b.second), prs, Role.valueOf(b.first.name), ristretto) },
                ).awaitAll()
            }
            assertEquals(ha.sk.hex(), hb.sk.hex())
            a.second.send("privet".toByteArray())
            assertEquals("privet", b.second.recv().decodeToString())
            b.second.send("otvet".toByteArray())
            assertEquals("otvet", a.second.recv().decodeToString())
            assertTrue(ns[2].stats().getValue("relayed_bytes") > 0)
        } finally { closeAll(ns) }
    }

    @Test fun gridRelaysEachRreqOnce() = runBlocking {
        val ns = List(9) { MeshNode(crypto, MeshConfig(rreqRetryMs = 60_000, sweepMs = 60_000)) }
        for (i in 0 until 9) {
            if (i % 3 < 2) link(ns[i], ns[i + 1])
            if (i < 6) link(ns[i], ns[i + 3])
        }
        try {
            assertFailsWith<TimeoutCancellationException> { ns[0].find(key, 500) }
            val counts = ns.map { it.stats().getValue("rreq_forwarded") }
            assertTrue(counts[0] == 0L && counts.drop(1).all { it == 1L }, "$counts")
        } finally { closeAll(ns) }
    }

    @Test fun rerouteAfterBreak() = runBlocking {
        val (a, b, c, d) = List(4) { MeshNode(crypto, fast()) }
        val (ab, _) = link(a, b)
        val (ac, _) = link(a, c)
        link(b, d)
        link(c, d)
        try {
            val (x, y) = both(a, d)
            a.detach(if (b.stats().getValue("routes") > 0) ab else ac)
            for (t in listOf(x.second, y.second)) assertFailsWith<MeshClosed> { withTimeout(5_000) { t.recv() } }
            val (x2, y2) = both(a, d)
            x2.second.send("x".toByteArray())
            assertEquals("x", y2.second.recv().decodeToString())
        } finally { closeAll(listOf(a, b, c, d)) }
    }

    @Test fun simultaneousSearchMakesOneRoute() = runBlocking {
        val a = MeshNode(crypto, fast())
        val b = MeshNode(crypto, fast())
        link(a, b)
        try {
            val (x, y) = both(a, b, 5_000)
            assertEquals(setOf(MeshRole.INITIATOR, MeshRole.RESPONDER), setOf(x.first, y.first))
            delay(500)
            assertEquals(1L, a.stats().getValue("routes"))
            assertEquals(1L, b.stats().getValue("routes"))
        } finally { closeAll(listOf(a, b)) }
    }

    @Test fun noPeerTimesOut() = runBlocking {
        val ns = chain(3)
        try { assertFailsWith<TimeoutCancellationException> { ns[0].find(key, 500) } } finally { closeAll(ns) }
        Unit
    }

    @Test fun floodIsRateLimited() = runBlocking {
        val a = MeshNode(crypto, fast())
        val b = MeshNode(crypto, fast())
        link(a, b)
        val (wa, wx) = MemoryWire.pair()
        val evil = coroutineScope {
            val att = async { a.attach(wa) }
            val l = async { Link.open(wx, crypto) }
            att.await()
            l.await()
        }
        try {
            repeat(500) {
                evil.send(Packet.rreq(Random.nextBytes(16), Random.nextBytes(32), Random.nextBytes(16), 8).encode())
            }
            delay(300)
            assertTrue(a.stats().getValue("rreq_forwarded") <= 15)
            val (x, y) = both(a, b, 5_000)
            assertEquals(setOf(MeshRole.INITIATOR, MeshRole.RESPONDER), setOf(x.first, y.first))
        } finally { closeAll(listOf(a, b)) }
    }

    @Test fun relayOffBlocksTransitButNotOwn() = runBlocking {
        val a = MeshNode(crypto, fast())
        val r = MeshNode(crypto, fast(relay = false))
        val b = MeshNode(crypto, fast())
        link(a, r)
        link(r, b)
        try {
            val res = coroutineScope {
                listOf(async { runCatching { a.find(key, 1_000) } }, async { runCatching { b.find(key, 1_000) } }).awaitAll()
            }
            assertTrue(res.all { it.exceptionOrNull() is TimeoutCancellationException }, "$res")
            val (x, y) = both(a, r, 5_000)
            assertEquals(setOf(MeshRole.INITIATOR, MeshRole.RESPONDER), setOf(x.first, y.first))
        } finally { closeAll(listOf(a, r, b)) }
    }
}
```

- [ ] `./gradlew :mesh:test` → PASS. Commit `feat(mesh): узел — маршрутизация и точка встречи (Kotlin)`.

### Task 9: совместимость Kotlin ↔ Python через TCP

<!-- file: vsc_mobile/tools/mesh_peer.py -->
```python
"""Узел mesh десктопа для интероп-теста: TCP-ретранслятор R и конечная точка P за ним.

usage: mesh_peer.py <desktop_src> <port> <prs_hex> <out_dir>
P ищет канал по метке из prs, проходит настоящий handshake (CPace), пишет sk в
<out_dir>/sk.txt и возвращает один кадр эхом. Kotlin-узел подключается к R по TCP —
так проверяются линк, ретрансляция и маршрут между реализациями.
"""

import asyncio
import os
import sys

sys.path.insert(0, sys.argv[1])

from mys_decentralized.errors import TransportError  # noqa: E402
from mys_decentralized.handshake import handshake  # noqa: E402
from mys_decentralized.protocol import Role  # noqa: E402
from mys_decentralized.transport import Transport  # noqa: E402
from mys_mesh.node import MeshClosed, MeshConfig, MeshNode  # noqa: E402
from mys_mesh.tags import tag_key  # noqa: E402
from mys_mesh.wire import MemoryWire, TcpWire  # noqa: E402


class Adapter(Transport):
    def __init__(self, t):
        self.t = t

    async def send(self, data):
        try:
            await self.t.send(data)
        except MeshClosed as e:
            raise TransportError(str(e)) from e

    async def recv(self):
        try:
            return await self.t.recv()
        except MeshClosed as e:
            raise TransportError(str(e)) from e

    async def close(self):
        await self.t.close()


async def main(port: int, prs: bytes, out: str) -> None:
    relay, peer = MeshNode(MeshConfig(rreq_retry=0.5)), MeshNode(MeshConfig(rreq_retry=0.5))
    a, b = MemoryWire.pair()
    await asyncio.gather(relay.attach(a), peer.attach(b))
    server = await asyncio.start_server(
        lambda r, w: asyncio.ensure_future(relay.attach(TcpWire(r, w))), "127.0.0.1", port)
    open(os.path.join(out, "ready"), "w").close()
    role, t = await peer.find(tag_key(prs), 60)
    res = await handshake(Adapter(t), prs, Role(int(role)))
    with open(os.path.join(out, "sk.tmp"), "w") as f:
        f.write(res.sk.hex())
    os.replace(os.path.join(out, "sk.tmp"), os.path.join(out, "sk.txt"))
    await t.send(await t.recv())
    await asyncio.sleep(1)
    server.close()
    await relay.close()
    await peer.close()


asyncio.run(main(int(sys.argv[2]), bytes.fromhex(sys.argv[3]), sys.argv[4]))
```

<!-- file: vsc_mobile/mesh/src/test/kotlin/ru/mobile/mys/mesh/MeshInteropTest.kt -->
```kotlin
package ru.mobile.mys.mesh

import java.io.File
import java.net.ServerSocket
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.mobile.mys.p2p.Role
import ru.mobile.mys.p2p.handshake

/** Kotlin-узел ↔ узлы десктопа (ретранслятор + собеседник) по TCP. Нужен DESKTOP_PYTHON, иначе пропуск. */
class MeshInteropTest {
    private val root = File(System.getProperty("mys.root"))
    private val python: String? = System.getenv("DESKTOP_PYTHON")
    private val desktopSrc = System.getenv("DESKTOP_SRC") ?: root.resolve("../vsc_desktop/src").canonicalPath

    @Test fun kotlinMeetsDesktopThroughRelay() = runBlocking {
        assumeTrue("нужен DESKTOP_PYTHON", python != null)
        val tmp = createTempDirectory("mys-mesh").toFile()
        val port = ServerSocket(0).use { it.localPort }
        val prs = ByteArray(32) { (it * 7).toByte() }
        val proc = ProcessBuilder(python, root.resolve("tools/mesh_peer.py").path, desktopSrc, "$port", prs.hex(), tmp.path)
            .redirectErrorStream(true).redirectOutput(tmp.resolve("peer.log")).start()
        val node = MeshNode(crypto, MeshConfig(rreqRetryMs = 500))
        try {
            withTimeout(30_000) { while (!tmp.resolve("ready").exists()) delay(100) }
            node.attach(TcpWire.connect("127.0.0.1", port))
            val (role, t) = node.find(tagKey(prs), 60_000)
            val res = handshake(Adapter(t), prs, Role.valueOf(role.name), ristretto)
            t.send("привет".toByteArray())
            assertEquals("привет", t.recv().decodeToString())
            withTimeout(10_000) { while (!tmp.resolve("sk.txt").exists()) delay(100) }
            assertEquals(res.sk.hex(), tmp.resolve("sk.txt").readText())
        } finally {
            node.close()
            proc.destroy()
            println(tmp.resolve("peer.log").takeIf { it.exists() }?.readText())
        }
    }
}
```

- [ ] `DESKTOP_PYTHON=$SCRATCH/dvenv/bin/python ./gradlew :mesh:test --tests '*MeshInteropTest*'` (+ `MYS_LIBSODIUM`) → PASS. Commit `test(mesh): интероп Kotlin ↔ десктоп через TCP-ретранслятор`.

### Task 10: документация и итоговый прогон

- [ ] `vsc_mobile/CLAUDE.md`, раздел «Команды»: `- Mesh (ядро): ./gradlew :mesh:test; интероп с десктопом — DESKTOP_PYTHON=… ./gradlew :mesh:test (без переменной пропускается)`.
- [ ] Полные прогоны: десктоп `dtest.sh -q`; телефон `./gradlew :crypto:test :p2p:test :mesh:test :app:testDebugUnitTest`.
- [ ] PR в обоих репозиториях (`feat/mesh-core`), без автора-ассистента.
