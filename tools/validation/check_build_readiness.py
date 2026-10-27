#!/usr/bin/env python3
"""Host-only guard for the clean, unconfigured OpenRideSync debug review profile.

Exit 0: this command's checks passed. Exit 1: a check failed. Exit 2: required
evidence/tools were unavailable. Neither result certifies device readiness.
No Gradle/SDK installation, ADB, account requests, or device changes are made.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
import zipfile
import zlib

from dex_build_config import Dex, DexError


PROFILE = {
    "name": "clean-unconfigured-debug-review",
    "package": "org.openridesync.app",
    "version_code": 1,
    "version_name": "0.1.0-dev",
    "min_sdk": 30,
    "target_sdk": 36,
    "debuggable": True,
}
ALLOWED_ASSETS = {"assets/PublicSuffixDatabase.list", "assets/openridesync-login.html"}
FORBIDDEN_RESOURCE_NAMES = {
    "google_api_key", "google_app_id", "default_web_client_id",
    "firebase_database_url", "google_storage_bucket", "gcm_defaultSenderId",
}
MAX_ENTRY = 128 * 1024 * 1024
MAX_TOTAL = 512 * 1024 * 1024
MAX_ENTRIES = 20000
PATTERNS = {
    "google_api_key": rb"AIza[0-9A-Za-z_-]{35}",
    "google_access_token": rb"ya29\.[A-Za-z0-9._-]{24,}",
    "github_token": rb"(?:gh[pousr]_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{80,})",
    "aws_access_key_id": rb"AKIA[A-Z0-9]{16}",
    "jwt_shaped_value": rb"eyJ[A-Za-z0-9_-]{16,}\.[A-Za-z0-9_-]{24,}\.[A-Za-z0-9_-]{16,}",
    "pem_private_key": (rb"-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----"
                        rb"[\r\n]+[A-Za-z0-9+/=\r\n]{64,}"
                        rb"-----END (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----"),
}
PATTERNS = {name: re.compile(pattern) for name, pattern in PATTERNS.items()}
UNFINISHED = [
    "Complete OEM-equivalent application/settings and end-to-end user experience",
    "Map/navigation/routing/guidance integration",
    "Trip recording/upload/deletion and complete synchronization",
    "Automatic vehicle reconnection and advanced recovery",
    "End-to-end calls/voice/notification interruption and recovery",
    "Real Android UX, permission/lifecycle/media and vehicle acceptance for this exact artifact",
]
LIMITS = [
    "Clean profile intentionally omits private Honda configuration and OEM UI assets; real authentication is unavailable.",
    "Embedded SOURCE_COMMIT is a declaration, not proof the checkout matched Git; independently verify source blobs before building.",
    "Expected certificate identifies this file only; no installed candidate or upgrade/signing compatibility is examined.",
    "Credential checks cover enumerated byte patterns and named APK entries/resources only, not arbitrary, encrypted, obfuscated, split or remotely fetched secrets/assets.",
    "No Android execution, installation, login, BLE, vehicle control, pixel/accessibility review or security certification is performed.",
]
COPY_INPUTS = (
    "settings.gradle.kts", "build.gradle.kts", "gradle.properties",
    "gradle/libs.versions.toml", "app/build.gradle.kts",
    "diagnostics/build.gradle.kts", "auth-bridge/build.gradle.kts", "honda/build.gradle.kts",
)


class CheckFailure(ValueError):
    pass


class MissingEvidence(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise CheckFailure(message)


def command_output(command, cwd=None, timeout=120):
    executable = shutil.which(str(command[0]))
    if executable is None:
        raise MissingEvidence("Required executable is unavailable: " + Path(command[0]).name)
    try:
        return subprocess.run([executable, *command[1:]], cwd=cwd, capture_output=True,
                              text=True, errors="replace", timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise MissingEvidence("Required command could not complete") from error


def validate_intent(args):
    require(re.fullmatch(r"[0-9a-f]{40}", args.source_commit) is not None,
            "A full lowercase 40-character source commit is required; unknown/short SHA is insufficient")
    require(args.with_honda_config == "false" and args.with_oem_ui_assets == "false",
            "This guard covers only explicitly unconfigured builds: both private opt-ins must be false")


def parse_badging(text):
    package_lines = re.findall(r"^package: (.+)$", text, re.M)
    require(len(package_lines) == 1, "Expected one package identity in aapt2 badging")
    fields = dict(re.findall(r"(\w+)='([^']*)'", package_lines[0]))
    result = {"package": fields.get("name"), "version_name": fields.get("versionName")}
    require(fields.get("versionCode", "").isdigit(), "Missing numeric APK versionCode")
    result["version_code"] = int(fields["versionCode"])
    for output, label in (("min_sdk", "(?:minSdkVersion|sdkVersion)"), ("target_sdk", "targetSdkVersion")):
        values = re.findall(r"^" + label + r":'(\d+)'$", text, re.M)
        require(len(values) == 1, "Missing or ambiguous APK SDK metadata")
        result[output] = int(values[0])
    result["debuggable"] = bool(re.search(r"^application-debuggable\s*$", text, re.M))
    require(all(result[key] == PROFILE[key] for key in result), "Manifest identity differs from clean review profile")
    return result


def parse_signer(text, expected):
    require(re.fullmatch(r"[0-9a-f]{64}", expected) is not None,
            "Expected signer SHA-256 must be 64 lowercase hex characters from a trusted prior record")
    certs = re.findall(r"^Signer #(\d+) certificate SHA-256 digest: ([0-9A-Fa-f]+)\s*$", text, re.M)
    require([(number, digest.lower()) for number, digest in certs] == [("1", expected)],
            "APK signer count or certificate SHA-256 differs from explicit expected identity")
    require(re.search(r"^Verified using v2 scheme .*: true\s*$", text, re.M), "APK v2 verification was not confirmed")
    subjects = re.findall(r"^Signer #1 certificate DN: (.+)$", text, re.M)
    require(len(subjects) == 1 and re.search(r"(?:^|,\s*)CN=Android Debug(?:,|$)", subjects[0]),
            "Expected an Android Debug certificate subject for this review profile")
    return {"certificate_sha256": expected, "subject": subjects[0], "verified_v2": True,
            "installed_candidate_compatibility": "not_checked"}


def resource_names(text):
    names = set(re.findall(r"^\s*resource 0x[0-9a-fA-F]+ (?:[\w.]+:)?[\w]+/([\w.]+)(?:\s|$)", text, re.M))
    require(bool(names), "aapt2 resource table could not be parsed")
    forbidden = {name for name in names if name in FORBIDDEN_RESOURCE_NAMES or name.startswith(("oem_ui_", "oem_ride_"))}
    require(not forbidden, "Private configuration or OEM resource names found: " + ", ".join(sorted(forbidden)))
    return {"parsed_resource_count": len(names), "forbidden_names_found": []}


def inspect_zip(apk, source_commit):
    require(apk.is_file(), "APK file is missing")
    require(apk.stat().st_size <= MAX_TOTAL, "APK exceeds inspection limit")
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    configs, dex_names, assets, findings = [], [], [], []
    total = 0
    with zipfile.ZipFile(apk) as archive:
        infos = archive.infolist()
        require(0 < len(infos) <= MAX_ENTRIES, "Invalid ZIP entry count")
        names = [info.filename for info in infos]
        require(len(names) == len(set(names)), "Duplicate ZIP entries are not accepted")
        require("AndroidManifest.xml" in names and "resources.arsc" in names, "APK manifest/resources missing")
        for info in infos:
            name, parts = info.filename, PurePosixPath(info.filename).parts
            require(not name.startswith("/") and "\\" not in name and ".." not in parts,
                    "Unsafe ZIP entry path")
            require(not info.flag_bits & 1, "Encrypted ZIP entry cannot be inspected")
            require(info.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED),
                    "Unsupported APK ZIP compression method")
            require(not stat.S_ISLNK(info.external_attr >> 16), "Symlink ZIP entries are not accepted")
            total += info.file_size
            require(info.file_size <= MAX_ENTRY and total <= MAX_TOTAL, "APK decompression limit exceeded")
            lower = name.lower()
            lower_parts = PurePosixPath(lower).parts
            require(not ("private" in lower_parts or "device-validation" in lower_parts or
                         "oem_ui_" in lower or "oem_ride_" in lower or "oem-media" in lower or
                         PurePosixPath(lower).name in {"auth-client.json", "google-services.json"} or
                         lower.endswith((".jks", ".keystore", ".p12", ".pfx"))),
                    "Forbidden private/OEM/configuration ZIP entry found")
            if name.startswith("assets/") and not info.is_dir():
                assets.append(name)
                require(name in ALLOWED_ASSETS, "Unexpected asset outside the explicit clean-profile allowlist")
            data = archive.read(info)  # zipfile verifies CRC; never extract files.
            views = [data]
            if name == "resources.arsc" or lower.endswith(".xml"):
                # ASCII credentials encoded as UTF-16 LE/BE at either alignment.
                views += [data[::2], data[1::2]]
            for pattern_name, pattern in PATTERNS.items():
                if any(pattern.search(view) for view in views):
                    findings.append({"entry": name, "pattern": pattern_name})
            if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name):
                dex_names.append(name)
                configs.extend(Dex(data).build_config(PROFILE["package"]))
            elif lower.endswith(".dex"):
                raise CheckFailure("Unexpected DEX path outside the supported classes*.dex layout")
        require(set(assets) == ALLOWED_ASSETS, "Expected clean-profile assets are missing")
    # Never include matched token/key contents in reports or error text.
    require(not findings, "Supported credential pattern found in APK: " +
            ", ".join(sorted({row["pattern"] for row in findings})))
    require(len(configs) == 1, "Expected exactly one application BuildConfig definition")
    expected = {"APPLICATION_ID": PROFILE["package"], "BUILD_TYPE": "debug",
                "VERSION_CODE": PROFILE["version_code"], "VERSION_NAME": PROFILE["version_name"],
                "SOURCE_COMMIT": source_commit}
    require(all(type(configs[0].get(key)) is type(value) and configs[0].get(key) == value
                for key, value in expected.items()),
            "Named BuildConfig static identity differs from expected source/version/package")
    return {"sha256": digest, "bytes": apk.stat().st_size, "zip_entries": len(infos),
            "uncompressed_bytes": total, "dex_files": len(dex_names), "build_config": expected,
            "assets": sorted(assets), "credential_pattern_ids": sorted(PATTERNS),
            "credential_pattern_findings": [], "zip_integrity": "passed"}


def inspect_artifact(args, report):
    apk = args.apk.resolve()
    report["artifact"] = inspect_zip(apk, args.source_commit)
    if not args.aapt2 or not args.apksigner or not args.expected_signer_sha256:
        raise MissingEvidence("ZIP/DEX checks completed; full audit requires aapt2, apksigner, and expected signer SHA-256")
    result = command_output([args.aapt2, "dump", "badging", str(apk)])
    require(result.returncode == 0, "aapt2 badging failed")
    report["manifest"] = parse_badging(result.stdout)
    result = command_output([args.aapt2, "dump", "resources", str(apk)])
    require(result.returncode == 0, "aapt2 resource inspection failed")
    report["resources"] = resource_names(result.stdout)
    result = command_output([args.apksigner, "verify", "--verbose", "--print-certs", str(apk)])
    require(result.returncode == 0, "apksigner cryptographic verification failed")
    report["signer"] = parse_signer(result.stdout, args.expected_signer_sha256)
    require(hashlib.sha256(apk.read_bytes()).hexdigest() == report["artifact"]["sha256"],
            "APK changed while being inspected")


def stage_config_inputs(repo, destination):
    android = repo
    staged = destination
    hashes = {}
    for relative in COPY_INPUTS:
        source = android / relative
        require(source.is_file() and source.resolve().is_relative_to(android.resolve()),
                "Required public Gradle input is missing or resolves outside Android project")
        data = source.read_bytes()
        target = staged / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        hashes[relative] = hashlib.sha256(data).hexdigest()
    return staged, hashes


def inspect_config(args, report):
    if not args.gradle:
        raise MissingEvidence("An existing installed Gradle executable/runner is required; no wrapper download is attempted")
    # Serialize this command with all other Gradle work. Each case invokes the
    # actual copied project scripts, with no private source/local.properties.
    with tempfile.TemporaryDirectory(prefix="openridesync-config-") as temporary:
        project, hashes = stage_config_inputs(args.repo.resolve(), Path(temporary))
        report["configuration_input_sha256"] = hashes
        cases = [
            ("clean_defaults", args.source_commit, None, None, None),
            ("clean_explicit", args.source_commit, "false", "false", None),
            ("missing_honda", args.source_commit, "true", "false",
             "Prepare private auth-client.json before enabling withHondaConfig"),
            ("missing_oem", args.source_commit, "false", "true",
             "OEM assets are excluded from this public repository"),
            ("invalid_source_commit", "not-a-git-sha", "false", "false",
             "sourceCommit must be a Git SHA or unknown"),
        ]
        report["configuration_probes"] = []
        for label, commit, honda, oem, expected_error in cases:
            command = [args.gradle, "--offline", "--no-daemon", "--max-workers=1",
                       "--console=plain", "--project-dir", str(project), ":app:help",
                       "-Pandroid.builder.sdkDownload=false", "-PsourceCommit=" + commit]
            if honda is not None:
                command += ["-PwithHondaConfig=" + honda, "-PwithOemUiAssets=" + oem]
            result = command_output(command, cwd=project, timeout=args.timeout)
            output = result.stdout + result.stderr
            if expected_error is None:
                passed = result.returncode == 0 and "BUILD SUCCESSFUL" in output
            else:
                passed = result.returncode != 0 and expected_error in output
            report["configuration_probes"].append({"case": label, "passed": passed,
                                                    "exit_code": result.returncode,
                                                    "expected_message_seen": expected_error in output if expected_error else None})
            require(passed, "Configuration probe failed: " + label + "; no fail-fast claim can be made from an unrelated failure")
    report["configuration_scope"] = "Real Gradle configuration of copied public scripts only; copies no private inputs; no compilation or positive configured build; ambient Gradle settings are not audited"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("artifact", "config"))
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--with-honda-config", choices=("true", "false"), required=True)
    parser.add_argument("--with-oem-ui-assets", choices=("true", "false"), required=True)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--aapt2", help="Existing official Build Tools executable")
    parser.add_argument("--apksigner", help="Existing official Build Tools executable")
    parser.add_argument("--expected-signer-sha256", help="Independent trusted expected debug certificate identity")
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--gradle", help="Existing installed executable/runner; must honor --project-dir; never use a downloader")
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args(argv)
    if args.mode == "artifact" and args.apk is None:
        parser.error("artifact mode requires --apk")
    report = {"schema": 1, "mode": args.mode, "profile": PROFILE,
              "requested_source_commit": args.source_commit,
              "explicit_private_flags": {"withHondaConfig": args.with_honda_config,
                                         "withOemUiAssets": args.with_oem_ui_assets},
              "unfinished_features": UNFINISHED, "limitations": LIMITS}
    exit_code = 0
    try:
        validate_intent(args)
        (inspect_artifact if args.mode == "artifact" else inspect_config)(args, report)
        report["status"] = "passed_host_checks_only"
    except MissingEvidence as error:
        report.update(status="incomplete", reason=str(error))
        exit_code = 2
    except (CheckFailure, DexError, OSError, zipfile.BadZipFile, UnicodeError,
            struct.error, zlib.error, NotImplementedError) as error:
        report.update(status="failed", reason=str(error))
        exit_code = 1
    print(json.dumps(report, indent=2, ensure_ascii=False))
    return exit_code

if __name__ == "__main__":
    sys.exit(main())
