-- Highest version seen per trade. The version rule is enforced by an atomic upsert on this table.
CREATE TABLE trade_latest (
    trade_id       VARCHAR(64) PRIMARY KEY,
    latest_version INTEGER     NOT NULL
);

-- The trade store: one row per (trade id, version).
CREATE TABLE trade (
    trade_id         VARCHAR(64) NOT NULL,
    version          INTEGER     NOT NULL,
    counter_party_id VARCHAR(64) NOT NULL,
    book_id          VARCHAR(64) NOT NULL,
    maturity_date    DATE        NOT NULL,
    created_date     DATE        NOT NULL,
    expired          CHAR(1)     NOT NULL DEFAULT 'N' CHECK (expired IN ('Y', 'N')),
    PRIMARY KEY (trade_id, version)
);

-- Lets the expiry job find unexpired, matured trades without scanning the table.
CREATE INDEX trade_unexpired_maturity_idx ON trade (maturity_date) WHERE expired = 'N';

-- ShedLock: ensures the expiry job runs on one processor instance at a time.
CREATE TABLE shedlock (
    name       VARCHAR(64)  PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
