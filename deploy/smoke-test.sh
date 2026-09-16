#!/usr/bin/env bash
#
# End-to-end smoke test for a running RetroWatch stack.
#
#   ./deploy/smoke-test.sh
#   BASE_URL=https://retrowatch.example.com ./deploy/smoke-test.sh
#
# Run it after `docker compose up -d` from the repository root. Reads .env for the
# secrets the stack was started with. Exits non-zero on the first failed check.
#
# It covers the parts of the stack that need no browser session: the HTTP surface,
# the worker authentication that replaced Google OIDC, the PostgreSQL-backed job
# queue (seed a row, watch the dispatcher claim and retry it), and the MinIO bucket
# layout. Anything behind Clerk still needs a real signed-in session.

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
COMPOSE="docker compose"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-120}"

failures=0

pass() { printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; failures=$((failures + 1)); }
info() { printf '\n\033[1m%s\033[0m\n' "$1"; }

if [[ ! -f .env ]]; then
  echo "No .env in $(pwd). Run this from the repository root after 'cp .env.example .env'." >&2
  exit 2
fi

# shellcheck disable=SC1091
set -a; source .env; set +a

for required in WORKER_AUTH_TOKEN POSTGRES_USER POSTGRES_DB S3_ACCESS_KEY S3_SECRET_KEY; do
  if [[ -z "${!required:-}" ]]; then
    echo "$required is missing from .env" >&2
    exit 2
  fi
done

# ── HTTP surface ──────────────────────────────────────────────────────────────
info "HTTP surface ($BASE_URL)"

if curl -fsS --max-time 10 "$BASE_URL/api-docs" >/dev/null; then
  pass "GET /api-docs responds"
else
  fail "GET /api-docs did not respond with 2xx"
fi

if curl -fsS --max-time 10 "$BASE_URL/" | grep -q 'id="root"'; then
  pass "GET / returns the built SPA"
else
  fail "GET / did not return the SPA shell"
fi

if curl -fsS --max-time 10 "$BASE_URL/" | grep -q '/assets/'; then
  pass "SPA references built assets"
else
  fail "SPA shell has no built asset references"
fi

# ── Worker authentication (replaced Cloud Tasks OIDC) ─────────────────────────
info "Worker authentication"

worker_status=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 \
  -X POST "$BASE_URL/api/tasks/process-video-worker" \
  -H 'Content-Type: application/json' \
  -d '{"jobId":"smoke-noauth","userId":"smoke","videoId":1,"adIds":[],"shaderStyle":"CRT"}')

if [[ "$worker_status" == "401" ]]; then
  pass "worker rejects a request with no token (401)"
else
  fail "worker returned $worker_status for a request with no token, expected 401"
fi

worker_status=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 \
  -X POST "$BASE_URL/api/tasks/process-video-worker" \
  -H 'Content-Type: application/json' \
  -H 'X-Worker-Token: definitely-the-wrong-token' \
  -d '{"jobId":"smoke-badauth","userId":"smoke","videoId":1,"adIds":[],"shaderStyle":"CRT"}')

if [[ "$worker_status" == "401" ]]; then
  pass "worker rejects a wrong token (401)"
else
  fail "worker returned $worker_status for a wrong token, expected 401"
fi

worker_body=$(curl -s --max-time 20 \
  -X POST "$BASE_URL/api/tasks/process-video-worker" \
  -H 'Content-Type: application/json' \
  -H "X-Worker-Token: $WORKER_AUTH_TOKEN" \
  -d '{"jobId":"smoke-goodauth","userId":"smoke","videoId":999999,"adIds":[],"shaderStyle":"CRT"}')

if grep -qi "video not found" <<<"$worker_body"; then
  pass "worker accepts the shared token and reaches the database"
else
  fail "worker did not report a missing video, got: $(head -c 160 <<<"$worker_body")"
fi

# ── Object storage ────────────────────────────────────────────────────────────
info "Object storage (MinIO)"

