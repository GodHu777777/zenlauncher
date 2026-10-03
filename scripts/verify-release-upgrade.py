#!/usr/bin/env python3
"""Verify a signed Release upgrade through real UI on a fresh API 24/34 emulator.

Requires adb, aapt and apksigner (PATH, ANDROID_HOME/ANDROID_SDK_ROOT, or explicit
options). Refuses existing installations, including retained package data. Never
uninstalls, clears data, downgrades, or repairs HOME after installing the update.
The original system HOME is restored; the upgraded app and its data are retained.
Run in a separate fresh AVD, before any debug instrumentation installs this package.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.zenlauncher.app"
HOME = PACKAGE + "/com.zenlauncher.app.MainActivity"
SETTINGS = PACKAGE + "/com.zenlauncher.app.SettingsActivity"
RELEASE_CERT = "337b92f7bda67e7c2ea553ba969e707257c3d438c3a9320c94f58b3414e7820c"
COMPONENT = re.compile(r"[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")


def command(args, timeout=20, allow_missing=False):
    result = subprocess.run([str(arg) for arg in args], text=True, capture_output=True,
                            timeout=timeout)
    if result.returncode and not (allow_missing and result.returncode == 1):
        raise RuntimeError(f"{args!r}: exit {result.returncode}: "
                           f"{result.stdout.strip()} {result.stderr.strip()}")
    return result.stdout.strip()


def eventually(description, probe, timeout=30):
    deadline = time.monotonic() + timeout
    while True:
        value = probe()
        if value:
            return value
        if time.monotonic() >= deadline:
            raise RuntimeError(f"Timed out waiting for {description}")
        time.sleep(0.2)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def canonical(component):
    require(COMPONENT.fullmatch(component), f"Invalid component: {component!r}")
    package, activity = component.split("/", 1)
    return package + "/" + (package + activity if activity.startswith(".") else activity)


def sdk_tool(name, explicit):
    candidate = explicit or os.environ.get(name.upper()) or shutil.which(name)
    if candidate:
        resolved = shutil.which(str(candidate))
        require(resolved is not None, f"Cannot execute {name}: {candidate}")
        return resolved
    for variable in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        root = os.environ.get(variable)
        if not root:
            continue
        paths = ([Path(root) / "platform-tools" / name] if name == "adb" else
                 list((Path(root) / "build-tools").glob("*/" + name)))
        paths.sort(key=lambda path: tuple(map(int, re.findall(r"\d+", path.parent.name))),
                   reverse=True)
        for path in paths:
            if path.is_file() and os.access(path, os.X_OK):
                return str(path)
    raise RuntimeError(f"{name} not found; supply --{name} or an Android SDK environment")


def inspect_apk(path, aapt, apksigner):
    require(path.is_file(), f"APK does not exist: {path}")
    badging = command([aapt, "dump", "badging", path])
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'",
                        badging, re.MULTILINE)
    require(package is not None, f"Cannot read APK package/version: {path}")
    require(package[1] == PACKAGE, f"Unexpected APK package: {package[1]}")
    require(not re.search(r"^application-debuggable\b", badging, re.MULTILINE),
            f"Refusing a debuggable APK: {path}")
    # These APKs have minSdk 24. Force a pre-v2 platform into verification so apksigner
    # actually checks V1 as well, matching the release CI's permanent-signature check.
    signature = command([apksigner, "verify", "--min-sdk-version", "23", "--verbose", "--print-certs", path])
    for scheme in (1, 2):
        require(re.search(rf"^Verified using v{scheme} scheme .*: true$", signature,
                          re.MULTILINE), f"APK must verify with v{scheme} signing: {path}")
    certificates = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$",
                              signature, re.MULTILINE)
    require(len(certificates) == 1, f"Expected exactly one APK signer: {path}")
    certificate = certificates[0].lower()
    require(certificate == RELEASE_CERT, f"APK does not use the permanent ZenLauncher key: {path}")
    minimum = re.search(r"^sdkVersion:'(\d+)'$", badging, re.MULTILINE)
    require(minimum is not None, f"Cannot read APK minSdk: {path}")
    return {"path": str(path.resolve()), "package": package[1], "version_code": int(package[2]),
            "version_name": package[3], "min_sdk": int(minimum[1]), "debuggable": False,
            "certificate_sha256": certificate, "v1_verified": True, "v2_verified": True,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--old-apk", required=True, type=Path)
    parser.add_argument("--new-apk", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--serial")
    for tool in ("adb", "aapt", "apksigner"):
        parser.add_argument("--" + tool)
    args = parser.parse_args(argv)
    report = {"success": False, "checks": [], "phase": "preflight"}
    cleanup_needed = False
    capture_failure = None
    original = None
    error = None
    last_xml = ""
    try:
        tools = {name: sdk_tool(name, getattr(args, name)) for name in ("adb", "aapt", "apksigner")}
        old = inspect_apk(args.old_apk, tools["aapt"], tools["apksigner"])
        new = inspect_apk(args.new_apk, tools["aapt"], tools["apksigner"])
        require(new["version_code"] > old["version_code"], "New versionCode must be strictly greater")
        require(new["certificate_sha256"] == old["certificate_sha256"], "APK signing certificates differ")
        report.update(old_apk=old, new_apk=new)
        devices = [line.split()[0] for line in command([tools["adb"], "devices"]).splitlines()[1:]
                   if len(line.split()) == 2 and line.split()[1] == "device"]
        serial = args.serial or (devices[0] if len(devices) == 1 else None)
        require(serial in devices and re.fullmatch(r"emulator-\d+", serial or ""),
                "Exactly one connected emulator, or an explicit connected emulator serial, is required")
        adb = [tools["adb"], "-s", serial]

        def shell(*parts, timeout=15, allow_missing=False):
            return command(adb + ["shell", *parts], timeout=timeout, allow_missing=allow_missing)

        fingerprint = shell("getprop", "ro.build.fingerprint")
        require(shell("getprop", "ro.kernel.qemu") == "1" and any(
            marker in fingerprint.lower() for marker in ("generic", "sdk", "emulator")),
            "Refusing to change an unverified emulator")
        user = shell("am", "get-current-user")
        require(user.isdigit(), f"Invalid foreground user: {user!r}")
        sdk = shell("getprop", "ro.build.version.sdk")
        require(sdk in ("24", "34"), f"This UI verification supports API 24 and 34, not {sdk!r}")
        require(max(old["min_sdk"], new["min_sdk"]) <= int(sdk), "APK minSdk exceeds emulator API")
        packages = shell("pm", "list", "packages", "-u", PACKAGE)
        package_dump = shell("dumpsys", "package", PACKAGE)
        require(not any(line.startswith("package:" + PACKAGE) for line in packages.splitlines())
                and not re.search(r"Package \[" + re.escape(PACKAGE) + r"\]", package_dump),
                "Use a fresh AVD: ZenLauncher or retained package data already exists; nothing was removed")
        report.update(serial=serial, sdk=int(sdk), user=int(user), fingerprint=fingerprint)
        focus_section = "displays" if sdk == "34" else "windows"

        def same_user():
            actual = shell("am", "get-current-user", timeout=5)
            require(actual == user, f"Foreground user changed: {user} -> {actual}")

        def resolve_home():
            output = shell("cmd", "package", "resolve-activity", "--brief", "--user", user,
                           "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
            matches = [line.strip() for line in output.splitlines() if COMPONENT.fullmatch(line.strip())]
            require(len(matches) == 1, f"Cannot resolve one HOME: {output!r}")
            return canonical(matches[0])

        def set_home(component, timeout=15):
            shell("cmd", "package", "set-home-activity", "--user", user, component, timeout=timeout)
            eventually("HOME assignment " + component, lambda: resolve_home() == canonical(component))

        def assert_default():
            same_user()
            actual = resolve_home()
            require(actual == HOME, f"Default HOME was lost: {actual}; will not repair the test")
            return actual

        def foreground(package, component=None):
            activities = shell("dumpsys", "activity", "activities", timeout=8)
            windows = shell("dumpsys", "window", focus_section, timeout=8)
            def matches(line):
                found = COMPONENT.search(line)
                return found is not None and (canonical(found[0]) == component if component
                                             else found[0].split("/", 1)[0] == package)
            return (any(matches(line) for line in activities.splitlines()
                        if "mResumedActivity" in line or "topResumedActivity" in line)
                    and any(matches(line) for line in windows.splitlines() if "mCurrentFocus=" in line))

        def stable_home(description):
            assert_default()
            eventually(description, lambda: foreground(PACKAGE, HOME))
            deadline = time.monotonic() + 1.5
            while time.monotonic() < deadline:
                require(foreground(PACKAGE, HOME), f"HOME lost focus/resumed state during {description}")
                time.sleep(0.1)
            assert_default()

        def home_key():
            shell("input", "keyevent", "3")

        def system_settings():
            shell("am", "start", "--user", user, "-W", "-a", "android.settings.SETTINGS")
            eventually("system Settings resumed and focused", lambda: foreground("com.android.settings"))

        def pids():
            return set(shell("pidof", PACKAGE, allow_missing=True, timeout=5).split())

        def installed_version(expected):
            data = shell("dumpsys", "package", PACKAGE)
            version = re.search(r"\bversionCode=(\d+)\b", data)
            name = re.search(r"^\s*versionName=(.+)$", data, re.MULTILINE)
            require(version and name, "Cannot read installed package version")
            require(int(version[1]) == expected["version_code"] and name[1].strip() == expected["version_name"],
                    f"Installed package has unexpected version: {version[1]} / {name[1].strip()}")
            return {"version_code": int(version[1]), "version_name": name[1].strip()}

        def install(path, replace):
            output = command(adb + ["install"] + (["-r"] if replace else []) + [str(path)], timeout=120)
            require("Success" in output.splitlines(), f"APK install did not report Success: {output}")

        ui_path = "/sdcard/zen-upgrade-" + secrets.token_hex(8) + ".xml"

        def ui():
            nonlocal last_xml
            output = shell("uiautomator", "dump", ui_path, timeout=20)
            require("dumped to:" in output, f"UI dump failed; refusing a stale hierarchy: {output}")
            last_xml = shell("cat", ui_path)
            return ET.fromstring(last_xml)

        def bounds(node):
            value = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
            require(value is not None, f"Invalid UI bounds: {node.attrib}")
            return tuple(map(int, value.groups()))

        def find(tree, **attributes):
            matches = [node for node in tree.iter("node") if node.get("package") == PACKAGE
                       and all(node.get(key) == value for key, value in attributes.items())
                       and bounds(node)[2] > bounds(node)[0] and bounds(node)[3] > bounds(node)[1]]
            require(len(matches) <= 1, f"Ambiguous UI selector: {attributes}")
            return matches[0] if matches else None

        def wait_node(**attributes):
            # ElementTree leaf nodes are falsey; return a tuple so a real match is always truthy.
            def probe():
                node = find(ui(), **attributes)
                return (node,) if node is not None else None
            return eventually("UI node " + repr(attributes), probe)[0]

        def tap(node):
            x1, y1, x2, y2 = bounds(node)
            require(node.get("enabled") == "true", f"Disabled UI control: {node.attrib}")
            shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

        def scroll_to_motto():
            for attempt in range(7):
                tree = ui()
                node = find(tree, **{"resource-id": PACKAGE + ":id/btnCustomMotto"})
                if node is not None:
                    return node
                require(attempt < 6, "Motto setting did not appear after bounded scrolling")
                scroll = find(tree, **{"class": "android.widget.ScrollView", "scrollable": "true"})
                require(scroll is not None, "Settings has no visible scrollable container")
                x1, y1, x2, y2 = bounds(scroll)
                shell("input", "swipe", str((x1 + x2) // 2), str(y1 + (y2 - y1) * 3 // 4),
                      str((x1 + x2) // 2), str(y1 + (y2 - y1) // 4), "300")

        def open_motto_dialog():
            tap(wait_node(**{"resource-id": PACKAGE + ":id/btnSettings"}))
            eventually("launcher Settings focused", lambda: foreground(PACKAGE, SETTINGS))
            tap(scroll_to_motto())
            wait_node(text="修改专注标语")
            return wait_node(**{"class": "android.widget.EditText"})

        marker = f"ZENUP{old['version_code']}TO{new['version_code']}API{sdk}" + secrets.token_hex(4).upper()
        report["motto_marker"] = marker

        def assert_motto():
            wait_node(**{"resource-id": PACKAGE + ":id/tvMotto", "text": marker})

        def failure_state():
            state = {}
            for name, probe in {
                "foreground_user": lambda: shell("am", "get-current-user", timeout=5),
                "resolved_home": resolve_home,
                "pids": lambda: sorted(pids()),
                "activity": lambda: shell("dumpsys", "activity", "activities", timeout=5),
                "windows": lambda: shell("dumpsys", "window", focus_section, timeout=5),
            }.items():
                try:
                    value = probe()
                    state[name] = ([line.strip() for line in value.splitlines() if any(
                        term in line for term in ("mResumedActivity", "topResumedActivity", "mCurrentFocus=", "mFocusedApp="))]
                        if name in ("activity", "windows") else value)
                except Exception as exc:
                    state[name + "_error"] = str(exc)[:500]
            if last_xml:
                args.output.parent.mkdir(parents=True, exist_ok=True)
                path = args.output.with_suffix(".last-ui.xml")
                path.write_text(last_xml)
                state["last_ui_xml"] = str(path)
            return state

        capture_failure = failure_state
        original = resolve_home()
        require(original.split("/", 1)[0] not in (PACKAGE, "android") and "resolver" not in original.lower(),
                f"A restorable stock HOME is required: {original}")
        candidates = shell("cmd", "package", "query-activities", "--components", "--user", user,
                           "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
        require(original in [canonical(line.strip()) for line in candidates.splitlines()
                             if COMPONENT.fullmatch(line.strip())], "Original HOME is not an enabled HOME candidate")
        report["original_home"] = original
        report["checks"].append("Fresh emulator and both non-debug Release APK signatures/versions verified")

        report["phase"] = "old_release_setup"
        cleanup_needed = True  # Installing a HOME candidate can itself clear the old HOME selection.
        install(args.old_apk, replace=False)
        report["installed_old"] = installed_version(old)
        # Android 14 runSetHomeActivity waits on RoleManager's asynchronous future.get().
        # Give this initial fixture command one bounded 60s attempt; this does not
        # change any post-upgrade assertion or permit a second Zen HOME assignment.
        assignment_started = time.monotonic()
        report["initial_home_assignment_timeout_seconds"] = 60
        try:
            set_home(HOME, timeout=60)  # The ONLY assignment of Zen HOME in this test.
        finally:
            report["initial_home_assignment_seconds"] = round(time.monotonic() - assignment_started, 3)
        home_key()
        stable_home("initial old Release HOME")
        edit = open_motto_dialog()
        original_text = edit.get("text", "")
        require(len(original_text) <= 128, "Unexpectedly long default motto on a fresh installation")
        tap(edit)
        wait_node(**{"class": "android.widget.EditText", "focused": "true"})
        deletes = len(original_text.encode("utf-16le")) // 2 + 2
        shell("input", "keyevent", "123", *(["67"] * deletes))
        wait_node(**{"class": "android.widget.EditText", "text": ""})
        shell("input", "text", marker)
        wait_node(**{"class": "android.widget.EditText", "text": marker})
        tap(wait_node(**{"resource-id": "android:id/button1", "text": "保存"}))
        wait_node(**{"resource-id": PACKAGE + ":id/tvMottoSummary", "text": marker})
        home_key()
        stable_home("old Release HOME after saving motto")
        assert_motto()

        report["phase"] = "old_release_persistence"
        system_settings()
        old_pids = eventually("background old Release process", pids)
        shell("am", "force-stop", "--user", user, PACKAGE)
        eventually("old process exit", lambda: not (pids() & old_pids))
        require(foreground("com.android.settings"), "System Settings lost focus after force-stop")
        home_key()
        stable_home("old Release HOME after force-stop")
        assert_motto()
        baseline_pids = eventually("fresh old Release process", pids)
        require(not (old_pids & baseline_pids), "Old process did not restart")
        report["baseline"] = {"pids_before_stop": sorted(old_pids), "pids_after_restart": sorted(baseline_pids),
                              "home": assert_default(), "motto": marker}
        report["checks"].append("Motto saved through UI survives old Release force-stop and system HOME restart")

        report["phase"] = "release_replacement"
        system_settings()
        assert_default()
        install(args.new_apk, replace=True)
        report["installed_new"] = installed_version(new)
        report["home_immediately_after_upgrade"] = assert_default()
        require(foreground("com.android.settings"), "System Settings lost focus during package replacement")
        # No HOME reassignment, explicit app launch, restore, or recovery is allowed below.
        home_key()
        stable_home("new Release HOME after replacement")
        assert_motto()
        new_pids = eventually("new Release process", pids)
        require(not (new_pids & baseline_pids), "Package replacement retained the old process")
        report["new_pids"] = sorted(new_pids)
        report["checks"].append("Same-key install -r upgrades the version and preserves default HOME and the on-disk motto")

        report["phase"] = "new_release_navigation"
        shell("input", "keyevent", "4")
        stable_home("root Back after Release upgrade")
        assert_motto()
        require(open_motto_dialog().get("text") == marker, "New Release settings read a different motto")
        tap(wait_node(**{"resource-id": "android:id/button2", "text": "取消"}))
        wait_node(**{"resource-id": PACKAGE + ":id/tvMottoSummary", "text": marker})
        home_key()
        stable_home("final upgraded HOME")
        assert_motto()
        installed_version(new)
        report["checks"].append("Upgraded root Back stays on HOME; settings and desktop both retain the exact motto")
        report["phase"] = "verified"
    except Exception as exc:
        error = str(exc)
        report["error"] = error
        if capture_failure is not None:
            try:
                report["failure_state"] = capture_failure()
            except Exception as capture_error:
                report["failure_state_error"] = str(capture_error)
    finally:
        if cleanup_needed and original is not None:
            try:
                set_home(original)
                report["restored_home"] = resolve_home()
                same_user()
                home_key()
                eventually("restored stock HOME focused", lambda: foreground(original.split("/", 1)[0], original))
            except Exception as exc:
                error = error or "Failed to restore original HOME"
                report["cleanup_error"] = str(exc)
        report["success"] = error is None
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
    if error:
        escaped = error.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::error title=Release upgrade verification failed::{escaped}", flush=True)
        return 1
    print(f"::notice title=Release upgrade verified::API {report['sdk']}: "
          f"{old['version_name']} -> {new['version_name']}; default HOME, motto and Back retained; "
          "stock HOME restored; upgraded app data retained.", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
