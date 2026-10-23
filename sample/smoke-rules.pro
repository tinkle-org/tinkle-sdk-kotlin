# Only the test entry point is kept. SDK callees are optimized using AAR consumer rules.
-keep class com.tinkle.sdk.smoke.SmokeChecks { public static void run(); }
# Test APK (minifyDebugAndroidTestWithR8) also minifies AndroidX Test infrastructure:
# without these keeps startup crashes in AppComponentFactoryRegistry with
# NoSuchMethodError in minified p.f. Sample-only; never ship in consumer-rules.pro.
-keep class androidx.test.platform.** { *; }
-keep class androidx.test.runner.** { *; }
-keep class androidx.test.monitor.** { *; }
# The app mapping folds kotlin.jvm.internal.Intrinsics to p.f and R8 removes
# overloads such as checkNotNullExpressionValue(Object, String); the test
# runner calls into it at startup and crashes when it is absent. Sample-only.
-keep class kotlin.jvm.internal.Intrinsics { *; }
