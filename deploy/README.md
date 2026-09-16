# Running RetroWatch on your own VPS

RetroWatch is a single Spring Boot application that serves the built React SPA and the
API from one port, plus PostgreSQL and object storage. `docker-compose.yml` in the
repository root brings up the whole stack on any Docker host — a $5 VPS is enough to
run the app; give it more RAM and disk if you plan to process long videos.

## What runs

| Service | Purpose |
|---|---|
| `app` | Spring Boot 4 / Java 21 + built SPA, FFmpeg for the video pipeline |
| `postgres` | Application database (jobs, uploads, analysis, watch history) |
| `minio` | S3-compatible object storage for source videos, ads and processed videos |
| `minio-init` | One-shot job that creates the `videos`, `ads` and `processed-videos` buckets |
| `caddy` | Optional reverse proxy with automatic Let's Encrypt TLS (`--profile tls`) |

Nothing in the stack calls Google Cloud. Gemini is the only outbound dependency and it
is optional at boot — set `GEMINI_BASE_URL` to a self-hosted gateway if you want to keep
inference on your own network too.

## 1. Prepare the server

Any Linux VPS with Docker Engine 24+ and the Compose plugin:

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker "$USER"   # log out and back in
```

A 2 GB / 2 vCPU instance with ~20 GB free disk will run the app comfortably. Video
processing is the heavy part: FFmpeg transcode work scales with CPU, and the compose
stack keeps uploads and intermediates on named volumes.

## 2. Get the code

```bash
git clone https://github.com/teddymalhan/RetroWatch.git
cd RetroWatch
```

## 3. Configure

```bash
cp .env.example .env
$EDITOR .env
```

Generate the secrets rather than typing them:

```bash
echo "POSTGRES_PASSWORD=$(openssl rand -base64 32)"
echo "WORKER_AUTH_TOKEN=$(openssl rand -hex 32)"
echo "S3_SECRET_KEY=$(openssl rand -base64 32)"
```

Required for a first run: `POSTGRES_PASSWORD`, `WORKER_AUTH_TOKEN`, `S3_SECRET_KEY`,
`VITE_CLERK_PUBLISHABLE_KEY` (Clerk is still the identity provider) and
`GEMINI_API_KEY` if you want ad analysis.

`VITE_CLERK_PUBLISHABLE_KEY` is compiled into the frontend bundle, so changing it
requires a rebuild (`docker compose build app`).

## 4. Start it

```bash
# Plain HTTP on port 8080 — good for a LAN box or an SSH tunnel
docker compose up -d --build

# Public VPS with automatic HTTPS: point an A/AAAA record at the server first,
# then set DOMAIN and ACME_EMAIL in .env and run
docker compose --profile tls up -d --build
```

Check it:

```bash
docker compose ps
docker compose logs -f app
curl -fsS http://localhost:8080/api-docs >/dev/null && echo "API up"
```

With the `tls` profile, Caddy fetches a certificate for `DOMAIN` on first start and
renews it automatically. Ports 80 and 443 must be reachable from the internet.

## 5. Day-to-day operations

```bash
# Update to the latest code
git pull && docker compose up -d --build

# Stop everything (keeps volumes/data)
docker compose down

# Stop and delete all data (database + media)
docker compose down -v

# Database shell
docker compose exec postgres psql -U retrowatch -d retrowatch

# MinIO console, loopback only — reach it over SSH:
#   ssh -L 9001:127.0.0.1:9001 user@your-vps
# then open http://localhost:9001
```

Backups are just the volumes: `postgres-data` holds the database, `minio-data` holds
every uploaded and processed video. Compose prefixes them with the project name — find
the real names with `docker volume ls`.

```bash
VOL=$(docker volume ls -q | grep postgres-data | head -1)
docker run --rm -v "$VOL":/data -v "$PWD":/backup alpine \
  tar czf /backup/postgres-$(date +%F).tar.gz -C /data .
```

## 6. Verify the deployment

```bash
./deploy/smoke-test.sh
```

Runs 13 checks against the live stack: the HTTP surface, worker-token enforcement on
`/api/tasks/**`, the MinIO bucket layout with a write/read/delete round trip, and a
seeded `queued_job` row watched through dispatcher claim and failure recording.
Exits non-zero on the first failure.

## Troubleshooting

**App container restarts with "Failed to determine suitable jdbc url"** — `POSTGRES_*`
values are missing from `.env`, or `DATABASE_URL` was overridden with something invalid.

**Every job fails with "Worker returned HTTP 401"** — `WORKER_AUTH_TOKEN` differs
between the dispatcher and the worker. They read the same variable, so this normally
means the container was started with a stale `.env`; `docker compose up -d --force-recreate`.

**Jobs stay QUEUED** — the dispatcher polls every 5 s after a 15 s startup delay
(`QUEUE_DISPATCH_INTERVAL_MS`, `QUEUE_INITIAL_DELAY_MS`). Check `docker compose logs app`
for "Claimed ... job(s)".

**Uploads fail with a 500 and an S3 error** — MinIO is unhealthy or
`S3_ACCESS_KEY`/`S3_SECRET_KEY` do not match the credentials MinIO was started with.
Both are set from `.env`, so recreate the containers after changing them.

**Gemini errors on large videos** — `generateContent` accepts inline media up to about
20 MB (`GEMINI_INLINE_MAX_BYTES`). Longer sources need trimming or a gateway that
supports the Files API.
