(ns example.server-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [example.server :as server]
            [example.bank :as bank]
            [example.bank-db :as db]
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
  (let [
        {{:keys [amount]} :body :keys [status]}
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
     {:concurrency 10})
    #_(is)
    #_(gtl/run
       {:name "account creation"
        :scenarios [{:name "Testing creation and deposit"
                     :steps [{:name "Root"
                              :request create-account-request}]}]}
       {:concurrency 1})))

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


#_(deftest load-testing
    (testing "account creation"
      (gtl/run
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
