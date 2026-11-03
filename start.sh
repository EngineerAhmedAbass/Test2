#!/usr/bin/env sh
cd "$(dirname "$0")"
command -v node >/dev/null 2>&1 || { echo "لازم تسطب Node.js الأول من https://nodejs.org"; exit 1; }
( sleep 1; (xdg-open http://localhost:3000 || open http://localhost:3000) >/dev/null 2>&1 ) &
node server.js
