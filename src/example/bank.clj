(ns example.bank
  (:require [example.bank-db :as db]
            [clojure.set :refer [rename-keys]]
            [clojure.core.match :refer [match]])
  (:import java.util.UUID))

(defn- un-ns-keys [m]
  (update-keys m (comp keyword name)))

(defn create-account [node  name]
  (-> (db/sync-put-account! node {:account-name name})
      (rename-keys {:xt/id :account-number})
      un-ns-keys))

(defn view-account [node account-id]
  (db/sync-get-entity! node account-id))

(defn deposit [node account-id amount]
  (db/sync-transfer! node {:sender-id db/global-reserve-id
                           :recipient-id account-id
                           :amount amount
                           :kind :deposit}))

(defn withdraw [node account-id amount]
  (db/sync-transfer! node {:sender-id account-id
                           :recipient-id db/global-reserve-id
                           :amount amount
                           :kind :withdraw}))

(defn transfer [node sender-id recipient-id amount]
  (db/sync-transfer! node {:sender-id sender-id
                           :recipient-id recipient-id
                           :amount amount
                           :kind :transfer}))

(defn transactors [log-entry]
  (-> log-entry
      :xtdb.api/doc
      ::db/tx-args
      first
      (select-keys [:sender-id :recipient-id])))

(defn participant [account-id log-entry]
  (->> log-entry
      transactors
      (some (fn [[k v]]
               (when (= v account-id) k)))))

(defn format-entry [account-id idx log-entry]
  (let [role ({:sender-id :sender
               :recipient-id :recipient} (participant account-id log-entry))]
    (-> {:sequence idx}
        (assoc (case role
                 :sender "debit"
                 :recipient "credit")
               (get-in log-entry [:xtdb.api/doc ::db/tx-args 0 :amount]))
        (assoc :description
               (match [role (get-in log-entry [:xtdb.api/doc ::db/tx-kind])]
                 [_ :withdraw] "withdraw"
                 [_ :deposit] "deposit"
                 [:sender :transfer] (str "send to "
                                          (:recipient-id (transactors log-entry)))
                 [:recipient :transfer] (str "receive from "
                                             (:sender-id (transactors log-entry))))))))

(defn audit-log [node account-id]
  (->> (db/full-audit-log! node)
       (sequence (comp (filter (partial participant account-id))
                       (map-indexed (partial format-entry account-id))))
       reverse))
