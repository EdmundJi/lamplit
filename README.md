# Growth Platform

`更好的自己` is a Vue single-page application backed by a Spring Boot modular monolith. MySQL is the authoritative data store, Redis provides disposable acceleration, and MinIO supplies local S3-compatible object storage.

## Prerequisites

- Java 21
- Docker with Docker Compose
- Node.js 20.19 or newer
- pnpm 11

## Local Setup

Create an ignored local environment file from `.env.example` and replace the example values before using shared or production-like environments.

```bash
cp .env.example .env.local
docker compose --env-file .env.local -f deploy/compose.yaml up -d
```

Run the backend with the local profile:

```bash
cd backend
set -a
source ../.env.local
set +a
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Run the frontend:

```bash
cd frontend
pnpm install
pnpm dev
```

## First Administrator

The app does not expose public administrator registration. To create the first admin, set these environment variables before starting the backend when no active `ADMIN` exists:

```bash
ADMIN_BOOTSTRAP_EMAIL=admin@example.com
ADMIN_BOOTSTRAP_PASSWORD='replace-with-a-strong-password'
ADMIN_BOOTSTRAP_DISPLAY_NAME=系统管理员
ADMIN_BOOTSTRAP_TIMEZONE=Asia/Shanghai
ADMIN_BOOTSTRAP_MFA_SECRET=
```

If `ADMIN_BOOTSTRAP_MFA_SECRET` is empty, the backend generates a TOTP secret and prints it once in the server logs. Add that secret to an authenticator app, sign in, complete MFA, then remove the bootstrap variables.

## Verification

```bash
cd backend && ./mvnw test
cd frontend && pnpm lint && pnpm test --run && pnpm build
docker compose --env-file .env.local -f deploy/compose.yaml config
```

Run the complete local data-flow gate after the backend and frontend are available:

```bash
./scripts/verify-global-flow.sh
```

The gate verifies authentication cookies and CSRF, consent persistence, goal/plan/task materialization, task state transitions and reversal, AI SSE and fixed crisis replacement, idempotent suggestion adoption, ZIP export, ownership isolation, deletion cooling-off/cancellation, database invariants, Redis health, and sensitive-log scanning. Set `QWEN_PROVIDER=qwen` and provide `QWEN_API_KEY` only through the process environment for a real provider smoke; never commit the key.

The backend health endpoint is available at `http://localhost:8080/actuator/health`. Nginx is configured to expose the same endpoint and proxy `/api/v1` when used as the SPA edge server.
