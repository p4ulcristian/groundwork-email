(ns postbox.api-names-test
  "The public names of postbox.api. Adding or removing one fails here, so
   the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [postbox.api]))

(deftest the-api-names
  (is (= '#{settings-shape
           email-shape
           webhook-settings-shape
           emails-table
           make-postbox
           make-background-job-kind
           queue-email!
           find-email
           make-webhook-routes}
         (set (keys (ns-publics 'postbox.api))))))
