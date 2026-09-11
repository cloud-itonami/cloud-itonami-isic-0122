(ns orchardops.facts
  "Reference facts for orchard operations coordination: supply category
  cost policy and fruit-species classification. This namespace contains pure
  lookup functions for domain reference data -- the Governor and Advisor
  consult these instead of inventing thresholds. Mirrors
  `vineyardops.facts` (cloud-itonami-isic-0121) in shape.")

(def supply-categories
  "Procurement categories this actor may propose orders for, and the
  default cost threshold above which an order proposal must escalate for
  human sign-off (grower/orchard-manager)."
  {"seedling"
   {:id "seedling" :name "苗木" :cost-threshold 500}

   "fertilizer"
   {:id "fertilizer" :name "肥料" :cost-threshold 500}

   "equipment"
   {:id "equipment" :name "設備" :cost-threshold 1000}})

(defn supply-category-by-id [id]
  (get supply-categories id))

(def default-cost-threshold
  "Fallback escalation threshold used when a supply-order proposal doesn't
  cite a known category (never invent a lower bar than this)."
  500)

(def fruit-classes
  "End-use classes this actor's orchard/block records may cover (ISIC
  0122: growing of tropical and subtropical fruits)."
  {"mango"     {:id "mango" :name "マンゴー"}
   "banana"    {:id "banana" :name "バナナ"}
   "papaya"    {:id "papaya" :name "パパイヤ"}
   "avocado"   {:id "avocado" :name "アボカド"}
   "pineapple" {:id "pineapple" :name "パイナップル"}})

(defn fruit-class-by-id [id]
  (get fruit-classes id))
