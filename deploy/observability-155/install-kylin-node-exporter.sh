#!/usr/bin/env bash
# Install node_exporter (loong64) as a systemd service on the Kylin host, and open a
# persistent reverse SSH tunnel so the 155 Prometheus can scrape it (Kylin :9100 -> 155 :9101).
#
# Prereq: scp the cross-compiled binary first:
#   scp -P 60022 node_exporter-loong64 vmuser@155.117.82.8:/tmp/node_exporter
# Run on the Kylin host (reads sudo password from $SUDO_PW), e.g.:
#   SUDO_PW=*** BACKEND_HOST=155.117.82.8 BACKEND_PW=*** bash install-kylin-node-exporter.sh
set -euo pipefail
SUDO() { echo "${SUDO_PW}" | sudo -S "$@"; }

# 1) node_exporter service
SUDO install -m 0755 /tmp/node_exporter /usr/local/bin/node_exporter
SUDO useradd --system --no-create-home --shell /usr/sbin/nologin node_exporter 2>/dev/null || true
SUDO bash -c 'cat > /etc/systemd/system/node_exporter.service' <<'UNIT'
[Unit]
Description=Prometheus Node Exporter
After=network-online.target
Wants=network-online.target

[Service]
User=node_exporter
Group=node_exporter
ExecStart=/usr/local/bin/node_exporter --web.listen-address=127.0.0.1:9100
Restart=on-failure
RestartSec=5
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true

[Install]
WantedBy=multi-user.target
UNIT
SUDO systemctl daemon-reload
SUDO systemctl enable --now node_exporter
sleep 1
echo "== node_exporter local check =="
curl -s http://127.0.0.1:9100/metrics | grep -E '^node_(cpu|memory)' | head -3

# 2) Reverse tunnel to the 155 control-plane host (autossh keeps it alive).
# Exposes Kylin:9100 as 127.0.0.1:9101 on the 155 host for Prometheus to scrape.
if command -v autossh >/dev/null 2>&1 && [ -n "${BACKEND_HOST:-}" ]; then
  SUDO bash -c "cat > /etc/systemd/system/node-exporter-tunnel.service" <<UNIT
[Unit]
Description=Reverse SSH tunnel: Kylin node_exporter -> ${BACKEND_HOST}:9101
After=network-online.target node_exporter.service
Wants=network-online.target

[Service]
Environment=AUTOSSH_GATETIME=0
ExecStart=/usr/bin/autossh -M 0 -N -o StrictHostKeyChecking=no -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -R 9101:127.0.0.1:9100 root@${BACKEND_HOST}
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
UNIT
  SUDO systemctl daemon-reload
  SUDO systemctl enable --now node-exporter-tunnel || echo "tunnel needs SSH key/agent auth to ${BACKEND_HOST}; see README"
else
  echo "autossh not present or BACKEND_HOST unset; node_exporter is running locally on :9100."
  echo "Set up an SSH key to root@<backend> and a -R 9101:127.0.0.1:9100 reverse tunnel for Prometheus."
fi
