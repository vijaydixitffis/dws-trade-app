# Trade Store — Code Review Report

| | |
|---|---|
| Reviewed | 12 November 2026, commit `e39d3d8` (tag `mvp1-dev-12Nov10pm`) |
| Scope | Dashboard UI, capture and query APIs, processor, simulator, Kafka usage, PostgreSQL and MongoDB layers, build, containers and pipeline; about 4,600 lines |
| Method | Read every source and configuration file; reproduced four suspected defects on the running Docker Compose stack |
| Reviewer note | This is a review of code written earlier in this same engagement, by the same author. Treat it as a hard self-review, not an independent one. |

## Changes since this review

The findings below describe commit `e39d3d8`. Later work changed the status of these:

| Finding | Status | What changed |
|---|---|---|
| SEC-1 | Partly addressed | Every service and the dashboard now require HTTP Basic authentication (one demo administrator account). There are still no roles and no TLS. The recommended design is in section 12 of the HLD |
| SEC-2 | Partly addressed | The simulator now requires the login and no longer accepts cross-origin calls. It is still deployed with the rest of the demo |
| SEC-6 | Partly addressed | The dashboard's nginx now sends `X-Content-Type-Options`, `X-Frame-Options` and `Referrer-Policy`. It still runs as root and has no content security policy |
| ARCH-6 | Fixed | The dashboard calls same-origin paths that nginx forwards to the services; CORS configuration was removed |

## 1. Verdict

The three business rules are implemented correctly and are well tested, including under concurrency. The weaknesses are around them: what happens when infrastructure fails, how the system behaves at volume, and security.

**Not ready for production. Fit for a demonstration.** Nine findings are rated High. Four of them can silently lose a trade or part of its history, and each has a small, contained fix.

| Severity | Count | Meaning |
|---|---|---|
| Critical | 0 | Wrong data stored, or a business rule broken |
| High | 9 | Silent loss, a throughput ceiling, or an open door; fix before any real use |
| Medium | 21 | Wrong or fragile behaviour in a specific situation; fix before production |
| Low | 19 | Hygiene, polish, or a small risk |

Evidence labels used below: **Confirmed** means reproduced on the running system; **Inspection** means found by reading the code and not exercised.

## 2. The nine High findings

### REL-1 A trade with a long id is accepted, then silently dropped — Confirmed

- **Where:** `trade-common/.../model/Trade.java`; `V1__trade_store.sql` (`VARCHAR(64)`).
- **What happens:** `Trade` checks only `@NotBlank`. A trade id, counter-party id or book id longer than 64 characters passes validation in the capture API, which answers 202. The insert then fails in PostgreSQL, is retried three times, and the record goes to the dead-letter topic.
- **Reproduced:** an 87-character trade id returned HTTP 202; 0 rows in the store; 0 rejection records; 1 record on `trades.inbound.DLT`.
- **Why it matters:** the caller is told the trade was accepted, and nothing visible ever says otherwise. This breaks the design's own promise that every accepted trade is stored or rejected with a reason.
- **Fix:**
  ```java
  public record Trade(
      @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String tradeId,
      @Min(1) int version,
      @NotBlank @Size(max = 64) String counterPartyId,
      @NotBlank @Size(max = 64) String bookId,
      @NotNull LocalDate maturityDate) {}
  ```
  Add tests at 64 and 65 characters for the API and the listener. Also treat `DataIntegrityViolationException` in the processor as a business rejection with a new reason `INVALID_TRADE`, so anything that slips through is recorded, not dead-lettered.

### REL-2 A three-second database or broker outage dead-letters valid trades — Inspection

- **Where:** `ProcessorConfig.errorHandler` (`FixedBackOff(1000, 3)`).
- **What happens:** every failure that is not a parse or validation error is retried three times, one second apart, then parked. A PostgreSQL restart or failover lasts longer than that, so every trade in flight during it is dead-lettered. Nothing replays the dead-letter topic.
- **Fix:** split the two cases. Poison records go to the dead-letter topic at once (already done). Infrastructure failures retry with exponential back-off and no attempt limit, which holds the partition until the dependency returns:
  ```java
  ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
  backOff.setMaxInterval(30_000);          // no max elapsed time: keep trying
  DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
  handler.addNotRetryableExceptions(JacksonException.class, IllegalArgumentException.class);
  ```
  Add an integration test that pauses the PostgreSQL container for ten seconds and asserts nothing reaches the dead-letter topic. See also OPS-2 for replay.

### REL-3 Expiry can mark trades expired without ever announcing it — Inspection

