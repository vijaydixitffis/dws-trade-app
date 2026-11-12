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
- Second pipeline run (43247952405): backend, dashboard and end-to-end regression jobs passed. The vulnerability scan ran and failed the build, as designed: Trivy reports critical CVEs in `tomcat-embed-core` 10.1.55 (CVE-2026-55276, -65182, -65637, -65905, -68525) and `spring-webmvc` 6.2.19 (CVE-2026-47884, -47890), both brought in by Spring Boot 3.5.16. The Spring fixes are only in Spring Framework 7.0.9, which means moving to Spring Boot 4. One Tomcat CVE (CVE-2026-68525) has no fixed version listed. Not yet resolved; awaiting a decision.

## Session 2 (continued) — Vulnerability scan fix

### Prompt 4 (verbatim)

> Vulnerability scan failed again

| # | Action | Result |
|---|---|---|
| 34 | Read the failing scan log (run 43248693415) | Same cause as before: 28 critical findings, all in Tomcat 10.1.55 and Spring MVC 6.2.19 from Spring Boot 3.5.16 |
| 35 | Migrated the build to Spring Boot 4.1.2 (Spring Framework 7.0.10, Tomcat 11.0.27, Jackson 3, Testcontainers 2, ShedLock 7.11.0) | New starters (`webmvc`, `kafka`, `flyway`, `restclient`) and split test modules; `tools.jackson` imports; Testcontainers 2 class names; `spring.mongodb.uri`; HTTP 422 constant renamed to `UNPROCESSABLE_CONTENT` |
| 36 | Fixed two simulator tests that returned 400 under Jackson 3 | Jackson 3 refuses missing primitive fields by default; `fail-on-null-for-primitives: false` makes omitted percentages zero |
| 37 | `mvn clean verify` | 54 tests pass, coverage gate met |
| 38 | Trivy scan in Docker with the local Maven cache (the first attempt was rate-limited by Maven Central) | 0 critical findings in every module and in the dashboard |
| 39 | Rebuilt and restarted the Compose stack, ran `deploy/regression.sh` | Regression passes on Spring Boot 4 |

Earlier note about CVE-2026-68525 having no fixed Tomcat version is superseded: Tomcat 11.0.27 is not flagged.

### Prompt 5 (verbatim)

> Modify https://github.com/vijaydixitffis/dws-trade-app/blob/main/docs/diagrams/component.svg to a nice rectangular orientation rather than long horizontal diagram
> Hold on to Phase 9 till I instruct

| # | Action | Result |
|---|---|---|
| 40 | Redrew `docs/diagrams/component.puml` as layers: Clients, Capture, Kafka, Processing and read side, Storage | Size changed from about 1100 x 620 (very wide) to 905 x 755 |
| 41 | Installed Graphviz (`brew install graphviz`) | The built-in layout engine placed boxes poorly; Graphviz gives a clean top-to-bottom flow. Re-render with `java -jar plantuml.jar -tsvg component.puml` |
| 42 | Re-rendered `component.svg` and checked the image | Dashboard-to-query arrow still crosses the diagram on its way down; left as is |
| 43 | Phase 9 | On hold until instructed |

### Prompt 6 (verbatim)

> Create a PDF High level design and architecture document using svg diagrams in sequence starting with a problem statement, and usual HLD sections with explaining how NFRs are taken care in the solution

| # | Action | Result |
|---|---|---|
| 44 | Added three PlantUML diagrams: `deployment.puml`, `data-model.puml`, `cicd.puml`, and rendered SVGs | Together with the component and two sequence diagrams there are six diagrams |
| 45 | Narrowed the trade-flow sequence diagram by wrapping long messages, and corrected its label "consume batch" to "consume record" (the processor handles one record at a time) | `sequence-trade-flow.svg` re-rendered |
| 46 | Wrote `docs/hld/hld.html` and rendered it to `docs/Trade-Store-HLD.pdf` with headless Chrome | 15 pages: cover, contents, problem statement, scope and decisions, components, technology, runtime flows (landscape page for the sequence diagram), rules, data, deployment, NFR table, testing and pipeline, risks, traceability, appendix |
| 47 | Section 9 of the PDF marks every NFR as met, partly met or not yet met | Gaps stated plainly: throughput not yet measured, no authentication or TLS, single broker in Compose, no correlation ids, unbounded MongoDB history |
| 48 | Checked the pipeline before stating it is green | Both latest runs on `main` succeeded |

