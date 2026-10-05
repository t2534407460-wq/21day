"""Synthetic, self-cleaning production-path check; never prints passwords, tokens, keys or document text."""
import base64,datetime,hashlib,json,secrets,subprocess,time,urllib.request,urllib.error,uuid
from pathlib import Path

BASE='https://zhuisu.leadjet.com.cn/'
owners=[str(uuid.uuid4()),str(uuid.uuid4())];emails=['interop-probe-'+owner+'@example.invalid' for owner in owners]
password='Aa1!'+secrets.token_hex(20);salt=secrets.token_bytes(16)
password_hash='pbkdf2-sha256$600000$'+base64.b64encode(salt).decode()+'$'+base64.b64encode(hashlib.pbkdf2_hmac('sha256',password.encode(),salt,600000,32)).decode()
key=secrets.token_hex(24);key_name='probe-'+uuid.uuid4().hex
configuration=Path('/data/three-project/knowledge-v2/appsettings.json')
checks=[]
def sql(db,statement):
    p=subprocess.run(['docker','exec','-i','project-accounts-postgres','psql','-U','postgres','-d',db,'-v','ON_ERROR_STOP=1','-At'],input=statement,text=True,capture_output=True)
    if p.returncode:raise RuntimeError('Synthetic database setup/cleanup failed in '+db)
    return p.stdout
def request(path,method='GET',data=None,token=None,k=None,expect=200):
    headers={}
    if token:headers['Authorization']='Bearer '+token
    if k:headers['X-Knowledge-Key']=k
    if data is not None:headers['Content-Type']='application/json'
    req=urllib.request.Request(BASE+path,None if data is None else json.dumps(data).encode(),headers,method=method)
    try:
        with urllib.request.urlopen(req,timeout=40) as r:status=r.status;body=r.read()
    except urllib.error.HTTPError as e:status=e.code;body=e.read()
    if status!=expect:raise RuntimeError(f'Unexpected HTTP {status} for {path.split("?")[0]}, expected {expect}')
    return json.loads(body) if body else None
