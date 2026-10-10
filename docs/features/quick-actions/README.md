# Quick Actions

Overflow uses `QuickActionArrangement` to show the dynamic actions that did not
fit in the toolbar, in saved order. Sticky and hidden actions stay out; empty
lists and stale toolbar counts are safe.

The editor reads the latest pending action order when it opens. A header or
outside-tap close waits for the preference write on an I/O dispatcher before
closing. If saving fails, it stays open and the next close retries. Other
visibility changes (such as hiding the keyboard) use the disposal fallback,
which queues the final order in a process-scoped writer with two retries;
a process killed before that write finishes can still lose the last forced edit.

Writes are ordered. Backup export waits for submitted edits and holds the same
barrier while taking its preference snapshot. A backup taken while the editor
is still open includes the last committed order, not its in-memory draft; one
taken after a submitted close waits for that save. Reset and restore also run
behind the ordered writer. Reset and replace-restore supersede queued edits;
merge-restore first saves them so the merge uses the latest order. A completed
replacement invalidates an already-open draft; a failed restore only does so
if rollback failed. The editor then reloads the current order instead of
overwriting it. Logs contain only a failure class, never actions.

Run arrangement, queue and drag-state tests with `./gradlew :app:testDebugUnitTest`.
Run `./gradlew qualityGate` before merging a quick-action change.
