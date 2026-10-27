"""Synthetic host tests. Fixtures are not installable APKs or Gradle executions."""
import argparse
import contextlib
import hashlib
import io
import json
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile
import zlib

import check_build_readiness as guard
from dex_build_config import Dex, DexError


SHA = "1" * 40
CERT = "a" * 64
VALUES = {"APPLICATION_ID": "org.openridesync.app", "BUILD_TYPE": "debug",
          "SOURCE_COMMIT": SHA, "VERSION_CODE": 1, "VERSION_NAME": "0.1.0-dev"}
BADGING = """package: name='org.openridesync.app' versionCode='1' versionName='0.1.0-dev' platformBuildVersionName='16'
minSdkVersion:'30'
targetSdkVersion:'36'
application-debuggable
"""
RESOURCES = """Package name=org.openridesync.app id=7f
  type string id=11 entryCount=1
    resource 0x7f110001 string/app_name
      () \"OpenRideSync\"
"""
SIGNER = ("Verifies\nVerified using v2 scheme (APK Signature Scheme v2): true\n"
          "Signer #1 certificate DN: C=US, O=Android, CN=Android Debug\n"
          "Signer #1 certificate SHA-256 digest: " + CERT + "\n")


def uleb(value):
    data = bytearray()
    while True:
        part, value = value & 127, value >> 7
        data.append(part | (128 if value else 0))
        if not value:
            return data


