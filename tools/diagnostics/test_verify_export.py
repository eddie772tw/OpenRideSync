import hashlib
from pathlib import Path
import tempfile
import unittest
import zipfile

from verify_export import HEADER, NAME, validate, verify


class ExportVerifierTests(unittest.TestCase):
    def test_synthetic_auth_workflow_closed_methods_and_statuses(self):
        session = "00000000-0000-0000-0000-000000000001"
        operation = "00000000-0000-0000-0000-000000000002"
        events = [
            ("AUTH", "TOKEN_READ", "STARTED", "NONE"),
            ("AUTH", "TOKEN_READ", "SUCCEEDED", "NONE"),
            ("API", "HONDA_GET_USER", "STARTED", "NONE"),
            ("API", "HONDA_GET_USER", "FAILED", "NOT_FOUND"),
            ("AUTH", "TOKEN_REFRESH", "STARTED", "NONE"),
            ("AUTH", "TOKEN_REFRESH", "FAILED", "UNAUTHORIZED"),
            ("API", "HONDA_GET_COUNTRY", "STARTED", "NONE"),
            ("API", "HONDA_GET_COUNTRY", "FAILED", "PERMISSION_DENIED"),
            ("API", "HONDA_LIST_MODEL_CATALOGS", "STARTED", "NONE"),
            ("API", "HONDA_LIST_MODEL_CATALOGS", "SUCCEEDED", "NONE"),
            ("API", "HONDA_LIST_MODEL_SERIES", "STARTED", "NONE"),
            ("API", "HONDA_LIST_MODEL_SERIES", "SUCCEEDED", "NONE"),
            ("API", "HONDA_LIST_MODELS", "STARTED", "NONE"),
            ("API", "HONDA_LIST_MODELS", "SUCCEEDED", "NONE"),
            ("API", "HONDA_GET_IMAGE", "STARTED", "NONE"),
            ("API", "HONDA_GET_IMAGE", "FAILED", "NOT_FOUND"),
            ("API", "HONDA_GET_SETUP_PAGE", "STARTED", "NONE"),
            ("API", "HONDA_GET_SETUP_PAGE", "SUCCEEDED", "NONE"),
            ("API", "HONDA_GET_TUTORIAL_PAGE", "STARTED", "NONE"),
            ("API", "HONDA_GET_TUTORIAL_PAGE", "SUCCEEDED", "NONE"),
            ("API", "GUIDE_DOWNLOAD", "STARTED", "NONE"),
            ("API", "GUIDE_DOWNLOAD", "FAILED", "NETWORK"),
            ("API", "HONDA_LIST_DAILY_TRIPS", "STARTED", "NONE"),
            ("API", "HONDA_LIST_DAILY_TRIPS", "SUCCEEDED", "NONE"),
            ("API", "HONDA_LIST_TRIP_DETAILS", "STARTED", "NONE"),
            ("API", "HONDA_LIST_TRIP_DETAILS", "SUCCEEDED", "NONE"),
            ("API", "HONDA_GET_TRIP_DETAIL", "STARTED", "NONE"),
            ("API", "HONDA_GET_TRIP_DETAIL", "FAILED", "NOT_FOUND"),
            ("API", "HONDA_GET_WEATHER", "STARTED", "NONE"),
            ("API", "HONDA_GET_WEATHER", "SUCCEEDED", "NONE"),
            ("API", "HONDA_GET_WEATHER_FORECAST", "STARTED", "NONE"),
            ("API", "HONDA_GET_WEATHER_FORECAST", "FAILED", "UNAVAILABLE"),
            # Synthetic callback stages only, not vehicle/function evidence.
            ("BLE", "BLE_SCAN", "STARTED", "NONE"),
            ("BLE", "BLE_SCAN", "OBSERVED", "NONE"),
            ("BLE", "BLE_BOND", "STARTED", "NONE"),
            ("BLE", "BLE_BOND", "SUCCEEDED", "NONE"),
            ("BLE", "BLE_GATT_CONNECT", "STARTED", "NONE"),
            ("BLE", "BLE_GATT_CONNECT", "SUCCEEDED", "NONE"),
            ("BLE", "BLE_DISCOVER_SERVICES", "STARTED", "NONE"),
            ("BLE", "BLE_DISCOVER_SERVICES", "SUCCEEDED", "NONE"),
            ("BLE", "BLE_CCCD_WRITE", "STARTED", "NONE"),
            ("BLE", "BLE_CCCD_WRITE", "SUCCEEDED", "NONE"),
            ("BLE", "BLE_SECURITY_STATUS", "OBSERVED", "NONE"),
            ("BLE", "BLE_SECURITY_RSA_EXCHANGE", "STARTED", "NONE"),
            ("BLE", "BLE_SECURITY_RSA_EXCHANGE", "SUCCEEDED", "NONE"),
            ("BLE", "BLE_SECURITY_AES_VERIFY", "STARTED", "NONE"),
            ("BLE", "BLE_SECURITY_AES_VERIFY", "FAILED", "UNAUTHORIZED"),
            ("BLE", "DISCONNECTED", "OBSERVED", "NONE"),
            ("UI", "PAIRING_ASSOCIATION", "STARTED", "NONE"),
            ("UI", "PAIRING_ASSOCIATION", "SUCCEEDED", "NONE"),
            ("BLE", "BLE_BTU_INFORMATION", "STARTED", "NONE"),
            ("BLE", "BLE_BTU_INFORMATION", "SUCCEEDED", "NONE"),
            ("UI", "PAIRED_VEHICLE_SAVE", "STARTED", "NONE"),
            ("UI", "PAIRED_VEHICLE_SAVE", "FAILED", "IO"),
            ("BLE", "BLE_CTS_START", "STARTED", "NONE"),
            ("BLE", "BLE_CTS_START", "SUCCEEDED", "NONE"),
            ("BLE", "BLE_CTS_REQUEST", "OBSERVED", "NONE"),
            ("BLE", "BLE_CTS_NOTIFY", "FAILED", "IO"),
            ("BLE", "BLE_ASSIGNMENT_REGISTER", "STARTED", "NONE"),
            ("BLE", "BLE_ASSIGNMENT_REGISTER", "FAILED", "TIMEOUT"),
            ("BLE", "BLE_CONTROL_COMMAND", "CANCELLED", "ABORTED"),
            ("BLE", "BLE_CONTROL_RECEIPT", "FAILED", "FAILED_PRECONDITION"),
            ("BLE", "BLE_RUNTIME_INIT", "STARTED", "NONE"),
            ("BLE", "BLE_FUNCTION_NOTIFICATION", "OBSERVED", "NONE"),
            ("BLE", "BLE_DRIVING_NOTIFICATION", "FAILED", "DATA_LOSS"),
            ("BLE", "BLE_RUNTIME_SERVICE", "STARTED", "NONE"),
            ("BLE", "BLE_RUNTIME_STATE", "OBSERVED", "NONE"),
            ("BLE", "BLE_RUNTIME_STOP", "CANCELLED", "CANCELLED"),
            ("MEDIA", "MEDIA_ACCESS", "OBSERVED", "NONE"),
            ("MEDIA", "MEDIA_BROWSER", "STARTED", "NONE"),
            ("MEDIA", "MEDIA_SESSION", "OBSERVED", "NONE"),
            ("MEDIA", "MEDIA_PLAY", "OBSERVED", "NONE"),
            ("MEDIA", "MEDIA_PAUSE", "OBSERVED", "NONE"),
            ("MEDIA", "MEDIA_PREVIOUS", "OBSERVED", "NONE"),
            ("MEDIA", "MEDIA_NEXT", "OBSERVED", "NONE"),
            ("DIAGNOSTICS", "ARCHIVE", "SUCCEEDED", "NONE"),
        ]
        count = len(events)
        rows = ["\t".join(HEADER)]
        for sequence, (component, event, outcome, error) in enumerate(events, 1):
            rows.append(f"1\t{session}\t1\t0\t{sequence}\t{component}\t{operation}\t{event}\t{outcome}\t{error}\t{count}\t{sequence-1}\t0\t0\tOK")
        rows.append(f"1\t{session}\t1\t0\t{count+1}\tDIAGNOSTICS\t-\tCHECKPOINT\tOBSERVED\tNONE\t{count}\t{count}\t0\t0\tOK")
        data = ("\n".join(rows) + "\n").encode()
        self.assertEqual(validate(data), {"rows": count+1, "sessions": 1, "complete": True})
        self.assertNotIn(b"googleIdToken", data)
        for raw in (b"modelId=synthetic-model", b"imageId=synthetic-image", b"accountId=synthetic-account",
                    b"https://synthetic.invalid/setup?private=value", b"modelLabel=synthetic-model", b"synthetic@example.invalid", b"Bearer synthetic-token", b"tripId=synthetic-trip",
                    b"02:00:00:00:00:00", b"deviceName=synthetic-meter", b"key=synthetic-private-key", b"challenge=synthetic-private-challenge", b"sessionPayload=synthetic-private-payload"):
            self.assertNotIn(raw, data)
            with self.assertRaises(ValueError):
                validate(data.replace(operation.encode(), raw))
        methods = [row.split("\t")[7] for row in rows[1:-1]]
        self.assertEqual(set(methods[8:]), {"HONDA_LIST_MODEL_CATALOGS", "HONDA_LIST_MODEL_SERIES", "HONDA_LIST_MODELS", "HONDA_GET_IMAGE", "HONDA_GET_SETUP_PAGE", "HONDA_GET_TUTORIAL_PAGE", "GUIDE_DOWNLOAD", "HONDA_LIST_DAILY_TRIPS", "HONDA_LIST_TRIP_DETAILS", "HONDA_GET_TRIP_DETAIL", "HONDA_GET_WEATHER", "HONDA_GET_WEATHER_FORECAST", "BLE_SCAN", "BLE_BOND", "BLE_GATT_CONNECT", "BLE_DISCOVER_SERVICES", "BLE_CCCD_WRITE", "BLE_SECURITY_STATUS", "BLE_SECURITY_RSA_EXCHANGE", "BLE_SECURITY_AES_VERIFY", "DISCONNECTED", "PAIRING_ASSOCIATION", "BLE_BTU_INFORMATION", "PAIRED_VEHICLE_SAVE", "BLE_CTS_START", "BLE_CTS_REQUEST", "BLE_CTS_NOTIFY", "BLE_ASSIGNMENT_REGISTER", "BLE_CONTROL_COMMAND", "BLE_CONTROL_RECEIPT", "BLE_RUNTIME_INIT", "BLE_FUNCTION_NOTIFICATION", "BLE_DRIVING_NOTIFICATION", "BLE_RUNTIME_SERVICE", "BLE_RUNTIME_STATE", "BLE_RUNTIME_STOP", "MEDIA_ACCESS", "MEDIA_BROWSER", "MEDIA_SESSION", "MEDIA_PLAY", "MEDIA_PAUSE", "MEDIA_PREVIOUS", "MEDIA_NEXT", "ARCHIVE"})
        self.assertEqual({row.split("\t")[6] for row in rows[1:-1]}, {operation})
        with self.assertRaisesRegex(ValueError, "unknown enum"):
            validate(data.replace(b"\tNOT_FOUND\t", b"\tBearer_secret\t"))
        with self.assertRaisesRegex(ValueError, "unknown enum"):
            validate(data.replace(b"\tGUIDE_DOWNLOAD\t", b"\thttps://synthetic.invalid/setup\t"))
        with self.assertRaisesRegex(ValueError, "unknown enum"):
            validate(data.replace(b"\tMEDIA_PLAY\t", b"\tMEDIA_FOO\t"))

    def test_valid_partial_and_tampered_export(self):
        session = "00000000-0000-0000-0000-000000000001"
        row = f"1\t{session}\t1\t0\t1\tDIAGNOSTICS\t-\tCHECKPOINT\tOBSERVED\tNONE\t0\t0\t0\t0\tOK\n"
        data = ("\t".join(HEADER) + "\n" + row).encode()
        self.assertTrue(validate(data)["complete"])
        with self.assertRaises(ValueError):
            validate(data[:-1])
        with self.assertRaises(ValueError):
            validate(data + row.encode())  # repeated sequence
        # A final checkpoint cannot hide a missing middle data record.
        event = f"1\t{session}\t1\t0\t1\tAPP\t-\tSTART\tOBSERVED\tNONE\t2\t0\t0\t0\tOK\n"
        final = f"1\t{session}\t1\t0\t3\tDIAGNOSTICS\t-\tCHECKPOINT\tOBSERVED\tNONE\t2\t2\t0\t0\tOK\n"
        missing = ("\t".join(HEADER)+"\n"+event+final).encode()
        self.assertFalse(validate(missing)["complete"])
        manifest = (f"schema=1\nfile={NAME}\nsha256={hashlib.sha256(data).hexdigest()}\n"
                    f"bytes={len(data)}\nrows=1\nsessions=1\ncomplete=true\n"
                    "evidence=app_metadata_not_vehicle_validation\n")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.zip"
            for content, valid in ((data, True), (data.replace(b"\t1\t0\t1\t", b"\t2\t0\t1\t"), False)):
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr(NAME, content)
                    archive.writestr("manifest.properties", manifest)
                if valid:
                    self.assertTrue(verify(path)["complete"])
                else:
                    with self.assertRaisesRegex(ValueError, "hash mismatch"):
                        verify(path)


if __name__ == "__main__":
    unittest.main()
