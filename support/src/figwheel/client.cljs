(ns figwheel.client
  (:require
   [goog.net.jsloader :as loader]
   [cljs.reader :refer [read-string]]
   [cljs.core.async :refer [put! chan <! map< close! timeout] :as async])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]]
   [figwheel.client :refer [defonce]]))

(defn log [{:keys [debug]} & args]
  (when debug
    (.log js/console (to-array args))))

;; this assumes no query string on url
(defn add-cache-buster [url]
  (str url "?rel=" (.getTime (js/Date.))))

(defn js-reload [{:keys [file namespace dependency-file] :as msg} callback]
  (when (or dependency-file
            ;; IMPORTANT make sure this file is currently provided
            (.isProvided_ js/goog namespace)) 
    (let [deferred (loader/load (add-cache-buster file))]
      (.addCallback deferred  (fn [] (apply callback [file]))))))

(defn reload-js-file [file-msg]
  (let [out (chan)]
    (js-reload file-msg (fn [url] (put! out url) (close! out)))
    out))

(defn reload-js-files [files-msg callback]
  (go
   (let [res (<! (async/into [] (async/merge (map reload-js-file (:files files-msg)))))]
     (when (not-empty res)
       (.log js/console "Figwheel: loaded files")
       (.log js/console (clj->js res))
       (<! (timeout 10)) ;; wait a beat before callback
       (apply callback [res])))))

(defn figwheel-closure-import-script [src]
  (if (.inHtmlDocument_ js/goog)
    (do
      #_(.log js/console "Figwheel: latently loading required file " src )
      (loader/load (add-cache-buster src))
      true)
    false))

(defn patch-goog-base []
  (set! (.-provide js/goog) (.-exportPath_ js/goog))
  (set! (.-CLOSURE_IMPORT_SCRIPT (.-global js/goog)) figwheel-closure-import-script))

(defn watch-and-reload* [{:keys [retry-count websocket-url jsload-callback] :as opts}]
    (.log js/console "Figwheel: trying to open cljs reload socket")  
    (let [socket (js/WebSocket. websocket-url)]
      (set! (.-onmessage socket) (fn [msg-str]
                                   (let [msg (read-string (.-data msg-str))]
                                     (when (= (:msg-name msg) :files-changed)
                                       (reload-js-files msg jsload-callback)))))
      (set! (.-onopen socket)  (fn [x]
                                 (patch-goog-base)
                                 (.log js/console "Figwheel: socket connection established")))
      (set! (.-onclose socket) (fn [x]
                                 (log opts "Figwheel: socket closed or failed to open")
                                 (when (> retry-count 0)
                                   (.setTimeout js/window
                                                (fn []
                                                  (watch-and-reload*
                                                   (assoc opts :retry-count (dec retry-count))))
                                                2000))))
      (set! (.-onerror socket) (fn [x] (log opts "Figwheel: socket error ")))))

(defn watch-and-reload [& {:keys [retry-count websocket-url jsload-callback] :as opts}]
  (defonce watch-and-reload-singleton
    (watch-and-reload* (merge { :retry-count 100 
                                :jsload-callback (fn [url]
                                                  (.dispatchEvent (.querySelector js/document "body")
                                                                  (js/CustomEvent. "figwheel.js-reload"
                                                                                   (js-obj "detail" url))))
                                :websocket-url "ws:localhost:3449/figwheel-ws" }
                              opts))))
