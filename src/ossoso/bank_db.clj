(ns ossoso.bank-db
  (:require [xtdb.api :as xt]
            [clojure.set :refer [rename-keys]])
  (:import java.util.UUID))

(def global-reserve-id
  #uuid "00000000-0000-1000-8000-000000000000")

(def audit-log-id ::audit-log)

(defn ->sync
  "blocks until tx has been indexed and returns data or gives a time out exception."
  [{:keys [tx data]} node]
  (and (xt/await-tx node tx) data))

(defn sync-get-entity!
  [node xt-id]
  (xt/entity (xt/db node) xt-id))

(defn put-account-tx-data
  [{:keys [account-name account-id]
    :or {account-id (java.util.UUID/randomUUID)}}]
  (let [account {:xt/id account-id
                 :account/name account-name
                 :account/balance 0}]
    {:txops [[:xtdb.api/put account]] :data account}))

(defn append-audit-txop [tx-data kind args & {:as add-kvs}]
  (update tx-data :txops
          conj [:xtdb.api/put (merge {:xt/id audit-log-id
                                      ::tx-kind kind
                                      ::tx-args args}
                                     add-kvs)]))

(defn xact [tx-data node]
   (let [tx (xt/submit-tx
                  node
                  (:txops tx-data))]
     (assoc tx-data :tx tx)))

(defn sync-put-account!
  [& [node & -args]]
  (-> (apply put-account-tx-data -args)
      ;; (append-audit-txop :put-account -args)
      (xact node)
      (->sync node)))

(defn transfer-txop
  [{:keys [correlation-id sender-id recipient-id amount kind] :as arg-m}]
  {:data arg-m :txops [[:xtdb.api/fn
                        :transfer-amount
                        correlation-id sender-id recipient-id amount kind]]})

(defn -|succeeded [data node correlation-id]
  (if-let [ex-doc (sync-get-entity! node correlation-id)]
    (throw (ex-info "Exception while making transfer" ex-doc))
    data))

(defn sync-transfer!
  [node arg-m]
  ;; correlation id corresponds to single transfer
  (let [correlation-id (java.util.UUID/randomUUID)]
    (-> (transfer-txop (assoc arg-m :correlation-id correlation-id))
        (append-audit-txop (:kind arg-m) [arg-m] :correlation-id correlation-id)
        (xact node)
        (->sync node)
        (-|succeeded node correlation-id))))

(defn full-audit-log! [node & {:keys [rm-ex?]}]
  (let [db (xt/db node)]
    (cond->> (xt/entity-history (xt/db node) ::audit-log :asc {:with-docs? true})
      rm-ex?
      (remove (comp (partial xtdb.api/entity db) :correlation-id :xtdb.api/doc)))))

(def ^:private -transfer-amount
  "transaction function for transfering amount between accounts
  creates new entity for each exception because
  (xtdb provides no innate error handling for tx-fn exceptions)"
  '(fn [ctx correlation-id in-id out-id amount kind]
     (println "args: " [correlation-id in-id out-id amount kind])
     (let [db (xtdb.api/db ctx)
           [in out] (map (partial xtdb.api/entity db)
                         [in-id out-id])
           [in-balance out-balance] (map :account/balance [in out])
           [in-balance' out-balance'] (map +
                                           [in-balance out-balance]
                                           [(- amount) amount])]
       (cond
         (and ; the global reserve can have negative numbers
          (not= :deposit kind)
          (neg? in-balance'))
         [[:xtdb.api/put {:xt/id correlation-id
                          :message  "sender balance insufficient"
                          :sender-balance in-balance
                          :transfer-amount amount}]]
         (not (pos-int? amount)) ; transfer argument order denotes direction of transfer
         [[:xtdb.api/put {:xt/id correlation-id
                          :message  "only transfer of positive amounts is allowed"
                          :sender-balance in-balance
                          :transfer-amount amount}]]
         :else
         [[:xtdb.api/put (assoc in :account/balance in-balance')]
          [:xtdb.api/put (assoc out :account/balance out-balance')]]))))

(defn setup! [node]
  (sync-put-account!
   node
   {:account-name "Global reserve" :account-id global-reserve-id})
  (xt/submit-tx
   node
   [[:xtdb.api/put
     {:xt/id :transfer-amount
      :xt/fn -transfer-amount}]]))
