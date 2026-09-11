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

This project did not start from an empty directory, and the honest way to thank the work it
stands on is to say precisely what was taken from it — including the places where we read
closely enough to disagree.

### The base it grew from

This project began as **[moonlight-in-lonely-city/personal_study](https://gitee.com/moonlight-in-lonely-city/personal_study)** on Gitee.

By surviving line count as of 2026-09-11, code written by
**[griffty73-debug](https://github.com/griffty73-debug) is about 43% of this repository
(27,615 lines)** — the whole of the `achievement/` and `admin/` subsystems have not had a
line changed since he wrote them. On 2026-09-11 he gave written permission to relicense all
of his past contributions from the Mulan Permissive Software License v2 to Apache-2.0. The
record is in [`RELICENSE.md`](RELICENSE.md) and [issue #1](https://github.com/EdmundJi/lamplit/issues/1).

### Generative Agents — the spine

**Joon Sung Park, Joseph C. O'Brien, Carrie J. Cai, Meredith Ringel Morris, Percy Liang,
Michael S. Bernstein.** *Generative Agents: Interactive Simulacra of Human Behavior.*
UIST 2023. [arXiv:2304.03442](https://arxiv.org/abs/2304.03442v2)

Without this paper the town would not have an architecture — it would have four figures each
performing one action on a timer, which is exactly what the first version was. What it gave us:

| From the paper | Where it lives here |
|---|---|
| Retrieval scored by recency + importance + relevance (§4.1, Fig. 6) | [`CompanionRecall.java`](backend/src/main/java/com/betterself/growth/town/companion/domain/CompanionRecall.java) |
| Reflection triggered by accumulated importance rather than a clock (§4.2) | `ResidentSimulation.needsReflection` |
| Memory as layers, where reflections outrank raw observation (§4.2, Fig. 7) | `CompanionRecall.tier` |
| **"Should this resident react to what they just saw?" as its own question** (§4.3.1) | `ResidentMind.react` — the paper's sentence is quoted verbatim in the code |
| Environment as places, sub-areas and usable objects (§3.2, Fig. 2) | `TownPlaces`, and the map section of [docs/01](docs/01-requirements.md) |

That fourth row turned out to be the single most valuable idea we borrowed, and we can put
numbers on it: offered as one option among many, `invite` was taken 0 times out of 96. Asked
as its own question, the same model said yes 168 times out of 357. We then generalised the
insight past where the paper took it, to `celebrate`, `propose`, `venture` and promise-making
— **that extension is ours, the insight is theirs.**

Where we went another way, and why: relevance multiplies the other factors here instead of
being summed with them (adding lets a fresh, important, entirely irrelevant memory win);
importance is assigned by rules rather than scored by the model; plans stay as three or four
qualitative segments instead of recursing down to 5–15 minute slots.

And one debt that is easy to miss: **§7.2, where the authors report their own agents drifting
toward excessive politeness and cooperation.** We took that limitation seriously enough to
build against it — the id/superego/ego layering and the "let it grow worse" rule both exist
because of that paragraph. A paper you can argue with in specifics is worth more than one you
can only cite.

### a16z-infra/ai-town — the engineering

**[a16z-infra/ai-town](https://github.com/a16z-infra/ai-town)**

Two concrete pitfalls avoided, both of which would have cost weeks:

**Concurrent writes to a conversation.** Several residents are driven by asynchronous model
calls that can time out, arrive late, or arrive after the world has moved on. ai-town's
per-operation identity is why
[`ConversationLifecycle.java`](backend/src/main/java/com/betterself/growth/town/companion/domain/ConversationLifecycle.java)
reserves a turn as a one-shot `Operation` and re-checks every field of it before letting a
reply land — a late answer can never overwrite a conversation that already ended, and two
requests can never both believe it is their turn. **That is an independent Java
implementation; no Convex code is embedded.**

**Movement.** "A collision map plus pathfinding, so people can stop anywhere standable"
([docs/04](docs/04-decisions.md)) is taken straight from them, and it saved us from building
an enumerated-slot system that would have had to be torn out the first time four people
needed to stand in a four-slot garden. See `collision.ts` and `pathfinding.ts`.

We deliberately did not follow them on three things, and said so at the time: their
sharp-angled personality design, their vector memory plus reflection (this town runs on
limited knowledge and hearsay instead), and their continuously-running world. **Being a
project worth disagreeing with in writing is its own kind of influence.**

### Humanoid Agents — the feedback loop

**Zhilin Wang, Yu Ying Chiu, Yu Cheung Chiu.** *Humanoid Agents: Platform for Simulating
Human-like Generative Agents.* EMNLP 2023 System Demonstrations.
[arXiv:2310.05418](https://arxiv.org/abs/2310.05418v1)

Generative Agents gave us perceive–plan–react but left a gap: once an action finishes, which
internal changes should feed back into the next plan? This paper answers that, and the answer
is the loop this town runs on.

Two things taken almost directly: internal quantities that accumulate silently and only
surface as a qualitative phrase once they cross a threshold (§3.2), and **closeness rendered
as words rather than a number** — `ResidentDirector.closeness` says so in its own comment,
citing their example.

Then the divergence, which is the part we are most sure about. In the paper, emotion is a
persistent category handed to the model every round. Here, **no internal value ever reaches a
model prompt at all** — `energy`, `social`, `dutyPressure` and the rest are forbidden by tests
that assert on field names, so changing the rule means changing the test. The reason is in
[docs/04](docs/04-decisions.md): *a number in the context turns the model into something that
reads a table.*

This is not a correction of their work. They need state to be readable and initial values to
be settable, because their experiments depend on ablating it. We want a town with no visible
dials. Same loop, opposite requirement — **and we only know that because the paper is
specific enough to disagree with.**

### Art and audio

- **[LimeZu](https://limezu.itch.io/)** — every pixel of this town comes from four purchased
  packs: Modern Exteriors, Modern Interiors, Modern Farm, Modern Office Revamped. The art is
  most of what makes the place feel worth sitting in. **Attribution is required by their
  licence**, redistribution is not permitted, and that is why the generated atlases and the
  original archives are absent here — to see the town you will need to buy the packs.
- **[Twemoji](https://github.com/twitter/twemoji)** — the emoji graphics, [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
- **Cafe ambiance** — the café room tone, by Marble Toast, [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/), from [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Cafe_ambiance.ogg).

## Licence

[Apache License 2.0](LICENSE). Attribution and the relicensing history are in
[`NOTICE`](NOTICE) and [`RELICENSE.md`](RELICENSE.md).
