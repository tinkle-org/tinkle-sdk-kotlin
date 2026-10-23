# Only the test entry point is kept. SDK callees are optimized using AAR consumer rules.
-keep class com.tinkle.sdk.smoke.SmokeChecks { public static void run(); }
