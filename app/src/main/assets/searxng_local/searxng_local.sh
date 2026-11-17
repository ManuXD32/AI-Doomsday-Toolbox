#!/bin/sh
# In-app SearXNG installer/launcher. The SAME script is executed by the app inside the Debian
# PRoot environment (via AgentProotCommandExecutor) and by tools/host/searxng_local/host_test.sh (single source: this asset)
# on an x86 host. POSIX sh only (Debian /bin/sh is dash).
#
# Usage: searxng_local.sh install|serve
# Environment:
#   SEARXNG_HOME    install directory (app: /workspace/searxng)         default /workspace/searxng
#   SEARXNG_PORT    loopback port                                         default 18888
#   SEARXNG_SECRET  server.secret_key (app generates a random one)        required for install
#   SEARXNG_COMMIT  pinned upstream commit                                default below
set -eu

PINNED_COMMIT="a7128d5d1d5af2837a68fbeaaee9c3e8391aa9cf"
HOME_DIR="${SEARXNG_HOME:-/workspace/searxng}"
PORT="${SEARXNG_PORT:-18888}"
COMMIT="${SEARXNG_COMMIT:-$PINNED_COMMIT}"
SRC="$HOME_DIR/src"
VENV="$HOME_DIR/venv"
SETTINGS="$HOME_DIR/settings.yml"
LOG_DIR="$HOME_DIR/logs"
MARKER="$HOME_DIR/.installed"

mkdir -p "$LOG_DIR"

write_settings() {
    [ -n "${SEARXNG_SECRET:-}" ] || { echo "SEARXNG_SECRET is required" >&2; exit 2; }
    case "$PORT" in ''|*[!0-9]*) echo "bad port" >&2; exit 2;; esac
    # use_default_settings keeps upstream defaults; only the keys below are overridden and only the
    # lean general-web engine set below is kept (fewer engines = faster, fewer CAPTCHA/timeouts on a phone).
    # bing/yahoo/mwmbl ship disabled upstream and mojeek is 'inactive: true' (dropped by the engine loader);
    # the engines: list below re-enables them. google, qwant, duckduckgo, brave and startpage are left out on
    # purpose: phone results (2026-11-16) showed 403 / access denied / CAPTCHA / timeouts for them.
    cat > "$SETTINGS" <<YML
use_default_settings:
  engines:
    keep_only:
      - bing
      - mojeek
      - wikipedia
      - yahoo
      - mwmbl
general:
  instance_name: "ADT local SearXNG"
  debug: false
  contact_url: "https://github.com/ManuXD32/AI-Doomsday-Toolbox"
search:
  formats:
    - html
    - json
server:
  bind_address: "127.0.0.1"
  port: $PORT
  secret_key: "$SEARXNG_SECRET"
  limiter: false
  public_instance: false
  image_proxy: false
outgoing:
  request_timeout: 10.0
  max_request_timeout: 15.0
engines:
  - name: bing
    disabled: false
  - name: yahoo
    disabled: false
  - name: mojeek
    inactive: false
    disabled: false
  - name: mwmbl
    disabled: false
YML
    chmod 600 "$SETTINGS"
}

do_install() {
    exec >>"$LOG_DIR/install.log" 2>&1
    echo "== SearXNG install $(date -u +%FT%TZ) commit=$COMMIT"
    rm -f "$MARKER"
    if [ ! -d "$SRC/.git" ]; then
        mkdir -p "$SRC"
        git -C "$SRC" init -q
        git -C "$SRC" remote add origin https://github.com/searxng/searxng.git
    fi
    git -C "$SRC" fetch --depth 1 origin "$COMMIT"
    git -C "$SRC" checkout -q --force FETCH_HEAD
    [ -d "$VENV" ] || python3 -m venv "$VENV"
    "$VENV/bin/pip" install --disable-pip-version-check -U pip setuptools wheel pyyaml msgspec typing_extensions pybind11
    (cd "$SRC" && "$VENV/bin/pip" install --disable-pip-version-check --use-pep517 --no-build-isolation -e .)
    write_settings
    echo "$COMMIT" > "$MARKER"
    echo "== install done"
}

do_serve() {
    [ -f "$MARKER" ] || { echo "SearXNG is not installed" >&2; exit 3; }
    exec >>"$LOG_DIR/serve.log" 2>&1
    # Existing installs pick up new settings on start when the app passes the (stable) secret.
    if [ -n "${SEARXNG_SECRET:-}" ]; then write_settings; fi
    echo "== SearXNG serve $(date -u +%FT%TZ) port=$PORT"
    cd "$SRC"
    SEARXNG_SETTINGS_PATH="$SETTINGS" exec "$VENV/bin/python" -m searx.webapp
}

case "${1:-}" in
    install) do_install ;;
    serve) do_serve ;;
    *) echo "usage: $0 install|serve" >&2; exit 64 ;;
esac
