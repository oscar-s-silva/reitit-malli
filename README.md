# Bank App

## Introduction

A bank application that allows
1. creating accounts
2. depositing/withdrawing units to/from an account
3. transfering units from account to account

## API

Bank actions are available through a REST API

The API can be accessed via swagger-ui at http://{hostname:localhost}:{port:3000}/index.html

## Technologies
Web server: Jetty
DB: XTDB (RocksDB [tx-log,document-store,index-store])
HTTP+Router: Ring+Reitit
Schemas+Transformers: Malli+Muuntaja

Tests:
Property based testing: org.clojure/test.check
Load testing: clj-gatling
test-runner: cognitect-labs/test-runner

## Usage
### Running the server
The following command runs Bank App server in localhost port 3000
```clj
clj -X:run-x

```
### Running the tests
```clj
clj -X:test
```

