(ns example.bank
  (:require [example.bank-db :as db]
            [clojure.set :refer [rename-keys]]
            [example.bank :as bank])
  (:import java.util.UUID))

(defn- un-ns-keys [m]
  (update-keys m (comp keyword name)))

(defn create-account! [name]
  ;; TODO check for nil
  (let [account (db/put-account! {:account-name name})]
    (-> account
        (rename-keys {:xt/id :account-number})
        un-ns-keys)))

(defn view-account! [account-id]
  (db/sync-account-entity! account-id))

(defn account-deposit! [account-id amount]
  (db/sync-transfer! :sender-id db/global-reserve-id
                     :recipient-id account-id
                     :amount amount
                     :from-global? true))

(defn account-withdraw! [account-id amount]
  (db/sync-transfer! :sender-id account-id
                     :recipient-id db/global-reserve-id
                     :amount amount))

(defn transfer! [sender-id recipient-id amount]
  (db/sync-transfer! :sender-id sender-id
                     :recipient-id recipient-id
                     :amount amount))
