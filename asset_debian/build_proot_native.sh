#!/usr/bin/env bash
set -euo pipefail

# Rebuild the native pieces that are shipped in app/src/main/jniLibs or the explicit QA carrier.
# The default is ARM64; setting ADT_HARNESS_QA_X86=true writes only generated/harness-qa. The ARM64
# PRoot package and x86_64 QA source archive are pinned by URL and digest; their Termux RUNPATH is
# not trusted. Deterministic, size-preserving ELF
# replacements change the dependency name `libtalloc.so.2` -> `libtalloc_2.so` and rewrite the
# Termux RUNPATH to `$ORIGIN`, so Android resolves adjacent signed APK libraries even when it
# ignores an app-supplied LD_LIBRARY_PATH. PROOT_LOADER selects the adjacent packaged loader.
# The process broker is built from the checked-in C source with the Android NDK.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE="$ROOT_DIR/app/src/main/cpp/proot_broker/proot_broker.c"
NDK_VERSION="29.0.14206865"
NDK_ROOT="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-/home/manu/Android/Sdk/ndk/$NDK_VERSION}}"

if [[ "${ADT_HARNESS_QA_X86:-false}" == "true" ]]; then
  OUT_REL="generated/harness-qa/jniLibs/x86_64"
  OUT_DIR="$ROOT_DIR/generated/harness-qa/jniLibs/x86_64"
  LICENSE_DIR="$ROOT_DIR/generated/harness-qa/licenses"
  PROVENANCE_DIR="$ROOT_DIR/generated/harness-qa"
  ABI_LABEL="x86_64"
  FILE_PATTERN="x86-64"
  CLANG="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android26-clang"
  PROOT_URL="https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.92_x86_64.deb"
  PROOT_SHA256="70236632826c30ec0245082b633bbc7ef1e9fa5531bd51bd4f20231bfcdc999b"
  PROOT_SOURCE_URL="https://github.com/termux/proot/archive/7266fb3e8516535682f5a9c8f3a7e70f6506eddb.zip"
  PROOT_SOURCE_SHA256="f42fd559272a61fcaa48dbd153f16d3c58090c432c959c8919b4f990ebc91550"
  PROOT_SOURCE_COMMIT="7266fb3e8516535682f5a9c8f3a7e70f6506eddb"
  PROOT_PATCH="$ROOT_DIR/asset_debian/patches/proot-renameat-android.patch"
  PROOT_PATCH_REL="asset_debian/patches/proot-renameat-android.patch"
  PROOT_SOURCE_BUILD=true
  SHMEM_URL="https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_x86_64.deb"
  SHMEM_SHA256="ffa9e4c87467b158b148d0ff92dda796aa038276c2075af3269cdcdb06f25797"
  TALLOC_URL="https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.4.3_x86_64.deb"
  TALLOC_SHA256="7ca2eaae2e53b28228a01301bc410b62845403d6317c25b8e0a7f40681de0628"
  COMPILER="x86_64-linux-android26-clang"
else
  OUT_REL="app/src/main/jniLibs/arm64-v8a"
  OUT_DIR="$ROOT_DIR/app/src/main/jniLibs/arm64-v8a"
  LICENSE_DIR="$ROOT_DIR/asset_debian/licenses"
  PROVENANCE_DIR="$ROOT_DIR/asset_debian"
  ABI_LABEL="arm64-v8a"
  FILE_PATTERN="ARM aarch64"
  CLANG="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"
  PROOT_URL="https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.92_aarch64.deb"
  PROOT_SHA256="1f1c983509701f6826f568482c70673ee453a9ba38c9f5fa445a472d6b7524e9"
  PROOT_SOURCE_URL=""
  PROOT_SOURCE_SHA256=""
  PROOT_SOURCE_COMMIT=""
  PROOT_PATCH=""
  PROOT_PATCH_REL=""
  PROOT_SOURCE_BUILD=false
  SHMEM_URL="https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb"
  SHMEM_SHA256="0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6"
  TALLOC_URL="https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb"
  TALLOC_SHA256="ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da"
  COMPILER="aarch64-linux-android26-clang"
fi

