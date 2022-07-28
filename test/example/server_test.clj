(ns example.server-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [xtdb.api :as xt]
            [example.server :refer [app]]
            [example.bank :as bank]
            [example.bank-db :as db]
            ;; [ring.mock.request :refer [request json-body]]
            [clj-gatling.core :as gtl]
            [org.httpkit.client :as http]))


;; node symbol bound in fixture
(def ^:dynamic *node*)


(use-fixtures :each (fn [f]
                      (with-open [node (xt/start-node {})]
                        (db/setup! node)
                        (binding [*node* node]
                          (f)))))

(defn- create-accounts [node]
  (let [acc-data [{:account-name "Mr. Allisson"
                   :account-id (:allisson util/test-account-ids)}
                  {:account-name "Ms. Bobson"
                   :account-id (:bobson util/test-account-ids)}
                  {:account-name "Mr. Carlton"
                   :account-id (:carlton util/test-account-ids)}]
        accs (vec (for [acc acc-data
                        :let [{:keys [account-number]} (bank/create-account node (:account-name acc))]]
                    account-number))]
    accs))


(defn localhost-request [_]
  (let [{:keys [status]} @(http/get "http://localhost:3000")]
    (= status 200)))

(defn get-entity! [node entity-id]
  (xt/entity (xt/db node) entity-id))

(deftest bank-usage-happy-path
  (testing "accounts can be created"
    (let [accs (create-accounts *node*)]
      (is 3 (count (map (partial get-entity! *node*) accs)))))
  (testing "account transactions"
    (testing "deposit, transfer, withdraw"
      (let [[a b c] (create-accounts *node*)
            _ (bank/deposit *node* a 100)
            _ (bank/deposit *node* c 10)
            _ (bank/transfer *node* a b 5)
            _ (bank/transfer *node* c a 10)
            _ (bank/withdraw *node* a 20)]
        (testing "audit-log shows txs for participatign account-id only"
          (is 5 (count (bank/audit-log *node* a))))))))

(deftest bank-usage-constraints
  (testing "failing when"
    (testing "result: negative source account balance"
      (let [[a] (create-accounts *node*)]
        (is (thrown? Exception (bank/withdraw *node* a 10)))))
    (testing "transfer amount is negative"
      (let [[a b] (create-accounts *node*)
            _ (bank/deposit *node* a 10)
            _ (bank/deposit *node* b 10)]
        (is (thrown? Exception (bank/transfer *node* a b -10)))))))

#_(deftest load-testing
    (testing "account creation"
      (gtl/run
          {:name "Simulation"
           :scenarios [{:name "Localhost test scenario"
                        :steps [{:name "Root"
                                 :request localhost-request}]}]}
          {:concurrency 1000})))

#_
(xt/q (xt/db *node*)
      '{:find [#_acc id]
        :where [[e :xt/id id]
                #_[e :account/name acc]]})

#_
(deftest example-server

  

  (testing "GET"
    (is (= (-> (request :get "/math/plus?x=20&y=3")
               app :body slurp)
           (-> {:request-method :get :uri "/math/plus" :query-string "x=20&y=3"}
               app :body slurp)
           (-> {:request-method :get :uri "/math/plus" :query-params {:x 20 :y 3}}
               app :body slurp)
           "{\"total\":23}")))

  (testing "POST"
    (is (= (-> (request :post "/math/plus") (json-body {:x 40 :y 2})
               app :body slurp)
           (-> {:request-method :post :uri "/math/plus" :body-params {:x 40 :y 2}}
               app :body slurp)
           "{\"total\":42}")))

  (testing "Download"
    (is (= (-> {:request-method :get :uri "/files/download"}
               app :body (#(slurp % :encoding "ascii")) count)  ;; binary
           (.length (clojure.java.io/file "resources/reitit.png"))
           506325)))

  (testing "Upload"
    (let [file (clojure.java.io/file "resources/reitit.png")
          multipart-temp-file-part {:tempfile file
                                    :size (.length file)
                                    :filename (.getName file)
                                    :content-type "image/png;"}]
      (is (= (-> {:request-method :post :uri "/files/upload" :multipart-params {:file multipart-temp-file-part}}
                 app :body slurp)
             "{\"name\":\"reitit.png\",\"size\":506325}")))))
