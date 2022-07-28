(ns ossoso.server
  (:require [reitit.ring :as ring]
            [reitit.coercion.malli]
            [reitit.ring.malli]
            [reitit.swagger :as swagger]
            [reitit.swagger-ui :as swagger-ui]
            [reitit.ring.coercion :as coercion]
            [reitit.dev.pretty :as pretty]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.multipart :as multipart]
            [reitit.ring.middleware.parameters :as parameters]
            [ring.adapter.jetty :as jetty]
            [ossoso.bank :as bank]
            [muuntaja.core :as m]
            [clojure.java.io :as io]
            ;; malli.core
            [malli.util :as mu]
            [malli.experimental.lite :as l]
            [xtdb.api :as xt]))

(defonce server* (atom nil))
(defonce xtdb-node* (atom nil))

(defn app [node]
  (ring/ring-handler
   (ring/router
    [["/swagger.json"
      {:get {:no-doc true
             :swagger {:info {:title "bank api"
                              :description "with [malli](https://github.com/metosin/malli) and reitit-ring"}
                       :tags [{:name "banking", :description "bank actions"}]}
             :handler (swagger/create-swagger-handler)}}]
     [""
      {:swagger {:tags ["banking"]}}

      ["/account"
       {:post {:summary "Create a bank account"
               :parameters {:body [:map
                                   [:name
                                    {:title "Name of account holder"}
                                    string?]]}
               :responses {200 {:body [:map [:name string?]]}}
               :handler (fn [{{{:keys [name]} :body} :parameters}]
                           ;; TODO implement logic

                          (let [account (bank/create-account node name)]
                            {:status 200
                             :body account}))}}]
      ["/account/:id"
       {:get {:summary "View a bank account"
              :parameters {:path [:map
                                  [:id
                                   {:name "Bank account ID"}
                                   uuid?]]}
              :responses {200 {:body [:map {:account-number pos-int?,
                                            :name string?,
                                            :balance (every-pred int (comp not neg?))}]}}
              :handler (fn [{{{account-id :id} :path} :parameters}]
                           ;; TODO implement logic
                         (let [account (bank/view-account node account-id)]
                           {:status 200
                            :body account}))}}
       ["/deposit"
        {:post {:summary "Deposit money to an account"
                :parameters {:body [:map
                                    [:amount
                                     {:name "Amount to deposit"}
                                     pos-int?]]}
                :responses {200 {:body [:map {:account-number pos-int?,
                                              :name string?,
                                              :balance nat-int?}]}}
                :handler (fn [{{{:keys [amount]} :body account-id :id} :parameters}]
                           (let [account (bank/deposit node account-id amount)]
                             {:status 200
                              :body account}))}}]
       ["/withdraw"
        {:post {:summary "Withdraw money from an account"
                :parameters {:body [:map
                                    [:amount
                                     {:name "Amount to withdraw"}
                                     pos-int?]]}
                :responses {200 {:body [:map {:account-number pos-int?,
                                              :name string?,
                                              :balance nat-int?}]}}
                :handler (fn [{{{:keys [amount]} :body account-id :id} :parameters}]
                           (let [account (bank/withdraw node account-id amount)]
                             {:status 200
                              :body account}))}}]
       ["/send"
        {:post {:summary "Transfer money between accounts"
                :parameters {:body [:map
                                    [:amount
                                     {:name "Amount to transfer"}
                                     pos-int?]
                                    [:account-number
                                     {:name "Account number of recipient"}
                                     pos-int?]]}
                :responses {200 {:body
                                 [:map {:name "Account data of sender after tx"}
                                  [:account-number pos-int?]
                                  [:name string?]
                                  [:balance nat-int?]]}}}
         :handler (fn [{{{amount :amount recipient-id :account-number} :body sender-id :id} :parameters}]

                    (let [account (bank/transfer node sender-id recipient-id amount)]
                      {:status 200
                       :body {:name "Mr. Black"
                              :account-number 1
                              :balance 45}}))}]
       ["/audit"
        {:get {:summary "Retrieve account audit log"
               :responses {200 {:body
                                (l/vector
                                 (l/or {:sequence nat-int?
                                        :credit pos-int?
                                        :description string?}
                                       {:sequence nat-int?
                                        :debit pos-int?
                                        :description string?}))
                                ;; this doesn't work for some reason
                                #_[:map
                                   [:audit-trail
                                    [:vector [:or
                                              [:map
                                               [:sequence nat-int?]
                                               [:credit pos-int?]
                                               [:description string?]]
                                              [:map
                                               [:sequence nat-int?]
                                               [:debit pos-int?]
                                               [:description string?]]]]]]}}
               :handler (fn [{{{id :id} :path} :parameters}]
                          {:status 200
                           :body {:audit-trail [{:sequence 3,
                                                 :debit 20,
                                                 :description "withdraw"},
                                                {:sequence 2,
                                                 :credit 10,
                                                 :description "receive from *800"},
                                                {:sequence 1,
                                                 :debit 5,
                                                 :description "send to *900"},
                                                {:sequence 0,
                                                 :credit 100,
                                                 :description "deposit"}]}})}}]]]]

    {:exception pretty/exception
     :data {:coercion (reitit.coercion.malli/create
                       {;; set of keys to include in error messages
                        :error-keys #{:coercion :in :schema :value :errors :humanized #_:transformed}
                           ;; schema identity function (default: close all map schemas)
                        :compile mu/closed-schema
                           ;; strip-extra-keys (effects only predefined transformers)
                        :strip-extra-keys true
                           ;; add/set default values
                        :default-values true
                           ;; malli options
                        :options nil})
            :muuntaja m/instance
            :middleware [;; swagger feature
                         swagger/swagger-feature
                           ;; query-params & form-params
                         parameters/parameters-middleware
                           ;; content-negotiation
                         muuntaja/format-negotiate-middleware
                           ;; encoding response body
                         muuntaja/format-response-middleware
                           ;; exception handling
                         exception/exception-middleware
                           ;; decoding request body
                         muuntaja/format-request-middleware
                           ;; coercing response bodys
                         coercion/coerce-response-middleware
                           ;; coercing request parameters
                         coercion/coerce-request-middleware
                           ;; multipart
                         multipart/multipart-middleware]}})
   (ring/routes
    (swagger-ui/create-swagger-ui-handler
     {:path "/"
      :config {:validatorUrl nil
               :operationsSorter "alpha"}})
    (ring/create-default-handler))))

(defn start-xtdb!
  "Use RocksDB for tx-log, document-store, and index-store
  source from https://docs.xtdb.com/guides/quickstart/"
  []
  (letfn [(kv-store [dir]
            {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
                        :db-dir (io/file dir)
                        :sync? true}})]
    (xt/start-node
     {:xtdb/tx-log (kv-store "data/dev/tx-log")
      :xtdb/document-store (kv-store "data/dev/doc-store")
      :xtdb/index-store (kv-store "data/dev/index-store")})))

(defn start-jetty! [& {:keys [port] :or {port 3000}}]
  (jetty/run-jetty #'app {:port port :join? false}))

(defn start [& {:keys [port] :or {port 3000}}]
  (reset! xtdb-node* (start-xtdb!))
  (println "STARTED XTDB")
  (reset! server* (jetty/run-jetty (app @xtdb-node*) {:port port :join? false}))
  (println "STARTED SERVER")
  (println "server running in port 3000"))

(defn stop []
  (.stop @server*)
  (.close @xtdb-node*))

(comment
  (start)
  (stop)
  (do (stop)
      (start))
  )
