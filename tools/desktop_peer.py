"""Эхо-пир на коде vsc_desktop — для интероп-теста Kotlin ↔ Python (p2p InteropTest).

    python tools/desktop_peer.py <desktop_src> <rendezvous_url> <phrase> <vault_dir>

Поднимает настоящий P2PService десктопа: на текст отвечает «echo: <текст>», на
файл — «sha256:<hex>», на «bye» выходит с кодом 0.
"""

import hashlib
import os
import queue
import sys

sys.path.insert(0, sys.argv[1])

from mys_decentralized import P2PService  # noqa: E402
from mys_storage import create_vault  # noqa: E402

url, phrase, vault_dir = sys.argv[2:5]
vault = create_vault(os.path.join(vault_dir, "peer.db"), b"pw",
                     params={"time_cost": 1, "memory_cost": 8, "parallelism": 1})
inbox: queue.Queue = queue.Queue()
# Колбэк — в потоке сервиса; отвечать оттуда нельзя (send ждёт тот же loop) — через очередь.
svc = P2PService(vault, url, on_message=lambda cid, body: inbox.put(cid), connect_timeout=60.0)
svc.start()
try:
    cid = svc.start_session(phrase)
    print("connected", svc.role_of(cid).name, flush=True)
    handled = 0
    while True:
        inbox.get(timeout=60)
        incoming = [m for m in vault.messages.list(cid) if m["direction"] == "in"]
        msg = incoming[handled]
        handled += 1
        body = bytes(msg["body"])
        if msg["kind"] != "text":
            svc.send(cid, "sha256:" + hashlib.sha256(body).hexdigest())
        elif body == b"bye":
            break
        else:
            svc.send(cid, "echo: " + body.decode())
finally:
    svc.stop()
