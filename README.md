# Trade Store

A distributed trade store built with Java 21, Spring Boot 4, Apache Kafka, PostgreSQL and MongoDB.
Trades are posted to a capture API, streamed through Kafka, validated and stored by a processor,
and shown on a React dashboard. A simulator generates load.

- Design: [docs/Trade-Store-HLD.pdf](docs/Trade-Store-HLD.pdf) (source: `docs/hld/hld.html`)
- Code review and its status: [docs/CODE_REVIEW.md](docs/CODE_REVIEW.md)
- What was done, step by step: [ACTIVITY_LOG.md](ACTIVITY_LOG.md)

![Component view](docs/diagrams/component.svg)

## The rules

| Rule | Behaviour |
|---|---|
| Lower version | Rejected with `LowerVersionTradeException`; recorded as a rejection |
| Same version | Replaces the stored record |
| Higher version | Stored as a new row; earlier versions are kept |
| Maturity date earlier than today | Rejected with `MaturityDatePassedException`; the capture API answers HTTP 422 |
| Maturity date passes later | The expiry job marks the trade `Expired = Y` |
| Invalid trade (missing field, id over 64 characters) | HTTP 400 at capture; recorded as `INVALID_TRADE` if it arrives by another route |

"Today" is the date on the server in the server's own time zone. To use another zone, set
`TRADE_BUSINESS_ZONE` (for example `Europe/London`) on the services.

## Run everything

Only Docker is needed.

```bash
docker compose -f deploy/docker-compose.yml up --build
```

Sign in with user `admin` and password `admin@2026!`. The browser asks for them when the
dashboard opens, and every API call needs them too.

| What | Where |
|---|---|
| Dashboard | http://localhost:8080 |
| Capture API | http://localhost:8081/api/v1/trades |
| Processor (health, metrics) | http://localhost:8082/actuator/health |
| Query API | http://localhost:8083/api/v1/trades |
| Simulator API | http://localhost:8084/api/v1/simulations/current |

Post a trade:

```bash
curl -i -u 'admin:admin@2026!' -X POST http://localhost:8081/api/v1/trades -H 'Content-Type: application/json' -d '{"tradeId":"T1","version":1,"counterPartyId":"CP-1","bookId":"B1","maturityDate":"2030-05-20"}'
```

Or open the dashboard and use the simulator panel:

- **Quick run** opens a dialog for the bulk settings: number of trades, rate, and the percentage of
  higher-version, same-version, lower-version and past-maturity trades.
- **Detailed run** adds sliders for the mix and the variety of the trades: counter-party ids, book
  ids, how many days ahead maturity dates fall, and a trade id prefix. Reusing a prefix from an
  earlier run continues the same trade ids, so the store replaces or rejects them by version.

Settings (all have demo defaults): copy `deploy/.env.example` to `deploy/.env`.

## Security in the demo

This is simple Basic auth for demonstration, not the production design.

- Every service and the dashboard require HTTP Basic authentication; only `/actuator/health` is open.
- Authorisation is by scope and deny-by-default: `trades:write` (post trades), `trades:read`
  (read), `simulator:run`, `ops:read`. The `admin` account holds all four. Add accounts with fewer
  scopes under `trade.security.accounts` (environment variables such as
  `TRADE_SECURITY_ACCOUNTS_0_USERNAME`, `_PASSWORD`, `_AUTHORITIES`).
- Request bodies are limited to 2 MB and callers to 600 requests a second per instance; the
  simulator refuses runs above 1,000,000 trades (`simulator.max-count`).
- The query service reaches PostgreSQL through a read-only account. The dashboard host runs as a
  non-root user and sends a content security policy.
- Change the account with `TRADE_SECURITY_USERNAME` and `TRADE_SECURITY_PASSWORD`; the database
  accounts with `POSTGRES_PASSWORD` and `TRADE_RO_PASSWORD`.

Known limits are listed in the HLD (section 11). The main ones: one shared account by default, the
password sent with every request, no TLS, and **no API gateway** in the demo scope. The recommended
authentication and authorisation design, and how it works with or without a gateway, is section 12
of the HLD.

## Develop

Requirements: JDK 21, Maven 3.9+, Docker (for the tests), Node 22+ (for the dashboard).

