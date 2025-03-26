(ns clofidence.main

  "Run all the test suite under ClojureStorm instrumentation and generate a report
  of our test coverage."

  (:require [clojure.set :as set]
            [clojure.java.io :as io]
            [clofidence.form-pprinter :as form-pprinter]
            [clofidence.utils :as utils]
            [clofidence.report-renderer :as renderer]
            [clofidence.tracer :as clofidence-tracer]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.cors :refer [wrap-cors]])
  (:import [java.io File]))


(defn- make-report [all-registered-forms coords-cov]
  (let [registered-forms-ids (into #{} (keys all-registered-forms))
        covered-forms-ids (into #{} (keys coords-cov))
        process-form (fn [x]
                       (let [[form-id {:keys [form/ns form/form form/emitted-coords]}] x
                             coords-hits (get coords-cov form-id)
                             coords-hittable (or emitted-coords
                                                 (-> form meta :clojure.storm/emitted-coords))
                             form-tokens (->> (form-pprinter/pprint-tokens form)
                                              (mapv (fn [{:keys [coord kind] :as token}]
                                                      (let [coord (when coord (utils/stringify-coord coord))]
                                                        (if (= kind :text)
                                                          (assoc token
                                                                 :cover-type
                                                                 (cond
                                                                   (contains? coords-hits coord)     :hit
                                                                   (contains? coords-hittable coord) :hittable
                                                                   :else                             :non-hittable))
                                                          token)))))
                             sub-form-hittable-cnt (count coords-hittable)
                             sub-form-hits-cnt (count (keys coords-hits))]
                         (when (pos? sub-form-hittable-cnt)
                           {:tokens form-tokens
                            :form-id form-id
                            :sub-form-hits-cnt     sub-form-hits-cnt
                            :sub-form-hittable-cnt sub-form-hittable-cnt
                            :hit-rate (if (pos? sub-form-hittable-cnt)
                                        (float (/ sub-form-hits-cnt sub-form-hittable-cnt))
                                        0.0)
                            :ns ns})))
        processed-forms (into [] (keep process-form) all-registered-forms)

        by-ns (update-vals (group-by :ns processed-forms)
                           (fn [forms]
                             {:forms-hits (sort-by :hit-rate > forms)
                              :ns-sub-form-hits (reduce #(+ %1 (:sub-form-hits-cnt %2)) 0 forms)
                              :ns-hittable-sub-forms-cnt (reduce #(+ %1 (:sub-form-hittable-cnt %2)) 0 forms)}))
        total-hittables-sub-forms (reduce #(+ %1 (:ns-hittable-sub-forms-cnt %2)) 0 (vals by-ns))
        total-sub-form-hits (reduce #(+ %1 (:ns-sub-form-hits %2)) 0 (vals by-ns))]

    {:forms-details-by-ns by-ns

     :total-forms (count registered-forms-ids)
     :total-forms-hitted (count (set/intersection covered-forms-ids registered-forms-ids))

     :total-sub-forms total-hittables-sub-forms
     :total-sub-forms-hits total-sub-form-hits}))



(defn- save [index-html ns-details-reports {:keys [output-folder]
                                            :or {output-folder "clofidence-output"}}]
  ;; ensure the output folder exists, if not, create it
  (let [out-folder-file (io/file output-folder)]

    (when-not (.exists out-folder-file)
      (.mkdir out-folder-file))

    (let [out-folder-path (.getAbsolutePath out-folder-file)]
      (spit (str out-folder-path File/separator "index.html" ) index-html)
      (doseq [[ns-file-name ns-file-html] ns-details-reports]
        (spit (str out-folder-path File/separator ns-file-name) ns-file-html)))))

(defn- total-coords-hits [coords-cov]
  (reduce-kv (fn [tot _ form-coords]
               (+ tot (count form-coords)))
   0
   coords-cov))

(defn report-and-save [coords-cov forms opts]
  (let [total-hits (total-coords-hits coords-cov)]
    (if (zero? total-hits)
      (println "\n\n Nothing recorded, so no report will be generated. Did you setup clojure.storm.instrumentOnlyPrefixes correctly?")
      (let [_ (println (format "Captured a total of %d forms coordinates hits for %d forms." total-hits (count coords-cov)))
            _ (println "Building and saving report...")
            report (make-report forms coords-cov)
            report-index-html (renderer/render-index-html report opts)
            ns-details-reports (renderer/render-namespaces-details-reports report)]
        (save report-index-html ns-details-reports opts)
        (println "All done")
        (System/exit 0)))))

(def cljs-server-port 7799)

(defn run-cljs [config]
  (println "Starting report server on" cljs-server-port)
  (let [handle-req (fn [{:keys [request-method uri body]}]
                     (try
                       (cond
                         (and (= request-method :post) (= uri "/report"))
                         (let [{:keys [coords-cov forms]} (read (clojure.lang.LineNumberingPushbackReader. (io/reader body)))]
                           (println (format "Tracing info submited coords-cov %d, forms %d" (count coords-cov) (count forms)))
                           (report-and-save coords-cov forms config)
                           {:status 200
                            :body ""})

                         (and (= request-method :get) (= uri "/config"))
                         {:status 200
                          :body (pr-str config)}

                         :else
                         (do
                           (println "No endpoint for" request-method uri)
                           {:status 404
                            :body ""}))
                       (catch Exception e
                         (.printStackTrace e))))]
    (jetty/run-jetty (wrap-cors handle-req
                                :access-control-allow-origin [#".*"]
                                :access-control-allow-methods [:get :post])
                     {:port cljs-server-port
                      :join? true})))

(comment
  ;; clojure
  (report-and-save {1 #{"3" "3,2"}}
                   {1 {:form/ns "my-app.core"
                       :form/form (with-meta '(defn sum [a b] (+ a b)) {:clojure.storm/emitted-coords #{"3" "3,1" "3,2"}})}}
                   {})

  ;; clojurescript
  (report-and-save {1 #{"3" "3,2"}}
                   {1 {:form/ns "my-app.core"
                       :form/form '(defn sum [a b] (+ a b))
                       :form/emitted-coords #{"3" "3,1" "3,2"}}}
                   {})
  )
(defn run

  "Run with clj -X:clofidence clofidence/run :report-name \"my-app\"

  Will generate my-app-coverage.html

  Some extra options could be :
  - :extra-forms #{my-def-macro defroute}"

  [{:keys [test-fn test-fn-args]
    :or {test-fn-args []}
    :as opts}]
  (require 'clofidence.storm)

  (let [tfn (requiring-resolve test-fn)]
    (println "Running all tests via " test-fn)
    (try
      (apply tfn test-fn-args)
      (catch Throwable t
        (println "ERROR: Tests function throwed an unhandled exception.\n Generating coverage report with what we got so far.")
        (.printStackTrace t))))

  (println "Tests done.")

  (let [registered-forms (requiring-resolve 'clofidence.storm/registered-forms)
        coords-cov (clofidence-tracer/immutable-coords-coverage)
        interesting-forms (clofidence-tracer/interesting-forms (registered-forms) opts)]
    (report-and-save coords-cov interesting-forms opts)))
