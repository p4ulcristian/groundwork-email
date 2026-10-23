(ns postbox.metrics
  "postbox's metrics, declared on the app's meter by make-postbox:

     postbox_emails_queued_total{tag}              counter: emails queued (one queued in a transaction undone later too)
     postbox_emails_sent_total{tag}                counter: emails the provider took
     postbox_email_sends_failed_total{tag}         counter: attempts the provider failed or refused
     postbox_webhook_events_total{webhook,outcome} counter: what the two webhooks were posted, by how it ended

   The tags' set is the settings' :tags."
  (:require
   [meter.api :as meter]))

(def webhooks #{:delivery :inbound})

(def outcomes
  "How a webhook post ended:
   :handled     the app's function ran
   :no-function no function for its tag or mailbox; written to the log
   :ignored     a delivery event postbox doesn't hand on (a delivery, an open, a click)
   :failed      the app's function threw; written to the log, answered 200
   :refused     no or the wrong token: 401
   :bad-body    not a JSON object: 400"
  #{:handled :no-function :ignored :failed :refused :bad-body})

(defn declare-metrics! [meter tags]
  (meter/declare-metrics!
   meter
   [{:name    :postbox/emails-queued-total
     :type    :counter
     :help    "Emails queued, by tag (one queued in a transaction undone later too)."
     :labels  {:tag tags}
     :buckets nil
     :read    nil}
    {:name    :postbox/emails-sent-total
     :type    :counter
     :help    "Emails the provider took, by tag."
     :labels  {:tag tags}
     :buckets nil
     :read    nil}
    {:name    :postbox/email-sends-failed-total
     :type    :counter
     :help    "Attempts to send an email that the provider failed or refused, by tag."
     :labels  {:tag tags}
     :buckets nil
     :read    nil}
    {:name    :postbox/webhook-events-total
     :type    :counter
     :help    "Posts to the delivery and inbound webhooks, by how they ended."
     :labels  {:webhook webhooks :outcome outcomes}
     :buckets nil
     :read    nil}]))

(defn count-queued! [meter tag]
  (meter/add-to-counter! meter :postbox/emails-queued-total {:tag tag} 1))

(defn count-sent! [meter tag]
  (meter/add-to-counter! meter :postbox/emails-sent-total {:tag tag} 1))

(defn count-failed! [meter tag]
  (meter/add-to-counter! meter :postbox/email-sends-failed-total {:tag tag} 1))

(defn count-webhook-event! [meter webhook outcome]
  (meter/add-to-counter! meter :postbox/webhook-events-total {:webhook webhook :outcome outcome} 1))
