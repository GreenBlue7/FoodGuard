# Backend deployment preparation

This is a separate backend stack for one server, not a change to the team's local
docker-compose.yml. It provides PostgreSQL, schema migration, judge and Spring.
The frontend build, domain, TLS certificate and HTTPS reverse proxy remain separate.
No real API keys, users, product rows, inspection records or mail are included.

## Fresh server

1. Copy `deploy/.env.example` to `deploy/.env` and fill in secrets on the server.
   Use a long URL-safe random hex DB password, a stable Base64 JWT secret of at least
   32 random bytes, and the actual HTTPS service URL. Set real Gemini/MFDS keys
   before testing their corresponding features. Blank keys allow startup only.
2. From the repository root, run:

   ```sh
   docker compose --env-file deploy/.env -f deploy/compose.yml build
   docker compose --env-file deploy/.env -f deploy/compose.yml up -d
   docker compose --env-file deploy/.env -f deploy/compose.yml ps -a
   docker compose --env-file deploy/.env -f deploy/compose.yml logs migrate backend judge
   ```

   Migration must exit with code 0; backend/judge must become healthy. The backend
   health check checks its listening port after Spring startup/schema validation;
   it does not guarantee that Gemini, SMTP or MFDS are reachable.
3. Serve the team's frontend and `/api`, `/email`, `/reports` on the **same HTTPS
   origin**. The refresh cookie uses Secure + SameSite=Strict. Use
   `nginx-backend.conf.example` inside the host's HTTPS server block and retain the
   team's SPA fallback for `/history` and `/inspections/...`. Match the proxy port
   if BACKEND_HOST_PORT changes. A proxy in another container needs its own network
   setup: host-loopback in this example does not address another container.
4. Keep one backend instance while the in-process collection lock is used.
   Enable `FOOD_SAFETY_SCHEDULED_REFRESH_ENABLED=true` after checking the key, then
   recreate backend with the same Compose command. At 03:00 Asia/Seoul the first
   collection is full, subsequent collections use CHNG_DT with overlap, and a
   successful full collection is repeated after 7 days. Failures retain both dates.
   Unknown historic full-sync dates also force a full collection. No product absent
   from a full response is deleted automatically. The import admin endpoint stays off.
5. The first full collection can wait until the next scheduled run. If data is
   needed before launch, import an approved product backup or temporarily choose a
   nearer collection cron, confirm success, then restore the daily schedule.
   The judge's `cases` table is created but **empty**. Import the team's approved
   case/embedding data using its existing workflow; schema creation alone does not
   populate the knowledge base or verify retrieval quality.
6. For email, configure a real authenticated STARTTLS SMTP server, verified sender
   and public URL before enabling NOTIFICATION_EMAIL_ENABLED. The recipient must
   verify their address and opt in. Use a consenting test recipient for delivery
   validation; no mail is sent by this setup procedure.
7. Before opening the service, verify login/refresh after reload, a real product
   inspection, history/report ownership, reinspection and the notification flow.
   Validate real AI duration and cost separately. Inspection submission is asynchronous; poll the returned job ID. Judge response
   timeout is 180s. See backend/ASYNC_INSPECTIONS.md for interruption recovery.

## Migration behavior and upgrades

`migrate.sh` applies backend `001` through `012` and the judge-owned cases schema,
in order. A PostgreSQL advisory lock serializes migration processes. Each SQL file
and its SHA-256 history row commit atomically. A second run skips unchanged files;
editing an already applied file stops startup. Add a new numbered migration for
future changes. Legacy BEGIN/COMMIT lines are removed only in the execution copy.
Checksums normalize CRLF to LF so Windows/Linux checkouts represent the same SQL.
Database and backend timestamps use Asia/Seoul, matching existing local records.

The migration service runs before backend startup and is rerun on subsequent
Compose deployments. To explicitly rerun it during maintenance:

```sh
docker compose --env-file deploy/.env -f deploy/compose.yml run --rm migrate
```

**Existing manually managed database:** the runner deliberately refuses a database
that already contains products without migration history. Do not initialize it,
delete its volume or fabricate migration history. Back up and restore a copy first.
For the current manually managed local database, 001–009 were already applied;
Apply missing migrations through `012_inspection_request_fingerprint.sql` before starting this backend version.
Migrating an existing deployment to the managed runner needs a separate audited
baseline procedure. Fresh production volumes are independent of local volumes.

Before upgrading, back up the DB, run migrations while application writes are
stopped, rebuild changed images and restart. A failed migration stops dependent
startup, but an already-running old backend is not automatically stopped by Compose;
schedule maintenance explicitly. Never use `down -v` to upgrade.

Example Linux host backup (contains private data; keep outside the repository):

```sh
mkdir -p backups
chmod 700 backups
umask 077
docker compose --env-file deploy/.env -f deploy/compose.yml exec -T db \
  sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > backups/foodguard.dump
test -s backups/foodguard.dump
```

Periodically restore the dump into a separate empty database and check it; file
existence alone does not verify recovery. Do not overwrite the running database.
This preparation does not provision a cloud server, enable a public endpoint,
perform an actual production deployment or run paid AI requests.
