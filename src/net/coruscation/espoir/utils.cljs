(ns net.coruscation.espoir.utils
  (:require [clojure.string :as str]
            [goog.object :as goog.object]))

(defn lstr [& strs]
  (str/join "\n" strs))

(defn recur-obj->clj
  [obj & {:keys [keywordize-keys]}]
  (cond
    (js/Array.isArray obj)
    (into [] (map (fn [item]
                    (recur-obj->clj item :keywordize-keys keywordize-keys))
                  obj))

    (goog.isObject obj)
    (into {} (-> (fn [result key]
                   (let [v (goog.object/get obj key)]
                     (cond (= "function" (goog/typeOf v))
                           result

                           :else
                           (assoc result
                                  (if keywordize-keys
                                    (keyword key)
                                    key)
                                  (recur-obj->clj v :keywordize-keys keywordize-keys)))))
                 (reduce {} (goog.object/getKeys obj))))
    :else
    obj))
