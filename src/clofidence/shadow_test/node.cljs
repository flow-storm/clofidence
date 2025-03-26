(ns clofidence.shadow-test.node
  (:require [shadow.test.env :as env]
            [cljs.test :as ct]
            [shadow.test :as st]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clofidence.config :as config]
            [clofidence.storm :as clofidence-storm]
            [clofidence.tracer :as clofidence-tracer]))

(defn ^:dev/after-load reset-test-data! []
  (-> (env/get-test-data)
      (env/reset-test-data!)))

(defn parse-args [args]
  (reduce
   (fn [opts arg]
     (cond
       (= "--help" arg)
       (assoc opts :help true)

       (= "--list" arg)
       (assoc opts :list true)

       (str/starts-with? arg "--test=")
       (let [test-arg (subs arg 7)
             test-syms
             (->> (str/split test-arg ",")
                  (map symbol))]
         (update opts :test-syms into test-syms))

       :else
       (do (println (str "Unknown arg: " arg))
           opts)
       ))
   {:test-syms []}
   args))

(defn find-matching-test-vars [test-syms]
  ;; FIXME: should have some kind of wildcard support
  (let [test-namespaces
        (->> test-syms (filter simple-symbol?) (set))
        test-var-syms
        (->> test-syms (filter qualified-symbol?) (set))]

    (->> (env/get-test-vars)
         (filter (fn [the-var]
                   (let [{:keys [name ns]} (meta the-var)]
                     (or (contains? test-namespaces ns)
                         (contains? test-var-syms (symbol ns name))))))
         )))

(defn execute-cli [{:keys [test-syms help list]}]
  (let [test-env
        (-> (ct/empty-env)
            ;; can't think of a proper way to let CLI specify custom reporter?
            ;; :report-fn is mostly for UI purposes, CLI should be fine with default report
            #_(assoc :report-fn
                     (fn [m]
                       (tap> [:test m (ct/get-current-env)])
                       (prn m))))]

    (cond
      help
      (do (println "Usage:")
          (println "  --list (list known test names)")
          (println "  --test=<ns-to-test>,<fqn-symbol-to-test> (run test for namespace or single var, separated by comma)"))

      list
      (doseq [[ns ns-info]
              (->> (env/get-tests)
                   (sort-by first))]
        (println "Namespace:" ns)
        (doseq [var (:vars ns-info)
                :let [m (meta var)]]
          (println (str "  " (:ns m) "/" (:name m))))
        (println "---------------------------------"))

      (seq test-syms)
      (let [test-vars (find-matching-test-vars test-syms)]
        (st/run-test-vars test-env test-vars))

      :else
      (st/run-all-tests test-env nil)
      )))

(defn shadow-node-main [& args]
  (reset-test-data!)

  (if env/UI-DRIVEN
    (js/console.log "Waiting for UI ...")
    (let [opts (parse-args args)]
      (execute-cli opts))))

(defn main [& args]
  (.then (js/fetch config/config-url #js {:method "GET"})
         (fn [config-resp]
           (.then (.text config-resp)
                  (fn [config-str]
                    (let [config-map (edn/read-string config-str)
                          _ (apply shadow-node-main args) ;; run the tests
                          ;; opts (parse-args args)
                          ;; _ (reset-test-data!)
                          ;; _ (execute-cli opts)
                          data (clofidence-storm/report-client-data config-map)]
                      (js/fetch config/report-url #js {:method "POST"
                                                       :body data})))))))
