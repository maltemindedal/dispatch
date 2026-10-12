#!/usr/bin/env bash
# Drive dispatch's REST API the way an operator with curl does and keep the evidence.
# Usage: verify.sh <doctor|core-test|build|launch|submit|lifecycle|stop|cleanup|all>
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(git -C "$HERE" rev-parse --show-toplevel)"
RUN_ID="${DISPATCH_VERIFY_RUN:-$(date +%Y%m%d-%H%M%S)}"
ART="${DISPATCH_VERIFY_ARTIFACTS:-/tmp/dispatch-verify/$RUN_ID}"
mkdir -p "$ART"
cd "$ROOT" || exit 1

FAILED=0
SKIPPED=0

run() {
  local name="$1"; shift
  printf '%q ' "$@" > "$ART/$name.cmd"; echo >> "$ART/$name.cmd"
  "$@" > "$ART/$name.stdout" 2> "$ART/$name.stderr"
  local rc=$?
  echo "$rc" > "$ART/$name.exit"
  echo "[$name] exit=$rc  ($ART/$name.*)"
  return "$rc"
}

expect() {
  local desc="$1" file="$2" pattern="$3"
  if grep -qE -- "$pattern" "$file"; then echo "  PASS $desc"; else echo "  FAIL $desc (no /$pattern/ in $file)"; FAILED=1; fi
}

base() { echo "http://127.0.0.1:$(cat "$ART/app.port")"; }

# http <name> <method> <path> [json-body]: status on line 1 of <name>.stdout, body in <name>.body
http() {
  local name="$1" method="$2" path="$3" body="${4:-}"
  if [ -n "$body" ]; then
    run "$name" curl -sS -X "$method" -H 'Content-Type: application/json' -d "$body" -o "$ART/$name.body" -w '%{http_code}\n' "$(base)$path"
  else
    run "$name" curl -sS -X "$method" -o "$ART/$name.body" -w '%{http_code}\n' "$(base)$path"
  fi
}

