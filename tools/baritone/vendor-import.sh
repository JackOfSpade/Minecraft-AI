#!/usr/bin/env bash
# Imports a Baritone version into a vendor directory, byte-identical to upstream, and writes MANIFEST.txt.
#   tools/baritone/vendor-import.sh <baritone git clone> <commit-ish> [dest=third_party/baritone]
# Blobs are copied with `git cat-file`, so line endings are exactly what upstream committed (LF) no matter what
# core.autocrlf the clone uses. Afterwards update third_party/baritone/UPSTREAM.md (tag, commit, tree) by hand.
set -eu
CLONE="$1"; REV="$2"; DEST="${3:-$(cd "$(dirname "$0")/../.." && pwd)/third_party/baritone}"
PATHS="src/api src/main src/launch src/test LICENSE README.md"
COMMIT="$(git -C "$CLONE" rev-parse --verify "$REV^{commit}")"
mkdir -p "$DEST"
find "$DEST" -mindepth 1 -maxdepth 1 ! -name UPSTREAM.md -exec rm -rf {} +
: > "$DEST/MANIFEST.txt.tmp"
# shellcheck disable=SC2086
git -C "$CLONE" ls-tree -r "$COMMIT" -- $PATHS | while read -r mode type sha path; do
  [ "$type" = blob ] || continue
  mkdir -p "$DEST/$(dirname "$path")"
  git -C "$CLONE" cat-file blob "$sha" > "$DEST/$path"
  printf '%s  %s\n' "$sha" "$path" >> "$DEST/MANIFEST.txt.tmp"
done
sort -k2 "$DEST/MANIFEST.txt.tmp" > "$DEST/MANIFEST.txt"; rm "$DEST/MANIFEST.txt.tmp"
echo "imported $(wc -l < "$DEST/MANIFEST.txt") files of $COMMIT into $DEST"