- **Where:** `ExpiryService.expireMatured`.
- **What happens:** each batch is committed, and only then are the `EXPIRED` events published. If the publish fails or the process stops in between, those rows are already `Y` and are never selected again, so their events are lost for good. The MongoDB history and the dashboard then disagree with the store.
- **Fix:** a transactional outbox. Write the event rows in the same transaction as the update, and have a relay publish and delete them:
  ```sql
  CREATE TABLE outbox (id BIGSERIAL PRIMARY KEY, topic TEXT, key TEXT, payload JSONB, created_at TIMESTAMPTZ DEFAULT now());
  ```
  This also closes REL-5. A cheaper interim fix: publish inside the transaction before commit, so a failed publish rolls the batch back and it is retried.

### REL-4 A MongoDB outage makes the history skip events permanently — Inspection

- **Where:** `trade-query-service` has no `CommonErrorHandler` bean, so `Projector` uses Spring Kafka's default: ten immediate attempts, then log and skip the record.
- **What happens:** if MongoDB is unreachable for even a second, every event consumed in that window is dropped from `trade_events` and `trade_rejections` and the offsets move on.
- **Fix:** give the query service the same handler as REL-2 (exponential back-off without limit for infrastructure errors, dead-letter topic for unreadable messages). Test by pausing the MongoDB container.

### PERF-1 The processor handles one trade per transaction with a blocking publish — Inspection, not measured

- **Where:** `TradeListener`, `TradeStore.save`, `KafkaOutcomePublisher.send`.
- **What happens:** every trade costs two or three SQL round trips in its own transaction, then a synchronous Kafka send that waits for the broker with no batching. Per-thread throughput is bounded by roughly one database commit plus one broker round trip per trade. With three threads this is likely a few thousand trades per second at best. That figure is an estimate; phase 9 has not measured it.
- **Fix, in order of value:**
  1. Use a batch listener and send outcomes asynchronously, waiting once per batch for all acknowledgements.
  2. Collapse the version claim and the upsert into one statement (a data-modifying CTE), halving round trips.
  3. Process a poll batch in one transaction with JDBC batching. All records in a partition batch for the same trade stay in order.
  4. Set `linger.ms` and compression on the processor's producer as the capture service already does.

### PERF-2 Under load the dashboard re-queries everything twice a second — Inspection

- **Where:** `App.tsx` (live-feed handler and 3-second poll); `QueryController.summary`.
- **What happens:** any live event triggers a full refresh at most every 500 ms: summary, trade page, 200 rejections and simulator status. The summary alone runs two `count(*)` queries on PostgreSQL and four counts on MongoDB. Each open browser does this, so read load grows with both trade volume and viewers, on the same database the processor writes to.
- **Fix:** keep running totals instead of counting. Maintain the six counters in the query service as events are projected (one MongoDB counter document updated with `$inc`), serve the summary from that, and send the counters in the live event so the browser updates without refetching. Refetch the table only when the visible page could have changed, and cache `/summary` for one second.

### PERF-3 The trade list cannot use an index — Confirmed

- **Where:** `TradeReadRepository.find` and `count`.
- **What happens:** the filter is `upper(trade_id) LIKE ?`, which no index covers. The order is `trade_id ASC, version DESC`, which the primary key `(trade_id, version)` cannot serve in one pass. Paging uses `OFFSET`, and every request also runs `count(*)`.
- **Reproduced:** `EXPLAIN` on the running database shows `Seq Scan on trade` with a filter, followed by a `Sort`. The table had 816 rows, so the plan is cheap today; the point is that no index exists that could serve it at any size.
- **Fix:**
  ```sql
  CREATE INDEX trade_sequence_idx ON trade (trade_id text_pattern_ops, version DESC);
  ```
  Make the filter a case-sensitive prefix (`trade_id LIKE 'T2%'`) or store an upper-cased column. Replace `OFFSET` with keyset paging (`WHERE (trade_id, -version) > (?, ?)`), and return an estimated total from `pg_class.reltuples` unless a filter is applied.

### SEC-1 No authentication, authorisation or transport security — Inspection (already acknowledged in the HLD)

- **Where:** all four services and Actuator endpoints.
- **What happens:** anyone who can reach the ports can post trades, read the store, read metrics and drive the simulator. Kafka, PostgreSQL and MongoDB links are plaintext.
- **Fix:** Spring Security with OAuth2 resource-server (JWT) on capture and query, scopes `trades:write` and `trades:read`; mutual TLS or SASL for Kafka; TLS to both databases; Actuator on a separate management port with only `health` public.

### SEC-2 The simulator is an open load generator — Inspection

