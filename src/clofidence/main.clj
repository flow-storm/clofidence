(ns clofidence.main

  "Run all the test suite under ClojureStorm instrumentation and generate a report
  of our test coverage."

  (:require [clojure.string :as str]
            [clojure.set :as set]
            [clofidence.form-pprinter :as form-pprinter]
            [clofidence.utils :as utils]
            [clofidence.report-renderer :as renderer])
  (:import [clojure.storm Tracer FormRegistry Emitter]
           [java.util HashMap HashSet]))

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
        processed-forms (->> all-registered-forms
                             (keep (fn [[form-id {:keys [form/ns form/form]}]]
                                     (let [coords-hits (get coords-cov form-id)
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
                                          :ns ns}))))
                             (doall))
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



(defn- save [file-name {:keys [details-str debug-str]}]
  (when details-str  (spit (format "%s-coverage.html" file-name) details-str))
  (when debug-str    (spit (format "%s.edn"          file-name) debug-str)))

(defn run

  "Run with clj -X:coverage coverage/run :report-name \"my-app\"

  Will generate my-app-coverage.html

  Some extra options could be :
  - :details?
  - :debug?
  - :extra-forms #{my-def-macro defroute}"

  [{:keys [test-fn test-fn-args report-name debug?]
    :or {test-fn-args []}
    :as opts}]
  (setup-storm)

  (let [tfn (requiring-resolve test-fn)]
    (println "Running all tests via " test-fn)
    (apply tfn test-fn-args))
  (println "Tests done. Building and saving report...")

  (let [coords-cov (immutable-coords-coverage)
        all-registered-forms (interesting-forms opts)]
    (save report-name {:details-str  (renderer/print-report-to-string (make-report all-registered-forms coords-cov) opts)
                       :debug-str    (when debug? (pr-str coords-cov))}))
  (println "All done."))

(comment
  (require '[dev-tester])


  (interesting-forms {})
  (run {:test-fn 'dev-tester/run-test
        :report-name "dev-tester"
        ;;:block-forms #{'defn}
        })
  )
