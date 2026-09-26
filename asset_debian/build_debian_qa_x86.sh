#!/usr/bin/env bash
set -euo pipefail

# Build the emulator-only x86_64 Harness carrier. This never writes the production ARM64 asset
# pack. The guest and native carrier are both amd64/x86_64 so the API 36 x86_64 AVD exercises the
# same ABI that it ships in generated/harness-qa.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSET_DIR="$ROOT_DIR/generated/harness-qa/assets/harness-qa/debian"
OCI_REF="docker.io/library/debian@sha256:6788062a1b42ac281f053ac876170b79a3eaed5d61383b8ed7eaca6c6965f3b1"
OCI_DIGEST="sha256:6788062a1b42ac281f053ac876170b79a3eaed5d61383b8ed7eaca6c6965f3b1"
OCI_CREATED="2026-08-24"
SNAPSHOT="20260824T000000Z"
IMAGE_ID="debian-trixie-amd64-20260824"

command -v podman >/dev/null || { echo "podman is required" >&2; exit 1; }
command -v sha256sum >/dev/null || { echo "sha256sum is required" >&2; exit 1; }
command -v install >/dev/null || { echo "install is required" >&2; exit 1; }

mkdir -p "$ASSET_DIR/licenses"
build_dir="$(mktemp -d "${TMPDIR:-/tmp}/adt-debian-qa-x86.XXXXXX")"
trap 'rm -rf "$build_dir"' EXIT

podman run --rm -i --arch amd64 \
  --network=host \
  -v "$build_dir:/out:Z" \
  "$OCI_REF" /bin/bash -s <<'GUEST'
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive
export LC_ALL=C
export TZ=UTC

cat > /etc/apt/sources.list.d/adt-snapshot.list <<'EOF_SOURCES'
deb [check-valid-until=no] http://snapshot.debian.org/archive/debian/20260824T000000Z trixie main
EOF_SOURCES
cat > /etc/apt/apt.conf.d/99adt-snapshot <<'EOF_APT'
Acquire::Check-Valid-Until "false";
Acquire::Retries "3";
EOF_APT
rm -f /etc/apt/sources.list /etc/apt/sources.list.d/debian.sources

apt-get update
apt-get install --no-install-recommends --yes \
  bash coreutils findutils grep sed gawk tar gzip xz-utils \
  apt ca-certificates curl git python3 python3-pip python3-venv

apt-get clean
rm -rf /var/lib/apt/lists/* /var/cache/apt/*
find /var/log -type f -delete
find /var/lib/dpkg -type f \( -name '*.log' -o -name '*.old' \) -delete
mkdir -p /proc /sys /dev /run /tmp

dpkg-query -W -f='${Package}\t${Version}\t${Architecture}\n' | sort > /out/rootfs.manifest
cp /etc/os-release /out/rootfs.os-release
cat /etc/apt/sources.list.d/adt-snapshot.list > /out/rootfs.sources.list

mkdir -p /out/licenses
if [ -f /usr/share/doc/debian/copyright ]; then
  cp /usr/share/doc/debian/copyright /out/licenses/debian-copyright.txt
else
  printf '%s\n' 'Debian copyright notices are distributed by the Debian packages in this rootfs.' \
    > /out/licenses/debian-copyright.txt
fi
dpkg-query -W -f='${Package}\t${Version}\t/usr/share/doc/${Package}/copyright\n' \
  | sort > /out/licenses/package-license-index.tsv

tar --create --xz --file=/out/rootfs.tar.xz \
  --directory=/ \
  --numeric-owner --sort=name --mtime='2026-08-24 00:00:00Z' --clamp-mtime \
  --exclude='./dev/*' --exclude='./proc/*' --exclude='./sys/*' \
  --exclude='./run/*' --exclude='./tmp/*' --exclude='./var/cache/apt/*' \
  --exclude='./var/lib/apt/lists/*' --exclude='./var/log/*' \
  .
GUEST

test -s "$build_dir/rootfs.tar.xz"
test -s "$build_dir/rootfs.manifest"
test -s "$build_dir/rootfs.os-release"

rootfs_sha256="$(sha256sum "$build_dir/rootfs.tar.xz" | awk '{print $1}')"
install -m 0644 "$build_dir/rootfs.tar.xz" "$ASSET_DIR/rootfs.tar.xz"
install -m 0644 "$build_dir/rootfs.manifest" "$ASSET_DIR/rootfs.manifest"
install -m 0644 "$build_dir/rootfs.os-release" "$ASSET_DIR/rootfs.os-release"
install -m 0644 "$build_dir/rootfs.sources.list" "$ASSET_DIR/rootfs.sources.list"
install -m 0644 "$build_dir/licenses/debian-copyright.txt" \
  "$ASSET_DIR/licenses/debian-copyright.txt"
install -m 0644 "$build_dir/licenses/package-license-index.tsv" \
  "$ASSET_DIR/licenses/package-license-index.tsv"
printf '%s  rootfs.tar.xz\n' "$rootfs_sha256" > "$ASSET_DIR/rootfs.tar.xz.sha256"

cat > "$ASSET_DIR/provenance.json" <<EOF_PROVENANCE
{
  "imageId": "$IMAGE_ID",
  "ociReference": "$OCI_REF",
  "ociDigest": "$OCI_DIGEST",
  "ociCreated": "$OCI_CREATED",
  "architecture": "amd64",
  "suite": "trixie",
  "snapshot": "$SNAPSHOT",
  "buildEpoch": "2026-08-24T00:00:00Z",
  "rootfsSha256": "$rootfs_sha256",
  "builder": "asset_debian/build_debian_qa_x86.sh",
  "packageInventory": "rootfs.manifest",
  "licenseIndex": "licenses/package-license-index.tsv",
  "qaOnly": true
}
EOF_PROVENANCE

cat > "$ASSET_DIR/manifest.json" <<EOF_MANIFEST
{
  "imageId": "$IMAGE_ID",
  "distribution": "Debian",
  "suite": "trixie",
  "architecture": "amd64",
  "source": "https://hub.docker.com/_/debian",
  "ociReference": "$OCI_REF",
  "ociDigest": "$OCI_DIGEST",
  "ociCreated": "$OCI_CREATED",
  "snapshot": "$SNAPSHOT",
  "rootfsAsset": "debian/rootfs.tar.xz",
  "rootfsSha256": "$rootfs_sha256",
  "rootfsKind": "debian-oci-plus-snapshot-developer-packages",
  "packages": [
    "bash", "coreutils", "findutils", "grep", "sed", "gawk", "tar", "gzip", "xz-utils",
    "apt", "ca-certificates", "curl", "git", "python3", "python3-pip", "python3-venv"
  ],
  "packageInventory": "rootfs.manifest",
  "licenses": "licenses/",
  "qaOnly": true,
  "notes": "Pinned amd64 Debian Trixie QA rootfs for the x86_64 emulator carrier."
}
EOF_MANIFEST

echo "Prepared QA Debian Trixie amd64 rootfs: $ASSET_DIR/rootfs.tar.xz"
echo "SHA-256: $rootfs_sha256"
