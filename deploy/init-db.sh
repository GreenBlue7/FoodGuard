#!/bin/sh
set -eu
# PostgreSQL invokes this only for an empty volume, before the later seed files.
export PGUSER="$POSTGRES_USER"
export PGDATABASE="$POSTGRES_DB"
sh /deploy/migrate.sh
