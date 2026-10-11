"""Produce a shareable contacts realm from an offline Keycloak export.

Keep only the configuration keys represented in the tracked template. Replace
all users and credentials with the template's demonstration placeholders.
"""
import argparse
import copy
import json
from pathlib import Path

TEMPLATE = Path(__file__).resolve().parents[1] / 'realm/contacts-realm.json'

def sanitize(export, template):
    if export.get('realm') != template['realm']:
        raise ValueError('Expected the contacts realm.')
    result = copy.deepcopy(template)
    for key in ('enabled', 'displayName', 'registrationAllowed', 'resetPasswordAllowed',
                'rememberMe', 'sslRequired', 'accessTokenLifespan'):
        if key in export:
            result[key] = export[key]
    clients = {client['clientId']: client for client in export.get('clients', [])}
    for client in result['clients']:
        if client['clientId'] not in clients:
            raise ValueError('Missing application client: ' + client['clientId'])
        source = clients[client['clientId']]
        for key in ('enabled', 'publicClient', 'standardFlowEnabled', 'directAccessGrantsEnabled',
                    'serviceAccountsEnabled', 'fullScopeAllowed', 'redirectUris', 'webOrigins'):
            if key in source:
                client[key] = source[key]
    # Roles, role scopes, mappers, users, and placeholders come from the reviewed
    # template; no live keys, password hashes, or service secrets are copied.
    return result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('raw', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    result = sanitize(json.loads(args.raw.read_text()), json.loads(TEMPLATE.read_text()))
    with args.output.open('x') as file:
        json.dump(result, file, indent=2)
        file.write('\n')
    print('Wrote configuration with demo users and credential placeholders only:', args.output)

if __name__ == '__main__':
    main()
