#!/usr/bin/env bash
set -euo pipefail
echo 'The smoke flow creates isolated seed data through public APIs.'
exec "$(dirname "$0")/smoke-api.sh"
