import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import package_teams_dev_app as packager
import render_teams_dev_manifest as renderer


def png(size):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress((b"\0" + b"\xff\xff\xff\0" * size) * size)) + chunk(b"IEND", b""))


class TeamsPackageTests(unittest.TestCase):
    def fixture(self, folder, color_size=192):
        manifest = renderer.render(ROOT / "docs/poc/teams-rsc/manifest.template.json",
            "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222",
            "https://sandbox.example.test")
        path = folder / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        icons = folder / "icons.zip"
        with zipfile.ZipFile(icons, "w") as archive:
            archive.writestr("color.png", png(color_size))
            archive.writestr("outline.png", png(32))
            archive.writestr("manifest.json", '{"staticTabs":[{"scopes":["personal"]}]}')
        return path, icons, folder / "output.zip"

    def test_packages_only_reviewed_manifest_and_root_level_valid_pngs(self):
        with tempfile.TemporaryDirectory() as temp:
            manifest, icons, output = self.fixture(Path(temp))
            packager.package(manifest, icons, output)
            with zipfile.ZipFile(output) as archive:
                self.assertEqual(set(archive.namelist()), {"manifest.json", "color.png", "outline.png"})
                shipped = json.loads(archive.read("manifest.json"))
                self.assertNotIn("staticTabs", shipped)
                renderer.validate_manifest(shipped)

    def test_wrong_icon_dimensions_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            manifest, icons, output = self.fixture(Path(temp), 128)
            with self.assertRaisesRegex(ValueError, "192x192"):
                packager.package(manifest, icons, output)

    def test_personal_tabs_cannot_bypass_package_contract(self):
        with tempfile.TemporaryDirectory() as temp:
            manifest, icons, output = self.fixture(Path(temp))
            content = json.loads(manifest.read_text())
            content["staticTabs"] = [{"entityId": "conversations", "scopes": ["personal"]}]
            manifest.write_text(json.dumps(content))
            with self.assertRaisesRegex(ValueError, "staticTabs"):
                packager.package(manifest, icons, output)
