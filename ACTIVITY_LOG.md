# Activity Log

Record of prompts received and actions taken on the Trade Store assignment. Newest session at the bottom.

## Session 1 — 2026-11-12 — Architecture and planning

### Prompt 1 (verbatim)

> @"/Users/vijaykumardixit/Downloads/VD Trade Assignment - For Architect Lead role.docx.pdf"
> Look at the assignment and give me a high level architecture and development approach. Provide architecture diagram for elaboration and understanding. This needs to be high transactions distributed real time processing software as every trade processor is. Consider a trade posting simulator and trade dashboard UI (with react or which ever is easiest and quicker). I imagine there will be components trade poster, trade capture (api), trade processor engine and trade storage (db). Use your own approach or design if it is better than I imagined. Ask questions for clarity rather than assuming. Follow strictly problem statement, outputs and TDD approach suggested in the document. Create a log of activities in a text/md file separately what actions/tasks executed (including this prompt). The output should also include a development plan, software needed (app server, db sql and no-sql)

### Actions

| # | Action | Result |
|---|---|---|
| 1 | Read the assignment PDF (1 page) | Extracted the stack requirements, the sample table and validation rules 1–3 |
| 2 | Inspected the project folder | Empty; not a git repository |
| 3 | Checked installed tools | JDK 21.0.7, Maven 3.9.9, Node 26.5.0, Docker 29.8.0 |
| 4 | Asked four clarifying questions | Answers below |
| 5 | Wrote `docs/ARCHITECTURE.md` | Requirements mapping, components, data flow, rules, data model, scaling, test strategy, pipeline, open points |
| 6 | Wrote `docs/DEVELOPMENT_PLAN.md` | Software list, repository layout, TDD method, ten phases, risks |
| 7 | Wrote PlantUML diagrams in `docs/diagrams/` | `component.puml`, `sequence-trade-flow.puml`, `sequence-expiry.puml` (not yet rendered or syntax-checked with PlantUML) |
| 8 | Showed an architecture diagram in the chat | Same content as `component.puml` |
| 9 | Wrote this log | — |

### Questions and answers

| Question | Answer given | Decision |
|---|---|---|
| Which streaming tool? | "chose the one which I can demonstrate with simple github pipeline setup locally and at client place where they can setup and run" | Apache Kafka, single-node KRaft, official Docker image; Testcontainers in the pipeline |
| Which SQL and NoSQL databases? | PostgreSQL + MongoDB | PostgreSQL is the trade store; MongoDB holds events and rejections |
| Packaging? | Separate services | Maven multi-module repo, one Spring Boot app per service, Docker Compose |
| What happens to rejected trades? | "Option 1 plus 3 combined as appropriate" | Exception, `trades.rejected` topic, stored in MongoDB, shown in dashboard; capture API also returns HTTP 422 for past maturity. Lower version is rejected only by the processor, because a check in the capture API could race |

### Design changes from the original idea

- Added a separate `trade-query-service` so dashboard reads do not compete with trade processing.
- Added outcome topics (`trades.events`, `trades.rejected`) and a dead-letter topic.
- The version rule is enforced by an atomic SQL statement, not only by Kafka ordering.

### Open points awaiting confirmation

Listed in `docs/ARCHITECTURE.md` section 11 (A1–A5). The main one: keep every version of a trade (as the sample table shows) or only the latest.

### Prompt 2 (verbatim)

> Keep every version

| # | Action | Result |
|---|---|---|
| 10 | Recorded the decision on open point A1 | The store keeps one row per `(tradeId, version)`; `docs/ARCHITECTURE.md` section 11 marked confirmed. A2–A5 remain on their stated defaults, unconfirmed |

## Session 2 — 2026-11-12 — Decisions and build

### Prompt 3 (verbatim)

> 1. Use the repo I created -https://github.com/vijaydixitffis/dws-trade-app
> 2. Today is treated as the date and timezone on the server running (no UTC) however it should be configurable to something else if admin configures
> 3. For UI use corporate style vanilla (blue-grey-white) palette
> 4. Convert puml diagrams into svg or jpeg images
> 5. Ignore sample trades in 2020
>
> Start phase 0 onwards developing once plans and activity logs updated. Create code structure that can be edited in Eclipse IDE with relevant maven archetype

### Actions

