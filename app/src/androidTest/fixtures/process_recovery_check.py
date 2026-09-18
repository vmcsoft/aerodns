"""Host-side process/reboot checks; requires an owned emulator and .validation debug APK.

Start the HTTPS response fixture on host loopback, prepare VPN consent/notifications,
and connect RecoveryControlReceiver first. This script never launches the app while
waiting for automatic recovery. Always-on is configured separately in Android Settings.
"""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "com.vmcsoft.aerodns.validation"
RECEIVER = PACKAGE + "/com.vmcsoft.aerodns.validation.RecoveryControlReceiver"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--scenario", choices=("process-death", "reboot", "disconnect-reboot", "always-on-disconnect", "force-stop"), required=True)
    parser.add_argument("--output", type=Path, required=True, help="JSON evidence outside the repository")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Only an explicitly selected owned emulator is supported")
    prefix = [args.adb, "-s", args.serial]

    def adb(*command, check=True):
        result = subprocess.run(prefix + list(command), capture_output=True, text=True, timeout=20)
        if check and result.returncode:
            raise RuntimeError(result.stderr or result.stdout)
        return result.stdout.strip()

    assert adb("shell", "getprop", "ro.kernel.qemu") == "1", "Refusing a physical device"
    assert adb("shell", "run-as", PACKAGE, "id"), "A debuggable validation package is required"

    def record():
        raw = adb("shell", "run-as", PACKAGE, "cat", "shared_prefs/vpn_recovery.xml", check=False)
        return {e.attrib["name"]: e.attrib.get("value", e.text or "") for e in ET.fromstring(raw)} if raw else {}

    def snapshot():
        notifications = adb("shell", "dumpsys", "notification", "--noredact")
        section = notifications.split("  Notification List:", 1)[-1]
        section = re.split(r"\n  \S", section, maxsplit=1)[0]
        own = [r for r in section.split("    NotificationRecord(") if "pkg=" + PACKAGE + " " in r.split("\n", 1)[0]]
        status = next((m.group(1) for r in own if (m := re.search(r"android.text=String \(([^\n]*)\)", r))), None)
        vpn = adb("shell", "dumpsys", "vpn_management")
        return {"pid": adb("shell", "pidof", PACKAGE, check=False), "record": record(),
                "status": status, "vpn": "Active package name: " + PACKAGE in vpn and "Active vpn type: 1" in vpn}

    def wait_healthy(timeout=90, previous_request=None):
        started = time.monotonic()
        while time.monotonic() - started < timeout:
            state = snapshot()
            if (state["pid"].isdigit() and state["vpn"] and
                    state["status"] == "Connected · Recovery fixture · " + protocol and
                    state["record"].get("requestId") != previous_request):
                return state, round(time.monotonic() - started, 3)
            time.sleep(1)
        raise AssertionError("Recovery did not become healthy: " + json.dumps(state))

    def observe_off(seconds):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            state = snapshot()
            assert not state["vpn"] and state["status"] is None and not state["record"], state
            time.sleep(1)
        return state

    def reboot():
        previous = adb("shell", "cat", "/proc/sys/kernel/random/boot_id")
        adb("reboot")
        started = time.monotonic()
        while time.monotonic() - started < 150:
            try:
                current = adb("shell", "cat", "/proc/sys/kernel/random/boot_id", check=False)
                if current and current != previous and adb("shell", "getprop", "sys.boot_completed", check=False) == "1":
                    return round(time.monotonic() - started, 3)
            except subprocess.TimeoutExpired:
                pass
            time.sleep(2)
        raise AssertionError("Emulator did not finish rebooting")

    before = snapshot()
    assert before["record"].get("serverId") == "process-recovery-fixture", "Connect the controlled fixture first"
    protocol = "DoH" if before["record"]["protocol"] == "DOH" else "Standard"
    before, _ = wait_healthy()
    evidence = {"scenario": args.scenario, "api": adb("shell", "getprop", "ro.build.version.sdk"),
                "always_on": adb("shell", "settings", "get", "secure", "always_on_vpn_app"),
                "lockdown": adb("shell", "settings", "get", "secure", "always_on_vpn_lockdown"), "before": before}
    print("Starting " + args.scenario, flush=True)
    try:
        if args.scenario == "process-death":
            pid = before["pid"]
            assert pid.isdigit(), "Expected exactly one validation process"
            adb("shell", "run-as", PACKAGE, "kill", "-9", pid)
            after, elapsed = wait_healthy(previous_request=before["record"]["requestId"])
            assert after["pid"] != pid, "The original process did not die"
            evidence.update(after=after, recovery_seconds=elapsed)
        elif args.scenario == "force-stop":
            adb("shell", "am", "force-stop", PACKAGE)
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                after = snapshot()
                assert not after["pid"] and not after["vpn"] and after["status"] is None, after
                time.sleep(1)
            evidence["after"] = after
        elif args.scenario == "always-on-disconnect":
            assert evidence["always_on"] == PACKAGE, "Enable Always-on for the protected disconnect check"
            assert evidence["lockdown"] != "1", "Disable lockdown for this recovery check"
            adb("shell", "am", "broadcast", "--receiver-foreground", "-n", RECEIVER, "--es", "command", "disconnect")
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                current = snapshot()
                assert current["vpn"] and current["pid"] == before["pid"], current
                assert current["record"] == before["record"] and current["status"] == before["status"], current
                time.sleep(1)
            evidence["before_reboot"] = current
            evidence["boot_seconds"] = reboot()
            after, elapsed = wait_healthy(previous_request=before["record"]["requestId"])
            evidence.update(after=after, recovery_seconds_after_boot=elapsed)
        elif args.scenario == "reboot":
            assert evidence["always_on"] == PACKAGE, "Enable Always-on for this validation package in Settings first"
            assert evidence["lockdown"] != "1", "Disable lockdown for the recovery-only reboot check"
            evidence["boot_seconds"] = reboot()
            after, elapsed = wait_healthy(previous_request=before["record"]["requestId"])
            evidence.update(after=after, recovery_seconds_after_boot=elapsed)
        else:
            assert evidence["always_on"] != PACKAGE, "Turn off Always-on before testing an explicit disconnect"
            adb("shell", "am", "broadcast", "--receiver-foreground", "-n", RECEIVER, "--es", "command", "disconnect")
            time.sleep(1)
            evidence["before_reboot"] = observe_off(10)
            evidence["boot_seconds"] = reboot()
            evidence["after"] = observe_off(30)
        if args.scenario in ("process-death", "reboot", "always-on-disconnect"):
            original = {k: v for k, v in before["record"].items() if k != "requestId"}
            restored = {k: v for k, v in evidence["after"]["record"].items() if k != "requestId"}
            assert original == restored, "Recovery changed the saved configuration"
            assert before["record"]["requestId"] != evidence["after"]["record"]["requestId"], "Expected a new restoration request"
        evidence["passed"] = True
    except Exception as error:
        evidence.update(passed=False, error=str(error))
        try:
            evidence["after"] = snapshot()
        except Exception as snapshot_error:
            evidence["snapshot_error"] = str(snapshot_error)
        raise
    finally:
        args.output.write_text(json.dumps(evidence, indent=2) + "\n")
        print(json.dumps(evidence), flush=True)


if __name__ == "__main__":
    main()
