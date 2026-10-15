# Saitoc Penpot fork

This fork starts at Penpot 2.18.3, upstream commit
`ff7a66f51938b2293260ea27a64e2c4e0833b5e3`.

Source: https://github.com/tudorsaitoc/penpot/tree/saitoc-2.18.3

## Patch set

The workspace and dashboard repository links point to this source branch.
The dashboard changelog link opens these patch notes. Upstream copyright
and MPL-2.0 notices remain in covered source files.

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
