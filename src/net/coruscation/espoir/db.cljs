(ns net.coruscation.espoir.db
  (:require ["node:sqlite" :as sql]
            [clojure.string :as str]
            ["node:fs" :as fs]
            [net.coruscation.espoir.utils :as utils
             :refer [lstr]]
            [clojure.core.async :as async]
            [clojure.edn :as edn]))

(defn sql-escape [str]
  (str/replace str "'" "''"))

(defn open-db [& [path options]]
  (sql/DatabaseSync. (or path ":memory:")
                     (clj->js (or options {}))))

(defn db-exec [^js db sql]
  (.exec db sql))

(defn db-prepare [^js db sql]
  (.prepare db sql))

(defn stat-expanded-sql [^js stat]
  (.expandedSQL stat))

(defn stat-get [^js stat & [named-parameters anonymous-parameters :as args]]
  (utils/recur-obj->clj
   (.apply ^js (.-get stat) stat (clj->js args))
   :keywordize-keys true))

(defn stat-iterate [^js stat & [named-parameters anonymous-parameters :as args]]
  (utils/recur-obj->clj (.apply ^js (.-iterate stat) stat (clj->js args)) :keywordize-keys true))

(defn stat-run [^js stat & [named-parameters anonymous-parameters :as args]]
  (utils/recur-obj->clj (.apply (.-run stat) stat (clj->js args)) :keywordize-keys true))

(defn stat-all [^js stat & [named-parameters anonymous-parameters :as args]]
  (utils/recur-obj->clj (.apply ^js (.-all stat) stat (clj->js args)) :keywordize-keys true))


(defn close-db [db])

(defn espoir-db-version [db]
  (db-exec db (lstr "create table if not exists version (version integer)"))
  (some-> db
          (db-prepare "select version from version")
          stat-get
          :version))

(defn espoir-db-initialize [db version]
  (db-exec db (str "begin transaction;\n"
                   "drop table if exists version;\n"
                   "drop table if exists memoize_version;"
                   "drop table if exists memoize;"
                   "create table version (version integer);\n"
                   "insert into version (version) values (" version ");\n"

                   "create table memoize_version (id text primary key, version integer);\n"

                   "create table memoize (\n"
                   "	id text,\n"
                   " 	params text,\n"
                   "    result text\n"
                   ");\n"
                   "create index memoize_id_index on memoize(id);\n"
                   "create index memoize_params_index on memoize(id);"

                   "commit;\n")))

(defn espoir-db-check-or-reinit [db version]
  (let [old-version (espoir-db-version db)]
    (when (not (= old-version version))
      (espoir-db-initialize db version))))

(def data-dir (str (or js/process.env.XDG_STATE_HOME (str js/process.env.HOME "/.local/state"))
                   "/net.coruscation.espoir"))

(when (not (fs/existsSync data-dir))
  (fs/mkdirSync data-dir #js {:recursive true}
                (fn [err]
                  (when err
                    (throw err)))))

(def ^:dynamic *db* (open-db (str data-dir "/data")))
(def ^:dynamic *db-version* 6)

(espoir-db-check-or-reinit *db* *db-version*)

(defn memoize-initialize [id version]
  (let [old-version (-> *db*
                        (db-prepare (str "select version from memoize_version where id=$id"))
                        (stat-get {:$id id})
                        :version)]
    (when (or (nil? old-version)
              (not (= old-version version)))
      (try
        (db-exec *db* "begin transaction;")
        (-> *db*
            (db-prepare  "delete from memoize where id=$id;")
            (stat-run {:$id id }))
        (-> *db*
            (db-prepare "delete from memoize_version where id=$id;")
            (stat-run {:$id id}))
        (-> *db*
            (db-prepare "insert into memoize_version(id, version) values($id,$version);")
            (stat-run {:$id id :$version version}))
        (db-exec *db* "commit;")
        (catch js/Error e
          (try
            (db-exec *db* "rollback;")
            (finally
              (throw e))))))))

(defn deserialize-obj [str]
  (edn/read-string str))

(defn serialize-obj [obj]
  (pr-str obj))

(defn memoize-get-saved-result [id params]
  (-> *db*
      (db-prepare (str "select result from memoize where id=$id and params=$params;"))
      (stat-get {:$id id :$params (serialize-obj params)})
      :result
      deserialize-obj))

(defn memoize-save-result [id params result]
  (-> *db*
      (db-prepare (str "insert into memoize(id,params,result) values($id,$params,$result);"))
      (stat-run {:$id id :$params (serialize-obj params) :$result (serialize-obj result)})))

(defn persistent-memoize [id version func]
  (memoize-initialize id version) 
  (fn [& params]
    (let [saved-result (memoize-get-saved-result id params)]
      (if (not (nil? saved-result))
        saved-result
        (let [result (apply func params)]
          (memoize-save-result id params result)
          result)))))

(defn persistent-async-memoize [id version func]
  (memoize-initialize id version)
  (fn [& params]
    (async/go
      (let [saved-result (memoize-get-saved-result id params)]
        (if (not (nil? saved-result))
          [saved-result nil]
          (let [[result error] (async/<! (apply func params))]
            (cond
              error
              [result error]

              :else
              (do (memoize-save-result id params result)
                  [result nil]))))))))