def synthetic_dex(values=None, extra_strings=(), owner="org.openridesync.app"):
    """Minimum structural fixture for the reader, not a runnable Android DEX."""
    values = VALUES if values is None else values
    descriptor = "L" + owner.replace(".", "/") + "/BuildConfig;"
    strings = sorted({descriptor, "Ljava/lang/String;", "I", *values,
                      *(value for value in values.values() if isinstance(value, str)), *extra_strings})
    types = sorted([descriptor, "Ljava/lang/String;", "I"], key=strings.index)
    fields = sorted(values)
    data = bytearray(112)
    strings_off = len(data)
    data.extend(bytes(len(strings) * 4))
    types_off = len(data)
    for name in types:
        data.extend(struct.pack("<I", strings.index(name)))
    fields_off = len(data)
    for name in fields:
        data.extend(struct.pack("<HHI", types.index(descriptor),
                                types.index("I" if isinstance(values[name], int) else "Ljava/lang/String;"),
                                strings.index(name)))
    classes_off = len(data)
    data.extend(bytes(32))
    data_off = len(data)
    for index, value in enumerate(strings):
        struct.pack_into("<I", data, strings_off + index * 4, len(data))
        encoded = value.encode("ascii")
        data.extend(uleb(len(value)) + encoded + b"\0")
    class_data = len(data)
    data.extend(uleb(len(fields)) + b"\0\0\0")
    for index, _ in enumerate(fields):
        data.extend(uleb(0 if index == 0 else 1) + uleb(0x19))
    static_values = len(data)
    data.extend(uleb(len(fields)))
    for name in fields:
        value = values[name]
        if isinstance(value, int):
            data.extend(b"\x04" + bytes([value]))
        else:
            index = strings.index(value)
            width = max(1, (index.bit_length() + 7) // 8)
            data.extend(bytes([0x17 | ((width - 1) << 5)]) + index.to_bytes(width, "little"))
    struct.pack_into("<IIIIIIII", data, classes_off, types.index(descriptor), 0x11, 0xFFFFFFFF,
                     0, 0xFFFFFFFF, 0, class_data, static_values)
    data[:8] = b"dex\n035\0"
    struct.pack_into("<III", data, 32, len(data), 112, 0x12345678)
    for at, count, offset in ((56, len(strings), strings_off), (64, len(types), types_off),
                              (80, len(fields), fields_off), (96, 1, classes_off),
                              (104, len(data) - data_off, data_off)):
        struct.pack_into("<II", data, at, count, offset)
    data[12:32] = hashlib.sha1(data[32:]).digest()
    struct.pack_into("<I", data, 8, zlib.adler32(data[12:]))
    return bytes(data)


def make_apk(path, dex=None, additional=(), omit=(), compression=zipfile.ZIP_DEFLATED):
    contents = {"AndroidManifest.xml": b"synthetic-manifest", "resources.arsc": b"synthetic-resources",
                "classes.dex": synthetic_dex() if dex is None else dex,
                "assets/PublicSuffixDatabase.list": b"synthetic-public-list",
                "assets/openridesync-login.html": b"synthetic-login"}
    with zipfile.ZipFile(path, "w", compression) as archive:
        for name, data in contents.items():
            if name not in omit:
                archive.writestr(name, data)
        for name, data in additional:
            archive.writestr(name, data)


class DexTests(unittest.TestCase):
    def test_reads_named_class_values(self):
        self.assertEqual(Dex(synthetic_dex()).build_config("org.openridesync.app"), [VALUES])

    def test_wrong_owner_does_not_match(self):
        self.assertEqual(Dex(synthetic_dex(owner="other.app")).build_config("org.openridesync.app"), [])

    def test_rejects_checksum_corruption(self):
        data = bytearray(synthetic_dex())
        data[-1] ^= 1
        with self.assertRaises(DexError):
            Dex(data)

    def test_rejects_unsupported_format(self):
        with self.assertRaises(DexError):
            Dex(b"dex\n041\0" + bytes(120))

    def test_rejects_truncation(self):
        with self.assertRaises(DexError):
            Dex(b"dex\n035\0")

    def test_identity_string_index_must_exist(self):
        dex = Dex(synthetic_dex())
        with self.assertRaises(DexError):
            dex.string(dex.string_count)


class ArtifactTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.apk = Path(self.temp.name) / "synthetic.apk"

    def test_clean_zip_and_named_provenance(self):
        make_apk(self.apk)
        result = guard.inspect_zip(self.apk, SHA)
        self.assertEqual(result["build_config"], VALUES)
        self.assertEqual(result["credential_pattern_findings"], [])

    def test_unrelated_sha_string_is_not_provenance(self):
        make_apk(self.apk, dex=synthetic_dex(dict(VALUES, SOURCE_COMMIT="2" * 40), extra_strings=[SHA]))
        with self.assertRaisesRegex(guard.CheckFailure, "BuildConfig static identity"):
            guard.inspect_zip(self.apk, SHA)

    def test_duplicate_buildconfig_fails(self):
        make_apk(self.apk, additional=[("classes2.dex", synthetic_dex())])
        with self.assertRaisesRegex(guard.CheckFailure, "exactly one"):
            guard.inspect_zip(self.apk, SHA)

    def test_missing_buildconfig_fails(self):
        make_apk(self.apk, dex=synthetic_dex(owner="other.app"))
        with self.assertRaisesRegex(guard.CheckFailure, "exactly one"):
            guard.inspect_zip(self.apk, SHA)

    def test_duplicate_zip_entry_fails(self):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            make_apk(self.apk, additional=[("classes.dex", synthetic_dex())])
        with self.assertRaisesRegex(guard.CheckFailure, "Duplicate ZIP"):
            guard.inspect_zip(self.apk, SHA)

    def test_unsupported_apk_compression_returns_json_failure(self):
        make_apk(self.apk, compression=zipfile.ZIP_BZIP2)
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = guard.main(["artifact", "--apk", str(self.apk), "--source-commit", SHA,
                               "--with-honda-config", "false", "--with-oem-ui-assets", "false"])
        self.assertEqual(code, 1)
        self.assertEqual(json.loads(output.getvalue())["reason"], "Unsupported APK ZIP compression method")

    def test_private_and_oem_paths_fail(self):
        for name in ("assets/auth-client.json", "res/font/oem_ui_regular.ttf", "res/drawable/oem_ride_weather_sunny.xml", "private/evidence.json",
                     "key.jks", "assets/unexpected.bin", "../outside", "nested/classes.dex"):
            with self.subTest(name=name):
                make_apk(self.apk, additional=[(name, b"synthetic")])
                with self.assertRaises(guard.CheckFailure):
                    guard.inspect_zip(self.apk, SHA)

    def test_missing_allowlisted_asset_fails(self):
        make_apk(self.apk, omit=["assets/openridesync-login.html"])
        with self.assertRaisesRegex(guard.CheckFailure, "assets are missing"):
            guard.inspect_zip(self.apk, SHA)

    def test_all_supported_secret_patterns_and_redaction(self):
        fixtures = [b"AIza" + b"a" * 35, b"ya29." + b"a" * 30, b"ghp_" + b"a" * 36,
                    b"AKIA" + b"A" * 16, b"eyJ" + b"a" * 20 + b"." + b"b" * 30 + b"." + b"c" * 20,
                    b"-----BEGIN PRIVATE KEY-----\n" + b"A" * 64 + b"\n-----END PRIVATE KEY-----"]
        for payload in fixtures:
            with self.subTest(pattern=payload[:4]):
                make_apk(self.apk, additional=[("res/raw/synthetic_payload", payload)])
                with self.assertRaisesRegex(guard.CheckFailure, "credential pattern") as caught:
                    guard.inspect_zip(self.apk, SHA)
                self.assertNotIn(payload.decode(), str(caught.exception))

    def test_utf16_resource_credential_is_detected(self):
        make_apk(self.apk, additional=[("res/xml/test.xml", ("AIza" + "a" * 35).encode("utf-16-le"))])
        with self.assertRaisesRegex(guard.CheckFailure, "credential pattern"):
            guard.inspect_zip(self.apk, SHA)

    def test_limit_is_a_failure_not_a_skipped_scan(self):
        make_apk(self.apk)
        with patch.object(guard, "MAX_ENTRY", 10):
            with self.assertRaisesRegex(guard.CheckFailure, "limit"):
                guard.inspect_zip(self.apk, SHA)

    def test_missing_tools_returns_incomplete_after_zip_checks(self):
        make_apk(self.apk)
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = guard.main(["artifact", "--apk", str(self.apk), "--source-commit", SHA,
                               "--with-honda-config", "false", "--with-oem-ui-assets", "false"])
        report = json.loads(output.getvalue())
        self.assertEqual(code, 2)
        self.assertEqual(report["status"], "incomplete")
        self.assertIn("artifact", report)
        self.assertNotIn("signer", report)

    def test_failed_cryptographic_verification_cannot_pass(self):
        make_apk(self.apk)
        args = argparse.Namespace(apk=self.apk, source_commit=SHA, aapt2="aapt2",
                                  apksigner="apksigner", expected_signer_sha256=CERT)
        results = [subprocess.CompletedProcess([], 0, BADGING, ""),
                   subprocess.CompletedProcess([], 0, RESOURCES, ""),
                   subprocess.CompletedProcess([], 1, SIGNER, "failed")]
        with patch.object(guard, "command_output", side_effect=results):
            with self.assertRaisesRegex(guard.CheckFailure, "cryptographic verification failed"):
                guard.inspect_artifact(args, {})


class MetadataTests(unittest.TestCase):
    def test_parse_expected_manifest(self):
        self.assertEqual(guard.parse_badging(BADGING)["min_sdk"], 30)

    def test_legacy_sdk_label_is_supported(self):
        self.assertEqual(guard.parse_badging(BADGING.replace("minSdkVersion", "sdkVersion"))["min_sdk"], 30)

    def test_duplicate_sdk_labels_are_rejected(self):
        with self.assertRaisesRegex(guard.CheckFailure, "ambiguous"):
            guard.parse_badging(BADGING + "sdkVersion:'29'\n")

    def test_manifest_changes_are_rejected(self):
        for text in (BADGING.replace("org.openridesync.app", "other.app"),
                     BADGING.replace("versionCode='1'", "versionCode='2'"),
                     BADGING.replace("0.1.0-dev", "0.2.0"),
                     BADGING.replace("minSdkVersion:'30'", "minSdkVersion:'29'"),
                     BADGING.replace("targetSdkVersion:'36'", "targetSdkVersion:'35'"),
                     BADGING.replace("application-debuggable", "")):
            with self.subTest(text=text):
                with self.assertRaises(guard.CheckFailure):
                    guard.parse_badging(text)

    def test_expected_signer(self):
        self.assertEqual(guard.parse_signer(SIGNER, CERT)["installed_candidate_compatibility"], "not_checked")

    def test_wrong_or_multiple_signers_are_rejected(self):
        for text in (SIGNER.replace(CERT, "b" * 64),
                     SIGNER + "Signer #2 certificate SHA-256 digest: " + CERT + "\n",
                     SIGNER.replace(": true", ": false"),
                     SIGNER.replace("CN=Android Debug", "CN=Release")):
            with self.subTest(text=text):
                with self.assertRaises(guard.CheckFailure):
                    guard.parse_signer(text, CERT)

    def test_resource_table_names_not_arbitrary_substrings(self):
        self.assertEqual(guard.resource_names(RESOURCES)["parsed_resource_count"], 1)
        for name in [*guard.FORBIDDEN_RESOURCE_NAMES, "oem_ui_regular", "oem_ride_weather_sunny"]:
            with self.subTest(name=name):
                with self.assertRaises(guard.CheckFailure):
                    guard.resource_names(RESOURCES.replace("string/app_name", "string/" + name))
        with self.assertRaises(guard.CheckFailure):
            guard.resource_names("unrecognized format")

    def test_explicit_clean_intent_only(self):
        args = argparse.Namespace(source_commit=SHA, with_honda_config="false", with_oem_ui_assets="false")
        guard.validate_intent(args)
        for key, value in (("source_commit", "unknown"), ("source_commit", "abcdef0"),
                           ("with_honda_config", "true"), ("with_oem_ui_assets", "true")):
            with self.subTest(key=key):
                changed = argparse.Namespace(**vars(args))
                setattr(changed, key, value)
                with self.assertRaises(guard.CheckFailure):
                    guard.validate_intent(changed)


class ConfigurationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name) / "repo"
        self.android = self.repo
        for relative in guard.COPY_INPUTS:
            path = self.android / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("// synthetic public build input\n")
        # A deliberate private sentinel must never be read/copied into the probe.
        private = self.android / "app/src/private/assets/auth-client.json"
        private.parent.mkdir(parents=True)
        private.write_text("do-not-copy")
        (self.android / "local.properties").write_text("do-not-copy")

    def test_staging_uses_only_public_allowlist(self):
        project, hashes = guard.stage_config_inputs(self.repo, Path(self.temp.name) / "staged")
        self.assertEqual(set(hashes), set(guard.COPY_INPUTS))
        self.assertEqual({p.relative_to(project).as_posix() for p in project.rglob("*") if p.is_file()}, set(guard.COPY_INPUTS))

    def test_real_probe_commands_are_sequential_offline_and_explicit(self):
        args = argparse.Namespace(gradle="existing-gradle", repo=self.repo, source_commit=SHA, timeout=10)
        calls = []

        def fake_run(command, cwd, timeout):
            calls.append(command)
            self.assertFalse((cwd / "app/src/private/assets/auth-client.json").exists())
            self.assertFalse((cwd / "local.properties").exists())
            if "-PwithHondaConfig=true" in command:
                return subprocess.CompletedProcess(command, 1, "", "Prepare private auth-client.json before enabling withHondaConfig")
            if "-PwithOemUiAssets=true" in command:
                return subprocess.CompletedProcess(command, 1, "", "OEM assets are excluded from this public repository")
            if "-PsourceCommit=not-a-git-sha" in command:
                return subprocess.CompletedProcess(command, 1, "", "sourceCommit must be a Git SHA or unknown")
            return subprocess.CompletedProcess(command, 0, "BUILD SUCCESSFUL", "")

        report = {}
        with patch.object(guard, "command_output", side_effect=fake_run):
            guard.inspect_config(args, report)
        self.assertEqual(len(calls), 5)
        self.assertTrue(all(row["passed"] for row in report["configuration_probes"]))
        for command in calls:
            self.assertIn("--offline", command)
            self.assertIn(":app:help", command)
            self.assertIn("-Pandroid.builder.sdkDownload=false", command)

    def test_unrelated_failure_is_not_a_successful_negative_probe(self):
        args = argparse.Namespace(gradle="existing-gradle", repo=self.repo, source_commit=SHA, timeout=10)
        results = [subprocess.CompletedProcess([], 0, "BUILD SUCCESSFUL", "")] * 2 + [
            subprocess.CompletedProcess([], 1, "", "Plugin was not found")]
        report = {}
        with patch.object(guard, "command_output", side_effect=results):
            with self.assertRaisesRegex(guard.CheckFailure, "missing_honda"):
                guard.inspect_config(args, report)
        self.assertFalse(report["configuration_probes"][-1]["passed"])


if __name__ == "__main__":
    unittest.main()
