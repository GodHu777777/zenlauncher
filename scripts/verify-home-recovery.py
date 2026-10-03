#!/usr/bin/env python3
"""Exercise process, sleep/wake and reboot HOME recovery outside instrumentation.

Only an isolated emulator is accepted. The original HOME selection is restored on
success and failure. No system launcher is disabled and no app data is cleared.
Sleep/wake and reboot coverage uses an emulator without a PIN or biometric lock.
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


def command(args, allow_missing=False, timeout=30):
    result = subprocess.run(args, text=True, capture_output=True, timeout=timeout)
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


def host_android_processes():
    output = command(["ps", "-eo", "pid=,ppid=,stat=,comm="], timeout=5)
    processes = []
    for line in output.splitlines():
        fields = line.split(None, 3)
        if len(fields) != 4:
            continue
        name = Path(fields[3]).name
        if name.lower() == "adb" or name.lower().startswith(("emulator", "qemu")):
            processes.append(dict(zip(("pid", "ppid", "state", "command"), fields[:3] + [name])))
    # Command names only: omit arguments and every unrelated host process.
    return processes


def host_transport_diagnostics():
    """Inspect transport/process availability without reconnecting or restarting anything."""
    state = {}
    try:
        state["adb_devices"] = command(["adb", "devices", "-l"], timeout=5)
    except Exception as exc:
        state["adb_devices_error"] = str(exc)[:600]
    try:
        state["emulator_adb_processes"] = host_android_processes()[:20]
    except Exception as exc:
        state["process_inspection_error"] = str(exc)[:600]
    try:
        kernel = subprocess.run(["dmesg"], text=True, capture_output=True, timeout=5)
        if kernel.returncode:
            state["kernel_log_error"] = f"dmesg exit {kernel.returncode}: {kernel.stderr.strip()[:500]}"
        else:
            relevant = re.compile(r"emulator|qemu|\boom(?:[_:-]|\b)|out of memory|killed process|segfault", re.IGNORECASE)
            state["kernel_log_lines"] = [line[:600] for line in kernel.stdout.splitlines()
                                         if relevant.search(line)][-15:]
    except Exception as exc:
        state["kernel_log_error"] = str(exc)[:600]
    try:
        with Path("/proc/meminfo").open() as memory_file:
            memory = memory_file.read(16384)
        state["memory_kib"] = {
            key: int(value) for key, value in re.findall(
                r"^(MemTotal|MemFree|MemAvailable|SwapTotal|SwapFree):\s+(\d+) kB$",
                memory, re.MULTILINE)
        }
    except OSError as exc:
        state["memory_unavailable"] = str(exc)[:200]
    return state


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--serial")
    args = parser.parse_args()
    report = {"checks": [], "success": False}
    original = None
    error = None
    capture_failure_state = None
    reboot_pending = False
    pre_reboot_host_processes = []
    try:
        devices = [line.split()[0] for line in command(["adb", "devices"]).splitlines()[1:]
                   if len(line.split()) == 2 and line.split()[1] == "device"]
        serial = args.serial or (devices[0] if len(devices) == 1 else None)
        if not serial or serial not in devices or not serial.startswith("emulator-"):
            raise RuntimeError("Exactly one connected emulator, or an explicit emulator serial, is required")
        adb = ["adb", "-s", serial]

        def shell(*parts, allow_missing=False, timeout=30):
            return command(adb + ["shell", *parts], allow_missing=allow_missing, timeout=timeout)

        fingerprint = shell("getprop", "ro.build.fingerprint")
        qemu = shell("getprop", "ro.kernel.qemu")
        if qemu != "1" or not any(x in fingerprint.lower() for x in ("generic", "sdk", "emulator")):
            raise RuntimeError("Refusing to change HOME on a device without emulator build and kernel markers")
        user = shell("am", "get-current-user")
        if not user.isdigit():
            raise RuntimeError(f"Unknown Android user: {user!r}")
        sdk = shell("getprop", "ro.build.version.sdk")
        report.update(serial=serial, fingerprint=fingerprint, user=int(user), sdk=sdk,
                      lock_coverage="Sleep/wake and reboot on an emulator without a PIN or biometrics; "
                                    "screen sleep alone does not prove the keyguard was enabled")
        # Since Android 10, mCurrentFocus belongs to DisplayContent.dump(), which
        # the "windows" subcommand does not call. Android 7 prints it in "windows".
        # Read the current section directly, excluding historical last-ANR dumps.
        focus_dump = ("dumpsys", "window", "displays" if int(sdk) >= 29 else "windows")

        def resolve_home():
            output = shell("cmd", "package", "resolve-activity", "--brief", "--user", user,
                           "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME",
                           timeout=10)
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
            activities = shell("dumpsys", "activity", "activities", timeout=10)
            return any(
                ("mResumedActivity" in line or "topResumedActivity" in line)
                and (HOME in line or canonical(HOME) in line)
                for line in activities.splitlines()
            )

        def home_has_focus():
            windows = shell(*focus_dump, timeout=10)
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
            output = shell("pidof", PACKAGE, allow_missing=True, timeout=5)
            return set(output.split()) if output else set()

        def boot_id():
            value = shell("cat", "/proc/sys/kernel/random/boot_id", timeout=5)
            if not re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", value):
                raise RuntimeError(f"Invalid kernel boot_id: {value!r}")
            return value

        def wait_for_boot(previous_boot_id=None, timeout=180, expected_host_processes=None):
            # One deadline covers disconnection, ADB reconnection and Android boot.
            # Observing a different boot_id prevents a stale sys.boot_completed=1
            # from making the pre-reboot system look like a successful new boot.
            deadline = time.monotonic() + timeout
            while True:
                state = {}
                try:
                    state["adb_state"] = command(adb + ["get-state"], timeout=5)
                    if state["adb_state"] == "device":
                        state["boot_id"] = boot_id()
                        state["boot_completed"] = shell("getprop", "sys.boot_completed", timeout=5)
                        if (state["boot_completed"] == "1"
                                and state["boot_id"] != previous_boot_id):
                            report["boot_wait_state"] = state
                            return state
                except (RuntimeError, subprocess.TimeoutExpired) as exc:
                    state["connection_error"] = str(exc)[:500]
                if expected_host_processes:
                    try:
                        current_processes = host_android_processes()
                        live_emulator_pids = {process["pid"] for process in current_processes
                                             if process["command"].lower().startswith(("emulator", "qemu"))
                                             and not process["state"].startswith(("Z", "X"))}
                        state["host_processes"] = current_processes[:20]
                        state["expected_host_pids"] = [process["pid"] for process in expected_host_processes]
                        # A replacement host process is not proof that the device is
                        # gone. Keep waiting for the real boot_id in that case.
                        all_exited = not live_emulator_pids
                    except Exception as exc:
                        # Unknown host process state is not proof of emulator termination.
                        state["host_process_inspection_error"] = str(exc)[:500]
                        all_exited = False
                    if all_exited:
                        report["boot_wait_state"] = state
                        report["host_transport_at_boot_timeout"] = host_transport_diagnostics()
                        raise RuntimeError(f"All pre-reboot emulator/QEMU host processes terminated: {state}")
                report["boot_wait_state"] = state
                if time.monotonic() >= deadline:
                    report["host_transport_at_boot_timeout"] = host_transport_diagnostics()
                    raise RuntimeError(f"Timed out waiting for a connected, completed new boot: {state}")
                time.sleep(1)

        def power_state():
            power = shell("dumpsys", "power", timeout=10)
            display = shell("dumpsys", "display", timeout=10)
            wakefulness = re.findall(r"^\s*mWakefulness=(\w+)\s*$", power, re.MULTILINE)
            screen_states = re.findall(r"^\s*mScreenState=(\w+)\s*$", display, re.MULTILINE)
            return {"wakefulness": wakefulness, "display_screen_states": screen_states}

        def wait_for_power(wakefulness, screen_state):
            def matches():
                state = power_state()
                report["last_power_state"] = state
                return state if (state["wakefulness"] == [wakefulness]
                                 and state["display_screen_states"]
                                 and all(value == screen_state
                                         for value in state["display_screen_states"])) else None
            return eventually(f"wakefulness={wakefulness}, display={screen_state}", matches)

        def keyguard_state():
            lines = shell("dumpsys", "window", "policy", timeout=10).splitlines()
            state = {}
            for index, line in enumerate(lines):
                header = re.fullmatch(r"(\s*)KeyguardServiceDelegate\s*", line)
                if header is None:
                    continue
                indentation = len(header.group(1))
                for field in lines[index + 1:]:
                    if not field.strip():
                        continue
                    depth = len(field) - len(field.lstrip())
                    if depth <= indentation:
                        break
                    if depth != indentation + 2:
                        continue
                    match = re.fullmatch(r"\s*(showing|inputRestricted|secure|currentUser)=(\S+)\s*", field)
                    if match:
                        value = match.group(2)
                        state[match.group(1)] = value == "true" if value in ("true", "false") else value
                break
            return state

        def user_state():
            # Nougat has no "activity users" command. Its processes dump includes
            # UserController.dump(), with the same UserState strings as newer APIs.
            section = "users" if int(sdk) >= 29 else "processes"
            output = shell("dumpsys", "activity", section, timeout=10)
            states = re.findall(rf"^\s*User #{re.escape(user)}: state=([A-Z_]+)(?:\s|$)",
                                output, re.MULTILINE)
            return {"user": user, "states": states}

        def wake_and_unlock(description):
            shell("input", "keyevent", "224")  # KEYCODE_WAKEUP, not a power toggle.
            awake = wait_for_power("Awake", "ON")
            # MENU dismisses the unsecured emulator keyguard; it cannot bypass a PIN.
            shell("input", "keyevent", "82")
            def unlocked():
                state = {"keyguard": keyguard_state(), "user": user_state(),
                         "foreground_user": shell("am", "get-current-user", timeout=10)}
                report["last_unlock_state"] = state
                guard = state["keyguard"]
                # Android 14's KeyguardServiceDelegate caches USER_NULL (-10000)
                # until setCurrentUser() is called after a user switch. This cache
                # is not the authoritative foreground user; query ActivityManager.
                return state if (guard.get("secure") is False
                                 and guard.get("showing") is False
                                 and guard.get("inputRestricted") is False
                                 and guard.get("currentUser") in (user, "-10000")
                                 and state["foreground_user"] == user
                                 and state["user"]["states"] == ["RUNNING_UNLOCKED"]) else None
            ready = eventually(f"unsecured keyguard hidden and user unlocked during {description}",
                               unlocked, timeout=45)
            return {"power": awake, **ready}

        def assert_default_home(description):
            actual = resolve_home()
            if actual != canonical(HOME):
                raise RuntimeError(f"Default HOME changed during {description}: {actual}")
            return actual

        def navigation_state():
            # Record before restoring stock HOME, so failures retain their actual state.
            # Keep public annotations focused on navigation, without unrelated logcat.
            transport = report.get("host_transport_at_boot_timeout")
            if transport is None:
                transport = host_transport_diagnostics()
            state = {"focus_dump_command": " ".join(focus_dump), "host_transport": transport}
            try:
                state["adb_state"] = command(adb + ["get-state"], timeout=5)
            except Exception as exc:
                state["connection_error"] = str(exc)[:600]
                state["boot_wait_state"] = report.get("boot_wait_state")
                return state
            probes = {
                "resolved_home": resolve_home,
                "pids": lambda: sorted(pids()),
                "boot_id": boot_id,
                "power": power_state,
                "keyguard": keyguard_state,
                "user": user_state,
                "resumed_activity_lines": lambda: [
                    line.strip()[:600]
                    for line in shell("dumpsys", "activity", "activities", timeout=10).splitlines()
                    if "mResumedActivity" in line or "topResumedActivity" in line
                ][:8],
                "focus_lines": lambda: [
                    line.strip()[:600] for line in shell(*focus_dump, timeout=10).splitlines()
                    if any(marker in line for marker in
                           ("mCurrentFocus", "mFocusedApp", "mTopFocusedDisplayId"))
                ][:8],
            }
            for name, probe in probes.items():
                try:
                    state[name] = probe()
                except Exception as exc:
                    state[name + "_error"] = str(exc)[:600]
            return state

        capture_failure_state = navigation_state
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

        report["sleep_wake"] = {
            "before": wait_for_power("Awake", "ON"),
            "keyguard_before": keyguard_state(),
        }
        shell("input", "keyevent", "223")  # KEYCODE_SLEEP proves an actual power transition.
        report["sleep_wake"]["asleep"] = wait_for_power("Asleep", "OFF")
        report["sleep_wake"]["keyguard_while_asleep"] = keyguard_state()
        report["sleep_wake"]["after"] = wake_and_unlock("screen wake")
        report["sleep_wake"]["home_after_wake"] = assert_default_home("sleep/wake")
        assert_stable_home("HOME after unsecured screen sleep/wake")
        shell("am", "start", "-W", "-a", "android.settings.SETTINGS")
        eventually("Settings foreground after screen wake", settings_is_resumed)
        shell("input", "keyevent", "3")
        assert_stable_home("system HOME from Settings after screen wake")
        report["checks"].append("Verified display OFF/ON and unsecured wake preserve the selected HOME")

        before_reboot = boot_id()
        report["reboot"] = {"boot_id_before": before_reboot, "home_before": resolve_home()}
        try:
            pre_reboot_host_processes = [process for process in host_android_processes()
                                        if process["command"].lower().startswith(("emulator", "qemu"))]
            report["reboot"]["host_processes_before"] = pre_reboot_host_processes
        except Exception as exc:
            report["reboot"]["host_process_inspection_error"] = str(exc)[:500]
        reboot_pending = True
        command(adb + ["reboot"], timeout=15)
        completed_boot = wait_for_boot(previous_boot_id=before_reboot,
                                       expected_host_processes=pre_reboot_host_processes)
        reboot_pending = False
        report["reboot"]["boot_id_after"] = completed_boot["boot_id"]
        current_user = shell("am", "get-current-user", timeout=10)
        if current_user != user:
            raise RuntimeError(f"Foreground Android user changed across reboot: {user} -> {current_user}")
        report["reboot"]["after_unlock"] = wake_and_unlock("reboot")
        # Never repair or reselect HOME here: persistence is the behavior under test.
        report["reboot"]["home_after_unlock"] = assert_default_home("reboot")
        assert_stable_home("automatic HOME after reboot and unsecured unlock")
        shell("am", "start", "-W", "-a", "android.settings.SETTINGS")
        eventually("Settings foreground after reboot", settings_is_resumed)
        shell("input", "keyevent", "3")
        assert_stable_home("system HOME from Settings after reboot")
        report["reboot"]["pids_after"] = sorted(eventually("launcher process after reboot", pids))
        report["checks"].append("New kernel boot preserves default HOME and the HOME key resumes ZenLauncher")

        shell("input", "keyevent", "4")
        assert_stable_home("root HOME to remain after Back following reboot")
        assert_default_home("Back after reboot")
        report["checks"].append("Back is consumed after reboot without changing default HOME")
    except Exception as exc:
        error = str(exc)
        if capture_failure_state is not None:
            report["failure_state"] = capture_failure_state()
            error += "\nNavigation state before cleanup: " + json.dumps(
                report["failure_state"], ensure_ascii=False)
        report["error"] = error
    finally:
        if original is not None:
            try:
                if reboot_pending:
                    # Recovery stays bounded even if the reboot verification timed out.
                    # Accept either boot for cleanup so a rejected reboot can still restore HOME.
                    wait_for_boot(timeout=60, expected_host_processes=pre_reboot_host_processes)
                set_home(original)
                report["restored_home"] = resolve_home()
                # Restore the default even if a keyguard/unlock assertion caused failure.
                wake_and_unlock("cleanup")
                shell("input", "keyevent", "3")
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
          f"{len(report['checks'])} checks passed; process, screen sleep/wake and new-boot HOME/Back "
          "recovery verified on an unsecured emulator, and the original default was restored.", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
