# Saitoc Penpot fork

This fork starts at Penpot 2.18.3, upstream commit
`ff7a66f51938b2293260ea27a64e2c4e0833b5e3`.

Source: https://github.com/tudorsaitoc/penpot/tree/saitoc-2.18.3

## Patch set

The workspace and dashboard repository links point to this source branch.
The dashboard changelog link opens these patch notes. Upstream copyright
and MPL-2.0 notices remain in covered source files.

`render-wasm/build` accepts a native Emscripten installation on PATH, or an
`EMSDK` directory, without requiring the container-only `/opt/emsdk` path.
`EM_CACHE` can point to a bounded task cache.

`docker/images/Dockerfile.saitoc` packages locally compiled frontend, backend
and exporter bundles. Its runtime bases are immutable upstream 2.18.3 image
digests. The frontend's upstream application files are removed before our
bundle is installed; exporter runtime dependencies use the unchanged pinned
lockfile. A Dockerfile-specific ignore file excludes source and compiler caches.
The build requires frontend/target/dist, backend/target/dist and
exporter/target from the upstream module build scripts.

Native PDF and bitmap requests accept `revn` and `vern` on each `exports`
entry. The exporter preserves both through grouping and the `render.html`
query. The native render application reads that exact file revision through
`get-file-revision` and extracts its page; it never substitutes the latest
page. Ordinary requests without revision arguments retain upstream behavior.
Grouping separates files, pages and incarnations so a batch cannot silently
render one revision using another entry's context. SVG historical export is
outside this extension.

Regression command from `exporter`: `pnpm run build:test` followed by
`node target/tests/test.js --focus exporter-tests.export-revision-test`.

## Engine action and revision contracts

`issue-file-action-grant` accepts `id`, `request-id`, `session-id`, `revn`,
`vern`, `changes`, and optional `features`. The actor is the authenticated
profile. It requires current file edit authority and returns a grant ID and
SHA-256 of the canonical decoded typed changes. This is an engine capability;
it cannot approve product spend, publication, evidence or rights.

`commit-file-action` takes the same arguments plus `grant-id` and
`args-sha256`. It locks the file, current authority rows and grant. Exact
revision/incarnation comparison, mutation, grant consumption and receipt all
share the Penpot transaction. The receipt returns the saved revision. Matching
replay returns that receipt without another mutation, but still checks current
authority, grant revocation and incarnation. Different arguments or a changed
file revision are denied. Approved stale revision errors return HTTP 409;
ordinary collaborative `update-file` keeps its existing catch-up semantics.
`revoke-file-action-grant` accepts `id` and `grant-id`.

Unused capabilities expire after 15 minutes. Exact replay receipts last 30 days;
issuance removes expired rows. Migration 0154 creates the capability table and
tags new snapshots/change logs with `vern`. Legacy history without a provable
incarnation is not guessed.

`get-file-revision` accepts `id`, `revn`, `vern`, and optional `features`.
It checks the same read authority and feature negotiation as `get-file`.
Historical data comes from an incarnation-tagged snapshot plus contiguous
changes. Its fully realized `data.pages-index` contains the requested saved
page objects. A retention gap returns `revision-unavailable`; there is no
latest-revision fallback.

Backend regression command from `backend` with an isolated test database and
Redis configured through `PENPOT_TEST_DATABASE_*` and `PENPOT_TEST_REDIS_URI`:
`clojure -M:dev:test --focus backend-tests.rpc-file-actions-test --focus backend-tests.rpc-file-test --focus backend-tests.rpc-file-snapshot-test`.

## Guided workspace

`guided=fable` on the workspace hash route mounts the controlled agent plugin
in a dock on file load and hides the ordinary header/sidebar/presence chrome
only for that path. The fixed experiment manifest is
`http://localhost:3041/manifest.json`; production must replace that build-time
constant with a reviewed deployed origin. Only read and download permissions
are admitted. No query parameter can select an arbitrary plugin origin.

The plugin receives `fable.prepareTitle`, `fable.approveTitle`, and
`fable.replayTitle`. These call the authenticated native grant RPCs and update
the canvas only after a saved receipt. Native undo records the inverse typed
change; replay does not apply it twice. The same dock can show the controlled
fixture export spread from the same-origin product shell. The transition name
currently covers the viewport, not an isolated WASM slide; exact cover geometry
and proxy websocket behavior still require runtime acceptance.

The optional snapshot label handoff also omits a missing label rather than
passing explicit null into the internal snapshot schema. Its regression
creates and restores an unnamed snapshot; explicit null remains invalid.

## Scope

Penpot owns the editable file, rendering and normal collaboration. Saitoc's
product services own proposal approval, spend, publication and billing.
DeckDoc is an interchange format, not a second editable authority.

This branch is a local integration experiment. Source availability does not
establish production hosting, identity federation, tenant isolation, import
fidelity or editable PPTX acceptance. Runtime receipts are maintained outside
this repository in the bounded adoption pack.

## Upstreaming

Source links and guided chrome are deployment-specific. The engine action
transaction, exact historical read and revision-threaded native export are
separate upstreamable changes. Their tests cover both the added path and the
existing file/snapshot behavior. No production acceptance is implied.