try:
    for owner,email in zip(owners,emails):sql('identity_db',f"INSERT INTO email_users(id,email,password_hash,verified_at) VALUES('{owner}','{email}','{password_hash}',now());")
    tokens=[request('identity/v1/email/login','POST',{'email':email,'password':password,'clientId':'21day-android','deviceId':str(uuid.uuid4())})['accessToken'] for email in emails]
    request('21day-api/v1/habits/cards',expect=401);request('knowledge/v2/access',expect=401);checks.append('anonymous rejected')
    day=datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).date().isoformat();habit=str(uuid.uuid4());device=str(uuid.uuid4())
    data={'plan':{'id':habit,'name':'Synthetic integration count','start':day,'mode':'AT_LEAST','unit':'次','input':'COUNT','rules':[{'from':day,'days':[1,2,3,4,5,6,7],'target':3,'reminder':1260}],'archivedOn':None,'smoking':False,'visual':'READING'},'entries':{},'timer':None}
    op={'operationId':str(uuid.uuid4()),'entityType':'habits','entityId':habit,'baseRevision':0,'schemaVersion':1,'data':data,'deleted':False}
    created=request('21day-api/v1/sync/push','POST',[op],tokens[0])[0];assert created['status']=='applied'
    card=request('21day-api/v1/habits/cards',token=tokens[0])['cards'][0];assert card['sourceId']==habit
    changed=json.loads(json.dumps(data));changed['entries'][day]={'habitId':habit,'date':day,'value':1,'note':'','recordedAt':int(time.time()*1000),'remainderSeconds':0,'sessions':[]}
    op.update(operationId=str(uuid.uuid4()),baseRevision=created['revision'],data=changed,baseline=data,kind='count')
    command={'operation':op,'action':'count','day':day,'timeZoneId':'Asia/Shanghai','deviceId':device}
    result=request('21day-api/v1/habits/commands','POST',command,tokens[0]);assert result['status']=='applied'
    replay=request('21day-api/v1/habits/commands','POST',command,tokens[0]);assert result==replay
    assert request('21day-api/v1/habits/cards',token=tokens[0])['cards'][0]['entry']['value']==1
    assert request('21day-api/v1/habits/cards',token=tokens[1])['cards']==[]
    assert request('21day-api/v1/sync/pull?after=0',token=tokens[0])['highWatermark']==2
    checks.append('habit source command replay and owner isolation')
    assert request('21day-api/v1/vault/status',token=tokens[0])['ready']
    value={'key':'sk-synthetic-integration-not-a-real-key'}
    assert request('21day-api/v1/vault/deepseek','PUT',{'baseRevision':0,'value':value},tokens[0])['revision']==1
    assert request('21day-api/v1/vault/deepseek',token=tokens[0])['value']==value
    request('21day-api/v1/vault/deepseek',token=tokens[1],expect=404)
    checks.append('protected vault write read and owner isolation')
    current=json.loads(configuration.read_text());current['Knowledge']['Clients'][key_name]={'KeyHash':hashlib.sha256(key.encode()).hexdigest().upper(),'CanSync':False};configuration.write_text(json.dumps(current));subprocess.run(['docker','restart','project-knowledge-v2'],check=True,stdout=subprocess.DEVNULL);time.sleep(4)
    request('knowledge/v2/access',token=tokens[0],expect=403)
    access=request('knowledge/v2/access',token=tokens[0],k=key);snapshot=access['snapshotId'];assert access['grants'][0]['version']=='7.0'
    request('knowledge/v2/search','POST',{'project':'sMES','version':'6.0','question':'工作台'},tokens[0],key,403)
    search=request('knowledge/v2/search','POST',{'project':'sMES','version':'7.0','question':'工作台 权限'},tokens[0],key)
    assert search['sources'] and all(s['relativePath'].startswith('sMES/版本/7.0/') for s in search['sources'])
    path='sMES/版本/7.0/INDEX.md'
    query=urllib.parse.urlencode({'project':'sMES','version':'7.0','snapshotId':snapshot,'path':path})
    doc=request('knowledge/v2/read?'+query,token=tokens[0],k=key)
    assert request('knowledge/v2/verify','POST',{'project':'sMES','version':'7.0','path':path,'snapshotId':snapshot,'contentHash':doc['contentHash']},tokens[0],key)['unchanged']
    note={'baseRevision':0,'project':'sMES','version':'7.0','path':path,'snapshotId':snapshot,'contentHash':doc['contentHash'],'bookmark':True,'note':'Synthetic check; removed after test','sourceProject':'21day','sourceId':habit}
    noteid=str(uuid.uuid4());assert request('knowledge/v2/notes/'+noteid,'PUT',note,tokens[0],key)['revision']==1
    assert request('knowledge/v2/notes/'+noteid,'PUT',note,tokens[0],key)['revision']==1
    assert request('knowledge/v2/notes',token=tokens[1],k=key)['notes']==[]
    checks.append('knowledge key version source hash linked note replay and isolation')
finally:
    current=json.loads(configuration.read_text());current['Knowledge']['Clients'].pop(key_name,None);configuration.write_text(json.dumps(current));subprocess.run(['docker','restart','project-knowledge-v2'],check=True,stdout=subprocess.DEVNULL)
    for owner,email in zip(owners,emails):
        for table in ['vault_values','vault_keys','integration_outbox','sync_receipts','sync_changes','sync_entities','sync_streams']:sql('day21_db',f"DELETE FROM {table} WHERE owner='{owner}';")
        sql('knowledge_meta_db',f"DELETE FROM knowledge_notes WHERE owner='{owner}';")
        sql('identity_db',f"DELETE FROM refresh_tokens WHERE session_id IN(SELECT id FROM identity_sessions WHERE user_id='{owner}'); DELETE FROM identity_sessions WHERE user_id='{owner}';DELETE FROM email_users WHERE id='{owner}' AND email='{email}';DELETE FROM login_throttles WHERE email='{email}';")
print(json.dumps({'status':'PASS','checks':checks,'syntheticAccountsCleaned':2,'temporaryReadKeyRemoved':True,'mailMessagesSent':0}))
