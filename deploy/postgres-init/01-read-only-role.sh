#!/bin/sh
# Creates the account the query service uses. It can read the trade store and nothing else, so a
# flaw in the read side cannot change a trade. Tables created later by the processor's migrations
# are readable automatically through the default privileges.
set -eu
psql -v ON_ERROR_STOP=1 -v ro_password="$TRADE_RO_PASSWORD" -v owner="$POSTGRES_USER" \
     --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
CREATE ROLE trade_ro LOGIN PASSWORD :'ro_password';
SELECT format('GRANT CONNECT ON DATABASE %I TO trade_ro', current_database()) \gexec
GRANT USAGE ON SCHEMA public TO trade_ro;
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" IN SCHEMA public GRANT SELECT ON TABLES TO trade_ro;
SQL
