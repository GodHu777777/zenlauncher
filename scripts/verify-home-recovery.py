#!/usr/bin/env python3
"""Exercise HOME recovery outside instrumentation, which runs in the app being killed.

Only an isolated emulator is accepted. The original HOME selection is restored on
success and failure. No system launcher is disabled and no app data is cleared.
"""

import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

PACKAGE = "com.zenlauncher.app"
HOME = PACKAGE + "/.MainActivity"


def command(args, allow_missing=False):
    result = subprocess.run(args, text=True, capture_output=True, timeout=30)
    if result.returncode and not (allow_missing and result.returncode == 1):
        raise RuntimeError(f"{args!r}: {result.stdout.strip()} {result.stderr.strip()}")
    return result.stdout.strip()


def eventually(description, probe, timeout=30):
    deadline = time.monotonic() + timeout
    while True:
        value = probe()
        if value:
            return value
        if time.monotonic() >= deadline:
            raise RuntimeError(f"Timed out waiting for {description}")
        time.sleep(0.25)


def canonical(component):
    package, activity = component.split("/", 1)
    return package + "/" + (package + activity if activity.startswith(".") else activity)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--serial")
    args = parser.parse_args()
    report = {"checks": [], "success": False}
    original = None
    error = None
    try:
        devices = [line.split()[0] for line in command(["adb", "devices"]).splitlines()[1:]
                   if len(line.split()) == 2 and line.split()[1] == "device"]
        serial = args.serial or (devices[0] if len(devices) == 1 else None)
        if not serial or serial not in devices or not serial.startswith("emulator-"):
            raise RuntimeError("Exactly one connected emulator, or an explicit emulator serial, is required")
        adb = ["adb", "-s", serial]

        def shell(*parts, allow_missing=False):
            return command(adb + ["shell", *parts], allow_missing=allow_missing)

        fingerprint = shell("getprop", "ro.build.fingerprint")
        qemu = shell("getprop", "ro.kernel.qemu")
        if qemu != "1" or not any(x in fingerprint.lower() for x in ("generic", "sdk", "emulator")):
            raise RuntimeError("Refusing to change HOME on a device without emulator build and kernel markers")
        user = shell("am", "get-current-user")
        if not user.isdigit():
            raise RuntimeError(f"Unknown Android user: {user!r}")
        report.update(serial=serial, fingerprint=fingerprint, user=int(user),
                      sdk=shell("getprop", "ro.build.version.sdk"))

        def resolve_home():
            output = shell("cmd", "package", "resolve-activity", "--brief", "--user", user,
                           "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
            matches = [line for line in output.splitlines()
                       if re.fullmatch(r"[\w.]+/[\w.$]+", line)]
            if len(matches) != 1:
                raise RuntimeError(f"Cannot resolve a single HOME component: {output!r}")
            return canonical(matches[0])

        def set_home(component):
            # Android 7 may return an empty success response; verify the resulting route.
            shell("cmd", "package", "set-home-activity", "--user", user, component)
            def route_matches():
                try:
                    return resolve_home() == canonical(component)
                except RuntimeError:
                    # Role changes can temporarily expose no resolver while being applied.
                    return False
            eventually(f"HOME route {component}", route_matches)

        def home_is_resumed():
            activities = shell("dumpsys", "activity", "activities")
            return any(
                ("mResumedActivity" in line or "topResumedActivity" in line)
                and (HOME in line or canonical(HOME) in line)
                for line in activities.splitlines()
            )

        def home_has_focus():
            windows = shell("dumpsys", "window", "windows")
            return any("mCurrentFocus" in line and (HOME in line or canonical(HOME) in line)
                       for line in windows.splitlines())

        def assert_stable_home(description):
            eventually(description, lambda: home_is_resumed() and home_has_focus())
            # Observe the full dispatch/transition interval; an immediately true condition
            # can otherwise pass before an asynchronous Back finishes the Activity.
            deadline = time.monotonic() + 1.5
            while time.monotonic() < deadline:
                if not home_is_resumed() or not home_has_focus():
                    raise RuntimeError(f"HOME lost its resumed/focused state during {description}")
                time.sleep(0.1)

        def pids():
            output = shell("pidof", PACKAGE, allow_missing=True)
            return set(output.split()) if output else set()

        original = resolve_home()
        if original.startswith("android/") or "ResolverActivity" in original:
            original = None
            raise RuntimeError("Set a real stock HOME before this test, so it can be restored")
        report["original_home"] = original
        if not args.apk.is_file():
            raise RuntimeError(f"APK does not exist: {args.apk}")
        installed = command(adb + ["install", "-r", str(args.apk)], allow_missing=False)
        if "Success" not in installed:
            raise RuntimeError(f"APK installation did not report success: {installed}")
        set_home(HOME)
        shell("input", "keyevent", "3")
        assert_stable_home("initial HOME Activity")
        report["checks"].append("HOME key resolves and resumes ZenLauncher")

        # Run from outside the app so killing its process cannot also kill the test.
        shell("am", "start", "-W", "-a", "android.settings.SETTINGS")
        def settings_is_resumed():
            return any(
                ("mResumedActivity" in line or "topResumedActivity" in line)
                and "com.android.settings/" in line
                for line in shell("dumpsys", "activity", "activities").splitlines()
            )
        eventually("Settings in the foreground before terminating the launcher", settings_is_resumed)
        old_pids = eventually("the background launcher process", pids)
        shell("am", "force-stop", "--user", user, PACKAGE)
        eventually("the old launcher process to exit", lambda: not (pids() & old_pids))
        if not settings_is_resumed():
            raise RuntimeError("Settings did not stay foreground after force-stop; HOME recovery is unproven")
        shell("input", "keyevent", "3")
        assert_stable_home("HOME to restart ZenLauncher after force-stop")
        new_pids = eventually("a new launcher process", pids)
        if old_pids & new_pids or resolve_home() != canonical(HOME):
            raise RuntimeError("HOME did not recover with a new process and the same default selection")
        report.update(old_pids=sorted(old_pids), new_pids=sorted(new_pids))
        report["checks"].append("HOME restarts the stopped launcher process without changing its default")

        shell("input", "keyevent", "4")
        assert_stable_home("root HOME to remain after Back in the recovered process")
        report["checks"].append("Back is consumed after process recovery")
    except Exception as exc:
        error = str(exc)
        report["error"] = error
    finally:
        if original is not None:
            try:
                set_home(original)
                shell("input", "keyevent", "3")
                report["restored_home"] = resolve_home()
            except Exception as exc:
                error = error or "Failed to restore original HOME"
                report["cleanup_error"] = str(exc)
        report["success"] = error is None
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
    if error:
        escaped = error.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::error title=HOME process recovery failed::{escaped}", flush=True)
        return 1
    print(f"::notice title=HOME process recovery::API {report['sdk']}: "
          f"{len(report['checks'])} checks passed; old process exited, system HOME restarted "
          "ZenLauncher, Back stayed on HOME, and the original default was restored.", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
