#!/bin/sh
# DeskDash Hub on a Raspberry Pi (Raspberry Pi OS / Debian / Ubuntu): copies the hub to /opt/deskdash-hub
# and runs it as a systemd service under the current user. Run again to update.
set -e

SRC="$(cd "$(dirname "$0")" && pwd)"
DEST=/opt/deskdash-hub
USER_NAME="${SUDO_USER:-$(id -un)}"
DATA="$(getent passwd "$USER_NAME" | cut -d: -f6)/.config/deskdash-hub"

command -v python3 >/dev/null || sudo apt-get install -y python3

sudo mkdir -p "$DEST/web"
sudo cp "$SRC"/deskdash_hub.py "$SRC"/providers.py "$SRC"/machines.py "$DEST"/
sudo cp "$SRC"/web/* "$DEST/web/"

sudo tee /etc/systemd/system/deskdash-hub.service >/dev/null <<EOF
[Unit]
Description=DeskDash Hub
After=network-online.target
Wants=network-online.target

[Service]
User=$USER_NAME
Environment=DESKDASH_HUB_DATA=$DATA
ExecStart=/usr/bin/python3 -u $DEST/deskdash_hub.py
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable deskdash-hub >/dev/null
sudo systemctl restart deskdash-hub

if command -v ufw >/dev/null && sudo ufw status | grep -q active; then
    sudo ufw allow 8780/tcp >/dev/null
    sudo ufw allow 8767/udp >/dev/null
fi

sleep 2
echo
echo "DeskDash Hub установлен и запущен."
sudo -u "$USER_NAME" env DESKDASH_HUB_DATA="$DATA" python3 "$DEST/deskdash_hub.py" token list
echo
echo "Открой на телефоне ссылку администратора:"
journalctl -u deskdash-hub -n 20 --no-pager -o cat | grep "вход администратора" | tail -1
echo "Журнал: journalctl -u deskdash-hub -f"
