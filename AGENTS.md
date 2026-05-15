# Repository Guidelines

## Project Structure & Module Organization
`RaceNav` is a single-module Android app. Main code lives in `app/src/main/java/com/andreykoff/racenav`, UI layouts and drawables in `app/src/main/res`, and bundled data such as `tile_catalog.json` in `app/src/main/assets`. App configuration is split across `app/src/main/AndroidManifest.xml`, `app/build.gradle.kts`, and root Gradle files. CI lives in `.github/workflows/build.yml`. The `server/` directory contains deployment-side tile catalog and nginx config, not Android runtime code.

## Build, Test, and Development Commands
Use the Gradle wrapper from the repository root:

- `./gradlew app:assembleDebug` builds the signed debug APK used by CI.
- `./gradlew app:installDebug` installs the debug build on a connected device or emulator.
- `./gradlew app:lintDebug` runs Android lint for the main development variant.
- `./gradlew app:testDebugUnitTest` runs JVM unit tests if/when test sources are added.
- `./gradlew app:connectedDebugAndroidTest` runs instrumentation tests on a connected device.
- `./gradlew app:clean` removes build outputs.

## Coding Style & Naming Conventions
Follow Kotlin official style (`kotlin.code.style=official`) with 4-space indentation and standard Android/Kotlin naming: `PascalCase` for classes, `camelCase` for methods and properties, and `UPPER_SNAKE_CASE` for constants. Keep package names under `com.andreykoff.racenav`. Match existing file naming: one main class per file, fragment classes ending in `Fragment`, services ending in `Service`, and XML resources in lowercase snake case such as `fragment_map.xml` or `ic_my_location.xml`.

## Testing Guidelines
There are currently no committed `app/src/test` or `app/src/androidTest` source sets, so new features should at minimum pass `app:lintDebug` and `app:assembleDebug`. Add JVM tests under `app/src/test/kotlin` for parser, geometry, and repository logic; add device tests under `app/src/androidTest/kotlin` for UI and integration flows. Name tests after behavior, for example `GpxParserTest` or `importsOfflineMapFile()`.

## Commit & Pull Request Guidelines
Recent history uses Conventional Commit prefixes such as `fix:` and `feat:` with short, imperative summaries. Keep commits focused and specific, for example `fix: avoid map arrow lag during drag`. PRs should describe user-visible impact, list verification steps, link the issue/task when available, and include screenshots or screen recordings for UI changes. Call out changes to signing, Firebase, networking, or map source configuration explicitly.

## Security & Configuration Tips
Do not commit real secrets, local SDK overrides, or machine-specific config changes. Review edits touching `local.properties`, `gradle.properties.local`, keystore handling, Firebase plugins, or `network_security_config.xml` carefully before opening a PR.