json() { python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(eval(sys.argv[2], {}, {"d": d}))' "$@"; }

docker_ok() { command -v docker >/dev/null && docker info >/dev/null 2>&1; }

doctor() {
  echo "artifacts: $ART"
  echo "repo: $ROOT @ $(git rev-parse --short HEAD) ($(git rev-parse --abbrev-ref HEAD))"
  command -v java >/dev/null || { echo "  FAIL java not on PATH (JDK 21 needed; set JAVA_HOME and PATH)"; return 1; }
  local v; v="$(java -version 2>&1 | head -1)"; echo "java: $v"
  [[ "$v" =~ \"(2[1-9]|[3-9][0-9])[.\"] ]] && echo "  PASS JDK 21 or newer" || { echo "  FAIL JDK older than 21"; return 1; }
  for t in curl python3; do command -v $t >/dev/null && echo "  PASS $t present" || { echo "  FAIL $t missing"; return 1; }; done
  docker_ok && echo "  PASS Docker daemon reachable (Testcontainers can run)" || echo "  WARN no Docker daemon: build will SKIP (its Postgres Testcontainers tests fail without Docker)"
  if [ -s "$ART/app.pid" ] && kill -0 "$(cat "$ART/app.pid")" 2>/dev/null; then echo "  PASS app from this run is up: pid $(cat "$ART/app.pid") at $(base)"; else echo "  INFO no app from this run is running"; fi
}

core_test() {
  run core-test ./gradlew --no-daemon :dispatch-core:test || FAILED=1
  expect "dispatch-core tests pass" "$ART/core-test.exit" "^0$"
  expect "gradle reports BUILD SUCCESSFUL" "$ART/core-test.stdout" "^BUILD SUCCESSFUL"
}

build() {
  if ! docker_ok; then echo "  SKIP build: ./gradlew build needs a Docker daemon for the Testcontainers tests in dispatch-postgres and dispatch-api"; SKIPPED=1; return; fi
  run build ./gradlew --no-daemon build || FAILED=1
  expect "./gradlew build passes (CI's whole gate)" "$ART/build.exit" "^0$"
}

launch() {
  if [ -s "$ART/app.pid" ] && kill -0 "$(cat "$ART/app.pid")" 2>/dev/null; then echo "  FAIL an app from this run is already up; run stop first"; FAILED=1; return 1; fi
  run bootjar ./gradlew --no-daemon -q :dispatch-api:bootJar || { echo "  FAIL bootJar failed"; FAILED=1; return 1; }
  local jar; jar="$(ls dispatch-api/build/libs/*.jar | grep -v plain | head -1)"
  local p; p="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')"
  echo "$p" > "$ART/app.port"
  java -jar "$jar" --server.port="$p" --server.address=127.0.0.1 > "$ART/app.log" 2>&1 &
  echo $! > "$ART/app.pid"
  local i
  for i in $(seq 1 120); do
    grep -q "Started JobQueueApplication" "$ART/app.log" 2>/dev/null && break
    kill -0 "$(cat "$ART/app.pid")" 2>/dev/null || break
    sleep 0.5
  done
  if grep -q "Started JobQueueApplication" "$ART/app.log" && [ "$(curl -s -o /dev/null -w '%{http_code}' "$(base)/stats")" = 200 ]; then
    echo "  PASS app ready at $(base) (pid $(cat "$ART/app.pid"), dev profile with H2, GET /stats 200)"
  else
    echo "  FAIL app not ready (see $ART/app.log)"; FAILED=1; return 1
  fi
}

submit() {
  [ -s "$ART/app.port" ] || { echo "  FAIL no app; run launch first"; FAILED=1; return; }
  http submit POST /jobs '{"type":"resize-image","payload":{"url":"s3://bucket/cat.png","width":800}}'
  expect "POST /jobs returns 201" "$ART/submit.stdout" "^201$"
  local id; id="$(json "$ART/submit.body" 'd["id"]')"
  echo "$id" > "$ART/submit.id"
  local i state=""
  for i in $(seq 1 40); do
    http submit-get GET "/jobs/$id" >/dev/null
    state="$(json "$ART/submit-get.body" 'd["state"]' 2>/dev/null)"
    [ "$state" = COMPLETED ] && break
    sleep 0.25
  done
  echo "$state" > "$ART/submit.final-state"
  [ "$state" = COMPLETED ] && echo "  PASS job $id was claimed and reached COMPLETED" || { echo "  FAIL job $id ended in '$state', not COMPLETED"; FAILED=1; }
  http unknown POST /jobs '{"type":"no-such-type"}'
  expect "unknown job type is refused with 422" "$ART/unknown.stdout" "^422$"
  http invalid POST /jobs '{"payload":{}}'
  expect "missing type is refused with 400" "$ART/invalid.stdout" "^400$"
  http stats GET /stats
  expect "GET /stats returns 200" "$ART/stats.stdout" "^200$"
  [ "$(json "$ART/stats.body" 'd["thisInstance"]["succeeded"] >= 1')" = True ] && echo "  PASS /stats counts the success" || { echo "  FAIL /stats thisInstance.succeeded is 0"; FAILED=1; }
}

lifecycle() {
  [ -s "$ART/app.port" ] || { echo "  FAIL no app; run launch first"; FAILED=1; return; }
  http dead-submit POST /jobs '{"type":"send-email","payload":{"subject":"no recipient"}}'
  expect "send-email without 'to' is accepted (201)" "$ART/dead-submit.stdout" "^201$"
  local id i state=""; id="$(json "$ART/dead-submit.body" 'd["id"]')"
  for i in $(seq 1 40); do
    http dead-get GET "/jobs/$id" >/dev/null
    state="$(json "$ART/dead-get.body" 'd["state"]' 2>/dev/null)"
    [ "$state" = DEAD ] && break
    sleep 0.25
  done
  [ "$state" = DEAD ] && echo "  PASS permanent failure dead-letters job $id" || { echo "  FAIL job $id ended in '$state', not DEAD"; FAILED=1; }
  http retry POST "/jobs/$id/retry"
  expect "POST /jobs/{id}/retry on a DEAD job returns 200" "$ART/retry.stdout" "^200$"
  http retry-again POST "/jobs/$(json "$ART/submit.body" 'd["id"]' )/retry"
  expect "retry of a COMPLETED job is refused with 409" "$ART/retry-again.stdout" "^409$"
  http sched POST /jobs '{"type":"resize-image","payload":{},"scheduledAt":"2099-01-01T00:00:00Z"}'
  local sid; sid="$(json "$ART/sched.body" 'd["id"]')"
  [ "$(json "$ART/sched.body" 'd["state"]')" = SCHEDULED ] && echo "  PASS future scheduledAt gives SCHEDULED" || { echo "  FAIL scheduled job is not SCHEDULED"; FAILED=1; }
  http cancel DELETE "/jobs/$sid"
  expect "DELETE of a SCHEDULED job returns 204" "$ART/cancel.stdout" "^204$"
  http cancel-get GET "/jobs/$sid"
  expect "the cancelled job is gone (404)" "$ART/cancel-get.stdout" "^404$"
}

stop() {
  [ -s "$ART/app.pid" ] || return 0
  local pid; pid="$(cat "$ART/app.pid")"
  kill "$pid" 2>/dev/null
  local i; for i in $(seq 1 100); do kill -0 "$pid" 2>/dev/null || break; sleep 0.1; done
  kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null
  rm -f "$ART/app.pid"
  echo "  stopped pid $pid"
}

cleanup() {
  stop
  echo "cleanup done; evidence kept in $ART"
}

STEP="${1:-}"
case "$STEP" in
  doctor) doctor || FAILED=1 ;;
  core-test) core_test ;;
  build) build ;;
  launch) launch ;;
  submit) submit ;;
  lifecycle) lifecycle ;;
  stop) stop ;;
  cleanup) cleanup ;;
  all)
    if doctor; then core_test; build; launch && { submit; lifecycle; }; else FAILED=1; echo "  doctor failed: not driving this checkout"; fi
    cleanup ;;
  *) sed -n '2,3p' "$0"; exit 2 ;;
esac
if [ "$FAILED" != 0 ]; then echo "RESULT: FAIL ($STEP)"; elif [ "$SKIPPED" != 0 ]; then echo "RESULT: PASS with SKIP ($STEP): skipped steps are unverified"; else echo "RESULT: PASS ($STEP)"; fi
exit "$FAILED"