- **Where:** `SimulationController`, `SimulatorConfig` (CORS allows `POST` and `DELETE`).
- **What happens:** one unauthenticated request can ask for ten million trades. A web page on an allowed origin can do the same from a visitor's browser.
- **Fix:** do not deploy the simulator outside test environments; guard it with a Spring profile and the same authentication as SEC-1; cap `count` by configuration.

## 3. All findings by category

### 3.1 Reliability and correctness

| Id | Sev | Evidence | Finding | Fix |
|---|---|---|---|---|
| REL-1 | High | Confirmed | Long ids accepted then dropped | Section 2 |
| REL-2 | High | Inspection | Short outage dead-letters valid trades | Section 2 |
| REL-3 | High | Inspection | Expiry events lost if publish fails after commit | Section 2 |
| REL-4 | High | Inspection | Projector skips events when MongoDB is down | Section 2 |
| REL-5 | Medium | Inspection | Processor commits then publishes. After a crash in between, the trade is reprocessed and its event says `REPLACED` although it was new | Outbox (REL-3), or add a `revision` counter column and derive the type from it |
| REL-6 | Medium | Inspection | Capture waits 10 s for Kafka but the producer keeps trying for 120 s (`delivery.timeout.ms` default). A caller can get 503 and the trade is still delivered later. A batch can also be half published when it fails | Set `delivery.timeout.ms=8000`, `request.timeout.ms=3000`; return per-trade results on partial failure; accept an idempotency key |
| REL-7 | Medium | Inspection | The start-up expiry run executes on the main thread in `ApplicationReadyEvent`, without the lock. A large backlog delays readiness, and a Kafka failure there aborts start-up | Trigger it through the scheduler with the lock, asynchronously, and catch failures |
| REL-9 | Medium | Confirmed | MongoDB stores `LocalDate` as an instant at the JVM zone's midnight (`maturityDate: ISODate('2027-11-12T00:00:00Z')`). If an instance runs in another zone, or the zone setting changes, dates read back a day off | Register converters so `LocalDate` is stored as an ISO string |
| REL-8 | Low | Inspection | Rows skipped by `SKIP LOCKED` during expiry wait until the next daily run | Run the job hourly; it is idempotent |
| REL-10 | Low | Inspection | A trade maturing today, accepted at 23:59 and processed after midnight, is rejected by the processor after the caller got 202 | Acceptable; document it, or carry the capture business date in a header and honour it |
| REL-11 | Low | Inspection | `ACCEPTED` versus `REPLACED` relies on PostgreSQL's internal `xmax = 0` | Use the `revision` column from REL-5 |
| REL-12 | Low | Inspection | Capture rejections get a random id, so a client retry records the same rejection twice | Derive the id from a hash of the trade and business date |

### 3.2 Performance and scalability

| Id | Sev | Evidence | Finding | Fix |
|---|---|---|---|---|
| PERF-1 | High | Inspection | One transaction and one blocking publish per trade | Section 2 |
| PERF-2 | High | Inspection | Dashboard refresh storm and count queries | Section 2 |
| PERF-3 | High | Confirmed | Trade list has no usable index | Section 2 |
| PERF-4 | Medium | Inspection | `LiveFeed.publish` writes to every browser from the Kafka listener thread. One slow client stalls the MongoDB projection. No heartbeat, no limit on subscribers, emitters never time out | Hand each event to a bounded per-subscriber queue on its own thread and drop slow clients; send a comment line every 15 s; cap subscribers |
| PERF-5 | Medium | Inspection | Expiry publishes events one at a time, waiting for each | Send the batch asynchronously and wait once (or use the outbox) |
| PERF-7 | Medium | Inspection | The simulator keeps one boxed `Integer` per trade. A ten-million-trade run needs a few hundred megabytes | Use a primitive `int[]` that grows, or cap distinct trades and reuse them |
| PERF-6 | Low | Inspection | No tuning of `max.poll.records`, fetch sizes or the connection pool; a meter lookup per trade | Tune in phase 9 from measurements; cache the counters |

### 3.3 Functional defects

