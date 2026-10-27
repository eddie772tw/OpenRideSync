"""Validate a standalone metadata TSV or export ZIP; standard Python only.

This validates evidence structure/integrity, never vehicle-function success.
Legacy GATT/encoder/media/notification readers continue to own their schemas.
"""
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import uuid
import zipfile

NAME = "openridesync-diagnostics-v1.tsv"
HEADER = "schema session epoch_ms elapsed_ms sequence component operation event outcome error accepted written dropped write_failures status".split()
COMPONENTS = set("APP UI AUTH API SYNC BLE MEDIA NOTIFICATION NAVIGATION TRIP SDK DIAGNOSTICS".split())
EVENTS = set("START STOP STATE_CHANGE REQUEST RESPONSE RETRY CONNECTED DISCONNECTED EXPORT ARCHIVE UNAVAILABLE CHECKPOINT HONDA_GET_USER HONDA_GET_COUNTRY HONDA_LIST_MODEL_CATALOGS HONDA_LIST_MODEL_SERIES HONDA_LIST_MODELS HONDA_GET_IMAGE HONDA_GET_SETUP_PAGE HONDA_GET_TUTORIAL_PAGE GUIDE_DOWNLOAD HONDA_LIST_DAILY_TRIPS HONDA_LIST_TRIP_DETAILS HONDA_GET_TRIP_DETAIL HONDA_GET_WEATHER HONDA_GET_WEATHER_FORECAST TOKEN_READ TOKEN_REFRESH BLE_SCAN BLE_BOND BLE_GATT_CONNECT BLE_DISCOVER_SERVICES BLE_CCCD_WRITE BLE_SECURITY_STATUS BLE_SECURITY_RSA_EXCHANGE BLE_SECURITY_AES_VERIFY PAIRING_ASSOCIATION BLE_BTU_INFORMATION PAIRED_VEHICLE_SAVE BLE_CTS_START BLE_CTS_REQUEST BLE_CTS_NOTIFY BLE_ASSIGNMENT_REGISTER BLE_CONTROL_COMMAND BLE_CONTROL_RECEIPT BLE_RUNTIME_INIT BLE_FUNCTION_NOTIFICATION BLE_DRIVING_NOTIFICATION BLE_RUNTIME_SERVICE BLE_RUNTIME_STATE BLE_RUNTIME_STOP MEDIA_ACCESS MEDIA_BROWSER MEDIA_SESSION MEDIA_PLAY MEDIA_PAUSE MEDIA_PREVIOUS MEDIA_NEXT".split())
OUTCOMES = set("STARTED SUCCEEDED FAILED CANCELLED UNAVAILABLE OBSERVED".split())
ERRORS = set("NONE IO TIMEOUT CANCELLED UNAUTHORIZED NETWORK INVALID_STATE UNSUPPORTED UNKNOWN INVALID_ARGUMENT NOT_FOUND ALREADY_EXISTS PERMISSION_DENIED RESOURCE_EXHAUSTED FAILED_PRECONDITION ABORTED OUT_OF_RANGE UNIMPLEMENTED INTERNAL DATA_LOSS UNAUTHENTICATED UNAVAILABLE DEADLINE_EXCEEDED".split())
LIMIT = 8 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate(data):
    require(len(data) <= LIMIT and data.endswith(b"\n"), "oversize or partial journal")
    rows = csv.reader(io.StringIO(data.decode("utf-8")), delimiter="\t", quoting=csv.QUOTE_NONE)
    require(next(rows, None) == HEADER, "unexpected schema")
    previous, complete, observed, counters, gaps = {}, {}, {}, {}, set()
    count = 0
    for fields in rows:
        count += 1
        require(len(fields) == len(HEADER) and sum(map(len, fields)) <= 2048, f"bad row {count+1}")
        f = dict(zip(HEADER, fields))
        require(f["schema"] == "1", "bad schema")
        require(str(uuid.UUID(f["session"])) == f["session"], "bad session")
        require(f["operation"] == "-" or str(uuid.UUID(f["operation"])) == f["operation"], "bad operation")
        nums = {key: int(f[key]) for key in ("epoch_ms", "elapsed_ms", "sequence", "accepted", "written", "dropped", "write_failures")}
        require(all(n >= 0 for n in nums.values()), "negative counter")
        require(nums["sequence"] > previous.get(f["session"], 0), "sequence reordered")
        if nums["sequence"] != previous.get(f["session"], 0) + 1:
            gaps.add(f["session"])
        require(nums["written"] <= nums["accepted"], "impossible write count")
        require(f["component"] in COMPONENTS and f["event"] in EVENTS and f["outcome"] in OUTCOMES and f["error"] in ERRORS, "unknown enum")
        require(f["status"] in {"OK", "DROPPED", "IO_ERROR", "SIZE_LIMIT"}, "unknown status")
        require(f["status"] != "OK" or nums["dropped"] == nums["write_failures"] == 0, "inconsistent integrity")
        current = [nums[k] for k in ("elapsed_ms", "accepted", "written", "dropped", "write_failures")]
        require(all(a >= b for a, b in zip(current, counters.get(f["session"], [0]*5))), "counter regression")
        counters[f["session"]] = current
        if f["event"] != "CHECKPOINT":
            observed[f["session"]] = observed.get(f["session"], 0) + 1
        previous[f["session"]] = nums["sequence"]
        complete[f["session"]] = (f["status"] == "OK" and f["component"] == "DIAGNOSTICS" and f["event"] == "CHECKPOINT" and nums["accepted"] == nums["written"] == observed.get(f["session"], 0) and f["session"] not in gaps)
    require(count > 0, "no records")
    return {"rows": count, "sessions": len(previous), "complete": all(complete.values())}


def verify(path):
    if path.suffix.lower() != ".zip":
        require(path.stat().st_size <= LIMIT, "oversize journal")
        return validate(path.read_bytes())
    with zipfile.ZipFile(path) as archive:
        require(sorted(archive.namelist()) == sorted([NAME, "manifest.properties"]), "unexpected or duplicate ZIP entries")
        require(archive.getinfo(NAME).file_size <= LIMIT and archive.getinfo("manifest.properties").file_size <= 4096, "oversize member")
        data = archive.read(NAME)
        lines = archive.read("manifest.properties").decode("utf-8").splitlines()
        require(all("=" in line for line in lines), "bad manifest")
        manifest = dict(line.split("=", 1) for line in lines)
        require(len(lines) == len(manifest), "duplicate manifest field")
        result = validate(data)
        require(manifest.get("schema") == "1" and manifest.get("file") == NAME, "bad export schema")
        require(manifest.get("sha256") == hashlib.sha256(data).hexdigest(), "hash mismatch")
        require(int(manifest["bytes"]) == len(data), "byte count mismatch")
        require(int(manifest["rows"]) == result["rows"] and int(manifest["sessions"]) == result["sessions"], "row/session mismatch")
        require(manifest["complete"] == str(result["complete"]).lower(), "integrity mismatch")
        require(manifest.get("evidence") == "app_metadata_not_vehicle_validation", "bad evidence scope")
        return dict(result, sha256=manifest["sha256"], evidence=manifest["evidence"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("file", type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(verify(args.file), indent=2))
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(2, f"Invalid diagnostics export: {error}\n")
