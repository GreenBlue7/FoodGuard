#!/bin/sh
set -eu
# All statements share one PostgreSQL session, so the advisory lock covers every migration.
sql=$(mktemp)
trap 'rm -f "$sql"' EXIT HUP INT TERM
cat > "$sql" <<'SQL'
\set ON_ERROR_STOP on
SELECT pg_advisory_lock(73194, 20261114);
CREATE TABLE IF NOT EXISTS foodguard_schema_migrations (
    version text PRIMARY KEY,
    checksum text NOT NULL,
    applied_at timestamptz NOT NULL DEFAULT current_timestamp
);
DO $$ BEGIN
    IF to_regclass('public.products') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM foodguard_schema_migrations WHERE version='001_core.sql') THEN
        RAISE EXCEPTION 'Existing unmanaged database: stop and follow deploy/README.md. No application data changed.';
    END IF;
END $$;
SQL

for file in /migrations/[0-9][0-9][0-9]_*.sql /judge-schema/cases.sql; do
    test -f "$file"
    version=$(basename "$file")
    checksum=$(sed 's/\r$//' "$file" | sha256sum | cut -d ' ' -f 1)
    # Fixed repository filenames only. Never interpolate user-supplied SQL or credentials.
    cat >> "$sql" <<SQL
SELECT EXISTS(SELECT 1 FROM foodguard_schema_migrations WHERE version='$version') AS applied \gset
\if :applied
DO \$\$ BEGIN
    IF NOT EXISTS(SELECT 1 FROM foodguard_schema_migrations WHERE version='$version' AND checksum='$checksum') THEN
        RAISE EXCEPTION 'Previously applied migration changed: $version';
    END IF;
END \$\$;
\else
BEGIN;
SQL
    # Legacy scripts contain their own transaction boundaries. Use one atomic transaction
    # per migration including its history row; preserve all other SQL verbatim.
    sed -e 's/\r$//' -e '/^[[:space:]]*BEGIN;[[:space:]]*$/d' -e '/^[[:space:]]*COMMIT;[[:space:]]*$/d' "$file" >> "$sql"
    cat >> "$sql" <<SQL

INSERT INTO foodguard_schema_migrations(version,checksum) VALUES('$version','$checksum');
COMMIT;
\endif
SQL
done
printf '\nSELECT pg_advisory_unlock(73194, 20261114);\n' >> "$sql"
psql --no-psqlrc --file="$sql"
