#!/bin/bash
# Home Assistant on the Raspberry Pi in Docker, next to the DeskDash hub: http://<pi>:8123.
# Starts with the Pi, picks up Zigbee/Z-Wave sticks, copies its settings to the NAS every night.
# Update later with: sudo ha-update. Run once with sudo; running it again changes nothing.
#
#   sudo bash ha_setup.sh
set -e

[ "$(id -u)" = 0 ] || { echo "Запусти через sudo: sudo bash $0"; exit 1; }
SRC="$(cd "$(dirname "$0")" && pwd)"
CONF=/opt/homeassistant/config

echo "== Docker"
# Docker's own packages: the distribution's docker.io 20.10 can't unpack the Home Assistant image on 32-bit
if ! command -v docker >/dev/null || docker --version | grep -q "version 20\."; then
    ARCH=$(dpkg --print-architecture)
    . /etc/os-release
    DIST=$([ "$ARCH" = armhf ] && echo raspbian || echo debian)
    apt-get purge -y -qq docker.io >/dev/null 2>&1 || true
    curl -fsSL "https://download.docker.com/linux/$DIST/gpg" | gpg --dearmor --yes -o /usr/share/keyrings/docker.gpg
    echo "deb [arch=$ARCH signed-by=/usr/share/keyrings/docker.gpg] https://download.docker.com/linux/$DIST $VERSION_CODENAME stable" \
        > /etc/apt/sources.list.d/docker.list
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq docker-ce docker-ce-cli containerd.io >/dev/null
fi
systemctl enable --now docker >/dev/null 2>&1
install -m 644 "$SRC/ha/seccomp-arm64.json" /etc/docker/seccomp-arm64.json

echo "== Настройки Home Assistant: $CONF"
mkdir -p "$CONF/themes"
if [ ! -f "$CONF/configuration.yaml" ]; then
    # Home Assistant's own starting config
    cat > "$CONF/configuration.yaml" <<'EOF'
default_config:

frontend:
  themes: !include_dir_merge_named themes

automation: !include automations.yaml
script: !include scripts.yaml
scene: !include scenes.yaml
EOF
    echo "[]" > "$CONF/automations.yaml"
    touch "$CONF/scripts.yaml" "$CONF/scenes.yaml"
fi

echo "== Образ (первый раз — около 1,5 ГБ)"
PLATFORM=
[ "$(dpkg --print-architecture)" = armhf ] && PLATFORM="--platform linux/arm64"
docker image inspect ghcr.io/home-assistant/home-assistant:stable >/dev/null 2>&1 \
    || docker pull -q $PLATFORM ghcr.io/home-assistant/home-assistant:stable

echo "== Служба"
install -m 755 "$SRC/ha/homeassistant-run" "$SRC/ha/ha-update" "$SRC/ha/ha-backup" /usr/local/sbin/
install -m 644 "$SRC/ha/homeassistant.service" "$SRC/ha/ha-backup.service" "$SRC/ha/ha-backup.timer" /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now homeassistant ha-backup.timer >/dev/null 2>&1
systemctl restart homeassistant
if command -v ufw >/dev/null && ufw status | grep -q active; then ufw allow 8123/tcp >/dev/null; fi

IP=$(hostname -I | awk '{print $1}')
echo -n "== Запуск"
for _ in $(seq 1 90); do
    curl -s -o /dev/null -m 3 "http://127.0.0.1:8123/" && break
    echo -n "."
    sleep 5
done
echo
echo "Home Assistant: http://$IP:8123 — открой и создай свой аккаунт (первый запуск)."
echo "Обновить потом: sudo ha-update"
