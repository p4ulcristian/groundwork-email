(ns groundwork-email.metrics
  "groundwork-email's metrics, declared on the app's meter by make-mailer:

     groundwork_email_emails_queued_total{tag}              counter: emails queued (one queued in a transaction undone later too)
     groundwork_email_emails_sent_total{tag}                counter: emails the provider took
     groundwork_email_email_sends_failed_total{tag}         counter: attempts the provider failed or refused
     groundwork_email_emails_gave_up_total{tag}             counter: emails the provider refused for themselves, marked gave-up
     groundwork_email_webhook_events_total{webhook,outcome} counter: what the two webhooks were posted, by how it ended

   The tags' set is the settings' :tags."
  (:require
   [groundwork-metrics.api :as metrics]))

(def webhooks #{:delivery :inbound})

(def outcomes
  "How a webhook post ended:
   :handled     the app's function ran
   :no-function no function for its tag (a delivery event); written to the log
   :ignored     a delivery event groundwork-email doesn't hand on (a delivery, an open, a click)
   :failed      the app's function threw; written to the log, answered 200
   :refused     no or the wrong token: 401
   :bad-body    not a JSON object: 400"
  #{:handled :no-function :ignored :failed :refused :bad-body})

(defn declare-metrics! [meter tags]
  (metrics/declare-metrics!
   meter
   [{:name    :groundwork-email/emails-queued-total
     :type    :counter
     :help    "Emails queued, by tag (one queued in a transaction undone later too)."
     :labels  {:tag tags}
     :buckets nil
     :read    nil}
    {:name    :groundwork-email/emails-sent-total
     :type    :counter
     :help    "Emails the provider took, by tag."
     :labels  {:tag tags}
     :buckets nil
     :read    nil}
    {:name    :groundwork-email/email-sends-failed-total
     :type    :counter
     :help    "Attempts to send an email that the provider failed or refused, by tag."
     :labels  {:tag tags}
     :buckets nil
     :read    nil}
    {:name    :groundwork-email/emails-gave-up-total
     :type    :counter
     :help    "Emails the provider refused for themselves, which no attempt sends, marked gave-up, by tag."
     :labels  {:tag tags}
     :buckets nil
     :read    nil}
    {:name    :groundwork-email/webhook-events-total
     :type    :counter
     :help    "Posts to the delivery and inbound webhooks, by how they ended."
     :labels  {:webhook webhooks :outcome outcomes}
     :buckets nil
     :read    nil}]))

(defn count-queued! [meter tag]
  (metrics/add-to-counter! meter :groundwork-email/emails-queued-total {:tag tag} 1))

(defn count-sent! [meter tag]
  (metrics/add-to-counter! meter :groundwork-email/emails-sent-total {:tag tag} 1))

(defn count-failed! [meter tag]
  (metrics/add-to-counter! meter :groundwork-email/email-sends-failed-total {:tag tag} 1))

(defn count-gave-up! [meter tag]
  (metrics/add-to-counter! meter :groundwork-email/emails-gave-up-total {:tag tag} 1))

(defn count-webhook-event! [meter webhook outcome]
  (metrics/add-to-counter! meter :groundwork-email/webhook-events-total {:webhook webhook :outcome outcome} 1))
