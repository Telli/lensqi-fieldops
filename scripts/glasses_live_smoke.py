#!/usr/bin/env python3
"""Bounded LIVE-provider check. Prepare the SDK mock glasses with a lab video and start their camera first.

Uses an isolated API with Hosting.Diagnostics request logging enabled. No fixture responses replace providers.
The test starts live assistance, checks lock-screen operation, pauses and resumes, then leaves analysis paused.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--adb', default=shutil.which('adb') or str(Path.home() / 'Library/Android/sdk/platform-tools/adb'))
p.add_argument('--api-log', required=True)
p.add_argument('--base', default='http://127.0.0.1:5204')
p.add_argument('--inspection', required=True)
p.add_argument('--out', default='docs/evidence/live-glasses')
a = p.parse_args()
out = Path(a.out); out.mkdir(parents=True, exist_ok=True)

def adb(*args):
    return subprocess.check_output([a.adb, *args], timeout=15)

def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/fieldops-live-window.xml')
    return list(ET.fromstring(adb('shell', 'cat', '/sdcard/fieldops-live-window.xml')).iter('node'))

def tap(label):
    for node in nodes():
        if label in (node.get('text'), node.get('content-desc')):
            x, y, xx, yy = map(int, re.findall(r'\d+', node.get('bounds')))
            adb('shell', 'input', 'tap', str((x + xx) // 2), str((y + yy) // 2))
            return
    raise AssertionError(f'Control not visible: {label}')

def requests():
    return len(re.findall(r'Request starting HTTP/1.1 POST .*?/live/(?:detect|guide)', Path(a.api_log).read_text()))

def texts():
    return [n.get('text') for n in nodes() if n.get('text')]

def screenshot(name):
    (out / name).write_bytes(adb('exec-out', 'screencap', '-p'))

def events():
    return json.load(urllib.request.urlopen(f'{a.base}/v1/inspections/{a.inspection}/events'))

before_events = events()
assert 'MOCK GLASSES' in texts(), 'Use the clearly labelled SDK mock with the synthetic lab video for this check'
result = {'sdk': 'Meta Mock Device Kit', 'source': 'HEVC synthetic lab clip', 'physicalGlassesTested': False}
try:
    tap('Start live assist')
    time.sleep(9)
    active = texts()
    fps = next((int(m[1]) for text in active if (m := re.match(r'(\d+) tracking updates/s', text))), 0)
    assert fps > 0, f'No decoded frames reached the analyzer: {active}'
    assert any(re.match(r'Tracking [1-9]', text) for text in active), f'No tracked object in the lab clip: {active}'
    result.update(trackingUpdatesPerSecond=fps, feedback=active)
    screenshot('mock-glasses-live.png')
    before = requests()
    adb('shell', 'input', 'keyevent', '223')  # sleep/lock
    time.sleep(7)
    during = requests()
    service = adb('shell', 'dumpsys', 'activity', 'services', 'com.lensqi.fieldops').decode()
    assert 'isForeground=true' in service and 'GlassesLiveService' in service
    assert during > before, 'No new inference was requested with the phone locked'
    result.update(requestsBeforeLock=before, requestsAfterSevenSecondsLocked=during, foregroundServiceWhileLocked=True)
    adb('shell', 'input', 'keyevent', '224'); adb('shell', 'wm', 'dismiss-keyguard')
    time.sleep(.5)
    tap('Pause live assist'); time.sleep(1)
    paused = requests(); time.sleep(5)
    assert requests() == paused, 'Pause did not stop inference'
    # Resume well after the stream's initial keyframe: a newly attached analyzer must still receive decoded images.
    tap('Start live assist'); time.sleep(6)
    resumed = texts()
    assert any(re.match(r'[1-9]\d* tracking updates/s', text) for text in resumed), 'Resume lost the video decoder'
    tap('Pause live assist'); time.sleep(.5)
    screenshot('mock-glasses-paused.png')
    assert events() == before_events, 'Preview frames changed inspection evidence'
    result.update(pauseStoppedRequests=True, resumeDecodedFrames=True, evidenceUnchanged=True)
    (out / 'background-check.json').write_text(json.dumps(result, indent=2) + '\n')
    print(f'PASS: SDK live glasses tracking {fps} updates/s; inference continues locked; pause/resume works; evidence unchanged.')
finally:
    adb('shell', 'input', 'keyevent', '224'); adb('shell', 'wm', 'dismiss-keyguard')
    try:
        tap('Pause live assist')
    except AssertionError:
        pass
