(ns net.coruscation.espoir.main)
(require '[babashka.pods :as pods])
;; workaround for https://github.com/babashka/pods/issues/25
(require '[clojure.zip])
(def pod (pods/load-pod 'retrogradeorbit/bootleg "0.1.9"))
(require '[pod.retrogradeorbit.hickory.select :as hs])
(require '[clojure.string :as str])
(require '[pod.retrogradeorbit.bootleg.utils :as bootleg])
(require '[clojure.term.colors :as term])
(require '[clojure.java.io :as io])
(require '[clojure.tools.cli :as cli])
(require '[babashka.pods :as pods])

(term/define-color-function :italic (str "\033[" 3 "m"))

(def cli-options
  [["-h" "--help" "Show help messages" :default false]
   ["-s" "--short" "Show results in a more concise format, omitting some information."
    :default false]
   ["-a" "--all" "Show all translation sections (only principal translations are shown by default)"
    :default false]
   ["-N" "--no-inflections" "Don't show inflection sections"
    :default false]
   ["-I" "--inflections-only" "Only show inflection sections"
    :default false]
   ["-n" "--no-color" "Disable ascii color output, env NO_COLOR is also supported"
    :default false]])

(def ^:dynamic *options* (atom (cli/get-default-options cli-options)))

(defn extract-string [tag & {:keys [spacer]
                             :or {spacer " "}}]
  (letfn [(inner [tag]
            (->> (cond
                   (string? tag) tag
                   (map? tag) (inner (:content tag))
                   true    (map inner tag))))]
    (->> (inner tag)
         (conj [])
         flatten
         (remove #(= "" (str/trim %)))
         (str/join spacer)
         str/trim)))

(defn collapse-whitespace [st]
  (.replaceAll st "\\s+" " "))

(defn indices-of [f coll]
  (keep-indexed #(if (f %2) %1 nil) coll))

(defn first-index-of [f coll]
  (first (indices-of f coll)))

(defn split-by [pred coll]
  (let [index (first-index-of pred coll)]
    (if (nil? index)
      [coll []]
      [(take index coll)
       (drop (inc index) coll)])))

(defn split-by-all [pred coll]
  (loop [coll coll
         result []]
    (if (seq coll)
      (let [[first second] (split-by pred coll) ]
        (recur second
               (if (seq first)
                 (conj result first)
                 result)))
      result)))

(defn process-definition [tags]
  (letfn [(remove-tooltip [tags]
            (filter (fn [tag]
                      (not (or (= (:class (:attrs tag)) "POS2")
                               (= (:class (:attrs tag)) "conjugate"))))
                    tags))
          (extract-word [tags]
            (as-> tags %
              (remove-tooltip %)
              (extract-string % :spacer "")
              (collapse-whitespace %)))
          (process-meaning [tag]
            (let [[_ explanation to-word]
                  (hs/select (hs/tag "td")
                             tag)]
              {:meaning-in-fr (some->>  explanation
                                        :content
                                        ((fn [contents] (if (> (count contents) 1)
                                                          (drop-last contents)
                                                          contents)))
                                        extract-string
                                        (re-matches #".*?\((.*)\).*")
                                        second
                                        str/trim)
               :meaning-in-to (some->> (hs/select (hs/class "dsense")
                                                  explanation)
                                       first
                                       extract-string
                                       (re-matches #".*?\((.*)\).*")
                                       second
                                       str/trim)
               :to-wd (some-> to-word
                              :content
                              extract-word)
               :to-tooltip (some-> (hs/select (hs/class "POS2")
                                              tag)
                                   first
                                   :content
                                   first)}))]
    {:fr-wd (->> (hs/select (hs/child (hs/class "FrWrd")
                                      (hs/tag "strong"))
                            (first tags))
                 first
                 :content
                 extract-word)
     :fr-tooltip (some-> (hs/select (hs/child (hs/class "FrWrd")
                                              (hs/class "POS2"))
                                    (first tags))
                         first
                         :content
                         first
                         str/trim)
     :meanings (map process-meaning tags)}))

(defn process-example-sentences [sentences-tags]
  (->> sentences-tags
       (map #(hs/select (hs/or (hs/class "FrEx")
                               (hs/class "ToEx"))
                        %))
       flatten
       (map (fn [item]
              (let [text (extract-string item)]
                {:italic (seq (hs/select (hs/class "ToEx")
                                         item))
                 :text text})))))

(defn process-wrd [wrd]
  (hs/select (hs/child (hs/tag "tbody")
                       (hs/tag "tr"))
             wrd)
  (let [title-tag (first (hs/select (hs/child (hs/class "wrtopsection")
                                              (hs/tag "td"))
                                    wrd))
        meaning-tags (hs/select (hs/or (hs/class "even")
                                       (hs/class "odd"))
                                wrd)]
    (let [definitions (reduce (fn [result next]
                                (if (some-> next
                                            :attrs
                                            :id
                                            (str/starts-with? "fren:"))
                                  (conj result [next])
                                  (update result
                                          (dec (count result))
                                          (fn [item]
                                            (conj item next)))))
                              []
                              meaning-tags)]
      {:title (-> title-tag
                  :attrs
                  :title)
       :definitions  (map (fn [_]
                            (let [[meanings sentences] (split-with #(seq (hs/select (hs/class "ToWrd") %)) _)]
                              {:definition (process-definition meanings)
                               :example-sentences (process-example-sentences sentences)}))
                          definitions)})))



(defn get-declension [elements]
  (if (string? (first elements))
    {:annot (first elements)}
    (letfn [(process [[base _ {[word-class] :content} _ & inflections]]
              {:base (extract-string base)
               :word-class word-class
               :declension
               (->> inflections
                    (partition 2)
                    (map (fn [[genre-or-nombre-ele raw-inflection]]
                           {:code (-> genre-or-nombre-ele
                                      :content
                                      first)
                            :text (->> raw-inflection
                                       (re-matches #":.*?(\p{IsAlphabetic}+).*")
                                       second)})))})]
      (some->> elements
               (drop 2)
               process))))

(defn get-conjugation [conjugations-doc]
  (let [[header & lists] conjugations-doc
        extract-conjugate (fn [item]
                            (if (seq (hs/select (hs/tag "sup")
                                                item))
                              (let [[personne _ description] (:content item)]
                                (str personne "ᵉ" description))
                              (extract-string item)))
        process-conjugation (fn [[[form] conjugations]]
                              {:form (->> form
                                          (hs/select (hs/tag "b"))
                                          first
                                          :content
                                          first)
                               :descriptions
                               (map extract-conjugate conjugations)})]
    (if header
      {:infinitif (->> header
                       (hs/select (hs/tag "a"))
                       first
                       :content
                       first)
       :conjugations (->> lists
                          (partition-by (fn [list]
                                          (:tag list)))
                          (partition 2)
                          (map process-conjugation))}
      nil)))

(defn get-inflections [doc]
  (let [inflections-doc (first (hs/select (hs/class "inflectionsSection")
                                          doc))
        conjugations (some->> inflections-doc
                              (hs/select (hs/child (hs/class "otherWRD")
                                                   (hs/tag "dl")))
                              first
                              :content
                              (split-by-all
                               (fn [item]
                                 (= item "--------------")))
                              (map get-conjugation))
        declensions (->> inflections-doc
                         :content
                         (drop 1)
                         (drop-last (if (seq conjugations)
                                      3
                                      1))
                         (partition-by (fn [element]
                                         (= :br (:tag element))))
                         (partition 2)
                         (map first)
                         (map get-declension))]
    {:declensions declensions
     :conjugations conjugations}))

(defn get-word [doc]
  {:inflections (get-inflections doc)
   :defs (->> doc
              (hs/select (hs/class "WRD"))
              (map process-wrd))})

(defn print-definition [definition]
  (let [{{:keys [fr-wd fr-tooltip meanings]} :definition
         example-sentences :example-sentences} definition]
    (print (str ((comp term/green term/bold) fr-wd) " " (term/blue fr-tooltip)":\n"))

    (doseq [[index
             {meaning-in-fr :meaning-in-fr
              meaning-in-to :meaning-in-to
              to-wd :to-wd
              to-tooltip :to-tooltip}]
            (map-indexed vector meanings)]
      (print (term/grey (str "  " index ". ")))
      (when (not (str/blank? meaning-in-fr))
        (print (str (if meaning-in-fr
                      (str "[" (term/magenta meaning-in-fr) "] ")
                      "")
                    "\n"))
        (print (str "     ")))
      (when (not (str/blank? meaning-in-to))
        (print (str "(" meaning-in-to ") ")))
      (print (str (term/bold to-wd) " " #_(term/blue to-tooltip) "\n")))
    (when (seq example-sentences)
      (doseq [{italic? :italic
               text :text} example-sentences]
        (println " " ((comp (if italic? italic identity) term/yellow) text))))))



#_[{:title ""
    :definitions [{:definition {:fr-wd "" :fr-tooltip "" :meanings [{:meaning-in-fr "" :to-wd "" :to-tooltip ""}]}
                   :example-sentences [{:italic true :text ""}]}]}]
(defn print-definitions [defs]
  (doseq [[index
           {:keys [title definitions]}] (map-indexed vector defs)]
    (println (str ((comp term/bold term/red) title) ":"))
    (doseq [[index definition] (map-indexed vector definitions)]
      (print-definition definition)
      (when (not (= index (- (count definitions) 1)))
        (print "\n")))
    (when (not (= index (- (count defs) 1)))
      (println))))

(defn print-definition-short [definition]
  (let [{{:keys [fr-tooltip meanings]} :definition}
        definition]
    (str "  " (term/blue fr-tooltip)
         ": "
         (->> meanings
              (map (fn [{meaning-in-to :meaning-in-to
                         to-wd :to-wd}]
                     (str (if meaning-in-to
                            (str "(" meaning-in-to ") ")
                            "")
                          (term/bold to-wd))))
              dedupe
              (str/join "; ")))))

#_[{:title ""
    :definitions [{:definition {:fr-wd "" :fr-tooltip "" :meanings [{:meaning-in-fr "" :to-wd "" :to-tooltip ""}]}
                   :example-sentences [{:italic false :text ""}]}]}]
(defn print-definitions-short [defs]
  (letfn [(process-def [{:keys [title definitions]}]
            (->> definitions
                 (partition-by (fn [definition]
                                 (:fr-wd (:definition definition))))
                 (map (fn [items]
                        (str
                         ((comp term/bold term/green)
                          (-> items
                              first :definition :fr-wd))
                         ":\n"
                         (apply str
                                (->> items
                                     (map print-definition-short)
                                     dedupe
                                     (str/join "\n"))))))
                 (concat [((comp term/red term/bold) title)])
                 (str/join "\n")))]

    (->> defs
         (map process-def)
         (str/join "\n\n")
         println)))

;; for any words other than verbs
#_[{:base "" :word-class "" :declension ({:code "" :text ""})}]
(defn print-declensions [declensions]
  (doseq [{base :base
           word-class :word-class
           forms :declension
           annot :annot} declensions]
    (if annot
      (println annot)
      (do (print (str (term/bold "Inflections") " of " (term/bold base) "[" (term/blue word-class) "]: "))
          (->> forms
               (map (fn [{code :code text :text}]
                      (str (term/blue code) ":" text)))
               (str/join ", ")
               print)
          (print "\n")))))

#_[{:infinitif "" :conjugations [{:form "" :descriptions [""]}]}]
(defn print-conjugations [conjugations]
  (doseq [{:keys [infinitif conjugations]} conjugations]
    (println (str "Du verb " ((comp term/bold term/magenta) infinitif)))
    (->> conjugations
         (map (fn [{form :form
                    descriptions :descriptions}]
                (concat
                 [(str "  " (term/bold form) " est: ")]
                 (map #(str "    " %) descriptions))))
         flatten
         (str/join "\n")
         println)))

(defn print-word [word]
  (let [{{:keys [declensions conjugations]} :inflections
         defs :defs} word
        {:keys [all short no-inflections inflections-only]} @*options*]
    (when (and (seq declensions)
               (not no-inflections))
      (print-declensions declensions)
      (println))
    (when (and (seq conjugations)
               (not no-inflections))
      (print-conjugations conjugations)
      (println))
    (when (not inflections-only)
      (let [defs (if (:all @*options*)
                   defs
                   (filter (fn [{:keys [title definitions]}]
                             (= title "Principal Translations"))
                           defs))]
        (if (:short @*options*)
          (print-definitions-short defs)
          (print-definitions defs))))))

(defn main [query]
  (let [doc (-> (slurp (str "https://www.wordreference.com/fren/"
                            (java.net.URLEncoder/encode query)))
                str/trim
                (bootleg/convert-to :hickory-seq)
                second)]
    (->> (get-word doc)
         print-word)))

(defn display-usage []
  (->> ["Usage: espoir [options] words"
        "Options: "
        (:summary (cli/parse-opts [] cli-options))]
       (str/join \newline)
       println))

(defn -main [& args]
  (let [{:keys [options arguments summary errors]} (cli/parse-opts args cli-options)]
    (cond
      (seq errors) (do (->> errors
                            (str/join \newline)
                            println)
                       (println)
                       (display-usage))
      (:help options) (display-usage)
      :else (do (swap! *options* (constantly options))
                (binding [term/*disable-colors* (or (:no-color options)
                                                    (not
                                                     (nil? (System/getenv "NO_COLOR"))))]
                  (some->> (seq arguments)
                           (str/join " ")
                           main)))))
  (pods/unload-pod pod)
  (shutdown-agents))
