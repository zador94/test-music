# Tests and assemble after every change

When you change application code (Kotlin, player, metronome, stems, Room, ViewModel):

1. Add or extend JUnit tests under `app/src/test` for the new behavior or bugfix. Tests assert behavior, not that a file exists.
2. Run **both** of these and do not call the slice done if either fails:

```
./gradlew clean :app:assembleDebug :app:testDebugUnitTest
```

`:app:testDebugUnitTest` compiles unit-test + main used by tests, but **does not** prove Android Studio / `assembleDebug` will compile. Visibility (`private var` vs `var` + `private set`), resources, manifest, Compose previews — ловит только полная сборка APK.

3. If Compose / ExoPlayer / ONNX cannot be exercised on the JVM, test the pure logic (math, parse, clock, detector). Say in the report what still needs a device.

Skip the test run only for README, gradle.properties, copyright, or docs-only edits with no app code. If you touched `app/src/main`, still run `:app:assembleDebug`.

Project policy: `/cursor/stores/self/principles/run-tests-after-changes.md`