| Id | Sev | Evidence | Finding | Fix |
|---|---|---|---|---|
| BUG-1 | Medium | Confirmed | Simulator ignores rates below 10 and above 10,000 per second. `perTick` is clamped to 1..1000 per 100 ms tick. Asking for 30 trades at 1 per second finished in 4 s instead of 30. The API accepts up to 50,000 per second but sends 10,000 | Carry the fractional remainder between ticks (`owed += rate * elapsed`), send `floor(owed)`, and split into several batches when above 1,000; add a test on elapsed time |
| BUG-2 | Medium | Inspection | The run dialog closes even when the simulator refuses the run, because `onStart` swallows the error. The form also lets through values the server rejects: negative percentages, a prefix with spaces, more than 3,650 days | Make `onStart` return success or the server's message; keep the dialog open and show it; mirror the server rules in the form |
| BUG-3 | Medium | Inspection | Dashboard requests are never cancelled. A slow response can overwrite a newer one, so the table can show the wrong filter or page. Every keystroke in the filter fires a request | Use `AbortController` per refresh and ignore out-of-date responses; debounce the filter by 300 ms |
| BUG-4 | Low | Inspection | The simulator's HTTP client has no timeouts. If capture hangs, the run hangs and Stop has no effect | Set connect and read timeouts; check `stopRequested` after a failed send |
| BUG-5 | Low | Inspection | The batch endpoint answers 202 even when every trade in it was rejected | Answer 207 with the per-trade results, or 422 when none were accepted |

### 3.4 Security

| Id | Sev | Evidence | Finding | Fix |
|---|---|---|---|---|
| SEC-1 | High | Inspection | No authentication, authorisation or TLS | Section 2 |
| SEC-2 | High | Inspection | Simulator is an open load generator | Section 2 |
| SEC-3 | Medium | Inspection | Database credentials `trade/trade` are in `application.yml` and the Compose file. The query service uses the same account as the processor, with write rights | Inject secrets from the environment or a vault; create a read-only role for the query service |
| SEC-4 | Medium | Inspection | No request size or rate limits. The batch size limit is checked only after the whole body is parsed. Unauthenticated past-maturity trades each create a MongoDB document | Limit body size at the server and proxy; add rate limiting at the gateway; see SEC-1 |
| SEC-5 | Medium | Inspection | Pipeline actions are pinned by tag (`@v4`, `@v0.36.0`), not commit hash; base images by tag, not digest. No image scan or SBOM | Pin to hashes and digests; add Dependabot or Renovate; scan built images with Trivy; publish an SBOM |
| SEC-6 | Low | Inspection | The dashboard's nginx runs as root with default configuration and no security headers | Use `nginxinc/nginx-unprivileged`; add CSP, `X-Content-Type-Options` and `Referrer-Policy` |
| SEC-7 | Low | Inspection | No static security analysis. The assignment's word "blocker" is a SonarQube severity and nothing checks for it | Add CodeQL or a SonarCloud quality gate |

### 3.5 Architecture and maintainability

| Id | Sev | Evidence | Finding | Fix |
|---|---|---|---|---|
| ARCH-1 | Medium | Inspection | The query service reads the processor's tables directly. The two services cannot change the schema independently | Accept and document it, or build the trade list from events into a read model the query service owns |
| ARCH-2 | Medium | Inspection | Kafka messages are plain JSON with no version field or schema registry. All services share the Java records, so they must be deployed together when a record changes | Add a `schemaVersion` field now; move to Avro or JSON Schema with a registry when a second team consumes the topics |
| ARCH-3 | Medium | Inspection | Topics are declared in two services with `replicas(1)` hard-coded. A production cluster cannot set replication without a code change, and a mismatched partition setting between services would make Spring add partitions, which breaks per-trade ordering | Make replication a property; declare topics in one place, or create them outside the applications |
| ARCH-4 | Low | Inspection | The Flyway migration lives in the shared jar | Move it to the processor, which owns the schema |
| ARCH-5 | Low | Inspection | Test container helpers are duplicated in two modules. `.mvn/jvm.config` forces IPv4 for everyone because of one machine's network | Share a test-support module; move the IPv4 flag to local `MAVEN_OPTS` |
| ARCH-6 | Low | Inspection | The dashboard's API addresses are fixed at build time and default to `localhost` | Use relative URLs and let nginx proxy `/api` to the services; this also removes the need for CORS |

### 3.6 Operability

| Id | Sev | Evidence | Finding | Fix |
|---|---|---|---|---|
| OPS-1 | Medium | Inspection | No correlation id, no trade id in the logging context, no tracing, plain-text logs | Propagate a correlation id from HTTP header to Kafka header to MDC; enable Micrometer Tracing; structured JSON logs |
| OPS-2 | Medium | Inspection | Nothing watches or replays the dead-letter topic. No metrics exporter, no alerts | Export to Prometheus; alert on consumer lag and dead-letter count; add a small replay command |
| OPS-3 | Medium | Inspection | No liveness or readiness probes. Compose starts services when dependencies have started, not when they are healthy | Enable `management.endpoint.health.probes`; add health checks to service containers and `condition: service_healthy` |
| OPS-4 | Low | Inspection | MongoDB history and Kafka topics have no retention policy set | TTL index on `at`; explicit `retention.ms` per topic |
| OPS-5 | Low | Inspection | No container memory settings for the JVM; shutdown behaviour of consumers under load is untested | Set `-XX:MaxRAMPercentage`; test a rolling restart in phase 9 |

