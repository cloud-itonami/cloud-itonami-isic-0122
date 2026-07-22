# cloud-itonami-isic-0122

Open Occupation Blueprint for **ISIC Rev. 4 0122**: Growing of tropical and
subtropical fruits.

This repository implements a forkable OSS **orchard operations
coordinator**: a facility-management robot manages orchard/block record
logging, field-operation (pruning/spraying/irrigation/harvest) scheduling,
and supply procurement under a governor-gated actor, so a tropical/
subtropical-fruit-growing operation (mango, banana, papaya, avocado,
pineapple orchards) keeps its own operational records and maintains full
transparency over decisions.

**Maturity: `:implemented`.** `src/orchardops/` implements the
`OrchardOpsAdvisor` (`orchardops.advisor`) and the independent
`OrchardOperationsGovernor` (`orchardops.governor`), composed by
`orchardops.operation` into a REAL compiled `langgraph-clj` `StateGraph`
following the itonami actor pattern (ADR-2607011000):
`intake -> advise -> govern -> decide -+-> commit / request-approval ->
commit / hold`, with `interrupt-before #{:request-approval}` +
checkpoint-based resume for genuine human-in-the-loop escalation. See
[Testing](#testing) below for the current green test count
(`clojure -M:dev:test`).

Fixed a prior deferred-stub gap (same compounding class as sibling
cloud-itonami-isic-* actors before their own fixes): `deps.edn` declared
`io.github.kotoba-lang/langgraph` ONLY under the unused `:dev` alias's
`:override-deps`, with an EMPTY base `:deps` map, so `langgraph.graph` was
never actually resolvable on any real build/run/test path; `build`
returned a bare closure over a synchronous `run-operation` and never
called `langgraph.graph/state-graph`/`add-node`/`compile-graph`; and
`orchardops.store` had no append-only audit ledger for a real `:commit`/
`:hold` node to write to. `orchardops.advisor` was already a genuine
`defprotocol Advisor` + `MockAdvisor` before this fix — it just had no
real graph node to be called from.

## What this does NOT do

This actor coordinates **back-office logistics only**. It explicitly does **NOT**:

- **Direct field-equipment operation** — remains the grower's exclusive authority
- **Spray-application decisions** — remains the agronomist/grower authority
- **Harvest-timing / economic decisions** — economic authority remains human
- **Direct execution of any kind** — any proposal for direct actuation is a hard block

## HARD invariants (always hold, never overridable)

1. **orchard-not-registered** — the request's `orchard-id` must resolve to a
   registered orchard/block in the Store before any proposal can proceed
2. **no-execution** — every proposal's `:effect` must be `:propose` (the governor
   never directly operates field equipment, never finalizes a spray application)
3. **field-equipment-or-spray-blocked** — `:operate-field-equipment` and
   `:finalize-spray-application` proposals are unconditionally, permanently blocked
4. **op-not-allowed** — any op outside the closed allowlist below is rejected
5. **orchard-count-invalid** — `:log-orchard-record` with a non-positive logged
   quantity (trees/plants counted / harvest weight / yield estimate / brix reading)
   is rejected

## Always-escalate operations (human sign-off, regardless of confidence)

- `:flag-crop-health-concern` — any pest (e.g. fruit-fly)/fungal-disease/
  frost-damage concern → automatic escalation
- `:order-supplies` over its category cost threshold (default 500 currency
  units; see `orchardops.facts/supply-categories`)
- Any proposal with confidence below the Governor's floor (0.7)

## Operational requests (closed allowlist, all `:effect :propose`)

```text
:log-orchard-record
  — record planting/harvest-yield/brix-test data
  — requires a registered orchard/block; non-positive quantities are rejected

:schedule-field-operation
  — propose pruning/spraying/irrigation/harvest scheduling
  — does NOT make or finalize a spray-application decision

:flag-crop-health-concern
  — surface a pest (e.g. fruit-fly), fungal disease, or frost-damage concern
  — ALWAYS escalates for human review

:order-supplies
  — procurement for seedlings, fertilizer, equipment
  — escalates if cost exceeds its category threshold
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs the
physical domain work**. Here a facility-management robot handles:

- Orchard/block record logging and entry
- Field-operation scheduling and reminders
- Supply inventory and ordering
- Audit ledger maintenance

The **OrchardOperationsGovernor** is the independent safety layer that gates all
proposals before a robot action is executed. The governor never dispatches hardware
directly; `:high`/`:safety-critical` actions (such as escalated crop-health concerns
or high-cost supply orders) require human sign-off.

## Core Contract

```text
operational request (log, schedule, concern, order)
        |
        v
OrchardOpsAdvisor -> OrchardOperationsGovernor -> phase gate -> commit, or escalate for human sign-off
        |
        v
robot actions (gated) + operating records + audit ledger
```

No automated operation can dispatch a robot action the governor refuses, suppress an
operating record, or hide a crop-health concern without governor approval and audit
evidence.

## Module structure

Mirrors `cloud-itonami-isic-0121` (`vineyardops.*`) module-for-module:

- `orchardops.facts` — reference data: supply-category cost thresholds, fruit classes
- `orchardops.registry` — pure independent verification functions (cost/count/confidence)
- `orchardops.store` — `Store` protocol + in-memory `MemStore` (orchard/block registration lookup)
- `orchardops.advisor` — `Advisor` protocol + `MockAdvisor` (the sealed LLM/decision node)
- `orchardops.governor` — `OrchardOperationsGovernor`: hard invariants + escalation gates
- `orchardops.phase` — 0→3 rollout phase gate
- `orchardops.operation` — compiles the real `langgraph-clj` `StateGraph`
  (`intake -> advise -> govern -> decide -> commit/request-approval/hold`)
  binding advisor, governor, phase gate, and store's audit ledger together
- `orchardops.sim` — demo runner (`clojure -M:run` / `clojure -M:dev:run`)

## Capability layer

Resolves via [`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation)
(ISIC Rev. 4 `0122`). Required capabilities:

- :robotics
- :identity
- :forms
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## Testing

```bash
clojure -M:dev:test   # run the suite (langgraph resolved via local sibling checkout)
clojure -M:lint       # clj-kondo, 0 errors / 0 warnings
clojure -M:dev:run    # demo runner -- drives the compiled StateGraph end-to-end
```

## License

AGPL-3.0-or-later.
