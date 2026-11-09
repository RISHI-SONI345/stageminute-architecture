# Design patterns audit (2026-10-18)

Question: does our design follow the well-known system-design ideas from the standard books, or only claim to? Each row names the idea, where it sits in our docs, and an honest status: **Applied** (written down and has a test or ticket), **Partly**, or **Gap**. Book names are given for the idea; no page numbers.

## 1. Applied

| Idea (source) | Where in our design | Proof it will hold |
|---|---|---|
| Modular monolith first (Fowler "MonolithFirst"; Newman, *Monolith to Microservices*) | AD-01, `02-SERVER-LLD` §1 | Spring Modulith `verify()` fails the build on a cycle or a hidden-package touch |
| Bounded contexts and ubiquitous language (Evans, *Domain-Driven Design*) | modules by area, `requirements/00-GLOSSARY.md`, glossary words in code | Glossary check in the pull request template |
| Deep modules (Ousterhout, *A Philosophy of Software Design*) | small module interfaces, `analyze` is one method | Review checklist (AD-49) |
| Ports and adapters / dependency rule (Cockburn; Martin, *Clean Architecture*) | ports only at vendor seams (AD-02), app `core` / `ui` / `native` | ESLint wall, ArchUnit rules, fakes for every vendor |
| Functional core, imperative shell (Bernhardt; Khorikov, *Unit Testing*) | `analysis.score` is pure, presenters are pure | ArchUnit `scoreIsPure`; ≥ 90% coverage gate |
| Architecture fitness functions (Ford et al., *Building Evolutionary Architectures*) | Modulith + ArchUnit + OpenAPI drift test | CI fails on drift |
| Architecture decision records (Richards and Ford, *Fundamentals of Software Architecture*) | `DECISIONS.md` AD-01 to AD-55, requirements `01` | A new pattern needs a row |
| Idempotent receiver, message store (Hohpe and Woolf, *Enterprise Integration Patterns*; Kleppmann, *DDIA* on idempotence) | idempotency keys, unique `transaction_id`, webhook inbox with ordering by event time | Idempotency tests per endpoint (`11` §3 sessions and quota) |
| Transactional outbox (DDIA; Richardson, *Microservices Patterns*) | Spring Modulith event publication, deletion outbox | Retry until done test, alert if stuck |
| Atomic conditional update instead of read-check-write (DDIA, transactions chapter) | `UPDATE … WHERE count < cap RETURNING` | Ran on real Postgres 2026-10-18 (cap 2 of 4 tries) |
| One clock (DDIA, unreliable clocks) | injected `Clock`, no `now()` defaults, ArchUnit `noHiddenClocks` | Rule and test exist |
| Timeouts, circuit breaker, bulkhead, fail fast, steady state (Nygard, *Release It!*) | 45 s budget, Resilience4j breaker, bulkhead of 8, purge jobs | WireMock vendor contract tests (`02-SERVER-LLD` §4); fault cases added in #19 |
| Load shedding by cost (Release It!) | AI budget throttle (D-58), per-account rate limit | Settings-driven, tested in `practice` |
| Schema evolution, expand then contract (DDIA, encoding and evolution) | Flyway forward-only (AD-54), generated API client | Drift test |
| Twelve-Factor config, logs, disposable processes | env-based secrets, JSON logs, graceful drain on deploy | Deploy ticket #30 |
| Tracer bullet (Hunt and Thomas, *The Pragmatic Programmer*) | P2 builds the whole loop on fake AI first | Phase gate |
| Small batches, continuous integration (Forsgren et al., *Accelerate*) | one ticket per pull request, CI on every push, Dependabot | PR template |
| Test pyramid and contract tests (Fowler; Google testing blog) | pure units → module tests → WireMock contracts → trust gates | `11-QUALITY-GATES-AND-TESTS.md` |
| MVVM with pure presenters, offline-first reads (mobile practice) | `05-APP-ARCHITECTURE` | Presenter table tests |

## 2. Gaps (not in the docs yet)

| # | Gap (source) | Why it matters here | Proposed fix | When |
|---|---|---|---|---|
| G1 | **No service level objectives** (Google, *Site Reliability Engineering*) | Alerts exist but nothing says what "good" is; a one-person team needs a few numbers to decide what to fix | Write 3 SLOs: `/analyze` success rate ≥ 99% (excluding user-side errors), `/analyze` p95 ≤ 15 s (the target already in `03` F-027; the app copy says "usually under 30 seconds" and must match the measured p95), sign-in success; alert on burn, not on every blip | Ticket #29 |
| G2 | **Retry rules are one line** (Nygard; AWS "timeouts, retries and backoff with jitter") | Only one retry on network errors is written; outbox and webhook retries have no backoff schedule | Table of retry rule per call: count, backoff with jitter, what is idempotent | Ticket #19, #18 |
| G3 | **Health is one endpoint** (Release It!, "handshaking"; Kubernetes-style liveness vs readiness) | Caddy and the uptime check only see "UP"; a server that cannot reach Postgres should report not ready | Add readiness (database reachable, settings loaded) and liveness; deploy waits for ready | **Done in #4** (AD-67): liveness and readiness groups with a test that cuts the database. Deploy waiting for ready: #30 |
| G4 | **Failed outbox events end in an alert only** (Hohpe and Woolf, dead letter channel) | After retries, a stuck deletion needs a visible parking place and an admin action | Mark events `failed` after N tries, show them in the admin console with a Retry button | Ticket #27, #28 |
| G5 | **Load test only for memory** (`11` row on uploads) | One VM, 8 concurrent analyses: we have not stated what happens at 20 | One k6 script against fakes: ramp to 2× the bulkhead, expect fast 429/503 with `Retry-After`, no memory growth | Ticket #19 |
| G6 | **No failure drills** (Nygard; "game days") | Backups and breakers are untested until they are needed | Before release: kill Postgres mid-analyze, return 500s from Gemini, restore a backup; record results in the device and release checklist | Ticket #31 |
| G7 | **Threat model is two lines** (OWASP ASVS/MASVS, STRIDE) | Money, audio and admin console are the high-value targets | One page: assets, entry points, top 10 threats with the control for each | Before ticket #26 |
| G8 | **No cost model per user in the docs beyond a sentence** (*Release It!*, capacity) | The AI budget (D-58) protects the bill, but expected cost per daily user is not written next to the pricing | Table: cost per analysis (Deepgram + Gemini) × sessions per day vs ad and subscription revenue, with the break-even | Ticket #19 |

## 3. Accepted risks (written, not gaps)

One VM is one point of failure (F-117, `07`); Clerk is a dependency for sign-in (`10-AUTH-CLERK` §6); no certificate pinning at launch (HLD). Each has an alert or a stated reason.

## 4. How to keep this honest

Each gap above becomes a line in the ticket named in the last column. At the end of every phase, the phase review (AD-50) re-reads this table and moves rows between sections.
