#!/bin/bash
# Outside access through your own domain on Cloudflare (a Cloudflare Tunnel): nothing is opened on the router,
# the Pi itself connects out to Cloudflare.
#   https://home.<domain>  — the DeskDash hub (the PWA: phones, from anywhere)
#   https://ha.<domain>    — Home Assistant
# The domain must already be added to your Cloudflare account. The first run prints a link: open it, sign in to
# Cloudflare and pick the domain. Run again to change the names; it keeps the same tunnel.
#
#   sudo bash tunnel_setup.sh example.ru [hub name] [HA name]
set -e

[ "$(id -u)" = 0 ] || { echo "Запусти через sudo: sudo bash $0 <домен>"; exit 1; }
DOMAIN="$1"
[ -n "$DOMAIN" ] || { echo "Укажи домен: sudo bash $0 example.ru"; exit 1; }
HUB_HOST="${2:-home}.$DOMAIN"
HA_HOST="${3:-ha}.$DOMAIN"
NAME=deskdash

if ! command -v cloudflared >/dev/null; then
    echo "== cloudflared"
    mkdir -p --mode=0755 /usr/share/keyrings
    curl -fsSL https://pkg.cloudflare.com/cloudflare-main.gpg > /usr/share/keyrings/cloudflare-main.gpg
    echo "deb [signed-by=/usr/share/keyrings/cloudflare-main.gpg] https://pkg.cloudflare.com/cloudflared $(. /etc/os-release; echo "$VERSION_CODENAME") main" \
        > /etc/apt/sources.list.d/cloudflared.list
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq cloudflared >/dev/null
fi

if [ ! -f /root/.cloudflared/cert.pem ]; then
    echo "== Вход в Cloudflare: открой ссылку ниже, войди и выбери домен $DOMAIN"
    cloudflared tunnel login
fi

echo "== Туннель $NAME"
ID=$(cloudflared tunnel list -o json 2>/dev/null | python3 -c "import json,sys; print(next((t['id'] for t in json.load(sys.stdin) or [] if t['name'] == '$NAME'), ''))")
if [ -z "$ID" ]; then
    cloudflared tunnel create "$NAME" >/dev/null
    ID=$(cloudflared tunnel list -o json | python3 -c "import json,sys; print(next(t['id'] for t in json.load(sys.stdin) if t['name'] == '$NAME'))")
fi
mkdir -p /etc/cloudflared
install -m 600 "/root/.cloudflared/$ID.json" "/etc/cloudflared/$ID.json"
cat > /etc/cloudflared/config.yml <<EOF
tunnel: $ID
credentials-file: /etc/cloudflared/$ID.json
ingress:
  - hostname: $HUB_HOST
    service: http://localhost:8780
  - hostname: $HA_HOST
    service: http://localhost:8123
  - service: http_status:404
EOF

echo "== Адреса в DNS"
cloudflared tunnel route dns --overwrite-dns "$NAME" "$HUB_HOST"
cloudflared tunnel route dns --overwrite-dns "$NAME" "$HA_HOST"

echo "== Служба"
if [ ! -f /etc/systemd/system/cloudflared.service ]; then
    cloudflared service install
fi
systemctl enable cloudflared >/dev/null 2>&1
systemctl restart cloudflared

echo
echo "Готово (DNS обновляется до пары минут):"
echo "  хаб:            https://$HUB_HOST"
echo "  Home Assistant: https://$HA_HOST"
