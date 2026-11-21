"""ريسيفر وهمي بيتكلم بروتوكول G-MScreen (زي تايجر) لتجربة GmsClient.

التشغيل: python3 -I fake_receiver.py <workdir> [--json] [--platform N] [--full]
"""
import socket
import struct
import sys
import threading
import time
import os

WORK = sys.argv[1]
JSON = "--json" in sys.argv
FULL = "--full" in sys.argv
PLATFORM = int(sys.argv[sys.argv.index("--platform") + 1]) if "--platform" in sys.argv else 20
LOG = open(os.path.join(WORK, "rcv.log"), "a", buffering=1)


def log(*a):
    print(*a, file=LOG)


def scramble(b):
    n = len(b)
    for i in range(n // 2):
        j = n - 1 - i
        b[i], b[j] = b[j], b[i]
        b[i] ^= 0x5B
        b[j] ^= 0x5B
    if n % 2:
        b[n // 2] ^= 0x5B


def info_packet():
    b = bytearray(108)
    b[0:12] = b"39WwijOog54a"
    b[12:20] = b"\x01\x02\x03\x04\x05\x06\x07\x08"
    name = b"TIGER T8 HIGH CLASS V2"
    b[20:20 + len(name)] = name
    b[68:72] = bytes([127, 0, 0, 1])
    b[72] = PLATFORM
    flags = (0x40 if JSON else 0) | (0x01 if FULL else 0)
    b[84] = flags
    scramble(b)
    return bytes(b)


def read_frame(conn):
    head = b""
    while len(head) < 15:
        c = conn.recv(15 - len(head))
        if not c:
            raise EOFError
        head += c
    assert head[:5] == b"Start" and head[12:15] == b"End", head
    n = int(head[5:12])
    body = b""
    while len(body) < n:
        c = conn.recv(n - len(body))
        if not c:
            raise EOFError
        body += c
    return body.decode("utf-8")


def gcdh(cmd, data=b""):
    return b"GCDH" + struct.pack(">III", len(data), cmd, 0) + data


def client(conn):
    try:
        first = read_frame(conn)
        log("LOGIN", "ok" if 'request="998"' in first and "<uuid>" in first else "BAD " + first)
        conn.sendall(info_packet())
        while True:
            m = read_frame(conn)
            log("CMD", m.replace("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>", ""))
            if 'request="26"' in m or '"request":"26"' in m:
                conn.sendall(gcdh(26))
            else:
                conn.sendall(gcdh(2003, b"<x/>"))  # إشعار عشوائي عشان نتأكد إن العميل بيقرا ويرمي
    except EOFError:
        log("closed")
    except Exception as e:  # noqa: BLE001
        log("error", repr(e))
    finally:
        conn.close()


def announce():
    u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    while True:
        try:
            u.sendto(info_packet(), ("127.0.0.1", 25860))
        except OSError:
            pass
        time.sleep(0.5)


threading.Thread(target=announce, daemon=True).start()
srv = socket.socket()
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", 20000))
srv.listen()
log("ready")
while True:
    c, _ = srv.accept()
    threading.Thread(target=client, args=(c,), daemon=True).start()
