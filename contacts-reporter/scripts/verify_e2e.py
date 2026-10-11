"""Disposable MySQL + actual Keycloak + packaged API/Reporter verification."""
import json, os, secrets, shutil, socket, subprocess, sys, time, urllib.error, urllib.request, uuid
from pathlib import Path
root=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(root/'identity/scripts'))
from get_token import local_env, post
DOCKER=shutil.which('docker') or '/Applications/Docker.app/Contents/Resources/bin/docker'
if not Path(DOCKER).is_file(): raise SystemExit('Install Docker and start its daemon before running this check.')
name='contacts-e2e-mysql-'+uuid.uuid4().hex[:8]
password=secrets.token_urlsafe(32)
env=dict(os.environ, **local_env()); env['MYSQL_ROOT_PASSWORD']=password
env['PATH']=str(Path(DOCKER).parent)+os.pathsep+env.get('PATH','')
processes=[]; files=[]; container=False

def http(url,token=None,data=None):
    headers={'Content-Type':'application/json'}
    if token: headers['Authorization']='Bearer '+token
    request=urllib.request.Request(url,headers=headers,data=json.dumps(data).encode() if data is not None else None)
    try:
        with urllib.request.urlopen(request,timeout=5) as response: return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, None

def start(module,port,variables):
    runenv=dict(env,**variables)
    log=open('/tmp/'+module+'-e2e-'+str(port)+'.log','w');files.append(log)
    process=subprocess.Popen(['java','-Dquarkus.http.port='+str(port),'-jar',str(root/module/'target/quarkus-app/quarkus-run.jar')],env=runenv,stdout=log,stderr=subprocess.STDOUT)
    processes.append(process)
    for attempt in range(40):
        try:
            with socket.create_connection(('127.0.0.1',port),timeout=1):return
        except OSError:
            if process.poll() is not None:raise RuntimeError(module+' failed to start; inspect its local test log.')
            time.sleep(1)
    raise RuntimeError(module+' startup timed out.')

try:
    subprocess.run([DOCKER,'info'],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True)
    subprocess.run([DOCKER,'run','--rm','-d','--name',name,'-p','127.0.0.1:13306:3306','-e','MYSQL_ROOT_PASSWORD','-e','MYSQL_DATABASE=contacts_db','-e','MYSQL_ROOT_HOST=%','mysql:8.4'],env=env,stdout=subprocess.DEVNULL,check=True)
    container=True
    for attempt in range(60):
        ready=subprocess.run([DOCKER,'exec',name,'mysqladmin','ping','-h127.0.0.1','--silent'],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        if ready.returncode==0:
            try:
                with socket.create_connection(('127.0.0.1',13306),timeout=1):break
            except OSError:pass
        time.sleep(1)
    else:raise RuntimeError('Disposable MySQL failed to start.')
    result=post('/protocol/openid-connect/token', {'grant_type':'client_credentials','client_id':'contacts-reporter','client_secret':env['CONTACTS_REPORTER_SECRET']})
    if 'access_token' not in result:raise RuntimeError('Local Keycloak rejected the configured reporter credentials.')
    token=result['access_token']
    start('contacts-api',18080,{'DB_URL':'jdbc:mysql://localhost:13306/contacts_db?allowPublicKeyRetrieval=true&useSSL=false','DB_USERNAME':'root','DB_PASSWORD':password})
    api='http://localhost:18080/api/v1'
    assert http(api+'/contacts')[0]==401
    for index in range(2):
        status,_=http(api+'/contacts',token,{'firstName':'Reporter','lastName':'Test','email':f'reporter-e2e-{index}@example.test'})
        assert status==201, status
    assert http(api+'/groups',token,{'name':'forbidden-test'})[0]==403
    start('contacts-reporter',18082,{'CONTACTS_API_URL':'http://localhost:18080'})
    status,report=http('http://localhost:18082/reports/contacts/count')
    assert status==200 and report=={'contactCount':2,'scope':'service-account'}, (status,report)
    print('PASS: real MySQL + Keycloak + API + Reporter; two created contacts produce contactCount=2 without inbound login.')
    start('contacts-reporter',18083,{'CONTACTS_API_URL':'http://localhost:18080','CONTACTS_REPORTER_SECRET':'intentionally-invalid-test-secret'})
    status,_=http('http://localhost:18083/reports/contacts/count')
    assert status==503,status
    print('PASS: actual Keycloak rejects wrong secret; Reporter returns generic 503. Missing API JWT=401; forbidden service group write=403.')
finally:
    for process in reversed(processes):
        process.terminate()
        try:process.wait(timeout=10)
        except subprocess.TimeoutExpired:process.kill();process.wait()
    for file in files:file.close()
    if container:subprocess.run([DOCKER,'stop',name],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
