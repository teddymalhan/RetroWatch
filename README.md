# RetroWatch

![RetroWatch System Architecture](retrowatch_architecture.png)

A nostalgic streaming platform that recreates the golden age of television with AI-matched period commercials.

RetroWatch lets you watch any YouTube video through an authentic CRT TV simulation, complete with Gemini AI-powered commercials that are matched to the content and inserted at natural break points — just like it's 1985.

## Table of Contents

- [Background](#background)
- [Install](#install)
- [Usage](#usage)
- [API](#api)
- [Maintainers](#maintainers)
- [Contributing](#contributing)
- [License](#license)

## Background

Modern streaming stripped out the communal, serendipitous texture of old TV. RetroWatch is built to bring that back: a 3D CRT television rendered in Three.js, YouTube playback embedded inside it, and an AI pipeline (Vertex AI / Gemini) that picks era-appropriate ads from your own library and splices them in at the right moments.

The project is a full-stack mono-repo: a React 19 SPA talks to a Spring Boot 4 / Java 21 backend, with Supabase (PostgreSQL + object storage) for persistence and Clerk for auth. Google Cloud Tasks drives async ad analysis, and the YouTube Data API v3 provides video metadata for break-point detection.

## Install

### Dependencies

- Node.js 20+ and pnpm
- Java 21+
- Docker and Docker Compose
- Google Cloud project with Vertex AI enabled
- Supabase project
- Clerk application

### Environment setup

Create a `.env` file in the project root:

```bash
# Clerk
VITE_CLERK_PUBLISHABLE_KEY=pk_test_...

# Supabase
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_ANON_KEY=eyJ...
SUPABASE_SERVICE_ROLE_KEY=eyJ...
SUPABASE_DB_URL=postgresql://postgres:...

# Google Cloud / Vertex AI
GCP_PROJECT_ID=your-gcp-project
GCP_LOCATION=us-central1
GEMINI_MODEL=gemini-2.0-flash-001
```

### Install

```bash
# Docker (recommended)
./run-local.sh

# Or manually
docker-compose up --build
```

## Usage

### Docker

```bash
docker-compose up --build
```

Access the app at `http://localhost:8080`.

### Kubernetes (Tilt)

```bash
tilt up
```

### Manual

```bash
# Frontend
cd frontend
pnpm install
pnpm dev        # http://localhost:5173

# Backend (separate terminal)
cd backend
mvn spring-boot:run
```

### CLI — frontend scripts

```bash
cd frontend
pnpm dev        # dev server
pnpm build      # production build
pnpm lint       # lint
```

### CLI — backend scripts

```bash
cd backend
mvn test                # run tests
mvn package             # build JAR
mvn spring-boot:run     # run locally
```

### Deploy to Google Cloud Run

```bash
gcloud builds submit --config=cloudbuild.yaml
```

## API

All routes are under `/api/protected/` and require a valid Clerk JWT.

| Endpoint | Method | Description |
|---|---|---|
| `/api/protected/ads` | GET, POST | List or upload ads |
| `/api/protected/ads/{id}/analyze` | POST | Trigger Gemini analysis on an ad |
| `/api/protected/library/history` | GET, POST | Watch history |
| `/api/protected/video/analyze` | POST | Analyze a YouTube video for break points |
| `/api/protected/match` | POST | Build an ad schedule for a video |

For full request/response shapes see the controller source in `backend/src/main/java/com/richwavelet/backend/api/`.

## Maintainers

[@teddymalhan](https://github.com/teddymalhan)

## Contributing

Questions and bug reports go to [GitHub Issues](https://github.com/teddymalhan/RetroWatch/issues).

PRs are welcome. Please open an issue first to discuss significant changes. There are no formal commit sign-off requirements at this time.

## License

[Unlicense](LICENSE) — public domain. Teddy Malhan.
