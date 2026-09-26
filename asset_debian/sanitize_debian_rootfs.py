#!/usr/bin/env python3
"""Remove build-host network identity from a Debian bootstrap archive."""

import argparse
import copy
import io
import os
from pathlib import Path
import tarfile
import tempfile


NETWORK_DEFAULTS = {
    "etc/hostname": b"adt-debian\n",
    "etc/hosts": b"127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n",
    "etc/resolv.conf": b"# ADT binds the guest resolver at runtime.\n",
}
MACHINE_IDS = {"etc/machine-id", "var/lib/dbus/machine-id"}


def entry_name(member):
    name = member.name
    while name.startswith("./"):
        name = name[2:]
    return name


def check_archive(path):
    seen = set()
    with tarfile.open(path, "r|xz") as archive:
        for member in archive:
            name = entry_name(member)
            if name in NETWORK_DEFAULTS or name in MACHINE_IDS:
                if name in seen or not member.isfile():
                    raise ValueError(f"Ambiguous bootstrap identity entry: {name}")
                seen.add(name)
                with archive.extractfile(member) as stream:
                    data = stream.read(4096)
                expected = NETWORK_DEFAULTS.get(name, b"")
                if data != expected or member.size != len(expected):
                    raise ValueError(f"Build-host identity must be sanitized before packaging: {name}")
    if not NETWORK_DEFAULTS.keys() <= seen:
        raise ValueError("Bootstrap is missing canonical guest network files")


def sanitize_archive(path):
    path = Path(path)
    descriptor, temporary = tempfile.mkstemp(prefix=path.name + ".sanitized-", dir=path.parent)
    os.close(descriptor)
    temporary = Path(temporary)
    seen = set()
    try:
        with tarfile.open(path, "r|xz") as source, tarfile.open(temporary, "w:xz", preset=6) as target:
            for original in source:
                member = copy.copy(original)
                member.pax_headers = original.pax_headers.copy()
                name = entry_name(member)
                if name in NETWORK_DEFAULTS or name in MACHINE_IDS:
                    if name in seen or not member.isfile():
                        raise ValueError(f"Ambiguous bootstrap identity entry: {name}")
                    seen.add(name)
                    data = NETWORK_DEFAULTS.get(name, b"")
                    member.size = len(data)
                    member.pax_headers.pop("size", None)
                    target.addfile(member, io.BytesIO(data))
                elif member.isfile():
                    with source.extractfile(original) as stream:
                        target.addfile(member, stream)
                else:
                    target.addfile(member)
        if not NETWORK_DEFAULTS.keys() <= seen:
            raise ValueError("Bootstrap is missing canonical guest network files")
        check_archive(temporary)
        temporary.chmod(path.stat().st_mode & 0o777)
        with temporary.open("rb") as stream:
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        # Only this call's incomplete output is removed; the source stays intact on failure.
        temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    (check_archive if args.check else sanitize_archive)(args.archive)
    print("Verified canonical guest network identity")


if __name__ == "__main__":
    main()
