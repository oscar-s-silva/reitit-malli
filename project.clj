(defproject ring-example "0.1.0-SNAPSHOT"
  :description "Reitit Ring App with Swagger"
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/tools.deps.alpha "0.14.1212"]
                 [metosin/jsonista "0.2.6"]
                 [com.xtdb/xtdb-core "1.21.0"]
                 [com.xtdb/xtdb-rocksdb "1.21.0"]
                 [ring/ring-jetty-adapter "1.7.1"]
                 [metosin/reitit "0.5.18"]
                 [clj-gatling "0.17.5"]]
  :repl-options {:init-ns example.server}
  :profiles {:dev {:dependencies [[ring/ring-mock "0.3.2"]]}})