| # | Action | Result |
|---|---|---|
| 11 | Checked the GitHub repository | Exists and is empty; `gh` is logged in as the owner |
| 12 | Started Docker Desktop | Daemon was not running; now up (needed for Testcontainers) |
| 13 | Updated `docs/ARCHITECTURE.md` and `docs/DEVELOPMENT_PLAN.md` | Server time zone with admin override, blue/grey/white UI, relative-date test data, repository URL, Eclipse import steps |
| 14 | Rendered the three PlantUML files to SVG | PlantUML 1.2024.8 jar from Maven Central; `component.puml` uses the built-in Smetana layout because Graphviz is not installed |
| 15 | Generated the code structure with Maven archetypes | Parent from `org.codehaus.mojo.archetypes:pom-root:1.1`; five modules from `maven-archetype-quickstart:1.5`; placeholder `App` classes removed |
| 16 | Added `.mvn/jvm.config` with `-Djava.net.preferIPv4Stack=true` | Maven downloads failed with "Broken pipe" over IPv6 on this network; harmless elsewhere |
| 17 | `git init`, remote set to the GitHub repository | Branch `main` |
| 18 | Phase 0: parent POM, module POMs, `.gitignore`, service Dockerfile, Docker Compose | Spring Boot 3.5.16 parent; JaCoCo gate at 80 % line coverage per module; OWASP Dependency-Check in the `security-scan` profile |
| 19 | Phase 1 (`trade-common`): tests written and committed first, then model, events, maturity rule, exceptions, business clock, Flyway schema | 8 tests pass |
| 20 | Phases 2–4 (`trade-processor-service`): tests committed first, then trade store with atomic version guard, Kafka listener, outcome publisher, dead-letter handling, expiry job with ShedLock | 20 tests pass against real PostgreSQL and Kafka containers; 94 % line coverage |
| 21 | Phase 3 (`trade-capture-service`): tests committed first, then REST API, immediate maturity check, batch endpoint, Kafka publisher | 8 tests pass; 92 % line coverage |
| 22 | Phase 5 (`trade-query-service`): tests committed first, then store read API, MongoDB projection, summary, SSE live feed, CORS | 11 tests pass; 100 % line coverage |
| 23 | Found that the `mongo:8` image will not start on this Docker Desktop kernel (MongoDB SERVER-121912) | Switched tests and Compose to `mongo:8.2`, which starts |
| 24 | Phase 6 (`trade-simulator`): tests committed first, then scenario generator, paced runner, control API | 7 tests pass; 92 % line coverage |
| 25 | Phase 7 (`trade-dashboard-ui`): React + Vite + TypeScript, plain CSS in blue, grey and white | 6 component tests pass; production build succeeds. Tests and components were written together and committed in one commit, not test-first |
| 26 | `npm audit` reported 2 critical findings in the test tooling (tinypool via vitest 3) | Upgraded vitest to 5, vite to 8, jsdom to 31; audit now reports 0 vulnerabilities |
| 27 | Phase 8: `.github/workflows/ci.yml` and `deploy/regression.sh` | Jobs: backend tests, dashboard tests, vulnerability scan (Trivy + `npm audit`, OWASP Dependency-Check when `NVD_API_KEY` is set), end-to-end regression |
| 28 | Full `mvn clean verify` | 54 backend tests pass; every module is above the coverage gate |
| 29 | Wrote `README.md` | Run, develop, test and pipeline instructions; known limits |

### How TDD was applied

For each backend phase the test classes were written first and committed with a `test: ... (red)` message, then the implementation was written until they passed and committed with `feat: ... (green)`. `git log` shows the order. The exception is the dashboard (action 25).

### Deviations from the plan

- The processor consumes one record at a time per partition thread, not in batches. It is simpler to reason about for retries and dead-lettering; batching is a tuning step for phase 9.
- MongoDB 8.2 instead of 8 (action 23).
- Trivy is the default vulnerability scanner in the pipeline, with OWASP Dependency-Check running only when an NVD API key is configured.


### Verification of the running system

| # | Action | Result |
|---|---|---|
| 30 | `docker compose -f deploy/docker-compose.yml up --build -d` | All eight containers started; Kafka, PostgreSQL and MongoDB healthy |
| 31 | `deploy/regression.sh` against the running stack | 17 of 17 checks pass, including a 500-trade simulator run |
| 32 | Opened the dashboard at http://localhost:8080 | Summary tiles, simulator panel, trade table and live indicator render; containers report the business zone as `Etc/UTC` because that is the container's own zone (set `TZ` or `TRADE_BUSINESS_ZONE` to change it) |
| 33 | Pushed `main` to https://github.com/vijaydixitffis/dws-trade-app | See the Actions tab for the pipeline result |

### Not done

- Phase 9 (load run with recorded throughput figures, tuning, class diagrams).
- OWASP Dependency-Check has not been run: it needs an NVD API key. Trivy has not been run locally; it runs in the pipeline.
- First pipeline run (43247039332): backend, dashboard and end-to-end regression jobs passed; the vulnerability scan job failed before scanning because the Trivy action tag `0.33.1` does not exist. Corrected to `v0.36.0` and pushed again.
