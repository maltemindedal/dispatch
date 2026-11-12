#!/usr/bin/env bash
# Replays the per-file rules over another checkout of this repo, such as a worktree of the commit
# before a past fix. It answers "would this rule have caught that mistake".
#
#   dispatch-lint/replay.sh <checkout>
#
# One plain javac run compiles every source set of the checkout together, against the checkout's
# own dependencies, with the plugin from this tree. A Gradle build of the checkout would stop at
# the first module with a finding and hide the rest. Prints each finding as path:line: [rule] text,
# then the javac error count on stderr. More errors than findings means some code did not compile
# and may hide findings.
set -euo pipefail
here=$(cd "$(dirname "$0")/.." && pwd)
tree=$(cd "$1" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

(cd "$here" && ./gradlew -q :dispatch-lint:jar)
cat > "$work/classpath.gradle" <<'GRADLE'
allprojects {
    tasks.register('replayClasspath') {
        doLast {
            project.findProperty('sourceSets')?.each { set ->
                set.compileClasspath.files.findAll { it.name.endsWith('.jar') && it.exists() }.each { println "JAR ${it}" }
            }
        }
    }
}
GRADLE
classpath=$(cd "$tree" && ./gradlew -q --init-script "$work/classpath.gradle" replayClasspath \
    | sed -n 's/^JAR //p' | sort -u | paste -sd: -)
find "$tree" -path '*/src/*' -name '*.java' ! -name module-info.java ! -path '*/dispatch-lint/*' > "$work/sources"

javac -d "$work/classes" -proc:none -Xmaxerrs 10000 -cp "$classpath" \
    -processorpath "$(ls "$here"/dispatch-lint/build/libs/*.jar)" \
    "-Xplugin:DispatchLint $tree $work/replay.idx" @"$work/sources" > "$work/javac.log" 2>&1 || true
sed -n "s#^$tree/\(.*\): error: \(\[[a-z-]*\] .*\)#\1: \2#p" "$work/javac.log" | sort
echo "$(grep -c ': error: ' "$work/javac.log") javac errors, the findings above included" >&2
