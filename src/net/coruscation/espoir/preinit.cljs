(ns net.coruscation.espoir.preinit
  (:require ["process" :as process]))

;; suppress the warning when requiring node:sqlite
(->
 (process/removeAllListeners "warning")
 (.on "warning" (fn [err]
                  (when (not (= (. err -name)
                                "ExperimentalWarning"))
                    (. js/console warn err)))))
