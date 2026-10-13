#!/usr/bin/env bash
set -euo pipefail

ready() { docker info >/dev/null 2>&1; }

if ready; then
  echo "Docker daemon is already running"
  exit 0
fi

# docker info also fails when dockerd is up and this user cannot open the socket.
if pgrep -x dockerd >/dev/null 2>&1 || sudo docker info >/dev/null 2>&1; then
  echo "dockerd is running but this user cannot open the socket" >&2
  exit 1
fi

sudo rm -f /var/run/docker.pid /var/run/docker.sock
sudo rm -f /run/containerd/containerd.pid /var/run/containerd/containerd.pid
sudo mkdir -p /var/lib/docker /run/containerd

# No systemd in this VM, so start dockerd in its own session and wait.
sudo setsid dockerd >/tmp/dockerd.log 2>&1 < /dev/null &

for _ in $(seq 1 90); do
  if ready; then
    echo "Docker daemon is ready"
    exit 0
  fi
  sleep 1
done

echo "Docker daemon did not become ready" >&2
sudo tail -n 120 /tmp/dockerd.log >&2 || true
if [ -f /var/run/docker.pid ]; then
  sudo kill "$(cat /var/run/docker.pid)" 2>/dev/null || true
fi
sudo pkill -x dockerd 2>/dev/null || true
sudo pkill -x containerd 2>/dev/null || true
for _ in $(seq 1 30); do
  if ! pgrep -x dockerd >/dev/null 2>&1 && ! pgrep -x containerd >/dev/null 2>&1; then
    exit 1
  fi
  sleep 0.5
done
exit 1
