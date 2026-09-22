#!/bin/sh
set -eu
if [ "${GITHUB_SYNC_ENABLED:-false}" = true ]; then
    GITHUB_TOKEN=$(cat /run/secrets/github_token)
    export GITHUB_TOKEN
fi
# Dedicated bind-mounted flock survives no process exit. Only its holder can
# recover the app's stale crash marker; another container cannot open this queue.
exec flock --exclusive --nonblock --no-fork /locks/server.lock sh -c '
    rm -f /data/queue/instance.lock
    exec node --import tsx src/server/main.ts
'
