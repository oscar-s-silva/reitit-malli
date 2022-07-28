(ns example.server-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.walk :refer [stringify-keys]]
            [xtdb.api :as xt]
            [jsonista.core :as j]
            [example.bank :as bank]
            [example.bank-db :as db]))


;; node symbol bound in fixture
(def node* (atom nil))

(use-fixtures :each (fn [f]
                      (with-open [node (xt/start-node {})]
                        (db/setup! node)
                        (reset! node* node)
                        (f))))

(defn test-json-data [b c]
  (format "[
        {
            \"sequence\": 3,
            \"debit\": 20,
            \"description\": \"withdraw\"
        },
        {
            \"sequence\": 2,
            \"credit\": 10,
            \"description\": \"receive from %s\"
        },
        {
            \"sequence\": 1,
            \"debit\": 5,
            \"description\": \"send to %s\"
        },
        {
            \"sequence\": 0,
            \"credit\": 100,
            \"description\": \"deposit\"
        }
]" c b))

(def test-account-data
  {:a {:name "Mr. Allisson"}
   :b {:name "Ms. Bobson"}
   :c {:name "Mr. Carlton"}})

(defn- create-accounts [node]
  (let [accs (vec (for [[_ acc] test-account-data
                        :let [{:keys [account-number]} (bank/create-account node (:name acc))]]
                    account-number))]
    accs))

(defn get-entity! [node entity-id]
  (xt/entity (xt/db node) entity-id))

(deftest bank-usage-happy-path
  (testing "accounts can be created"
    (let [accs (create-accounts @node*)]
      (is (= 3 (count (map (partial get-entity! @node*) accs))))))
  (testing "account transactions"
    (testing "deposit, transfer, withdraw"
      (let [[a b c] (create-accounts @node*)]
        (bank/deposit @node* a 100)
        (bank/deposit @node* c 10) ; a does not participate
        (bank/transfer @node* a b 5)
        (bank/transfer @node* c a 10)
        (bank/withdraw @node* a 20)
        (testing "audit-log shows txs for participating account-id only"
          (is (= 4 (count (bank/audit-log @node* a)))))
        (testing "JSON is equivalent to example"
          (let [audit-json (bank/audit-log @node* a)]
            (is (= (j/read-value (test-json-data b c)) (stringify-keys audit-json)))))))))

(deftest bank-txs-individually
  (let [[a b c] (create-accounts @node*)
        [a-acc b-acc _c-acc] [(assoc (:a test-account-data) :account-number a :balance 0)
                              (assoc (:b test-account-data) :account-number b :balance 0)
                              (assoc (:c test-account-data) :account-number c :balance 0)]]
    (testing "account transactions reflect new situation"
      (testing "deposit"
        (is (= a-acc (bank/view-account @node* a)))
        (is (= (assoc a-acc :balance 100) (bank/deposit @node* a 100))))
      (testing "transfer"
        (is (= (assoc a-acc :balance 100) (bank/view-account @node* a)))
        (is (= (assoc a-acc :balance 0) (bank/transfer @node* a b 100))))
      (testing "withdraw"
        (is (assoc b-acc :balance 100) (bank/view-account @node* b))
        (is (= (assoc b-acc :balance 0) (bank/withdraw @node* b 100)))))))

(deftest bank-usage-constraints
  (testing "failing when"
    (testing "result: negative source account balance"
      (let [[a] (create-accounts @node*)]
        (is (thrown? Exception (bank/withdraw @node* a 10)))))
    (testing "transfer amount is negative"
      (let [[a b] (create-accounts @node*)
            _ (bank/deposit @node* a 10)
            _ (bank/deposit @node* b 10)]
        (is (thrown? Exception (bank/transfer @node* a b -10)))))))