### 3.7 User interface and accessibility

| Id | Sev | Evidence | Finding | Fix |
|---|---|---|---|---|
| UI-1 | Medium | Inspection | The dialog does not trap focus, so Tab moves to controls behind it, and the page behind still scrolls. Tabs have no `tabpanel` or arrow-key support | Use the native `<dialog>` element with `showModal()`, which provides both; complete the tab roles |
| UI-2 | Low | Inspection | "Rejected at" uses the browser's zone and locale while the business date uses the server's zone | Show times in the business zone, labelled |
| UI-3 | Low | Inspection | Only the latest 200 rejections are shown, with no paging or filter; no loading state on first paint | Add paging and a reason filter; add a loading indicator |

### 3.8 Tests and build

| Id | Sev | Evidence | Finding | Fix |
|---|---|---|---|---|
| TEST-1 | Medium | Inspection | The tests cover the rules well but not failure. Nothing tests a database, broker or MongoDB outage, or two processors competing for the expiry lock. REL-1 to REL-4 all sit in this gap | Add Testcontainers tests that pause each dependency; add a two-instance expiry test |
| TEST-2 | Low | Inspection | Dashboard tests live in one file and stub `fetch`; the live feed and a real browser are untested | Split per component; add one Playwright test against the Compose stack |
| TEST-3 | Low | Inspection | No load test, so every throughput statement is unproven | Phase 9 (on hold) |
| TEST-4 | Low | Inspection | No static analysis in the build | Add Error Prone or SpotBugs, and ESLint for the dashboard |

## 4. What is sound

- **The version rule.** One atomic conditional upsert with a row lock. It is correct for any number of processor instances and proven by a 40-version, 8-thread test.
- **One definition of the maturity boundary**, shared by capture, processor and expiry, with an injected clock.
- **Business rejections are separated from technical failures** (rejected topic versus dead-letter topic).
- **Idempotent projection.** Event ids come from the Kafka position, so redelivery overwrites.
- **Tests use real PostgreSQL, Kafka and MongoDB**, not mocks of them, and were written before the code.
- **The pipeline's vulnerability gate works.** It forced the Spring Boot 4 migration on real findings.
- **Small, readable modules** with the rules free of framework code.

## 5. Suggested order of work

| Step | Findings | Effort | Why first |
|---|---|---|---|
| 1 | REL-1, REL-2, REL-4, BUG-1, BUG-2 | 1 day | Small changes that stop silent loss and fix confirmed bugs |
| 2 | TEST-1 | 1 day | Proves step 1 and guards the next steps |
| 3 | REL-3, REL-5, REL-11 (outbox and `revision`) | 1.5 days | Closes the remaining loss window |
| 4 | PERF-3, PERF-2, PERF-4, BUG-3 | 1.5 days | Read side stops competing with writes |
| 5 | PERF-1, PERF-5 with phase 9 measurements | 2 days | Raise the throughput ceiling against real numbers |
| 6 | SEC-1 to SEC-5, OPS-1 to OPS-3 | 3 to 4 days | Required before any deployment beyond a demo |
| 7 | Remaining Medium and Low items | 2 days | Hardening and polish |

Effort figures are rough estimates for one developer.

## 6. How the confirmed items were reproduced

| Finding | Command or step | Observed |
|---|---|---|
| REL-1 | `POST /api/v1/trades` with an 87-character `tradeId` | HTTP 202; store 0 rows; rejections 0; dead-letter topic 1 record |
| BUG-1 | `POST /api/v1/simulations` with `{"count":30,"ratePerSecond":1}` | Run finished in 4 s; 30 s expected |
| PERF-3 | `EXPLAIN SELECT * FROM trade WHERE upper(trade_id) LIKE 'S%' ORDER BY trade_id ASC, version DESC LIMIT 25` | `Seq Scan` with filter, then `Sort` (816 rows in the table) |
| REL-9 | `db.trade_events.findOne()` in `mongosh` | `maturityDate: ISODate('2027-11-12T00:00:00.000Z')` |

The checks left one oversized test trade on the dead-letter topic and 30 simulator trades in the store of the local demo stack.
