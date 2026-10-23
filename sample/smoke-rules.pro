# Stable test entry point. SDK callees are optimized using AAR consumer rules.
# Additional app members used by androidTest and its dependencies are derived
# by generate<Variant>InstrumentationKeeps (see README.md in this directory).
-keep class com.tinkle.sdk.smoke.SmokeChecks { public static void run(); }
