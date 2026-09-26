# Autocorrect Binder test fixture

This separately installed debug app supplies two synthetic protocol-v5 providers
for host instrumentation tests. It is not an app dependency and ships in no
Better FlorisBoard variant. Its exported services accept the normal provider
binding action; its control provider requires a signature permission requested
only by the host's Debug variant.

The fixture records event names, process identity, request IDs, and whether a
final request had any text. It never stores or logs text, candidates, document
content, dictionary entries, or protocol Bundles. Use synthetic input only.