To regenerate the PDF after editing `docs/hld/hld.html`:
`"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --no-pdf-header-footer --print-to-pdf=docs/Trade-Store-HLD.pdf file://$PWD/docs/hld/hld.html`

### Prompt 7 (verbatim)

> On the UI change Trade simulator - Provide two buttons which launch a modal with form to supply Trade inputs. One Button shows the current form, second button shows more specific and detailed input elements so user can select variation in trades posted

| # | Action | Result |
|---|---|---|
| 49 | Interpreted "more specific and detailed input elements ... variation in trades posted" as choosing the variety of the generated trades, not posting one hand-typed trade | Say so if a single manual trade form is wanted as well |
| 50 | Simulator tests written and committed first (red), then implemented: optional `counterParties`, `books`, `minMaturityDays`, `maxMaturityDays`, `tradeIdPrefix` on the run request; new `Profile` record; generator honours them; validation (names not blank, window ordered, prefix `[A-Za-z0-9-]{1,16}`) | 16 simulator tests pass |
| 51 | Dashboard tests written and committed first (red), then implemented a reusable `Modal` (Escape, click outside, focus returned), **Quick run** (current six fields) and **Detailed run** (volume, mix sliders with the remainder shown as new trades, counter-party and book lists, maturity window, id prefix) | 11 dashboard tests pass; build succeeds |
| 52 | Rebuilt the simulator and dashboard containers; ran a detailed run through the API and opened both dialogs in the browser | Trades arrived with the chosen counter-parties, book, prefix and maturity window; dialogs render correctly |
| 53 | Full checks | 63 backend tests pass, regression passes, `npm audit` reports 0 vulnerabilities |
| 54 | Updated README, HLD (component, test and interface text) and re-rendered the PDF and pipeline diagram | Test counts now 63 backend and 11 dashboard |

### Prompt 8 (verbatim)

> 1. Add PDF HLD on the title page - Submitted By: Vijaykumar Dixit and append on footer submitted by Vijaykumar Dixit
> 2. Ensure activity log is updated and checked in
> 3. Tag the repo with label "mvp1-dev-12Nov10pm"

| # | Action | Result |
|---|---|---|
| 55 | Added "Submitted by: Vijaykumar Dixit" to the HLD title page and "Submitted by Vijaykumar Dixit" to the footer of every page after the title page (the title page itself has no footer) | PDF re-rendered |
| 56 | Brought this activity log up to date and committed it | This entry |
| 57 | Created and pushed the tag `mvp1-dev-12Nov10pm` on the commit that contains this entry | Tag points at the final commit of this step |

## Session 3 — 2026-11-12 — Code review

### Prompt 9 (verbatim)

> You are a senior lead developer with 20+ years experience in building Java with full stack distributed async and high throughput low latency systems. Do a thorough code review of this project including UI, API, Backend, middleware and DB layers and create a md file of review report and identify clearly categorized issues and their fixes

| # | Action | Result |
|---|---|---|
| 58 | Reviewed all source and configuration (about 4,600 lines) at commit `e39d3d8` | 49 findings: 0 critical, 9 high, 21 medium, 19 low, in eight categories |
| 59 | Reproduced four suspected defects on the running Compose stack | Confirmed: an 87-character trade id gets HTTP 202 and is then dead-lettered with no rejection record; the simulator sends 30 trades "at 1 per second" in 4 s; the trade-list query runs as a sequential scan plus sort; MongoDB stores dates as midnight instants |
| 60 | Wrote `docs/CODE_REVIEW.md` | Each finding has a severity, evidence label (confirmed or by inspection), and a fix; a suggested order of work is included |
| 61 | No code was changed | The review is a report only. The tag `mvp1-dev-12Nov10pm` still points at `e39d3d8`, the reviewed commit |

