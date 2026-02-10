#!/bin/sh
set -eu

if [ ! -d /qortal ]; then
    mkdir -p /qortal || true
fi

if [ ! -w /qortal ]; then
    echo "ERROR: /qortal is not writable by uid:gid $(id -u):$(id -g)." >&2
    echo "ERROR: Ensure host bind path ownership/permissions allow writes (e.g. chown/chmod on qortal/data)." >&2
    ls -ld /qortal >&2 || true
    exit 70
fi

if [ ! -f /qortal/settings.json ]; then
    printf '{}\n' > /qortal/settings.json
fi

if [ "$#" -eq 0 ]; then
    set -- -Djava.net.preferIPv4Stack=false -jar /usr/local/qortal/qortal.jar
fi

exec java "$@"
