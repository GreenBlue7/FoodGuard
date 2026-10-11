# API Specification

## Status

Integration draft, 2026-10-12. This records the current contracts and the decisions
needed before the Vue-to-Spring API can be implemented. It is not a team-approved
replacement for either module's existing API.

## Existing interfaces

| Caller | Current request | Current response | Mismatch |
| --- | --- | --- | --- |
| Vue product search | `GET /api/products?query=...` | `Product[]` with `id`, `name`, `manufacturer`, `reportNo`, `type`, `approvedFunctions`, `status` | Spring expects `keyword` and returns `prdlstReportNo`, `prdlstNm`, `bsshNm`, `productType`, `primaryFnclty`, `approvedFunctions`. |
| Vue inspection | `POST /api/inspections` with `{productIds: string[], productTypeAnswer?: string, adText: string}` | `{jobId: string}` | Spring has `POST /api/checks/full-process` with a single report number and returns the completed result. |
| Vue result lookup | `GET /api/inspections/{jobId}` | `InspectionJob` with `PENDING/RUNNING/DONE/FAILED/TIMEOUT`, `layer1`, `layer2` | Spring has `GET /api/checks/{id}` and `COMPLETED/PARTIAL/REVIEW_REQUIRED`. |
| Spring judge request | `POST /judge` | Judge response | Backend adapter now sends the judge's actual camel-case `JudgeRequest` and reads `JudgeResponse`; the end-to-end call still requires runtime verification. |

Source of truth for the current consumer shapes: `frontend/src/types/inspection.ts`,
`frontend/src/api/inspection.ts`, and `judge/app/schemas.py`.

## Spring to judge: implemented wire shape

```json
{
  "requestId": "generated-uuid",
  "mode": "C",
  "product": {
    "type": "HEALTH_FUNCTIONAL",
    "names": ["제품명"],
    "approvedFunctions": ["인정 기능성"]
  },
  "adText": "검사할 문구"
}
```

`mode C` requires a product. The judge accepts `HEALTH_FUNCTIONAL`,
`FUNCTIONAL_LABELED_FOOD`, `GENERAL_FOOD`, or `UNKNOWN`. Its response contains
`sentences`, `results`, `model`, `kbVersion`, and `elapsedMs`; a result contains
`sentenceIds`, `text`, `status`, `violationType`, `reason`, `evidence`, `span`,
and `revision`. The backend currently calls the judge only when it considers
the product type confirmed. `kbVersion` is the case corpus date, not the MFDS
product data date.

## Decisions needed for Vue to Spring

1. Choose the public URL and job model. The existing Vue client expects an
   asynchronous job (`POST /api/inspections` then `GET /api/inspections/{jobId}`).
   The current Spring endpoint blocks until the judge returns. Agree on
   synchronous versus durable asynchronous execution and on status transitions.
2. Define how `productIds[]` map to MFDS report numbers, how several products
   are passed to the judge, and what the result shows for a mixed set.
3. Define a truthful product `status`. I0030 fields currently saved by Spring
   do not establish `ACTIVE`, `DISCONTINUED`, or `EXPORT_ONLY`; the backend must
   not guess `ACTIVE` just to fill Vue's current type.
4. Confirm the permitted handling of an unregistered product. A missing I0030
   record alone does not prove `GENERAL_FOOD`. Decide when a seller answer is
   enough to apply general-food rules and whether `mode A` is used for unknowns.
5. Define the layer-1 rule result, including matched ranges, and who approves
   the rule content. The current backend produces only missing phrases.
6. Map judge `results/evidence` to Vue `findings/lawRefs/cases` without inventing
   article text, case year, or source. The judge response currently lacks some
   fields required by Vue's `Finding` type. Confirm which module supplies them.
7. Define time zone/date formats, text range units (Vue expects Unicode code
   points with an exclusive end), failure/timeout response, and model/data
   provenance stored with each inspection.

Until these decisions are made, the existing Spring `/api/checks` endpoints are
legacy integration endpoints. Their responses should not be treated as matching
the Vue `InspectionJob` contract.

## Local integration state, 2026-10-12

- The existing Spring product database is Docker `capstone_db` on host port
  `5435`. Product lookup and inspection persistence have been observed against
  this database. The historical products now have parsed approved functions
  stored in `product_approved_functions`. This extraction is heuristic: some
  ingredient headings in the source text can appear as list items, so the team
  must review the data before treating the list as legally verified claims.
- The judge's `.env.example` still points `DATABASE_URL` at
  `localhost:5432/foodguard`. The team must choose whether the judge shares
  `capstone_db` or uses a separate database and set its actual environment
  accordingly. Do not infer that it uses the Spring database from the example.
- The `vector` extension is enabled in `capstone_db`, but the judge's `cases`
  table and case data are not loaded. The judge supplies `judge/sql/cases.sql`;
  its owner needs to confirm when that schema and case data should be loaded.
- Spring calls `http://localhost:8000/judge` by default. With no judge service
  listening, `POST /api/checks/full-process` saves the layer-1 result with
  overall status `PARTIAL` and an error in `step2`.
