import hashlib,json,shutil,subprocess,time,urllib.request,zipfile
from pathlib import Path
root=Path('/data/three-project');archive=root/'day21-replay014.zip'
if hashlib.sha256(archive.read_bytes()).hexdigest()!='47ee9129bd435e9b9e5aa94f6c98632da726022a03f6880a2e961677245a2c65':raise RuntimeError('Archive mismatch')
expected=json.loads((root/'day21-replay014-manifest.json').read_text());app=root/'21day/app';backup=root/'backups'/('day21-replay014-'+time.strftime('%Y%m%d-%H%M%S'));backup.mkdir(mode=0o700)
with zipfile.ZipFile(archive) as package:
    if set(package.namelist())!=set(expected) or set(expected)!={'Day21.Server.dll','Day21.Server.pdb','Day21.Server.deps.json','ProjectInterop.Sync.dll'}:raise RuntimeError('Unexpected file set')
    for name,digest in expected.items():
        if hashlib.sha256(package.read(name)).hexdigest()!=digest:raise RuntimeError('File mismatch')
        shutil.copy2(app/name,backup/name)
    try:
        for name in expected:
            pending=app/(name+'.update');pending.write_bytes(package.read(name));pending.chmod(0o644);pending.replace(app/name)
        subprocess.run(['docker','restart','project-21day-api'],check=True,capture_output=True,timeout=30)
        for _ in range(20):
            try:
                with urllib.request.urlopen('http://127.0.0.1:18768/health',timeout=3) as r:health=json.load(r)
                if health.get('project')=='21day':break
            except OSError:pass
            time.sleep(1)
        else:raise RuntimeError('Health did not recover')
        print(json.dumps({'status':'PASS','backup':str(backup),'hashes':expected,'health':health}))
    except Exception:
        for name in expected:shutil.copy2(backup/name,app/name)
        subprocess.run(['docker','restart','project-21day-api'],check=True,capture_output=True,timeout=30)
        raise
