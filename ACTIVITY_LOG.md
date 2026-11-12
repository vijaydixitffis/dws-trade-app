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
