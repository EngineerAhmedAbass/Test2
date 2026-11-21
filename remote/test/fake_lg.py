"""تليفزيون LG وهمي (webOS) لتجربة LgClient على الكمبيوتر.

التشغيل: python3 -I fake_lg.py <workdir> [--no-wss] [--reject-signed]
بيكتب كل اللي وصله في <workdir>/lg.log
"""
import asyncio
import datetime
import json
import os
import ssl
import sys

import websockets
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

WORK = sys.argv[1]
NO_WSS = "--no-wss" in sys.argv
REJECT_SIGNED = "--reject-signed" in sys.argv
NEW_FW = "--new-fw" in sys.argv          # زي webOS 23: مفيش ws:3000 بس socketPath بيقول ws://:3000
NO_POINTER = "--no-pointer" in sys.argv  # التليفزيون رافض يدي socket الزراير
LOG = open(os.path.join(WORK, "lg.log"), "a", buffering=1)
KEY = "client-key-123"
state = {"scheme": "wss" if not NO_WSS else "ws"}


def log(*a):
    print(*a, file=LOG)


def tls_ctx():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "LG webOS TV")])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(1).not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=30)).sign(key, hashes.SHA256()))
    pem = os.path.join(WORK, "lg.pem")
    open(pem, "wb").write(cert.public_bytes(serialization.Encoding.PEM) + key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    c = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    c.load_cert_chain(pem)
    return c


async def pointer(ws):
    async for m in ws:
        log("INPUT", m.replace("\n", "|"))


async def main_socket(ws):
    async for raw in ws:
        m = json.loads(raw)
        t, mid, uri, payload = m.get("type"), m.get("id"), m.get("uri", ""), m.get("payload", {})
        if t == "hello":
            await ws.send(json.dumps({"type": "hello", "id": mid, "payload": {"deviceType": "tv", "deviceOS": "webOS"}}))
        elif t == "register":
            signed = "signatures" in payload["manifest"]
            log("REGISTER", "signed" if signed else "unsigned", "key=" + str(payload.get("client-key")),
                "perms=%d" % len(payload["manifest"]["permissions"]))
            if signed and REJECT_SIGNED:
                await ws.send(json.dumps({"type": "error", "id": mid, "error": "500 Application error"}))
                continue
            if payload.get("client-key") == KEY:
                await ws.send(json.dumps({"type": "registered", "id": mid, "payload": {"client-key": KEY}}))
            else:
                await ws.send(json.dumps({"type": "response", "id": mid, "payload": {"pairingType": "PROMPT", "returnValue": True}}))
                log("PROMPT shown")
                await asyncio.sleep(1.0)  # المستخدم بيدوس "سماح"
                await ws.send(json.dumps({"type": "registered", "id": mid, "payload": {"client-key": KEY}}))
        elif t == "request":
            log("REQUEST", uri, json.dumps(payload, ensure_ascii=False))
            p = {"returnValue": True}
            if uri.endswith("getSystemInfo"):
                p["modelName"] = "OLED55C1"
            elif uri.endswith("getPointerInputSocket"):
                if NO_POINTER:
                    await ws.send(json.dumps({"type": "error", "id": mid, "error": "401 insufficient permissions", "payload": {}}))
                    continue
                port = 3001 if state["scheme"] == "wss" else 3000
                scheme = state["scheme"]
                if NEW_FW:
                    scheme, port = "ws", 3000
                p["socketPath"] = f"{scheme}://127.0.0.1:{port}/resources/abc123/netinput.pointer.sock"
            elif uri.endswith("connectionmanager/getinfo"):
                p["wiredInfo"] = {"macAddress": "a8:23:fe:01:02:03"}
            await ws.send(json.dumps({"type": "response", "id": mid, "payload": p}))
        elif t == "subscribe":
            log("SUBSCRIBE", uri)
            if uri.endswith("getVolume"):
                p = {"volumeStatus": {"volume": 12, "muteStatus": False, "maxVolume": 100}}
            elif uri.endswith("getForegroundAppInfo"):
                p = {"appId": "youtube.leanback.v4"}
            elif uri.endswith("getPowerState"):
                p = {"state": "Active"}
            elif uri.endswith("listLaunchPoints"):
                p = {"launchPoints": [
                    {"id": "youtube.leanback.v4", "title": "YouTube", "icon": "http://127.0.0.1/yt.png"},
                    {"id": "netflix", "title": "Netflix", "icon": "http://127.0.0.1/nf.png"},
                    {"id": "com.webos.app.hdmi1", "title": "HDMI 1", "icon": ""}]}
            else:
                p = {}
            p["returnValue"] = True
            await ws.send(json.dumps({"type": "response", "id": mid, "payload": p}))


async def handler(ws):
    path = ws.request.path
    if "netinput.pointer.sock" in path:
        await pointer(ws)
    else:
        try:
            await main_socket(ws)
        except websockets.ConnectionClosed:
            log("closed")


async def main():
    if not NEW_FW:
        await websockets.serve(handler, "127.0.0.1", 3000)
    if not NO_WSS:
        await websockets.serve(handler, "127.0.0.1", 3001, ssl=tls_ctx())
    log("ready")
    await asyncio.Future()


asyncio.run(main())
