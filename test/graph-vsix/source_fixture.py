"""Private loopback GraphSON fixture, not a Cosmos compatibility simulator."""
import asyncio
import datetime
import ipaddress
import json
from pathlib import Path
import ssl
import sys
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
from websockets.asyncio.server import serve

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

def vertex(partition, identity):
    props = [{"key": "pk", "value": partition, "id": "pk-1", "meta": {}},
             {"key": "name", "value": identity, "id": "name-1", "meta": {}},
             {"key": "age", "value": 42, "id": "age-1", "meta": {}},
             {"key": "active", "value": True, "id": "active-1", "meta": {}},
             {"key": "score", "value": 1.5, "id": "score-1", "meta": {}},
             {"key": "tag", "value": "same", "id": "tag-1", "meta": {"rank": 1}},
             {"key": "tag", "value": "same", "id": "tag-2", "meta": {"rank": 2}}]
    return {"id": identity, "partition": partition, "label": "Person", "properties": props}

vertices = [vertex("p0", "a"), vertex("p0", "b"), vertex("p1", "a")]
edges = [{"id": identity, "partition": "p0", "label": "knows",
          "source": {"id": "a", "partition": "p0"},
          "destination": {"id": "a", "partition": destination},
          "properties": [{"key": "weight", "value": 1.5}]} for identity, destination in
         [("e0", "p1"), ("e1", "p1"), ("e2", "p0")]]

def decode(value):
    if isinstance(value, dict):
        if "@type" in value:
            raw = value["@value"]
            if value["@type"] == "g:Map":
                return {decode(raw[i]): decode(raw[i + 1]) for i in range(0, len(raw), 2)}
            return decode(raw)
        return {k: decode(v) for k, v in value.items()}
    if isinstance(value, list):
        return [decode(v) for v in value]
    return value

def encode(value):
    if isinstance(value, bool) or value is None or isinstance(value, str):
        return value
    if isinstance(value, int):
        return {"@type": "g:Int64", "@value": value}
    if isinstance(value, float):
        return {"@type": "g:Double", "@value": value}
    if isinstance(value, list):
        return [encode(v) for v in value]
    return {k: encode(v) for k, v in value.items()}

vertex_selection = "g.V([g2rPartition,g2rElementId])"
edge_selection = "g.V([g2rPartition,g2rOwnerId]).outE().hasId(g2rElementId)"
allowed_scripts = {"g.V().limit(1).count()"}
for selection in ["g.V()", "g.E()", "g.V().has(g2rPartitionProperty,g2rPartition)",
                  "g.V().has(g2rPartitionProperty,g2rPartition).outE()"]:
    for label in ["", ".hasLabel(g2rObservedLabel)"]:
        allowed_scripts.add(selection + label + ".count()")
for selection in ["g.V().has(g2rPartitionProperty,g2rPartition)", "g.V([g2rPartition,g2rOwner]).outE()"]:
    for cursor in ["", ".hasId(gt(g2rAfter))"]:
        allowed_scripts.add(selection + cursor + ".id().order().limit(g2rPageSize).fold()")
for selection in [vertex_selection, edge_selection]:
    allowed_scripts.add(selection + ".properties().count()")
    allowed_scripts.add("g.withStrategies(ProjectionStrategy.build().IncludeSystemProperties(g2rSystemKey).create())"
                        + selection[1:] + ".values(g2rSystemKey).fold()")
allowed_scripts.add(vertex_selection
                    + ".project('id','label','partition','properties').by(id()).by(label()).by(values(g2rPartitionProperty))"
                    + ".by(properties().project('key','value','id','meta').by(key()).by(value()).by(id()).by(valueMap()).fold())")
allowed_scripts.add(edge_selection
                    + ".project('id','label','partition','source','destination','properties').by(id()).by(label())"
                    + ".by(outV().values(g2rPartitionProperty))"
                    + ".by(outV().project('id','partition').by(id()).by(values(g2rPartitionProperty)))"
                    + ".by(inV().project('id','partition').by(id()).by(values(g2rPartitionProperty)))"
                    + ".by(properties().project('key','value').by(key()).by(value()).fold())")

