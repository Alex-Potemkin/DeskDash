#!/bin/bash
# The Pi as the always-on member of an Obsidian vault synced with Syncthing: phone and PC swap notes through it even
# when the other one is off. It keeps old versions of changed and deleted notes for a month, and ha-backup copies the
# vault to the NAS at night. The vault lives on the SD card, not on a NAS disk: disks get pulled out, notes are small.
# Run with sudo; again changes nothing. The folder id and device ids are in Syncthing on the PC ("Actions → Show ID").
#
#   sudo bash obsidian_setup.sh <folder id> <device id>...
set -e

[ "$(id -u)" = 0 ] || { echo "Запусти через sudo"; exit 1; }
FOLDER="$1"
shift || true
[ -n "$FOLDER" ] && [ $# -gt 0 ] || { echo "sudo bash $0 <id папки> <id устройства>..."; exit 1; }
USER_NAME="${SUDO_USER:-aslhost}"
VAULT=$(getent passwd "$USER_NAME" | cut -d: -f6)/Obsidian
st() { sudo -u "$USER_NAME" syncthing cli "$@"; }

if ! command -v syncthing >/dev/null; then
    echo "== Syncthing"
    mkdir -p /etc/apt/keyrings
    curl -fsSL https://syncthing.net/release-key.gpg -o /etc/apt/keyrings/syncthing-archive-keyring.gpg
    echo "deb [signed-by=/etc/apt/keyrings/syncthing-archive-keyring.gpg] https://apt.syncthing.net/ syncthing stable-v2" \
        > /etc/apt/sources.list.d/syncthing.list
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq syncthing >/dev/null
fi
systemctl enable --now "syncthing@$USER_NAME" >/dev/null 2>&1
for _ in $(seq 1 30); do st show system >/dev/null 2>&1 && break; sleep 1; done

echo "== Папка $VAULT"
sudo -u "$USER_NAME" mkdir -p "$VAULT"
st config folders list | grep -qx "$FOLDER" || st config folders add --id "$FOLDER" --label "Obsidian Vault" --path "$VAULT"
for id in "$@"; do
    st config devices list | grep -qx "$id" || st config devices add --device-id "$id"
    st config folders "$FOLDER" devices list | grep -qx "$id" || st config folders "$FOLDER" devices add --device-id "$id"
done
st config folders "$FOLDER" versioning type set trashcan
st config folders "$FOLDER" versioning params set cleanoutDays 30 2>/dev/null || true

echo
echo "Готово. ID этого Pi (добавь его на телефоне и ПК):"
st show system | python3 -c "import json,sys; print(json.load(sys.stdin)['myID'])"
