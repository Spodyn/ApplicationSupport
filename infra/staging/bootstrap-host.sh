#!/usr/bin/env bash
# Run via the existing trusted administrator SSH session. Does not launch USI.
set -euo pipefail
umask 077
[ "$(id -u)" = 0 ] || { echo 'Root is required for host preparation.' >&2; exit 1; }
source_dir=$(cd -- "$(dirname -- "$0")" && pwd)
admin_ip=${SSH_CONNECTION%% *}
[ -n "$admin_ip" ] || { echo 'Use the existing administrator SSH session.' >&2; exit 1; }
[ -f /root/usi-bootstrap/runner-wg.pub ] && [ -f /root/usi-bootstrap/actions-ssh.pub ]
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y ca-certificates curl git python3 openssl wireguard-tools iproute2 ufw age
if ! command -v docker >/dev/null; then
  install -m 0755 -d /etc/apt/keyrings
  curl --fail --silent --show-error --location https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod 0644 /etc/apt/keyrings/docker.asc
  . /etc/os-release
  printf 'Types: deb\nURIs: https://download.docker.com/linux/ubuntu\nSuites: %s\nComponents: stable\nSigned-By: /etc/apt/keyrings/docker.asc\n' "$VERSION_CODENAME" > /etc/apt/sources.list.d/docker.sources
  apt-get update
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
systemctl enable --now docker
install -d -m 0700 /etc/wireguard
if [ ! -f /etc/wireguard/usi-staging.key ]; then wg genkey > /etc/wireguard/usi-staging.key; fi
peer=$(cat /root/usi-bootstrap/runner-wg.pub)
if [ ! -f /etc/wireguard/usi-staging.conf ]; then
  {
    printf '[Interface]\nAddress = 10.77.0.1/32\nListenPort = 51820\nPrivateKey = '
    cat /etc/wireguard/usi-staging.key
    printf '\n[Peer]\nPublicKey = %s\nAllowedIPs = 10.77.0.2/32\n' "$peer"
  } > /etc/wireguard/usi-staging.conf
fi
chmod 0600 /etc/wireguard/usi-staging.*
systemctl enable --now wg-quick@usi-staging
id usi-deploy >/dev/null 2>&1 || useradd --system --create-home --shell /bin/bash usi-deploy
chown root:root /home/usi-deploy
chmod 0755 /home/usi-deploy
install -d -m 0755 -o root -g root /home/usi-deploy/.ssh
key=$(cat /root/usi-bootstrap/actions-ssh.pub)
printf 'from="10.77.0.2",restrict,command="/usr/bin/sudo -n /usr/local/sbin/usi-staging-receive \\"$SSH_ORIGINAL_COMMAND\\"" %s\n' "$key" > /home/usi-deploy/.ssh/authorized_keys
chmod 0644 /home/usi-deploy/.ssh/authorized_keys
install -d -m 0755 /usr/local/lib/usi-staging
install -m 0755 "$source_dir/receive.py" /usr/local/sbin/usi-staging-receive
install -m 0644 "$source_dir/deployment_policy.py" "$source_dir/check_ci.py" /usr/local/lib/usi-staging/
printf 'usi-deploy ALL=(root) NOPASSWD: /usr/local/sbin/usi-staging-receive *\n' > /etc/sudoers.d/usi-staging
chmod 0440 /etc/sudoers.d/usi-staging
visudo -cf /etc/sudoers.d/usi-staging
cat > /etc/ssh/sshd_config.d/50-usi-staging.conf <<'EOF'
Match User usi-deploy
    PasswordAuthentication no
    KbdInteractiveAuthentication no
    AllowTcpForwarding no
    X11Forwarding no
    PermitTTY no
    PermitUserRC no
Match all
EOF
sshd -t
systemctl reload ssh
ufw default deny incoming
ufw default allow outgoing
ufw allow from "$admin_ip" to any port 22 proto tcp comment 'USI trusted administrator'
ufw allow in on usi-staging from 10.77.0.2 to 10.77.0.1 port 22 proto tcp comment 'USI Actions over VPN'
ufw allow 51820/udp comment 'USI WireGuard'
ufw allow 80/tcp comment 'Caddy ACME and redirect'
ufw allow 443/tcp comment 'Caddy HTTPS and WebSocket'
ufw --force enable
echo 'Host preparation completed. No application deployed and no provider webhook changed.'
