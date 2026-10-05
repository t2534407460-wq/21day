"""Run on the authorized host after uploading the fixed, hash-checked release archive."""
import hashlib,json,os,secrets,shutil,subprocess,time,urllib.request,zipfile
from pathlib import Path

ROOT=Path('/data/three-project')
ARCHIVE_HASH='ed2630abeb47493f9cc14b3570a761ae2c38d1a4508caa247e48c568de9600fd'
archive=ROOT/'interop-services-20261005.zip'
if hashlib.sha256(archive.read_bytes()).hexdigest()!=ARCHIVE_HASH:raise RuntimeError('Archive hash mismatch')
manifest=json.loads((ROOT/'interop-services-manifest.json').read_text())
stamp=time.strftime('%Y%m%d-%H%M%S')
backup=ROOT/'backups'/('interop-'+stamp);backup.mkdir(mode=0o700)

def run(args,input=None):
    result=subprocess.run(args,input=input,text=True,capture_output=True,timeout=60)
    if result.returncode:raise RuntimeError('Command failed: '+args[0]+' '+args[1]+' (details retained on host, no credentials printed)')
    return result.stdout
def health(port,project):
    for attempt in range(20):
        try:
            with urllib.request.urlopen(f'http://127.0.0.1:{port}/health',timeout=3) as response: value=json.load(response)
            if value.get('project')==project:return value
        except (OSError,ValueError):pass
        time.sleep(1)
    raise RuntimeError('Health did not recover for '+project)
def protected(path,content,uid=1654):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_bytes(content);path.chmod(0o600);os.chown(path,uid,uid)
def sql(statement):
    return run(['docker','exec','-i','project-accounts-postgres','psql','-U','postgres','-d','postgres','-v','ON_ERROR_STOP=1','-At'],statement)

