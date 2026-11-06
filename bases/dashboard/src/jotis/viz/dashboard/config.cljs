(ns jotis.viz.dashboard.config)

;; Where the browser finds the API server. Set at build time from the SPREAD_VIZ_API environment
;; variable (see shadow-cljs.edn :closure-defines); the default matches the server's default port.
(goog-define ^string api-base "http://localhost:3001")