for tool in curl sha256sum dpkg-deb file readelf perl install; do
  command -v "$tool" >/dev/null || { echo "$tool is required" >&2; exit 1; }
done
test -x "$CLANG" || { echo "Android NDK clang is unavailable: $CLANG" >&2; exit 1; }
if [[ "$PROOT_SOURCE_BUILD" == true ]]; then
  for tool in unzip make patch; do
    command -v "$tool" >/dev/null || { echo "$tool is required for the x86_64 QA source build" >&2; exit 1; }
  done
  LLVM_STRIP="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
  test -x "$LLVM_STRIP" || { echo "Android NDK llvm-strip is unavailable: $LLVM_STRIP" >&2; exit 1; }
  test -s "$PROOT_PATCH" || { echo "Pinned PRoot compatibility patch is missing: $PROOT_PATCH" >&2; exit 1; }
fi
test -s "$SOURCE" || { echo "Broker source is missing: $SOURCE" >&2; exit 1; }
grep -Fq 'PR_SET_CHILD_SUBREAPER' "$SOURCE" || {
  echo "Broker must enable child subreaper ownership" >&2
  exit 1
}
grep -Fq 'g_owner_start_ticks' "$SOURCE" || {
  echo "Broker must bind supervision to the app process start identity" >&2
  exit 1
}
grep -Fq 'OWNER_MISSES_BEFORE_FORCE' "$SOURCE" || {
  echo "Broker must tolerate bounded /proc owner-read races" >&2
  exit 1
}
grep -Fq -- '--child-pid-file' "$SOURCE" || {
  echo "Broker must expose its PRoot child identity" >&2
  exit 1
}
grep -Fq -- '--process-ledger-file' "$SOURCE" || {
  echo "Broker must persist bounded descendant identity records" >&2
  exit 1
}
grep -Fq -- '--no-force-on-timeout' "$SOURCE" || {
  echo "Broker must support Harness graceful-stop ownership of the deadline" >&2
  exit 1
}
grep -Fq -- '--term-child-only' "$SOURCE" || {
  echo "Broker must support Harness child-only graceful signaling" >&2
  exit 1
}
mkdir -p "$OUT_DIR"
mkdir -p "$LICENSE_DIR"

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/adt-proot-native.XXXXXX")"
trap 'rm -rf "$work_dir"' EXIT

download_and_verify() {
  local url="$1" expected="$2" destination="$3"
  curl --fail --location --retry 3 --output "$destination" "$url"
  printf '%s  %s\n' "$expected" "$destination" | sha256sum --check --strict
}

download_and_verify "$PROOT_URL" "$PROOT_SHA256" "$work_dir/proot.deb"
download_and_verify "$SHMEM_URL" "$SHMEM_SHA256" "$work_dir/libandroid-shmem.deb"
download_and_verify "$TALLOC_URL" "$TALLOC_SHA256" "$work_dir/libtalloc.deb"
mkdir -p "$work_dir/proot" "$work_dir/shmem" "$work_dir/talloc"
dpkg-deb -x "$work_dir/proot.deb" "$work_dir/proot"
dpkg-deb -x "$work_dir/libandroid-shmem.deb" "$work_dir/shmem"
dpkg-deb -x "$work_dir/libtalloc.deb" "$work_dir/talloc"

proot="$work_dir/proot/data/data/com.termux/files/usr/bin/proot"
loader="$work_dir/proot/data/data/com.termux/files/usr/libexec/proot/loader"
shmem="$work_dir/shmem/data/data/com.termux/files/usr/lib/libandroid-shmem.so"
talloc="$work_dir/talloc/data/data/com.termux/files/usr/lib/libtalloc.so.2.4.3"

