#!/usr/bin/env bash
set -euo pipefail

ready() { docker info >/dev/null 2>&1; }

if ready; then
  echo "Docker daemon is already running"
  exit 0
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
exit 1
