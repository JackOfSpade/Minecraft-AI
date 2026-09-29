#!/usr/bin/env bash
# Runs the Baritone source generator without Gradle (Gradle's generateBaritoneSources task runs the same program).
#   tools/baritone/apply.sh                      generate build/baritone-src from third_party/baritone (default command)
#   tools/baritone/apply.sh generate --keep-repo same, but keep a git repo with one commit per patch (authoring)
#   tools/baritone/apply.sh export               rewrite tools/baritone/patches from the commits in that repo
#   tools/baritone/apply.sh verify               check third_party/baritone is byte-identical to upstream
#   tools/baritone/apply.sh report --upstream DIR --out DIR [--3way]   try the series on another Baritone version
# Needs a JDK 21+ (JAVA_HOME or PATH) and git. Fails loudly, naming the patch, if a patch does not apply.
set -eu
cd "$(dirname "$0")/../.."
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then JAVA="$JAVA_HOME/bin/java"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java.exe" ]; then JAVA="$JAVA_HOME/bin/java.exe"
else JAVA=java; fi
exec "$JAVA" tools/baritone/BaritoneSource.java "$@"
