# Tests after every change

When you change application code (Kotlin, player, metronome, stems, Room, ViewModel):

1. Add or extend JUnit tests under `app/src/test` for the new behavior or bugfix. Tests assert behavior, not that a file exists.
2. Run `./gradlew :app:testDebugUnitTest` and do not call the slice done if it fails.
3. If Compose / ExoPlayer / ONNX cannot be exercised on the JVM, test the pure logic (math, parse, clock, detector). Say in the report what still needs a device.

Skip the test run only for README, gradle.properties, copyright, or docs-only edits with no app code.

Project policy: `/cursor/stores/self/principles/run-tests-after-changes.md`
