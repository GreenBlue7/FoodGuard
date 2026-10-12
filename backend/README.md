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
On a repeat import, existing products keep their saved `approvedFunctions`;
only newly inserted products get an automatically parsed list. Product name,
manufacturer, type, and the raw `primaryFnclty` source are refreshed. The
completion message and server log count products whose raw functionality text
changed, because their preserved list may need manual review. Stop other
writers before a full import; cross-process edits are not coordinated.

## Current Vue integration endpoints

`GET /api/products?query=...` searches up to 20 products and returns the Vue
field names (`id`, `name`, `manufacturer`, `reportNo`, `type`,
`approvedFunctions`, `status`). The I0030 product data does not contain a
verified sale status, so `status` is `null` until the team agrees on a data
source or adjusts the frontend type. The older `?keyword=` response remains
available for existing backend clients.

`POST /api/inspections` accepts `{ "productIds": ["report-no"],
"productTypeAnswer": "GENERAL", "adText": "..." }`. At most one product is
accepted for the midterm demonstration. The endpoint completes the rule check
and judge call, saves the result, then returns only `{ "jobId": "1" }`.
This is synchronous execution, not a background queue. The browser can then
call `GET /api/inspections/{jobId}` to read the saved result. A missing or
unrecognized product uses `GENERAL_FOOD` only when the answer is `GENERAL`;
otherwise the judge receives `UNKNOWN` in mode C. The judge's case database
may remain separate; set `JUDGE_URL` to its reachable HTTP endpoint.

The new lookup returns `status` (`DONE`, `FAILED`, or `TIMEOUT`), `request`,
`productInfoSource`, `provisionalStep1`, the original `judgeResponse`, and a
`layer2` projection when the judge responds. That projection maps sentence
positions, verdicts, reasons, model and case-corpus date to the Vue field names.
Judge `HOLD` becomes Vue `REVIEW`; the original verdict remains in
`judgeResponse`. A judge evidence title is mapped to a Vue case only when it
matches the current judge title format, so other evidence remains available in
the raw response. Article numbers are shown with an empty `LawRef.text`, since
the judge does not send article text. The judge also does not expose its
testimonial tag: `isTestimonial` is `null` rather than a guessed boolean.
`dataAsOf` is `null` when `kbVersion` is not an ISO date; when present it dates
the judge's case corpus, not the product database or legal rules. The Vue type
currently requires a boolean and a date string, so the team still needs to
settle those two fields before claiming exact contract compatibility.

`layer1` remains `null`: `rule.json` is provisional and must not be presented
as an approved legal rule. Do not invent a product sale status or legal text to
fill the remaining gaps.

The judge call distinguishes socket timeouts from other connection failures.
Missing or inconsistent judge response fields produce a saved `FAILED` job;
sentence positions are checked against the submitted text using Unicode code
points. Blank ad text, multiple or blank product IDs, and unknown product type
answers return HTTP 400 before the judge is called.
The older `/api/checks` endpoints remain available.

The remaining Vue result contract is tracked in `../api/api_specs.md`.

## Fresh database and backend test check

Create an empty PostgreSQL database, apply `db/001_core.sql` and then
`db/002_pgvector.sql`, and set `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` to
that database. Use a valid JDK 17 or newer installation for `JAVA_HOME`; the
project compiles with a Java 17 toolchain. From `backend`, run
`gradlew.bat test` (or `./gradlew test` on Unix). The Spring context test also
checks that Hibernate can validate the new schema. This check does not create
the judge-owned `cases` table or test the full Vue-to-judge flow.
