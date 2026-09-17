"""Host-driven consent/dashboard/tile startup checks on an owned, disposable emulator.

Requires isolated .validation APK, working internet, notifications enabled and
Always-on off. Uses real Android consent UI; never pregrants VPN consent. All results
and UI captures must stay outside Git. English system/app UI is required.
"""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = 'com.vmcsoft.aerodns.validation'
ACTIVITY = PACKAGE + '/com.vmcsoft.aerodns.presentation.MainActivity'
TILE = PACKAGE + '/com.vmcsoft.aerodns.presentation.tile.DnsTileService'
RECEIVER = PACKAGE + '/com.vmcsoft.aerodns.validation.RecoveryControlReceiver'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--scenario', choices=['dashboard', 'speed-test', 'tile', 'all'], default='all')
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        parser.error('Only an explicitly selected owned emulator is supported')
    prefix = [args.adb, '-s', args.serial]

    def adb(*command, check=True):
        p = subprocess.run(prefix + list(command), capture_output=True, text=True, timeout=25)
        if check and p.returncode:
            raise RuntimeError(p.stderr or p.stdout)
        return p.stdout.strip()

    assert adb('shell', 'getprop', 'ro.kernel.qemu') == '1'
    assert adb('shell', 'settings', 'get', 'secure', 'always_on_vpn_app') in ('null', '')
    assert adb('shell', 'run-as', PACKAGE, 'id')
    evidence = {'api': adb('shell', 'getprop', 'ro.build.version.sdk'), 'checks': []}

    def record(name):
        evidence['checks'].append(name)
        print('PASS: ' + name, flush=True)

    def ui():
        adb('shell', 'uiautomator', 'dump', '/sdcard/aerodns-startup-ui.xml')
        return ET.fromstring(adb('shell', 'cat', '/sdcard/aerodns-startup-ui.xml'))

    def wait(fn, message, seconds=25):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            value = fn()
            if value:
                return value
            time.sleep(0.5)
        raise AssertionError(message)

    def nodes(attribute, value):
        return [n for n in ui().iter('node') if n.get(attribute) == value]

    def click(attribute, value):
        found = wait(lambda: nodes(attribute, value), 'Missing UI control: ' + value)
        assert len(found) == 1, 'Ambiguous UI control: ' + value
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', found[0].get('bounds')))
        adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))

    def consent(allow):
        wait(lambda: nodes('resource-id', 'com.android.vpndialogs:id/warning'), 'VPN consent dialog did not appear')
        click('resource-id', 'android:id/button1' if allow else 'android:id/button2')

    def reveal_result(name):
        # Benchmark order is measured, not fixed; Cloudflare can start below the fold.
        for _ in range(6):
            root = ui()
            if any(n.get('text') == name for n in root.iter('node')):
                return
            lists = [n for n in root.iter('node') if n.get('scrollable') == 'true']
            assert len(lists) == 1, 'Expected one scrollable benchmark list'
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', lists[0].get('bounds')))
            x = str((x1 + x2) // 2)
            adb('shell', 'input', 'swipe', x, str(y2 - (y2 - y1) // 5),
                x, str(y1 + (y2 - y1) // 5), '350')
        raise AssertionError('Benchmark result not found: ' + name)

    def status():
        raw = adb('shell', 'dumpsys', 'notification', '--noredact')
        section = raw.split('  Notification List:', 1)[-1]
        section = re.split(r'\n  \S', section, maxsplit=1)[0]
        own = [r for r in section.split('    NotificationRecord(') if 'pkg=' + PACKAGE + ' ' in r.split('\n', 1)[0]]
        return next((m.group(1) for r in own if (m := re.search(r'android.text=String \(([^\n]*)\)', r))), None)

    def vpn():
        raw = adb('shell', 'dumpsys', 'vpn_management')
        return 'Active package name: ' + PACKAGE in raw and 'Active vpn type: 1' in raw

    def healthy():
        wait(lambda: vpn() and (status() or '').startswith('Connected ·'), 'VPN did not become healthy', 25)

    def off():
        wait(lambda: not vpn() and status() is None, 'VPN or foreground notification remains')

    def disconnect():
        adb('shell', 'am', 'broadcast', '--receiver-foreground', '-n', RECEIVER, '--es', 'command', 'disconnect')
        off()

    def launch():
        adb('shell', 'am', 'start', '-n', ACTIVITY)

    def reset_consent():
        disconnect()
        adb('shell', 'am', 'force-stop', PACKAGE)
        adb('shell', 'appops', 'set', PACKAGE, 'ACTIVATE_VPN', 'deny')
        assert not adb('shell', 'pidof', PACKAGE, check=False), 'Expected a cold process'

    try:
        if args.scenario in ('dashboard', 'all'):
            reset_consent()
            launch()
            consent(False)
            off()
            record('cold dashboard consent denial leaves VPN off')
            click('content-desc', 'Connect/Disconnect')
            consent(False)
            off()
            record('dashboard retry offers consent; repeated denial leaves VPN off')
            click('content-desc', 'Connect/Disconnect')
            consent(True)
            healthy()
            record('dashboard retry accepts consent and connects without reopening')
            click('content-desc', 'Connect/Disconnect')
            off()
            adb('shell', 'am', 'force-stop', PACKAGE)
            assert not adb('shell', 'pidof', PACKAGE, check=False)
            launch()
            click('content-desc', 'Connect/Disconnect')
            healthy()
            adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
            time.sleep(6)
            healthy()
            launch()
            click('content-desc', 'Connect/Disconnect')
            off()
            record('prepared cold dashboard connects; backgrounding preserves health; UI disconnect works')

        if args.scenario in ('speed-test', 'all'):
            reset_consent()
            launch()
            consent(False)
            click('text', 'Speed Test')
            wait(lambda: nodes('text', 'Activate Selected'), 'Benchmark did not finish', 90)
            reveal_result('Cloudflare')
            click('text', 'Cloudflare')
            click('text', 'Activate Selected')
            consent(False)
            off()
            record('speed-test activation denial leaves VPN off')
            click('text', 'Activate Selected')
            consent(True)
            healthy()
            record('speed-test activation retries consent and connects')
            click('content-desc', 'Connect/Disconnect')
            off()

        if args.scenario in ('tile', 'all'):
            reset_consent()
            adb('shell', 'cmd', 'statusbar', 'add-tile', TILE)
            adb('shell', 'cmd', 'statusbar', 'expand-settings')
            time.sleep(2)
            # Force-stop before clicking verifies a new process rather than the in-process
            # tile callback exercised by instrumentation. The tile is already registered.
            adb('shell', 'am', 'force-stop', PACKAGE)
            assert not adb('shell', 'pidof', PACKAGE, check=False)
            adb('shell', 'cmd', 'statusbar', 'click-tile', TILE)
            consent(True)
            off()  # Permission from tile opens the dashboard; it does not auto-connect.
            click('content-desc', 'Connect/Disconnect')
            healthy()
            click('content-desc', 'Connect/Disconnect')
            off()
            record('unprepared cold tile opens consent; dashboard connects after grant')
            adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
            adb('shell', 'cmd', 'statusbar', 'expand-settings')
            time.sleep(2)
            adb('shell', 'am', 'force-stop', PACKAGE)
            assert not adb('shell', 'pidof', PACKAGE, check=False)
            adb('shell', 'cmd', 'statusbar', 'click-tile', TILE)
            healthy()
            adb('shell', 'cmd', 'statusbar', 'click-tile', TILE)
            off()
            record('prepared cold tile connects and disconnects with dashboard backgrounded')
        evidence['passed'] = True
    except Exception as error:
        evidence.update(passed=False, error=str(error))
        raise
    finally:
        # This script owns only this emulator's isolated package and temporary tile.
        try:
            disconnect()
            adb('shell', 'cmd', 'statusbar', 'collapse')
            if args.scenario in ('tile', 'all'):
                adb('shell', 'cmd', 'statusbar', 'remove-tile', TILE)
            adb('shell', 'rm', '-f', '/sdcard/aerodns-startup-ui.xml')
        finally:
            args.output.write_text(json.dumps(evidence, indent=2) + '\n')


if __name__ == '__main__':
    main()
