(ns clofidence.utils)

(defn lerp [min-val max-val t]
  (+ min-val (* t (- max-val min-val))))

(defn def-fn? [form]
  (boolean
   (when (seq? form)
     (when-let [fsymb (first form)]
       (when (= "def" (name fsymb))
         (let [def-init-form (last form)]
           (when (seq? def-init-form)
             (when-let [fdi (first def-init-form)]
               (#{"fn" "fn*"} (name fdi))))))))))

(defn first-symb [form]
  (when (and (seq? form)
             (symbol? (first form)))
    (first form)))

(defn render-str [^StringBuilder sb format-str & format-args]
  (if (seq format-args)
    (.append sb (apply format format-str format-args))
    (.append sb format-str)))

(defn render-str-ln [^StringBuilder sb format-str & format-args]
  (if (seq format-args)
    (.append sb (apply format format-str format-args))
    (.append sb format-str))
  (.append sb "\n"))
