(ns intermine-registry.pages
  "Server-rendered pages (Hiccup 1; text is escaped with h, attributes are
  escaped by Hiccup)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [hiccup.core :refer [h]]
            [hiccup.page :refer [html5]]
            [intermine-registry.api :as api]
            [intermine-registry.data :as data]))

(defn- safe-url
  "url if it is http(s), else nil (mine-supplied values end up in src/href)."
  [url]
  (when (and (string? url) (re-matches #"https?://[^\s\"'<>]+" url)) url))

(defn- css-colour [c]
  (when (and (string? c) (re-matches #"#[0-9A-Fa-f]{3,8}|[a-zA-Z]{3,20}|rgba?\([0-9., %]+\)" c)) c))

(defn- layout [{:keys [title active]} & body]
  (html5 {:lang "en"}
         [:head
          [:meta {:charset "utf-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
          [:title (h (if title (str title " - InterMine Registry") "InterMine Registry"))]
          [:meta {:name "description" :content "The InterMine Registry: the list of InterMine data warehouses (mines) and their web services."}]
          [:link {:rel "stylesheet" :href "/css/registry.css"}]]
         [:body
          [:header.site
           [:div.wrap
            [:a.brand {:href "/"} [:span.mark "IM"] "InterMine Registry"]
            [:nav
             (for [[href label k] [["/" "Mines" :home] ["/docs" "API" :docs] ["/status" "Status" :status]]]
               [:a {:href href :class (when (= k active) "active")} label])]]]
          [:main.wrap body]
          [:footer.site
           [:div.wrap
            [:p "InterMine Registry; API designed in Leonardo Kuffo's Google Summer of Code 2017 project; "
             "re-implemented 2026; LGPL-2.1; "
             [:a {:href api/repository-url} "source"] "."]
            [:p "To add a mine or correct its details, open a pull request against "
             [:a {:href (str api/repository-url "/blob/dev/resources/instances.edn")} [:code "resources/instances.edn"]] "."]]]]))

(defn- status-dot [status]
  (let [running? (= "Running" status)]
    [:span {:class (str "dot " (if running? "up" "down")) :title (h (or status "unknown"))}
     [:span.sr (h (or status "unknown"))]]))

(defn- logo
  "The mine's logo over a neutral mark (its initial), which shows when there
  is no logo or it does not load."
  [inst]
  [:span.logo {:aria-hidden "true"}
   [:span.placeholder (h (subs (str (:name inst) "?") 0 1))]
   (when-let [src (safe-url (get-in inst [:images :logo]))]
     [:img {:src src :alt "" :loading "lazy" :onerror "this.remove()"}])])

(def ^:private organisms-shown 6)

(defn- organisms-cell [organisms]
  (let [n (count organisms)]
    (if (<= n (inc organisms-shown))
      (h (str/join ", " organisms))
      [:span {:title (h (str/join ", " organisms))}
       (h (str/join ", " (take organisms-shown organisms)))
       [:span.muted (str " and " (- n organisms-shown) " more")]])))

(defn- date-part [iso] (when iso (str/replace (str iso) #"T(\d\d:\d\d).*" " $1 UTC")))

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
                  [:td (status-dot (:status inst))]
                  [:td [:div.mine (logo inst)
                        [:span
                         [:a.name {:href (if (and to-galaxy? (safe-url (:url inst)))
                                           (str (:url inst) "/begin.do?GALAXY_URL=" (java.net.URLEncoder/encode ^String galaxy-url "UTF-8"))
                                           (safe-url (:url inst)))}
                          (h (:name inst))]
                         (when-not (:isProduction inst) [:span.tag "dev"])]]]
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

(defn instance-page [ns opts]
  (when-let [inst (api/find-instance (data/instances) ns)]
    (let [result (get-in @data/state [:refresh :results (:namespace inst)])
          json-path (str "/service/instances/" (:namespace inst))
          url (safe-url (:url inst))]
      (layout {:title (:name inst)}
              [:p.crumbs [:a {:href "/"} "Mines"] " / " (h (:name inst))]
              [:h1.with-logo (logo inst) (h (:name inst)) " " (status-dot (:status inst))]
              (when-not (str/blank? (:description inst)) [:p.lead (h (str/trim (:description inst)))])
              [:p.links
               (when url [:a.button {:href url} "Open the mine"])
               (when url [:a {:href (str url "/service")} "Web service root"])
               [:a {:href json-path} "Registry JSON"]
               [:a {:href (str "/" (h (:namespace inst)))} [:code (str (:base-url opts) "/" (h (:namespace inst)))]]]
              [:table.fields
               [:tbody
                (field-row "Status" (h (:status inst)))
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
                (field-row "Logo" (if-let [u (safe-url (get-in inst [:images :logo]))]
                                    [:span [:img.big-logo {:src u :alt (str (h (:name inst)) " logo") :onerror "this.remove()"}] " " [:code (h u)]]
                                    ""))
                (field-row "Added" (h (date-part (:created_at inst))))
                (field-row "Last checked" (h (date-part (:last_time_updated inst))))
                (when (seq (:errors result))
                  (field-row "Last refresh problems" [:ul.plain (for [e (:errors result)] [:li (h e)])]))]]
              [:h2 "JSON"]
              [:p "As served at " [:a {:href json-path} [:code json-path]] ":"]
              [:pre.json (h (json/generate-string (api/instance->json inst) {:pretty true}))]))))

(defn docs [opts]
  (let [base (:base-url opts)
        example (some-> (first (filter #(= "flymine" (:namespace %)) (data/instances)))
                        api/instance->json)
        example (or example (some-> (first (data/instances)) api/instance->json))]
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
                [:code "status"] ", " [:code "colors"] ", " [:code "images"] " and " [:code "last_time_updated"]
                " are refreshed from the mine; the rest come from the instances file."]
               [:pre.json (h (json/generate-string example {:pretty true}))]]))))

(defn status-page [opts]
  (let [{:keys [last-run results]} (:refresh @data/state)
        insts (sort-by (comp str/lower-case :name) (data/instances))]
    (layout {:title "Status" :active :status}
            [:h1 "Refresh status"]
            [:p (if (pos? (or (:minutes opts) 0))
                  (str "Every mine is checked every " (:minutes opts) " minutes. ")
                  "Scheduled checks are disabled. ")
             (if last-run
               (str "Last complete run: " (date-part (:finished last-run)) " (" (:count last-run) " mines).")
               "No run since the cache was created; the values shown are the initial ones from the instances file.")]
            [:div.table-scroll
             [:table.status
              [:thead [:tr [:th [:span.sr "Status"]] [:th "Mine"] [:th "Checked"] [:th "Changed"] [:th "Problems"]]]
              [:tbody
               (for [inst insts
                     :let [r (get results (:namespace inst))]]
                 [:tr
                  [:td (status-dot (:status inst))]
                  [:td [:a {:href (str "/instances/" (h (:namespace inst)))} (h (:name inst))]]
                  [:td.when (h (or (date-part (:checked r)) "not yet"))
                   (when (:ms r) [:span.muted (str " (" (:ms r) " ms)")])]
                  [:td (if (seq (:changes r))
                         [:ul.plain (for [{:keys [field from to]} (:changes r)]
                                      [:li [:code (h field)] " " (h (json/generate-string from)) " → " (h (json/generate-string to))])]
                         (when r "nothing"))]
                  [:td (when (seq (:errors r)) [:ul.plain.errors (for [e (:errors r)] [:li (h e)])])]])]]])))

(defn not-found [path _opts]
  (layout {:title "Not found"}
          [:h1 "Not found"]
          [:p "There is no " [:code (h path)] " in the registry. " [:a {:href "/"} "All mines"] "."]))
