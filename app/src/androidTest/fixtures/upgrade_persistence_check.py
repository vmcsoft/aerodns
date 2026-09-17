"""Replace an isolated baseline APK with a candidate on an owned emulator.

Clears only .validation data between scenarios, never between baseline and candidate.
Uses debug-signed local artifacts; it does not prove Play signing compatibility.
"""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

PACKAGE = 'com.vmcsoft.aerodns.validation'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--build-tools', type=Path, required=True)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--test-apk', type=Path, required=True)
    parser.add_argument('--allow-reset-validation-data', action='store_true')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not args.serial.startswith('emulator-') or not args.allow_reset_validation_data:
        parser.error('Use an owned emulator and explicitly allow resetting validation-package data')

    def run(command, timeout=60, binary=False):
        result = subprocess.run(command, capture_output=True, text=not binary, timeout=timeout)
        if result.returncode:
            raise RuntimeError(str(result.stdout) + str(result.stderr))
        return result.stdout if binary else result.stdout.strip()

    def adb(*command, **kwargs):
        return run([args.adb, '-s', args.serial] + list(command), **kwargs)

    def artifact(path, package):
        badging = run([str(args.build_tools / 'aapt2'), 'dump', 'badging', str(path)])
        match = re.search(r"package: name='([^']+)' versionCode='([^']*)' versionName='([^']*)'", badging)
        assert match and match[1] == package, 'Unexpected APK package: ' + str(path)
        signature = run([str(args.build_tools / 'apksigner'), 'verify', '--print-certs', str(path)])
        certificates = re.findall(r'Signer #\d+ certificate SHA-256 digest: (\w+)', signature)
        assert certificates, 'No verified signing certificate'
        return {'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'package': match[1],
                'version_code': int(match[2]) if match[2] else None, 'version_name': match[3], 'certificates': certificates}

    report = {'artifacts': {'baseline': artifact(args.baseline, PACKAGE),
                            'candidate': artifact(args.candidate, PACKAGE),
                            'test': artifact(args.test_apk, PACKAGE + '.test')}, 'scenarios': []}
    old, new = report['artifacts']['baseline'], report['artifacts']['candidate']
    assert old['sha256'] != new['sha256'], 'Baseline and candidate must be different binaries'
    assert old['certificates'] == new['certificates'] == report['artifacts']['test']['certificates']
    assert new['version_code'] >= old['version_code']
    assert adb('shell', 'getprop', 'ro.kernel.qemu') == '1'
    assert adb('shell', 'settings', 'get', 'secure', 'always_on_vpn_app') in ('null', '')
    assert 'Active vpn type: 1' not in adb('shell', 'dumpsys', 'vpn_management')
    report['api'] = adb('shell', 'getprop', 'ro.build.version.sdk')
    assert int(report['api']) >= 33, 'This host observer is scoped to API 33+'

    def phase(scenario, name):
        text = adb('shell', 'am', 'instrument', '-w', '-e', 'ownedUpgradeEmulator', 'true',
                   '-e', 'upgradeScenario', scenario, '-e', 'upgradePhase', name,
                   '-e', 'class', 'com.vmcsoft.aerodns.UpgradePersistenceDeviceTest',
                   PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner', timeout=90)
        log = args.output.with_name(args.output.stem + '-' + scenario + '-' + name + '.log')
        log.write_text(text + '\n')
        assert 'OK (1 test)' in text, 'Phase failed; see ' + str(log)
        return json.loads(adb('shell', 'run-as', PACKAGE, 'cat', 'files/upgrade-' + scenario + '-' + name + '.json'))

    def data_hash():
        data = adb('exec-out', 'run-as', PACKAGE, 'cat', 'files/datastore/aerodns_preferences.preferences_pb', binary=True)
        return hashlib.sha256(data).hexdigest()

    try:
        for scenario in ['standard', 'doh', 'legacy']:
            record = {'scenario': scenario, 'phases': []}
            report['scenarios'].append(record)
            # Reset setup to the older debug baseline between independent scenarios.
            # Only baseline staging permits a downgrade; the tested update below does not.
            adb('install', '-r', '-d', str(args.baseline))
            adb('install', '-r', str(args.test_apk))
            assert adb('shell', 'pm', 'clear', PACKAGE) == 'Success'
            adb('shell', 'appops', 'set', PACKAGE, 'ACTIVATE_VPN', 'allow')
            adb('shell', 'pm', 'grant', PACKAGE, 'android.permission.POST_NOTIFICATIONS')
            record['phases'].append(phase(scenario, 'seed'))
            adb('shell', 'am', 'force-stop', PACKAGE)
            record['data_before'] = data_hash()
            # Actual Android package replacement: no uninstall or data clear here.
            adb('install', '-r', str(args.candidate))
            record['data_after_install'] = data_hash()
            assert record['data_before'] == record['data_after_install'], 'Replacement changed persisted data'
            record['phases'].append(phase(scenario, 'verify'))
            assert record['data_before'] == data_hash(), 'Dashboard startup changed persisted preferences'
            record['phases'].append(phase(scenario, 'edit'))
            adb('shell', 'am', 'force-stop', PACKAGE)
            record['phases'].append(phase(scenario, 'verifyEdited'))
            assert len({p['package_uid'] for p in record['phases']}) == 1, 'Package UID changed across update'
            record['passed'] = True
            print('Passed:', scenario, flush=True)
        report['passed'] = True
    except Exception as error:
        report.update(passed=False, error=str(error))
        raise
    finally:
        adb('shell', 'am', 'force-stop', PACKAGE)
        args.output.write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
