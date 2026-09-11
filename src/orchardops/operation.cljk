(ns orchardops.operation
  "OperationActor -- one orchard operation = one supervised actor run,
  expressed as a REAL compiled `langgraph-clj` `StateGraph`
  (`langgraph.graph/state-graph` + `compile-graph`). The advisor
  (`orchardops.advisor/Advisor`) is sealed into a single node (`:advise`);
  its proposal is ALWAYS routed through the independent Orchard
  Operations Governor (`:govern`) and the rollout phase gate (folded into
  `:decide`) before anything commits to the SSoT.

  PRIOR BUG (fixed here, same compounding class as sibling cloud-itonami
  actors before their own fixes): this namespace's own docstring
  previously admitted \"NOTE: langgraph-clj StateGraph integration is
  deferred ... This stub version defines the high-level flow
  synchronously\" -- `build` returned a plain closure over `run-operation`,
  never called `langgraph.graph/state-graph`/`add-node`/`compile-graph`,
  and had no human-in-the-loop `interrupt-before` gate at all (escalation
  was just a disposition value returned to the caller, who had to invent
  their own resume mechanism). `blueprint.edn` nonetheless claimed
  `:itonami.blueprint/maturity :implemented`, which was false while the
  code's own comment said \"stub\" -- fixed together with this file.
  `orchardops.advisor` was ALREADY a real `defprotocol Advisor` +
  `MockAdvisor` before this fix (unlike most sibling actors' dead
  advisor.cljc) -- it just had no real graph node to be called from.

  State machine:
  intake -> advise -> govern -> decide -+-> commit
                                         +-> request-approval -> commit
                                         +-> hold

  Everything the actor depends on is injected, so each is a swap, not a
  rewrite:
    - the Store    (`orchardops.store/mem-store`, or any `Store` impl)
    - the Advisor  (mock today; `orchardops.advisor/Advisor` is already
                     the injection point -- see its docstring)
    - the Phase    (`orchardops.phase/gate`, 0->3 rollout -- unchanged,
                     folded into the `:decide` node below exactly as
                     `run-operation` used to sequence it)

  One graph run = one orchard coordination operation. No unbounded inner
  loop -- each run is auditable and checkpointed. An orchard/block's
  operating history is advanced by MANY runs (log-orchard-record /
  schedule-field-operation / flag-crop-health-concern / order-supplies),
  each its own independent graph run, and every commit/hold/
  approval-rejected decision fact lands in `orchardops.store`'s
  append-only ledger (`store/append-ledger!`), reachable ONLY from the
  `:commit` and `:hold` terminal nodes of the compiled graph below --
  before this fix `orchardops.store` had no ledger at all.

  Human-in-the-loop = real approval workflow:
  `interrupt-before #{:request-approval}` pauses the actor at the
  `:request-approval` node until a human operator (grower / orchard
  manager / agronomist) resumes it with a decision.
  `:flag-crop-health-concern` ALWAYS reaches this node when the Governor
  is clean -- see `orchardops.governor/always-escalate-ops` -- and in
  `:phase-0` (default rollout stage) EVERY would-be commit is forced to
  escalate as well, per `orchardops.phase/gate` (unchanged)."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [orchardops.advisor :as advisor]
            [orchardops.governor :as governor]
            [orchardops.phase :as phase]
            [orchardops.store :as store]))

;; ============================================================================
;; Audit-fact builders
;; ============================================================================

(defn- commit-fact
  "The audit fact written when a proposal commits. `:record` carries the
  operational payload the advisor proposed -- orchardops has no separate
  stateful commit-record! entity beyond orchard registration, so the
  ledger fact itself is the durable record of what happened."
  [request context proposal]
  {:t          :committed
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:orchard-id request)
   :disposition :commit
   :basis      (:cites proposal)
   :summary    (:summary proposal)
   :record     (:value proposal)})

(defn- commit-record [request _context proposal]
  {:effect  (:effect proposal)
   :path    [(:orchard-id request)]
   :value   (or (:value proposal) {})
   :payload (:value proposal)})

(defn- escalate-fact
  "The audit fact written when a proposal is routed to human sign-off --
  either because the phase gate forced it (e.g. phase-0's
  simulation-only default, or phase-1's always-escalate-op rule) or
  because the Governor itself flagged low confidence / a high-cost
  supply order / an always-escalate op (`:flag-crop-health-concern`)."
  [request verdict reason]
  {:t          :approval-requested
   :op         (:op request)
   :subject    (:orchard-id request)
   :reason     (or reason
                   (cond (:high-stakes? verdict) :always-escalate
                         :else :low-confidence))
   :confidence (:confidence verdict)})

;; ============================================================================
;; Compiled StateGraph
;; ============================================================================

(defn build
  "Compiles an OperationActor graph bound to `store` (an
  `orchardops.store/Store`, e.g. `(orchardops.store/mem-store {...})`).
  opts:
    :advisor      -- an `orchardops.advisor/Advisor` (default: mock-advisor)
    :checkpointer -- a `langgraph.checkpoint/Checkpointer`
                     (default: in-memory `cp/mem-checkpointer`)

  The compiled graph's input map: `{:request .. :context ..}`. `:context`
  carries `:actor-id`/`:role`/`:phase` (per-request; `:phase` defaults to
  `orchardops.phase/default-phase` exactly as the previous synchronous
  `run-operation` did) and is threaded straight into
  `orchardops.governor/check`/`hold-fact` and `orchardops.phase/gate`."
  [store & [{:keys [advisor checkpointer]
             :or   {advisor      (advisor/mock-advisor)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :record      {:default nil}
         :approval    {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (advisor/-advise advisor store request)]
            {:proposal p :audit [(advisor/trace request p)]})))

      (g/add-node :govern
        (fn [{:keys [request context proposal]}]
          {:verdict (governor/check request context proposal store)}))

      (g/add-node :decide
        (fn [{:keys [request context verdict]}]
          (let [base-disposition (phase/verdict->disposition verdict)
                ph               (:phase context phase/default-phase)
                {:keys [disposition reason]} (phase/gate ph request base-disposition)]
            (case disposition
              :hold
              {:disposition :hold
               :audit [(cond-> (governor/hold-fact request context verdict)
                         reason (assoc :phase-reason reason :phase ph))]}

              :escalate
              {:disposition :escalate
               :audit [(escalate-fact request verdict reason)]}

              :commit
              {:disposition :commit}))))

      (g/add-node :request-approval
        (fn [{:keys [request context proposal approval verdict]}]
          (if (= :approved (:status approval))
            {:disposition :commit
             :record (assoc (commit-record request context proposal)
                            :payload (assoc (:value proposal)
                                            :approved-by (:by approval)))
             :audit [{:t :approval-granted :op (:op request)
                      :subject (:orchard-id request) :by (:by approval)}]}
            {:disposition :hold
             :audit [(merge (governor/hold-fact request context
                                                (assoc verdict :violations
                                                       (conj (vec (:violations verdict))
                                                             {:rule :approver-rejected})))
                            {:t :approval-rejected})]})))

      (g/add-node :commit
        (fn [{:keys [request context proposal]}]
          (let [f (commit-fact request context proposal)]
            (store/append-ledger! store f)
            {:audit [f]
             :record (commit-record request context proposal)})))

      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(#{:governor-hold :approval-rejected} (:t %)) audit))]
            (store/append-ledger! store (assoc hf :disposition :hold)))
          {}))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit   :commit
            :escalate :request-approval
            :hold)))

      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}]
          (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer     checkpointer
        :interrupt-before #{:request-approval}})))
