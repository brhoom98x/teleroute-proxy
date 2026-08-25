#!/usr/bin/env bash
#
# TeleRoute proxy installer.
#
#   sudo bash install.sh
#
# Fetches the latest release from GitHub, installs it as a systemd service, and generates a
# SOCKS5 password. Safe to re-run: an existing config is never overwritten, so upgrading does not
# invalidate the credentials already sitting in your Telegram client.
#
# Environment:
#   REPO           owner/name            (default brhoom98x/teleroute-proxy)
#   VERSION        tag to install        (default: latest release)
#   GITHUB_TOKEN   required for a private repo - see README
#   BIND           address to listen on  (default 0.0.0.0)
#   PORT           default 19808

set -euo pipefail

REPO="${REPO:-brhoom98x/teleroute-proxy}"
VERSION="${VERSION:-}"
BIND="${BIND:-0.0.0.0}"
PORT="${PORT:-19808}"
GITHUB_TOKEN="${GITHUB_TOKEN:-}"

JAR_DIR=/opt/teleroute
CONF_DIR=/etc/teleroute
CONF="$CONF_DIR/teleroute.conf"
UNIT=/etc/systemd/system/teleroute-proxy.service
SERVICE_USER=teleroute

log()  { printf '\033[0;32m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m!!\033[0m %s\n' "$*"; }
die()  { printf '\033[0;31mxx\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "Run as root:  sudo bash install.sh"
command -v systemctl >/dev/null || die "systemd is required; this installer does not cover other init systems."

# ---------------------------------------------------------------- dependencies
install_packages() {
    local pkgs=("$@")
    if   command -v apt-get >/dev/null; then
        DEBIAN_FRONTEND=noninteractive apt-get update -qq
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "${pkgs[@]}"
    elif command -v dnf >/dev/null; then dnf install -y -q "${pkgs[@]}"
    elif command -v yum >/dev/null; then yum install -y -q "${pkgs[@]}"
    elif command -v apk >/dev/null; then apk add --quiet "${pkgs[@]}"
    elif command -v pacman >/dev/null; then pacman -Sy --noconfirm --quiet "${pkgs[@]}"
    else die "No supported package manager found. Install a JRE 17+, curl and jq by hand, then re-run."
    fi
}

need=()
command -v curl >/dev/null || need+=(curl)
command -v jq   >/dev/null || need+=(jq)
if ! command -v java >/dev/null; then
    if   command -v apt-get >/dev/null; then need+=(default-jre-headless)
    elif command -v apk     >/dev/null; then need+=(openjdk17-jre-headless)
    elif command -v pacman  >/dev/null; then need+=(jre-openjdk-headless)
    else need+=(java-17-openjdk-headless)
    fi
fi
if [ ${#need[@]} -gt 0 ]; then
    log "Installing: ${need[*]}"
    install_packages "${need[@]}"
fi

java -version >/dev/null 2>&1 || die "Java still not runnable after install."
JAVA_MAJOR=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')
[ "${JAVA_MAJOR:-0}" -ge 17 ] || die "Java 17 or newer required, found $JAVA_MAJOR."
log "Java $JAVA_MAJOR"

# ---------------------------------------------------------------- fetch release
API="https://api.github.com/repos/$REPO/releases"
if [ -n "$VERSION" ]; then API="$API/tags/$VERSION"; else API="$API/latest"; fi

auth=()
[ -n "$GITHUB_TOKEN" ] && auth=(-H "Authorization: Bearer $GITHUB_TOKEN")

log "Querying $REPO"
meta=$(curl -fsSL "${auth[@]}" -H "Accept: application/vnd.github+json" "$API") || {
    die "Could not read the release. For a private repository export GITHUB_TOKEN with a token that has 'Contents: read' on $REPO."
}

TAG=$(echo "$meta" | jq -r '.tag_name')
JAR_NAME=$(echo "$meta" | jq -r '.assets[].name | select(endswith(".jar"))' | head -1)
[ -n "$JAR_NAME" ] && [ "$JAR_NAME" != "null" ] || die "No .jar asset in release $TAG."
log "Release $TAG -> $JAR_NAME"

fetch_asset() {
    local name="$1" dest="$2"
    local id
    id=$(echo "$meta" | jq -r --arg n "$name" '.assets[] | select(.name==$n) | .id')
    [ -n "$id" ] && [ "$id" != "null" ] || return 1
    # The API asset endpoint works for private and public repos alike, unlike browser_download_url.
    curl -fsSL "${auth[@]}" -H "Accept: application/octet-stream" \
        "https://api.github.com/repos/$REPO/releases/assets/$id" -o "$dest"
}

tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
fetch_asset "$JAR_NAME" "$tmp/proxy.jar" || die "Download failed for $JAR_NAME"

if fetch_asset "SHA256SUMS" "$tmp/SHA256SUMS" 2>/dev/null; then
    want=$(grep " $JAR_NAME\$" "$tmp/SHA256SUMS" | awk '{print $1}')
    got=$(sha256sum "$tmp/proxy.jar" | awk '{print $1}')
    [ "$want" = "$got" ] || die "Checksum mismatch. Expected $want, got $got. Do not run this jar."
    log "Checksum verified"
else
    warn "No SHA256SUMS in the release; skipping integrity check."
fi

# ---------------------------------------------------------------- install
id -u "$SERVICE_USER" >/dev/null 2>&1 || {
    log "Creating user $SERVICE_USER"
    useradd --system --user-group --no-create-home --shell /usr/sbin/nologin "$SERVICE_USER" 2>/dev/null ||
    adduser --system --group --no-create-home "$SERVICE_USER"
}

install -d -m 755 "$JAR_DIR"
install -d -m 750 -o "$SERVICE_USER" -g "$SERVICE_USER" "$CONF_DIR"
install -m 644 -o "$SERVICE_USER" -g "$SERVICE_USER" "$tmp/proxy.jar" "$JAR_DIR/teleroute-proxy.jar"
log "Installed $JAR_DIR/teleroute-proxy.jar"

NEW_PASSWORD=""
if [ -f "$CONF" ]; then
    log "Keeping the existing config at $CONF (password unchanged)"
else
    # Alphanumeric on purpose. base64 emits '+', '/' and '=', and a '/' in the password breaks
    # any tool that takes a proxy as socks5://user:pass@host:port -- curl rejects it outright,
    # and it has to be percent-encoded in a tg://socks deep link. 32 alphanumerics is ~190 bits,
    # so nothing is lost by dropping the awkward characters.
    # Bounded read, not "tr < /dev/urandom | head -c 32": head closing the pipe sends tr SIGPIPE,
    # and under "set -o pipefail" that aborts the installer. 512 bytes yields ~124 usable
    # characters on average, far more than the 32 taken.
    NEW_PASSWORD=$(head -c 512 /dev/urandom | LC_ALL=C tr -dc "A-Za-z0-9" | cut -c1-32)
    [ ${#NEW_PASSWORD} -eq 32 ] || die "Password generation produced ${#NEW_PASSWORD} characters, expected 32."
    umask 077
    cat > "$CONF" <<EOF
bind=$BIND
port=$PORT
username=teleroute
password=$NEW_PASSWORD
scanIntervalSeconds=300
allowNonTelegram=false
EOF
    chown "$SERVICE_USER:$SERVICE_USER" "$CONF"
    chmod 600 "$CONF"
    log "Wrote $CONF"
fi

if fetch_asset "teleroute-proxy.service" "$tmp/unit" 2>/dev/null; then
    install -m 644 "$tmp/unit" "$UNIT"
else
    cat > "$UNIT" <<'EOF'
[Unit]
Description=TeleRoute SOCKS5 proxy for Telegram
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=teleroute
Group=teleroute
ExecStart=/usr/bin/java -Xmx128m -jar /opt/teleroute/teleroute-proxy.jar /etc/teleroute/teleroute.conf
Restart=on-failure
RestartSec=5
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
RestrictAddressFamilies=AF_INET AF_INET6
RestrictSUIDSGID=true
CapabilityBoundingSet=
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF
fi

# ExecStart hardcodes /usr/bin/java; some distributions only ship it elsewhere.
JAVA_BIN=$(command -v java)
[ "$JAVA_BIN" = "/usr/bin/java" ] || sed -i "s|/usr/bin/java|$JAVA_BIN|" "$UNIT"

systemctl daemon-reload
systemctl enable --now teleroute-proxy >/dev/null 2>&1 || true
systemctl restart teleroute-proxy
sleep 6

if ! systemctl is-active --quiet teleroute-proxy; then
    journalctl -u teleroute-proxy --no-pager -n 20 || true
    die "Service failed to start. Log above."
fi

log "Service running"
journalctl -u teleroute-proxy --no-pager -n 4 --output=cat | sed 's/^/    /'

# ---------------------------------------------------------------- summary
IP=$(hostname -I 2>/dev/null | awk '{print $1}')
echo
log "Point Telegram at:  server ${IP:-<this host>}   port $PORT   username teleroute"
if [ -n "$NEW_PASSWORD" ]; then
    echo
    warn "Password (shown once, it is not recoverable from here later):"
    echo "    $NEW_PASSWORD"
    echo
    echo "    Read it again with:  sudo grep ^password= $CONF"
fi
echo
if [ "$BIND" = "0.0.0.0" ]; then
    warn "Listening on every interface. Authentication is mandatory and only Telegram destinations"
    warn "are relayed, but the safest setup is still to reach this over WireGuard or Tailscale and"
    warn "firewall $PORT off the public internet entirely."
fi
