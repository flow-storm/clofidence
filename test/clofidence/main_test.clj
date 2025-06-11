(ns clofidence.main-test
  (:require [clojure.test :refer :all]
            [clofidence.main :as main])
  (:import [java.io StringReader]))

(deftest test-read-with-tagged-literals
  (testing "Correctly reads data with tagged literals"
    (let [data "#unknown {:foo :bar}"
          reader (StringReader. data)
          result (main/read-with-tags reader)]
      ;; The first value should be the tagged literal map
      (is (instance? clojure.lang.TaggedLiteral result))
      (is (= 'unknown (:tag result)))
      (is (= '{:foo :bar} (:form result))))))
