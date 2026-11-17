(ns intermine-registry.pages
  "Server-rendered pages (Hiccup 1; text is escaped with h, attributes are
  escaped by Hiccup)."
  (:require [cheshire.core :as json]
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
              (for [[href label k] [["/" "Mines" :home] ["/docs" "API" :docs] ["/status" "Status" :status]]]
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
  "The CSS class of the tile shade for the namespace's cached logo
  (tile-light or tile-dark, see logos/logo-tile); tile-light without one."
  [namespace]
  (str "tile-" (or (logos/tile namespace) "light")))

(defn- logo
  "The registry's copy of the mine's logo (/instances/{namespace}/logo) over
  a neutral mark (its initial), which shows when the registry holds no logo
  yet or it does not load. The pages never point at the mine's own host. The
  tile is dark for a light logo (logos/logo-tile), in either page theme."
  [inst]
  [:span {:class (str "logo " (tile-class (:namespace inst))) :aria-hidden "true"}
   [:span.placeholder (h (subs (str (:name inst) "?") 0 1))]
   (when (logos/cached? (:namespace inst))
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
               [:a {:href "/?mines=all"} "show non-production mines"])]
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
                         (when-not (:isProduction inst) [:span.tag "dev"])
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
                (field-row "Production" (if (:isProduction inst) "yes" "no (dev/beta)"))
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
                                        (if (str/blank? latitude) "" (h (str latitude ", " longitude)))))
                (field-row "Colours" (or (seq (swatches (:colors inst))) ""))
                (field-row "Logo" (let [u (safe-url (get-in inst [:images :logo]))]
                                    (cond
                                      (logos/cached? (:namespace inst))
                                      [:span [:img {:class (str "big-logo " (tile-class (:namespace inst)))
                                                    :src (logos/logo-path (:namespace inst)) :alt (str (h (:name inst)) " logo") :onerror "this.remove()"}]
                                       (when u [:span " " [:code (h u)]])]
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
                " for a white or pale logo)."]
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
