(ns clofidence.report-renderer
  (:require [clofidence.utils :as utils]))

(def report-styles
  "body {font-family: sans; background-color: #3f474f; color: #c9d1d9}
   pre {padding: 10px; overflow: hidden;}
   .outer {max-width: 1000px; margin: auto; background-color: #323232; padding: 10px;}
   .hit {color: #7af27a; font-weight: bold;}
   .hittable {color: #ff5656; font-weight: bold;}
   .non-hittable {color: #d7dde3}
   .green {background-color: rgba(90, 253, 56, 0.15)}
   .yellow {background-color: rgba(253, 231, 56, 0.15)}
   .red {background-color: rgba(248,81,73,0.1)}
   .overview {margin-bottom: 30px; color: #323232}
   .ns-total {background-color: #ff5656; position: relative; height: 25px; display: inline-block; font-size: 12px; margin-bottom: 5px;}
   .ns-covered {background-color: #7af27a; height: 25px;}
   .ns-overview-text {position: absolute; top: 3px; left: 3px;} ")

(defn- print-form-html [{:keys [tokens hit-rate]}]
  (let [bg-color (cond
                   (<= 0.5 hit-rate 1.0)  "green"
                   (<  0.0  hit-rate 0.5) "yellow"
                   (=  0.0  hit-rate)      "red")]
    (println (format "<pre class=\"%s\">" bg-color))
    (doseq [{:keys [kind text cover-type]} tokens]
      (let [
            txt (case kind
                  :sp " "
                  :nl "\n"
                  :text (format "<span class=\"%s\">%s</span>" (name cover-type) text))]
        (print txt)))
    (println "\n</pre>")))

(defn- print-report-overview [forms-details-by-ns]
  (let [bar-font-size 12
        max-bar-width-px 1000
        min-bar-width-px (* bar-font-size
                            (+ 7 ;; the (NN %) text
                             (->> (keys forms-details-by-ns)
                                  (mapv count)
                                  (apply max))))
        biggest-ns-sub-forms-cnt (->> (vals forms-details-by-ns)
                                      (mapv :ns-hittable-sub-forms-cnt)
                                      (apply max))]
    (println "<div class=\"overview\">")
    (doseq [[ns-name {:keys [ns-sub-form-hits ns-hittable-sub-forms-cnt]}] forms-details-by-ns]
      (let [coverded-perc (int (* 100 (/ ns-sub-form-hits ns-hittable-sub-forms-cnt)))
            ns-bar-scale (/ ns-hittable-sub-forms-cnt biggest-ns-sub-forms-cnt)
            ns-bar-width-px (int (utils/lerp min-bar-width-px max-bar-width-px ns-bar-scale))]
        (println (format "<div class=\"ns-total\" style=\"width: %dpx\">" ns-bar-width-px))
        (println (format "<span class=\"ns-overview-text\">%s (%d%%)</span>" ns-name coverded-perc))
        (println (format "<div class=\"ns-covered\" style=\"width: %d%%\"></div>" coverded-perc))
        (println "</div>")))
    (println "</div>")))

(defn print-report-to-string [{:keys [forms-details-by-ns
                                      total-forms total-forms-hitted
                                      total-sub-forms total-sub-forms-hits]}
                              {:keys [report-name details?]
                               :or {details? true}}]
  (let [forms-hit-rate (float (/ total-forms-hitted total-forms))
        sub-forms-hit-rate (float (/ total-sub-forms-hits total-sub-forms))]
    (with-out-str
      (println "<html>")
      (println (format "<head><style>%s</style></head>" report-styles))
      (println "<body>")
      (println "<div class=\"outer\">")
      (println (format "<h2>%s test coverage report</h2>" report-name))
      (println (format "<b><h4>Total forms hit rate : %d/%d (%.1f%%) </h4></b>" total-forms-hitted total-forms (* 100 forms-hit-rate)))
      (println (format "<b><h4>Total sub forms hit rate : %d/%d (%.1f%%)</h4></b>" total-sub-forms-hits total-sub-forms (* 100 sub-forms-hit-rate)))

      (print-report-overview forms-details-by-ns)

      (when details?
        (doseq [[ns-name ns-details] forms-details-by-ns]
          (let [{:keys [ns-sub-form-hits ns-hittable-sub-forms-cnt forms-hits]} ns-details
                ns-hit-rate (float (/ ns-sub-form-hits ns-hittable-sub-forms-cnt))]
            (println (format "<div><b>%s %d/%d (%.1f%%)</b>" ns-name ns-sub-form-hits ns-hittable-sub-forms-cnt (* 100 ns-hit-rate)))
            (doseq [form-detail forms-hits]
              (print-form-html form-detail)))))
      (println "</div>")
      (println "</body>")
      (println "</html>"))))
