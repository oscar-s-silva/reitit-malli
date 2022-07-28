(ns example.util
  (:require [example.bank :as bank]))

(def test-account-ids {:allisson #uuid "00000000-0000-2000-8000-000000000000"
                       :bobson #uuid "00000000-0000-3000-8000-000000000000"
                       :carlton #uuid "00000000-0000-4000-8000-000000000000"})

(defn create-accounts [node]
  (let [acc-data [{:account-name "Mr. Allisson"
                   :account-id (:allisson test-account-ids)}
                  {:account-name "Ms. Bobson"
                   :account-id (:bobson test-account-ids)}
                  {:account-name "Mr. Carlton"
                   :account-id (:carlton test-account-ids)}]
        accs (vec (for [acc acc-data
                        :let [{:keys [account-number]} (bank/create-account node (:account-name acc))]]
                    account-number))]
    accs))
