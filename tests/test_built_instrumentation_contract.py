"""Opt-in post-build check of every generated keep against defined APK DEX members."""
import os
from pathlib import Path
import re
import subprocess
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(os.environ.get('TINKLE_VERIFY_BUILT_APKS'), 'set TINKLE_VERIFY_BUILT_APKS after building both smoke variants')
class BuiltInstrumentationContractTest(unittest.TestCase):
    def test_generated_members_survive_in_app_dex_and_app_still_shrinks(self):
        dexdump = Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0/dexdump'
        for variant in ('debug', 'release'):
            with self.subTest(variant=variant):
                rules = (ROOT / f'sample/build/generated/instrumentation-keeps/{variant}.pro').read_text()
                mapping_dir = ROOT / f'sample/build/outputs/mapping/{variant}'
                config = (mapping_dir / 'configuration.txt').read_text()
                self.assertIn(f'instrumentation-keeps/{variant}.pro', config)
                self.assertNotRegex(rules, r'\*|-(?:dont\w+|ignorewarnings)\b')
                self.assertNotRegex(config, r'(?m)^-(?:dontshrink|dontoptimize|dontobfuscate|ignorewarnings)\b')
                self.assertNotRegex(rules, r'-keep \w+ com\.tinkle\.sdk\.(?!smoke\.)')
                # Actual removed code and renamed classes, beyond isMinifyEnabled.
                self.assertTrue((mapping_dir / 'usage.txt').read_text().strip())
                mappings = re.findall(r'(?m)^(\S+) -> (\S+):$', (mapping_dir / 'mapping.txt').read_text())
                self.assertTrue(any(original != renamed for original, renamed in mappings))
                names = dict(mappings)

                def mapped_type(name):
                    # AGP applies app mapping to test descriptors too. Descriptor-
                    # only types need not retain their original names.
                    base = name.split('[', 1)[0]
                    return names.get(base, base) + name[len(base):]

                apk = ROOT / f'sample/build/outputs/apk/{variant}/sample-{variant}.apk'
                xml = subprocess.check_output([str(dexdump), '-l', 'xml', str(apk)], text=True)
                dex = {}
                parents = {}
                for package in ET.fromstring(xml).findall('package'):
                    for cls in package.findall('class'):
                        name = '.'.join(filter(None, (package.attrib['name'], cls.attrib['name'])))
                        parents[name] = [cls.attrib.get('extends', '')] + [i.attrib['name'] for i in cls.findall('implements')]
                        members = set()
                        for member in cls:
                            if member.tag in ('method', 'constructor'):
                                method = '<init>' if member.tag == 'constructor' else member.attrib['name']
                                result = 'void' if member.tag == 'constructor' else member.attrib['return']
                                params = tuple(p.attrib['type'] for p in member.findall('parameter'))
                                members.add((result, method, params))
                            elif member.tag == 'field':
                                members.add((member.attrib['type'], member.attrib['name']))
                        dex[name] = members

                def resolves(name, member, visited=None):
                    visited = set() if visited is None else visited
                    if name in visited:
                        return False
                    visited.add(name)
                    if member in dex.get(name, set()):
                        return True
                    if member[1] == '<init>':
                        return False  # Constructors are never inherited.
                    return any(resolves(parent, member, visited) for parent in parents.get(name, []))

                blocks = re.findall(r'^-keep (class|interface|enum|@interface) ([\w.$]+) \{\n([^}]*?)^\}', rules, re.M)
                self.assertGreater(len(blocks), 10)
                member_count = 0
                for kind, name, body in blocks:
                    self.assertIn(name, dex, name)
                    self.assertIn(f'-keep {kind} {name} {{\n{body}}}', config)
                    for line in body.splitlines():
                        line = re.sub(r'\b(?:public|protected|private|static|final|abstract)\s+', '', line.strip())
                        if '(' in line:
                            signature, params = line.rstrip(';').rstrip(')').split('(', 1)
                            result, method = ('void', '<init>') if signature == '<init>' else signature.split()
                            expected = (mapped_type(result), method, tuple(mapped_type(p.strip()) for p in params.split(',') if p.strip()))
                        else:
                            field_type, field_name = line.rstrip(';').split()
                            expected = (mapped_type(field_type), field_name)
                        self.assertTrue(resolves(name, expected), f'{name}: {expected}')
                        member_count += 1
                print(f'{variant}: verified {len(blocks)} classes and {member_count} exact DEX members; app shrinking/obfuscation active')


if __name__ == '__main__':
    unittest.main()
