#!/bin/sh
# Writes the nginx password file from the same settings the services use.
# {PLAIN} keeps the demo simple; the file is readable only by the nginx user.
set -eu
printf '%s:{PLAIN}%s\n' "${TRADE_SECURITY_USERNAME:-admin}" "${TRADE_SECURITY_PASSWORD:-admin@2026!}" > /etc/nginx/.htpasswd
chown nginx:nginx /etc/nginx/.htpasswd
chmod 600 /etc/nginx/.htpasswd
