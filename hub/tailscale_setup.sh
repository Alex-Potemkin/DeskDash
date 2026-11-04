#!/bin/bash
# Outside access without a domain or a public IP: Tailscale, a private network between your devices. It works behind
# the provider's CGNAT, nothing is opened on the router. The Pi also passes on traffic to the home network, so
# phones reach the hub, Home Assistant, the NAS and the PC at their usual 192.168.x.x addresses from anywhere.
# The first run prints a link: open it and sign in. Then approve the route in the Tailscale admin console
# (Machines → this Pi → Edit route settings). Run again to update.
#
#   sudo bash tailscale_setup.sh
set -e

[ "$(id -u)" = 0 ] || { echo "Запусти через sudo: sudo bash $0"; exit 1; }

if ! command -v tailscale >/dev/null; then
    echo "== Tailscale"
    . /etc/os-release
    DIST=$([ "$(dpkg --print-architecture)" = armhf ] && echo raspbian || echo debian)
    curl -fsSL "https://pkgs.tailscale.com/stable/$DIST/$VERSION_CODENAME.noarmor.gpg" > /usr/share/keyrings/tailscale-archive-keyring.gpg
    curl -fsSL "https://pkgs.tailscale.com/stable/$DIST/$VERSION_CODENAME.tailscale-keyring.list" > /etc/apt/sources.list.d/tailscale.list
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq tailscale >/dev/null
fi
# the control server's long stream stalls on port 80 on some Russian networks: the Pi signs in but never joins
grep -q TS_FORCE_NOISE_443 /etc/default/tailscaled || echo "TS_FORCE_NOISE_443=true" >> /etc/default/tailscaled
systemctl enable tailscaled >/dev/null 2>&1
systemctl restart tailscaled

echo "== Пересылка в домашнюю сеть"
echo "net.ipv4.ip_forward = 1" > /etc/sysctl.d/99-deskdash-tailscale.conf
sysctl -q -p /etc/sysctl.d/99-deskdash-tailscale.conf

LAN=$(ip -4 route | awk '/ proto kernel / && !/docker|br-|tailscale/ {print $1; exit}')
echo "== Вход (домашняя сеть $LAN)"
# --accept-dns=false: the Pi keeps the home router's DNS, Home Assistant and the hub don't depend on Tailscale
tailscale up --hostname="$(hostname | tr A-Z a-z)" --advertise-routes="$LAN" --accept-dns=false

echo
echo "Готово. Адрес Pi в Tailscale: $(tailscale ip -4)"
echo "  хаб:            http://$(tailscale ip -4):8780"
echo "  Home Assistant: http://$(tailscale ip -4):8123"
echo "  NAS:            \\\\$(tailscale ip -4)\\nas"
