"""Bounded genuine-provider smoke: one detection and one guidance request, using a synthetic fixture.
Run with .venv/bin/python scripts/live_ar_probe.py --base http://127.0.0.1:5204.
Use an isolated API data directory. Calls the configured paid providers; never part of automatic test gates.
"""
import argparse, concurrent.futures, io, json, time, urllib.request, urllib.error, uuid
from pathlib import Path
from PIL import Image
p = argparse.ArgumentParser(); p.add_argument('--base', required=True); p.add_argument('--output', default='docs/evidence/live-ar/provider-probe-minimal.json'); args = p.parse_args()
root = Path(__file__).resolve().parent.parent

def request(path, body, content_type):
    start = time.monotonic()
    try:
        with urllib.request.urlopen(urllib.request.Request(args.base + path, body, {'Content-Type': content_type}), timeout=25) as r:
            return {'status': r.status, 'elapsedSeconds': round(time.monotonic() - start, 3), 'body': json.load(r)}
    except urllib.error.HTTPError as e:
        return {'status': e.code, 'elapsedSeconds': round(time.monotonic() - start, 3), 'body': json.load(e)}

created = request('/v1/inspections', json.dumps({'fixtureId': 'LAB-RACK-01', 'assetId': 'LAB-SW-01'}).encode(), 'application/json')
inspection = created['body']['inspectionId']
photo = Image.open(root / 'fixtures/telecom-lab/images/lab-sw-01-initial-synthetic.jpg'); photo.thumbnail((640, 640))
image = io.BytesIO(); photo.save(image, format='JPEG')

def call(kind):
    boundary = uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="frameId"\r\n\r\nprobe-frame-1\r\n--{boundary}\r\nContent-Disposition: form-data; name="image"; filename="preview.jpg"\r\nContent-Type: image/jpeg\r\n\r\n'.encode() + image.getvalue() + f'\r\n--{boundary}--\r\n'.encode())
    return kind, request(f'/v1/inspections/{inspection}/live/{kind}', body, 'multipart/form-data; boundary=' + boundary)

with concurrent.futures.ThreadPoolExecutor() as pool:
    result = dict(pool.map(call, ['detect', 'guide']))
result.update(inspectionId=inspection, label='LIVE_META', input='repository synthetic lab photo, resized to 640x400', fixturePreviewOnly=True)
with urllib.request.urlopen(args.base + f'/v1/inspections/{inspection}/events') as r: result['eventsAfter'] = len(json.load(r))
Path(args.output).write_text(json.dumps(result, indent=2))
for kind in ['detect', 'guide']: print(kind, result[kind]['status'], result[kind]['elapsedSeconds'], 'seconds')
assert result['eventsAfter'] == 1, 'preview must not append evidence'
assert all(result[k]['status'] == 200 for k in ['detect', 'guide']), 'provider probe did not pass'
