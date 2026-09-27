# Keyboard window layout

[`ImeWindowController`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/window/ImeWindowController.kt)
owns the move/resize editor state and saves the window config for the current
form factor. [`ImeWindow`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/window/ImeWindow.kt)
renders the resulting window and docking indicator.

Releasing a floating move in the dock zone switches to fixed mode and closes
the move editor immediately. The dock zone ends at `dockToFixedHeight`; the
indicator and release decision use that same threshold. Docking keeps the saved
floating position, so switching back can restore it.

Releasing a move above the dock zone saves the floating position and leaves
editing enabled. Fixed moves and fixed or floating resizes likewise save their
matching size and position while keeping editing enabled. Saving preferences
may be asynchronous; the editor state must not wait for that write.

The window-controller JVM tests cover docking and representative move/resize paths:

```shell
./gradlew :app:testDebugUnitTest
```

Run a device check when changing gesture dispatch or rendering, which these
controller tests do not exercise.
