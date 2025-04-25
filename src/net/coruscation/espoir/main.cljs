(ns net.coruscation.espoir.main
  (:require
   [net.coruscation.espoir.preinit :as pi]
   [hickory.select :as hs]
   [hickory.core :as hk]
   [clojure.string :as str]
   [clojure.term.colors :as term]
   [clojure.tools.cli :as cli]
   [clojure.core.async :as a]
   [cljs.math :as math]
   ["process" :as process]
   [net.coruscation.espoir.db :as db]
   [net.coruscation.espoir.utils :as utils]
   [goog.object :as goog.object]))

(def axios (js/require "axios"))
(def xmldom (js/require "xmldom"))
(def ^:dynamic *interactive* (atom false))
#_(reset! *interactive* true)

(defn http-get [url]
  (a/go
    (let [chan (a/chan)]
      (-> (axios.get url)
          (.then (fn [resp]
                   (a/go (a/>! chan (js->clj resp :keywordize-keys true))))))
      (a/<! chan))))

(defn output [& args]
  (process/stdout.write (str/join " " (map str args))))

(def cli-options
  [["-h" "--help" "Show help messages" :default "[default]"]
   ["-g" "--conjugation" "Get conjugations of a verb"
    :default false]
   ["-s" "--short" "Show results in a more concise format, omitting some information."
    :default false]
   ["-a" "--all" "Show all translation sections (only principal translations are shown by default)"
    :default false]
   ["-N" "--no-inflections" "Don't show inflection sections"
    :default false]
   ["-f" "--fr-to-en" "Force French to English lookup" :default false]
   ["-e" "--en-to-fr" "Force English to French lookup" :default false]
   ["-I" "--inflections-only" "Only show inflection sections"
    :default false]
   ["-n" "--no-color" "Disable ascii color output, env NO_COLOR is also supported"
    :default false]])

(def ^:dynamic *options* (atom (cli/get-default-options cli-options)))
;; :fr or :en
(def ^:dynamic *lang* :fr)

(defn color-head [& args]
  (apply (if (= *lang* :fr)
           term/red
           term/blue)
         args))

(defn color-primary [& args]
  (apply (if (= *lang* :fr)
           term/green
           term/blue)
         args))

(defn color-secondary [& args]
  (apply (if (= *lang* :fr)
           term/magenta
           term/green)
         args))

(defn color-tip [& args]
  (apply (if (= *lang* :fr)
           term/blue
           term/magenta)
         args))

(defn color-sentence [& args]
  (apply (if (= *lang* :fr)
           term/yellow
           term/yellow)
         args))


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

