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

adoptium_arch() {
  case "$(uname -m)" in
    x86_64) printf '%s\n' x64 ;;
    aarch64|arm64) printf '%s\n' aarch64 ;;
    *)
      echo "unsupported architecture $(uname -m)" >&2
      return 1
      ;;
  esac
}

install_temurin21() {
  if [ -x /usr/local/lib/temurin-21/bin/java ]; then
    sudo ln -sfn /usr/local/lib/temurin-21/bin/java /usr/local/bin/java
    sudo ln -sfn /usr/local/lib/temurin-21/bin/javac /usr/local/bin/javac
    return
  fi
  local arch tmp link sum extracted
  arch="$(adoptium_arch)"
  tmp="$(mktemp -d)"
  curl --retry 3 --retry-delay 5 -fsSL \
    "https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=${arch}&image_type=jdk&os=linux&vendor=eclipse" \
    -o "$tmp/assets.json"
  read -r link sum < <(python3 -c '
import json, sys
pkg = json.load(open(sys.argv[1]))[0]["binary"]["package"]
print(pkg["link"], pkg["checksum"])
' "$tmp/assets.json")
  curl --retry 3 --retry-delay 5 -fsSL "$link" -o "$tmp/jdk.tar.gz"
  printf '%s  %s\n' "$sum" "$tmp/jdk.tar.gz" | sha256sum -c -
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
  if ! getent group ubuntu >/dev/null; then
    echo "group ubuntu does not exist" >&2
    exit 1
  fi
  local raw merged
  raw="$(sudo cat /etc/docker/daemon.json 2>/dev/null || true)"
  merged="$(printf '%s' "$raw" | python3 -c '
import json, sys
raw = sys.stdin.read()
if raw.strip():
    cfg = json.loads(raw)
    if not isinstance(cfg, dict):
        raise SystemExit("daemon.json must be a JSON object")
else:
    cfg = {}
cfg["group"] = "ubuntu"
json.dump(cfg, sys.stdout, indent=2)
sys.stdout.write("\n")
')"
  printf '%s' "$merged" | sudo tee /etc/docker/daemon.json >/dev/null
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

gradle_version="$(sed -n 's/.*gradle-\([0-9][0-9.]*\)-bin\.zip.*/\1/p' "$ROOT/gradle/wrapper/gradle-wrapper.properties" | head -1)"
if [ -z "$gradle_version" ]; then
  echo "Gradle version is missing from gradle/wrapper/gradle-wrapper.properties" >&2
  exit 1
fi
echo "Resolving Gradle ${gradle_version} and compiling"
./gradlew --no-daemon testClasses :dispatch-api:bootJar

echo "install done"
