# RetroWatch — status

Written 2026-09-16 ~02:40 UTC by the Photon/iMessage Hermes session, after the CLI session
(`20260916_015211_aee107`) was stopped at the user's request.

---

## 1. The migration work (CLI session, now stopped)

Main tree: `/home/ubuntu/Projects/RetroWatch`, branch `main`, working tree clean.

```
870ed95 fix(deploy): make the container build and compose stack actually run
17add8f docs: describe the self-hosted architecture and deployment
c077b88 feat(deploy): self-hosted VPS stack replacing Cloud Build and Cloud Run
c56f9b3 feat(storage): add an S3-compatible object storage backend
a7e253c test: repair the pre-existing test suite so `mvn test` is green
d1e5a01 refactor(backend): replace Google Cloud Tasks and Vertex AI with self-hosted equivalents
```

Left Google Cloud: Cloud Tasks, Vertex AI SDK, Google OIDC verification, Cloud Build, Cloud Run,
the `gcloud` credential mounts. Replacements: Postgres-backed job queue (lease + exponential
backoff), REST Gemini client with configurable base URL, shared-token worker auth, S3/MinIO
storage backend, docker-compose stack (app + Postgres + MinIO + optional Caddy TLS).

**Stack is up and healthy right now** (`docker compose ps`, host port 8080):

```
retrowatch-app-1        Up 14 minutes (healthy)   0.0.0.0:8080->8080/tcp
retrowatch-postgres-1   Up 16 minutes (healthy)
retrowatch-minio-1      Up 16 minutes (healthy)   127.0.0.1:9001->9001/tcp
```

The CLI session was stopped with SIGTERM on 2026-09-16 ~02:39 UTC; `mvn test`, the compose stack
and the session store were left in a consistent state (its last commit is `870ed95`).

## 2. AI path on the OpenCode key (Photon session, in a separate worktree)

Branch `feat/opencode-ai-provider` in git worktree `/home/ubuntu/Projects/RetroWatch-ai`.
**Nothing in the main tree was touched** (the CLI session was still building there).

What changed on that branch:

| Kind | File |
|---|---|
| new | `backend/.../service/AiTextClient.java` — one interface for every AI call |
| new | `backend/.../service/OpenAiCompatibleClient.java` — OpenAI `/chat/completions` provider |
| new | `backend/src/test/.../OpenAiCompatibleClientTest.java` — 8 unit tests |
| new | `backend/src/test/.../OpenAiCompatibleClientLiveTest.java` — env-gated live probe |
| edit | `GeminiClient.java` — implements the interface, stays the default |
| edit | `AdAnalysisService`, `GeminiService`, `YouTubeAnalysisService` — inject the interface |
| edit | `backend/src/main/resources/application.properties` — `ai.provider`, `ai.openai.*` |
| edit | `README.md` — "AI provider" section |

Evidence, real runs in the `maven:3.9-eclipse-temurin-21` container:

```
[live probe] model output: {"answer": "AI_PATH_OK"}
[live probe] prompt+parse path -> categories=[entertainment]
  topics=[Rick Astley, Never Gonna Give You Up, official music video, 1980s pop music, Rickroll meme]
  sentiment=positive adBreaks=1
AI tests:  Tests run: 11, Failures: 0, Errors: 0, Skipped: 1
Full suite (live probes skipped): Tests run: 121, Failures: 0, Errors: 0, Skipped: 3 — BUILD SUCCESS
```

(Baseline before this change was 110 tests; the branch adds 11. Committed as `037fbeb`.)

The skipped test is the YouTube-metadata one (see open items). Endpoint used:
`https://opencode.ai/zen/go/v1`, model `deepseek-v4-flash`, header `x-opencode-session` —
without that header OpenCode Go answers 400 `MissingSessionID`.

### Caveats (do not lose these)

- OpenCode Go is **OpenAI chat-completions only**. It has no Gemini `generateContent` path
  (`/v1beta/models/...:generateContent` → 404) and cannot carry inline video. Ad-video analysis
  therefore still needs `AI_PROVIDER=gemini`; with `AI_PROVIDER=openai` a video call fails loudly
  by design instead of analysing nothing.
- OpenCode Go is a **coding-agent subscription** ("traffic monitored for abuse"). Fine for
  testing the AI path; not what you would ship as the app's production provider.
- `ai.provider` defaults to `gemini`, so nothing changes until you flip it.

### Turning it on

In the app's `.env`:

```bash
AI_PROVIDER=openai
AI_OPENAI_BASE_URL=https://opencode.ai/zen/go/v1
AI_OPENAI_API_KEY=<key>          # or export OPENCODE_GO_API_KEY
AI_OPENAI_MODEL=deepseek-v4-flash
```

Merging the branch:

```bash
cd /home/ubuntu/Projects/RetroWatch
git merge feat/opencode-ai-provider
# worktree cleanup afterwards: git worktree remove /home/ubuntu/Projects/RetroWatch-ai
```

## 3. Still open

- **Clerk credentials missing** — `VITE_CLERK_PUBLISHABLE_KEY` is a test key and
  `CLERK_WEBHOOK_SECRET` is unset, so no browser click-through of `/api/protected/**` routes
  (sign-in, ad upload, matching) has been exercised. Reaching the *publishable key* is not
  enough; the webhook secret is also needed.
- **`YOUTUBE_API_KEY` is empty** in `RetroWatch/.env`, so YouTube metadata fetch and the
  ad-break analysis that depends on it cannot run. That is why one live test was skipped.
- **Ad-video analysis has not been run against any real model** by either session — it needs a
  Gemini-protocol key/gateway (or a video-capable gateway) plus an uploaded ad.
- The full Maven suite on the branch is green (121 tests, 3 skipped — the three live probes).
  Nothing has been pushed anywhere; all commits are local.