if [[ "$PROOT_SOURCE_BUILD" == true ]]; then
  download_and_verify "$PROOT_SOURCE_URL" "$PROOT_SOURCE_SHA256" "$work_dir/proot-source.zip"
  mkdir -p "$work_dir/proot-source"
  unzip -q "$work_dir/proot-source.zip" -d "$work_dir/proot-source"
  proot_source="$(find "$work_dir/proot-source" -mindepth 1 -maxdepth 1 -type d -print -quit)"
  test -n "$proot_source" || { echo "Pinned PRoot source archive has no root directory" >&2; exit 1; }
  patch --dry-run --forward --fuzz=0 -d "$proot_source" -p1 < "$PROOT_PATCH"
  patch --forward --fuzz=0 -d "$proot_source" -p1 < "$PROOT_PATCH"
  (
    cd "$proot_source/src"
    CC="$CLANG" \
    CPPFLAGS="-I$work_dir/talloc/data/data/com.termux/files/usr/include -I$work_dir/shmem/data/data/com.termux/files/usr/include" \
    CFLAGS="-Wno-error=implicit-function-declaration -Wno-deprecated-declarations" \
    LDFLAGS="-L$work_dir/talloc/data/data/com.termux/files/usr/lib -L$work_dir/shmem/data/data/com.termux/files/usr/lib -Wl,-rpath,/data/data/com.termux/files/usr/lib" \
    PROOT_WITH_LIBANDROID_SHMEM=true \
    PROOT_UNBUNDLE_LOADER="/data/data/com.termux/files/usr/libexec/proot" \
    make -j2 V=0 proot loader/loader
  )
  proot="$proot_source/src/proot"
  loader="$proot_source/src/loader/loader"
  "$LLVM_STRIP" --strip-all "$proot" "$loader"
fi
for artifact in "$proot" "$loader" "$shmem" "$talloc"; do test -s "$artifact"; done

# Keep the dynamic dependency as a normal .so filename so Android's native-library packaging
# cannot silently drop `*.so.2`; the name is the same width as the original ELF string.
grep -aFq 'libtalloc.so.2' "$proot" || { echo "missing proot dependency" >&2; exit 1; }
grep -aFq 'libtalloc.so.2' "$talloc" || { echo "missing talloc soname" >&2; exit 1; }
perl -0777 -i -pe 's/libtalloc\.so\.2\0/libtalloc_2.so\0/g' "$proot"
perl -0777 -i -pe 's/libtalloc\.so\.2\0/libtalloc_2.so\0/g' "$talloc"

# Preserve every ELF offset while replacing the 35-byte Termux library directory plus its NUL
# with the seven-byte `$ORIGIN` token and 29 NUL bytes. A shorter in-place substitution would
# shift section offsets and corrupt the executable.
for relocatable in "$proot" "$shmem" "$talloc"; do
  grep -aFq '/data/data/com.termux/files/usr/lib' "$relocatable" || {
    echo "missing Termux RUNPATH in $relocatable" >&2
    exit 1
  }
  perl -0777 -i -pe 's{\Q/data/data/com.termux/files/usr/lib\E\0}{"\$ORIGIN" . ("\0" x 29)}ge' "$relocatable"
done

install -m 0755 "$proot" "$OUT_DIR/libproot.so"
install -m 0755 "$loader" "$OUT_DIR/libproot_loader.so"
install -m 0644 "$shmem" "$OUT_DIR/libandroid-shmem.so"
install -m 0644 "$talloc" "$OUT_DIR/libtalloc_2.so"

"$CLANG" -O2 -fPIE -fstack-protector-strong -D_FORTIFY_SOURCE=2 \
  -Werror -Wall -Wextra -Wconversion -Wshadow -Wformat=2 \
  -Wl,--build-id=none -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
  -fPIE -pie "$SOURCE" -o "$OUT_DIR/libproot_broker.so"
chmod 0755 "$OUT_DIR/libproot_broker.so"

for artifact in "$OUT_DIR/libproot.so" "$OUT_DIR/libproot_loader.so" \
  "$OUT_DIR/libproot_broker.so" "$OUT_DIR/libandroid-shmem.so" "$OUT_DIR/libtalloc_2.so"; do
  file "$artifact" | grep -q "$FILE_PATTERN"
done
readelf -d "$OUT_DIR/libproot.so" | grep -Fq 'Shared library: [libtalloc_2.so]'
readelf -d "$OUT_DIR/libproot.so" | grep -Fq 'Shared library: [libandroid-shmem.so]'
readelf -d "$OUT_DIR/libproot.so" | grep -Fq 'Library runpath: [$ORIGIN]'
readelf -d "$OUT_DIR/libandroid-shmem.so" | grep -Fq 'Library runpath: [$ORIGIN]'
readelf -d "$OUT_DIR/libtalloc_2.so" | grep -Eq 'SONAME.*libtalloc_2.so'
readelf -d "$OUT_DIR/libtalloc_2.so" | grep -Fq 'Library runpath: [$ORIGIN]'
readelf -lW "$OUT_DIR/libproot_loader.so" | grep -Fq 'There is no dynamic section' || true

