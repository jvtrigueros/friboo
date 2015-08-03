(ns org.zalando.stups.friboo.http-test
  (:require
    [clojure.test :refer :all]
    [org.zalando.stups.friboo.system.http :refer :all]
    [ring.middleware.gzip :as gzip]
    [amazonica.aws.s3 :as s3]
    [clj-time.format :as tf]
    [overtone.at-at :as at]))

(deftest test-map-authorization-header-simple
  (is (= "xyz" (map-authorization-header "xyz")))
  (is (= "Bearer 123" (map-authorization-header "Token 123"))))

(deftest test-map-authorization-header-basic-auth
  (is (= "Bearer 123" (map-authorization-header "Basic b2F1dGgyOjEyMw=="))))

(deftest test-add-logs-to-empty-list
  (let [logs (ref [])
        new-logs ["foo" "bar"]]
    (add-logs logs new-logs)
    (is (= @logs ["foo" "bar"]))))

(deftest test-add-logs-to-existing-ones
  (let [logs (ref ["bla"])
        new-logs ["foo" "bar"]]
    (add-logs logs new-logs)
    (is (= @logs ["bla" "foo" "bar"]))))

(deftest test-add-no-logs-to-existing-ones
  (let [logs (ref ["bla"])
        new-logs []]
    (add-logs logs new-logs)
    (is (= @logs ["bla"]))))

(deftest test-empty-empty-logs
  (let [logs (ref [])
        previous-logs (empty-logs logs)]
    (is (empty? @logs))
    (is (empty? previous-logs))))

(deftest test-empty-logs
  (let [logs (ref ["foo" "bar"])
        previous-logs (empty-logs logs)]
    (is (empty? @logs))
    (is (= ["foo" "bar"] previous-logs))))

(deftest test-is-modifying
  (let [methods [:post :put :patch :delete]]
    (doseq [method methods]
      (is (is-modifying? {:request-method method})))))

(deftest test-is-non-modifying
  (let [methods [:get :head :options]]
    (doseq [method methods]
      (is (not (is-modifying? {:request-method method}))))))

