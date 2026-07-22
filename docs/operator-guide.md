# Operator Guide: Orchard Operations Coordinator

## Overview

The Orchard Operations Coordinator is a facility-management robot that:

1. **Logs operational data** — planting counts, harvest weights, yield, brix readings
2. **Schedules coordination** — pruning/spraying/irrigation/harvest field operations, supply orders
3. **Escalates concerns** — any crop health, pest, fungal disease, or frost-damage issue
4. **Maintains transparency** — audit ledger traces all decisions

The robot is **not** the decision-maker. The grower/agronomist make all
decisions about spray application, crop health response, and economic
choices. The robot **proposes** actions and escalates when human input is
needed.

## Operating the Actor

### Prerequisites

1. **Orchard/Block Registration** — your orchard/block must be registered in
   the system before any operation can proceed
2. **Authorized User** — operator must be authenticated and authorized
3. **Clear Request Type** — specify what you're doing:
   - `:log-orchard-record` — record planting/harvest-yield/brix-test data
   - `:schedule-field-operation` — arrange pruning/spraying/irrigation/harvest
   - `:flag-crop-health-concern` — report a pest/fungal-disease/frost concern
   - `:order-supplies` — procurement request

### Workflow

1. **Submit Request**
   ```clojure
   {:orchard-id "orchard-001"
    :op :log-orchard-record
    :record-type "harvest"
    :count 500
    :notes "healthy yield"}
   ```

2. **Actor Processes** (the compiled `langgraph-clj` `StateGraph` built by
   `orchardops.operation/build`, run via `langgraph.graph/run*`)
   - `:advise` — `OrchardOpsAdvisor` proposes an action (`orchardops.advisor`)
   - `:govern` — `OrchardOperationsGovernor` checks hard invariants and escalation gates (`orchardops.governor`)
   - `:decide` — rollout-phase constraints applied on top of the Governor's verdict (`orchardops.phase`)

3. **Outcomes** (`:disposition` on the return value)
   - **`:commit`** — operation logged, robot proceeds (`:record` is present)
   - **`:escalate`** — operation held pending human decision (audit fact `:t :approval-requested`)
   - **`:hold`** — operation blocked, hard violation (audit fact `:t :governor-hold`, cites `:violations`)

### Escalation Scenarios

**Automatic escalation (always human sign-off):**
- `:flag-crop-health-concern` — any pest (e.g. fruit-fly)/fungal-disease/frost-damage issue
- Supply orders over cost threshold (default 500 currency units)
- Low confidence operations (< 0.7)

**Hard blocks (no override):**
- `:operate-field-equipment` — direct equipment operation is grower authority
- `:finalize-spray-application` — spray-application decisions are agronomist/grower authority
- Missing/unregistered orchard/block — must register first

### Resuming Escalated Operations

`orchardops.operation/build` compiles a REAL `langgraph-clj` `StateGraph`
with `interrupt-before #{:request-approval}`: a call to
`(langgraph.graph/run* actor {:request .. :context ..} {:thread-id
tid})` genuinely pauses (checkpointed) at `:request-approval` whenever
the phase gate or Governor escalates. A human operator resumes it with

```clojure
(langgraph.graph/run* actor {:approval {:status :approved :by "operator-id"}}
                       {:thread-id tid :resume? true})
```

(`:status :rejected` routes to `:hold` instead). Nothing is committed to
the store or the ledger until this resume call runs — the ledger stays
empty across the interrupt (see `test/orchardops/operation_graph_test.cljc`
for the falsifiable proof).

## Audit & Transparency

Every graph run appends an `:audit` vector containing an advisor-proposal
trace and a disposition fact (`:committed`, `:governor-hold`, or
`:approval-requested`/`:approval-granted`/`:approval-rejected`) to
`orchardops.store`'s append-only ledger (`store/ledger` /
`store/append-ledger!`), written ONLY from the compiled graph's real
`:commit`/`:hold` nodes.

- Every proposal produces a trace, regardless of outcome
- Every hold cites the specific Governor rule(s) violated (`:violations`)
- Every escalation cites its `:reason` (always-escalate op / high cost / low confidence)

## Integration

The actor provides a standard protocol (`orchardops.store/Store`) for backend
integration:

- **Orchard/block lookup** — `(store/registered-orchard store orchard-id)`
- **Audit ledger** — `(store/ledger store)` / `(store/append-ledger! store fact)`

Implementations include in-memory `MemStore` (testing, `orchardops.store`),
and future Datomic/kotoba-server backends (the same seam point all
cloud-itonami actors use). `orchardops.operation/build`'s compiled graph
appends every committed/held/approval-rejected decision fact via
`append-ledger!` from its `:commit`/`:hold` nodes -- callers never need to
append facts themselves.

## Safety Guarantees

- **No unsupervised decisions** — no spray-application or crop-health
  response decision is made by the robot
- **No suppressed concerns** — crop health concerns cannot be hidden or delayed
- **No unlogged operations** — every action is recorded in the audit ledger
- **No direct execution** — the governor gates every robot action

The robot is safe because:
1. It never decides — it proposes
2. It always escalates when needed
3. It never hides information
4. Every action is auditable
