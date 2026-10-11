"""Smoke-test live reporter claims; does not verify JWT signatures."""
import json
import time
import urllib.error
import urllib.request

from get_token import ISSUER, claims, local_env, post


def validate_reporter_claims(payload, now=None):
    now = time.time() if now is None else now
    if not payload.get('sub'):
        raise ValueError('Reporter token has no subject.')
    if payload.get('iss') != ISSUER:
        raise ValueError('Reporter token has an unexpected issuer.')
    audiences = payload.get('aud', [])
    if isinstance(audiences, str):
        audiences = [audiences]
    if 'contacts-api' not in audiences:
        raise ValueError('Reporter token does not target contacts-api.')
    if not isinstance(payload.get('exp'), (int, float)) or payload['exp'] <= now:
        raise ValueError('Reporter token is expired or has no expiration.')
    roles = payload.get('roles', [])
    if not isinstance(roles, list) or 'user' not in roles or 'admin' in roles:
        raise ValueError('Reporter token must have user access without admin access.')
    if payload.get('azp') != 'contacts-reporter':
        raise ValueError('Reporter token has an unexpected authorized client.')


def main():
    with urllib.request.urlopen(ISSUER + '/.well-known/openid-configuration', timeout=15) as response:
        discovery = json.load(response)
    if discovery.get('issuer') != ISSUER:
        raise ValueError('Discovery issuer does not match the configured realm URL.')
    secret = local_env().get('CONTACTS_REPORTER_SECRET')
    if not secret or secret.startswith('REPLACE_'):
        raise ValueError('Generate CONTACTS_REPORTER_SECRET in .env before checking the server.')
    result = post('/protocol/openid-connect/token', {
        'grant_type': 'client_credentials', 'client_id': 'contacts-reporter', 'client_secret': secret})
    if 'access_token' not in result:
        raise ValueError('Reporter token request failed: ' + result.get('error', 'invalid response'))
    validate_reporter_claims(claims(result['access_token']))
    print('PASS: discovery and reporter sub, iss, aud, exp, roles, and client identity.')
    print('Decoded claims only; API JWT signature validation is a separate step.')


if __name__ == '__main__':
    try:
        main()
    except (urllib.error.URLError, TimeoutError):
        raise SystemExit('Cannot reach Keycloak. Start Docker Compose and wait for realm import.')
    except ValueError as error:
        raise SystemExit(str(error))
