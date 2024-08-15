(ns clofidence.utils
  (:require [clojure.string :as str])
  #?(:clj (:import [java.util HashSet HashMap])))

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
               (and (symbol? fdi)
                 (#{"fn" "fn*"} (name fdi)))))))))))

(defn first-symb [form]
  (when (and (seq? form)
             (symbol? (first form)))
    (first form)))

(defn stringify-coord [coord-vec]
  (str/join "," coord-vec))

;;;;;;;;;;;;;;;;;
;; Mutable map ;;
;;;;;;;;;;;;;;;;;

#?(:clj (defn make-mutable-map [] (HashMap.))
   :cljs (defn make-mutable-map [] (js/Map.)))

#?(:clj (defn mutable-map-put [^HashMap mh k v]
          (.put mh k v))
   :cljs (defn mutable-map-put [mh k v]
           (.set mh k v)))

#?(:clj (defn mutable-map-contains? [^HashMap mh k]
          (.containsKey mh k))
   :cljs (defn mutable-map-contains? [mh k]
           (.has mh k)))

#?(:clj (defn mutable-map-get [^HashMap mh k]
          (.get mh k))
   :cljs (defn mutable-map-get [mh k]
           (.get mh k)))

;;;;;;;;;;;;;;;;;
;; Mutable set ;;
;;;;;;;;;;;;;;;;;

#?(:clj (defn make-mutable-set [] (HashSet.))
   :cljs (defn make-mutable-set [] (js/Set.)))

#?(:clj (defn mutable-set-add [^HashSet ms o]
          (.add ms o))
   :cljs (defn mutable-set-add [ms o]
           (.add ms o)))

#?(:clj
   (defmacro env-prop [prop-name]
     (System/getProperty prop-name)))
