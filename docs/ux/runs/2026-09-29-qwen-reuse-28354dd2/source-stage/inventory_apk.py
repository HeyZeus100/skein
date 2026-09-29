"""Read-only APK inventory; all entry bytes are read, so ZIP CRC checks run too."""
import hashlib
import json
import struct
import sys
import zipfile
from pathlib import Path


def file_identity(path):
    path = Path(path)
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    return {"path": str(path.resolve()), "size": path.stat().st_size, "sha256": digest}


def inventory(path):
    result = file_identity(path)
    entries = {}
    with zipfile.ZipFile(path) as archive:
        names = [entry.filename for entry in archive.infolist()]
        if len(names) != len(set(names)):
            raise ValueError("Duplicate ZIP entries")
        for entry in archive.infolist():
            if entry.is_dir():
                continue
            digest = hashlib.sha256()
            count = 0
            head = b""
            with archive.open(entry) as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    if not head:
                        head = chunk[:64]
                    digest.update(chunk)
                    count += len(chunk)
            if count != entry.file_size:
                raise ValueError("ZIP size mismatch")
            record = {
                "size": count,
                "sha256": digest.hexdigest(),
                "crc32": format(entry.CRC, "08x"),
                "compressed_size": entry.compress_size,
                "compression": entry.compress_type,
                "zip_time": list(entry.date_time),
            }
            if entry.filename.startswith("lib/") and entry.filename.endswith(".so"):
                if not head.startswith(b"\x7fELF"):
                    raise ValueError("Native entry is not ELF")
                record["elf"] = {
                    "class_bits": {1: 32, 2: 64}.get(head[4]),
                    "endianness": {1: "little", 2: "big"}.get(head[5]),
                    "machine": struct.unpack("<H" if head[5] == 1 else ">H", head[18:20])[0],
                }
            entries[entry.filename] = record
    result["entries"] = entries
    result["native_libraries"] = {
        name: {"size": value["size"], "sha256": value["sha256"]}
        for name, value in entries.items()
        if name.startswith("lib/") and name.endswith(".so")
    }
    result["zip_crc_all_entries_read"] = True
    return result


if __name__ == "__main__":
    output = Path(sys.argv[2])
    with output.open("x") as stream:
        json.dump(inventory(sys.argv[1]), stream, indent=2)
        stream.write("\n")
