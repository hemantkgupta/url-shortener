#!/usr/bin/env bash
# =============================================================================
# deploy/gcp/bootstrap.sh — Prepare a GCP Ubuntu VM for one-box deployment
# =============================================================================
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root: sudo bash ./deploy/gcp/bootstrap.sh" >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive

apt-get update
apt-get install -y \
  apt-transport-https \
  ca-certificates \
  curl \
  git \
  gnupg \
  jq \
  lsof \
  lsb-release \
  software-properties-common \
  unzip

install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc

echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "${VERSION_CODENAME}") stable" \
  > /etc/apt/sources.list.d/docker.list

apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

systemctl enable docker
systemctl start docker

mkdir -p /data/server/url-shortener
chmod 755 /data/server/url-shortener
mkdir -p \
  /data/server/url-shortener/scylla \
  /data/server/url-shortener/redis \
  /data/server/url-shortener/etcd \
  /data/server/url-shortener/kafka \
  /data/server/url-shortener/clickhouse \
  /data/server/url-shortener/clickhouse-logs
chmod -R a+rwX /data/server/url-shortener

DEFAULT_USER="${SUDO_USER:-}"
if [[ -n "${DEFAULT_USER}" && "${DEFAULT_USER}" != "root" ]]; then
  usermod -aG docker "${DEFAULT_USER}" || true
  chown "${DEFAULT_USER}:${DEFAULT_USER}" /data/server/url-shortener || true
fi

echo "Bootstrap complete."
if [[ -n "${DEFAULT_USER}" && "${DEFAULT_USER}" != "root" ]]; then
  echo "Log out and back in so ${DEFAULT_USER} picks up docker group membership."
fi
