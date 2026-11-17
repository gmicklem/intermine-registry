# InterMine Registry

The InterMine Registry is the list of public [InterMine](https://intermine.org) instances (mines)
and an API that serves it. BlueGenes, the InterMine webapps (to find their own namespace), the
InterMine client libraries read it, and Galaxy users pass through its hand-over pages, at
[registry.intermine.org](https://registry.intermine.org).

Version 2 is a small Clojure service: the list of mines is a version-controlled file,
[`resources/instances.edn`](resources/instances.edn); the server re-implements the read API of
the original registry exactly, refreshes each mine's versions and branding on a schedule, and
renders a few plain pages. There is no database and there are no accounts.

## Acknowledgements

The registry and its API were designed and built by [Leonardo Kuffo](https://github.com/lkuffo) in
his Google Summer of Code 2017 project with InterMine. Later contributors to the Node
implementation (`git shortlog -s dev`): Yo Yehudi, Daniela Butano, himanshukumar660, Aman Dwivedi,
Raghav Sharma, Akashdeep Ramnaney, itolimaesther, Ankit Kumar, Arunan Sugunakumar, JosephBadua,
akshat, Aastha Bist, CoryWritesCode, JGO613, Laksh Singla, Pavika and dmahajan980. The 2026
re-implementation keeps their API so that every existing client works unchanged.

## Adding or changing a mine

Open a pull request against [`resources/instances.edn`](resources/instances.edn): copy an
existing entry and edit it. The comment at the top of the file describes every field. CI checks the
file (clojure.spec: required keys, URL shapes, unique namespaces and ids, `isProduction` a
boolean); the server refuses to start with an invalid file and lists the problems. You only set the
hand-maintained fields; versions, status, colours and logo are fetched from the mine.

Never change a mine's `namespace`: it is part of URLs that mines and clients store
(`registry.intermine.org/<namespace>`).

A mine whose branding gives no usable logo, or a generic one, can have a logo carried by the
registry itself: put the image in [`resources/public/logos/`](resources/public/logos) (png, svg,
jpg, jpeg, gif or webp; a small square image, transparent if possible) and name it in the mine's
entry as `:logo-override "<file>"` (a plain file name: letters, digits, `.`, `_`, `-`; CI checks
that the file exists). Record where the file came from (URL, date) in a comment next to the entry.
This is a last resort: first ask the mine's maintainer to set `branding.images.logo` in their
`web.properties`, which every client then sees. See [Logo overrides](#logo-overrides).

## Running

Needs JDK 17+ and the [Clojure CLI](https://clojure.org/guides/install_clojure).

```bash
clojure -M -m intermine-registry.core     # http://localhost:3000
clojure -M:test                           # tests (includes the check of instances.edn)
clojure -M:fmt check                      # formatting (cljfmt; `fix` to apply)
clojure -T:build uber                     # target/intermine-registry.jar
java -jar target/intermine-registry.jar
```

Docker:

```bash
docker build -t intermine-registry .
docker run -p 3000:3000 -v registry-cache:/cache intermine-registry
```

[`docker-compose.example.yml`](docker-compose.example.yml) is a Compose service with a health
check, ready to sit next to the mines behind the reverse proxy that terminates TLS.

### Configuration

All configuration is by environment variable:

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `3000` | HTTP port (as the Node app) |
| `REGISTRY_REFRESH_MINUTES` | `360` | refresh every mine this often (every 6 hours; the registry advertises the range of mines, not their uptime); `0` disables the schedule. The first run is 10 s after start-up. |
| `REGISTRY_UNREACHABLE_AFTER_HOURS` | `24` | a mine is marked Unreachable only when its checks have been failing for more than this many hours (see [Status and last check](#status-and-last-check)) |
| `REGISTRY_CACHE_DIR` | `./cache` (`/cache` in Docker) | where `registry-cache.edn` keeps the last refreshed values, and `logos/` the mines' logos, so a restart does not lose them |
| `REGISTRY_ADMIN_TOKEN` | unset | enables `PUT /service/synchronize[/{id}]` with `Authorization: Bearer <token>`; unset, those answer 405 |
| `REGISTRY_INSTANCES_FILE` | the bundled `resources/instances.edn` | path of an instances file to use instead |
| `REGISTRY_BASE_URL` | `https://registry.intermine.org` | absolute links on the pages and in the API docs |

## API

Documented on the running server at [`/docs`](https://registry.intermine.org/docs) (generated from
the route table in `src/clj/intermine_registry/api.clj`). In short:

- `GET /service/instances`: production instances, sorted by name, unreachable ones included (see
  `status` and `last_check` below); `?mines=all|dev|prod`,
  `?q=` (organism, description word or part of the name).
  `{"instances": [...], "statusCode": 200, "executionTime": "11/17/2026, 2:10:58 AM"}`
- `GET /service/instances/{id|namespace|name}`: `{"instance": {...}, "statusCode": 200, ...}`;
  404 `{"statusCode": 404, "errorMsg": "Not Found", "message": ...}`
- `GET /service/namespace?url=<mine url>`: `{"statusCode": 200, "namespace": "flymine"}`
- `GET /instances/{namespace}/logo`: the registry's own copy of the mine's logo, or its logo
  override (see [Logos](#logos)); 404 when it holds none yet
- `GET /{namespace}`: 303 redirect to the mine
- `GET /health`: `{"status": "ok", "instances": 14, "running": 14, "unreachable": 0, "lastRefresh": ...}`
- `PUT /service/synchronize`, `PUT /service/synchronize/{id}`: refresh now (admin token)
- `POST/PUT/DELETE /service/instances[/{id}]`: 405, the registry is read-only

Pages: `/` (the mines, with a filter), `/instances/{namespace}`, `/docs`, `/status` (the last
refresh of each mine: when, what changed, what failed), and the Galaxy hand-over pages
`/im-to-galaxy?GALAXY_URL=` and `/galaxy-to-im?URL=`. The pages follow the OS's light or dark
setting; a System / Light / Dark switch in the header overrides it (kept in the browser's
`localStorage` as `registry-theme`; without JavaScript the switch is hidden and the OS decides).

### Status and last check

Each instance in the API has a `status` string (as before) and a `last_check` object:

```json
"status": "Unreachable",
"last_check": {"time": "2026-11-18T10:00:00.000Z", "outcome": "failed",
               "reason": "bot-protection page", "since": "2026-11-17T09:00:00.000Z"}
```

- `last_check.outcome` is `"ok"` or `"failed"`; `reason` is `null` for `ok`; `time` is when the
  check ran; `since` is the time of the first failure of the current run of failures (`null` for
  `ok`). `last_check` is `null` for a mine that has never been checked. It is kept in the cache like
  the versions. The seeded capture counts as a check at its `last_time_updated`, outcome `ok`.
- `status` is `"Running"` when the last check succeeded, `"Unreachable"` when the checks have been
  failing for more than `REGISTRY_UNREACHABLE_AFTER_HOURS` (default 24) hours, `"Not checked yet"`
  before the first check. It is a time rule, not a count: `last_check.since` is the time of the
  first failure of the current run, and the mine becomes Unreachable at the first failed check
  that finds `time - since` greater than the limit (exactly 24 h is not more). Until then a failing
  mine stays `"Running"` (a mine that has never been checked stays `"Not checked yet"`), and
  `last_check` records each failure and its reason (the pages add a small "check failed (reason),
  retrying" note). The next successful check clears it at once. With the 6-hourly schedule that is
  the sixth consecutive failed check (checks at +0, +6, ... +30 h; the one at +24 h is not yet over the limit). `"Not Running"` is no longer produced; an older cache
  or instances file with it is read as `"Unreachable"`. `isProduction` is not affected.
- `reason` is one of `timeout`, `connection refused`, `unknown host`, `TLS error`, `http NNN` (a
  non-2xx answer, e.g. `http 503`), `bot-protection page` (an HTML answer that mentions Anubis, has
  a `<meta name="generator">` tag or a title about a challenge or verification), `html instead of
  json` (any other HTML), `invalid version string`, `answer too large`, or `connection error: <Class>`.
- Mines are never hidden because of a check; clients that want only working mines can filter on
  `status == "Running"`.

### Logos

The pages show each mine's logo from the registry itself, so the home page does not depend on
fourteen other hosts (and mines behind bot protection or on http only still show a logo). During
the refresh, after a successful check, the registry fetches the mine's logo: first the
`images.logo` of its `/service/branding` (a relative URL is resolved against the mine's URL), then
`{url}/model/images/favicon.ico`, then `{url}/favicon.ico`. It accepts the first answer that is an
image: `Content-Type` `image/png`, `image/jpeg`, `image/gif`, `image/svg+xml`, `image/x-icon` or
`image/vnd.microsoft.icon`, at most 512 KB, and not an HTML page; anything else is skipped (the
reasons are on `/status`). The image is stored as `REGISTRY_CACHE_DIR/logos/<namespace>.<ext>` and
listed, with its content type, source URL, time and SHA-1, in `registry-cache.edn`. When a fetch
fails the previous copy is kept.

- `GET /instances/{namespace}/logo` serves the stored image with its content type and
  `Cache-Control: public, max-age=86400`; 404 when there is none.
- In the API, `images.logo_cached` is the absolute URL of that copy (built from
  `REGISTRY_BASE_URL`); it is present only when the registry holds a logo. `images.logo` is still
  the mine's own URL, unchanged (except for a mine with a [logo override](#logo-overrides)).
- `images.logo_tile`, present with `images.logo_cached`, is `"light"` or `"dark"`: the shade of the
  tile the logo reads well on. When it stores a logo the registry decodes it (PNG, JPEG, GIF; an
  ICO or SVG counts as `"light"`) and measures the mean luminance, 0.2126 R + 0.7152 G + 0.0722 B,
  of its visible pixels (weighted by alpha, ignoring alpha 32 or less): a logo at 180 or more out of
  255 (`logos/light-logo-luminance`) is light and gets a dark tile, any other a light tile. Of the
  mines' logos in November 2026, AquaMine's white trident (240) and ThaleMine's white disc (208)
  are on dark tiles, the rest (13 to 147) on white ones. The shade is kept in the logo index of
  `registry-cache.edn` (`:logo-tile`); a cache from before it was kept is upgraded at start-up.
- The pages put each logo on a 28 px tile of that shade, in both page themes, and show a light
  tile with the mine's initial until the registry holds a logo, so on a fresh install (no cache
  yet) every mine shows its initial until the first refresh, 10 s after start-up, has filled
  `logos/`. The logo files are not part of the repository (except the overrides below).

#### Logo overrides

A mine with a `:logo-override` in `instances.edn` (see
[Adding or changing a mine](#adding-or-changing-a-mine)) shows the registry's own file,
`resources/public/logos/<file>` on the classpath, instead of a fetched copy, everywhere: the home
and instance pages, `GET /instances/{namespace}/logo` (served with the same `Cache-Control`,
`X-Content-Type-Options: nosniff` and an `ETag` from the file's SHA-1, as for cached logos) and the
API. The refresh does not fetch a logo for that mine (it still checks it and refreshes its versions
and branding). The tile shade is measured from the override at start-up and kept in memory. In the
API, for such a mine:

- `images.logo` and `images.logo_cached` are both the registry's URL,
  `REGISTRY_BASE_URL/instances/{namespace}/logo`;
- `images.logo_tile` is the override's shade;
- `images.logo_declared` is the logo the mine's own branding names (when it names one), so nothing
  is lost.

An override whose file is missing is ignored (with a warning at start-up) and the mine's logo is
fetched as usual. PlanMine has one: its branding names the generic InterMine squares on
`cdn.rawgit.com`, so the registry carries the planarian of its front-page logo (`planmine.png`).

## How the refresh works

For each mine, in parallel with at most 4 mines at a time and 10 s timeouts, the server calls
`{url}/service/version/intermine`, `/service/version/release`, `/service/version` and
`/service/branding`, with the rules of the Node app's `scheduled/automaticUpdate.js`:

- the check succeeds when `/service/version/intermine` answers 200 with a version; see
  [Status and last check](#status-and-last-check) for `status` and `last_check`. A failed check never
  removes a mine from `/service/instances` or from the pages, and keeps its last known versions
  and branding;
- the version answers are cleaned (quotes, brackets and slashes removed) and a version equal to
  the API version is blanked (a mine answering every path with the same page);
- `colors` and `images` come from `properties.colors` and `properties.images` of the branding JSON;
- `last_time_updated` is set; `organisms` and every other hand-maintained field are not touched.

New in 2.0: every answer is validated (`api_version` an integer string, versions short strings, not
HTML pages or unsubstituted `@TOKENS@`); a rejected answer keeps the previous value and is
reported on `/status`. The refreshed values go to the cache file, never back into
`instances.edn`; the values in `instances.edn` are only the initial ones.

## For mine maintainers: letting the registry check your mine

The registry checks every mine every `REGISTRY_REFRESH_MINUTES` (default 360, six hours) to show
its versions and branding and whether it answers. Each check is four plain `GET` requests, sent in parallel, with a 10 s
timeout:

```
GET {url}/service/version
GET {url}/service/version/intermine
GET {url}/service/version/release
GET {url}/service/branding
```

The requests carry `User-Agent: InterMine-Registry/2.0 (+https://registry.intermine.org)` and
`Accept: text/plain, application/json;q=0.9, */*;q=0.1` (a mine answers `Accept: application/json`
with a JSON envelope instead of the bare version; the registry reads the bare version). Redirects
are followed.

After a successful check the registry also fetches your mine's logo (the `images.logo` of the
branding, else `{url}/model/images/favicon.ico`, else `{url}/favicon.ico`; at most 512 KB, as an
image content type) with the same kind of request and shows its own copy of it, see
[Logos](#logos).

If your mine sits behind bot protection such as [Anubis](https://anubis.techaro.lol), a CDN
challenge or a web application firewall, the checker gets a challenge page instead of the version.
The registry then shows your mine as Unreachable with the reason `bot-protection page` (or `html
instead of json`), and keeps its last known versions. To let the checks through, either:

- exempt those four paths (`/service/version`, `/service/version/intermine`,
  `/service/version/release`, `/service/branding`; BlueGenes and the InterMine clients need
  `/service/*` anyway) from the bot protection, or
- allowlist the User-Agent `InterMine-Registry/2.0` (or the registry server's IP address).

Unreachable mines stay listed: on the home page, in `/service/instances` and on their own page,
with the reason and the date it began (`/status` shows the reasons for every mine, the instance page
has a "Last check" section). The registry advertises the range of mines, not their uptime, so a
failed check is only recorded (the mine stays Running, with a note); a mine is marked Unreachable
when its checks have been failing for more than 24 hours (`REGISTRY_UNREACHABLE_AFTER_HOURS`), so a
restart, a maintenance window or a passing network problem does not flag it. The next successful
check clears it. The reason on `/status` is what the registry saw, so it is the
first place to look if your mine is listed as Unreachable although it works in a browser.

## Migrating from the Node version (1.x)

- No MongoDB: the data is `resources/instances.edn`, seeded from the live registry on
  17 Nov 2026 (the 14 running instances; the 29 instances marked "Not Running" in Mongo were not
  carried over).
- No accounts, no login, no add/edit pages: `POST`, `PUT` and `DELETE` on `/service/instances`
  answer 405; changes are pull requests.
- Instances no longer carry Mongo's `_id` and `__v` (no known client reads them; the InterMine
  webapp's Java client maps `__v` to an int that defaults to 0).
- `/service/namespace?url=` prefers an exact URL match (the old substring match could answer a beta
  mine for the production URL).
- `/api-docs` (Swagger UI) redirects to `/docs`.
- `PUT /service/synchronize` uses a bearer token instead of HTTP Basic users.

See [CHANGELOG.md](CHANGELOG.md).

## License

LGPL-2.1, see [LICENSE](LICENSE).
