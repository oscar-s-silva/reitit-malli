(ns ossoso.server-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ossoso.server :as server]
            [ossoso.bank :as bank]
            [ossoso.bank-db :as db]
            ;; [ring.mock.request :refer [request json-body]]
            [clj-gatling.core :as gtl]
            [org.httpkit.client :as http]))


(def server-test-port 3000)
(def test-deposit-amount 123)
(def test-acc-name  "Mr. Black")

;; need this because jetty uses .stop instead of .close
;; adapted from pedestal's `with-server`
(defmacro with-server [& body]
  `(let [server*# (atom nil)]
     (try
       (reset! server*# (server/start-jetty!))
       ~@body
       (finally (.stop @server*#)))))


(use-fixtures :each (fn [f]
                      (with-server
                        (with-open [x (server/start-xtdb!)]
                         (f)))))

(defn request-fn [& {:keys [account-id]}]
  (let [{:keys [status]} @(http/post (str "http://localhost:" server-test-port "/account"))]
    (= status 200)))

(defn create-account-request [context]
  (let [{{:keys [account-number]} :body :keys [status]}
        @(http/post (str "http://localhost:" server-test-port "/account")
                    {:body {:name test-acc-name}})]
    [(= status 200) (assoc context :acc-id account-number :acc-name test-acc-name)]))

(defn account-deposit-request [context]
  (let [{{:keys [amount]} :body :keys [status]}
        @(http/post (str "http://localhost:" server-test-port "/account/" (:acc-id context) "/deposit")
                    {:body {:amount test-deposit-amount}})]
    [(and (= status 200) (= test-deposit-amount amount)) (assoc context :deposit-amount amount)]))

(defn view-account-request [context]
  (let [{{:keys [balance account-number]} :body :keys [status]} @(http/get (str "http://localhost:" server-test-port "/account/" (:acc-id context)))]
    (and (= status 200) (= balance test-deposit-amount) (= (:acc-id context) account-number))))

(deftest load-testing
  (testing "account creations"
    (gtl/run
     {:name "account creation"
      :scenarios [{:name "Testing creation and deposit"
                   :steps [{:name "Create account"
                            :request create-account-request}
                           {:name "Deposit to account"
                            :request account-deposit-request}
                           {:name "View account info"
                            :request view-account-request}]}]}
     {:concurrency 1000})
