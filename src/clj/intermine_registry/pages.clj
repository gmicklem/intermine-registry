(ns intermine-registry.pages
  "Server-rendered pages (Hiccup 1; text is escaped with h, attributes are
  escaped by Hiccup)."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hiccup.core :refer [h]]
            [hiccup.page :refer [html5]]
            [intermine-registry.api :as api]
            [intermine-registry.data :as data]
            [intermine-registry.logos :as logos]
            [intermine-registry.refresh :as refresh]))

(defn- safe-url
  "url if it is http(s), else nil (mine-supplied values end up in src/href)."
  [url]
  (when (and (string? url) (re-matches #"https?://[^\s\"'<>]+" url)) url))

(defn- css-colour [c]
  (when (and (string? c) (re-matches #"#[0-9A-Fa-f]{3,8}|[a-zA-Z]{3,20}|rgba?\([0-9., %]+\)" c)) c))

(def theme-key
  "The localStorage key of the visitor's theme choice (\"light\" or \"dark\";
  absent for System)."
  "registry-theme")

(def ^:private theme-head-script
  ;; in <head>, before the stylesheet: applies a stored choice before the
  ;; first paint, so a dark page never flashes light (or the other way)
  (str "(function () { try { var t = localStorage.getItem('" theme-key "');"
       " if (t === 'light' || t === 'dark') document.documentElement.setAttribute('data-theme', t); }"
       " catch (e) {} })();"))

(def ^:private theme-switch-script
  ;; shows the switch (hidden without JavaScript, when the page follows the
  ;; OS) and stores the choice
  (str "
(function () {
  var box = document.getElementById('theme-switch'), root = document.documentElement;
  if (!box) return;
  var current = root.getAttribute('data-theme') || 'system';
  box.querySelectorAll('input').forEach(function (input) {
    input.checked = input.value === current;
    input.addEventListener('change', function () {
      if (!input.checked) return;
      try {
        if (input.value === 'system') localStorage.removeItem('" theme-key "');
        else localStorage.setItem('" theme-key "', input.value);
      } catch (e) {}
      if (input.value === 'system') root.removeAttribute('data-theme');
      else root.setAttribute('data-theme', input.value);
    });
  });
  box.hidden = false;
})();"))

(defn- theme-switch
  "System / Light / Dark: a radio group (arrow keys move between the
  choices). Hidden until the script shows it: without JavaScript the pages
  follow the OS."
  []
  [:fieldset#theme-switch.theme-switch {:hidden true}
   [:legend.sr "Theme"]
   (for [[v label] [["system" "System"] ["light" "Light"] ["dark" "Dark"]]]
     (list [:input {:type "radio" :name "theme" :id (str "theme-" v) :value v :checked (= v "system")}]
           [:label {:for (str "theme-" v)} label]))])

(defn- layout [{:keys [title active]} & body]
  (html5 {:lang "en"}
         [:head
          [:meta {:charset "utf-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
          [:title (h (if title (str title " - InterMine Registry") "InterMine Registry"))]
          [:meta {:name "description" :content "The InterMine Registry: the list of InterMine data warehouses (mines) and their web services."}]
          [:script theme-head-script]
          [:link {:rel "stylesheet" :href "/css/registry.css"}]]
         [:body
          [:header.site
           [:div.wrap
            [:a.brand {:href "/"} [:span.mark "IM"] "InterMine Registry"]
            [:div.header-end
             [:nav
              (for [[href label k] [["/" "Mines" :home] ["/map" "Map" :map] ["/docs" "API" :docs] ["/status" "Status" :status]]]
                [:a {:href href :class (when (= k active) "active")} label])]
             (theme-switch)]]]
          [:main.wrap body]
          [:footer.site
           [:div.wrap
            [:p "InterMine Registry; API designed in Leonardo Kuffo's Google Summer of Code 2017 project; "
             "re-implemented 2026; LGPL-2.1; "
             [:a {:href api/repository-url} "source"] "."]
            [:p "To add a mine or correct its details, open a pull request against "
             [:a {:href (str api/repository-url "/blob/dev/resources/instances.edn")} [:code "resources/instances.edn"]] "."]]]
          [:script theme-switch-script]]))

(def maintainer-notes-url
  "The README section for mine maintainers."
  (str api/repository-url "/blob/dev/README.md#for-mine-maintainers-letting-the-registry-check-your-mine"))

(defn- date-part [iso] (when iso (str/replace (str iso) #"T(\d\d:\d\d).*" " $1 UTC")))

(defn- day-part [iso] (when iso (subs (str iso) 0 (min 10 (count (str iso))))))

(defn- check-failed? [inst] (= "failed" (get-in inst [:last_check :outcome])))

(defn- unreachable-hours [opts]
  (or (:unreachable-after-hours opts) refresh/default-unreachable-after-hours))

(defn- check-sentence
  "A plain-language sentence about the last check of the instance."
  [inst]
  (let [{:keys [reason since]} (:last_check inst)]
    (cond
      (= "Unreachable" (:status inst))
      (str "Unreachable since " (day-part since) (when reason (str " (" reason ")")))

      (check-failed? inst)
      (str (or (:status inst) "unknown") "; check failed (" reason "), retrying")

      (= "Not checked yet" (:status inst)) "Not checked yet"
      :else (str (or (:status inst) "unknown")))))

(defn- status-dot [inst]
  (let [status (or (:status inst) "unknown")
        css (case status "Running" "up" "Unreachable" "unreachable" "unknown")
        text (check-sentence inst)]
    [:span {:class (str "dot " css) :title (h text)}
     [:span.sr (h text)]]))

(defn- bot-reason? [reason] (contains? #{"bot-protection page" "html instead of json"} reason))

(defn- tile-class
  "The CSS class of the tile shade for the namespace's logo (its override or
  cached copy; tile-light or tile-dark, see logos/logo-tile); tile-light
  without one."
  [namespace]
  (str "tile-" (or (logos/tile namespace) "light")))

(defn- logo
  "The registry's logo for the mine (/instances/{namespace}/logo: its logo
  override, else the copy of its own logo) over a neutral mark (its
  initial), which shows when the registry holds no logo yet or it does not
  load. The pages never point at the mine's own host. The tile is dark for a
  light logo (logos/logo-tile), in either page theme."
  [inst]
  [:span {:class (str "logo " (tile-class (:namespace inst))) :aria-hidden "true"}
   [:span.placeholder (h (subs (str (:name inst) "?") 0 1))]
   (when (logos/has-logo? (:namespace inst))
     [:img {:src (logos/logo-path (:namespace inst)) :alt "" :loading "lazy" :onerror "this.remove()"}])])

(def ^:private organisms-shown 6)

(defn- organisms-cell [organisms]
  (let [n (count organisms)]
    (if (<= n (inc organisms-shown))
      (h (str/join ", " organisms))
      [:span {:title (h (str/join ", " organisms))}
       (h (str/join ", " (take organisms-shown organisms)))
       [:span.muted (str " and " (- n organisms-shown) " more")]])))

(defn- mines-param [params] (get params "mines"))

(defn home
  "The mines table. With :galaxy {:mode :to-galaxy :url GALAXY_URL} (the
  /im-to-galaxy page Galaxy's InterMine data source opens) each mine links
  to {mine}/begin.do?GALAXY_URL=...; with {:mode :from-galaxy :url URL}
  (/galaxy-to-im) each mine gets a form posting the identifiers in the
  Galaxy file at URL to {mine}/portal.do, as the Node app's pages did."
  [params _opts & [galaxy]]
  (let [mines (mines-param params)
        galaxy-url (safe-url (:url galaxy))
        to-galaxy? (and galaxy-url (= :to-galaxy (:mode galaxy)))
        from-galaxy? (and galaxy-url (= :from-galaxy (:mode galaxy)))
        all? (some? mines)
        insts (cond->> (data/instances)
                (not all?) (filter :isProduction)
                true (sort-by (comp str/lower-case :name)))]
    (layout {:active :home}
            [:section.intro
             [:h1 "InterMine instances"]
             [:p "The registry lists the public "
              [:a {:href "https://intermine.org"} "InterMine"]
              " data warehouses (mines). Client applications such as BlueGenes and the InterMine "
              "client libraries read it from " [:a {:href "/docs"} "the API"] " at "
              [:code "/service/instances"] "."]
             (when to-galaxy?
               [:p.notice "Choose a mine to query; the results can be sent back to Galaxy at " [:code (h galaxy-url)] "."])
             (when from-galaxy?
               [:p.notice "Choose the mine to send the identifiers from Galaxy to."])]
            [:div.toolbar
             [:label.filter [:span.sr "Filter"]
              [:input#filter {:type "search" :placeholder "Filter by name, organism, maintainer..."
                              :autocomplete "off"}]]
             [:span.count [:span#shown (count insts)] " of " (count insts) " mines"]
             (if all?
               [:a {:href "/"} "show production mines only"]
               [:a {:href "/?mines=all"} "show test mines"])]
            [:div.table-scroll
             [:table#mines.mines
              [:thead
               [:tr [:th {:scope "col"} [:span.sr "Status"]] [:th {:scope "col"} "Mine"] [:th {:scope "col"} "Namespace"]
                [:th {:scope "col"} "Organisms"] [:th {:scope "col"} "InterMine"] [:th {:scope "col"} "Release"]
                [:th {:scope "col"} "Maintainer"] [:th {:scope "col"} "Last checked"]
                (when from-galaxy? [:th {:scope "col"} "From Galaxy"])]]
              [:tbody
               (for [inst insts]
                 [:tr {:data-search (h (str/lower-case (str/join " " (concat [(:name inst) (:namespace inst) (:maintainerOrgName inst) (:description inst)]
                                                                             (:organisms inst)))))}
                  [:td (status-dot inst)]
                  [:td [:div.mine (logo inst)
                        [:span
                         [:a.name {:href (if (and to-galaxy? (safe-url (:url inst)))
                                           (str (:url inst) "/begin.do?GALAXY_URL=" (java.net.URLEncoder/encode ^String galaxy-url "UTF-8"))
                                           (safe-url (:url inst)))}
                          (h (:name inst))]
                         (when-not (:isProduction inst) [:span.tag "test"])
                         (when (or (= "Unreachable" (:status inst)) (check-failed? inst))
                           [:span.unreachable-note (h (check-sentence inst))])]]]
                  [:td [:a {:href (str "/instances/" (h (:namespace inst)))} [:code (h (:namespace inst))]]]
                  [:td.organisms (organisms-cell (:organisms inst))]
                  [:td.nowrap (h (:intermine_version inst))]
                  [:td (h (:release_version inst))]
                  [:td (if-let [u (safe-url (:maintainerUrl inst))]
                         [:a {:href u} (h (:maintainerOrgName inst))]
                         (h (:maintainerOrgName inst)))]
                  [:td.when (h (date-part (:last_time_updated inst)))]
                  (when (and from-galaxy? (safe-url (:url inst)))
                    [:td [:form.galaxy {:action (str (:url inst) "/portal.do") :method "post"}
                          [:input {:type "hidden" :name "externalids" :value ""}]
                          [:input {:type "hidden" :name "class" :value ""}]
                          [:button {:type "submit" :disabled true} (h (str "Send to " (:name inst)))]]])])]]]
            [:p#none.empty {:hidden true} "No mine matches the filter."]
            (when from-galaxy?
              ;; the Galaxy file: one identifier per line, tab-separated
              ;; identifierType, identifier, organism (the Node app's format)
              [:script (str "var galaxyData = " (json/generate-string galaxy-url) ";\n" "
fetch(galaxyData).then(function (r) { return r.text(); }).then(function (text) {
  var rows = text.split('\\n').filter(function (l) { return l.trim() !== ''; });
  var type = rows.length ? rows[0].split('\\t')[0] : '';
  var ids = rows.map(function (l) { return l.split('\\t')[1]; }).filter(Boolean).join(',');
  document.querySelectorAll('form.galaxy').forEach(function (f) {
    f.elements.externalids.value = ids; f.elements['class'].value = type; f.querySelector('button').disabled = false;
  });
}).catch(function () { document.getElementById('none').hidden = false;
  document.getElementById('none').textContent = 'Could not read the identifiers from Galaxy.'; });")])
            [:script "
(function () {
  var input = document.getElementById('filter'), rows = document.querySelectorAll('#mines tbody tr'),
      shown = document.getElementById('shown'), none = document.getElementById('none');
  input.addEventListener('input', function () {
    var q = input.value.trim().toLowerCase(), n = 0;
    rows.forEach(function (r) { var hit = r.getAttribute('data-search').indexOf(q) >= 0; r.hidden = !hit; if (hit) n++; });
    shown.textContent = n; none.hidden = n > 0;
  });
})();"])))

;; The world map (/map): an inline SVG, 720 x 360 units, land from
;; resources/public/world-110m-land.path (scripts/make-world-path.py), a dot
;; per mine with coordinates. Mines at (or near) one place are fanned out in a
;; small ring and get one label listing their names.

(def map-width 720)
(def map-height 360)

(defn project
  "[x y] on the /map canvas of latitude lat and longitude lon (degrees): the
  equirectangular projection of scripts/make-world-path.py, x = (lon + 180) *
  2, y = (90 - lat) * 2."
  [lat lon]
  [(* 2.0 (+ (double lon) 180.0)) (* 2.0 (- 90.0 (double lat)))])

(defn coordinates
  "[lat lon] (doubles) of the mine's :location (strings in instances.edn),
  nil when blank or not a position."
  [inst]
  (let [{:keys [latitude longitude]} (:location inst)
        num #(when (string? %) (parse-double (str/trim %)))
        lat (num latitude)
        lon (num longitude)]
    (when (and lat lon (<= -90.0 lat 90.0) (<= -180.0 lon 180.0)) [lat lon])))

(defn- map-href
  "The /map link for the mine (with ?mines=all for a test mine),
  pointing at its dot."
  [inst]
  (str "/map" (when-not (:isProduction inst) "?mines=all") "#mine-" (:namespace inst)))

(def ^:private land-path
  (delay (some-> (io/resource "public/world-110m-land.path") slurp str/trim)))

(def ^:private dot-r 4.5)
(def ^:private dot-spacing
  "Centre distance of neighbouring dots in a ring (they overlap a little; the
  surface-coloured stroke separates them)."
  7.0)
(def ^:private font-size 9.0)
(def ^:private line-height 10.5)

(defn- ring-radius [n]
  (if (<= n 1) 0.0 (/ dot-spacing (* 2.0 (Math/sin (/ Math/PI n))))))

(defn- make-cluster [members]
  {:members (vec members)
   :cx (/ (reduce + (map :x members)) (count members))
   :cy (/ (reduce + (map :y members)) (count members))})

(defn- extent
  "The radius of the cluster's ring of dots, dots included."
  [c]
  (+ (ring-radius (count (:members c))) dot-r))

(defn- clusters
  "Groups the placed mines ({:inst :x :y}) so that no two rings of dots
  overlap: starting with one cluster per mine, merges the pair that overlaps
  most (centre distance less than the sum of the extents plus 1 unit) until
  none does. Mines at one place always end up together."
  [placed]
  (loop [cs (mapv #(make-cluster [%]) placed)]
    (let [overlaps (for [i (range (count cs)) j (range (inc i) (count cs))
                         :let [a (cs i) b (cs j)
                               room (- (Math/hypot (- (:cx a) (:cx b)) (- (:cy a) (:cy b)))
                                       (+ (extent a) (extent b) 1.0))]
                         :when (neg? room)]
                     [room i j])]
      (if-let [[_ i j] (first (sort overlaps))]
        (recur (conj (vec (keep-indexed (fn [k c] (when-not (#{i j} k) c)) cs))
                     (make-cluster (concat (:members (cs i)) (:members (cs j))))))
        (vec (sort-by (juxt :cx :cy) cs))))))

(defn- fan
  "The cluster's members with the position of their dot (:dx :dy): a single
  mine at its place; several on a ring round the cluster's centre, the ring
  turned so that each dot lies roughly in the direction of its true place
  (mines at one place side by side, alphabetically)."
  [{:keys [members cx cy]}]
  (let [n (count members)]
    (if (= 1 n)
      [(assoc (first members) :dx cx :dy cy)]
      (let [r (ring-radius n)
            step (/ (* 2.0 Math/PI) n)
            bearing (fn [{:keys [x y]}]
                      (when (> (Math/hypot (- x cx) (- y cy)) 0.25)
                        (Math/atan2 (- y cy) (- x cx))))
            ms (sort-by (juxt #(or (bearing %) 0.0) (comp str/lower-case :name :inst)) members)
            turns (keep-indexed (fn [i m] (some-> (bearing m) (- (* i step)))) ms)
            sx (reduce + (map #(Math/cos %) turns))
            sy (reduce + (map #(Math/sin %) turns))
            a0 (if (< (Math/hypot sx sy) 1e-6)
                 (if (= 2 n) Math/PI (- (/ Math/PI 2.0)))
                 (Math/atan2 sy sx))]
        (map-indexed (fn [i m]
                       (let [a (+ a0 (* i step))]
                         (assoc m :dx (+ cx (* r (Math/cos a))) :dy (+ cy (* r (Math/sin a))))))
                     ms)))))

(defn- text-width
  "An estimate of the width of s at the label font size (system-ui averages
  about 0.55 em a character)."
  [s]
  (* 0.56 font-size (count s)))

(defn- overlap [[ax ay aw ah] [bx by bw bh]]
  (* (max 0.0 (- (min (+ ax aw) (+ bx bw)) (max ax bx)))
     (max 0.0 (- (min (+ ay ah) (+ by bh)) (max ay by)))))

(defn- label-candidates
  "Places for a w x h label block round the cluster, in order of preference:
  right, left, the four diagonals, above, below; then the same further out
  (drawn with a leader line). :box is [x y w h]."
  [{:keys [cx cy] :as c} w h]
  (for [[far? extra] [[false 0.0] [true 16.0]]
        :let [r (+ (extent c) 2.5 extra)
              d (* 0.72 r)]
        [anchor x y] [["start" (+ cx r) (- cy (/ h 2))]
                      ["end" (- cx r w) (- cy (/ h 2))]
                      ["start" (+ cx d) (- cy d h)]
                      ["start" (+ cx d) (+ cy d)]
                      ["end" (- cx d w) (- cy d h)]
                      ["end" (- cx d w) (+ cy d)]
                      ["middle" (- cx (/ w 2)) (- cy r h)]
                      ["middle" (- cx (/ w 2)) (+ cy r)]]]
    {:anchor anchor :box [x y w h] :far? far?}))

(defn- place-labels
  "Adds a :label to each cluster (with its :dots, see fan): the candidate
  place that overlaps the labels placed so far, every ring of dots and the
  canvas edge least (clusters with the longest labels first)."
  [cs]
  (let [disc (fn [c] (let [e (extent c)] [(- (:cx c) e) (- (:cy c) e) (* 2 e) (* 2 e)]))
        discs (map disc cs)
        canvas [0.0 0.0 (double map-width) (double map-height)]
        order (sort-by (fn [i] [(- (count (:members (cs i)))) (:cx (cs i))]) (range (count cs)))]
    (loop [[i & more] order, placed [], cs cs]
      (if-not i
        cs
        (let [c (cs i)
              ;; top to bottom as the dots, so each name sits by its dot
              names (map (comp :name :inst) (sort-by (juxt #(Math/round (* 10.0 (:dy %))) (comp str/lower-case :name :inst)) (:dots c)))
              w (+ 2.0 (apply max (map text-width names)))
              h (* line-height (count names))
              cost (fn [k {:keys [box far?]}]
                     (+ (* 10 (reduce + (map #(overlap box %) (concat placed discs))))
                        (* 10 (- (* w h) (overlap box canvas)))
                        (if far? 40 0)
                        k))
              best (apply min-key (fn [[k cand]] (cost k cand))
                          (map-indexed vector (label-candidates c w h)))
              label (assoc (second best) :names names)]
          (recur more (conj placed (:box label)) (assoc cs i (assoc c :label label))))))))

(defn- n1
  "x with one decimal (Locale/ROOT: always a point)."
  [x]
  (String/format java.util.Locale/ROOT "%.1f" (object-array [(double x)])))

(defn- mine-path [inst] (str "/instances/" (:namespace inst)))

(defn- world-svg [cs]
  [:svg.world-map {:viewBox (str "0 0 " map-width " " map-height) :width "100%"
                   :role "group" :aria-labelledby "map-title"}
   [:title#map-title "Where the mines are"]
   (when-let [d @land-path] [:path.land {:d d}])
   (for [{:keys [cx cy] :as c} cs
         :let [{:keys [far?] [x y w h] :box} (:label c)]
         :when far?
         :let [;; nearest point of the label box, and the edge of the ring towards it
               px (max x (min cx (+ x w)))
               py (max y (min cy (+ y h)))
               len (max 1e-6 (Math/hypot (- px cx) (- py cy)))
               e (+ (extent c) 1.0)]]
     [:line.leader {:x1 (n1 (+ cx (* e (/ (- px cx) len)))) :y1 (n1 (+ cy (* e (/ (- py cy) len))))
                    :x2 (n1 px) :y2 (n1 py)}])
   (for [c cs
         {:keys [inst dx dy]} (:dots c)]
     [:a {:href (mine-path inst) :id (str "mine-" (:namespace inst))}
      [:title (h (:name inst))]
      [:circle {:cx (n1 dx) :cy (n1 dy) :r (n1 dot-r)}]])
   (for [c cs
         :let [{:keys [anchor names] [x y w _] :box} (:label c)
               by-name (into {} (map (juxt (comp :name :inst) :inst) (:members c)))
               tx (case anchor "start" x "end" (+ x w) (+ x (/ w 2)))]
         [k nm] (map-indexed vector names)]
     [:a.label {:href (mine-path (by-name nm)) :tabindex "-1"}
      [:text {:x (n1 tx) :y (n1 (+ y 7.6 (* k line-height))) :text-anchor anchor} (h nm)]])])

(defn map-page
  "Where the mines are: a world map with a dot per mine that has coordinates
  (production mines; all with ?mines=all, as the home page), and the list of
  those without."
  [params _opts]
  (let [all? (some? (mines-param params))
        insts (cond->> (data/instances)
                (not all?) (filter :isProduction)
                true (sort-by (comp str/lower-case :name)))
        placed (for [inst insts
                     :let [[lat lon] (coordinates inst)]
                     :when lat
                     :let [[x y] (project lat lon)]]
                 {:inst inst :x x :y y})
        missing (remove coordinates insts)
        cs (place-labels (mapv #(assoc % :dots (fan %)) (clusters placed)))]
    (layout {:title "Where the mines are" :active :map}
            [:section.intro
             [:h1 "Where the mines are"]
             [:p (str (count placed) " " (if all? "mines" "production mines") ", placed by the latitude and longitude "
                      "in their registry entries. Mines at the same or nearby places are drawn as a small ring of dots with one label.")
              " " (if all?
                    [:a {:href "/map"} "Show production mines only"]
                    [:a {:href "/map?mines=all"} "Show test mines too"]) "."]]
            [:figure.map-figure
             [:div.map-scroll (world-svg cs)]
             [:figcaption.map-hint "The map scrolls sideways."]]
            (when (seq missing)
              [:section#not-on-map
               [:h2 "Not on the map"]
               [:ul.inline-list
                (for [inst missing]
                  [:li [:a {:href (mine-path inst)} (h (:name inst))]])]])
            [:p.soft "The coordinates come from each mine's entry in the registry (" [:code ":location"] "). "
             "To add or correct them, open a pull request against "
             [:a {:href (str api/repository-url "/blob/dev/resources/instances.edn")} [:code "resources/instances.edn"]] "."
             " Land outline: Natural Earth (public domain)."])))

(defn- swatches [colors]
  (for [[group v] colors
        [k c] (if (map? v) v {group v})
        :let [c (css-colour c)]
        :when c]
    [:span.swatch [:span.chip {:style (str "background:" c)}] [:code (h (str (name group) (when (map? v) (str "." (name k))) " " c))]]))

(defn- field-row [label value]
  [:tr [:th {:scope "row"} label] [:td value]])

(defn- last-check-block [inst opts]
  (let [{:keys [time outcome reason since] :as check} (:last_check inst)]
    [:section.last-check
     [:h2 "Last check"]
     (if-not check
       [:p "The registry has not checked this mine yet."]
       [:table.fields
        [:tbody
         (field-row "Time" (h (date-part time)))
         (field-row "Outcome" (h outcome))
         (when reason (field-row "Reason" (h reason)))
         (when (and (= "failed" outcome) since)
           (field-row (if (= "Unreachable" (:status inst)) "Unreachable since" "Failing since")
                      (h (date-part since))))]])
     (when (check-failed? inst)
       [:p.notice
        (if (bot-reason? reason)
          [:span "For the maintainer of this mine: the registry's request was answered with a web page instead of "
           "the version number, usually by bot protection such as Anubis. Please let the registry's checks through: "
           [:a {:href maintainer-notes-url} "how to do it"] "."]
          [:span "The registry could not get a version from this mine. If the mine is up, see "
           [:a {:href maintainer-notes-url} "what the registry's checks need"] "."])])
     [:p.soft "A mine stays listed when a check fails, with its last known versions and branding. "
      "The registry shows which mines exist, not whether they are up this minute: a mine is marked "
      "Unreachable only when its checks have been failing for more than "
      (unreachable-hours opts) " hours."]]))

(defn instance-page [ns opts]
  (when-let [inst (api/find-instance (data/instances) ns)]
    (let [result (get-in @data/state [:refresh :results (:namespace inst)])
          json-path (str "/service/instances/" (:namespace inst))
          url (safe-url (:url inst))]
      (layout {:title (:name inst)}
              [:p.crumbs [:a {:href "/"} "Mines"] " / " (h (:name inst))]
              [:h1.with-logo (logo inst) (h (:name inst)) " " (status-dot inst)]
              (when-not (str/blank? (:description inst)) [:p.lead (h (str/trim (:description inst)))])
              [:p.links
               (when url [:a.button {:href url} "Open the mine"])
               (when url [:a {:href (str url "/service")} "Web service root"])
               [:a {:href json-path} "Registry JSON"]
               [:a {:href (str "/" (h (:namespace inst)))} [:code (str (:base-url opts) "/" (h (:namespace inst)))]]]
              [:table.fields
               [:tbody
                (field-row "Status" (h (check-sentence inst)))
                (field-row "Namespace" [:code (h (:namespace inst))])
                (field-row "Id" [:code (h (:id inst))])
                (field-row "URL" (if url [:a {:href url} (h url)] (h (:url inst))))
                (field-row "Production" (if (:isProduction inst) "yes" "no (test mine)"))
                (field-row "Organisms" (h (str/join ", " (:organisms inst))))
                (field-row "Neighbours" (h (str/join ", " (:neighbours inst))))
                (field-row "InterMine version" (h (:intermine_version inst)))
                (field-row "Release" (h (:release_version inst)))
                (field-row "API version" (h (:api_version inst)))
                (field-row "Maintainer" (h (:maintainerOrgName inst)))
                (field-row "Maintainer URL" (if-let [u (safe-url (:maintainerUrl inst))] [:a {:href u} (h u)] ""))
                (field-row "Maintainer email" (h (:maintainerEmail inst)))
                (field-row "Source code" (if-let [u (safe-url (:maintainerGithubUrl inst))] [:a {:href u} (h u)] ""))
                (field-row "Twitter" (if (str/blank? (:twitter inst)) ""
                                         [:a {:href (str "https://twitter.com/" (h (:twitter inst)))} "@" (h (:twitter inst))]))
                (field-row "Location" (let [{:keys [latitude longitude]} (:location inst)]
                                        (cond
                                          (coordinates inst)
                                          [:span.location (h (str latitude ", " longitude)) " · "
                                           [:a {:href (map-href inst)} "show on map"]]
                                          (str/blank? latitude) ""
                                          :else (h (str latitude ", " longitude)))))
                (field-row "Colours" (or (seq (swatches (:colors inst))) ""))
                (field-row "Logo" (let [u (safe-url (get-in inst [:images :logo]))
                                        override (logos/override (:namespace inst))]
                                    (cond
                                      (logos/has-logo? (:namespace inst))
                                      [:span [:img {:class (str "big-logo " (tile-class (:namespace inst)))
                                                    :src (logos/logo-path (:namespace inst)) :alt (str (h (:name inst)) " logo") :onerror "this.remove()"}]
                                       (if override
                                         [:span " the registry's own image (" [:code (h (:file override))] ")"
                                          (when u [:span "; the mine's branding names " [:code (h u)]])]
                                         (when u [:span " " [:code (h u)]]))]
                                      u [:code (h u)]
                                      :else "")))
                (field-row "Added" (h (date-part (:created_at inst))))
                (field-row "Last checked" (h (date-part (:last_time_updated inst))))
                (when (seq (:errors result))
                  (field-row "Last refresh problems" [:ul.plain (for [e (:errors result)] [:li (h e)])]))]]
              (last-check-block inst opts)
              [:h2 "JSON"]
              [:p "As served at " [:a {:href json-path} [:code json-path]] ":"]
              [:pre.json (h (json/generate-string (api/instance->json inst opts) {:pretty true}))]))))

(defn docs [opts]
  (let [base (:base-url opts)
        example (some-> (first (filter #(= "flymine" (:namespace %)) (data/instances)))
                        (api/instance->json opts))
        example (or example (some-> (first (data/instances)) (api/instance->json opts)))]
    (layout {:title "API" :active :docs}
            [:h1 "API"]
            [:p "Every answer is JSON (" [:code "application/json; charset=utf-8"] ") with "
             [:code "Access-Control-Allow-Origin: *"] ", so browsers can call the API from any site. "
             "The API is the one the registry has had since 2017 (Leonardo Kuffo's GSoC project); "
             "the write endpoints were removed in 2.0 (the instances are maintained by pull request), "
             "and instances no longer carry Mongo's " [:code "_id"] " and " [:code "__v"] "."]
            [:p "Base URL: " [:code (h base)]]
            [:div.table-scroll
             [:table.routes
              [:thead [:tr [:th "Method"] [:th "Path"] [:th "Description"]]]
              [:tbody
               (for [{:keys [method path summary]} api/routes-doc]
                 [:tr [:td [:code (h method)]] [:td [:a {:href (str "#" (str/replace path #"[^a-z]+" "-"))} [:code (h path)]]] [:td (h summary)]])]]]
            (for [{:keys [method path summary params example returns]} api/routes-doc]
              [:section.route {:id (str/replace path #"[^a-z]+" "-")}
               [:h2 [:code (h method)] " " [:code (h path)]]
               [:p (h summary)]
               (when (seq params)
                 [:dl.params (for [[n d] params] (list [:dt [:code (h n)]] [:dd (h d)]))])
               [:p.returns [:strong "Returns "] [:code (h returns)]]
               (when example
                 [:p "Example: " [:a {:href example} [:code (h (str "curl " base example))]]])])
            (when example
              [:section
               [:h2 "An instance"]
               [:p "The fields of an instance, as in " [:code "/service/instances"] " and "
                [:code "/service/instances/{id}"] ". "
                [:code "api_version"] ", " [:code "intermine_version"] ", " [:code "release_version"] ", "
                [:code "status"] ", " [:code "last_check"] ", " [:code "colors"] ", " [:code "images"] " and " [:code "last_time_updated"]
                " are refreshed from the mine; the rest come from the instances file. "
                [:code "status"] " is " [:code "Running"] ", " [:code "Unreachable"] " (the checks have been failing for more than "
                (unreachable-hours opts) " hours) or "
                [:code "Not checked yet"] "; " [:code "last_check"] " is "
                [:code "{time, outcome (ok|failed), reason, since}"] ". Unreachable mines stay in the list. "
                [:code "images.logo"] " is the mine's own logo URL; " [:code "images.logo_cached"]
                " (when present) is the registry's copy of it, served from " [:code "/instances/{namespace}/logo"] ", and "
                [:code "images.logo_tile"] " (present with it) is " [:code "\"light\""] " or " [:code "\"dark\""]
                ": the shade of tile the logo reads well on, from its mean luminance (" [:code "dark"]
                " for a white or pale logo). For a mine whose logo the registry carries itself (a logo override, "
                "for a mine whose branding gives no usable logo or a generic one), " [:code "images.logo"]
                " and " [:code "images.logo_cached"] " are both the registry's URL, and "
                [:code "images.logo_declared"] " is the logo the mine's branding names, when it names one."]
               [:pre.json (h (json/generate-string example {:pretty true}))]]))))

(defn status-page [opts]
  (let [{:keys [last-run results]} (:refresh @data/state)
        insts (sort-by (comp str/lower-case :name) (data/instances))]
    (layout {:title "Status" :active :status}
            [:h1 "Refresh status"]
            [:p (if (pos? (or (:minutes opts) 0))
                  (str "Every mine is checked "
                       (let [m (:minutes opts)]
                         (if (zero? (mod m 60))
                           (if (= 60 m) "every hour" (str "every " (quot m 60) " hours"))
                           (str "every " m " minutes")))
                       ". ")
                  "Scheduled checks are disabled. ")
             (if last-run
               (str "Last complete run: " (date-part (:finished last-run)) " (" (:count last-run) " mines).")
               "No run since the cache was created; the values shown are the initial ones from the instances file.")]
            [:p "A failed check never removes a mine from the registry: it keeps its last known versions and "
             "branding and the reason is shown here. A mine is marked "
             [:strong "Unreachable"] " only when its checks have been failing for more than "
             (unreachable-hours opts) " hours; until then it stays Running and the failed check is noted. Reasons: " [:code "timeout"] ", " [:code "connection refused"] ", "
             [:code "http NNN"] ", " [:code "bot-protection page"] ", " [:code "html instead of json"] ", "
             [:code "invalid version string"] ". Mine maintainers: "
             [:a {:href maintainer-notes-url} "letting the registry check your mine"] "."]
            [:div.table-scroll
             [:table.status
              [:thead [:tr [:th [:span.sr "Status"]] [:th "Mine"] [:th "Checked"] [:th "Last check"] [:th "Changed"] [:th "Problems"]]]
              [:tbody
               (for [inst insts
                     :let [r (get results (:namespace inst))]]
                 [:tr
                  [:td (status-dot inst)]
                  [:td [:a {:href (str "/instances/" (h (:namespace inst)))} (h (:name inst))]]
                  [:td.when (h (or (date-part (:checked r)) "not yet"))
                   (when (:ms r) [:span.muted (str " (" (:ms r) " ms)")])]
                  [:td (if (check-failed? inst)
                         [:span.errors (h (str (check-sentence inst)))]
                         "ok")]
                  [:td (if (seq (:changes r))
                         [:ul.plain (for [{:keys [field from to]} (:changes r)]
                                      [:li [:code (h field)] " " (h (json/generate-string from)) " → " (h (json/generate-string to))])]
                         (when r "nothing"))]
                  [:td (when (seq (:errors r)) [:ul.plain.errors (for [e (:errors r)] [:li (h e)])])]])]]])))

(defn not-found [path _opts]
  (layout {:title "Not found"}
          [:h1 "Not found"]
          [:p "There is no " [:code (h path)] " in the registry. " [:a {:href "/"} "All mines"] "."]))