old21=json.loads(run(['docker','inspect','project-21day-api']))[0]
site=Path('/opt/1panel/www/sites/zhuisu.leadjet.com.cn/proxy')
route=site/'project-knowledge-v2.conf'
if route.exists() or (ROOT/'knowledge-v2/app').exists():raise RuntimeError('Existing v2 deployment requires reviewed update, not initial setup')
previous_files={};renamed=False;new21=False;newknowledge=False;route_created=False
try:
    with zipfile.ZipFile(archive) as package:
        if set(package.namelist())!=set(manifest):raise RuntimeError('Unexpected archive contents')
        for name,digest in manifest.items():
            service,filename=name.split('/')
            if service not in ('identity','21day','knowledge-v2') or '/' in filename or filename in ('appsettings.json','appsettings.Production.json'):raise RuntimeError('Invalid release path')
            content=package.read(name)
            if hashlib.sha256(content).hexdigest()!=digest:raise RuntimeError('Release file mismatch')
            target=ROOT/service/'app'/filename;target.parent.mkdir(parents=True,exist_ok=True)
            if target.exists():
                saved=backup/service/filename;saved.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(target,saved);previous_files[name]=saved
            else:previous_files[name]=None
            temp=target.with_suffix(target.suffix+'.update');temp.write_bytes(content);temp.chmod(0o644);temp.replace(target)
    keyfile=ROOT/'21day/secrets/vault.key'
    if not keyfile.exists():protected(keyfile,secrets.token_bytes(32))
    if len(keyfile.read_bytes())!=32:raise RuntimeError('Invalid vault key')
    keybackup=ROOT/'vault-key-backups/21day.key'
    if not keybackup.exists():protected(keybackup,keyfile.read_bytes(),0);keybackup.parent.chmod(0o700)
    settings=ROOT/'21day/appsettings.json';shutil.copy2(settings,backup/'day21-settings.json');config=json.loads(settings.read_text());config['Vault']={'MasterKeyFile':'/run/day21-secrets/vault.key'};protected(settings,json.dumps(config).encode())
    # Keep database and login identity independent; no public or cross-project DB privileges.
    if sql("SELECT 1 FROM pg_roles WHERE rolname='knowledge_app';").strip():raise RuntimeError('Knowledge role already exists; inspect before provisioning')
    password=secrets.token_hex(32)
    sql("CREATE ROLE knowledge_app LOGIN PASSWORD '"+password+"' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;\nCREATE DATABASE knowledge_meta_db OWNER knowledge_app;\nREVOKE CONNECT ON DATABASE knowledge_meta_db FROM PUBLIC;\nGRANT CONNECT ON DATABASE knowledge_meta_db TO knowledge_app;")
    protected(ROOT/'database/secrets/knowledge',password.encode(),0)
    legacy=json.loads(Path('/opt/project-knowledge/secrets.json').read_text())
    clients=legacy['Knowledge']['Clients']
    if not any(not c.get('CanSync',False) for c in clients.values()):raise RuntimeError('No configured read-only knowledge client')
    knowledge={'ConnectionStrings':{'Knowledge':f'Host=127.0.0.1;Port=25432;Database=knowledge_meta_db;Username=knowledge_app;Password={password}'},'Knowledge':{'DataRoot':'/knowledge-data','Clients':clients,'Versions':['7.0']}}
    protected(ROOT/'knowledge-v2/appsettings.json',json.dumps(knowledge).encode())
    run(['docker','restart','project-identity']);identity_health=health(18766,'identity')
    run(['docker','stop','project-21day-api']);oldname='project-21day-api-before-'+stamp;run(['docker','rename','project-21day-api',oldname]);renamed=True
    args=['docker','run','-d','--name','project-21day-api','--network',old21['HostConfig']['NetworkMode'],'--user',old21['Config']['User'],'--restart',old21['HostConfig']['RestartPolicy']['Name'],'--read-only','--tmpfs','/tmp:rw,noexec,nosuid,size=64m','--cap-drop','ALL','--security-opt','no-new-privileges']
    for env in old21['Config']['Env']:args+=['-e',env]
    for mount in old21['Mounts']:args+=['-v',mount['Source']+':'+mount['Destination']+(':ro' if not mount['RW'] else ':rw')]
    args+=['-v',str(keyfile.parent)+':/run/day21-secrets:ro','-w',old21['Config']['WorkingDir'] or '/app',old21['Config']['Image']]+old21['Config']['Cmd']
    run(args);new21=True;day_health=health(18768,'21day')
    run(['docker','run','-d','--name','project-knowledge-v2','--network','host','--user','1654:1654','--restart','unless-stopped','--read-only','--tmpfs','/tmp:rw,noexec,nosuid,size=64m','--cap-drop','ALL','--security-opt','no-new-privileges','-e','ASPNETCORE_URLS=http://127.0.0.1:18769','-e','ASPNETCORE_ENVIRONMENT=Production','-v',str(ROOT/'knowledge-v2/app')+':/app:ro','-v',str(ROOT/'knowledge-v2/appsettings.json')+':/app/appsettings.json:ro','-v','/opt/project-knowledge/data:/knowledge-data:ro','-w','/app','mcr.microsoft.com/dotnet/aspnet:10.0.12','dotnet','Smes.Knowledge.Api.dll']);newknowledge=True;knowledge_health=health(18769,'knowledge')
    route.write_text('''# Independent key-gated knowledge reader and account metadata.
location = /knowledge/v2/health {
    if ($scheme != https) { return 426; }
    proxy_pass http://127.0.0.1:18769/health;
    proxy_set_header Host $host;
}
location ^~ /knowledge/v2/ {
    if ($scheme != https) { return 426; }
    client_max_body_size 32k;
    proxy_pass http://127.0.0.1:18769;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 40s;
}
''');route_created=True
    run(['docker','exec','1Panel-openresty-eiwX','nginx','-t']);run(['docker','exec','1Panel-openresty-eiwX','nginx','-s','reload'])
    result={'status':'PASS','backup':str(backup),'archiveHash':ARCHIVE_HASH,'services':[identity_health,day_health,knowledge_health],'old21Container':oldname,'fileHashes':manifest,'mailMessagesSent':0}
    (backup/'result.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
except Exception:
    if route_created:route.unlink();run(['docker','exec','1Panel-openresty-eiwX','nginx','-s','reload'])
    if newknowledge:run(['docker','stop','project-knowledge-v2'])
    if new21:run(['docker','rm','-f','project-21day-api'])
    for name,saved in previous_files.items():
        service,filename=name.split('/');target=ROOT/service/'app'/filename
        if saved is not None:shutil.copy2(saved,target)
        elif target.exists():target.unlink()
    if (backup/'day21-settings.json').exists():shutil.copy2(backup/'day21-settings.json',ROOT/'21day/appsettings.json')
    if renamed:run(['docker','rename',oldname,'project-21day-api']);run(['docker','start','project-21day-api'])
    run(['docker','restart','project-identity'])
    print(json.dumps({'status':'ROLLED_BACK','backup':str(backup),'note':'New isolated database and protected keys retained for inspection; no user data removed.'}))
    raise
