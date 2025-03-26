(ns clofidence.shadow-test.browser
  {:dev/always true}
  (:require [shadow.test :as st]
            [shadow.test.env :as env]
            [cljs-test-display.core :as ctd]
            [shadow.dom :as dom]
            [cljs.storm.tracer]
            [clofidence.storm :as clofidence-storm]
            [clofidence.tracer :as clofidence-tracer]
            [clofidence.config :as config]
            [clojure.edn :as edn]))



(defn start []
  (-> (env/get-test-data)
      (env/reset-test-data!))

  (.then (js/fetch config/config-url #js {:method "GET"})
         (fn [config-resp]
           (.then (.text config-resp)
                  (fn [config-str]
                    (let [config-map (edn/read-string config-str)]
                      (st/run-all-tests (ctd/init! "test-root"))
                      (js/fetch config/report-url #js {:method "POST"
                                                       :body (clofidence-storm/report-client-data config-map)}))))
           ))
  )

(defn stop [done]
  ; tests can be async. You must call done so that the runner knows you actually finished
  (done))

(defn ^:export init []
  (dom/append [:div#test-root])
  (start))
