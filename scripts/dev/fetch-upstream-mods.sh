#!/usr/bin/env bash
# Downloads the two third-party mods that the wrapper's real-server GameTests load at runtime: PvP BOT 0.0.15 and
# HeroBot 1.21.11-1.4.3 (the versions the user's profile runs). They are never committed to this repository.
# usage: scripts/dev/fetch-upstream-mods.sh [dest-dir]   (default $HOME/.cache/upstream-mods; prints the dir)
set -eu
DEST="${1:-$HOME/.cache/upstream-mods}"
mkdir -p "$DEST"
fetch() { # url, file name, sha1 (checked against the jar the user's profile runs)
  if [ -f "$DEST/$2" ] && echo "$3  $DEST/$2" | sha1sum -c --quiet - 2>/dev/null; then return 0; fi
  curl -fsSL --retry 3 -o "$DEST/$2" "$1"
  echo "$3  $DEST/$2" | sha1sum -c --quiet -
}
fetch "https://cdn.modrinth.com/data/MpQFUorJ/versions/X2QD1Ent/PVP_bot-0.0.15.jar" \
  "PVP_bot-0.0.15.jar" f22a1c9fd522c622e52c4d097944fb7034a9e670
fetch "https://cdn.modrinth.com/data/wt23fpWX/versions/9ig2SGA7/herobot-1.21.11-1.4.3%2Bv260315.jar" \
  "herobot-1.21.11-1.4.3+v260315.jar" 17aba207e6bd906cae58b1c804f455955a9036b5
echo "$DEST"
