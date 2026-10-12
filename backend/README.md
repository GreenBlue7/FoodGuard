# Spring backend setup

This module uses PostgreSQL. For a new empty `foodguard` database, apply
`db/001_core.sql` before starting Spring. For the existing Docker
`capstone_db` on port 5435, keep its product and inspection data and apply only
`db/upgrade_existing_capstone.sql`. Do not apply `001_core.sql` to the existing
database. The upgrade script also adds the inspection-to-product foreign key
and inspection-date index; existing inspection report numbers must refer to
products. The application validates tables on startup and does not alter them.

The existing Docker database was imported before `approvedFunctions` was
stored. After creating `product_approved_functions`, the one-time backfill can
populate it from `products.primary_fnclty`. Set `DB_URL`, `DB_USERNAME`, and
`DB_PASSWORD` in the shell first, then run these commands from `backend`:

```powershell
.\gradlew.bat bootRun --args="--spring.main.web-application-type=none --foodsafety.approved-functions-backfill-enabled=true --foodsafety.approved-functions-backfill-mode=preview"
.\gradlew.bat bootRun --args="--spring.main.web-application-type=none --foodsafety.approved-functions-backfill-enabled=true --foodsafety.approved-functions-backfill-mode=apply"
```

Preview only reports counts. Apply inserts functions for products that have no
saved functions, in 500-product transactions; it does not change existing
product or inspection rows. The parser is heuristic, so review representative
products before treating parsed phrases as verified claims. The backfill is
disabled during normal application startup.
For a read-only review of all source products after the table has been filled,
use `--foodsafety.approved-functions-backfill-mode=refresh-preview`. The
backfill has no mode that overwrites saved functions; parser revisions require
a separately reviewed data migration. Some ingredient headings in free-text
source data still need manual review.

The parser now removes a small set of clear nutrient/section headings only when
they precede an explicitly numbered claim. It also removes leading Korean
section markers such as `(가)` from the extracted phrase. Short claims such as
`수면` and `항산화` are retained. This is text segmentation, not legal approval of
the extracted phrases. Ambiguous headings remain for manual review.

To reconcile previously saved lists after this parser revision, first back up
the database and stop other writers. Preview, then apply the separate opt-in
runner from `backend`:

```powershell
.\gradlew.bat bootRun --args="--spring.main.web-application-type=none --foodsafety.approved-functions-reconcile-enabled=true --foodsafety.approved-functions-reconcile-mode=preview"
.\gradlew.bat bootRun --args="--spring.main.web-application-type=none --foodsafety.approved-functions-reconcile-enabled=true --foodsafety.approved-functions-reconcile-mode=apply"
```

The runner updates a product only when every stored function and its position
still exactly match the previous parser output. It skips manually changed lists
and any transformation that cannot be explained by removing a clear heading or
its leading `(가)`-style marker. Each 500-product batch is transactional. A
second preview after apply should report `changedProducts=0`; it reports already
reconciled products separately. The runner is disabled during normal startup.

The default JDBC URL is `jdbc:postgresql://localhost:5432/foodguard`. To use
the existing Docker database, set
`DB_URL=jdbc:postgresql://localhost:5435/capstone_db`, `DB_USERNAME=postgres`,
and `DB_PASSWORD` to the password configured for that container. The Python
judge has a separate `DATABASE_URL`; set it to the chosen shared database when
the judge is integrated. The judge's `.env.example` currently points elsewhere.
`db/002_pgvector.sql` enables the pgvector extension in that database. The
judge's `cases` table is defined in `judge/sql/cases.sql` and is not created
by the backend scripts.

`FOOD_SAFETY_API_KEY` is needed only when running the I0030 import. The import
controller is disabled unless `FOOD_SAFETY_IMPORT_ENABLED=true`; enable it
only in a controlled environment because it has no user authentication.
`FOOD_SAFETY_TIMEOUT_MS` sets the API connection/read timeout (default 30000).
Set `JUDGE_URL` to the full judge endpoint (default `http://localhost:8000/judge`)
and `JUDGE_TIMEOUT_MS` to the HTTP connection/read timeout (default 30000).
The judge requires its own configuration and database tables.

With a JDK available through `JAVA_HOME`, run `./gradlew bootRun` (Windows:
`gradlew.bat bootRun`) from this directory. Product import is an explicit
`POST /api/data/load-all` operation; it is not started by the application.
An import error returns HTTP 502 and reports how many records were saved before
the failure. Concurrent imports within one backend process return HTTP 409.
This operation is not atomic across all API pages; coordination across multiple
backend processes has not been implemented.

The public Vue API contract remains open in `../api/api_specs.md`.

## Fresh database and backend test check

Create an empty PostgreSQL database, apply `db/001_core.sql` and then
`db/002_pgvector.sql`, and set `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` to
that database. Use a valid JDK 17 or newer installation for `JAVA_HOME`; the
project compiles with a Java 17 toolchain. From `backend`, run
`gradlew.bat test` (or `./gradlew test` on Unix). The Spring context test also
checks that Hibernate can validate the new schema. This check does not create
the judge-owned `cases` table or test the full Vue-to-judge flow.
