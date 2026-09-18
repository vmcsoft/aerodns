"""Screen-off forced-idle recovery on an owned emulator; not a battery-drain test.

Requires the candidate .validation APK, VPN/notification consent and the loopback
HTTPS load fixture on port 18446. The fixture is reached through emulator host alias.
"""
import argparse
import json
import re
import subprocess
import time
from pathlib import Path

PACKAGE = 'com.vmcsoft.aerodns.validation'
RECEIVER = PACKAGE + '/com.vmcsoft.aerodns.validation.RecoveryControlReceiver'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--seconds', type=int, default=180)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not args.serial.startswith('emulator-') or not 60 <= args.seconds <= 900:
        parser.error('Select an owned emulator and a duration between 60 and 900 seconds')
    prefix = [args.adb, '-s', args.serial]

    def adb(*command, check=True):
        result = subprocess.run(prefix + list(command), capture_output=True, text=True, timeout=20)
        if check and result.returncode:
            raise RuntimeError(result.stderr or result.stdout)
        return result.stdout.strip()

    assert adb('shell', 'getprop', 'ro.kernel.qemu') == '1'
    assert adb('shell', 'run-as', PACKAGE, 'id')
    assert adb('shell', 'settings', 'get', 'secure', 'always_on_vpn_app') in ('null', '')
    assert adb('shell', 'dumpsys', 'deviceidle', 'get', 'force') == 'false'
    assert adb('shell', 'dumpsys', 'deviceidle', 'get', 'screen') == 'true'

    def snapshot():
        pid = adb('shell', 'pidof', PACKAGE, check=False)
        resources = {}
        if pid.isdigit():
            status = adb('shell', 'run-as', PACKAGE, 'cat', '/proc/' + pid + '/status', check=False)
            stat = adb('shell', 'run-as', PACKAGE, 'cat', '/proc/' + pid + '/stat', check=False)
            if ')' in stat:
                fields = stat.rsplit(')', 1)[1].split()
                resources['cpu_ticks'] = int(fields[11]) + int(fields[12])
            for key in ['VmRSS', 'Threads']:
                match = re.search(r'^' + key + r':\s+(\d+)', status, re.M)
                if match:
                    resources[key] = int(match.group(1))
            fds = adb('shell', 'run-as', PACKAGE, 'ls', '/proc/' + pid + '/fd', check=False)
            resources['fds'] = len(fds.splitlines())
        raw = adb('shell', 'dumpsys', 'notification', '--noredact')
        section = raw.split('  Notification List:', 1)[-1]
        section = re.split(r'\n  \S', section, maxsplit=1)[0]
        own = [r for r in section.split('    NotificationRecord(') if 'pkg=' + PACKAGE + ' ' in r.split('\n', 1)[0]]
        text = next((m.group(1) for r in own if (m := re.search(r'android.text=String \(([^\n]*)\)', r))), None)
        vpn = adb('shell', 'dumpsys', 'vpn_management')
        return {'pid': pid, 'resources': resources, 'notification': text,
                'vpn': 'Active package name: ' + PACKAGE in vpn and 'Active vpn type: 1' in vpn,
                'idle': adb('shell', 'dumpsys', 'deviceidle', 'get', 'deep')}

    def healthy(timeout=45):
        start = time.monotonic()
        while time.monotonic() - start < timeout:
            state = snapshot()
            if state['vpn'] and state['notification'] == 'Connected · Recovery fixture · DoH':
                return state, round(time.monotonic() - start, 3)
            time.sleep(1)
        raise AssertionError('No healthy selected VPN after wake: ' + json.dumps(state))

    def config():
        import xml.etree.ElementTree as ET
        root = ET.fromstring(adb('shell', 'run-as', PACKAGE, 'cat', 'shared_prefs/vpn_recovery.xml'))
        return {e.attrib['name']: e.attrib.get('value', e.text or '') for e in root}

    before = snapshot()
    assert not before['vpn'], 'Disconnect the test VPN first'
    report = {'requested_idle_seconds': args.seconds, 'clock_ticks_per_second': adb('shell', 'getconf', 'CLK_TCK')}
    changed = False
    try:
        adb('shell', 'am', 'start', '-n', PACKAGE + '/com.vmcsoft.aerodns.presentation.MainActivity')
        time.sleep(2)  # Initialize the same repository/network observer used by the dashboard.
        result = adb('shell', 'am', 'broadcast', '--receiver-foreground', '-n', RECEIVER,
                     '--es', 'command', 'connect', '--ei', 'port', '18446')
        assert 'submitted:connect' in result, result
        report['before'], _ = healthy()
        original = config()
        changed = True
        adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        adb('shell', 'dumpsys', 'battery', 'unplug')
        adb('shell', 'input', 'keyevent', 'KEYCODE_SLEEP')
        adb('shell', 'dumpsys', 'deviceidle', 'force-idle', 'deep')
        assert adb('shell', 'dumpsys', 'deviceidle', 'get', 'deep') == 'IDLE'
        start = time.monotonic()
        report['samples'] = []
        print('Entered screen-off forced deep idle', flush=True)
        while time.monotonic() - start < args.seconds:
            time.sleep(min(30, max(0, args.seconds - (time.monotonic() - start))))
            sample = snapshot()
            sample['elapsed_seconds'] = round(time.monotonic() - start, 3)
            report['samples'].append(sample)
            assert sample['idle'] == 'IDLE', 'Emulator left forced idle early'
            print('Idle sample:', sample['elapsed_seconds'], sample['notification'], flush=True)
        adb('shell', 'dumpsys', 'deviceidle', 'unforce')
        adb('shell', 'dumpsys', 'battery', 'reset')
        adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        report['after'], report['healthy_observation_seconds_after_wake'] = healthy()
        # A retained notification is not proof of fresh DNS after wake. The .test name
        # is unique and only this selected fixture can return the controlled address.
        started = time.monotonic()
        while time.monotonic() - started < 45:
            name = 'wake-' + str(time.monotonic_ns()) + '.load.test'
            # Android's IPv4 ping binary does not accept iputils' newer -4 flag.
            lookup = adb('shell', 'ping', '-c', '1', '-W', '1', name, check=False)
            if '192.0.2.42)' in lookup:
                report['fresh_lookup_seconds_after_healthy_observation'] = round(time.monotonic() - started, 3)
                report['fresh_system_lookup_passed'] = True
                break
            time.sleep(1)
        assert report.get('fresh_system_lookup_passed'), 'No fresh controlled DNS answer after wake'
        restored = config()
        assert {k: v for k, v in original.items() if k != 'requestId'} == {k: v for k, v in restored.items() if k != 'requestId'}
        report['same_request'] = original['requestId'] == restored['requestId']
        report['passed'] = True
    except Exception as error:
        report.update(passed=False, error=str(error))
        raise
    finally:
        try:
            if changed:
                adb('shell', 'dumpsys', 'deviceidle', 'unforce')
                adb('shell', 'dumpsys', 'battery', 'reset')
                adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
            adb('shell', 'am', 'broadcast', '--receiver-foreground', '-n', RECEIVER, '--es', 'command', 'disconnect')
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                state = snapshot()
                if not state['vpn'] and state['notification'] is None:
                    break
                time.sleep(0.5)
            assert not state['vpn'] and state['notification'] is None, 'Test VPN remained after cleanup'
            report['cleanup'] = {'vpn_removed': True, 'forced_idle': adb('shell', 'dumpsys', 'deviceidle', 'get', 'force')}
        except Exception as cleanup_error:
            report.update(passed=False, cleanup_error=str(cleanup_error))
            raise
        finally:
            args.output.write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
