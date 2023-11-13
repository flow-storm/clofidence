(ns dev-tester.utils)

(defmacro dummy-sum-macro [a b]
  `(+ ~a ~b))

(defn factorial [n]
  (if (zero? n)
    1
    (* n (factorial (dec n)))))
