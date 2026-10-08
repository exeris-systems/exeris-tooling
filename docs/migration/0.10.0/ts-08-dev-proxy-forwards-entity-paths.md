---
title: "0.10.0 migration step: `exeris-codegen-ts`: `npm start` proxies the entity paths through `proxy.conf.js`"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-08
---

### `exeris-codegen-ts`: `npm start` proxies the entity paths through `proxy.conf.js`

The scaffold of an app with a backend writes `proxy.conf.js` in place of `proxy.conf.json`, and
`package.json` starts `ng serve --proxy-config proxy.conf.js`. The emitted services call
`apiBasePath` followed by the entity path (`/orders` by default), and the single `/api` rule in
`proxy.conf.json` matched none of those requests, so `npm start` reached no backend.

`proxy.conf.js` has one rule per entity path, each forwarding to `http://localhost:8443`. An
entity's API path is also its page route, so every rule has a `bypass`: a request whose `Accept`
header includes `text/html` is a browser navigation, and the dev server answers it with
`index.html`. A deep link or a refresh on `/orders` loads the app, and the list page's `HttpClient`
call to `/orders` reaches the kernel application.

**What to do.** Both files are seeds, so a regeneration (L1) writes `proxy.conf.js` and keeps the
`package.json` and `proxy.conf.json` you have; the run says so when it writes the proxy beside a
kept `package.json`. Change the `start` script to `ng serve --proxy-config proxy.conf.js`, move
any change you made to `proxy.conf.json`, such as the target port, into `proxy.conf.js`, and delete
`proxy.conf.json`. The generator no longer owns it and does not delete it. `proxy.conf.js` is kept
on later runs too, so an entity added afterwards needs its rule added by hand, or the file deleted
so the next run writes it with every entity. **If you ran `exeris:detach` (L2),** the scaffold is
yours: adopt `proxy.conf.js` from a fresh generation if you want the bypass.
