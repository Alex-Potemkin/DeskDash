#!/bin/bash
# NAS on the Raspberry Pi: mounts an external disk at /mnt/nas on every boot, shares it over Samba
# and shows it on the hub's "Сервер" tab. Run once with sudo; running it again changes nothing.
#
#   sudo bash nas_setup.sh                 the first NTFS/ext4/exFAT partition on a USB disk
#   sudo bash nas_setup.sh /dev/sda1       a specific partition
set -e

[ "$(id -u)" = 0 ] || { echo "Запусти через sudo: sudo bash $0"; exit 1; }
USER_NAME="${SUDO_USER:-aslhost}"
UID_N=$(id -u "$USER_NAME")
GID_N=$(id -g "$USER_NAME")
MNT=/mnt/nas
SHARE=nas

DEV="$1"
if [ -z "$DEV" ]; then
    DEV=$(lsblk -nrpo NAME,FSTYPE | awk '$2 ~ /^(ntfs|ext4|exfat)$/ && $1 !~ /mmcblk|loop|zram/ {print $1}' | head -1)
fi
[ -b "$DEV" ] || { echo "Диск не найден. Укажи раздел: sudo bash $0 /dev/sda1"; lsblk; exit 1; }
FS=$(lsblk -no FSTYPE "$DEV")
UUID=$(lsblk -no UUID "$DEV")
LABEL=$(lsblk -no LABEL "$DEV")
echo "Диск: $DEV  «$LABEL»  $FS  UUID=$UUID"

echo "== Пакеты"
PKGS="samba"
[ "$FS" = ntfs ] && PKGS="$PKGS ntfs-3g"
[ "$FS" = exfat ] && PKGS="$PKGS exfatprogs"
apt-get update -qq
DEBIAN_FRONTEND=noninteractive apt-get install -y -qq $PKGS >/dev/null

echo "== Монтирование в $MNT"
mkdir -p "$MNT"
case "$FS" in
    # ntfs-3g, not the kernel ntfs3 driver: it also mounts a disk that Windows didn't shut down cleanly
    ntfs)  OPTS="uid=$UID_N,gid=$GID_N,umask=002,big_writes"; TYPE=ntfs-3g ;;
    exfat) OPTS="uid=$UID_N,gid=$GID_N,umask=002"; TYPE=exfat ;;
    *)     OPTS="defaults"; TYPE=ext4 ;;
esac
# nofail: the Pi still boots when the disk is unplugged
LINE="UUID=$UUID $MNT $TYPE $OPTS,nofail,x-systemd.device-timeout=15s 0 0"
if grep -q " $MNT " /etc/fstab; then
    sed -i "\| $MNT |c\\$LINE" /etc/fstab
else
    cp /etc/fstab /etc/fstab.bak-deskdash
    echo "$LINE" >> /etc/fstab
fi
systemctl daemon-reload
mountpoint -q "$MNT" && umount "$MNT"
mount "$MNT"
[ "$FS" = ext4 ] && chown "$USER_NAME:$USER_NAME" "$MNT"
df -h "$MNT" | tail -1

echo "== Samba: \\\\$(hostname)\\$SHARE"
if ! grep -q "^\[$SHARE\]" /etc/samba/smb.conf; then
    cat >> /etc/samba/smb.conf <<EOF

[$SHARE]
   comment = NAS ($LABEL)
   path = $MNT
   valid users = $USER_NAME
   force user = $USER_NAME
   read only = no
   browseable = yes
   create mask = 0664
   directory mask = 0775
EOF
fi
testparm -s >/dev/null 2>&1 || { echo "Ошибка в /etc/samba/smb.conf"; exit 1; }
if ! pdbedit -L 2>/dev/null | grep -q "^$USER_NAME:"; then
    echo "Придумай пароль для сетевой папки (пользователь $USER_NAME):"
    smbpasswd -a "$USER_NAME"
fi
systemctl enable --now smbd >/dev/null 2>&1
systemctl restart smbd
if command -v ufw >/dev/null && ufw status | grep -q active; then ufw allow samba >/dev/null; fi

echo "== Хаб: показывать $MNT на вкладке «Сервер»"
CFG=$(getent passwd "$USER_NAME" | cut -d: -f6)/.config/deskdash-hub/config.json
if [ -f "$CFG" ]; then
    sudo -u "$USER_NAME" python3 - "$CFG" "$MNT" <<'EOF'
import json, sys
path, mnt = sys.argv[1], sys.argv[2]
c = json.load(open(path, encoding="utf-8"))
paths = c.setdefault("nas", {}).setdefault("paths", ["/"])
if mnt not in paths:
    paths.append(mnt)
json.dump(c, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
EOF
    sudo -u "$USER_NAME" XDG_RUNTIME_DIR=/run/user/$UID_N systemctl --user restart deskdash-hub 2>/dev/null \
        || systemctl restart deskdash-hub 2>/dev/null || true
fi

IP=$(hostname -I | awk '{print $1}')
echo
echo "Готово."
echo "  Windows:  \\\\$IP\\$SHARE   (логин $USER_NAME и пароль Samba)"
echo "  Android:  smb://$IP/$SHARE   (например, в CX Проводник или Solid Explorer)"
echo "  Диск монтируется сам при включении; если его отключить, Pi всё равно загрузится."
