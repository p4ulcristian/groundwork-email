(ns groundwork-email.api-names-test
  "The public names of groundwork-email.api. Adding or removing one fails here, so
   the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork-email.api]))

(deftest the-api-names
  (is (= '#{settings-shape
           email-shape
           webhook-settings-shape
           emails-table
           make-mailer
           make-background-job-kind
           queue-email!
           find-email
           make-webhook-routes}
         (set (keys (ns-publics 'groundwork-email.api))))))
