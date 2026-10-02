# Keyboard window layout

[`ImeWindowController`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/window/ImeWindowController.kt)
owns the move/resize editor state and saves the window config for the current
form factor. [`ImeWindow`](../../../app/src/main/kotlin/dev/patrickgold/florisboard/ime/window/ImeWindow.kt)
renders the resulting window and docking indicator.
System-bar setup follows context wrappers to the hosting activity or IME service.

The inner window provides keyboard and Smartbar row heights from the current
computed spec, including live resizing. Inline-autofill chip height uses the
same Smartbar height and density, minus its existing vertical margins.

Releasing a floating move in the dock zone switches to fixed mode and closes
the move editor immediately. The dock zone ends at `dockToFixedHeight`; the
indicator and release decision use that same threshold. Docking keeps the saved
floating position, so switching back can restore it.

Releasing a move above the dock zone saves the floating position and leaves
editing enabled. Fixed moves and fixed or floating resizes likewise save their
matching size and position while keeping editing enabled. Saving preferences
may be asynchronous; the editor state must not wait for that write.
Queued saves stay with the form factor they were requested for, even if the
window changes while a write is pending. Other saved form factors stay unchanged.

Reset restores the selected mode's default size. Floating resets keep a saved
position that still fits onscreen. Other saved modes and form factors stay unchanged.

The window-controller JVM tests cover resets, docking, move/resize paths, and
queued saves across form-factor changes:

```shell
./gradlew :app:testDebugUnitTest
```

Run a device check when changing gesture dispatch or rendering, which these
controller tests do not exercise.
