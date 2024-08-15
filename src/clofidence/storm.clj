(ns clofidence.storm
  (:require [clofidence.tracer :as clofidence-tracer])
  (:import [clojure.storm Emitter Tracer FormRegistry]))

(Emitter/setInstrumentationEnable true)
(Emitter/setFnCallInstrumentationEnable false)
(Emitter/setFnReturnInstrumentationEnable true)
(Emitter/setExprInstrumentationEnable true)
(Emitter/setBindInstrumentationEnable false)

(Tracer/setTraceFnsCallbacks
 {:trace-expr-fn (fn [_ _ coord form-id] (clofidence-tracer/hit-form-coord form-id coord))
  :trace-fn-return-fn (fn [_ _ coord form-id] (clofidence-tracer/hit-form-coord form-id coord))})

(defn registered-forms [] (FormRegistry/getAllForms))
