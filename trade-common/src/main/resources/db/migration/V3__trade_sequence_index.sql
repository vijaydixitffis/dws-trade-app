-- Serves the dashboard's trade list: ordered by trade id, then version descending, with prefix
-- filters and keyset paging. The "C" collation compares bytes, so a prefix is a simple range and
-- the same index serves the ordering, the prefix filter and the cursor.
CREATE INDEX trade_sequence_idx ON trade (trade_id COLLATE "C" ASC, version DESC);
