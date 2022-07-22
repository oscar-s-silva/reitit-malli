(ns example.server-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.test.check.generators :as gen]
            [example.server :refer [app]]
            [example.bank :as bank]
            ;; [ring.mock.request :refer [request json-body]]
            [clj-gatling.core :as gtl]
            [org.httpkit.client :as http]))


(defn valid-sequences []
  ;; two test accounts Alice and Bob
  (let [pairing-gen (gen/let [n1 (gen/fmap inc gen/nat)
                              n2 (gen/fmap - n1)
                              acc-change (gen/shuffle [0 n1 n2])])]
    (gen/sample pairing-gen 10)))
(comment )

(defn rm-endebted [matrix]
  (reductions (fn [{:keys [global alice bob] :as acc} transfer-v]
            (let [change-m (zipmap [:global :alice :bob] transfer-v)
                  acc' (merge-with + acc change-m)]
              (if (some neg? (vals (select-keys acc' [:alice :bob])))
                acc
                acc')))
          {:global 0 :alice 0 :bob 0}
          matrix))

(defn transfer-matrices []
  (let [pairing-gen (gen/let [n1 (gen/fmap inc gen/nat)
                              n2 (gen/return (- n1))]
                      (gen/shuffle [0 n1 n2]))]
    (gen/sample (gen/vector pairing-gen) 100)
    ))

(defn add-type [{:keys [global alice bob] :as row}]
  (cond
    (pos? global) (assoc row :type {:name :deposit
                                    :account-holder (if (neg? alice)
                                                 :alice
                                                 :bob)
                                    :amount (abs global)})
    (neg? global) (assoc row :type {:name :withdrawal
                                    :account-holder (if (neg? alice)
                                                    :alice
                                                    :bob)
                                    :amount (abs global)})
    :else (assoc row :type {:name :transfer-from
                            :account-holder (if (neg? alice)
                                               :alice
                                               :bob)
                            :amount (abs alice)})))

(defn filter-valid-transfers [transfer-mat]
  (->> transfer-mat
       (map (partial remove (partial every? zero?)))
       (map rm-endebted)))

(for [sample (transfer-matrices)
      row sample
      :let [{{:keys [name account-holder amount]} :type} row]]
  (cond
    :deposit (bank/account-deposit! account-holder amount)
    :withdrawal (bank/account-withdraw! account-holder amount)
    :transfer (bank/transfer! account-holder (if (= :alice account-holder)
                                               :bob
                                               :alice))))

(defn localhost-request [_]
  (let [{:keys [status]} @(http/get "http://localhost:3000")]
    (= status 200)))

(deftest load-testing
  (testing "account creation"
    #p (gtl/run
      {:name "Simulation"
       :scenarios [{:name "Localhost test scenario"
                    :steps [{:name "Root"
                             :request localhost-request}]}]}
      {:concurrency 1000})))

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
