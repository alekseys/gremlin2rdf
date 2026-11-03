"""Private TLS SDK gateway fixture; not proof of a live Cosmos account storage contract."""
import datetime
import ipaddress
import json
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
import ssl
import sys
import threading
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

root = Path(sys.argv[1])
key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "localhost")])
now = datetime.datetime.now(datetime.timezone.utc)
cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
        .serial_number(x509.random_serial_number()).not_valid_before(now - datetime.timedelta(minutes=1))
        .not_valid_after(now + datetime.timedelta(days=1))
        .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
        .add_extension(x509.SubjectAlternativeName([x509.DNSName("localhost"), x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]), critical=False)
        .sign(key, hashes.SHA256()))
cert_path, key_path = root / "fixture-ca.pem", root / "fixture-key.pem"
cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
key_path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(cert_path, key_path)
summary = {"requests": 0, "requestCharge": 0, "rejectedTemplates": 0, "documentPages": 0, "mutations": 0}
summary["routes"] = {}
summary_lock = threading.RLock()
def save():
    with summary_lock:
        (root / "source-summary.json").write_text(json.dumps(summary))
def metadata(identity):
    return {"_rid": identity, "_self": "fixture/" + identity, "_etag": identity,
            "_attachments": "attachments/", "_ts": 123}
def vertex(partition, identity):
    return {"id": identity, "pk": partition, "label": "Person",
            "name": [{"id": "name-1", "_value": identity, "_meta": {}}],
            "tag": [{"id": "tag-1", "_value": "same", "_meta": {"rank": 1}},
                    {"id": "tag-2", "_value": "same", "_meta": {"rank": 2}}],
            "age": [{"id": "age-1", "_value": 42, "_meta": {}}],
            "active": [{"id": "active-1", "_value": True, "_meta": {}}],
            **metadata(partition + "-" + identity)}
documents = [vertex("p0", "a"), vertex("p0", "b"), vertex("p1", "a")]
documents += [{"id": identity, "pk": "p0", "label": "knows", "_isEdge": True,
               "_vertexId": "a", "_vertexLabel": "Person", "_sink": "a", "_sinkLabel": "Person",
               "_sinkPartition": target, "weight": 1.5, **metadata(identity)}
              for identity, target in [("e0", "p1"), ("e1", "p1"), ("e2", "p0")]]

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_): pass
    def do_GET(self):
        path = self.path.rstrip("/")
        with summary_lock:
            summary["requests"] += 1
            summary["routes"][path] = summary["routes"].get(path, 0) + 1
        charge, continuation = None, None
        status = 200
        if not path:
            endpoint = f"https://localhost:{self.server.server_port}/"
            body = {"id": "fixture", "_rid": "fixture", "enableMultipleWriteLocations": False,
                    "writableLocations": [{"name": "fixture", "databaseAccountEndpoint": endpoint}],
                    "readableLocations": [{"name": "fixture", "databaseAccountEndpoint": endpoint}],
                    "userConsistencyPolicy": {"defaultConsistencyLevel": "Session", "maxIntervalInSeconds": 5, "maxStalenessPrefix": 100}}
        elif path == "/dbs/fixture/colls/graph":
            body = {"id": "graph", "_rid": "IXYFAOHEBPM=", "_self": "dbs/IXYFAA==/colls/IXYFAOHEBPM=/",
                    "_etag": "fixture", "partitionKey": {"paths": ["/pk"], "kind": "Hash", "version": 2},
                    "indexingPolicy": {"automatic": True, "indexingMode": "consistent", "includedPaths": [{"path": "/*"}], "excludedPaths": []}}
        elif path in ["/dbs/fixture/colls/graph/pkranges", "/dbs/IXYFAA==/colls/IXYFAOHEBPM=/pkranges"]:
            if self.headers.get("If-None-Match"):
                status, body = 304, None
            else:
                body = {"_rid": "IXYFAOHEBPM=", "PartitionKeyRanges": [{"id": "0", "minInclusive": "", "maxExclusive": "FF",
                        "_rid": "IXYFAOHEBPMCAAAAAAAAUA==", "throughputFraction": 1, "status": "online", "parents": []}], "_count": 1}
        elif path in ["/dbs/fixture/colls/graph/docs", "/dbs/IXYFAA==/colls/IXYFAOHEBPM=/docs"]:
            token = self.headers.get("x-ms-continuation", "")
            start = int(token) if token.isdigit() else 0
            maximum = max(1, min(2, int(self.headers.get("x-ms-max-item-count", "2"))))
            page = documents[start:start + maximum]
            continuation = str(start + len(page)) if start + len(page) < len(documents) else None
            charge = 1
            with summary_lock:
                summary["documentPages"] += 1
                summary["requestCharge"] += charge
            body = {"_rid": "IXYFAOHEBPM=", "Documents": page, "_count": len(page)}
        else:
            with summary_lock:
                summary["rejectedTemplates"] += 1
            status, body = 404, {"code": "NotFound", "message": "fixture route"}
        save()
        raw = json.dumps(body).encode() if body is not None else b""
        self.send_response(status)
        for header, value in {"Content-Type": "application/json", "Content-Length": str(len(raw)),
                              "x-ms-activity-id": "00000000-0000-0000-0000-000000000001",
                              "x-ms-session-token": "0:-1#1", "etag": '"fixture-ranges"',
                              "x-ms-schemaversion": "1.20", "x-ms-item-count": "1",
                              "x-ms-gatewayversion": "version=2.14.0", "x-ms-serviceversion": "version=2.14.0"}.items():
            self.send_header(header, value)
        if charge is not None: self.send_header("x-ms-request-charge", str(charge))
        if continuation is not None: self.send_header("x-ms-continuation", continuation)
        self.end_headers()
        self.wfile.write(raw)
    def do_POST(self):
        with summary_lock:
            summary["mutations"] += 1
        save()
        self.send_error(405)
    do_PUT = do_DELETE = do_PATCH = do_POST

server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
server.socket = context.wrap_socket(server.socket, server_side=True)
save()
(root / "source-ready.json").write_text(json.dumps({"port": server.server_port}))
server.serve_forever()
