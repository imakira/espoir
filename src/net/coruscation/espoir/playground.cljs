(ns net.coruscation.espoir.playground
  (:require
   [goog.object :as go]
   [clojure.string :as str]
   [clojure.term.colors :as term]
   [clojure.tools.cli :as cli]
   [clojure.core.async :as a]
   [hickory.select :as hs]
   [hickory.core :as hk]
   [net.coruscation.espoir.main :as main]
   [net.coruscation.espoir.db :as db]
   [net.coruscation.espoir.utils :as utils]))

(def sample-page (atom nil))
(def query "poste")

(a/go
  (reset! sample-page
          (doto (a/<!
                 (main/http-get
                  (str "https://www.wordreference.com/fren/" query)))
            tap>)))

(def dom (-> @sample-page
             :data
             hk/parse
             hk/as-hickory))

(def word (main/get-word dom :fr))

(main/print-word query word)

;; This wouldn't work in cider as the output function send output content directly to stdout
;; (probably due to dynamic variables lost in async calls), cider couldn't catch it
;; for debug purpose, use tap> function and check the output on shadow-cljs 
;; (main/main "espoir")


(def sample-conj-page (atom nil))

(a/go
  (reset! sample-conj-page
          (a/<! (main/http-get
                 (str
                  "https://www.wordreference.com/conj/frverbs.aspx?v="
                  "esperer")))))

(a/go
  (reset! sample-conj-page
          (a/<! (main/http-get
                 (str
                  "https://www.wordreference.com/conj/frverbs.aspx?v="
                  "croître")))))

(def dom (-> @sample-conj-page
             :data
             hk/parse-dom-with-domparser
             hk/as-hickory))

(main/get-conj-non-finite dom)
(main/get-conj-finite dom)

(main/print-finite-conjugations (main/get-conj-conjugations dom))
(main/print-non-finite-conjugations (main/get-conj-conjugations dom))