(defn str-len-nocolor [str]
  (count (str/replace str #"\x1b\[[0-9;]*m(?:\x1b\[K)?" "")))

(defn string-repeat [n s]
  (apply str (repeat n s)))

(defn text-align-left [strs]
  (let [length (apply max (map str-len-nocolor strs))]
    (map (fn [s]
           (str s (string-repeat (- length (str-len-nocolor s)) " ")))
         strs)))

(defn text-align-right [strs]
  (let [length (apply max (map str-len-nocolor strs))]
    (map (fn [s]
           (str (string-repeat (- length (str-len-nocolor s)) " ")
                s))
         strs)))

(defn text-align-center [strs]
  (let [length (apply max (map str-len-nocolor strs))]
    (map (fn [s]
           (str (string-repeat (math/floor (/ (- length (str-len-nocolor s))
                                              2))
                               " ")
                s
                (string-repeat (math/ceil (/ (- length (str-len-nocolor s))
                                             2))
                               " ")))
         strs)))

(defn text-connect [strs-list padding]
  (let [padding (if (number? padding)
                  (string-repeat padding " ")
                  padding)]
    (apply map (fn [& strs]
                 (str/join padding strs))
           strs-list)))


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
                                            (str/starts-with?
                                             (if (= *lang* :fr)
                                               "fren:"
                                               "enfr:")))
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
                                       (re-matches #"(?u):.*?(\p{L}+).*")
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

(defn get-pron [doc]
  (when-let [pronDom (first (hs/select (hs/id "pronWR") doc))]
    (first (:content pronDom))))

(defn get-word [doc]
  {:pron (get-pron doc)
   :inflections (get-inflections doc)
   :defs (->> doc
              (hs/select (hs/class "WRD"))
              ((fn [wrds]
                 (if (seq wrds)
                   wrds
                   (throw (ex-info "Word not found" {:type :word-not-found})))))
              (map process-wrd))})

(defn print-pron [query pron]
  (output (str ((comp color-head term/bold)
                query)
               "  "
               ((comp color-tip term/italic)
                pron)
               "\n")))

(defn print-definition [definition]
  (let [{{:keys [fr-wd fr-tooltip meanings]} :definition
         example-sentences :example-sentences} definition]
    (output (str ((comp color-primary term/bold) fr-wd) " " (color-tip fr-tooltip) ":\n"))

    (doseq [[index
             {meaning-in-fr :meaning-in-fr
              meaning-in-to :meaning-in-to
              to-wd :to-wd
              to-tooltip :to-tooltip}]
            (map-indexed vector meanings)]
      (output (term/grey (str "  " index ". ")))
      (when (not (str/blank? meaning-in-fr))
        (output (str (if meaning-in-fr
                       (str "[" (color-secondary meaning-in-fr) "] ")
                       "")
                     "\n"))
        (output (str "     ")))
      (when (not (str/blank? meaning-in-to))
        (output (str "(" meaning-in-to ") ")))
      (output (str (term/bold to-wd) " " #_(term/blue to-tooltip) "\n")))
    (when (seq example-sentences)
      (doseq [{italic? :italic
               text :text} example-sentences]
        (println " " ((comp (if italic? term/italic identity) color-sentence) text))))))



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
        (output "\n")))
    (when (not (= index (- (count defs) 1)))
      (println))))

(defn print-definition-short [definition]
  (let [{{:keys [fr-tooltip meanings]} :definition}
        definition]
    (str "  " (color-tip fr-tooltip)
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
                         ((comp term/bold color-primary)
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
      (do (output (str (term/bold "Inflections") " of " (term/bold base) "[" (term/blue word-class) "]: "))
          (->> forms
               (map (fn [{code :code text :text}]
                      (str (term/blue code) ":" text)))
               (str/join ", ")
               output)
          (output "\n")))))

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

(defn print-word [query word]
  (let [{{:keys [declensions conjugations]} :inflections
         defs :defs
         pron :pron} word
        {:keys [all short no-inflections inflections-only]} @*options*]
    (when pron (print-pron query pron))
    (when (and (seq declensions)
               (not no-inflections)
               (not (= :en *lang*)))
      (print-declensions declensions)
      (println))
    (when (and (seq conjugations)
               (not no-inflections)
               (not (= :en *lang*)))
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

(defn keywordize-label [label]
  (-> label
      (str/replace "é" "e")
      (str/replace " " "-")
      str/lower-case
      keyword))

(def personne [:1s :2s :3s :1m :2m :3m])
(def non-finite-conjugation-name-map (into (array-map)
                                           [[:infinitif "infinitif"]
                                            [:present "participe présent"]
                                            [:passe "participe passé"]
                                            [:pronominale "forme pronominale"]]))
(def non-finite-conjugation (keys non-finite-conjugation-name-map))

(def finite-conjugation-name-map (into (array-map)
                                       [[:indicatif "indicatif"]
                                        [:composee "formes composées / compound tenses"]
                                        [:subjonctif "subjonctif"]
                                        [:conditionnel "conditionnel"]
                                        [:imperatif "impératif"]]))

(def finite-conjugation (keys finite-conjugation-name-map))

(defn get-conj-parse-item [dom]
  (letfn [(cal [dom]
            (cond (string? dom)
                  (if (= (str/trim dom)
                         "")
                    ""
                    dom)

                  (sequential? dom)
                  (map cal dom)

                  (= (:tag dom) :span)
                  [:antiquated (cal (:content dom))]

                  (= (:tag dom) :b)
                  [:irregular (cal (:content dom))]

                  (= (:tag dom) :i)
                  [:defect (cal (:content dom))]

                  (= (:tag dom) :a)
                  [:highlight (cal (:content dom))]))
          (normalize [data modifier]
            (cond (string? data)
                  (if (= modifier :default)
                    [data]
                    [[modifier data]])

                  (empty? data)
                  []

                  (keyword? (first data))
                  (normalize (drop 1 data) (first data))

                  :else
                  (apply concat (map (fn [item]
                                       (normalize item modifier))
                                     data))))]
    (into [] (filter
              (fn [item]
                (not (empty? item)))
              (normalize (cal (if (map? dom)
                                (:content dom)
                                dom))
                         :default)))))

(defn get-conj-non-finite [dom]
  (let [data-tuple (some->> (hs/select (hs/descendant (hs/id "conjtable")
                                                      (hs/tag "td"))
                                       dom)
                            second
                            :content
                            (split-by-all (fn [item]
                                            (and (map? item)
                                                 (= (:tag item)
                                                    :br))))
                            (map
                             (fn [item]
                               {:text (extract-string item :spacer "")
                                :richtext (map (fn [x]
                                                 (if (string? x)
                                                   (str/trim x)
                                                   x))
                                               (get-conj-parse-item item))})))]
    (if (nil? data-tuple)
      {}
      (->> data-tuple
           (map vector non-finite-conjugation)
           (into (array-map))))))


(defn get-conj-finite [dom]
  (some->> (hs/select (hs/class "aa")
                      dom)
           (map (fn [section]
                  (let [personne-labels (->> section
                                             (hs/select (hs/tag "table"))
                                             first
                                             (hs/select (hs/tag "th"))
                                             (drop 1)
                                             (map extract-string ))]
                    {:personne-labels personne-labels
                     :data
                     (->> (-> (hs/select (hs/tag "table")
                                         section))
                          (map (fn [col]
                                 (let [label (-> (hs/select (hs/tag "tr") col)
                                                 first
                                                 extract-string
                                                 str/trim)]
                                   [(keywordize-label label)
                                    {:label label
                                     :data
                                     (->> (hs/select (hs/tag "td")
                                                     col)
                                          (map (fn [item]
                                                 (let [text (extract-string item :spacer "")]
                                                   {:text text
                                                    :richtext (get-conj-parse-item item)})))
                                          (map vector personne)
                                          (into (array-map)))}])))
                          (into (array-map)))})))
           (map vector finite-conjugation)
           (into (array-map))))

(defn get-conj-conjugations [dom]
  (merge (get-conj-finite dom)
         (get-conj-non-finite dom)))

(defn get-conjugation-item-string [{:keys [text richtext]}]
  (str/join (map (fn [text]
                   (cond (string? text)
                         text

                         (= (first text)
                            :antiquated)
                         (term/white (second text))

                         (= (first text)
                            :irregular)
                         (term/blue (second text))

                         (= (first text)
                            :defect)
                         (term/magenta (second text))

                         (= (first text)
                            :highlight)
                         (term/blue (second text))))
                 richtext)))


(defn get-finite-conjugations-section-strs [section-name {:keys [personne-labels data]}]
  (let [data (vals data)]
    (let [str-list (text-connect
                    (concat [(text-align-center (concat
                                                 [(term/underline (term/green (->> data
                                                                                   first
                                                                                   :label)))]
                                                 (text-connect
                                                  [(text-align-right (map term/white personne-labels))
                                                   (text-align-left
                                                    (->> data
                                                         first
                                                         :data
                                                         vals
                                                         (map get-conjugation-item-string)))]
                                                  1)))]
                            (->> data
                                 (drop 1)
                                 (map (fn [col]
                                        (text-align-left
                                         (concat [(term/underline (term/green (:label col)))]
                                                 (->> col
                                                      :data
                                                      vals
                                                      (map get-conjugation-item-string))))))))
                    2)]
      (concat [(term/bold (term/red section-name))]
              str-list))))

(defn print-non-finite-conjugations [conjugations]
  (doseq [i (text-connect [(repeat (count non-finite-conjugation)
                                   (string-repeat 2 " " ))
                           (text-connect
                            [(text-align-right (map (fn [key]
                                                      (term/bold
                                                       (str (key non-finite-conjugation-name-map) ":")))
                                                    non-finite-conjugation))
                             (text-align-left (map (fn [key]
                                                     (get-conjugation-item-string (key conjugations)))
                                                   non-finite-conjugation))]
                            2)]
                          "")]
    (output (str i "\n"))))

(defn print-finite-conjugations [conjugations]
  (doseq [str (text-align-center
               (apply concat
                      (for [section-id finite-conjugation]
                        (concat (get-finite-conjugations-section-strs
                                 (section-id finite-conjugation-name-map)
                                 (section-id conjugations))
                                [""]))))]
    (print str)))

(def get-conj-by-query
  (db/persistent-async-memoize
   "get-conj-by-query"
   1
   (fn [query]
     (a/go
       (try (let [reps (a/<! (http-get
                              (str
                               "https://www.wordreference.com/conj/frverbs.aspx?v="
                               query)))
                  dom (-> (:data reps)
                          hk/parse
                          hk/as-hickory)]
              [(get-conj-conjugations dom) nil])
            (catch js/Error e
              [nil e]))))))

(defn get-conj [query]
  (a/go
    ;; TODO Maybe we can move this logic into the output function
    (binding [term/*disable-colors* (or (:no-color @*options*)
                                        (not
                                         (nil? (aget process/env "NO_COLOR"))))]
      (let [[conjugations err] (a/<! (get-conj-by-query query))]
        (when err (throw err))
        (if (empty? conjugations)
	  (println (term/red "Conjugations for word "
                             (term/bold (term/green query))
                             " can't be found"))
          (do (print-non-finite-conjugations conjugations)
              (output "\n")
              (print-finite-conjugations conjugations)))))))


(def get-word-by-query
  (db/persistent-async-memoize
   "get-word-by-query"
   1
   ;; TODO
   ;; add lang option to the param list
   (fn [query]
     (a/go
       (try
         (let [{:keys [fr-to-en en-to-fr] } @*options*
               reps (a/<! (http-get (str "https://www.wordreference.com/"
                                         (if en-to-fr
                                           "enfr/"
                                           "fren/")
                                         query)))
               doc (try (-> (:data reps)
                            hk/parse
                            hk/as-hickory)
                        (catch js/Error e
                          (throw (ex-info "Word not found" {:type :word-not-found}))))
               eng? (->> (aget (:request reps) "path")
                         (re-matches #"^/enfr/.*$")
                         boolean)]
           (when (or (and fr-to-en
                          eng?)
                     (and en-to-fr
                          (not eng?)))
             (throw (ex-info "Word not found" {:type :word-not-found})))
           [(merge (get-word doc) {:lang (if eng? :en :fr)}) nil])
         (catch js/Error e
           [nil e]))))))

(defn main [query]
  (a/go
    (let [[word err] (a/<! (get-word-by-query query))]
      (try (if err
             (case (:type (ex-data err))
               :word-not-found (do
                                 (println (term/red "Word "
                                                    ((comp term/bold term/green)
                                                     query)
                                                    " not found."))
                                 (when-not @*interactive*
                                   (process/exit 1)))
               [nil (throw err)])
             (binding [*lang* (:lang word)
                       term/*disable-colors* (or (:no-color @*options*)
                                                 (not
                                                  (nil? (aget process/env "NO_COLOR"))))]
               [(print-word query word) nil]))
           (catch js/Error e
             [nil e])))))

(defn display-usage []
  (->> ["Usage: espoir [options] words"
        "Options: "
        (:summary (cli/parse-opts [] cli-options))]
       (str/join \newline)
       println))

(defn ^:export DOMParserNoWarning [& rest]
  (xmldom.DOMParser. #js {:locator {}
                          :errorHandler #()
                          :error #()
                          :fatalError #(js/console.error %)}))

(set! js/DOMParser DOMParserNoWarning)
(defn -main [& args]
  (let [{:keys [options arguments summary errors]} (cli/parse-opts args cli-options)]
    (swap! *options* (constantly options))
    (cond
      (seq errors)
      (do (->> errors
               (str/join \newline)
               println)
          (println)
          (display-usage))

      (or (and (:help options)
               (not (= (:help options)
                       "[default]")))
          (empty? arguments))
      (display-usage)

      (:conjugation options)
      (some->> (seq arguments)
               (str/join " ")
               get-conj)

      :else
      (some->> (seq arguments)
               (str/join " ")
               main))))
