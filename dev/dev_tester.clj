(ns dev-tester
  (:require [dev-tester.utils :as utils]))

;;;;;;;;;;;;;;;;;;;;;;;
;; Some testing code ;;
;;;;;;;;;;;;;;;;;;;;;;;

(defmulti do-it type)

(defmethod do-it java.lang.Long
  [l]
  (utils/factorial l))

(defmethod do-it java.lang.String
  [s]
  (count s))

(defprotocol Adder
  (add [x]))

(defrecord ARecord [n]

  Adder
  (add [_] (+ n 1000)))

(deftype AType [^int n]
  Adder
  (add [_] (int (+ n 42))))

(defprotocol Suber
  (sub [x]))

(extend-protocol Adder

  java.lang.Long
  (add [l] (+ l 5)))

(extend-type java.lang.Long

  Suber
  (sub [l] (- l 42)))

(def other-function
  (fn [a b]
    (+ a b 10)))

(defn inc-atom [a]
  (swap! a inc))

(defn hinted [a ^long b c ^long d]
  (+ a c (+ b d)))

(defn boo [xs]
  (let [a 25
        yy (other-function 4 5)
        hh (range)
        *a (atom 10)
        _ (inc-atom *a)
        xx @*a
        b (utils/dummy-sum-macro a 4)
        m ^{:meta1 true :meta2 "nice-meta-value"} {:a 5 :b ^:interesting-vector [1 2 3]}
        mm (assoc m :c 10)
        c (+ a b 7)
        d (add (->ARecord 5))
        e (add (AType. 10))
        j (loop [i 100
                 sum 0]
            (if (> i 0)
              (recur (dec i) (+ sum i))
              sum))]
    (->> xs
         (map (fn [x] (+ 1 (do-it x))))
         (reduce + )
         add
         sub
         (+ c d j e (hinted a c d j)))))

(defn return-fn
  []
  (fn hello [] "hello"))

(def what-was-said ((return-fn)))

(defn run-test []
  (boo [1 "hello" 4]))
