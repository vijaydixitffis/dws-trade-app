#!/bin/sh
# Writes the nginx password file from the same settings the services use. It runs as the
# unprivileged nginx user, so the file goes to /tmp. {PLAIN} keeps the demo simple.
set -eu
umask 077
printf '%s:{PLAIN}%s\n' "${TRADE_SECURITY_USERNAME:-admin}" "${TRADE_SECURITY_PASSWORD:-admin@2026!}" > /tmp/.htpasswd
