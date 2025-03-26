(ns clofidence.tracer
  (:require [clofidence.utils :as utils]))

(def coords-coverage
  "A map of form-id to a set of coordinates.
  This var uses a mutable map and set for performance so
  only use `hit-form-coord` to add and `immutable-coords-coverage` to
  read it."
  nil)

(defn init []
  #?(:clj (alter-var-root #'coords-coverage (constantly (utils/make-mutable-map)))
     :cljs (set! coords-coverage (utils/make-mutable-map))))

;; initialize coords-coverage as soon as we load this ns
(init)

(defn hit-form-coord [form-id coord]
  (let [form-coords (locking coords-coverage
                      (if (utils/mutable-map-contains? coords-coverage form-id)
                        (utils/mutable-map-get coords-coverage form-id)
                        (let [form-coords (utils/make-mutable-set)]
                          (utils/mutable-map-put coords-coverage form-id form-coords)
                          form-coords)))]
    (locking form-coords
      (utils/mutable-set-add form-coords coord))))

(defn immutable-coords-coverage []
  (locking coords-coverage
    (persistent!
     (reduce (fn [r [form-id form-coords]]
               (let [imm-coords (locking form-coords
                                  (persistent!
                                   (reduce (fn [imc coord]
                                             (conj! imc coord))
                                           (transient #{})
                                           form-coords)))]
                 (assoc! r form-id imm-coords)))
             (transient {})
             coords-coverage))))


(defn interesting-forms

  "Retrieve and select the forms that will be added to the report"

  [registered-forms {:keys [extra-forms block-forms]}]
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
            
            registered-forms)))
