# Vendored front-end assets

Same rule as the main application: third-party assets are served from here, never from a CDN. A
page that pulls its script from a third party is a page whose render depends on that party being
reachable.

This is a second copy rather than a shared file because FakeBank is a separate deployment with its
own classpath — a shared asset would mean one of the two applications serving something it does
not package.

| File | Version | Source | SHA-384 (base64) |
|---|---|---|---|
| `htmx.min.js` | 2.0.4 | `https://unpkg.com/htmx.org@2.0.4/dist/htmx.min.js` | `HGfztofotfshcF7+8n44JQL2oJmowVChPTg48S+jvZoztPfvwD79OC/LTtG6dMp+` |

To verify a file matches the row above:

```sh
openssl dgst -sha384 -binary htmx.min.js | openssl base64 -A
```

The hash above was verified against this copy when it was vendored, not assumed from the source it
was copied from. A hash that no longer matches its file is worse than no hash, because it reads as
verified.