def evaluate(script, bindings):
    if script == "g.V().limit(1).count()":
        return 1
    if any(token in script for token in [".addV(", ".addE(", ".drop(", ".property("]):
        raise ValueError("Source mutations prohibited")
    if script not in allowed_scripts:
        raise ValueError("Unrecognised bounded adapter template")
    if bindings.get("g2rPartitionProperty", "pk") != "pk":
        raise ValueError("Unknown fixture partition property")
    selected = vertices
    if "g2rElementId" in bindings:
        if "outE()" in script:
            selected = [e for e in edges if e["partition"] == bindings["g2rPartition"] and
                        e["source"]["id"] == bindings["g2rOwnerId"] and e["id"] == bindings["g2rElementId"]]
        else:
            selected = [v for v in vertices if v["partition"] == bindings["g2rPartition"] and v["id"] == bindings["g2rElementId"]]
    elif "g2rOwner" in bindings:
        selected = [e for e in edges if e["partition"] == bindings["g2rPartition"] and e["source"]["id"] == bindings["g2rOwner"]]
    else:
        if "g2rPartition" in bindings:
            selected = [v for v in vertices if v["partition"] == bindings["g2rPartition"]]
        if script.startswith("g.E()"):
            selected = edges
        elif ".outE()" in script:
            owners = {(v["partition"], v["id"]) for v in selected}
            selected = [e for e in edges if (e["partition"], e["source"]["id"]) in owners]
    if "g2rObservedLabel" in bindings:
        selected = [v for v in selected if v["label"] == bindings["g2rObservedLabel"]]
    if "g2rSystemKey" in bindings:
        assert len(selected) == 1
        fault_path = root / "source-fault.json"
        if fault_path.exists() and json.loads(fault_path.read_text()).get("missingSystemKey") == bindings["g2rSystemKey"]:
            return []
        row = selected[0]
        metadata = {"_etag": "stable", "_ts": 123, "_self": "fixture", "_attachments": "fixture", "_rid": "fixture",
                    "inVPartition": row.get("destination", {}).get("partition"),
                    "outVPartition": row.get("source", {}).get("partition")}
        return [metadata[bindings["g2rSystemKey"]]]
    if script.endswith(".properties().count()"):
        return sum(len(row["properties"]) for row in selected)
    if script.endswith(".count()"):
        return len(selected)
    if script.endswith(".id().order().limit(g2rPageSize).fold()"):
        return sorted(row["id"] for row in selected if row["id"] > bindings.get("g2rAfter", ""))[:bindings["g2rPageSize"]]
    if ".project('id','label','partition'," in script:
        assert len(selected) == 1
        return selected[0]
    raise ValueError("Unrecognised bounded adapter template")

requests = 0
compressed_connections = 0
rejected_templates = 0
async def connection(socket):
    global requests, compressed_connections, rejected_templates
    if socket.protocol.extensions:
        compressed_connections += 1
    async for frame in socket:
        if isinstance(frame, bytes):
            frame = frame[frame[0] + 1:].decode("utf8")
        request = decode(json.loads(frame))
        args = request["args"]
        requests += 1
        try:
            result = evaluate(args["gremlin"], args.get("bindings", {}))
            response = {"requestId": request["requestId"], "status": {"code": 200, "message": "",
                        "attributes": {"x-ms-total-request-charge": 1.0}}, "result": {"data": [encode(result)], "meta": {}}}
        except Exception as error:
            rejected_templates += 1
            response = {"requestId": request["requestId"], "status": {"code": 597, "message": str(error),
                        "attributes": {"x-ms-total-request-charge": 1.0, "x-ms-status-code": 400}},
                        "result": {"data": [], "meta": {}}}
            print(json.dumps({"fixtureRejectedTemplate": True, "reason": str(error)}), flush=True)
        await socket.send(json.dumps(response).encode("utf8"))
        (root / "source-summary.json").write_text(json.dumps({
            "requests": requests, "requestCharge": requests, "compressedConnections": compressed_connections,
            "rejectedTemplates": rejected_templates, "vertices": len(vertices), "edges": len(edges)}))

async def main():
    async with serve(connection, "127.0.0.1", 0, ssl=context) as server:
        port = server.sockets[0].getsockname()[1]
        (root / "source-ready.json").write_text(json.dumps({"port": port}))
        await asyncio.Future()

asyncio.run(main())
