(ns alida.test-helpers
  "Shared test utilities.")

(defn fake-http
  "Return a sys map whose :alida/http-request looks up canned responses by URL,
   recording each request into the requests atom. Missing URLs return HTTP 500.

   responses : map of url-string -> response map
   requests  : atom holding a vector, appended with each request map"
  [responses requests]
  {:alida/http-request (fn [request]
                         (swap! requests conj request)
                         (or (get responses (:url request))
                             {:status 500
                              :body (str "missing fake response for " (:url request))}))})
