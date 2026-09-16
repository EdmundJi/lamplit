#!/usr/bin/env bash
# Regenerate the read-only static OpenWiki viewer committed under wiki-site/public.
set -euo pipefail

cd "$(dirname "$0")/.."

if ! command -v openwiki >/dev/null 2>&1; then
    echo "openwiki is required; install the repository's documented OpenWiki CLI first." >&2
    exit 1
fi

openwiki visualize openwiki --export wiki-site/public

echo "OpenWiki static site refreshed in wiki-site/public."
