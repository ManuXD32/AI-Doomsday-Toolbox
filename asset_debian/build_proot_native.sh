#!/usr/bin/env bash
set -euo pipefail

# Rebuild the arm64 native pieces that are shipped in app/src/main/jniLibs. The PRoot package is
# pinned by URL and digest; its Termux RUNPATH is not trusted. Deterministic, size-preserving ELF
# replacements change the dependency name `libtalloc.so.2` -> `libtalloc_2.so` and rewrite the
# Termux RUNPATH to `$ORIGIN`, so Android resolves adjacent signed APK libraries even when it
# ignores an app-supplied LD_LIBRARY_PATH. PROOT_LOADER selects the adjacent packaged loader.
# The process broker is built from the checked-in C source with the Android NDK.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$ROOT_DIR/app/src/main/jniLibs/arm64-v8a"
SOURCE="$ROOT_DIR/app/src/main/cpp/proot_broker/proot_broker.c"
NDK_VERSION="29.0.14206865"
NDK_ROOT="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-/home/manu/Android/Sdk/ndk/$NDK_VERSION}}"
CLANG="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"

PROOT_URL="https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.92_aarch64.deb"
PROOT_SHA256="1f1c983509701f6826f568482c70673ee453a9ba38c9f5fa445a472d6b7524e9"
SHMEM_URL="https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb"
SHMEM_SHA256="0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6"
TALLOC_URL="https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb"
TALLOC_SHA256="ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da"

for tool in curl sha256sum dpkg-deb file readelf perl install; do
  command -v "$tool" >/dev/null || { echo "$tool is required" >&2; exit 1; }
done
test -x "$CLANG" || { echo "Android NDK clang is unavailable: $CLANG" >&2; exit 1; }
test -s "$SOURCE" || { echo "Broker source is missing: $SOURCE" >&2; exit 1; }
mkdir -p "$OUT_DIR"
mkdir -p "$ROOT_DIR/asset_debian/licenses"

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
  file "$artifact" | grep -q 'ARM aarch64'
done
readelf -d "$OUT_DIR/libproot.so" | grep -Fq 'Shared library: [libtalloc_2.so]'
readelf -d "$OUT_DIR/libproot.so" | grep -Fq 'Shared library: [libandroid-shmem.so]'
readelf -d "$OUT_DIR/libproot.so" | grep -Fq 'Library runpath: [$ORIGIN]'
readelf -d "$OUT_DIR/libandroid-shmem.so" | grep -Fq 'Library runpath: [$ORIGIN]'
readelf -d "$OUT_DIR/libtalloc_2.so" | grep -Eq 'SONAME.*libtalloc_2.so'
readelf -d "$OUT_DIR/libtalloc_2.so" | grep -Fq 'Library runpath: [$ORIGIN]'
readelf -lW "$OUT_DIR/libproot_loader.so" | grep -Fq 'There is no dynamic section' || true

(cd "$ROOT_DIR" && sha256sum \
  app/src/main/jniLibs/arm64-v8a/libproot.so \
  app/src/main/jniLibs/arm64-v8a/libproot_loader.so \
  app/src/main/jniLibs/arm64-v8a/libproot_broker.so \
  app/src/main/jniLibs/arm64-v8a/libandroid-shmem.so \
  app/src/main/jniLibs/arm64-v8a/libtalloc_2.so) > "$ROOT_DIR/asset_debian/native-sha256sums.txt"
cat > "$ROOT_DIR/asset_debian/native-provenance.json" <<EOF_PROVENANCE
{
  "architecture": "arm64-v8a",
  "proot": {
    "source": "$PROOT_URL",
    "sha256": "$PROOT_SHA256",
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
    "compiler": "aarch64-linux-android26-clang",
    "ndk": "$NDK_VERSION",
    "sha256": "$(sha256sum "$OUT_DIR/libproot_broker.so" | cut -d' ' -f1)",
    "elfLoadAlignment": 16384,
    "ptyBehavior": "Accepts an inherited controlling PTY and transfers its foreground process group to the PRoot child.",
    "license": "GPL-2.0-or-later"
  }
}
EOF_PROVENANCE
cat > "$ROOT_DIR/asset_debian/licenses/proot-native-notice.txt" <<'EOF_NOTICE'
The packaged PRoot executable and loader are from the pinned Termux PRoot arm64 package.
They are GPL-2.0-or-later. Source and license information:
  https://github.com/termux/proot
  https://www.gnu.org/licenses/old-licenses/gpl-2.0.html

The Agent process broker is original project glue, distributed under GPL-2.0-or-later in
app/src/main/cpp/proot_broker/proot_broker.c. It is not a security boundary: PRoot uses ptrace
path translation and does not provide Linux namespaces. The app still clamps mounts and owns
the broker process group and resource limits.
EOF_NOTICE

echo "Prepared arm64 PRoot, loader, broker, and dependencies in $OUT_DIR"