(deftest test-collect-audit-logs
  (let [dummy-response {:status 200 :body "foobar"}
        dummy-request {:swagger        "will be erased"
                       :configuration  "will be erased"
                       :body           "will be erased"
                       :tokeninfo      {"access_token" "will be erased"
                                        "uid"          "testuser"}
                       :headers        {"authorization" "will be erased"
                                        "content-type"  "application/json"}
                       :parameters     {:foo "bar"}
                       :request-method :post}
        next-handler (constantly dummy-response)
        logs (ref [])
        enabled? true
        handler-fn (collect-audit-logs next-handler logs enabled?)]
    (is (= dummy-response (handler-fn dummy-request)))
    (is (seq @logs))
    (let [line (first @logs)]
      (is (= (set (keys line)) #{:tokeninfo :headers :parameters :request-method :logged-on}))
      (is (= (:tokeninfo line) {"uid" "testuser"}))
      (is (= (:headers line) {"content-type" "application/json"})))))

(deftest test-collect-no-audit-logs-when-no-success1
  (let [dummy-response {:status 400 :body "foobar"}
        dummy-request {:swagger        "will be erased"
                       :configuration  "will be erased"
                       :body           "will be erased"
                       :tokeninfo      {"access_token" "will be erased"
                                        "uid"          "testuser"}
                       :headers        {"authorization" "will be erased"
                                        "content-type"  "application/json"}
                       :parameters     {:foo "bar"}
                       :request-method :post}
        next-handler (constantly dummy-response)
        logs (ref [])
        enabled? true
        handler-fn (collect-audit-logs next-handler logs enabled?)]
    (is (= dummy-response (handler-fn dummy-request)))
    (is (empty? @logs))))

(deftest test-collect-no-audit-logs-when-no-success2
  (let [dummy-response {:status 100 :body "foobar"}
        dummy-request {:swagger        "will be erased"
                       :configuration  "will be erased"
                       :body           "will be erased"
                       :tokeninfo      {"access_token" "will be erased"
                                        "uid"          "testuser"}
                       :headers        {"authorization" "will be erased"
                                        "content-type"  "application/json"}
                       :parameters     {:foo "bar"}
                       :request-method :post}
        next-handler (constantly dummy-response)
        logs (ref [])
        enabled? true
        handler-fn (collect-audit-logs next-handler logs enabled?)]
    (is (= dummy-response (handler-fn dummy-request)))
    (is (empty? @logs))))


(deftest test-collect-no-audit-logs-when-not-modifying
  (let [dummy-response {:status 200 :body "foobar"}
        dummy-request {:swagger        "will be erased"
                       :configuration  "will be erased"
                       :body           "will be erased"
                       :tokeninfo      {"access_token" "will be erased"
                                        "uid"          "testuser"}
                       :headers        {"authorization" "will be erased"
                                        "content-type"  "application/json"}
                       :parameters     {:foo "bar"}
                       :request-method :get}
        next-handler (constantly dummy-response)
        logs (ref [])
        enabled? true
        handler-fn (collect-audit-logs next-handler logs enabled?)]
    (is (= dummy-response (handler-fn dummy-request)))
    (is (empty? @logs))))

(deftest test-collect-no-audit-logs-when-disabled
  (let [dummy-response {:status 200 :body "foobar"}
        dummy-request {:swagger        "will be erased"
                       :configuration  "will be erased"
                       :body           "will be erased"
                       :tokeninfo      {"access_token" "will be erased"
                                        "uid"          "testuser"}
                       :headers        {"authorization" "will be erased"
                                        "content-type"  "application/json"}
                       :parameters     {:foo "bar"}
                       :request-method :post}
        next-handler (constantly dummy-response)
        logs (ref [])
        enabled? false
        handler-fn (collect-audit-logs next-handler logs enabled?)]
    (is (= dummy-response (handler-fn dummy-request)))
    (is (empty? @logs))))

(defn track
  "Adds a tuple on call for an action."
  ([a action]
   (fn [& all-args]
     (swap! a conj {:key  action
                    :args (into [] all-args)}))))

(deftest test-store-audit-logs
  (let [calls (atom [])
        audit-logs (ref [{:foo "bar"} {:foo "baz"}])]
    (with-redefs [s3/put-object (track calls :s3-put)
                  audit-logs-file-formatter (tf/formatter "'test-file'")]
      (store-audit-logs! audit-logs "test-bucket")

      (is (= 1 (count @calls)))
      (let [args (apply hash-map (:args (first @calls)))]
        (is (= (:key args) "test-file"))
        (is (= (:bucket-name args) "test-bucket"))
        (is (= (get-in args [:metadata :content-length]) 27))
        (is (= (with-open [rdr (clojure.java.io/reader (:input-stream args))]
                 (reduce conj [] (line-seq rdr)))
               ["{\"foo\":\"bar\"}", "{\"foo\":\"baz\"}"]))
        (is (empty? @audit-logs))))))

(deftest test-store-empty-audit-logs
  (let [calls (atom [])
        audit-logs (ref [])]
    (with-redefs [s3/put-object (track calls :s3-put)
                  audit-logs-file-formatter (tf/formatter "'test-file'")]
      (store-audit-logs! audit-logs "test-bucket")

      (is (empty? @calls)))))

(deftest test-store-audit-logs-failed
  (let [audit-logs (ref [{:foo "bar"} {:foo "baz"}])]
    (with-redefs [s3/put-object (fn [& _] (throw (Exception.)))]
      (store-audit-logs! audit-logs "test-bucket")
      (is (= (count @audit-logs) 2)))))

(deftest test-schedule-audit-log-flusher
  (let [calls (atom [])]
    (with-redefs [at/every (track calls :schedule)]
      (let [pool (schedule-audit-log-flusher! "test-bucket" (ref []) {:audit-flush-millis 1000})]
        (is pool)
        (is (= (count @calls) 1))))))

(deftest test-stop-audit-log-flusher
  (let [calls (atom [])
        bucket "test-bucket"
        logs (ref [])
        pool (atom {})]
    (with-redefs [at/stop-and-reset-pool! (track calls :stop)
                  store-audit-logs! (track calls :store-logs)]
      (stop-audit-log-flusher! bucket logs pool)

      (is (= (count @calls) 2))
      (is (some #(= (:key %) :stop) @calls))
      (is (some #(= (:key %) :store-logs) @calls)))))

(deftest test-gzip-encoding
  (let [dummy-response {:status 200 :body (pr-str (range 1 500))}
        dummy-request  {:headers {"accept-encoding" "gzip"}
                                 :request-method :get}
        next-handler (constantly dummy-response)
        zip-me (gzip/wrap-gzip next-handler)
        result (zip-me dummy-request)]
    (is (= (get-in result [:headers "Content-Encoding"])
           "gzip"))))