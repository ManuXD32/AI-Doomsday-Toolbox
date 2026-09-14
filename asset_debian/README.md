# Debian install-time asset pack

This pack is the signed, install-time bootstrap image for the Agent's Local Debian
environment. It is deliberately separate from the writable per-environment rootfs copies.

The pinned source is the official Debian arm64 Trixie OCI image
`docker.io/library/debian@sha256:7215f78f35ffe58fe13f244fac9c4f21326d55187271fbb3e1a8aa5cc7e387ab`,
created at the fixed epoch `2026-08-24`. The build resolves developer packages from Debian
snapshot `20260824T000000Z`, exports the deterministic `rootfs.tar.xz`, and records its digest in
`src/main/assets/debian/rootfs.tar.xz.sha256` and `manifest.json`. `rootfs.manifest`,
`rootfs.os-release`, `rootfs.sources.list`, `provenance.json`, and `licenses/` are shipped as
auditable inventory/provenance inputs.

The base includes shell/core utilities, apt, certificates, curl, Git, Python 3, pip, and venv;
Node and native toolchains remain deliberate user `apt` installs inside a writable guest copy.

The application never downloads an executable package into writable storage. The PRoot
executable, loader, and process broker are packaged in the APK's arm64 native library
directory. Guest users may deliberately run `apt` inside a Debian environment; those
packages belong to the guest rootfs and are governed by the Agent's command approval policy.

The Debian rootfs source is distributed under its upstream Debian terms. PRoot and its loader are
GPL-2.0-or-later projects; the pinned native package hashes, broker source, and notices are in
`native-provenance.json`, `native-sha256sums.txt`, and `licenses/proot-native-notice.txt` before
shipping the corresponding native artifacts.
