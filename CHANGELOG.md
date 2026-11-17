# Changelog

## 2.0.0 (unreleased)

A re-implementation in Clojure of the GSoC 2017 Node/Express/MongoDB registry, keeping its read API.

### Changed
- The data is a version-controlled file, `resources/instances.edn`, validated with clojure.spec at
  start-up and in CI; mines are added and changed by pull request. Seeded from the live registry's
  `/service/instances` on 17 Nov 2026 (14 running instances).
- Versions and branding are refreshed every `REGISTRY_REFRESH_MINUTES` (default 60; was daily at
  midnight), with 10 s timeouts, and every answer is validated: HTML pages (served by mines behind
  bot protection or retired mines) are no longer stored as versions; the previous value is kept and
  the problem shown on `/status`. A mine whose `/service/version/intermine` answers 200 with
  something that is not a version counts as a failed check (it was "Running").
- A failed check no longer hides a mine. Unreachable mines stay in `GET /service/instances` and on
  the home page, with their last known versions and branding. Each instance has a new `last_check`
  object `{time, outcome ("ok"|"failed"), reason, since}` (reasons: `timeout`, `connection refused`,
  `http NNN`, `bot-protection page`, `html instead of json`, `invalid version string`, ...), kept in
  the cache. `status` is `"Running"` (last check ok), `"Unreachable"` (two consecutive failed
  checks; the first failure is recorded in `last_check` but keeps `"Running"`) or
  `"Not checked yet"`; `"Not Running"` is gone (an old cache reads as `"Unreachable"`). The seeded
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
- New: `GET /health`, Dockerfile, Compose example, GitHub Actions CI.

### Removed
- MongoDB, user accounts, login, the add/edit/delete pages and the write endpoints
  (`POST /service/instances`, `PUT` and `DELETE /service/instances/{id}` answer 405).
- Mongo's `_id` and `__v` in instances (the one difference in the instance JSON).
- The Node implementation (`app.js`, `bin/`, `routes/`, `models/`, `scheduled/`, `views/`,
  `public/`, `package.json`, `swagger.json`, `createUser.js`, `initRegistry.js`); it remains in the
  git history (last commit 37f67f2).

## 1.x

The Node/Express/MongoDB registry, 2017-2020 (Leonardo Kuffo, GSoC 2017, and contributors).
