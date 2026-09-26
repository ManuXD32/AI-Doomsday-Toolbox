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

Before hashing or packaging an exported image, `sanitize_debian_rootfs.py`
replaces container-inherited host network identity with generic guest files.
`verifyDebianAssetPayloads` checks this policy as well as archive hashes. Android
network resolver data is supplied at guest launch. See
[`docs/debian-rootfs-privacy.md`](../docs/debian-rootfs-privacy.md) for the exact
contract, tests, and compatibility handling for saved environments.

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

The broker binds runtime ownership to the kernel parent PID relationship, with optional
`/proc/<pid>/stat` start-time revalidation when readable. Android may deny the native child
access to its parent app's proc metadata; that must not prevent launch or kill a live owner.
It intentionally does not use `PR_SET_PDEATHSIG` on that edge: Linux can deliver
that signal when a launching pthread retires while the app process remains alive. The broker polls
the recorded process identity with three bounded 100 ms misses before forcing descendant cleanup.
The immediate broker-to-PRoot child edge still uses `PR_SET_PDEATHSIG`, so a real broker death does
not leave the guest process behind. The host regression
`tests/native/proot_broker_parent_liveness_test.sh` covers both cases with readable and denied
proc metadata. PRoot, its loader and shared dependencies still use the pinned Termux packages;
the repair adds no service or additional runtime layer.

The opt-in API 36 x86_64 Harness carrier is separate from the production arm64 payload. Its
`build_proot_native.sh` path compiles pinned PRoot commit
`7266fb3e8516535682f5a9c8f3a7e70f6506eddb` with
`asset_debian/patches/proot-renameat-android.patch`. Android's x86_64 app sandbox can return
`ENOSYS` for guest `rename(2)` when `PROOT_NO_SECCOMP=1`; the patch translates that operation to
the equivalent atomic `renameat(2)` after PRoot path handling. The source archive and patch hashes
are recorded in the generated QA `native-provenance.json`; the production arm64 build continues
to use the pinned Termux package unchanged.