Side effects of the checks on the local demo stack: one oversized test trade on the dead-letter topic and 30 simulator trades in the store.

## Session 4 — 2026-11-12 — Basic authentication

### Prompt 10 (verbatim)

> For security issues found - mention in HLD under known issues - Simple Basic auth is used for demo purpose. Implement this and Load UI after basic auth where admin and any password admin@2026! Also recommend a authN and authZ design for API calls

| # | Action | Result |
|---|---|---|
| 62 | Read "admin and any password admin@2026!" as user `admin` with password `admin@2026!` | Say so if a different rule was meant |
| 63 | New module `trade-security`; tests written and committed first (red) | 9 tests: 401 without or with wrong credentials, demo and configured accounts, POST without CSRF token, open health check, protected Actuator, no session |
| 64 | Implemented `TradeSecurityConfig` (Spring Security HTTP Basic, one in-memory administrator, BCrypt, stateless) and imported it in all four services | Account from `TRADE_SECURITY_USERNAME` / `TRADE_SECURITY_PASSWORD`; a warning is logged when the demo password is in use; `/actuator/health` stays open for container probes |
| 65 | Simulator signs in to the capture API with the same account | `CaptureClientTest` checks the `Authorization` header |
| 66 | Dashboard now loads only after login: nginx `auth_basic` in front of the page, and nginx forwards `/query/` and `/simulator/` to the services with the browser's credentials | The dashboard uses same-origin paths, so the CORS configuration and its test were removed (review finding ARCH-6). Vite proxies the same paths in development |
| 67 | Compose passes the account to every service and to nginx; nginx gained a health check and three security headers | — |
| 68 | Regression script signs in and gained 11 authentication checks | 28 of 28 checks pass on the rebuilt stack |
| 69 | Checked the live feed through the login with `curl` | `event:connected` with credentials, 401 without; challenge header is `Basic realm="Trade Store"` |
| 70 | Did not type the password into the browser's own login prompt | The login prompt is a browser dialog that page automation cannot drive; the equivalent requests were verified with `curl` and the regression script |
| 71 | HLD: known issue "Simple Basic auth is used for demo purpose" (section 11, renamed "Known issues, risks and next steps"), security NFR row, deployment diagram, test counts | — |
| 72 | HLD: new section 12 "Recommended authentication and authorisation design" with diagram `security-target.svg` | OAuth 2.0 / OpenID Connect; authorisation code + PKCE for users, client credentials for systems; scopes `trades:write`, `trades:read`, `simulator:run`, `ops:read`; data-level entitlements; Kafka access lists; database roles; migration steps |
| 73 | README and `docs/CODE_REVIEW.md` updated | Review now has a "Changes since this review" table: SEC-1, SEC-2 and SEC-6 partly addressed, ARCH-6 fixed |
| 74 | Full checks | 74 backend tests and 12 dashboard tests pass; PDF re-rendered (19 pages) |

Known limits of this step: one shared account, no roles, no TLS, and the password has a default in the code and Compose file. These are stated in the HLD and README.

## Session 5 — 2026-11-13 — Review fixes, security without a gateway

### Prompt 11 (verbatim)

> 1. Implement the security fixes without the gateway
> 2. Update HLD open issues of not using Gateway for the demo scope and purpose
> 3. Complete security fixes, config and component updates
> 4. Complete Steps 1 to 4 to address code review comments, bug fixes

### Prompt 12 (verbatim, sent while the work was running)

> Anything that is not fixed/implemented in code review report add it to the HLD open issues and mention demo purpose as reason

