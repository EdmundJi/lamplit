[简体中文](README.zh-CN.md) · **English**

# lamplit

A web town you can leave open all day. You move onto a small street that already has
residents and a past, share part of your schedule with one autonomous figure, and shape
its life through explicit arrangements or the occasional passing thought.

The entry point is `/town`: move onto a street with four residents already living on it,
pick a real to-do to focus alongside, or let the figure live by its own rhythm. The world
is stored on the server and survives a refresh. **Finishing a focus session never completes
your real task for you** — that stays your call.

Residents keep their own needs, plans, relationships and source-backed memories. Real
encounters lead to negotiation, collaboration and new small wishes. The model may adjust
plans and propose new projects inside the rules; when the model is unavailable, residents
go on living by the plans they already have — **they do not say a stand-in line**, because
a canned sentence gets remembered as something they actually said.

- [Progress](progress.md) — what was done recently, what is next.
- [01 · The town we want](docs/01-requirements.md) · [02 · How the code is organised](docs/02-modules.md)
- [Doc index](docs/README.md) — everything by number. [agent.md](agent.md) holds the short development conventions.
- [References](references/README.md) — the two research papers and links to their official implementations.
- [06 · How a society grows, and how we would know](docs/06-society.md) — the lab notebook for the emergent-norms work, including the hypotheses that got falsified.

Built as a modular monolith on Vue and Spring Boot. MySQL is the system of record, Redis is
a discardable accelerator, MinIO provides local S3-compatible object storage.

## Requirements

- Java 21
- Docker (with Docker Compose)
- Node.js 22.13 or newer (the pnpm 11.9 pinned in `packageManager` requires it; Node 20 crashes outright)
- pnpm 11

## Running locally

Create your ignored local env file from the example, and replace the sample values before
using any shared or production-like environment.

```bash
cp .env.example .env.local
docker compose --env-file .env.local -f deploy/compose.yaml up -d
```

Backend, with the local profile:

```bash
cd backend
set -a
source ../.env.local
set +a
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Frontend:

```bash
cd frontend
pnpm install
pnpm dev
```

### Art assets are not in this repository

The town's pixel art comes from four **purchased** LimeZu packs. Their licence permits
commercial and open-source use but **forbids redistribution**, so neither the generated
atlases nor the original archives are committed here.

To see the visuals you need to buy the packs yourself, put
`modernexteriors-win.zip`, `moderninteriors-win.zip`, `Modern_Farm_v1.2.zip` and
`Modern_Office_Revamped_v1.2.zip` into `tmp/`, then generate the atlases. The scripts need
Pillow; if your default `python3` lacks it, `uv run --with pillow python <script>` avoids
polluting the global environment:

```bash
python3 scripts/build-town-assets.py
python3 scripts/build-companion-assets.py
```

Everything else runs without them. See [asset rules](frontend/public/assets/town/README.md).

## Shared development server

When you want this machine to act as a development server other people can connect to,
use this instead — both ends run in containers with the source mounted from the host, so
collaborators need neither Java 21 nor pnpm locally.

```bash
scripts/dev-server.sh up      # first run downloads Maven / pnpm deps; slow once
scripts/dev-server.sh urls    # print the addresses to hand out
scripts/dev-server.sh logs    # follow logs, or `logs backend`
```

This and "Running locally" above are **mutually exclusive**: they share data volumes, so
the data carries over, but the ports collide. Stop your local `pnpm dev` and
`mvnw spring-boot:run` before switching.

Full notes (ports, remote debugging, resetting data, what collaborators need to know) are in
[the shared development environment doc](docs/archive/2026-09-07/开发服务器.md).

## First administrator

There is no public admin sign-up. To create the first administrator while no active `ADMIN`
exists, set these before starting the backend:

```bash
ADMIN_BOOTSTRAP_EMAIL=admin@example.com
ADMIN_BOOTSTRAP_PASSWORD='replace-with-a-strong-password'
ADMIN_BOOTSTRAP_DISPLAY_NAME=Administrator
ADMIN_BOOTSTRAP_TIMEZONE=Asia/Shanghai
ADMIN_BOOTSTRAP_MFA_SECRET=
```

If `ADMIN_BOOTSTRAP_MFA_SECRET` is empty the backend generates a TOTP secret and prints it
to the server log exactly once. Add it to an authenticator app, sign in, complete MFA, then
remove the bootstrap variables.

## Verifying

```bash
cd backend && ./mvnw test
cd frontend && pnpm lint && pnpm test --run && pnpm build
docker compose --env-file .env.local -f deploy/compose.yaml config
```

With both ends up, run the full local data-flow gate:

```bash
./scripts/verify-global-flow.sh
```

It checks auth cookies and CSRF, consent persistence, goal/week-plan/task materialisation,
task state transitions and undo, AI SSE with its fixed crisis fallback, idempotent suggestion
adoption, ZIP export, ownership isolation, the deletion cooling-off period and its
cancellation, database invariants, Redis health, and a sensitive-log scan.

Automated acceptance defaults to `QWEN_PROVIDER=mock` and never reaches an external model.
To make the product actually call a model, set `QWEN_PROVIDER=qwen`, a matching
`QWEN_BASE_URL`, `QWEN_MODEL`, a valid `QWEN_API_KEY`, and optionally `QWEN_TIMEOUT`
(default 120s) and `QWEN_STREAM_TIMEOUT` (default 130s) in your uncommitted `.env.local`.
Placeholder keys are rejected; real keys never enter the repository.

Backend health is at `http://localhost:8080/actuator/health`. When Nginx fronts the SPA it
is configured to expose the same address and to proxy `/api/v1`.

