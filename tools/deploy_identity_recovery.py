"""Run on the authorized host after uploading a fixed identity bundle and SHA-256 manifest."""
import argparse,hashlib,json,shutil,subprocess,time,urllib.request,zipfile
from pathlib import Path

p=argparse.ArgumentParser();p.add_argument('--archive',required=True);p.add_argument('--sha256',required=True);args=p.parse_args()
archive=Path(args.archive)
if hashlib.sha256(archive.read_bytes()).hexdigest()!=args.sha256:raise RuntimeError('Archive hash mismatch')
root=Path('/data/three-project/identity/app');backup=Path('/data/three-project/backups')/('identity-recovery-'+time.strftime('%Y%m%d-%H%M%S'));backup.mkdir(mode=0o700)
changed={}
def restart():
    subprocess.run(['docker','restart','project-identity'],check=True,capture_output=True,timeout=30)
    for _ in range(25):
        try:
            with urllib.request.urlopen('http://127.0.0.1:18766/health',timeout=3) as response:health=json.load(response)
            if health.get('project')=='identity' and health.get('mailReady'):return health
        except (OSError,ValueError):pass
        time.sleep(1)
    raise RuntimeError('Identity health did not recover')
with zipfile.ZipFile(archive) as package:
    manifest=json.loads(package.read('manifest.json'))
    if set(package.namelist())!=set(manifest)|{'manifest.json'}:raise RuntimeError('Unexpected bundle files')
    if not {'Identity.Server.dll','Identity.Server.deps.json','QRCoder.dll'}<=set(manifest):raise RuntimeError('Incomplete bundle')
    for name,digest in manifest.items():
        if '/' in name or '\\' in name or not name.endswith(('.dll','.pdb','.deps.json','.runtimeconfig.json')):raise RuntimeError('Invalid bundle path')
        data=package.read(name)
        if hashlib.sha256(data).hexdigest()!=digest:raise RuntimeError('File hash mismatch')
        target=root/name
        if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest()==digest:continue
        changed[name]=target.exists()
        if target.exists():shutil.copy2(target,backup/name)
    try:
        for name in changed:
            pending=root/(name+'.update');pending.write_bytes(package.read(name));pending.chmod(0o644);pending.replace(root/name)
        health=restart()
        request=urllib.request.Request('http://127.0.0.1:18766/identity/v1/wake-recovery/request',data=b'{"deviceId":"00000000-0000-0000-0000-000000000001","sessionId":"00000000-0000-0000-0000-000000000002"}',headers={'Content-Type':'application/json'},method='POST')
        try:urllib.request.urlopen(request,timeout=5);raise RuntimeError('Anonymous recovery must fail')
        except urllib.error.HTTPError as e:
            if e.code!=401:raise
        print(json.dumps({'status':'PASS','backup':str(backup),'changed':list(changed),'hashes':manifest,'health':health,'anonymousRecovery':401}))
    except Exception:
        for name,existed in changed.items():
            if existed:shutil.copy2(backup/name,root/name)
            else:(root/name).unlink(missing_ok=True)
        restart();raise
