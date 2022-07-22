(ns example.bank-db
  (:require [xtdb.api :as xt]
            [clojure.set :refer [rename-keys]])
  (:import java.util.UUID))

(def node (xt/start-node {}))

(def global-reserve-id
  #uuid "00000000-0000-1000-8000-000000000000")

(defn put-account!
  [{:keys [account-name account-id] :or {account-id (java.util.UUID/randomUUID)}}]
  (let [account {:xt/id account-id
                 :account/name account-name
                 :account/balance 0}]
    (when-let [tx (xt/submit-tx
                   node
                   [[::xt/put account]])]
      {:tx tx :data account})))


(do (defn setup! []
      (xt/submit-tx
       node
       [[::xt/put
         {:xt/id :transfer-amount
          :xt/fn '(fn [ctx in-id out-id amount from-global?]
                    (println "args " [in-id out-id amount from-global?])
                    (let [db (xtdb.api/db ctx)
                          [in out] (map (partial xtdb.api/entity (xtdb.api/db ctx))
                                        [in-id out-id])
                          [in-balance out-balance] (map :account/balance [in out])
                          [in-balance' out-balance'] (map +
                                                          [in-balance out-balance]
                                                          [(- amount) amount])]
                      (when (and
                          ;; the global reserve can have negative numbers
                             (not from-global?)
                             (neg? in-balance'))
                        (throw (ex-info "sender balance insufficient"
                                        {:sender-balance in-balance
                                         :transfer-amount amount})))
                      [[::xt/put (assoc in :account/balance in-balance')]
                       [::xt/put (assoc out :account/balance out-balance')]]))}]]))
    (setup!)
    (let [create-accounts (fn []
                            (let [acc-data [{:account-name "Mr. Oizo"}
                                            {:account-name "Mr. White"}
                                            {:account-name "Global reserve" :account-id global-reserve-id}]
                                  accs (vec (for [acc acc-data
                                                  :let [{{id :xt/id} :data tx :tx} (put-account! acc)
                                                        _ (xt/await-tx node tx)]]
                                              id))]
                              accs))
          accs (create-accounts)
          _ (def accs accs)
          [oizo white] accs]
      (xt/await-tx node (xt/submit-tx node
                                      [[::xt/fn
                                        :transfer-amount global-reserve-id oizo 50 true]
                                       #_[::xt/fn
                                          :transfer-amount oizo white 5 false]]))))



(comment (setup!)
         ;; transfer amount from oizo to white

         (let [acc-data [{:name "Mr. Oizo"} {:name "Mr. White"}]
               accs (vec (for [acc acc-data
                               :let [{{id :xt/id} :account tx :tx} (put-account! acc)
                                     _ (xt/await-tx node tx)]]
                           id))
               _ (def accs accs)
               [oizo white global] (conj accs global-reserve-id)]
           (xt/submit-tx node [[::xt/fn :transfer-amount global oizo 50 global-reserve-id]])))

(defn account-entity!
  [account-id]
  (xt/entity (xt/db node) account-id))


(defn get-account!
  [{:keys [account-id]}]
  (xt/entity (xt/db node) account-id))

(defn transfer!
  [& {:keys [sender-id recipient-id amount unchecked?] :or {unchecked? false}}]
  (xt/submit-tx node [[::xt/fn
                       :transfer-amount
                       sender-id recipient-id amount unchecked?]]))

(comment (put-account! {:name "Mr. Black"})
         (get-account! {:account-uuid #uuid "a09ac1bc-c015-4fc3-8788-558fda54f071"})
         (update-keys {:a/b 1} account-name))

(defn ->sync [{tx :tx data :data}]
  (and (xt/await-tx node tx) #p data))

(def sync-put-account! (comp ->sync put-account!))
(def sync-setup! (comp ->sync setup!))
(def sync-account-entity! (comp ->sync account-entity!))
(def sync-get-account! (comp ->sync get-account!))
(def sync-transfer! (comp ->sync transfer!))

(xt/q (xt/db node)
      '{:find [acc id]
        :where [[e :xt/id id]
                [e :account/name acc]]})
;; => #{["Mr. Oizo" #uuid "6079b308-3501-48b7-945d-a0d72f567859"] ["Mr. Oizo" #uuid "2f5cfcdc-a040-4421-a307-a1970a5b6e58"] ["Global reserve" #uuid "a31d908f-4772-4896-a110-066dfe6e11fe"] ["Mr. White" #uuid "518e6d47-4da7-4fa3-a40c-6742c8689468"] ["Global reserve" #uuid "d29a1b00-d403-412b-89ec-cd781c668800"] ["Mr. White" #uuid "de91262f-4a2a-4d43-ae3b-0f86d1baf48c"]}


;; => #'example.bank/transfer!
