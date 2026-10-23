from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
RULES = '\n'.join(line.split('#', 1)[0].strip()
                  for line in (ROOT / 'consumer-rules.pro').read_text().splitlines())


class ConsumerRulesTest(unittest.TestCase):
    def test_only_known_desktop_awt_missing_types_are_suppressed(self):
        suppressions = re.findall(r'(?m)^-dontwarn\b([^\n]*)$', RULES)
        self.assertCountEqual([value.strip() for value in suppressions], [
            'java.awt.Component',
            'java.awt.GraphicsEnvironment',
            'java.awt.HeadlessException',
            'java.awt.Window',
        ])
        self.assertNotRegex(RULES, r'(?m)^-(ignorewarnings|dontshrink|dontoptimize|dontobfuscate)\b')

    def test_native_and_reflective_abi_keeps_remain(self):
        for kind in ('class', 'interface'):
            self.assertIn(f'-keep {kind} com.sun.jna.** {{ *; }}', RULES)
        for base in ('Library', 'Callback'):
            self.assertIn(f'-keep class com.tinkle.sdk.** implements com.sun.jna.{base} {{ *; }}', RULES)
            self.assertIn(f'-keep interface com.tinkle.sdk.** extends com.sun.jna.{base} {{ *; }}', RULES)
        self.assertIn('-keep class com.tinkle.sdk.** extends com.sun.jna.Structure { *; }', RULES)
        self.assertRegex(RULES, r'-keepclasseswithmembers,includedescriptorclasses class com\.tinkle\.sdk\.\*\*\s*\{\s*native <methods>;\s*\}')

    def test_rules_ship_with_sdk_and_sample_still_minifies(self):
        self.assertIn('consumerProguardFiles("consumer-rules.pro")',
                      (ROOT / 'build.gradle.kts').read_text())
        sample = (ROOT / 'sample/build.gradle.kts').read_text()
        for build_type in ('debug', 'release'):
            block = re.search(rf'\b{build_type}\s*\{{([^}}]*)\}}', sample).group(1)
            self.assertIn('isMinifyEnabled = true', block)
        self.assertNotIn('-dontwarn', (ROOT / 'sample/smoke-rules.pro').read_text())

    def test_error_prone_annotations_are_pinned_and_instrumentation_only(self):
        sample = (ROOT / 'sample/build.gradle.kts').read_text()
        declarations = re.findall(
            r'(\w+)\("com\.google\.errorprone:error_prone_annotations:([^"\n]+)"\)',
            sample,
        )
        self.assertEqual(len(declarations), 1)
        scope, version = declarations[0]
        # R8 needs the CLASS-retained types as libraries, not test APK program classes.
        self.assertEqual(scope, 'androidTestCompileOnly')
        self.assertRegex(version, r'^\d+\.\d+\.\d+$')
        self.assertNotIn('com.google.errorprone', (ROOT / 'build.gradle.kts').read_text())

    def test_reference_derived_keeps_replace_blanket_sample_keeps(self):
        smoke = (ROOT / 'sample/smoke-rules.pro').read_text()
        stripped = '\n'.join(line.split('#', 1)[0].strip() for line in smoke.splitlines())
        self.assertNotIn('**', stripped)
        self.assertNotIn('kotlin.', stripped)
        self.assertNotIn('androidx.test.', stripped)
        self.assertIn('-keep class com.tinkle.sdk.smoke.SmokeChecks { public static void run(); }', stripped)
        self.assertNotRegex(stripped, r'(?m)^-(dontwarn|ignorewarnings|dontshrink|dontoptimize|dontobfuscate)\b')
        # Shipped scope is unchanged: no androidx.test or kotlin keeps, still only the 4 AWT dontwarns.
        self.assertNotIn('androidx.test', RULES)
        self.assertNotIn('kotlin.', RULES)
        self.assertNotIn('androidx.test', (ROOT / 'consumer-rules.pro').read_text())
        suppressions = re.findall(r'(?m)^-dontwarn\b([^\n]*)$', RULES)
        self.assertCountEqual([value.strip() for value in suppressions], [
            'java.awt.Component',
            'java.awt.GraphicsEnvironment',
            'java.awt.HeadlessException',
            'java.awt.Window',
        ])
        # App R8 shrinks/optimizes/obfuscates. AGP's test R8 pass applies app
        # mapping with test-code shrinking/optimization/obfuscation disabled.
        sample = (ROOT / 'sample/build.gradle.kts').read_text()
        for build_type in ('debug', 'release'):
            block = re.search(rf'\b{build_type}\s*\{{([^}}]*)\}}', sample).group(1)
            self.assertIn('isMinifyEnabled = true', block)
        self.assertNotIn('isMinifyEnabled = false', sample)

    def test_agp_supports_pinned_kotlin_metadata(self):
        root = (ROOT / 'build.gradle.kts').read_text()
        for plugin in ('library', 'application'):
            self.assertIn(f'id("com.android.{plugin}") version "8.13.2"', root)
        self.assertIn('id("org.jetbrains.kotlin.android") version "2.3.0"', root)


if __name__ == '__main__':
    unittest.main()
