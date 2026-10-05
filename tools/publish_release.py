"""Publish only an APK and release notes, never project source or signing keys.

Uses the named owner's existing Git Credential Manager login without displaying
or saving its token. Default creates/updates a draft; --publish makes it public.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import urllib.error
import urllib.parse
import urllib.request

OWNER = 't2534407460-wq'
REPOSITORY = OWNER + '/21day'

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--version', required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--notes', type=Path, required=True)
    parser.add_argument('--publish', action='store_true')
    args = parser.parse_args()
    expected_name = '21day-' + args.version + '-debug.apk'
    if args.apk.name != expected_name or not args.apk.is_file():
        raise SystemExit('APK path/name does not match the release version.')
    binary = args.apk.read_bytes()
    checksum = hashlib.sha256(binary).hexdigest()
    env = dict(os.environ, GIT_TERMINAL_PROMPT='0', GCM_INTERACTIVE='never')
    result = subprocess.run(['git', 'credential', 'fill'], input=f'protocol=https\nhost=github.com\nusername={OWNER}\n\n', text=True, capture_output=True, env=env, timeout=30)
    credentials = dict(line.split('=', 1) for line in result.stdout.splitlines() if '=' in line)
    token = credentials.get('password')
    if not token:
        raise SystemExit('The release account must be logged into Git Credential Manager.')

    def request(method, path, payload=None, raw=None):
        url = path if path.startswith('https://uploads.github.com/') else 'https://api.github.com' + path
        body = raw if raw is not None else json.dumps(payload).encode() if payload is not None else None
        headers = {'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json', 'User-Agent': '21day-release', 'Content-Type': 'application/vnd.android.package-archive' if raw is not None else 'application/json'}
        with urllib.request.urlopen(urllib.request.Request(url, data=body, headers=headers, method=method), timeout=180) as response:
            return json.load(response)

    if request('GET', '/user')['login'] != OWNER:
        raise SystemExit('Signed-in account does not match the selected release owner.')
    try:
        repo = request('GET', '/repos/' + REPOSITORY)
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        repo = request('POST', '/user/repos', {'name': '21day', 'description': '廿一 Android 安装包与版本发布；仅分发安装包，不上传项目源码。', 'private': False, 'auto_init': True})
    if repo.get('private') or not repo.get('permissions', {}).get('push', True):
        raise SystemExit('A public writable release repository is required.')
    notes = args.notes.read_text(encoding='utf-8-sig').rstrip() + '\n\nSHA-256: `' + checksum + '`\n'
    releases = request('GET', '/repos/' + REPOSITORY + '/releases?per_page=100')
    release = next((item for item in releases if item['tag_name'] == 'v' + args.version), None)
    if release is None:
        release = request('POST', '/repos/' + REPOSITORY + '/releases', {'tag_name': 'v' + args.version, 'name': '廿一 ' + args.version, 'body': notes, 'draft': True, 'prerelease': False})
    assets = request('GET', '/repos/' + REPOSITORY + '/releases/' + str(release['id']) + '/assets')
    asset = next((item for item in assets if item['name'] == expected_name), None)
    if asset:
        if asset.get('digest') != 'sha256:' + checksum:
            raise SystemExit('An asset with this name already exists with different contents; use a new release version.')
    else:
        upload = release['upload_url'].split('{')[0] + '?name=' + urllib.parse.quote(expected_name)
        asset = request('POST', upload, raw=binary)
    if asset.get('digest') != 'sha256:' + checksum or asset['size'] != len(binary):
        raise SystemExit('Uploaded asset verification failed; release stays draft.')
    if args.publish:
        release = request('PATCH', '/repos/' + REPOSITORY + '/releases/' + str(release['id']), {'body': notes, 'draft': False, 'prerelease': False, 'make_latest': 'true'})
        asset = request('GET', '/repos/' + REPOSITORY + '/releases/assets/' + str(asset['id']))
    print(json.dumps({'url': release['html_url'], 'draft': release['draft'], 'asset_url': asset['browser_download_url'], 'sha256': checksum}, ensure_ascii=False))

if __name__ == '__main__':
    main()
