(ns http-ticket-checker-clj.handler
  (:use http-ticket-checker-clj.configuration)
  (:use compojure.core)
  (:use [ring.util.response :only (file-response header content-type)])
  (:require [compojure.handler :as handler]
            [compojure.route :as route])
  (:require [clojurewerkz.spyglass.client :as m])
  (:require [clojure.data.json :as json]))


(def ticket-store-atom
  (atom nil))

(defn create-ticket-store []
  (m/bin-connection "alhena:11211"))

(defn get-ticket-store []
  (deref ticket-store-atom))

(defn set-ticket-store [ticket-store]
  (swap! ticket-store-atom
    (fn [_] ticket-store)))


(defn get-ticket [ticket_id]
  (m/get (get-ticket-store) ticket_id))

(defn parse-ticket [raw_ticket]
  (if raw_ticket
    (let [ticket (json/read-str raw_ticket)]
      (let [resource_ids (ticket "resources")
            presentationType (ticket "type")
            userIdentifier (ticket "userIdentifier")]
        (if (and resource_ids type userIdentifier)
          {:resource_ids resource_ids :presentationType presentationType :userIdentifier userIdentifier}
          nil)))
    nil))

(defn get-resource-id [resource]
  (first
    (clojure.string/split
      (last
        (clojure.string/split
          resource
          #"/"))
      #"\.")))

(defn shorten-resource-id [resource_id]
  (last
    (clojure.string/split resource_id #":")))


(defn valid-ticket? [resource ticket_id]
  (let [ticket (get-ticket ticket_id)
        resource_id (get-resource-id resource)]
    (if (and ticket resource_id)
      (let [parsed_ticket (parse-ticket ticket)]
        (if parsed_ticket
          (and
            (= ((get-config) :presentation_type) (parsed_ticket :presentationType))
            (not
              (not
                (some #{resource_id} (list
                                       (last
                                         (map shorten-resource-id (parsed_ticket :resource_ids))))))))
          false))
      false)))


(defn init []
  (do
    (set-ticket-store (create-ticket-store))
    (set-config (load-config))))

(defn destroy []
  (do
    (m/shutdown (get-ticket-store))
    (set-ticket-store nil)))


(defn handle-good-ticket [resource]
  (header
    (file-response resource {:root ((get-config) :file_dir)})
    "Cache-Control"
    "no-cache"))

(defn handle-bad-ticket []
  (content-type
    {:status 403
     :body "ticket invalid"}
    "text/plain"))


(defroutes app-routes
  (GET "/ticket/:id" [id] (get-ticket id))
  (GET "/reconnect" [] (str (init)))

  (GET ["/:resource" :resource #"[^?]+"] [resource & params]
    (if (re-find #"\.\." resource)
      (handle-bad-ticket)
      (if
        (valid-ticket? resource (params :ticket))
        (handle-good-ticket resource)
        (handle-bad-ticket))))

  (route/not-found "Not Found"))


(def app
  (handler/site app-routes))
