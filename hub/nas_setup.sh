#!/bin/bash
# NAS on the Raspberry Pi: after this, any USB disk plugged in mounts by itself at /mnt/nas/<its label>,
# shows up in the \\<pi>\nas network folder and on the hub's "Сервер" tab; pulled out, it is unmounted cleanly.
# NTFS, exFAT, FAT and ext4 disks all work as they are, nothing gets formatted. Run once with sudo; again to update.
#
#   sudo bash nas_setup.sh
set -e

[ "$(id -u)" = 0 ] || { echo "Запусти через sudo: sudo bash $0"; exit 1; }
SRC="$(cd "$(dirname "$0")" && pwd)"
USER_NAME="${SUDO_USER:-aslhost}"
[ "$USER_NAME" = root ] && USER_NAME=aslhost

echo "== Пакеты"
apt-get update -qq
# wsdd: the Pi shows up in Windows Explorer's "Network"
DEBIAN_FRONTEND=noninteractive apt-get install -y -qq samba ntfs-3g exfatprogs wsdd >/dev/null

echo "== Диски подключаются сами"
echo "$USER_NAME" > /etc/deskdash-nas.user
install -m 755 "$SRC/nas/deskdash-nas" "$SRC/nas/deskdash-nas-dfree" /usr/local/sbin/
install -m 644 "$SRC/nas/deskdash-nas@.service" /etc/systemd/system/
install -m 644 "$SRC/nas/99-deskdash-nas.rules" /etc/udev/rules.d/
mkdir -p /mnt/nas
# root's: the folder itself is on the SD card, so the share can write into the disks but not next to them
chown root:root /mnt/nas
# the old one-disk setup put the disk into fstab at /mnt/nas itself: now every disk gets its own folder there
if grep -q " /mnt/nas " /etc/fstab; then
    mountpoint -q /mnt/nas && umount /mnt/nas || true
    sed -i '\| /mnt/nas |d' /etc/fstab
fi
systemctl daemon-reload
udevadm control --reload
# disks that are already plugged in
udevadm trigger --action=add --subsystem-match=block

echo "== Сетевая папка \\\\$(hostname)\\nas"
if ! grep -q "^\[nas\]" /etc/samba/smb.conf; then
    cat >> /etc/samba/smb.conf <<EOF

[nas]
   comment = DeskDash NAS: все подключённые диски
   path = /mnt/nas
   valid users = $USER_NAME
   force user = $USER_NAME
   read only = no
   browseable = yes
   create mask = 0664
   directory mask = 0775
EOF
fi
grep -q "deskdash-nas-dfree" /etc/samba/smb.conf \
    || sed -i '/^\[nas\]/a\   dfree command = /usr/local/sbin/deskdash-nas-dfree' /etc/samba/smb.conf
# a wrong login is refused, not turned into a guest: Windows can't sign a guest session and shows a meaningless error
sed -i 's/^\( *map to guest *= *\).*/\1never/I' /etc/samba/smb.conf
testparm -s >/dev/null 2>&1 || { echo "Ошибка в /etc/samba/smb.conf"; exit 1; }
systemctl enable --now smbd wsdd >/dev/null 2>&1 || true
systemctl restart smbd
if command -v ufw >/dev/null && ufw status | grep -q active; then ufw allow samba >/dev/null; fi

echo "== Wi-Fi без энергосбережения"
# a server answers at once: with power saving the Pi drops pings and takes 200 ms to wake up
if [ -d /etc/NetworkManager/conf.d ]; then
    printf '[connection]\nwifi.powersave = 2\n' > /etc/NetworkManager/conf.d/deskdash-wifi.conf
    iw dev wlan0 set power_save off 2>/dev/null || true
fi

echo "== Хаб: диски на вкладке «Сервер», копии на самый большой диск"
CFG=$(getent passwd "$USER_NAME" | cut -d: -f6)/.config/deskdash-hub/config.json
if [ -f "$CFG" ]; then
    sudo -u "$USER_NAME" python3 - "$CFG" <<'EOF'
import json, sys
path = sys.argv[1]
c = json.load(open(path, encoding="utf-8"))
paths = [p for p in c.setdefault("nas", {}).get("paths") or ["/"] if p != "/mnt/nas"]
if "/mnt/nas/*" not in paths:
    paths.append("/mnt/nas/*")
c["nas"]["paths"] = paths
json.dump(c, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
EOF
    systemctl restart deskdash-hub 2>/dev/null || true
fi

IP=$(hostname -I | awk '{print $1}')
echo
echo "Готово. Вставь диск — через несколько секунд он в сетевой папке:"
echo "  Windows:  \\\\$IP\\nas      Android:  smb://$IP/nas"
if ! pdbedit -L 2>/dev/null | grep -q "^$USER_NAME:"; then
    echo
    echo "Остался один шаг: пароль для сетевой папки. Придумай его и введи:"
    echo "  sudo smbpasswd -a $USER_NAME"
fi
