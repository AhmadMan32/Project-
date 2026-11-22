#!/usr/bin/env bash
# Kalkan DNS — WARP modu için kendi WireGuard sunucunuzu kurar.
# Ubuntu / Debian sunucuda root olarak çalıştırın:
#   sudo bash warp-sunucu-kur.sh
# Sonunda telefona aktaracağınız "kalkan-warp.conf" dosyası oluşur.
set -euo pipefail

PORT="${PORT:-51820}"
NET="10.66.0"
WG=/etc/wireguard

[ "$(id -u)" = 0 ] || { echo "Lütfen root olarak çalıştırın (sudo)."; exit 1; }
if [ -f "$WG/wg0.conf" ]; then
  echo "$WG/wg0.conf zaten var; mevcut kurulumun üzerine yazılmadı."; exit 1
fi

apt-get update -y
apt-get install -y wireguard iptables curl

IFACE="$(ip -4 route show default | awk '{print $5; exit}')"
PUBIP="${PUBIP:-$(curl -4 -fsS https://api.ipify.org || hostname -I | awk '{print $1}')}"

umask 077
mkdir -p "$WG"
SERVER_KEY="$(wg genkey)";  SERVER_PUB="$(echo "$SERVER_KEY" | wg pubkey)"
CLIENT_KEY="$(wg genkey)";  CLIENT_PUB="$(echo "$CLIENT_KEY" | wg pubkey)"

cat > "$WG/wg0.conf" <<EOF
[Interface]
Address = $NET.1/24
ListenPort = $PORT
PrivateKey = $SERVER_KEY
PostUp = iptables -t nat -A POSTROUTING -s $NET.0/24 -o $IFACE -j MASQUERADE; iptables -A FORWARD -i wg0 -j ACCEPT; iptables -A FORWARD -o wg0 -j ACCEPT
PostDown = iptables -t nat -D POSTROUTING -s $NET.0/24 -o $IFACE -j MASQUERADE; iptables -D FORWARD -i wg0 -j ACCEPT; iptables -D FORWARD -o wg0 -j ACCEPT

[Peer]
# Telefon
PublicKey = $CLIENT_PUB
AllowedIPs = $NET.2/32
EOF

echo "net.ipv4.ip_forward=1" > /etc/sysctl.d/99-kalkan-warp.conf
sysctl -p /etc/sysctl.d/99-kalkan-warp.conf

systemctl enable --now wg-quick@wg0

# ::/0 da tünele alınır; böylece IPv6 trafiği tünel dışına sızmaz.
cat > "$WG/kalkan-warp.conf" <<EOF
[Interface]
PrivateKey = $CLIENT_KEY
Address = $NET.2/32
DNS = 1.1.1.1, 1.0.0.1

[Peer]
PublicKey = $SERVER_PUB
Endpoint = $PUBIP:$PORT
AllowedIPs = 0.0.0.0/0, ::/0
PersistentKeepalive = 25
EOF

echo
echo "Kurulum tamam. Güvenlik duvarınızda UDP $PORT portunu açın."
echo "Telefona aktarılacak dosya: $WG/kalkan-warp.conf"
echo "------------------------------------------------------------"
cat "$WG/kalkan-warp.conf"
