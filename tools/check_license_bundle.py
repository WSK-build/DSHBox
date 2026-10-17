#!/usr/bin/env python3
"""Check plugin license delivery and the contents of a built APK, offline."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import xml.etree.ElementTree as ET
import zipfile


def check(condition, message):
    if not condition:
        raise SystemExit(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    source_license = (root / 'LICENSE').read_text(encoding='utf-8').strip()
    plugins = root / 'plugin-manager/dshbox-plugins/assets/plugins'
    for name in ('dsh-mobile-adapt', 'mobile-pilot', 'browser-use'):
        package = plugins / name / 'plugin'
        metadata = json.loads((package / 'package.json').read_text(encoding='utf-8'))
        check(metadata.get('license') == 'GPL-3.0-only', f'{name}: missing SPDX identifier')
        check((package / 'LICENSE').read_text(encoding='utf-8').strip() == source_license,
              f'{name}: GPL text differs')
        check('SPDX-License-Identifier: GPL-3.0-only' in (package / 'NOTICE').read_text(encoding='utf-8'),
              f'{name}: scope notice missing')
    for entry in json.loads((root / 'licenses/SOURCES.json').read_text(encoding='utf-8')):
        data = (root / 'licenses' / entry['file']).read_bytes()
        check(hashlib.sha256(data).hexdigest() == entry['sha256'],
              f"License text changed: {entry['file']}")
    keys = {'settings_open_source_licenses', 'licenses_loading', 'licenses_read_error'}
    for locale in ('values', 'values-zh', 'values-ar', 'values-es', 'values-fr', 'values-ru'):
        strings = ET.parse(root / 'app/src/main/res' / locale / 'strings.xml').getroot()
        check(keys <= {entry.get('name') for entry in strings}, f'Missing locale: {locale}')
    if args.apk:
        with zipfile.ZipFile(args.apk) as apk:
            names = apk.namelist()
            check(len(names) == len(set(names)), 'APK contains duplicate entry names')
            entries = apk.read('assets/licenses/INDEX.txt').decode('utf-8').splitlines()
            check(len(entries) == len(set(entries)), 'License index contains duplicates')
            for name in entries:
                check(name and not PurePosixPath(name).is_absolute() and
                      '..' not in PurePosixPath(name).parts and '\\' not in name,
                      'Unsafe license index path')
                check('assets/licenses/' + name in names, f'Missing APK license: {name}')
                check(not name.endswith('.class'), 'Binary class included as license text')
            for name in ('LICENSE', 'THIRD_PARTY_NOTICES.md', 'TRADEMARK.md'):
                check(apk.read('assets/licenses/' + name) == (root / name).read_bytes(),
                      f'Outdated APK notice: {name}')
            for name in ('dsh-mobile-adapt', 'mobile-pilot', 'browser-use'):
                for doc in ('LICENSE', 'NOTICE'):
                    data = (plugins / name / 'plugin' / doc).read_bytes()
                    check(apk.read(f'assets/plugins/{name}/plugin/{doc}') == data,
                          f'{name}: missing installed payload {doc}')
                    check(apk.read(f'assets/licenses/plugins/{name}/{doc}') == data,
                          f'{name}: missing readable {doc}')
            for path in (root / 'licenses').rglob('*'):
                if path.is_file():
                    relative = path.relative_to(root / 'licenses').as_posix()
                    check(apk.read('assets/licenses/texts/' + relative) == path.read_bytes(),
                          f'Missing source license: {relative}')
            dependencies = apk.read('assets/licenses/DEPENDENCIES.txt').decode('utf-8')
            check('androidx.webkit:webkit:1.12.1' in dependencies, 'WebKit absent from manifest')
            check('org.json:json:' not in dependencies and 'junit:junit:' not in dependencies,
                  'JVM test dependency incorrectly bundled')
            check(any('androidx.webkit' in path and path.endswith('LICENSE.txt') for path in entries),
                  'WebKit license text absent')
            print(f'APK: {len(entries)} indexed legal documents; payloads match source')
    print('PASS: plugin scope, license provenance hashes and six-language labels')


if __name__ == '__main__':
    main()
