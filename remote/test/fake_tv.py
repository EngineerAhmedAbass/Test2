"""تليفزيون وهمي لتجربة TvClient على الكمبيوتر (من غير تليفزيون حقيقي).

بيستخدم تعريفات protobuf الحقيقية بتاعة البروتوكول (من مكتبة androidtvremote2) عشان
يتأكد إن الرسايل اللي التطبيق بيبعتها مطابقة.

التشغيل: python3 -I fake_tv.py <path/to/androidtvremote2> <workdir> <client_cert.pem>
"""
import ast
import datetime
import hashlib
import os
import secrets
import socket
import ssl
import sys
import threading

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
from google.protobuf import descriptor_pb2, descriptor_pool, message_factory
from google.protobuf.internal.decoder import _DecodeVarint
from google.protobuf.internal.encoder import _VarintBytes

LIB, WORK, CLIENT_PEM = sys.argv[1], sys.argv[2], sys.argv[3]
LOG = open(os.path.join(WORK, "tv.log"), "a", buffering=1)


def log(*a):
    print(*a, file=LOG)


def load_proto(name):
    tree = ast.parse(open(os.path.join(LIB, name)).read())
    data = max((n.value for n in ast.walk(tree) if isinstance(n, ast.Constant) and isinstance(n.value, bytes)), key=len)
    return descriptor_pb2.FileDescriptorProto.FromString(data)


pool = descriptor_pool.DescriptorPool()
pool.Add(load_proto("polo_pb2.py"))
pool.Add(load_proto("remotemessage_pb2.py"))
OuterMessage = message_factory.GetMessageClass(pool.FindMessageTypeByName("polo.wire.protobuf.OuterMessage"))
RemoteMessage = message_factory.GetMessageClass(pool.FindMessageTypeByName("remote.RemoteMessage"))

# شهادة التليفزيون
key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "FakeTV")])
now = datetime.datetime.now(datetime.timezone.utc)
cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
        .serial_number(1).not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=30)).sign(key, hashes.SHA256()))
open(os.path.join(WORK, "tv.pem"), "wb").write(
    cert.public_bytes(serialization.Encoding.PEM)
    + key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))

# شهادة وهمية تانية عشان قبل الاقتران الريموت يرفض شهادة الموبايل
other_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
other = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(other_key.public_key())
         .serial_number(2).not_valid_before(now - datetime.timedelta(days=1))
         .not_valid_after(now + datetime.timedelta(days=30)).sign(other_key, hashes.SHA256()))
open(os.path.join(WORK, "other.pem"), "wb").write(other.public_bytes(serialization.Encoding.PEM))

state = {"paired": False}


def ctx(cafile):
    c = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    c.load_cert_chain(os.path.join(WORK, "tv.pem"))
    c.verify_mode = ssl.CERT_REQUIRED
    c.load_verify_locations(cafile)
    return c


def recv(conn, cls):
    buf = b""
    while True:
        try:
            n, pos = _DecodeVarint(buf, 0)
            if len(buf) >= pos + n:
                m = cls()
                m.ParseFromString(buf[pos:pos + n])
                recv.rest = buf[pos + n:]
                return m
        except IndexError:
            pass
        chunk = conn.recv(4096)
        if not chunk:
            raise EOFError
        buf += chunk


def send(conn, m):
    b = m.SerializeToString()
    conn.sendall(_VarintBytes(len(b)) + b)


