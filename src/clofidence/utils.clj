(ns clofidence.utils)

(defn lerp [min-val max-val t]
  (+ min-val (* t (- max-val min-val))))
