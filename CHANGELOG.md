# Changelog

## 2.0.0 (unreleased)

A re-implementation in Clojure of the GSoC 2017 Node/Express/MongoDB registry, keeping its read API.

### Added
- `/map`, "Where the mines are": a world map (inline SVG, Natural Earth 1:110m land, public
  domain; equirectangular) with a dot per mine at the `location` of its entry, linking to the mine's
  page; mines at the same or nearby places are fanned out on a small ring with one label. Production
  mines, all with `?mines=all`; mines without coordinates are listed under "Not on the map". A "Map"
  link in the header; the instance page's Location row links to the mine's dot ("show on map"). The
  API is unchanged.

### Changed
- The data is a version-controlled file, `resources/instances.edn`, validated with clojure.spec at
  start-up and in CI; mines are added and changed by pull request. Seeded from the live registry's
  `/service/instances` on 17 Nov 2026 (14 running instances).
- Versions and branding are refreshed every `REGISTRY_REFRESH_MINUTES` (default 360, every six hours:
  the registry advertises the range of mines, not their uptime; was daily at midnight), with 10 s timeouts, and every answer is validated: HTML pages (served by mines behind
  bot protection or retired mines) are no longer stored as versions; the previous value is kept and
  the problem shown on `/status`. A mine whose `/service/version/intermine` answers 200 with
  something that is not a version counts as a failed check (it was "Running").
- A failed check no longer hides a mine. Unreachable mines stay in `GET /service/instances` and on
  the home page, with their last known versions and branding. Each instance has a new `last_check`
  object `{time, outcome ("ok"|"failed"), reason, since}` (reasons: `timeout`, `connection refused`,
  `http NNN`, `bot-protection page`, `html instead of json`, `invalid version string`, ...), kept in
  the cache. `status` is `"Running"` (last check ok, or failing for less than
  `REGISTRY_UNREACHABLE_AFTER_HOURS`; failures are recorded in `last_check` and the pages note them),
  `"Unreachable"` (the checks have been failing for more than `REGISTRY_UNREACHABLE_AFTER_HOURS`,
  default 24, measured from `last_check.since`) or `"Not checked yet"`; `"Not Running"` is gone (an old cache reads as `"Unreachable"`). The seeded
  capture counts as an ok check at its `last_time_updated`. `/health` gains `unreachable`.
- Pages: grey status dot with the reason as tooltip and under the mine name on the home page; a
  "Last check" section on instance pages with a note for maintainers whose mine answers with a
  bot-protection page; the reasons on `/status`. README: "For mine maintainers: letting the registry
  check your mine".
- Requests to mines send a `User-Agent: InterMine-Registry/2.0` header (the Node app sent none,
  which bot protection such as Anubis challenges).
- `GET /service/namespace?url=` returns the instance whose URL matches exactly (ignoring scheme,
  `www.` and trailing slashes) before falling back to the old substring match, production instances
  first. The old answer for `https://www.flymine.org/flymine` was `flymine.beta`.
- 404 from `GET /service/instances/{id}` also has a `message` (it keeps `errorMsg`).
- `?q=` matches part of the name as plain text (it was a regular expression).
- `PUT /service/synchronize[/{id}]` takes `Authorization: Bearer $REGISTRY_ADMIN_TOKEN`
  (405 when no token is configured) instead of HTTP Basic users.
- New pages: `/` (mines table with filter), `/instances/{namespace}`, `/docs` (API documentation
  generated from the route table; `/api-docs` redirects there), `/status`. `/im-to-galaxy` and
  `/galaxy-to-im` keep working.
- Logos and favicons are cached by the registry: after a successful check it fetches the mine's
  `images.logo` (then `{url}/model/images/favicon.ico`, then `{url}/favicon.ico`; image content
  types only, at most 512 KB), stores it under `REGISTRY_CACHE_DIR/logos/<namespace>.<ext>`
  (indexed in the cache EDN; a failed fetch keeps the previous copy) and serves it at
  `GET /instances/{namespace}/logo` (`Cache-Control: public, max-age=86400`). The pages use that
  path instead of the mines' own hosts and show the initial mark until the registry holds a logo.
  Instances in the API gain `images.logo_cached` (absolute, from `REGISTRY_BASE_URL`) when a logo is
  held; `images.logo` is unchanged.
- Each cached logo has a tile shade, measured when it is stored (mean luminance of its visible
  pixels; 180 of 255 or more is a light logo): the pages show light logos (AquaMine, ThaleMine) on a
  near-black tile and the others on a white one, in both page themes, and the API gives it as
  `images.logo_tile` (`"light"` or `"dark"`, present with `images.logo_cached`). A cache from before
  the shade was kept gets it at start-up.
- Logo overrides: a mine's entry in `instances.edn` can name, as `:logo-override`, an image bundled
  with the registry under `resources/public/logos/` (for a mine whose branding gives no usable logo
  or a generic one). It wins over the fetched copy on the pages and at
  `GET /instances/{namespace}/logo` (same `Cache-Control`, `ETag` and `nosniff` headers), the
  refresh no longer fetches that mine's logo, and in the API `images.logo` and `images.logo_cached`
  are both the registry's URL, `images.logo_tile` the override's shade and the new
  `images.logo_declared` the logo the mine's branding names. PlanMine is the first: its branding
  names the generic InterMine squares, so the registry carries the planarian from its front-page
  logo (`planmine.png`), and its `images.logo` differs from the Node registry's.
- A System / Light / Dark theme switch in the page header (stored in `localStorage`, applied before
  the first paint); without JavaScript the pages follow the OS setting as before.
- New: `GET /health`, Dockerfile, Compose example, GitHub Actions CI.
- Data corrections (17 Nov 2026): AllianceMine's URL is the InterMine service root
  `https://alliancemine.alliancegenome.org/alliancemine` (the registry held the Alliance BlueGenes
  page); the URGI mines (OakMine, RepetDB, WheatMine) and their maintainer URL use the renamed
  domain `urgi.versailles.inrae.fr`, to which every `inra.fr` URL now redirects.
- Data corrections (17 Nov 2026): the blank locations are filled in: AllianceMine at SGD, Stanford
  (37.428265, -122.169498, the old registry's YeastMine entry), AquaMine and FAANGMine at the Elsik
  Lab, University of Missouri (38.940397, -92.327813, as HymenopteraMine and MaizeMine).

### Removed
- MongoDB, user accounts, login, the add/edit/delete pages and the write endpoints
  (`POST /service/instances`, `PUT` and `DELETE /service/instances/{id}` answer 405).
- Mongo's `_id` and `__v` in instances (the one difference in the instance JSON).
- The Node implementation (`app.js`, `bin/`, `routes/`, `models/`, `scheduled/`, `views/`,
  `public/`, `package.json`, `swagger.json`, `createUser.js`, `initRegistry.js`); it remains in the
  git history (last commit 37f67f2).

## 1.x

The Node/Express/MongoDB registry, 2017-2020 (Leonardo Kuffo, GSoC 2017, and contributors).