def ib(v):
    return v.to_bytes((v.bit_length() + 7) // 8, "big")


def pairing(conn):
    def out():
        m = OuterMessage()
        m.protocol_version = 2
        m.status = 200
        return m

    m = recv(conn, OuterMessage)
    log("PAIR pairing_request", m.pairing_request.service_name, m.pairing_request.client_name)
    r = out(); r.pairing_request_ack.server_name = "FakeTV"; send(conn, r)
    m = recv(conn, OuterMessage)
    log("PAIR options", m.options.input_encodings[0].type, m.options.input_encodings[0].symbol_length, m.options.preferred_role)
    r = out(); e = r.options.input_encodings.add(); e.type = 3; e.symbol_length = 6; r.options.preferred_role = 1; send(conn, r)
    m = recv(conn, OuterMessage)
    log("PAIR configuration", m.configuration.encoding.type, m.configuration.encoding.symbol_length, m.configuration.client_role)
    r = out(); r.configuration_ack.SetInParent(); send(conn, r)

    client = x509.load_der_x509_certificate(conn.getpeercert(True)).public_key().public_numbers()
    server = key.public_key().public_numbers()
    tail = secrets.token_bytes(2)
    h = hashlib.sha256(ib(client.n) + ib(client.e) + ib(server.n) + ib(server.e) + tail).digest()
    code = "%02X" % h[0] + tail.hex().upper()
    open(os.path.join(WORK, "code.txt"), "w").write(code)
    log("PAIR code", code)

    m = recv(conn, OuterMessage)
    if m.secret.secret == h:
        r = out(); r.secret_ack.secret = h; send(conn, r)
        state["paired"] = True
        log("PAIR ok")
    else:
        r = out(); r.status = 402; send(conn, r)
        log("PAIR bad secret")


def remote(conn):
    m = RemoteMessage()
    m.remote_configure.code1 = 622
    m.remote_configure.device_info.model = "MIBOX4"
    m.remote_configure.device_info.vendor = "Xiaomi"
    send(conn, m)
    r = recv(conn, RemoteMessage)
    log("REMOTE configure", r.remote_configure.code1, r.remote_configure.device_info.package_name)
    m = RemoteMessage(); m.remote_set_active.SetInParent(); send(conn, m)
    r = recv(conn, RemoteMessage)
    log("REMOTE set_active", r.remote_set_active.active)
    m = RemoteMessage(); m.remote_start.started = True; send(conn, m)
    m = RemoteMessage(); m.remote_set_volume_level.volume_max = 100; m.remote_set_volume_level.volume_level = 30; send(conn, m)
    m = RemoteMessage(); m.remote_ime_key_inject.app_info.counter = 7; m.remote_ime_key_inject.app_info.app_package = "com.google.android.youtube.tv"
    m.remote_ime_key_inject.text_field_status.counter_field = 3; send(conn, m)
    m = RemoteMessage(); m.remote_ping_request.val1 = 42; send(conn, m)
    r = recv(conn, RemoteMessage)
    log("REMOTE ping_response", r.remote_ping_response.val1)
    while True:
        r = recv(conn, RemoteMessage)
        if r.HasField("remote_key_inject"):
            log("REMOTE key", r.remote_key_inject.key_code, r.remote_key_inject.direction)
        elif r.HasField("remote_ime_batch_edit"):
            b = r.remote_ime_batch_edit
            log("REMOTE text", b.ime_counter, b.field_counter, b.edit_info[0].insert, b.edit_info[0].text_field_status.value,
                b.edit_info[0].text_field_status.start, b.edit_info[0].text_field_status.end)
        elif r.HasField("remote_app_link_launch_request"):
            log("REMOTE launch", r.remote_app_link_launch_request.app_link)
        else:
            log("REMOTE other", r)


def serve(port, handler, cafile_fn):
    s = socket.socket()
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.1", port))
    s.listen()
    while True:
        raw, _ = s.accept()
        def run(raw=raw):
            try:
                conn = ctx(cafile_fn()).wrap_socket(raw, server_side=True)
            except Exception as e:  # noqa: BLE001
                log("TLS refused on", port, type(e).__name__)
                raw.close()
                return
            try:
                handler(conn)
            except EOFError:
                log("closed", port)
            except Exception as e:  # noqa: BLE001
                log("error", port, repr(e))
            finally:
                conn.close()
        threading.Thread(target=run, daemon=True).start()


threading.Thread(target=serve, args=(6467, pairing, lambda: CLIENT_PEM), daemon=True).start()
log("ready")
serve(6466, remote, lambda: CLIENT_PEM if state["paired"] else os.path.join(WORK, "other.pem"))
