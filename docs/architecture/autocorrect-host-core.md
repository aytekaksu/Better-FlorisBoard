# Autocorrect host architecture

## Decision

The external-autocorrect host uses a functional core with an Android shell.
Protocol payloads and Android objects stay at the boundary; provider discovery,
binding, session, request, cancellation, stale-reply, finish, and provider
health decisions belong to the platform-neutral host core.

This is an incremental replacement of imperative manager state, not a second
host. Suggestion request identity, supersession, cancellation, reply admission,
and provider circuit health are already reducer-owned through
`AutocorrectSuggestionRequestCoordinator`. Android discovery, binding handles,
session payload transport, finish acknowledgements, provider UI, and dictionary
work remain in `AutocorrectPluginManager`.

During migration, a rule moves only when its current behavior has a
characterization test and the Android facade can preserve its public call
surface. Do not describe a rule as core-owned until the manager's competing
decision has been removed.

## Ownership

| Layer | Owns | Must not own |
| --- | --- | --- |
| `lib/autocorrect-host-core` | Immutable state, typed IDs, events, effects, invariants, circuit policy | Android, coroutines, Binder, payload text, candidates |
| `lib/autocorrect-api` | Provider-facing models, limits, message IDs, Bundle codecs | Host lifecycle decisions |
| Android host adapter | Package discovery, `ServiceConnection`, `Messenger`, coroutine scope, transport payloads | Duplicate request admission or stale-reply rules |
| Feature facade | Existing calls from NLP, keyboard, glide, settings, and IME lifecycle | Transport details |

The core transition is:

```text
(HostState, HostEvent) → HostTransition(new state, ordered effects)
```

The adapter installs the returned state before executing effects. Any
asynchronous result returns as a token-bearing event through the same
serialized dispatcher. An effect is a command, never a callback.

## Invariants

- A binding epoch identifies one attempt to communicate with one provider.
- A session belongs to one provider, editor generation, and configuration.
- At most one suggestion request is current; issuing another retires and
  cancels the old lease.
- A reply is publishable only when provider, binding epoch, session, request,
  and editor generation all match.
- Retired request history is bounded and is used only to classify late replies.
- Provider health is per provider. Repeated transport failures open a circuit;
  one half-open probe may test recovery.
- Finish acknowledgements keep only the binding they require. Destruction
  clears all retained work and emits resource release last.
- State validation runs after every transition in tests and debug integration.

Core state contains no request text, candidate, dictionary entry, touch point,
package metadata beyond the opaque provider ID, `Throwable`, clock, or Android
handle.

## Effect execution

Effects are processed in order:

- discovery and binding effects start Android work;
- session/request effects serialize bounded API payloads;
- cancellation and unbind effects are best effort and idempotent;
- accepted replies release the separately held payload to the feature facade;
- fallback effects let built-in NLP continue;
- health effects update diagnostics and schedule monotonic recovery;
- ignored/rejected events produce content-free diagnostics;
- resource release cancels scopes, receivers, timers, and transport.

Binder calls must not occur while a core-state lock is held. A synchronous
failure is converted to an event after the new state is installed.
The Android adapter's separate monitor keeps the physical lease and final
content check with each send, and START admission with its wire send.

## Safe migration sequence

1. Characterize the current facade behavior.
2. Add or extend a pure event and its invariant tests.
3. Translate one manager decision to the core.
4. Execute only the effects for that decision through the adapter.
5. Delete the replaced volatile field or derive it from core state.
6. Run focused tests, minified compilation, and the complete quality gate.

Do not mirror a migrated decision indefinitely. Temporary comparison assertions
are acceptable in debug builds, but the reducer must become the authority
before the migration slice is complete.

## Current migration boundary

The reducer now owns discovery revisions, provider selection, UI-only binding
demand, binding epochs, editor generations, session admission, pending finishes,
request leases, and the provider circuit. Android executes its ordered effects
and feeds token-bearing callbacks back to the reducer. A queued START is checked
again before Binder send; a session that never sends START cannot emit FINISH.

The manager keeps Android Binder handles, provider UI/document leases, and
bounded wire payloads, but no second binding or session decision state. Normal
session close retains the binding until FINISH is acknowledged. Changing the
selected provider sends FINISH best-effort, then unbinds immediately so a
provider that never acknowledges cannot block the new selection. Final editor
content is captured at session close in a small runtime-only map, never in the
effect queue or diagnostics. Repeated non-private editor teardown keeps that
closure snapshot. A private or ineligible editor observed at the manager-locked
send decision gets an empty final request; privacy, connection loss, and
incompatible session events clear queued snapshots.
An open typing circuit can still bind for a visible provider page, but it does
not start or admit typing again until cooldown.

## Verification

```shell
./gradlew :lib:autocorrect-host-core:test
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleBeta
```

State-machine tests should cover table-driven examples and generated event
sequences. The main generated invariant is that no old epoch, session, request,
or editor generation can produce an accepted reply.
