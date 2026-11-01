#!/usr/bin/env python3
"""Validate a LensQi teaching ZIP without extracting it or treating annotations as robot actions."""
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import zipfile


def validate(path: Path) -> dict:
    allowed = {"dataset.json", "session.json", "timeline.jsonl", "frames.csv", "video.mp4", "context.json"}
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or not set(names) <= allowed:
            raise ValueError("Duplicate or unexpected archive paths")
        if not allowed.difference({"context.json"}) <= set(names):
            raise ValueError("Missing required recording files")
        if sum(i.file_size for i in archive.infolist()) > 300 * 1024 * 1024:
            raise ValueError("Archive exceeds the recorder's size budget")
        for name in names:
            if name != "video.mp4" and archive.getinfo(name).file_size > 16 * 1024 * 1024:
                raise ValueError("Oversized metadata file")
        manifest = json.loads(archive.read("dataset.json"))
        if manifest.get("schemaVersion") != 1 or manifest.get("format") != "lensqi-teaching-observations":
            raise ValueError("Unsupported teaching format")
        if set(manifest["files"]) != set(names) - {"dataset.json"}:
            raise ValueError("Hash inventory does not match the archive")
        for name, expected in manifest["files"].items():
            digest = hashlib.sha256()
            with archive.open(name) as source:
                for block in iter(lambda: source.read(65536), b""):
                    digest.update(block)
            if digest.hexdigest() != expected:
                raise ValueError(f"Hash mismatch: {name}")
        record = json.loads(archive.read("session.json"))
        if record != manifest["record"] or not record["consent"]["recordingAllowed"]:
            raise ValueError("Session or recording consent mismatch")
        events = [json.loads(line) for line in archive.read("timeline.jsonl").splitlines() if line.strip()]
        if [e["sequence"] for e in events] != list(range(1, len(events) + 1)):
            raise ValueError("Timeline sequence is not continuous")
        times = [e["elapsedNs"] for e in events if e["elapsedNs"] is not None]
        if times != sorted(times):
            raise ValueError("Capture clock moved backwards")
        editable = {"Step", "ActionStart", "ActionEnd", "Note", "Outcome", "Correction", "Review"}
        last = next((e for e in reversed(events) if e["kind"] in editable), None)
        reviewed = last is not None and last["kind"] == "Review"
        eligible = reviewed and record["consent"]["trainingAllowed"] and record["state"] == "Complete"
        if manifest["reviewedByRecorder"] != reviewed or manifest["trainingEligible"] != eligible:
            raise ValueError("Review/training eligibility does not match the evidence")
        timestamps = []
        with archive.open("frames.csv") as raw:
            for index, row in enumerate(csv.DictReader(io.TextIOWrapper(raw))):
                pts = int(row["presentation_time_us"])
                if int(row["frame_index"]) != index or (timestamps and pts <= timestamps[-1]):
                    raise ValueError("Invalid frame index or presentation timestamp")
                timestamps.append(pts)
                if int(row["relative_time_us"]) != pts - timestamps[0]:
                    raise ValueError("Relative frame time mismatch")
        video = record["video"]
        if len(timestamps) < 2 or len(timestamps) != video["frameCount"]:
            raise ValueError("Video frame count does not match its timestamp index")
        if timestamps[0] != video["firstPtsUs"] or timestamps[-1] != video["lastPtsUs"] or video["hasAudio"]:
            raise ValueError("Video timestamp bounds or audio policy mismatch")
        return {"recording": record["id"], "frames": len(timestamps), "annotations": len(events),
                "state": record["state"], "reviewed": reviewed, "trainingEligible": eligible,
                "note": "Integrity and schema checked. This does not validate annotation correctness, recording rights, or robot policy suitability."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(validate(args.archive), indent=2))
    except (ValueError, KeyError, OSError, zipfile.BadZipFile) as error:
        parser.exit(1, f"INVALID: {error}\n")
