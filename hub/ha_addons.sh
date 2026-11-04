#!/bin/bash
# Adds to Home Assistant what it doesn't have out of the box: Bambu Lab printers (ha-bambulab), Yandex devices and
# speakers (YandexStation) and HACS, which keeps these two up to date. After this they are in Settings → Devices &
# Services → Add integration. Run with sudo; again to get the latest versions.
#
#   sudo bash ha_addons.sh
set -e

[ "$(id -u)" = 0 ] || { echo "Запусти через sudo: sudo bash $0"; exit 1; }
CC=/opt/homeassistant/config/custom_components
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$CC"

# a release's zip holds the integration's files without a folder around them
zip_release() {
    curl -fsSL -o "$TMP/$2.zip" "https://github.com/$1/releases/latest/download/$3"
    rm -rf "${CC:?}/$2"
    mkdir -p "$CC/$2"
    python3 -m zipfile -e "$TMP/$2.zip" "$CC/$2"
}

echo "== HACS"
zip_release hacs/integration hacs hacs.zip
echo "== Bambu Lab"
zip_release greghesp/ha-bambulab bambu_lab bambu_lab.zip
[ -f "$CC/bambu_lab/manifest.json" ] || { mv "$CC/bambu_lab" "$TMP/b"; mv "$TMP/b/custom_components/bambu_lab" "$CC/bambu_lab" 2>/dev/null || mv "$TMP/b/bambu_lab" "$CC/bambu_lab"; }
# The cover picture of the last print comes from Amazon S3 with no timeout, on the thread that reads the printer.
# Where S3 stalls (Russian networks) that thread hangs for good and the printer's entities never appear.
sed -i 's/response = requests.get(url)$/response = requests.get(url, timeout=10)/' "$CC/bambu_lab/pybambu/bambu_cloud.py"
echo "== Яндекс (YandexStation)"
TAG=$(curl -fsSI https://github.com/AlexxIT/YandexStation/releases/latest | tr -d '\r' | awk 'tolower($1) == "location:" {n = split($2, a, "/"); print a[n]}')
curl -fsSL -o "$TMP/y.tgz" "https://github.com/AlexxIT/YandexStation/archive/refs/tags/$TAG.tar.gz"
tar -xzf "$TMP/y.tgz" -C "$TMP"
rm -rf "${CC:?}/yandex_station"
cp -r "$TMP"/YandexStation-*/custom_components/yandex_station "$CC/"

echo "== Перезапуск Home Assistant"
systemctl restart homeassistant
for n in hacs bambu_lab yandex_station; do
    python3 -c "import json,sys; m=json.load(open('$CC/$n/manifest.json')); print(' ', m['name'], m.get('version'))"
done
echo "Готово: Настройки → Устройства и службы → Добавить интеграцию."
