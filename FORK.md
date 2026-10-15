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

## Scope

Penpot owns the editable file, rendering and normal collaboration. Saitoc's
product services own proposal approval, spend, publication and billing.
DeckDoc is an interchange format, not a second editable authority.

This branch is a local integration experiment. Source availability does not
establish production hosting, identity federation, tenant isolation, import
fidelity or editable PPTX acceptance. Runtime receipts are maintained outside
this repository in the bounded adoption pack.

## Upstreaming

Source-link changes are deployment-specific. Engine transaction and historical
revision changes will be described here with their supported contracts and
backend test commands when integrated. They must not alter the collaborative
catch-up behavior of the ordinary update-file RPC.
