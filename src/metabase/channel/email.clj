(ns metabase.channel.email
  (:require
   [metabase.analytics.core :as analytics]
   [metabase.channel.settings :as channel.settings]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]
   [metabase.util.malli.schema :as ms]
   [metabase.util.retry :as retry]
   [postal.core :as postal]
   [postal.support :refer [make-props]]
   [throttle.core :as throttle])
  (:import
   (javax.mail Session)
   (throttle.core Throttler)))

(set! *warn-on-reflection* true)

;; https://github.com/metabase/metabase/issues/11879#issuecomment-713816386
(when-not *compile-files*
  (System/setProperty "mail.mime.splitlongparameters" "false"))

(defn- make-email-throttler
  [rate-limit]
  (throttle/make-throttler
   :email
   :attempt-ttl-ms     1000
   :initial-delay-ms   1000
   :attempts-threshold rate-limit))

(defonce ^:private email-throttler (when-let [rate-limit (channel.settings/email-max-recipients-per-second)]
                                     (make-email-throttler rate-limit)))

(defn check-email-throttle
  "Check if the email throttler is enabled and if so, throttle the email sending based on the total number of recipients.

  We will allow multi-recipient emails to broach the limit, as long as the limit has not been reached yet.

  We want two properties:
    1. All emails eventually get sent.
    2. Lowering the threshold must never cause more overflow."
  [email]
  (when email-throttler
    (when-let [recipients (not-empty (into #{} (mapcat email) [:to :bcc]))]
      (let [throttle-threshold (.attempts-threshold ^Throttler email-throttler)
            check-one!         #(throttle/check email-throttler true)]
        (check-one!)
        (try
          (dotimes [_ (dec (count recipients))]
            (throttle/check email-throttler true))
          (catch Exception _e
            (log/warn "Email throttling is enabled and the number of recipients exceeds the rate limit per second. Skip throttling."
                      {:email-subject  (:subject email)
                       :recipients     (count recipients)
                       :max-recipients throttle-threshold})))))))

;; ## PUBLIC INTERFACE

(defn send-email!
  "Internal function used to send messages. Should take 2 args - a map of SMTP credentials, and a map of email details.
  Provided so you can swap this out with an \"inbox\" for test purposes.

  If email-rate-limit-per-second is set, this function will throttle the email sending based on the total number of recipients."
  [smtp-credentials email-details]
  (check-email-throttle email-details)
  (postal/send-message smtp-credentials email-details))

(defn- add-ssl-settings [m ssl-setting]
  (merge
   m
   (case (keyword ssl-setting)
     :tls      {:tls true}
     :ssl      {:ssl true}
     :starttls {:starttls.enable   true
                :starttls.required true}
     {})))

(defn- smtp-settings []
  (-> {:host (channel.settings/email-smtp-host)
       :user (channel.settings/email-smtp-username)
       :pass (channel.settings/email-smtp-password)
       :port (channel.settings/email-smtp-port)}
      (add-ssl-settings (channel.settings/email-smtp-security))))

(def ^:private EmailMessage
  [:and
   [:map {:closed true}
    [:subject      :string]
    [:recipients   [:or [:sequential ms/Email] [:set ms/Email]]]
    [:message-type [:enum :text :html :attachments]]
    [:message      [:or :string [:sequential :map]]]
    [:bcc?         {:optional true} [:maybe :boolean]]]
   [:fn {:error/message (str "Bad message-type/message combo: message-type `:attachments` should have a sequence of maps as its message; "
                             "other types should have a String message.")}
    (fn [{:keys [message-type message]}]
      (if (= message-type :attachments)
        (and (sequential? message) (every? map? message))
        (string? message)))]])

(defn send-message-or-throw!
  "Send an email to one or more `recipients`. Upon success, this returns the `message` that was just sent. This function
  does not catch and swallow thrown exceptions, it will bubble up. Should prefer to use [[send-email-retrying!]] unless
  the caller has its own retry logic."
  [{:keys [subject recipients message-type message bcc?] :as _email}]
  (try
    (when-not (channel.settings/email-smtp-host)
      (throw (ex-info (tru "SMTP host is not set.") {:cause :smtp-host-not-set})))
    ;; Now send the email
    (let [to-type (if bcc? :bcc :to)]
      (send-email! (smtp-settings)
                   (merge
                    {:from    (if-let [from-name (channel.settings/email-from-name)]
                                (str from-name " <" (channel.settings/email-from-address) ">")
                                (channel.settings/email-from-address))
                     ;; FIXME: postal doesn't accept recipients if it's a set, need to fix this from upstream
                     to-type  (seq recipients)
                     :subject subject
                     :body    (case message-type
                                :attachments message
                                :text        message
                                :html        [{:type    "text/html; charset=utf-8"
                                               :content message}])}
                    (when-let [reply-to (channel.settings/email-reply-to)]
                      {:reply-to reply-to}))))
    (catch Throwable e
      (analytics/inc! :metabase-email/message-errors)
      (when (not= :smtp-host-not-set (:cause (ex-data e)))
        (throw e)))
    (finally
      (analytics/inc! :metabase-email/messages))))

(mu/defn send-email-retrying!
  "Like [[send-message-or-throw!]] but retries sending on errors according to the retry settings."
  [email :- EmailMessage]
  ((retry/decorate send-message-or-throw!) email))

(def ^:private SMTPStatus
  "Schema for the response returned by various functions in [[metabase.channel.email]]. Response will be a map with the key
  `:metabase.channel.email/error`, which will either be `nil` (indicating no error) or an instance of [[java.lang.Throwable]]
  with the error."
  [:map {:closed true}
   [::error [:maybe (ms/InstanceOfClass Throwable)]]])

(defn send-message!
  "Send an email to one or more `:recipients`. `:recipients` is a sequence of email addresses; `:message-type` must be
  either `:text` or `:html` or `:attachments`.

    (email/send-message!
     {:subject      \"[Metabase] Password Reset Request\"
      :recipients   [\"cam@metabase.com\"]
      :message-type :text
      :message      \"How are you today?\")}

  Upon success, this returns the `:message` that was just sent. (TODO -- confirm this.) This function will catch and
  log any exception, returning a [[SMTPStatus]]."
  [& {:as msg-args}]
  (try
    (send-email-retrying! msg-args)
    (catch Throwable e
      (log/warn e "Failed to send email")
      {::error e})))

(def ^:private SMTPSettings
  [:map {:closed true}
   [:host                         ms/NonBlankString]
   [:port                         ms/PositiveInt]
   ;; TODO -- not sure which of these other ones are actually required or not, and which are optional.
   [:user        {:optional true} [:maybe :string]]
   [:security    {:optional true} [:maybe [:enum :tls :ssl :none :starttls]]]
   [:pass        {:optional true} [:maybe :string]]
   [:sender      {:optional true} [:maybe :string]]
   [:sender-name {:optional true} [:maybe :string]]
   [:reply-to    {:optional true} [:maybe [:sequential ms/Email]]]])

(mu/defn- test-smtp-settings :- SMTPStatus
  "Tests an SMTP configuration by attempting to connect and authenticate if an authenticated method is passed
  in `:security`."
  [{:keys [host port user pass sender security], :as details} :- SMTPSettings]
  (try
    (let [ssl?    (= (keyword security) :ssl)
          proto   (if ssl? "smtps" "smtp")
          details (-> details
                      (assoc :proto proto
                             :connectiontimeout "1000"
                             :timeout "4000")
                      (add-ssl-settings security))
          session (doto (Session/getInstance (make-props sender details))
                    (.setDebug false))]
      (with-open [transport (.getTransport session proto)]
        (.connect transport host port user pass)))
    {::error nil}
    (catch Throwable e
      (log/error e "Error testing SMTP connection")
      {::error e})))

(def ^:private email-security-order [:tls :starttls :ssl])

(def ^:private ^Long retry-delay-ms
  "Amount of time to wait between retrying SMTP connections with different security options. This delay exists to keep
  us from getting banned on Outlook.com."
  500)

(mu/defn- guess-smtp-security :- [:maybe [:enum :tls :starttls :ssl]]
  "Attempts to use each of the security methods in security order with the same set of credentials. This is used only
  when the initial connection attempt fails, so it won't overwrite a functioning configuration. If this uses something
  other than the provided method, a warning gets printed on the config page.

  If unable to connect with any security method, returns `nil`. Otherwise returns the security method that we were
  able to connect successfully with."
  [details :- SMTPSettings]
  ;; make sure this is not lazy, or chunking can cause some servers to block requests
  (some
   (fn [security-type]
     (if-not (::error (test-smtp-settings (assoc details :security security-type)))
       security-type
       (do
         (Thread/sleep retry-delay-ms) ; Try not to get banned from outlook.com
         nil)))
   email-security-order))

(mu/defn test-smtp-connection :- [:or SMTPStatus SMTPSettings]
  "Test the connection to an SMTP server to determine if we can send emails. Takes in a dictionary of properties such
  as:

    {:host     \"localhost\"
     :port     587
     :user     \"bigbird\"
     :pass     \"luckyme\"
     :sender   \"foo@mycompany.com\"
     :security :tls}

  Attempts to connect with different `:security` options. If able to connect successfully, returns working
  [[SMTPSettings]]. If unable to connect with any `:security` options, returns an [[SMTPStatus]] with the `::error`."
  [details :- SMTPSettings]
  (let [initial-attempt (test-smtp-settings details)]
    (if-not (::error initial-attempt)
      details
      (if-let [working-security-type (guess-smtp-security details)]
        (assoc details :security working-security-type)
        initial-attempt))))
