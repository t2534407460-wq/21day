"""Apply the coordinated island runtime archive; retain configuration and roll back failed health checks."""
import hashlib,json,shutil,subprocess,time,urllib.request,zipfile
from pathlib import Path
root=Path('/data/three-project');archive=root/'island-source-0.1.1-sync0.1.4-20261005.zip'
if hashlib.sha256(archive.read_bytes()).hexdigest()!='bbc177bdefb1245a89e58968d0dcb04eaa8b462f3be98b377bbd30675a5fa5bd':raise RuntimeError('Archive mismatch')
expected=json.loads((root/'island-source011-manifest.json').read_text())
app=root/'island/app';backup=root/'backups'/('island-source011-'+time.strftime('%Y%m%d-%H%M%S'));backup.mkdir(mode=0o700)
config_hash=hashlib.sha256((root/'island/appsettings.json').read_bytes()).hexdigest()
with zipfile.ZipFile(archive) as package:
    if set(package.namelist())!=set(expected) or len(expected)!=17:raise RuntimeError('Unexpected file set')
    for name,digest in expected.items():
        if '/' in name or '\\' in name or name in ['appsettings.json','NuGet.Config']:raise RuntimeError('Unsafe runtime entry')
        if hashlib.sha256(package.read(name)).hexdigest()!=digest:raise RuntimeError('File hash mismatch')
        if not (app/name).is_file():raise RuntimeError('Expected existing runtime file')
        shutil.copy2(app/name,backup/name)
    try:
        for name in expected:
            pending=app/(name+'.update');pending.write_bytes(package.read(name));pending.chmod(0o644);pending.replace(app/name)
        subprocess.run(['docker','restart','project-island-api'],check=True,capture_output=True,timeout=30)
        for _ in range(20):
            try:
                with urllib.request.urlopen('http://127.0.0.1:18767/health',timeout=3) as r:health=json.load(r)
                if health.get('project')=='island' and health.get('build')=='0.1.1':break
            except OSError:pass
            time.sleep(1)
        else:raise RuntimeError('Health did not recover')
        assert hashlib.sha256((root/'island/appsettings.json').read_bytes()).hexdigest()==config_hash
        assert all(hashlib.sha256((app/n).read_bytes()).hexdigest()==h for n,h in expected.items())
        print(json.dumps({'status':'PASS','backup':str(backup),'runtimeFiles':len(expected),'configUnchanged':True,'health':health}))
    except Exception:
        for name in expected:shutil.copy2(backup/name,app/name)
        subprocess.run(['docker','restart','project-island-api'],check=True,capture_output=True,timeout=30)
        raise
