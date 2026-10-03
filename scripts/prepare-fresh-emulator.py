#!/usr/bin/env python3
"""Wait for a fresh API 24/34 emulator's real stock HOME before test installation.

Observes provisioning, the unlocked foreground user, enabled system HOME candidates,
and actual resumed/focused HOME stability. Never writes provisioning settings, assigns
a default HOME, installs an APK, or retries an application test. Only wake/menu/HOME
keys are sent once, after verifying an isolated emulator without ZenLauncher data.
"""

import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

PACKAGE = "com.zenlauncher.app"
COMPONENT = re.compile(r"[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")
STABLE_SECONDS = 5


def canonical(component):
    package, activity = component.split("/", 1)
    return package + "/" + (package + activity if activity.startswith(".") else activity)


def components(output):
    return [line.strip() for line in output.splitlines() if COMPONENT.fullmatch(line.strip())]


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args(argv)
    require(10 <= args.timeout <= 300, "Readiness timeout must be between 10 and 300 seconds")
    started = time.monotonic()
    deadline = started + args.timeout
    report = {"success": False, "timeout_seconds": args.timeout,
              "required_stable_seconds": STABLE_SECONDS, "observations": []}

    def command(parts):
        remaining = deadline - time.monotonic()
        require(remaining > 0, "Fresh-emulator readiness deadline expired")
        result = subprocess.run(parts, capture_output=True, text=True, timeout=min(8, remaining))
        require(result.returncode == 0,
                f"{parts!r}: exit {result.returncode}: {result.stdout[-500:]} {result.stderr[-500:]}")
        return result.stdout.strip()

    try:
        adb_tool = shutil.which(args.adb)
        require(adb_tool is not None, f"Cannot execute adb: {args.adb}")
        devices = [line.split()[0] for line in command([adb_tool, "devices"]).splitlines()[1:]
                   if len(line.split()) == 2 and line.split()[1] == "device"]
        serial = args.serial or (devices[0] if len(devices) == 1 else None)
        require(serial in devices and re.fullmatch(r"emulator-\d+", serial or ""),
                "A single connected emulator or explicit connected emulator serial is required")

        def shell(*parts):
            return command([adb_tool, "-s", serial, "shell", *parts])

        fingerprint = shell("getprop", "ro.build.fingerprint")
        require(shell("getprop", "ro.kernel.qemu") == "1" and any(
            word in fingerprint.lower() for word in ("sdk", "generic", "emulator")),
            "Refusing to prepare an unverified emulator")
        sdk = shell("getprop", "ro.build.version.sdk")
        require(sdk in ("24", "34"), f"Readiness checks support API 24 and 34, not {sdk!r}")
        user = shell("am", "get-current-user")
        require(user.isdigit(), f"Invalid foreground user: {user!r}")
        existing = shell("pm", "list", "packages", "-u", PACKAGE)
        require("package:" + PACKAGE not in existing.splitlines(),
                "Fresh emulator required: ZenLauncher or retained package data already exists")
        report.update(serial=serial, sdk=int(sdk), user=int(user), fingerprint=fingerprint)

        # These keys can dismiss an unsecured emulator lock screen and let the
        # system's own first-boot HOME finish. They cannot bypass a secure lock.
        for key in ("224", "82", "3"):
            shell("input", "keyevent", key)
        report["initial_keys"] = ["WAKEUP", "MENU", "HOME"]

        def observe():
            state = {"boot_completed": shell("getprop", "sys.boot_completed"),
                     "foreground_user": shell("am", "get-current-user"),
                     "device_provisioned": shell("settings", "get", "global", "device_provisioned"),
                     "user_setup_complete": shell("settings", "--user", user, "get", "secure", "user_setup_complete")}
            section = "users" if int(sdk) >= 29 else "processes"
            users = shell("dumpsys", "activity", section)
            state["user_states"] = re.findall(rf"^\s*User #{user}: state=([A-Z_]+)(?:\s|$)", users, re.MULTILINE)
            home_args = ("--user", user, "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
            homes = components(shell("cmd", "package", "resolve-activity", "--brief", *home_args))
            state["home_components"] = homes
            home = canonical(homes[0]) if len(homes) == 1 else None
            state["resolved_home"] = home
            candidates = components(shell("cmd", "package", "query-activities", "--components", *home_args))
            state["enabled_home_candidates"] = [canonical(value) for value in candidates]
            package = home.split("/", 1)[0] if home else None
            state["installed_enabled_system_package"] = False
            if package:
                installed = shell("pm", "list", "packages", "-s", "-e", "--user", user, package)
                state["installed_enabled_system_package"] = "package:" + package in installed.splitlines()
            if int(sdk) >= 29:
                # Reading the actual RoleManager state also proves its shell service
                # is responsive. Empty/mismatched holders are recorded, never repaired.
                state["home_role_holders"] = shell("cmd", "role", "get-role-holders", "--user", user,
                                                    "android.app.role.HOME").splitlines()
            activities = shell("dumpsys", "activity", "activities")
            windows = shell("dumpsys", "window", "displays" if sdk == "34" else "windows")

            def foreground_components(output, fields):
                found = []
                for line in output.splitlines():
                    if any(field in line for field in fields):
                        match = COMPONENT.search(line)
                        if match:
                            found.append(canonical(match[0]))
                return found

            state["resumed"] = foreground_components(activities, ("mResumedActivity", "topResumedActivity"))
            state["focused"] = foreground_components(windows, ("mCurrentFocus=",))
            state["home_after_observation"] = [canonical(value) for value in components(
                shell("cmd", "package", "resolve-activity", "--brief", *home_args))]
            state["foreground_user_after_observation"] = shell("am", "get-current-user")
            problems = []
            for field in ("boot_completed", "device_provisioned", "user_setup_complete"):
                if state[field] != "1":
                    problems.append(field + " is not 1")
            if state["foreground_user"] != user or state["foreground_user_after_observation"] != user:
                problems.append("Foreground user changed")
            if state["user_states"] != ["RUNNING_UNLOCKED"]:
                problems.append("Foreground user is not RUNNING_UNLOCKED")
            if not home or package in (PACKAGE, "android") or any(
                    word in home.lower() for word in ("setup", "provision", "resolver")):
                problems.append("HOME is absent or a temporary setup/resolver component")
            if not state["installed_enabled_system_package"] or home not in state["enabled_home_candidates"]:
                problems.append("HOME is not an installed, enabled system HOME candidate")
            if home is None or not state["resumed"] or not state["focused"] or any(
                    value != home for value in state["resumed"] + state["focused"]):
                problems.append("Stock HOME is not consistently resumed and focused")
            if state["home_after_observation"] != [home]:
                problems.append("HOME changed during the observation")
            if int(sdk) >= 29 and state["home_role_holders"] != [package]:
                problems.append("HOME role holder has not settled on the stock HOME package")
            state["problems"] = problems
            return state

        stable_since = None
        stable_home = None
        samples = 0
        while time.monotonic() < deadline:
            try:
                state = observe()
            except (RuntimeError, subprocess.SubprocessError) as exc:
                state = {"problems": [str(exc)[:1000]]}
            state["elapsed_seconds"] = round(time.monotonic() - started, 3)
            report["observations"].append(state)
            report["observations"] = report["observations"][-30:]
            if state["problems"]:
                stable_since = None
                stable_home = None
                samples = 0
            else:
                if state["resolved_home"] != stable_home:
                    stable_home = state["resolved_home"]
                    stable_since = time.monotonic()
                    samples = 0
                samples += 1
                elapsed = time.monotonic() - stable_since
                if elapsed >= STABLE_SECONDS and samples >= 3:
                    report.update(success=True, original_home=state["home_components"][0],
                                  stable_home=stable_home, stable_seconds=round(elapsed, 3),
                                  stable_observations=samples)
                    break
            time.sleep(min(0.5, max(0, deadline - time.monotonic())))
        require(report["success"], "Fresh emulator did not reach provisioned, unlocked, stable stock HOME "
                f"within {args.timeout}s; last state: {report['observations'][-1] if report['observations'] else {}}")
    except Exception as exc:
        report["error"] = str(exc)
    finally:
        report["elapsed_seconds"] = round(time.monotonic() - started, 3)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(report, indent=2), flush=True)
    if not report["success"]:
        escaped = report["error"].replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print("::error title=Fresh emulator readiness failed::" + escaped, flush=True)
        return 1
    print(f"::notice title=Fresh emulator ready::API {report['sdk']}, user {report['user']}, "
          f"stock HOME {report['original_home']}; verified stable for {report['stable_seconds']}s.", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