| # | Action | Result |
|---|---|---|
| 75 | Interpreted "Steps 1 to 4" as steps 1 to 4 of the suggested order in `docs/CODE_REVIEW.md` section 5, and "security fixes without the gateway" as the review's SEC items plus the authorisation model, using Basic authentication, since a token design needs an identity provider the demo does not have | Say so if something else was meant |
| 76 | Step 1 (tests first, committed red, then code): trade id, counter-party and book limits; `INVALID_TRADE` rejection reason; exponential back-off retry policy; query-service error handler and dead-letter topics; rate-accurate simulator pacing; dashboard run dialog stays open on refusal and validates like the server | 86 backend and 16 dashboard tests at that point |
| 77 | Step 2: tests that pause PostgreSQL and MongoDB containers, dead-letter unreadable events, and run the expiry job from two callers at once | Found that the start-up expiry run bypassed the lock; fixed |
| 78 | Step 3: transactional outbox (V2 migration, writer, ordered relay with an advisory lock), atomic trade and event, expiry events in the same transaction, background start-up run, first-delivery-wins history, ISO dates in MongoDB, explicit replaced-versus-new check | 104 backend tests |
| 79 | Step 4: sequence index (V3), keyset paging with a cursor, capped counts, cached summary, live feed with per-browser queues; dashboard cursor paging, typing pause, cancellation and stale-answer protection, rejections fetched on demand, invalid-trade tile | 118 backend and 23 dashboard tests. A test of the live feed showed that dropping a subscriber on a full queue also dropped a healthy one during bursts; changed to discard the oldest signal and drop only a stalled subscriber |
| 80 | Security without a gateway: scopes `trades:write`, `trades:read`, `simulator:run`, `ops:read` per endpoint, deny by default, extra accounts by configuration, request size filter (413), per-caller rate limit (429), simulator run cap | 139 backend tests |
| 81 | Deployment hardening: read-only database account for the query service, unprivileged nginx with a content security policy and rate limits, base images pinned by digest, workflow actions pinned to commits, image scan, CycloneDX bill of materials, CodeQL workflow, Dependabot, `deploy/.env.example` | Regression script now has 33 checks, all passing on a stack rebuilt from scratch |
| 82 | The first image scan found three fixable critical findings in the old nginx base image | Moved to `nginx-unprivileged:stable-alpine-slim`, which is clean; all five images scan clean |
| 83 | Found two flaws in my own regression checks (a `pipefail` interaction with `grep -q`) and one wrong test expectation (deny-by-default versus the `/api/v1/**` read rule) | Fixed; none was a product defect |
| 84 | Opened the dashboard in the browser under the new policy | Loads and works. Putting the login in the page address makes browsers refuse `fetch`, which is a test artefact; with the login cached the page works normally. The browser's own login prompt was not driven by hand |
| 85 | Updated the diagrams: capture and processing, a new event-delivery diagram, expiry, data model (outbox), component, deployment | Flow split in two so it stays readable |
| 86 | HLD version 1.1: flows, data, deployment, NFR table, tests and pipeline, traceability, appendix; section 11 now has the open issues including "No API gateway in the demo scope"; section 12 gained "what the demo implements today" and "operating without a gateway" | 25 pages |
| 87 | Per the second message: every code review item not fixed is now a row in the HLD with its proposed fix and the reason it is deferred (demo purpose) | 26 rows |
| 88 | README rewritten; `docs/CODE_REVIEW.md` has a status table for every finding; `docs/ARCHITECTURE.md` points to the HLD | — |

### Not done

- TLS, tokens, a gateway, a shared rate limit (HLD sections 11 and 12).
- Phase 9 (load run and tuning) remains on hold.
- OWASP Dependency-Check has not run (needs an NVD key).
| 89 | First CI run of this work: backend, dashboard, regression, image scan and CodeQL passed; the dependency scans passed but the software bill of materials step failed because containerised Trivy was rate-limited by Maven Central (the same cause hit locally) | Mounted the runner's Maven cache into the step and pushed the fix |
