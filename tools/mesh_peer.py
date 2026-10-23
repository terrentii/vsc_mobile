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
