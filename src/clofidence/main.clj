(ns clofidence.main

  "Run all the test suite under ClojureStorm instrumentation and generate a report
  of our test coverage."

  (:require [clojure.string :as str]
            [clojure.set :as set]
            [clojure.java.io :as io]
            [clofidence.form-pprinter :as form-pprinter]
            [clofidence.utils :as utils]
            [clofidence.report-renderer :as renderer])
  (:import [clojure.storm Tracer FormRegistry Emitter]
           [java.util HashMap HashSet]
           [java.io File]))

(def coords-coverage
  "A map of form-id to a set of coordinates.
  This var uses a mutable HashMap and HashSet for performance so
  only use `hit-form-coord` to add and `immutable-coords-coverage` to
  read it."
  (HashMap.))

(defn- hit-form-coord [form-id coord]
  (let [^HashSet form-coords (locking coords-coverage
                               (if (.containsKey ^HashMap coords-coverage form-id)
                                 (.get ^HashMap coords-coverage form-id)
                                 (let [form-coords (HashSet.)]
                                   (.put ^HashMap coords-coverage form-id form-coords)
                                   form-coords)))]
    (locking form-coords
      (.add form-coords coord))))

(defn- immutable-coords-coverage []
  (locking coords-coverage
    (persistent!
     (reduce-kv (fn [r form-id form-coords]
                  (let [imm-coords (locking form-coords
                                     (persistent!
                                      (reduce (fn [imc coord]
                                                (conj! imc coord))
                                              (transient #{})
                                              form-coords)))]
                    (assoc! r form-id imm-coords)))
                (transient {})
                coords-coverage))))

(defn- setup-storm []
  (Emitter/setInstrumentationEnable true)

  (Emitter/setFnCallInstrumentationEnable false)
  (Emitter/setFnReturnInstrumentationEnable true)
  (Emitter/setExprInstrumentationEnable true)
  (Emitter/setBindInstrumentationEnable false)

  (Tracer/setTraceFnsCallbacks
   {:trace-expr-fn (fn [_ _ coord form-id] (hit-form-coord form-id coord))
    :trace-fn-return-fn (fn [_ _ coord form-id] (hit-form-coord form-id coord))}))

(defn- interesting-forms

  "Retrieve and select the forms that will be added to the report"

  [{:keys [extra-forms block-forms]}]
  (let [;; This is super hacky and should be solved in ClojureStorm.
        ;; It is currently registering all forms under the namespace,
        ;; which also contains forms added by Clojure that aren't found
        ;; on user's source code, and we don't want them to show up on
        ;; the reports.
        default-blocked-forms #{"var" "ns" "if" ".resetMeta" "def"
                                "." "defprotocol" "quote" "comment"}
        blocked-forms (when block-forms
                        (into default-blocked-forms (map name block-forms)))

        interesting-symbs (into #{"defn" "defn-" "defmethod" "extend-type" "extend-protocol"
                                  "deftype" "defrecord"}
                                (map name extra-forms))

        interesting-form? (if block-forms
                            (fn [form]
                              (when-let [symb (utils/first-symb form)]
                                (let [symb-ns (namespace symb)
                                      symb-name (name symb)]
                                  (and (not= symb-ns "clojure.core")
                                       (or (not (contains? blocked-forms symb-name))
                                           (utils/def-fn? form))))))

                            (fn [form]
                              (when-let [symb (utils/first-symb form)]
                                (let [symb-name (name symb)]
                                  (or (interesting-symbs symb-name)
                                      (utils/def-fn? form))))))]
    (reduce (fn [r {:keys [form/id form/form] :as frm}]
              (if (interesting-form? form)
                (assoc r id frm)
                r))
            {}
            (FormRegistry/getAllForms))))

(defn- stringify-coord [coord-vec]
  (str/join "," coord-vec))

(defn- make-report [all-registered-forms coords-cov]
  (let [registered-forms-ids (into #{} (keys all-registered-forms))
        covered-forms-ids (into #{} (keys coords-cov))
        process-form (fn [x]
                       (let [[form-id {:keys [form/ns form/form]}] x
                             coords-hits (get coords-cov form-id)
                             coords-hittable (-> form meta :clojure.storm/emitted-coords)
                             form-tokens (->> (form-pprinter/pprint-tokens form)
                                              (mapv (fn [{:keys [coord kind] :as token}]
                                                      (let [coord (when coord (stringify-coord coord))]
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

(defn run

  "Run with clj -X:clofidence clofidence/run :report-name \"my-app\"

  Will generate my-app-coverage.html

  Some extra options could be :
  - :extra-forms #{my-def-macro defroute}"

  [{:keys [test-fn test-fn-args]
    :or {test-fn-args []}
    :as opts}]
  (setup-storm)

  (let [tfn (requiring-resolve test-fn)]
    (println "Running all tests via " test-fn)
    (try
      (apply tfn test-fn-args)
      (catch Throwable t
        (println "ERROR: Tests function throwed an unhandled exception.\n Generating coverage report with what we got so far."))))

  (println "Tests done.")

  (let [coords-cov (immutable-coords-coverage)
        all-registered-forms (interesting-forms opts)
        total-hits (total-coords-hits coords-cov)
        _ (when (zero? total-hits)
            (println "\n\n Nothing recorded, so no report will be generated. Did you setup clojure.storm.instrumentOnlyPrefixes correctly?")
            (System/exit 1))
        _ (println (format "Captured a total of %d forms coordinates hits for %d forms." total-hits (count coords-cov)))
        _ (println "Building and saving report...")
        report (make-report all-registered-forms coords-cov)
        report-index-html (renderer/render-index-html report opts)
        ns-details-reports (renderer/render-namespaces-details-reports report)]
    (save report-index-html ns-details-reports opts))
  (println "All done."))

(comment
  (require '[dev-tester])


  (interesting-forms {})
  (run {:test-fn 'dev-tester/run-test
        :report-name "dev-tester"
        :output-folder "./report-output"
        ;;:block-forms #{'defn}
        })
  )