(cd "$ROOT_DIR" && sha256sum \
  "$OUT_REL/libproot.so" \
  "$OUT_REL/libproot_loader.so" \
  "$OUT_REL/libproot_broker.so" \
  "$OUT_REL/libandroid-shmem.so" \
  "$OUT_REL/libtalloc_2.so") > "$PROVENANCE_DIR/native-sha256sums.txt"
cat > "$PROVENANCE_DIR/native-provenance.json" <<EOF_PROVENANCE
{
  "architecture": "$ABI_LABEL",
  "qaOnly": ${ADT_HARNESS_QA_X86:-false},
  "proot": {
    "source": "$PROOT_URL",
    "sha256": "$PROOT_SHA256",
    "sourceCommit": "${PROOT_SOURCE_COMMIT:-}",
    "sourceArchive": "${PROOT_SOURCE_URL:-}",
    "sourceArchiveSha256": "${PROOT_SOURCE_SHA256:-}",
    "patch": "${PROOT_PATCH_REL:-}",
    "patchSha256": "$(if [[ -n "${PROOT_PATCH:-}" ]]; then sha256sum "$PROOT_PATCH" | cut -d' ' -f1; fi)",
    "version": "5.1.107.92",
    "license": "GPL-2.0-or-later",
    "loaderOverride": "PROOT_LOADER=libproot_loader.so",
    "runtimeLibraryPath": "\$ORIGIN"
  },
  "dependencies": {
    "libandroid-shmem.so": "$SHMEM_SHA256",
    "libtalloc.so.2": "$TALLOC_SHA256",
    "patchedSoname": "libtalloc_2.so"
  },
  "broker": {
    "source": "app/src/main/cpp/proot_broker/proot_broker.c",
    "compiler": "$COMPILER",
    "ndk": "$NDK_VERSION",
    "sha256": "$(sha256sum "$OUT_DIR/libproot_broker.so" | cut -d' ' -f1)",
    "elfLoadAlignment": 16384,
    "ptyBehavior": "Accepts an inherited controlling PTY and transfers its foreground process group to the PRoot child.",
    "subreaper": true,
    "forceSignal": "SIGINT",
    "ownerMonitor": "Kernel parent PID relation, with optional /proc start-time revalidation; three consecutive 100ms ownership misses force cleanup",
    "parentDeathSignal": "None for the app owner; SIGTERM remains on the broker-to-PRoot child edge",
    "gracefulSignal": "Kotlin targets the verified Node PID, then broker sends TERM to PRoot child only",
    "noForceOnTimeout": true,
    "termChildOnly": true,
    "childPidFile": true,
    "processLedgerFile": true,
    "descendantCleanup": "Subreaper-owned descendants receive TERM/KILL without same-UID name sweeps.",
    "license": "GPL-2.0-or-later"
  }
}
EOF_PROVENANCE
cat > "$LICENSE_DIR/proot-native-notice.txt" <<EOF_NOTICE
The production arm64 PRoot executable and loader are from the pinned Termux PRoot package.
The x86_64 QA carrier applies the checked-in Android rename compatibility patch to the
pinned upstream source archive; it is an emulator-only diagnostic carrier.
They are GPL-2.0-or-later. Source and license information:
  https://github.com/termux/proot
  https://www.gnu.org/licenses/old-licenses/gpl-2.0.html

The Agent process broker is original project glue, distributed under GPL-2.0-or-later in
app/src/main/cpp/proot_broker/proot_broker.c. It is not a security boundary: PRoot uses ptrace
path translation and does not provide Linux namespaces. The app still clamps mounts and owns
the broker process group and resource limits.
EOF_NOTICE

echo "Prepared $ABI_LABEL PRoot, loader, broker, and dependencies in $OUT_DIR"
