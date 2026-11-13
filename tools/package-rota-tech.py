#!/usr/bin/env python3
"""Package the review build without local credentials, databases or stale jars."""

import hashlib
import json
from pathlib import Path
import zipfile


root = Path(__file__).resolve().parents[1]
jar = root / "target/tracker-server.jar"
web = root / "traccar-web/build"
if not jar.is_file() or not (web / "index.html").is_file():
    raise SystemExit("Run the backend and web builds before packaging")

with zipfile.ZipFile(jar) as archive:
    manifest = archive.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "")
attributes = dict(line.split(": ", 1) for line in manifest.splitlines() if ": " in line)
if attributes.get("Implementation-Version") != "6.17.0":
    raise SystemExit("Expected a Traccar 6.17.0 backend")

files = {"tracker-server.jar": jar, "LICENSE.txt": root / "LICENSE.txt"}
for dependency in attributes["Class-Path"].split():
    path = Path(dependency)
    if path.is_absolute() or ".." in path.parts or path.parts[0] != "lib":
        raise SystemExit(f"Invalid manifest dependency: {dependency}")
    files[dependency] = root / "target" / path
for directory, destination in [(root / "schema", "schema"), (root / "templates", "templates"), (web, "web")]:
    for path in sorted(directory.rglob("*")):
        if path.is_file():
            files[f"{destination}/{path.relative_to(directory).as_posix()}"] = path
files["HOMOLOGACAO.md"] = root / "docs/operations/rota-tech-6.17-homologacao.md"
reports = sorted((root / "docs/operations").glob("*-validation.json"))
if reports:
    files["VALIDATION.json"] = reports[-1]

configuration = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE properties SYSTEM 'http://java.sun.com/dtd/properties.dtd'>
<properties>
    <entry key="database.driver">org.h2.Driver</entry>
    <entry key="database.url">jdbc:h2:./data/review</entry>
    <entry key="database.user">sa</entry>
    <entry key="database.password"></entry>
    <entry key="web.address">127.0.0.1</entry>
    <entry key="web.port">18082</entry>
    <entry key="notificator.types">web</entry>
    <entry key="protocols.enable">gt06</entry>
    <entry key="gt06.address">127.0.0.1</entry>
    <entry key="gt06.port">15023</entry>
</properties>
"""
checksums = {}
destination = root / "target/rota-tech-traccar-6.17.0-review.zip"
with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as archive:
    for name, path in sorted(files.items()):
        data = path.read_bytes()
        archive.writestr(name, data)
        checksums[name] = hashlib.sha256(data).hexdigest()
    archive.writestr("conf/review.xml", configuration)
    archive.writestr("data/", "")
    archive.writestr("logs/", "")
    archive.writestr("SHA256SUMS.json", json.dumps(checksums, indent=2) + "\n")
print(destination)