```bash
mvn verify
```

This runs every backend test, including the integration tests that start Kafka, PostgreSQL and
MongoDB in containers (some pause a container to simulate an outage), and fails if line coverage of
a module is below 80 %.

Run the services from an IDE against local infrastructure:

```bash
docker compose -f deploy/docker-compose.yml up kafka postgres mongo
```

Then start `CaptureApplication`, `ProcessorApplication`, `QueryApplication` and
`SimulatorApplication`, and the dashboard (its development server forwards `/query` and
`/simulator` to the local services and the browser asks for the login):

```bash
cd trade-dashboard-ui && npm install && npm run dev
```

### Start the whole system from Maven (and from Eclipse)

Docker must be running. From the repository root:

```bash
mvn -N -Pdocker exec:exec@docker-up
```

This builds the images and starts Kafka, PostgreSQL, MongoDB, the four services and the dashboard,
and returns once they are healthy; the Spring services need about 20 more seconds before the
dashboard shows data. Stop everything and delete the data with:

```bash
mvn -N -Pdocker exec:exec@docker-down
```

In Eclipse: right-click the parent project (`dws-trade-app`) → Run As → Maven build...; set Goals to
`exec:exec@docker-up`, Profiles to `docker`, and tick "Non-recursive". Then open
http://localhost:8080 and sign in as `admin` / `admin@2026!`.

If Eclipse reports `Cannot run program "docker"`, Docker is not on the PATH of the Eclipse process
(common on macOS when Eclipse is started from the Dock). The `docker` profile already uses Docker
Desktop's standard locations on macOS; if Docker is installed elsewhere, add the Maven argument
`-Ddocker.executable=/full/path/to/docker` (in Eclipse: Parameter Name `docker.executable`).

### Eclipse

File → Import → Maven → Existing Maven Projects, select the repository root and import the
parent and the six modules. Use a Java 21 JRE.

## Tests

| Level | Where | Count | Needs Docker |
|---|---|---|---|
| Rules (unit) | `trade-common` | 8 | No |
| Security: scopes, deny by default, limits | `trade-security` | 28 | No |
| Store rules, concurrency, outbox, expiry, lock | `trade-processor-service` | 37 | Yes |
| HTTP contracts, Kafka publishing | `trade-capture-service` | 14 | Some |
| Paging and index plans, history, live feed, outages | `trade-query-service` | 30 | Yes |
| Generator, pacing, run control, caps | `trade-simulator` | 22 | No |
| Dashboard | `trade-dashboard-ui` (`npm test`) | 23 | No |
| End-to-end regression | `deploy/regression.sh` against the running Compose stack | 33 checks | Yes |

## Pipeline

`.github/workflows/ci.yml` runs on every push and pull request:

1. Backend build and tests with the coverage gate.
2. Dashboard tests and build.
3. Open-source vulnerability scan: Trivy over the Maven and npm dependencies, and `npm audit`. Any
   critical finding (CVSS 9.0 or above) fails the build. OWASP Dependency-Check also runs when the
   repository secret `NVD_API_KEY` is set (a free key from https://nvd.nist.gov). A CycloneDX
   software bill of materials is kept with each build.
4. End-to-end regression on Docker Compose, then a Trivy scan of the built images (a critical
   finding that has a fix fails the build).

`.github/workflows/codeql.yml` analyses the Java and TypeScript source. Workflow actions are pinned
to commits, base images to digests, and Dependabot proposes updates weekly.

To run the same checks without GitHub:

```bash
mvn verify
```

```bash
docker compose -f deploy/docker-compose.yml up --build -d && deploy/regression.sh
```

```bash
NVD_API_KEY=your-key mvn -Psecurity-scan -DskipTests verify
```

## Known limits

- Simple Basic auth for demonstration: one shared account by default, no TLS, no API gateway.
- Single Kafka broker and single database instances in the Compose file.
- The processor commits one transaction per trade. Throughput has not been measured (phase 9 is on
  hold).
- Request and rate limits are per instance and held in memory.
- The dashboard's live feed reaches only the browsers connected to the query-service instance that
  consumed the event; the dashboard also polls every three seconds.
- Dead-letter topics are not watched or replayed, and MongoDB history has no retention limit.
