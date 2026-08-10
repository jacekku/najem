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

## Fonts

`fonts/` vendors two families from Google Fonts, self-hosted for the same CDN-independence reason
as htmx above: `Instrument Sans` and `IBM Plex Mono`, pulled 2026-08-07 from
`fonts.googleapis.com/css2` at the versions Google was serving that day (Instrument Sans v4,
IBM Plex Mono v20).

`latin` and `latin-ext` subsets are vendored for both families. `latin-ext` is not optional here:
it carries `ł ń ś ż ź ć ę ą ó`, the Polish diacritics this application's copy uses, none of which
are in the `latin` subset. Shipping only `latin` would not fail loudly — it falls back to a system
font mid-word, invisible on an English test fixture and easy to miss in review.

The two families are vendored differently because they are shipped differently by Google:

- **Instrument Sans is variable.** Google serves one physical file per subset covering the whole
  400-700 weight axis, so only 2 files are vendored:
  `instrument-sans-variable-latin.woff2` and `instrument-sans-variable-latin-ext.woff2`. Naming a
  weight in the filename would have been a lie — an earlier version of this vendoring committed
  the same bytes four times under four weight-suffixed names, which cost ~90KB of duplicate
  content in the repo and meant a page using two weights (this design system uses 400/500/600/700)
  downloaded the same file twice, since the browser caches by URL and each weight had its own URL.
  `base.css` declares each subset once, with `font-weight: 400 700`, not once per weight.
- **IBM Plex Mono is static.** Google serves a distinct file per weight, so it stays vendored and
  declared per-weight: 6 files (400/500/600 × latin/latin-ext).

8 `.woff2` files in total.

Both families are licensed SIL Open Font License 1.1; the combined licence text for both is in
`fonts/OFL.txt`, with a header line naming which block covers which family. The OFL requires the
licence to travel with the font files, which is why it is vendored alongside them rather than
linked.
