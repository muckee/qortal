#!/bin/sh
set -eu

if [ ! -f /qortal/settings.json ]; then
    printf '{}\n' > /qortal/settings.json
fi

if [ "$#" -eq 0 ]; then
    set -- -Djava.net.preferIPv4Stack=false -jar /usr/local/qortal/qortal.jar
fi

exec java "$@"