## Acknowledgements

This project did not start from an empty directory. Every line below is a checkable fact
rather than a courtesy.

### The base it grew from

This project began as **[moonlight-in-lonely-city/personal_study](https://gitee.com/moonlight-in-lonely-city/personal_study)** on Gitee.

By surviving line count as of 2026-09-11, code written by
**[griffty73-debug](https://github.com/griffty73-debug) is about 43% of this repository
(27,615 lines)**, including the whole of the `achievement/` and `admin/` subsystems, which
have not had a line changed since. On 2026-09-11 he gave written permission to relicense all
of his past contributions from the Mulan Permissive Software License v2 to Apache-2.0; the
record is in [`RELICENSE.md`](RELICENSE.md) and [issue #1](https://github.com/EdmundJi/lamplit/issues/1).

### Research this is built on

Both papers are kept in [`references/`](references/README.md) with their arXiv versions and SHA-256 hashes:

- **Generative Agents: Interactive Simulacra of Human Behavior** — Joon Sung Park, Joseph C.
  O'Brien, Carrie J. Cai, Meredith Ringel Morris, Percy Liang, Michael S. Bernstein (UIST 2023,
  [arXiv:2304.03442](https://arxiv.org/abs/2304.03442v2)). The perceive-then-decide-whether-to-react
  loop, the memory/reflection/planning layering, and expressing an environment as places with
  usable objects all come from here.
- **Humanoid Agents: Platform for Simulating Human-like Generative Agents** — Zhilin Wang,
  Yu Ying Chiu, Yu Cheung Chiu (EMNLP 2023 Demos,
  [arXiv:2310.05418](https://arxiv.org/abs/2310.05418v1)). How basic needs and emotion feed
  back into plans comes from here.

**These are a research basis, not evidence of product results.** The papers demonstrate
short-horizon social simulation; nothing here should be read as a claim that long-term
memory, stable personality or running cost are solved.

### Open-source implementations referenced

- **[a16z-infra/ai-town](https://github.com/a16z-infra/ai-town)** — the per-operation identity
  and explicit typing ownership in
  [`ConversationLifecycle.java`](backend/src/main/java/com/betterself/growth/town/companion/domain/ConversationLifecycle.java)
  are informed by it (referencing commit `8e05997f`). **That is an independent Java
  implementation; no Convex code is embedded.** The "collision map plus pathfinding, so people
  can stop anywhere standable" approach also comes from it.
- **[joonspk-research/generative_agents](https://github.com/joonspk-research/generative_agents)** — the official Generative Agents implementation.
- **[HumanoidAgents/HumanoidAgents](https://github.com/HumanoidAgents/HumanoidAgents)** — the official Humanoid Agents implementation.

### Art and audio

- **[LimeZu](https://limezu.itch.io/)** — all of the town's pixel art comes from four purchased
  packs: Modern Exteriors, Modern Interiors, Modern Farm, Modern Office Revamped.
  **Attribution is required by their licence**, and redistribution is not permitted, which is
  why the generated output and the original archives are absent from this repository.
- **[Twemoji](https://github.com/twitter/twemoji)** — the emoji graphics in the interface,
  [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
- **Cafe ambiance** — the café room tone, by Marble Toast,
  [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/), from
  [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Cafe_ambiance.ogg).

## Licence

[Apache License 2.0](LICENSE). Attribution and the relicensing history are in
[`NOTICE`](NOTICE) and [`RELICENSE.md`](RELICENSE.md).
