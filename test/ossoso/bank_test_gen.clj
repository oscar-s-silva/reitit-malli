(ns ossoso.bank-test-gen
  (:require [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test :refer [deftest is testing]]
            [xtdb.api :as xt]
            [ossoso.bank :as bank]
            [ossoso.bank-db :as db]
            [ossoso.util :as util]))

(declare ^:dynamic *node*)

;; per trial fixture
(defmacro xtdb-accounts-fixture [& body]
  `(with-open [node# (xt/start-node {})]
     (db/setup! node#)
     (binding [*node* node#]
       ~@body)))

(defn add-type [[global alice _bob :as transfer-v]]
  (let [ab-order {:account-owner :alice :recipient :bob}
        ba-order {:account-owner :bob :recipient :alice}]
    (-> (cond
          (pos? global) {:type (merge {:name :withdraw
                                       :amount global}
                                      (if (neg? alice)
                                        ab-order
                                        ba-order)
                                      {:recipient nil})}
          (neg? global) {:type (merge {:name :deposit
                                       :amount (- global)}
                                      (if (pos? alice)
                                        ab-order
                                        ba-order)
                                      {:recipient nil})}
          :else {:type (merge {:name :transfer
                               :amount (abs alice)}
                              (if (neg? alice)
                                ab-order
                                ba-order))})
        (assoc :transfer-v transfer-v))))


;; For the tests to be meaningful we assume that functions in the bank ns
;; map to a vector of balance changes given by this generator
(def tx-matrix-gen
  ;; two test accounts Alice and Bob
  (gen/let [n1 (gen/fmap inc gen/nat)
            n2 (gen/return (- n1))]
    (->> [0 n1 n2]
         gen/shuffle
         gen/vector
         (gen/fmap (partial map add-type)))))


(defn compute-tx-data-result [type-rows]
  (reduce (fn [acc {:keys [transfer-v]}]
            (-> (let [change-m (zipmap [:global :alice :bob] transfer-v)
                   acc' (merge-with + acc change-m)]
               (cond
                ;; non global reserve results in negative balance
                 (some neg? (vals (select-keys acc' [:alice :bob]))) acc
                 :else (update acc' :valid-tx-count inc)))))
          {:global 0 :alice 0 :bob 0 :valid-tx-count 0 :transfer-v [0 0 0]}
          type-rows))

(defn add-acccount-ids [trial aid bid]
  (map #(-> %
            (update-in
             [:type :account-owner]
             {:alice aid
              :bob bid})
            (update-in
             [:type :recipient]
             {:alice aid
              :bob bid})) trial))

(defn call-by-type
  [node {{:keys [name account-owner recipient amount]} :type}]
  (case name
    :deposit (bank/deposit node account-owner amount)
    :withdraw (bank/withdraw node account-owner amount)
    :transfer (bank/transfer node account-owner recipient amount)))

()

(defspec endebting-txs-aborted
  {:max-size 2}
  (prop/for-all [trial tx-matrix-gen]
                (xtdb-accounts-fixture
                 (let [[gid aid bid] (cons db/global-reserve-id (util/create-accounts *node*))
                       entry-w-accids (add-acccount-ids trial aid bid)
                       valid-txs (compute-tx-data-result trial)
                       data-valid-count (:valid-tx-count valid-txs)
                       processed-count (loop [[tx & txs] entry-w-accids valid-tx-count 0]
                                         (if tx
                                           (if (try (call-by-type *node* tx)
                                                    true
                                                    (catch Exception e
                                                      (println "failed with: " (ex-message e))
                                                      false))
                                             (recur txs (inc valid-tx-count))
                                             (recur txs valid-tx-count))
                                           valid-tx-count))]
                   (let [end-balances (mapv (comp :account/balance (partial bank/view-account *node*))
                                            [gid aid bid])]

                     (testing "invalid txs don't contribute to final sums "
                       (is (= end-balances (mapv valid-txs [:global :alice :bob]))))
                     (testing "balances add to zero"
                       (is (zero? (reduce + 0 end-balances))))
                     (testing "number of txs match"
                       (is (= data-valid-count processed-count)))
                     (testing "successful transactions were logged"
                       (= data-valid-count (count (db/full-audit-log! *node* :rm-ex? true)))))))))