buckets=$($COMPOSE run --rm --no-deps -T --entrypoint /bin/sh minio-init -c \
  "mc alias set local http://minio:9000 '$S3_ACCESS_KEY' '$S3_SECRET_KEY' >/dev/null 2>&1; mc ls local 2>/dev/null" 2>/dev/null)

for bucket in videos ads processed-videos; do
  # `mc ls` lines look like: [2026-09-16 02:23:42 UTC]     0B videos/
  if grep -qE "[[:space:]]${bucket}/[[:space:]]*$" <<<"$buckets"; then
    pass "bucket '$bucket' exists"
  else
    fail "bucket '$bucket' is missing"
  fi
done

smoke_key="smoke/roundtrip-$$.txt"
roundtrip_output=$($COMPOSE run --rm --no-deps -T --entrypoint /bin/sh minio-init -c \
  "mc alias set local http://minio:9000 '$S3_ACCESS_KEY' '$S3_SECRET_KEY' >/dev/null 2>&1; \
   printf 'retrowatch-smoke' | mc pipe local/videos/$smoke_key >/dev/null 2>&1; \
   mc cat local/videos/$smoke_key 2>/dev/null; \
   mc rm --force local/videos/$smoke_key >/dev/null 2>&1" 2>/dev/null)

# Compare after the fact rather than piping into `grep -q`: under `set -o pipefail`
# grep's early exit would SIGPIPE `mc` and report a false failure.
if [[ "$roundtrip_output" == "retrowatch-smoke" ]]; then
  pass "write/read/delete round trip against MinIO"
else
  fail "write/read/delete round trip against MinIO returned '${roundtrip_output}'"
fi

# ── Job queue: seed a row, watch the dispatcher claim it ──────────────────────
info "Job queue (PostgreSQL + JobDispatcher)"

job_id="smoke-$(date +%s)-$$"

if $COMPOSE exec -T postgres psql -q -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c \
  "INSERT INTO queued_job (job_id, user_id, video_id, ad_ids, shader_style, status, attempts, max_attempts, created_at, updated_at)
   VALUES ('$job_id', 'smoke-user', 999999, '', 'CRT', 'PENDING', 0, 3, now(), now());" >/dev/null 2>&1; then
  pass "inserted queued_job $job_id"
else
  fail "could not insert a queued_job row"
fi

claimed=""
deadline=$((SECONDS + TIMEOUT_SECONDS))
while (( SECONDS < deadline )); do
  row=$($COMPOSE exec -T postgres psql -q -t -A -F'|' -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c \
    "SELECT status, attempts FROM queued_job WHERE job_id = '$job_id';" 2>/dev/null | tr -d '\r')
  if [[ -n "$row" && "${row#*|}" != "0" ]]; then
    claimed="$row"
    break
  fi
  sleep 3
done

if [[ -n "$claimed" ]]; then
  pass "dispatcher claimed the job (status=${claimed%%|*}, attempts=${claimed##*|})"
else
  fail "dispatcher never claimed the job within ${TIMEOUT_SECONDS}s"
fi

# The worker cannot succeed for a non-existent video, so the job must have recorded
# an error rather than silently vanishing.
last_error=$($COMPOSE exec -T postgres psql -q -t -A -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c \
  "SELECT coalesce(last_error, '') FROM queued_job WHERE job_id = '$job_id';" 2>/dev/null | tr -d '\r')

if grep -qi "video not found" <<<"$last_error"; then
  pass "job failure was recorded with the worker's error"
else
  fail "no worker error recorded, got: '$last_error'"
fi

# ── Summary ───────────────────────────────────────────────────────────────────
printf '\n'
if (( failures == 0 )); then
  printf '\033[32mAll smoke checks passed.\033[0m\n'
  exit 0
fi

printf '\033[31m%d smoke check(s) failed.\033[0m\n' "$failures"
exit 1
