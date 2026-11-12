# Trade Store — Development Plan and Software

Companion to `ARCHITECTURE.md`. Progress is recorded in `ACTIVITY_LOG.md`.
Repository: https://github.com/vijaydixitffis/dws-trade-app

## 1. Software needed

### On the developer or client machine

| Software | Version | Purpose | On this machine |
|---|---|---|---|
| JDK (Temurin) | 21 LTS | Build and run services | Installed (21.0.7) |
| Maven | 3.9+ | Build | Installed (3.9.9) |
| Docker with Compose | recent | Kafka, PostgreSQL, MongoDB, Testcontainers | Installed (29.8.0) |
| Node.js | 20 LTS or newer | Dashboard build | Installed (26.5.0) |
| Git + a GitHub repository | — | Source control and GitHub Actions | `vijaydixitffis/dws-trade-app` |
| `act` (optional) | latest | Run the GitHub Actions workflow locally | Not checked |
| PlantUML (optional) | latest | Re-render `.puml` files to SVG; rendered SVGs are committed | Jar used from Maven Central |

A client who only wants to run the system needs Docker alone: `docker compose up` starts everything from images.

### Runtime stack

| Role | Choice | Notes |
|---|---|---|
| Application server | Embedded Tomcat inside each Spring Boot jar | No separate app server to install |
| Framework | Spring Boot 4.1.2 | Web, Kafka, JDBC, Data MongoDB, Actuator, Validation |
| Streaming | Apache Kafka, KRaft mode, `apache/kafka` image | Single container locally |
| SQL database | PostgreSQL 17 | Trade store |
| NoSQL database | MongoDB 8.2 | Events and rejections |
| Schema migration | Flyway | PostgreSQL |
| Scheduler lock | ShedLock | One expiry run across instances |
| UI | React 19, Vite, TypeScript | Served by nginx in its container. Plain CSS, corporate blue / grey / white palette, no UI framework |

### Build, test and pipeline tooling

JUnit 5, AssertJ, Mockito, Awaitility, Testcontainers (Kafka, PostgreSQL, MongoDB), JaCoCo, OWASP Dependency-Check Maven plugin, `npm audit`, Vitest, React Testing Library, GitHub Actions, PlantUML.

## 2. Repository layout

```
dws-trade-app/
├── pom.xml                      parent, dependency and plugin management
├── trade-common/                Trade model, events, rules, exceptions (no Spring)
├── trade-capture-service/       REST in, Kafka out            :8081
├── trade-processor-service/     Kafka in, PostgreSQL, expiry  :8082
├── trade-query-service/         MongoDB projection, REST, SSE :8083
├── trade-simulator/             Load and scenario generator   :8084
├── trade-dashboard-ui/          React app                     :5173
├── deploy/docker-compose.yml    Kafka, PostgreSQL, MongoDB, all services
├── docs/                        Architecture, plan, diagrams
├── .github/workflows/ci.yml
├── ACTIVITY_LOG.md
└── README.md
```

## 3. How TDD will be applied

1. Write the test list for the phase from the rules in the document.
2. Take one test, write it, watch it fail.
3. Write the least code that passes it.
4. Refactor with the tests green.
5. Commit the test and the implementation as separate commits (`test:` then `feat:`), so the order is visible in the history.

"Today" is always supplied by an injected `Clock`, so date rules are tested with fixed dates.

## 4. Phases

Effort is a rough estimate for one developer.

| Phase | Deliverable | First tests to write | Effort |
|---|---|---|---|
| 0. Foundation | Git repository, parent POM, empty modules, Docker Compose for Kafka/PostgreSQL/MongoDB, CI workflow that builds and tests | A smoke test per module that the context loads | 0.5 day |
| 1. Rules (`trade-common`) | `Trade`, `TradeValidator`, `LowerVersionTradeException`, `MaturityDatePassedException` | Lower version rejected; same version replaces; higher version accepted; maturity yesterday rejected; maturity today accepted | 0.5 day |
| 2. Trade store (processor, PostgreSQL) | Flyway schema, `TradeStore` with the atomic version guard | The V1 matrix against a real PostgreSQL; concurrent writers on one trade end at the highest version; ordering by trade id then version | 1 day |
| 3. Streaming | Capture REST endpoint, producer, processor listener, outcome topics, DLT | `202` and message on topic; `422` for past maturity and message on rejected topic; end-to-end accept, replace, reject through Kafka; redelivery is harmless | 1.5 days |
| 4. Expiry | Scheduler with ShedLock, `EXPIRED` events | With the clock moved past maturity the trade becomes `Y`; unmatured trades untouched; two instances, one run | 0.5 day |
| 5. Read side | MongoDB projection, query REST API, SSE feed | Events and rejections land once in MongoDB even when replayed; API returns the documented order; SSE emits on new events | 1 day |
| 6. Simulator | Configurable rate, count and scenario mix | Generated mix matches the configured ratios; lower-version and past-maturity trades are really such | 0.5 day |
| 7. Dashboard | Trade table, rejections tab, live counters, simulator controls | Component tests for rendering, filtering and live update | 1 day |
| 8. Pipeline hardening | Regression suite on Docker Compose, JaCoCo gate, OWASP scan with `failBuildOnCVSS=9`, `npm audit`, Docker images | A deliberately vulnerable dependency on a branch proves the build fails | 0.5 day |
| 9. Load and documentation | Throughput run with the simulator, tuning notes, PlantUML class diagrams, README with run instructions | Recorded throughput and lag figures | 0.5 day |

Total: about 7.5 days. Phases 1 to 4 satisfy the document on their own; phases 5 to 7 are the simulator and dashboard additions.

## 5. Definition of done (per phase)

- All tests for the phase were written first and pass.
- The pipeline is green, including the coverage gate and the vulnerability scan.
- `docker compose up` still starts the whole system.
- Diagrams and `ACTIVITY_LOG.md` are updated.

## 6. Risks

| Risk | Mitigation |
|---|---|
| OWASP Dependency-Check is slow or fails without an NVD API key | Request the free key early and cache the data in the workflow; Trivy as fallback |
| Testcontainers makes the pipeline slow | One shared set of containers per module; integration tests in their own job |
| Midnight boundary and time zones in the maturity and expiry rules | Single configured business zone, injected `Clock`, tests at the boundary |
| Scope: five deployables for an assignment | Phases 1 to 4 are demonstrable alone; later phases add without rework |

## 7. Opening the code in Eclipse

The modules were generated with Maven archetypes (`pom-root` for the parent, `maven-archetype-quickstart` for each Java module), so they are standard Maven projects.

1. File → Import → Maven → Existing Maven Projects.
2. Select the repository root. Eclipse (m2e) lists the parent and the five Java modules; import all.
3. Use a Java 21 JRE for the workspace.

`trade-dashboard-ui` is a Node project and is not imported by m2e; open it as a general folder or in any web editor.
