# Autocorrect protocol version history

## Version 5

Version 5 is the current external-provider contract. Its checked-in API
signature and canonical Bundle shapes are compatibility fixtures under
`lib/autocorrect-api/src/test/resources/api`.

The host currently requires an exact protocol version during discovery.
Unknown enum values and optional fields use documented safe defaults where
forward-compatible decoding is possible; malformed required data and
out-of-bounds collections are rejected or clamped before allocation.

Version 5 retains the version 4 surface and adds:

- request-scoped `MSG_CANCEL` payloads containing the required suggestion
  request ID, so delayed cross-thread Binder delivery cannot cancel newer work;
- an explicit `Unhandled` reply when a suggestion targets a session which is
  no longer active, rather than silently abandoning the host request.

The host and provider must be upgraded together. Exact-version discovery means
a version 5 host does not bind a version 4 provider, and a version 4 host does
not bind a version 5 provider.

## Version 4

Version 4 included:

- bounded session and suggestion messages;
- tap and glide input traces;
- candidate accept, revert, and removal callbacks;
- ordered session finish with an optional final snapshot;
- declarative provider UI and short-lived document descriptors;
- controlled host user-dictionary queries and mutations.

Its `MSG_CANCEL` payload was empty. Because cancellation and suggestion
messages can originate on different host threads, Android could deliver an old
cancel after a newer suggestion. The base provider then cancelled its newest
job without replying, leaving the host's newer request pending until a
lifecycle reset. Version 5 replaces that ambiguous operation.

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
