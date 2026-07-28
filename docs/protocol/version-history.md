# Autocorrect protocol version history

## Version 4

Version 4 is the current external-provider contract. Its checked-in API
signature and canonical Bundle shapes are compatibility fixtures under
`lib/autocorrect-api/src/test/resources/api`.

The host currently requires an exact protocol version during discovery.
Unknown enum values and optional fields use documented safe defaults where
forward-compatible decoding is possible; malformed required data and
out-of-bounds collections are rejected or clamped before allocation.

Version 4 includes:

- bounded session and suggestion messages;
- tap and glide input traces;
- candidate accept, revert, and removal callbacks;
- ordered session finish with an optional final snapshot;
- declarative provider UI and short-lived document descriptors;
- controlled host user-dictionary queries and mutations.

## Change rules

- Never renumber or reuse a message, field, action, metadata, enum, or semantic
  meaning within a released version.
- Additive optional fields need round-trip, absent-field, malformed-field, and
  golden-fixture tests.
- A source or binary API change requires deliberate review of the public API
  snapshot and provider migration.
- An incompatible wire change requires a new version and a tested coexistence
  plan before the host advertises it.
- Payload limits may become stricter only when old valid providers remain safe;
  otherwise version the change.

Do not update snapshots simply because a test failed. Inspect the generated
candidate under the module build reports, describe the compatibility effect,
and update this history in the same change.
