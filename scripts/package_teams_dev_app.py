"""Package the reviewed manifest and existing PNG icons without CLI manifest rewriting."""
from __future__ import annotations
import argparse
import json
import struct
import zipfile
import zlib
from pathlib import Path
from render_teams_dev_manifest import validate_manifest


def package(manifest_path: Path, icons_zip: Path, output: Path) -> None:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    icons = {}
    with zipfile.ZipFile(icons_zip) as source:
        for kind, size in (("color", 192), ("outline", 32)):
            name = manifest["icons"][kind]
            if name != f"{kind}.png":
                raise ValueError("Icons must be root-level color.png and outline.png")
            content = source.read(name)
            if content[:8] != b"\x89PNG\r\n\x1a\n" or len(content) < 33:
                raise ValueError("Icon must be a PNG")
            if struct.unpack(">II", content[16:24]) != (size, size):
                raise ValueError(f"{kind}.png must be {size}x{size}")
            offset = 8
            chunks = []
            while offset < len(content):
                length = struct.unpack(">I", content[offset:offset + 4])[0]
                chunk = content[offset + 4:offset + 8 + length]
                crc = content[offset + 8 + length:offset + 12 + length]
                if len(crc) != 4 or struct.unpack(">I", crc)[0] != zlib.crc32(chunk):
                    raise ValueError("PNG chunk integrity validation failed")
                chunks.append(chunk[:4])
                offset += length + 12
            if not chunks or chunks[0] != b"IHDR" or chunks[-1] != b"IEND" or b"IDAT" not in chunks:
                raise ValueError("PNG structure validation failed")
            icons[name] = content
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("manifest.json", json.dumps(manifest, indent=2) + "\n")
        for name, content in icons.items():
            archive.writestr(name, content)
    with zipfile.ZipFile(output) as archive:
        if set(archive.namelist()) != {"manifest.json", "color.png", "outline.png"} or archive.testzip():
            raise ValueError("Package integrity validation failed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--icons-from", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    package(args.manifest, args.icons_from, args.output)
    print(f"Packaged and verified Teams sandbox app: {args.output}")
