# Vendored front-end assets

Third-party assets are served from this application, never from a CDN. A page that pulls its
script from a third party is a page whose render depends on that party being reachable, and it
puts an unpinned third-party script into an application handling tenancy-scoped financial data.

There is no npm, no bundler and no build-time JavaScript toolchain here — that is the point of
choosing htmx over a framework.

| File | Version | Source | SHA-384 (base64) |
|---|---|---|---|
| `htmx.min.js` | 2.0.4 | `https://unpkg.com/htmx.org@2.0.4/dist/htmx.min.js` | `HGfztofotfshcF7+8n44JQL2oJmowVChPTg48S+jvZoztPfvwD79OC/LTtG6dMp+` |

To verify a file matches the row above:

```sh
openssl dgst -sha384 -binary htmx.min.js | openssl base64 -A
```

When upgrading: download, re-run the command, update the version and hash in the same commit as
the file. A hash that no longer matches its file is worse than no hash, because it reads as
verified.
