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
| `REGISTRY_REFRESH_MINUTES` | `60` | refresh every mine this often; `0` disables the schedule. The first run is 10 s after start-up. |
| `REGISTRY_CACHE_DIR` | `./cache` (`/cache` in Docker) | where `registry-cache.edn` keeps the last refreshed values, so a restart does not lose them |
| `REGISTRY_ADMIN_TOKEN` | unset | enables `PUT /service/synchronize[/{id}]` with `Authorization: Bearer <token>`; unset, those answer 405 |
| `REGISTRY_INSTANCES_FILE` | the bundled `resources/instances.edn` | path of an instances file to use instead |
| `REGISTRY_BASE_URL` | `https://registry.intermine.org` | absolute links on the pages and in the API docs |

## API

Documented on the running server at [`/docs`](https://registry.intermine.org/docs) (generated from
the route table in `src/clj/intermine_registry/api.clj`). In short:

- `GET /service/instances`: running production instances, sorted by name; `?mines=all|dev|prod`,
  `?q=` (organism, description word or part of the name).
  `{"instances": [...], "statusCode": 200, "executionTime": "11/17/2026, 2:10:58 AM"}`
- `GET /service/instances/{id|namespace|name}`: `{"instance": {...}, "statusCode": 200, ...}`;
  404 `{"statusCode": 404, "errorMsg": "Not Found", "message": ...}`
- `GET /service/namespace?url=<mine url>`: `{"statusCode": 200, "namespace": "flymine"}`
- `GET /{namespace}`: 303 redirect to the mine
- `GET /health`: `{"status": "ok", "instances": 14, "running": 14, "lastRefresh": ...}`
- `PUT /service/synchronize`, `PUT /service/synchronize/{id}`: refresh now (admin token)
- `POST/PUT/DELETE /service/instances[/{id}]`: 405, the registry is read-only

Pages: `/` (the mines, with a filter), `/instances/{namespace}`, `/docs`, `/status` (the last
refresh of each mine: when, what changed, what failed), and the Galaxy hand-over pages
`/im-to-galaxy?GALAXY_URL=` and `/galaxy-to-im?URL=`.

## How the refresh works

For each mine, in parallel with at most 4 mines at a time and 10 s timeouts, the server calls
`{url}/service/version/intermine`, `/service/version/release`, `/service/version` and
`/service/branding`, with the rules of the Node app's `scheduled/automaticUpdate.js`:

- `status` is `"Running"` when `/service/version/intermine` answers 200 with a version, else
  `"Not Running"`; `/service/instances` lists only running mines (as before);
- the version answers are cleaned (quotes, brackets and slashes removed) and a version equal to
  the API version is blanked (a mine answering every path with the same page);
- `colors` and `images` come from `properties.colors` and `properties.images` of the branding JSON;
- `last_time_updated` is set; `organisms` and every other hand-maintained field are not touched.

New in 2.0: every answer is validated (`api_version` an integer string, versions short strings, not
HTML pages or unsubstituted `@TOKENS@`); a rejected answer keeps the previous value and is
reported on `/status`. The refreshed values go to the cache file, never back into
`instances.edn`; the values in `instances.edn` are only the initial ones.

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
