(ns intermine-registry.logos
  "The registry's own copy of each mine's logo.

  The refresh fetches a mine's logo (see refresh/refresh-logo) and stores it
  under REGISTRY_CACHE_DIR/logos/<namespace>.<ext>; the index of what is
  stored (file, content type, where it came from, when, size, SHA-1) is in
  the cache EDN (data/state :logos), and the pages and the API point at
  GET /instances/{namespace}/logo, so the home page does not depend on the
  mines' own hosts. This namespace checks, stores and reads those files.

  Each entry also has a :logo-tile, \"light\" or \"dark\": the shade of the
  tile the pages put behind the logo, chosen from the logo's mean luminance
  when it is stored (logo-tile), so that a white logo is not lost on a white
  tile. Entries from before the tile was kept get one at start-up
  (add-missing-tiles!)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [intermine-registry.data :as data])
  (:import [java.awt.image BufferedImage]
           [java.io ByteArrayInputStream File]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardCopyOption]
           [java.security MessageDigest]
           [javax.imageio ImageIO ImageReadParam ImageReader]
           [javax.imageio.stream MemoryCacheImageInputStream]))

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

;; ---------------------------------------------------------------- tile shade

(def light-logo-luminance
  "A logo whose mean luminance (0-255, see image-luminance) is at least this
  is a light logo and gets the dark tile; any other logo gets the light tile.
  Measured on the mines' logos of November 2026: AquaMine (a white trident,
  240) and ThaleMine (a plant on a white disc, 208) are light; FlyMine and
  MouseMine (147, a pale grey mouse that still reads well on white) and every
  other logo (13 to 139) are not."
  180)

(def ^:private visible-alpha
  "Pixels with an alpha at or below this are ignored (anti-aliased fringes,
  shadows)."
  32)

(def ^:private max-sampled-side
  "A larger image is subsampled to about this many pixels a side when it is
  decoded, so a small file that decodes to a huge image costs little."
  256)

(defn image-luminance
  "The mean relative luminance, 0.2126 R + 0.7152 G + 0.0722 B (0-255, sRGB
  values), of the visible pixels of img, weighted by their alpha; nil when no
  pixel has an alpha above visible-alpha."
  [^BufferedImage img]
  (let [w (.getWidth img) h (.getHeight img)]
    (loop [y 0 x 0 sum 0.0 weight 0.0]
      (cond
        (>= y h) (when (pos? weight) (/ sum weight))
        (>= x w) (recur (inc y) 0 sum weight)
        :else (let [argb (.getRGB img (int x) (int y))
                    a (bit-and (unsigned-bit-shift-right argb 24) 0xff)]
                (if (<= a visible-alpha)
                  (recur y (inc x) sum weight)
                  (let [r (bit-and (bit-shift-right argb 16) 0xff)
                        g (bit-and (bit-shift-right argb 8) 0xff)
                        b (bit-and argb 0xff)]
                    (recur y (inc x)
                           (+ sum (* a (+ (* 0.2126 r) (* 0.7152 g) (* 0.0722 b))))
                           (+ weight a)))))))))

(defn shade-for-luminance
  "\"dark\" (the tile) for a light logo, \"light\" otherwise, also when the
  luminance is unknown (nil)."
  [luminance]
  (if (and luminance (>= luminance light-logo-luminance)) "dark" "light"))

(defn image-tile
  "The tile shade for a decoded image."
  [^BufferedImage img]
  (shade-for-luminance (image-luminance img)))

(defn decode-image
  "The image in body (bytes) as a BufferedImage, subsampled to about
  max-sampled-side pixels a side, or nil when javax.imageio cannot read it
  (it reads PNG, JPEG and GIF; not ICO, SVG or WebP) or it is broken."
  ^BufferedImage [^bytes body]
  (try
    (with-open [in (MemoryCacheImageInputStream. (ByteArrayInputStream. body))]
      (let [readers (ImageIO/getImageReaders in)]
        (when (.hasNext readers)
          (let [^ImageReader reader (.next readers)]
            (try
              (.setInput reader in true true)
              (let [w (.getWidth reader 0) h (.getHeight reader 0)
                    step (max 1 (long (Math/ceil (/ (double (max w h)) max-sampled-side))))
                    ^ImageReadParam param (doto (.getDefaultReadParam reader)
                                            (.setSourceSubsampling (int step) (int step) 0 0))]
                (.read reader 0 param))
              (finally (.dispose reader)))))))
    (catch Throwable _ nil)))

(defn logo-tile
  "The tile shade (\"light\" or \"dark\") for a logo's bytes; \"light\" when
  the image cannot be decoded."
  [^bytes body]
  (shade-for-luminance (some-> (decode-image body) image-luminance)))

(def tiles #{"light" "dark"})

;; ---------------------------------------------------------------- files

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
     :bytes (alength ^bytes body) :sha1 (sha1-hex body) :logo-tile (logo-tile body)}))

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

(defn tile
  "The tile shade (\"light\" or \"dark\") of the namespace's cached logo;
  nil when the registry holds none. An entry without one (not upgraded yet)
  counts as light."
  [namespace]
  (when-let [entry (get-in @data/state [:logos namespace])]
    (if (contains? tiles (:logo-tile entry)) (:logo-tile entry) "light")))

(defn add-missing-tiles
  "The logo index with a :logo-tile computed for every entry that has none
  (an index written before the tile was kept), from the stored file. An entry
  whose file cannot be read is left as it is."
  [cache-dir index]
  (into {}
        (map (fn [[ns entry]]
               [ns (if (contains? tiles (:logo-tile entry))
                     entry
                     (if-let [body (try (read-logo cache-dir entry) (catch java.io.IOException _ nil))]
                       (assoc entry :logo-tile (logo-tile body))
                       entry))]))
        index))

(defn add-missing-tiles!
  "At start-up: gives the logos in the state that have no tile shade one
  (add-missing-tiles) and, when any changed, writes the cache, so an existing
  cache upgrades itself. Returns the namespaces that got a tile."
  [cache-dir]
  (when cache-dir
    (let [before (:logos @data/state)
          after (add-missing-tiles cache-dir before)
          changed (sort (filter #(not= (get before %) (get after %)) (keys after)))]
      (when (seq changed)
        (swap! data/state update :logos
               (fn [current] (merge current (select-keys after changed))))
        (data/write-cache! cache-dir @data/state)
        (log/info "Tile shade computed for the cached logos of" (str/join ", " changed)))
      (vec changed))))
