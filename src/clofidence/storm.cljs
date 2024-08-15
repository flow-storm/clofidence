(ns clofidence.storm
  (:require [clofidence.tracer :as clofidence-tracer]
            [cljs.storm.tracer :as cljs-storm-tracer]))

(def *cljs-forms-registry (atom {}))

(set! cljs-storm-tracer/trace-expr-fn (fn [_ _ coord form-id] (clofidence-tracer/hit-form-coord form-id coord)))
(set! cljs-storm-tracer/trace-fn-return-fn (fn [_ _ coord form-id] (clofidence-tracer/hit-form-coord form-id coord)))
(set! cljs-storm-tracer/trace-form-init-fn (fn [{:keys [form-id ns form emitted-coords]}]
                                             (swap! *cljs-forms-registry assoc form-id {:form/id form-id
                                                                                        :form/ns ns
                                                                                        :form/emitted-coords emitted-coords
                                                                                        :form/form form})))

(defn report-client-data [config]
  (let [reg-forms @*cljs-forms-registry
        interesting-forms (clofidence-tracer/interesting-forms (vals reg-forms) config)
        data {:coords-cov (clofidence-tracer/immutable-coords-coverage)
              :forms      interesting-forms}]
    (binding [*print-meta* true]
      (pr-str data))))
