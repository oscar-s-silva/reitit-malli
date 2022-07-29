(ns ossoso.bank
  (:require [ossoso.bank-db :as db]
            [clojure.set :refer [rename-keys]]
            [clojure.core.match :refer [match]])
  (:import java.util.UUID))

(defn un-ns-keys [m]
  (update-keys m (comp keyword name)))

(def xtdb-data->api-data (comp un-ns-keys
                            #(rename-keys % {:xt/id :account-number})))

(defn view-account [node account-id]
  (xtdb-data->api-data (db/sync-get-entity! node account-id)))

(defn create-account [node name]
  (xtdb-data->api-data (db/sync-put-account! node {:account-name name})))

(defn deposit [node account-id amount]
  (when (db/sync-transfer! node {:sender-id db/global-reserve-id
                            :recipient-id account-id
                            :amount amount
                            :kind :deposit})
    (view-account node account-id)))

(defn withdraw [node account-id amount]
  (when (db/sync-transfer! node {:sender-id account-id
                                 :recipient-id db/global-reserve-id
                                 :amount amount
                                 :kind :withdraw})
    (view-account node account-id)))

(defn transfer [node sender-id recipient-id amount]
  (when (db/sync-transfer! node {:sender-id sender-id
                                 :recipient-id recipient-id
                                 :amount amount
                                 :kind :transfer})
    (view-account node sender-id)))

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

(defn audit-log [node account-id & args]
  (->> (apply db/full-audit-log! node args)
       (sequence (comp (filter (partial participant account-id))
                    (map-indexed (partial format-entry account-id))))
       reverse))
