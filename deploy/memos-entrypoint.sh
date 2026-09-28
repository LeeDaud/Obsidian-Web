#!/bin/sh
set -eu
MEMOS_ECHO_BRIDGE_TOKEN=$(cat /run/secrets/memos_bridge_token_memos)
export MEMOS_ECHO_BRIDGE_TOKEN
exec /usr/local/bin/memos
