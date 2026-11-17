(ns intermine-registry.logos
  "The registry's own copy of each mine's logo.

  The refresh fetches a mine's logo (see refresh/refresh-logo) and stores it
  under REGISTRY_CACHE_DIR/logos/<namespace>.<ext>; the index of what is
  stored (file, content type, where it came from, when, size, SHA-1) is in
  the cache EDN (data/state :logos), and the pages and the API point at
  GET /instances/{namespace}/logo, so the home page does not depend on the
  mines' own hosts. This namespace checks, stores and reads those files."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [intermine-registry.data :as data])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardCopyOption]
           [java.security MessageDigest]))

(def max-bytes
  "A logo larger than this is not cached."
  (* 512 1024))

(defn logos-dir ^File [cache-dir] (io/file cache-dir "logos"))

(defn media-type
  "The media type of a Content-Type header value: lower case, no parameters;
  nil for a blank header."
  [header]
  (when-not (str/blank? header)
    (str/lower-case (str/trim (first (str/split header #";" 2))))))

(defn- head-string [^bytes body n]
  (String. body 0 (int (min n (alength body))) StandardCharsets/ISO_8859_1))

(defn check-image
  "nil if body (bytes) with the Content-Type header value is an acceptable
  logo, else the reason it is not (a short string). Acceptable: one of the
  data/logo-content-types, not empty, at most max-bytes, and not an HTML page
  that a mine serves for every path under an image content type."
  [content-type ^bytes body]
  (let [ct (media-type content-type)]
    (cond
      (nil? ct) "no content type"
      (not (contains? data/logo-content-types ct)) (str "not an image (" ct ")")
      (zero? (alength body)) "empty answer"
      (> (alength body) max-bytes) (str "too large (over " (quot max-bytes 1024) " KB)")
      (re-find #"(?i)^\s*(<!doctype\s+html|<html[\s>]|<head[\s>]|<body[\s>])" (head-string body 512))
      "an HTML page, not an image"
      (and (= ct "image/svg+xml") (not (re-find #"(?i)<svg[\s>]" (head-string body 4096))))
      "not an SVG document"
      :else nil)))

(defn- sha1-hex [^bytes body]
  (let [d (.digest (MessageDigest/getInstance "SHA-1") body)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(defn- move-into-place! [^File tmp ^File target]
  (Files/move (.toPath tmp) (.toPath target)
              (into-array [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE])))

(defn store!
  "Writes body to cache-dir/logos/<namespace>.<ext> (a temporary file, then a
  rename) and returns the index entry. previous is the namespace's earlier
  index entry: its file is deleted when the extension changed."
  [cache-dir namespace {:keys [content-type body source]} previous now]
  (let [ct (media-type content-type)
        file-name (str namespace "." (get data/logo-content-types ct))
        dir (logos-dir cache-dir)
        target (io/file dir file-name)
        tmp (io/file dir (str file-name ".tmp"))]
    (.mkdirs dir)
    (with-open [out (io/output-stream tmp)] (.write out ^bytes body))
    (move-into-place! tmp target)
    (when-let [old (:file previous)]
      (when (and (not= old file-name) (data/valid-logo-entry? previous))
        (.delete (io/file dir old))))
    {:file file-name :content-type ct :source source :fetched now
     :bytes (alength ^bytes body) :sha1 (sha1-hex body)}))

(defn read-logo
  "The bytes of the stored logo of an index entry, or nil when the entry is
  not valid or the file is gone."
  [cache-dir entry]
  (when (and cache-dir (data/valid-logo-entry? entry))
    (let [f (io/file (logos-dir cache-dir) (:file entry))]
      (when (.isFile f) (Files/readAllBytes (.toPath f))))))

(defn logo-path
  "The registry path of a mine's cached logo."
  [namespace]
  (str "/instances/" namespace "/logo"))

(defn cached?
  "Does the registry hold a logo for the namespace?"
  [namespace]
  (contains? (:logos @data/state) namespace))
