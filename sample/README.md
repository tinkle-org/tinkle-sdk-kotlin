# Minified SDK consumer smoke

This app runs `SmokeChecks.run()` against the release SDK AAR. The app body
exercises the optimized SDK; the instrumented test invokes that stable entry
point. Both app build types enable R8 shrinking, optimization, and obfuscation.

## Why reference-derived keeps are needed

AGP 8.13's application-module androidTest R8 pass disables shrinking,
optimization, and obfuscation **of the test code**, and applies the target app's
mapping. It does not make test references roots for the earlier **app** R8 pass.
Consequently, methods needed only by the runner can disappear from app-owned
runtime dependencies. Applying a mapping cannot restore removed code.

For example, AndroidX Test runner 1.7.0 / monitor 1.8.0 use Kotlin runtime
classes shared with the app, including lazy initialization and lambda base
constructors. Package-wide keeps would conceal SDK consumer-rule regressions.

`GenerateInstrumentationKeeps` in buildSrc runs R8 8.13.19 TraceReferences
(matching AGP 8.13.2, with Kotlin 2.3 metadata support). The sample registers it
with public variant/artifact APIs:

* **Targets:** the app variant's `ScopedArtifacts.Scope.ALL` `CLASSES`, including
  its compiled sources, SDK AAR, and runtime dependencies.
* **Sources:** the androidTest variant's `Scope.ALL` `CLASSES`, including compiled
  test sources **and the complete runtime dependency graph**, not just the test
  entry class. This includes the AndroidX Test runner/monitor dependencies.
* **Libraries:** the Android boot classpath, optional SDK `android.test` jars for
  the runner's JUnit3 adapters, and the test compile classpath (including its
  compile-only Error Prone annotations).

Class ownership is resolved before tracing. A class present in both app and
test belongs to the app: it stays a target and is removed from sources. Otherwise
TraceReferences would exclude it from the target set, missing precisely the
shared Kotlin runtime problem. Libraries exclude all app/test-owned classes;
boot libraries win over overlapping compiler stubs. Conflicting definitions
within either program input fail. Input jars/directories are normalized into
sorted class-only jars under the task's `build/tmp` directory.

TraceReferences derives exact class/member rules, including inherited members
and superclass constructor calls. No source-level guesses about Kotlin runtime
implementation names are needed. `TraceReferencesCheckConsumer` forwards to the
keep-rule consumer and rejects unresolved references. All warnings/errors fail
generation. There are five exact platform-method exceptions configured in the
sample: monitor's `ExposedInstrumentationApi.execStartActivity` calls reference
hidden framework methods stripped from public SDK stubs. Their exact signatures
are visible in `sample/build.gradle.kts`, and each encountered exception is logged
at `--info`. There is no class/package-wide missing-reference suppression.

The generated `sample/build/generated/instrumentation-keeps/<variant>.pro` is
added to the **app variant's** `proguardFiles` using its task output provider.
The graph is:

```text
app class compilation / dependency class artifacts
  -> test class compilation (uses the app compile jar)
  -> generate<Variant>InstrumentationKeeps
  -> minify<Variant>WithR8
  -> minify<Variant>AndroidTestWithR8 (uses app mapping)
```

Generation consumes no app mapping, minified classes, or APK, so there is no
compile -> app R8 -> generation cycle. It runs for the variant selected by
`smokeBuildType` (`debug` by default, `release` also supported). This uses the
public API rather than Keeper's private AGP hooks. Ordinary SDK assembly does
not include the sample. `consumer-rules.pro` is unchanged and ships no test
keeps. The only SDK-app entry generated for the current test is `SmokeChecks.run`;
the test dependencies do not turn SDK internals into tracing sources.

## Local verification

Use JDK 17 and set `ANDROID_HOME`. Build/stage the same-release native artifacts
and binding using the core repository's SDK CI helper before SDK assembly.
After building the root release AAR:

```sh
export TINKLE_SDK_TEST_AAR="$PWD/build/outputs/aar/tinkle-sdk-kotlin-release.aar"
./gradlew -p buildSrc test
./gradlew :sample:assembleDebug :sample:assembleDebugAndroidTest \
  -PtinkleSdkTestAar="$TINKLE_SDK_TEST_AAR"
./gradlew :sample:assembleRelease :sample:assembleReleaseAndroidTest \
  -PsmokeBuildType=release -PtinkleSdkTestAar="$TINKLE_SDK_TEST_AAR"
TINKLE_VERIFY_BUILT_APKS=1 python3 -B -m unittest discover -s tests -v
```

The exported `TINKLE_SDK_TEST_AAR` enables the Android task-graph checks and
selects the AAR for sample builds. With both APK variants built,
`TINKLE_VERIFY_BUILT_APKS=1` also enables the DEX checks in the same test run.

The buildSrc tests compile real Java fixtures and run TraceReferences. They
cover direct references, inherited methods, superclass constructors, shared
runtime ownership, jar/directory equivalence, conflicting definitions, missing
classes/methods, and exact platform-stub exceptions. The opt-in Python tests run
real AGP dry-run graphs for both build types and inspect defined APK DEX members
using build-tools 36.0.0 `dexdump`. They account for inherited members and mapped
descriptor types, check every generated rule against the merged app R8 config,
and verify removed code plus obfuscated class names. Without the opt-in variables,
the Android-dependent Python checks are skipped.

Generation is bytecode-reference analysis; it does not discover arbitrary
reflection or JNI string lookups. Keep those contracts explicitly where owned
(the sample entry rule and SDK consumer rules). Installed runner startup and
native behavior still require connected tests on CI; APK/DEX checks do not
replace those tests.

## Sources

* [Keeper's explanation of the reachability gap and TraceReferences approach](https://slackhq.github.io/keeper/)
  and [Android issue 126429384](https://issuetracker.google.com/issues/126429384).
* [AGP ScopedArtifactsOperation API](https://developer.android.google.cn/reference/tools/gradle-api/8.13/com/android/build/api/variant/ScopedArtifactsOperation)
  and [Variant API (`proguardFiles`)](https://developer.android.google.cn/reference/tools/gradle-api/8.13/com/android/build/api/variant/Variant).
* [R8 8.13.19 TraceReferences](https://r8.googlesource.com/r8/+/refs/tags/8.13.19/src/main/java/com/android/tools/r8/tracereferences/TraceReferences.java),
  [CheckConsumer](https://r8.googlesource.com/r8/+/refs/tags/8.13.19/src/main/java/com/android/tools/r8/tracereferences/TraceReferencesCheckConsumer.java),
  and [KeepRules](https://r8.googlesource.com/r8/+/refs/tags/8.13.19/src/main/java/com/android/tools/r8/tracereferences/TraceReferencesKeepRules.java).
* [AndroidX's hidden Instrumentation bridge rationale](https://github.com/android/android-test/blob/main/runner/monitor/java/androidx/test/internal/runner/hidden/ExposedInstrumentationApi.java).
