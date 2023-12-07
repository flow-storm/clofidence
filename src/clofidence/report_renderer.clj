(ns clofidence.report-renderer
  (:require [clofidence.utils :as utils :refer [render-str-ln render-str]]))

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

(defn- render-form-html [sb {:keys [tokens hit-rate]}]
  (let [bg-color (cond
                   (<= 0.5 hit-rate 1.0)  "green"
                   (<  0.0  hit-rate 0.5) "yellow"
                   (=  0.0  hit-rate)      "red")]
    (render-str-ln sb "<pre class=\"%s\">" bg-color)
    (doseq [{:keys [kind text cover-type]} tokens]
      (let [txt (case kind
                  :sp " "
                  :nl "\n"
                  :text (format "<span class=\"%s\">%s</span>" (name cover-type) text))]
        (render-str sb txt)))
    (render-str-ln sb "\n</pre>")))

(defn- render-report-overview [sb forms-details-by-ns]
  (let [bar-font-size 12
        max-bar-width-px 1000
        min-bar-width-px (* bar-font-size
                            (+ 7 ;; the (NN %) text, calculate the longest namespace name
                             (->> (keys forms-details-by-ns)
                                  (mapv count)
                                  (apply max))))
        biggest-ns-sub-forms-cnt (->> (vals forms-details-by-ns)
                                      (mapv :ns-hittable-sub-forms-cnt)
                                      (apply max))
        calc-covered-perc (fn [{:keys [ns-sub-form-hits ns-hittable-sub-forms-cnt]}]
                            (if-not (pos? ns-hittable-sub-forms-cnt)
                              0
                              (int (* 100 (/ ns-sub-form-hits ns-hittable-sub-forms-cnt)))))
        sorted-nses (sort-by (comp calc-covered-perc second) > (seq forms-details-by-ns))]
    (render-str-ln sb "<div class=\"overview\">")
    (doseq [[ns-name {:keys [ns-hittable-sub-forms-cnt] :as ns-data}] sorted-nses]
      (let [coverded-perc (calc-covered-perc ns-data)
            ns-bar-scale (if-not (pos? biggest-ns-sub-forms-cnt)
                           0.0
                           (/ ns-hittable-sub-forms-cnt biggest-ns-sub-forms-cnt))
            ns-bar-width-px (int (utils/lerp min-bar-width-px max-bar-width-px ns-bar-scale))]
        (render-str-ln sb "<div class=\"ns-total\" style=\"width: %dpx\">" ns-bar-width-px)
        (render-str-ln sb "<span class=\"ns-overview-text\">%s (%d%%)</span>" ns-name coverded-perc)
        (render-str-ln sb "<div class=\"ns-covered\" style=\"width: %d%%\"></div>" coverded-perc)
        (render-str-ln sb "</div>")))
    (render-str-ln sb "</div>")))

(defn render-report-to-string [{:keys [forms-details-by-ns
                                      total-forms total-forms-hitted
                                      total-sub-forms total-sub-forms-hits]}
                              {:keys [report-name details?]
                               :or {details? true}}]
  (let [^StringBuilder sb (StringBuilder.)
        forms-hit-rate (if-not (pos? total-forms)
                         0.0
                         (float (/ total-forms-hitted total-forms)))
        sub-forms-hit-rate (if-not (pos? total-sub-forms)
                             0.0
                             (float (/ total-sub-forms-hits total-sub-forms)))]
    (render-str-ln sb "<html>")
    (render-str-ln sb (format "<head><style>%s</style></head>" report-styles))
    (render-str-ln sb "<body>")
    (render-str-ln sb "<div class=\"outer\">")
    (render-str-ln sb (format "<h2>%s test coverage report</h2>" report-name))
    (render-str-ln sb (format "<b><h4>Total forms hit rate : %d/%d (%.1f%%) </h4></b>" total-forms-hitted total-forms (* 100 forms-hit-rate)))
    (render-str-ln sb (format "<b><h4>Total sub forms hit rate : %d/%d (%.1f%%)</h4></b>" total-sub-forms-hits total-sub-forms (* 100 sub-forms-hit-rate)))

    (render-report-overview sb forms-details-by-ns)

    (when details?
      (doseq [[ns-name ns-details] forms-details-by-ns]
        (let [{:keys [ns-sub-form-hits ns-hittable-sub-forms-cnt forms-hits]} ns-details
              ns-hit-rate (if-not (pos? ns-hittable-sub-forms-cnt)
                            0.0
                            (float (/ ns-sub-form-hits ns-hittable-sub-forms-cnt)))]
          (render-str-ln sb (format "<div><b>%s %d/%d (%.1f%%)</b>" ns-name ns-sub-form-hits ns-hittable-sub-forms-cnt (* 100 ns-hit-rate)))
          (doseq [form-detail forms-hits]
            (render-form-html sb form-detail)))))
    (render-str-ln sb "</div>")
    (render-str-ln sb "</body>")
    (render-str-ln sb "</html>")
    (.toString sb)))
