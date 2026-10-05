"""Self-cleaning HTTPS source projection check with two synthetic accounts; no real mail or user data."""
import base64,hashlib,json,secrets,subprocess,urllib.request,urllib.error,uuid
BASE='https://zhuisu.leadjet.com.cn/'
owners=[str(uuid.uuid4()),str(uuid.uuid4())];emails=['island-probe-'+o+'@example.invalid' for o in owners]
password='Aa1!'+secrets.token_hex(20);salt=secrets.token_bytes(16)
password_hash='pbkdf2-sha256$600000$'+base64.b64encode(salt).decode()+'$'+base64.b64encode(hashlib.pbkdf2_hmac('sha256',password.encode(),salt,600000,32)).decode()
def sql(db,statement):
    p=subprocess.run(['docker','exec','-i','project-accounts-postgres','psql','-U','postgres','-d',db,'-v','ON_ERROR_STOP=1','-At'],input=statement,text=True,capture_output=True)
    if p.returncode:raise RuntimeError('Synthetic DB setup/cleanup failed: '+db)
    return p.stdout
def request(path,method='GET',data=None,token=None,expected=200):
    headers={'Content-Type':'application/json'}
    if token:headers['Authorization']='Bearer '+token
    req=urllib.request.Request(BASE+path,None if data is None else json.dumps(data).encode(),headers,method=method)
    try:
        with urllib.request.urlopen(req,timeout=40) as r:status=r.status;body=r.read()
    except urllib.error.HTTPError as e:status=e.code;body=e.read()
    if status!=expected:raise RuntimeError(f'HTTP {status}, expected {expected}: {path}')
    return json.loads(body) if body else None
try:
    for owner,email in zip(owners,emails):sql('identity_db',f"INSERT INTO email_users(id,email,password_hash,verified_at) VALUES('{owner}','{email}','{password_hash}',now());")
    tokens=[request('identity/v1/email/login','POST',{'email':e,'password':password,'clientId':'21day-android','deviceId':str(uuid.uuid4())})['accessToken'] for e in emails]
    request('island-api/v1/items/cards',expected=401)
    item=str(uuid.uuid4());unknown=str(uuid.uuid4())
    operations=[{'operationId':str(uuid.uuid4()),'entityType':'items','entityId':item,'baseRevision':0,'schemaVersion':1,'deleted':False,'data':{'adapter':'island-desktop-v1','tables':{'life_items':[{'id':item,'title':'Synthetic linked island item','status':'pending','due_utc_instant':'2026-10-06T08:00:00Z','deleted_at':None,'note':'private synthetic note'}]}}}, {'operationId':str(uuid.uuid4()),'entityType':'items','entityId':unknown,'baseRevision':0,'schemaVersion':1,'deleted':False,'data':{'adapter':'future-adapter','tables':{}}}]
    assert all(x['status']=='applied' for x in request('island-api/v1/sync/push','POST',operations,tokens[0]))
    before=request('island-api/v1/sync/pull?after=0',token=tokens[0])['highWatermark']
    result=request('island-api/v1/items/cards',token=tokens[0]);assert result['sourceProject']=='island'
    assert result['cards']==[{'sourceId':item,'sourceRevision':1,'title':'Synthetic linked island item','status':'pending','dueAt':'2026-10-06T08:00:00Z'}]
    assert request('island-api/v1/items/cards',token=tokens[1])['cards']==[]
    assert request('island-api/v1/sync/pull?after=0',token=tokens[0])['highWatermark']==before
finally:
    for owner,email in zip(owners,emails):
        for table in ['integration_outbox','sync_receipts','sync_changes','sync_entities','sync_streams']:sql('island_db',f"DELETE FROM {table} WHERE owner='{owner}';")
        sql('identity_db',f"DELETE FROM refresh_tokens WHERE session_id IN(SELECT id FROM identity_sessions WHERE user_id='{owner}');DELETE FROM identity_sessions WHERE user_id='{owner}';DELETE FROM email_users WHERE id='{owner}' AND email='{email}';DELETE FROM login_throttles WHERE email='{email}';")
print(json.dumps({'status':'PASS','checks':['anonymous 401','same-account canonical item projection','no private fields','unknown adapter excluded','other account empty','read leaves cursor unchanged'],'syntheticAccountsCleaned':2,'mailMessagesSent':0}))
