"""Тест-векторы крипто-ядра МЫС из эталонной реализации vsc_desktop.

Все входы детерминированы (SHAKE-256 от меток), чтобы Kotlin-порт мог
воспроизвести вычисления байт-в-байт. Запуск (из корня vsc_mobile, в nix develop):

    ../vsc_desktop/.venv/bin/python tools/gen_vectors.py ../vsc_desktop/src \
        > crypto/src/test/resources/vectors.json
"""

import hashlib
import importlib
import json
import sys

sys.path.insert(0, sys.argv[1])

from mys_crypto import _ristretto, pake  # noqa: E402
from mys_crypto import envelope as E  # noqa: E402
from mys_crypto import primitives as P  # noqa: E402
from mys_crypto import ratchet as R  # noqa: E402
from mys_crypto import transform as T  # noqa: E402
# Подмодули через importlib: __init__ пакета экспортирует функцию handshake,
# которая перекрывает одноимённый подмодуль при `from … import handshake`.
FT = importlib.import_module("mys_decentralized.filetransfer")
HS = importlib.import_module("mys_decentralized.handshake")
PR = importlib.import_module("mys_decentralized.protocol")

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

v["room_params"] = [
    {"phrase": p, "room_id": H(r), "prs": H(s)}
    for p in ["тестовая фраза", "  Тест ﬁ  ", "a"]
    for r, s in [PR.derive_room_params(p)]
]

frames = {
    "hello": PR.Hello(det("room"), [("127.0.0.1", 5555), ("192.168.1.2", 40000)]),
    "hello_empty": PR.Hello(det("room")),
    "pair_initiator": PR.Pair(PR.Role.INITIATOR),
    "pair_responder": PR.Pair(PR.Role.RESPONDER, [("10.0.0.1", 1)]),
    "pake": PR.Pake(det("y")),
    "pake_ad": PR.Pake(det("y"), b"ad"),
    "confirm": PR.Confirm(det("mac")),
    "data": PR.Data(det("sealed", 50)),
    "relay": PR.Relay(PR.encode_message(PR.Pake(det("y")))),
    "punch": PR.Punch(),
    "punch_ack": PR.PunchAck(),
    "peer_left": PR.PeerLeft(),
}
v["frames"] = {k: H(PR.encode_message(m)) for k, m in frames.items()}

meta = FT.FileMeta(transfer_id=det("tid", 16), total_size=300000, chunk_size=65536,
                   chunk_count=5, sha256=det("sha"), mime_type="image/png", filename="фото.png")
v["file_meta"] = {"hex": H(FT.encode_file_meta(meta)), "transfer_id": H(meta.transfer_id),
                  "total_size": meta.total_size, "chunk_size": meta.chunk_size,
                  "chunk_count": meta.chunk_count, "sha256": H(meta.sha256),
                  "mime_type": meta.mime_type, "filename": meta.filename}
chunk = FT.FileChunk(transfer_id=det("tid", 16), index=3, data=b"abc")
v["file_chunk"] = {"hex": H(FT.encode_file_chunk(chunk)), "transfer_id": H(chunk.transfer_id),
                   "index": chunk.index, "data": H(chunk.data)}

c0 = v["cpace"][0]
isk = bytes.fromhex(c0["isk"])
ya_pub, yb_pub = bytes.fromhex(c0["Ya"]), bytes.fromhex(c0["Yb"])
v["handshake"] = {
    "prs": c0["prs"], "sid": H(HS._derive_sid(bytes.fromhex(c0["prs"]))),
    "isk": c0["isk"], "my_y": c0["Ya"], "peer_y": c0["Yb"],
    "confirm_mac": H(HS._confirm_mac(isk, ya_pub, yb_pub)),
    "sk": H(P.hkdf(isk, 32, b"", b"mys-ratchet-root")),
    "bob_pub": H(P.x25519_keypair_from_seed(P.hkdf(isk, 32, b"", b"mys-ratchet-bob-dh"))[1]),
}

json.dump(v, sys.stdout, ensure_ascii=False, indent=1)
print()
