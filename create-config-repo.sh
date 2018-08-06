#!/usr/bin/env bash
# https://disconnected.systems/blog/another-bash-strict-mode/
set -euo pipefail
trap 's=$?; echo "$0: Error on line "$LINENO": $BASH_COMMAND"; exit $s' ERR

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null && pwd )"

if [ ! $# -eq 1 ]; then
    echo >&2 "Usage: $0 <destination_directory>"
    exit 1
fi

cp -r "$DIR/example-config-repo" "$1"
tree "$1"
