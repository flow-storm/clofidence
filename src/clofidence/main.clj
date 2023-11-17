(ns clofidence.main

  "Run all the test suite under ClojureStorm instrumentation and generate a report
  of our test coverage."

  (:require [clojure.string :as str]
            [clojure.set :as set]
            [clofidence.form-pprinter :as form-pprinter])
  (:import [clojure.storm Tracer FormRegistry Emitter]
           [java.util HashMap HashSet]))

(def default-interesting-forms
  #{'defn 'defn- 'defmethod 'extend-type 'extend-protocol
    'deftype 'defrecord})

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

(def report-styles
  "body {font-family: sans; background-color: #3f474f; color: #c9d1d9}
   pre {padding: 10px; overflow: hidden;}
   .outer {max-width: 1000px; margin: auto; background-color: #323232; padding: 10px;}
   .hit {color: #7af27a; font-weight: bold;}
   .hittable {color: #ff5656; font-weight: bold;}
   .non-hittable {color: #d7dde3}
   .green {background-color: rgba(90, 253, 56, 0.15)}
   .yellow {background-color: rgba(253, 231, 56, 0.15)}
   .red {background-color: rgba(248,81,73,0.1)}
   .overview {margin-bottom: 30px; color: #323232}
   .ns-total {background-color: #ff5656; position: relative; height: 25px; display: inline-block; font-size: 12px; margin-bottom: 5px;}
   .ns-covered {background-color: #7af27a; height: 25px;}
   .ns-overview-text {position: absolute; top: 3px; left: 3px;}
")


(defn setup-storm []
  (Emitter/setInstrumentationEnable true)

  (Emitter/setFnCallInstrumentationEnable false)
  (Emitter/setFnReturnInstrumentationEnable true)
  (Emitter/setExprInstrumentationEnable true)
  (Emitter/setBindInstrumentationEnable false)

  (Tracer/setTraceFnsCallbacks
   {:trace-expr-fn (fn [_ _ coord form-id] (hit-form-coord form-id coord))
    :trace-fn-return-fn (fn [_ _ coord form-id] (hit-form-coord form-id coord))}))

(defn interesting-forms [interesting-first-symbol]
  (reduce (fn [r {:keys [form/id form/form] :as frm}]
            (if (and (seq? form)
                     (symbol? (first form))
                     (interesting-first-symbol (symbol (name (first form)))))
              (assoc r id frm)
              r))
          {}
          (FormRegistry/getAllForms)))

(defn- stringify-coord [coord-vec]
  (str/join "," coord-vec))

(defn make-report [all-registered-forms coords-cov]
  (let [registered-forms-ids (into #{} (keys all-registered-forms))
        covered-forms-ids (into #{} (keys coords-cov))
        processed-forms (->> all-registered-forms
                             (mapv (fn [[form-id {:keys [form/ns form/form]}]]
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
                                       {:tokens form-tokens
                                        :form-id form-id
                                        :sub-form-hits-cnt     sub-form-hits-cnt
                                        :sub-form-hittable-cnt sub-form-hittable-cnt
                                        :hit-rate (if (pos? sub-form-hittable-cnt)
                                                    (float (/ sub-form-hits-cnt sub-form-hittable-cnt))
                                                    0.0)
                                        :ns ns}))))
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

(defn print-form-html [{:keys [tokens hit-rate]}]
  (let [bg-color (cond
                   (<= 0.5 hit-rate 1.0)  "green"
                   (<  0.0  hit-rate 0.5) "yellow"
                   (=  0.0  hit-rate)      "red")]
    (println (format "<pre class=\"%s\">" bg-color))
    (doseq [{:keys [kind text cover-type]} tokens]
      (let [
            txt (case kind
                  :sp " "
                  :nl "\n"
                  :text (format "<span class=\"%s\">%s</span>" (name cover-type) text))]
        (print txt)))
    (println "\n</pre>")))

(defn lerp [min-val max-val t]
  (+ min-val (* t (- max-val min-val))))

(defn print-report-overview [forms-details-by-ns]
  (let [bar-font-size 12
        max-bar-width-px 1000
        min-bar-width-px (* bar-font-size
                            (+ 7 ;; the (NN %) text
                             (->> (keys forms-details-by-ns)
                                  (mapv count)
                                  (apply max))))
        biggest-ns-sub-forms-cnt (->> (vals forms-details-by-ns)
                                      (mapv :ns-hittable-sub-forms-cnt)
                                      (apply max))]
    (println "<div class=\"overview\">")
    (doseq [[ns-name {:keys [ns-sub-form-hits ns-hittable-sub-forms-cnt]}] forms-details-by-ns]
      (let [coverded-perc (int (* 100 (/ ns-sub-form-hits ns-hittable-sub-forms-cnt)))
            ns-bar-scale (/ ns-hittable-sub-forms-cnt biggest-ns-sub-forms-cnt)
            ns-bar-width-px (int (lerp min-bar-width-px max-bar-width-px ns-bar-scale))]
        (println (format "<div class=\"ns-total\" style=\"width: %dpx\">" ns-bar-width-px))
        (println (format "<span class=\"ns-overview-text\">%s (%d%%)</span>" ns-name coverded-perc))
        (println (format "<div class=\"ns-covered\" style=\"width: %d%%\"></div>" coverded-perc))
        (println "</div>")))
    (println "</div>")))

(defn print-report-to-string [{:keys [forms-details-by-ns
                                      total-forms total-forms-hitted
                                      total-sub-forms total-sub-forms-hits]}
                              {:keys [report-name details?]
                               :or {details? true}}]
  (let [forms-hit-rate (float (/ total-forms-hitted total-forms))
        sub-forms-hit-rate (float (/ total-sub-forms-hits total-sub-forms))]
    (with-out-str
      (println "<html>")
      (println (format "<head><style>%s</style></head>" report-styles))
      (println "<body>")
      (println "<div class=\"outer\">")
      (println (format "<h2>%s test coverage report</h2>" report-name))
      (println (format "<b><h4>Total forms hit rate : %d/%d (%.1f%%) </h4></b>" total-forms-hitted total-forms (* 100 forms-hit-rate)))
      (println (format "<b><h4>Total sub forms hit rate : %d/%d (%.1f%%)</h4></b>" total-sub-forms-hits total-sub-forms (* 100 sub-forms-hit-rate)))

      (print-report-overview forms-details-by-ns)

      (when details?
        (doseq [[ns-name ns-details] forms-details-by-ns]
          (let [{:keys [ns-sub-form-hits ns-hittable-sub-forms-cnt forms-hits]} ns-details
                ns-hit-rate (float (/ ns-sub-form-hits ns-hittable-sub-forms-cnt))]
            (println (format "<div><b>%s %d/%d (%.1f%%)</b>" ns-name ns-sub-form-hits ns-hittable-sub-forms-cnt (* 100 ns-hit-rate)))
            (doseq [form-detail forms-hits]
              (print-form-html form-detail)))))
      (println "</div>")
      (println "</body>")
      (println "</html>"))))

(defn save [file-name {:keys [details-str debug-str]}]
  (when details-str  (spit (format "%s-coverage.html" file-name) details-str))
  (when debug-str    (spit (format "%s.edn"          file-name) debug-str)))

(defn run

  "Run with clj -X:coverage coverage/run :report-name \"my-app\"

  Will generate my-app-coverage.html

  Some extra options could be :
  - :details?
  - :debug?
  - :extra-forms #{my-def-macro defroute}"

  [{:keys [test-fn test-fn-args report-name extra-forms debug?]
    :or {test-fn-args []}
    :as opts}]
  (setup-storm)

  (let [tfn (requiring-resolve test-fn)]
    (println "Running all tests via " test-fn)
    (apply tfn test-fn-args))
  (println "Tests done. Building and saving report...")

  (let [coords-cov (immutable-coords-coverage)
        all-registered-forms (interesting-forms (into default-interesting-forms extra-forms))]
    (save report-name {:details-str  (print-report-to-string (make-report all-registered-forms coords-cov) opts)
                       :debug-str    (when debug? (pr-str coords-cov))}))
  (println "All done."))

(comment
  (require '[dev-tester])


  (interesting-forms default-interesting-forms)
  (run {:test-fn 'dev-tester/run-test
        :report-name "dev-tester"})
  )
