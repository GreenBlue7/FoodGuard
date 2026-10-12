# Approved-function extraction review (2026-10-12)

The I0030 `PRIMARY_FNCLTY` field is free text. The backend stores its original
text in `products.primary_fnclty` and a heuristic list in
`product_approved_functions`. The list is not an independently verified set of
legally approved claims.

## Observed problems and correction

- Standalone nutrient headings such as `비타민D` and `판토텐산` were sometimes
  stored as functions when followed by numbered claims.
- Korean list markers such as `(가)` remained at the start of some functions.
- A broad rule that skipped every line before a numbered claim would also
  remove plausible functions such as `근육, 결합조직 등 신체조직의 구성성분`.

The current parser only skips a reviewed set of clear nutrient/section
headings when a numbered claim follows. It strips a leading `(가)`-style marker
from a line. It leaves ambiguous text in place for later review.

The existing local `capstone_db` was backed up before correction. The opt-in
reconciliation updated 2,362 products whose saved lists exactly matched the
old parser output. The function-row count changed from 283,986 to 280,486;
product and inspection counts remained 45,874 and 4. A second preview reported
zero further automatic changes. Two products (`20190009483569` and
`20190009483854`) were skipped because the change was not a simple heading or
list-marker removal. Among the products proposed for correction, none had a
stored list that differed from the old parser output before the correction.

## Remaining review

- Some headings without a numbered following claim remain, for example
  `테아닌` and `셀레늄` in product `200400150832008`. A blanket removal could
  discard valid short functions, so these need source-level review.
- Source annotations can be fragmented, for example manufacturing-method
  footnotes on product `20040015039249` and `산화 ･ 환원` on product
  `20190009483569`. The two skipped products should be reviewed before a
  targeted migration.
- The team still needs to confirm how this heuristic list may be used as an AI
  input. The original source text remains available.
- The repeat-import path now reuses existing product entities and leaves their
  stored function lists untouched. Only newly inserted products receive parsed
  functions. If an existing raw source changes, its saved list is retained and
  the import reports the count for manual review. This was verified with a
  two-product import on an isolated database, not with a full live re-import.
  Back up the live database before using that operation.

## Demo product check (2026-10-13)

Read-only comparison of report number `20040015039105` (고려홍삼골드플러스)
confirmed five saved entries against the five numbered clauses in the current
`primary_fnclty` source: 면역력 증진, 피로개선, 혈소판 응집 억제를 통한 혈액흐름,
기억력 개선, and 항산화. This checks text segmentation for this one product,
not legal approval of any advertising claim. The two ambiguous products above
still retain their previous lists and are unsuitable as unreviewed demo cases.

## Verification

The core schema and pgvector extension were applied to an empty temporary
PostgreSQL database. The Spring backend started against it; product search,
inspection creation, and inspection retrieval worked with fixture data. The
inspection was `PARTIAL` because the judge service was not running. The backend
test suite passed 19 tests with no failures or skips. The temporary database
was removed after verification.

The reconciliation procedure and fresh-database setup are in `README.md`.

## Isolated integration check (2026-10-13)

The complete backend test suite passed 23 tests with no failures, errors, or
skips against a separate temporary PostgreSQL database. In an HTTP smoke check,
a mock I0030 response refreshed an existing product's raw functionality while
keeping its manually saved `MANUAL-CLAIM` entry. A new product received a parsed
entry. A second import reported zero changed source texts. Product search by
`query`, `POST /api/inspections`, and `GET /api/inspections/{jobId}` worked with a
mock judge. Both unregistered `GENERAL` and `UNKNOWN` flows produced saved jobs;
two product IDs returned HTTP 400 and an absent job returned HTTP 404. The
temporary servers and database were removed. The live `capstone_db` counts
remained 45,874 products, 280,486 function rows, and 4 inspection records.

This check used a mock judge response. It does not verify the real AI service,
the pending `layer2` mapping, or the final frontend result screen.
