#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export DEBIAN_FRONTEND=noninteractive

jdk21_on_path() {
  command -v java >/dev/null 2>&1 || return 1
  local v
  v="$(java -version 2>&1 || true)"
  [[ "$v" == *'"21.'* ]]
}

install_temurin21() {
  if [ -x /usr/local/lib/temurin-21/bin/java ]; then
    sudo ln -sfn /usr/local/lib/temurin-21/bin/java /usr/local/bin/java
    sudo ln -sfn /usr/local/lib/temurin-21/bin/javac /usr/local/bin/javac
    return
  fi
  local tmp extracted
  tmp="$(mktemp -d)"
  curl --retry 3 --retry-delay 5 -fsSL \
    "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk" \
    -o "$tmp/jdk.tar.gz"
  sudo mkdir -p /usr/local/lib
  sudo tar -xzf "$tmp/jdk.tar.gz" -C /usr/local/lib
  extracted="$(echo /usr/local/lib/jdk-21*)"
  sudo rm -rf /usr/local/lib/temurin-21
  sudo mv "$extracted" /usr/local/lib/temurin-21
  sudo ln -sfn /usr/local/lib/temurin-21/bin/java /usr/local/bin/java
  sudo ln -sfn /usr/local/lib/temurin-21/bin/javac /usr/local/bin/javac
  rm -rf "$tmp"
}

docker_packages_present() {
  command -v docker >/dev/null 2>&1 && [ -x /usr/sbin/iptables ]
}

install_docker() {
  if docker_packages_present; then
    echo "Docker packages already installed"
    return
  fi
  # The VM has no systemd. Refuse package postinsts that try to start services.
  if [ ! -x /usr/sbin/policy-rc.d ]; then
    echo '#!/bin/sh' | sudo tee /usr/sbin/policy-rc.d >/dev/null
    echo 'exit 101' | sudo tee -a /usr/sbin/policy-rc.d >/dev/null
    sudo chmod 755 /usr/sbin/policy-rc.d
  fi
  sudo apt-get update
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y \
    -o Dpkg::Options::=--force-confdef \
    -o Dpkg::Options::=--force-confold \
    ca-certificates curl
  sudo install -m 0755 -d /etc/apt/keyrings
  if [ ! -f /etc/apt/keyrings/docker.asc ]; then
    sudo curl --retry 3 --retry-delay 5 -fsSL \
      https://download.docker.com/linux/ubuntu/gpg \
      -o /etc/apt/keyrings/docker.asc
    sudo chmod a+r /etc/apt/keyrings/docker.asc
  fi
  local codename
  codename="$(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")"
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${codename} stable" \
    | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
  sudo apt-get update
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y \
    -o Dpkg::Options::=--force-confdef \
    -o Dpkg::Options::=--force-confold \
    docker-ce \
    docker-ce-cli \
    containerd.io \
    docker-buildx-plugin \
    docker-compose-plugin \
    iptables
}

configure_docker() {
  sudo mkdir -p /etc/docker
  # The socket group is ubuntu so the agent user can open it without a new login.
  # Testcontainers talks to the socket from the JVM, not through a sudo wrapper.
  local desired
  desired='{
  "group": "ubuntu"
}'
  if [ "$(cat /etc/docker/daemon.json 2>/dev/null || true)" != "$desired" ]; then
    printf '%s\n' "$desired" | sudo tee /etc/docker/daemon.json >/dev/null
  fi
}

stop_docker() {
  if [ -f /var/run/docker.pid ]; then
    sudo kill "$(cat /var/run/docker.pid)" 2>/dev/null || true
  fi
  if [ -f /run/containerd/containerd.pid ]; then
    sudo kill "$(cat /run/containerd/containerd.pid)" 2>/dev/null || true
  fi
  local i
  for i in $(seq 1 30); do
    if ! pgrep -x dockerd >/dev/null 2>&1 && ! pgrep -x containerd >/dev/null 2>&1; then
      break
    fi
    sleep 0.5
  done
  sudo rm -f /var/run/docker.pid /var/run/docker.sock
  sudo rm -f /run/containerd/containerd.pid
}

if jdk21_on_path; then
  echo "JDK 21 already on PATH: $(java -version 2>&1 | head -1)"
else
  echo "Installing Temurin JDK 21"
  install_temurin21
  java -version
fi

install_docker
configure_docker

echo "Pulling images Testcontainers starts"
bash "$ROOT/.cursor/start.sh"
docker pull postgres:17-alpine
docker pull testcontainers/ryuk:0.14.0
stop_docker

echo "Resolving Gradle 9.8.0 and compiling"
./gradlew --no-daemon testClasses :dispatch-api:bootJar

echo "install done"
