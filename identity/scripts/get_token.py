"""Obtain a reporter or browser-login user token; print selected claims only."""
import argparse
import base64
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request
import hashlib
import base64
import secrets

ROOT = Path(__file__).resolve().parents[2]
ISSUER = 'http://localhost:8081/realms/contacts'

def local_env():
    values = {}
    path = ROOT / '.env'
    if path.exists():
        for line in path.read_text().splitlines():
            if line.strip() and not line.lstrip().startswith('#') and '=' in line:
                name, value = line.split('=', 1)
                values[name.strip()] = value.strip()
    values.update(os.environ)
    return values

def post(endpoint, values):
    request = urllib.request.Request(ISSUER + endpoint,
        data=urllib.parse.urlencode(values).encode(),
        headers={'Content-Type': 'application/x-www-form-urlencoded'})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        body = json.load(error)
        return {'error': body.get('error', 'http_error')}

def claims(token):
    payload = token.split('.')[1]
    return json.loads(base64.urlsafe_b64decode(payload + '=' * (-len(payload) % 4)))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('identity', choices=['reporter', 'user'])
    parser.add_argument('--save', type=Path, help='Save access token to a NEW private file')
    args = parser.parse_args()
    if args.identity == 'reporter':
        secret = local_env().get('CONTACTS_REPORTER_SECRET')
        if not secret:
            raise SystemExit('Set CONTACTS_REPORTER_SECRET in .env or your environment.')
        result = post('/protocol/openid-connect/token', {
            'grant_type': 'client_credentials', 'client_id': 'contacts-reporter', 'client_secret': secret})
    else:
        verifier = base64.urlsafe_b64encode(
            secrets.token_bytes(32)
        ).rstrip(b'=').decode('ascii')

        challenge = base64.urlsafe_b64encode(
            hashlib.sha256(verifier.encode('ascii')).digest()
        ).rstrip(b'=').decode('ascii')
        device = post('/protocol/openid-connect/auth/device', {
            'client_id': 'contacts-api',
            'scope': 'openid profile email',
            'code_challenge_method': 'S256',
            'code_challenge': challenge
        })


        if 'error' in device:
            raise SystemExit('Device login failed: ' + device['error'])
        print('Open:', device['verification_uri'])
        print('Enter code:', device['user_code'])
        print('Sign in as demo-user or demo-admin with the password from your local .env.')
        deadline = time.monotonic() + device['expires_in']
        interval = device.get('interval', 5)
        while time.monotonic() < deadline:
            time.sleep(interval)
            result = post('/protocol/openid-connect/token', {
                'grant_type': 'urn:ietf:params:oauth:grant-type:device_code',
                'client_id': 'contacts-api',
                'device_code': device['device_code'],
                'code_verifier': verifier})
            if result.get('error') == 'slow_down':
                interval += 5
            elif result.get('error') != 'authorization_pending':
                break
        else:
            raise SystemExit('Device login expired; run the command again.')
    if 'access_token' not in result:
        raise SystemExit('Token request failed: ' + result.get('error', 'invalid response'))
    token = result['access_token']
    payload = claims(token)
    print(json.dumps({name: payload.get(name) for name in ('sub', 'iss', 'aud', 'exp', 'roles')}, indent=2))
    print('Decoded claims only; this script does not verify the JWT signature.')
    if args.save:
        descriptor = os.open(args.save, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'w') as file:
            file.write(token)
        print('Saved access token to', args.save)

if __name__ == '__main__':
    try:
        main()
    except (urllib.error.URLError, TimeoutError):
        raise SystemExit('Cannot connect to Keycloak. Start docker compose and wait for realm import.')
