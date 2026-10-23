"""Opt-in real AGP task-graph regression; needs Android SDK and a built release AAR."""
import os
from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(os.environ.get('TINKLE_SDK_TEST_AAR'), 'set TINKLE_SDK_TEST_AAR for AGP graph checks')
class InstrumentationWiringTest(unittest.TestCase):
    def test_minify_alone_requires_test_classes_and_generation_without_cycle(self):
        for variant in ('debug', 'release'):
            with self.subTest(variant=variant):
                cap = variant.capitalize()
                result = subprocess.run([
                    str(ROOT / 'gradlew'), f':sample:minify{cap}WithR8',
                    f'-PsmokeBuildType={variant}',
                    f'-PtinkleSdkTestAar={os.environ["TINKLE_SDK_TEST_AAR"]}',
                    '--dry-run', '--console=plain',
                ], cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                self.assertEqual(result.returncode, 0, result.stdout)
                tasks = [line.split()[0] for line in result.stdout.splitlines() if line.startswith(':sample:')]
                compile_test = tasks.index(f':sample:compile{cap}AndroidTestKotlin')
                generate = tasks.index(f':sample:generate{cap}InstrumentationKeeps')
                minify = tasks.index(f':sample:minify{cap}WithR8')
                self.assertLess(compile_test, generate)
                self.assertLess(generate, minify)
                self.assertNotIn(f':sample:minify{cap}AndroidTestWithR8', tasks)
                self.assertNotIn(f':sample:package{cap}', tasks)


if __name__ == '__main__':
    unittest.main()
