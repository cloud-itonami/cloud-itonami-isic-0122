(ns orchardops.store
  "Store abstraction for orchard block/planting records. Current
  implementation is an in-memory map; production should migrate to
  Datomic/kotoba-server (the same seam point all cloud-itonami actors
  use). Mirrors `vineyardops.store` (cloud-itonami-isic-0121) in shape.

  A registered orchard block is the minimal unit of authority: an
  orchard/block must be registered before ANY proposal referencing it can
  be considered by the Governor (see `orchardops.governor`'s
  `orchard-registered` invariant). Orchard data is opaque to this
  namespace -- callers/backends decide what an orchard record contains
  (name, location, fruit species, planted area, etc); this Store only
  answers \"is this orchard-id registered, and if so what's on file\".

  PRIOR GAP (fixed here, same compounding class as sibling actors): this
  namespace had no append-only audit ledger at all -- `orchardops.operation`
  never had a real `:commit`/`:hold` node to append to, since `build` was
  documented as a stub. `ledger`/`append-ledger!` are the missing
  plumbing, now genuinely called from `orchardops.operation/build`'s
  compiled StateGraph's `:commit` and `:hold` nodes (mirrors
  `pastaops.store`/`forestrysupport.store`, cloud-itonami-isic-1074/0240)."
  )

;; Protocol for swappable store implementations
(defprotocol Store
  (registered-orchard [store orchard-id]
    "Retrieve a registered orchard/block record by ID. Returns nil if the
    orchard-id is nil or not registered.")
  (ledger [store]
    "The append-only audit ledger: every committed/held/approval-rejected
    decision fact, in append order.")
  (append-ledger! [store fact]
    "Append one immutable decision fact to the ledger. Returns fact."))

;; In-memory implementation (MemStore) for development/testing
(defrecord MemStore [orchards ledger-atom]
  Store
  (registered-orchard [_store orchard-id]
    (when orchard-id
      (get @orchards orchard-id)))
  (ledger [_store] @ledger-atom)
  (append-ledger! [_store fact]
    (swap! ledger-atom conj fact)
    fact))

(defn mem-store
  "Create an in-memory store. `initial-orchards` is an optional map of
  orchard-id -> orchard-record."
  [& [{:keys [initial-orchards] :or {initial-orchards {}}}]]
  (MemStore. (atom initial-orchards) (atom [])))

(defn add-orchard
  "Register or update an orchard/block in the store. Used by tests and
  simulation."
  [^MemStore store orchard-id orchard-data]
  (swap! (:orchards store) assoc orchard-id orchard-data)
  orchard-data)
