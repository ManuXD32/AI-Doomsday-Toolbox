import hashlib
import io
from pathlib import Path
import tarfile
import tempfile
import unittest

from sanitize_debian_rootfs import NETWORK_DEFAULTS, check_archive, sanitize_archive


class RootfsIdentityTest(unittest.TestCase):
    def make_archive(self, path, duplicate=False, omit=False):
        with tarfile.open(path, "w:xz") as archive:
            for name in NETWORK_DEFAULTS:
                if omit and name == "etc/hosts":
                    continue
                data = b"private-build-host-fixture\n"
                entry = tarfile.TarInfo("./" + name)
                entry.size, entry.mode, entry.uid, entry.gid, entry.mtime = len(data), 0o644, 0, 0, 123
                archive.addfile(entry, io.BytesIO(data))
                if duplicate and name == "etc/hosts":
                    archive.addfile(entry, io.BytesIO(data))
            entry = tarfile.TarInfo("./usr/bin/fixture")
            entry.size, entry.mode, entry.mtime = 7, 0o755, 321
            archive.addfile(entry, io.BytesIO(b"payload"))
            link = tarfile.TarInfo("./bin/fixture")
            link.type, link.linkname = tarfile.SYMTYPE, "../usr/bin/fixture"
            archive.addfile(link)

    def test_sanitizes_identity_without_changing_package_content_or_metadata(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "rootfs.tar.xz"
            self.make_archive(path)
            with self.assertRaisesRegex(ValueError, "Build-host identity"):
                check_archive(path)
            sanitize_archive(path)
            check_archive(path)
            with tarfile.open(path) as archive:
                for name, data in NETWORK_DEFAULTS.items():
                    entry = archive.getmember("./" + name)
                    self.assertEqual(data, archive.extractfile(entry).read())
                    self.assertEqual((0o644, 0, 0, 123), (entry.mode, entry.uid, entry.gid, entry.mtime))
                entry = archive.getmember("./usr/bin/fixture")
                self.assertEqual(b"payload", archive.extractfile(entry).read())
                self.assertEqual((0o755, 321), (entry.mode, entry.mtime))
                self.assertEqual("../usr/bin/fixture", archive.getmember("./bin/fixture").linkname)

    def test_malformed_input_preserves_original_archive(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "rootfs.tar.xz"
            for kwargs in ({"duplicate": True}, {"omit": True}):
                self.make_archive(path, **kwargs)
                before = hashlib.sha256(path.read_bytes()).digest()
                with self.assertRaises(ValueError):
                    sanitize_archive(path)
                self.assertEqual(before, hashlib.sha256(path.read_bytes()).digest())
                self.assertEqual([path], list(Path(tmp).iterdir()))


if __name__ == "__main__":
    unittest.main()
