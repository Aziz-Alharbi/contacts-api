import base64
import copy
import json
from pathlib import Path
import unittest

from sanitize_export import sanitize
from get_token import claims
from check_identity import validate_reporter_claims

ROOT = Path(__file__).resolve().parents[1]

class IdentityConfigurationTest(unittest.TestCase):
    def setUp(self):
        self.realm = json.loads((ROOT / 'realm/contacts-realm.json').read_text())
        self.clients = {client['clientId']: client for client in self.realm['clients']}

    def test_reporter_is_scoped_service_account(self):
        client = self.clients['contacts-reporter']
        self.assertFalse(client['publicClient'])
        self.assertTrue(client['serviceAccountsEnabled'])
        self.assertFalse(client['standardFlowEnabled'])
        self.assertFalse(client['fullScopeAllowed'])
        account = next(user for user in self.realm['users'] if user.get('serviceAccountClientId'))
        self.assertEqual(['user'], account['realmRoles'])
        scopes = next(scope for scope in self.realm['scopeMappings'] if scope['client'] == 'contacts-reporter')
        self.assertEqual(['user'], scopes['roles'])

    def test_tokens_have_explicit_audience_and_roles_mappers(self):
        for client in self.clients.values():
            mappers = {mapper['protocolMapper']: mapper['config'] for mapper in client['protocolMappers']}
            self.assertEqual('contacts-api', mappers['oidc-audience-mapper']['included.client.audience'])
            self.assertEqual('roles', mappers['oidc-usermodel-realm-role-mapper']['claim.name'])
            self.assertEqual('true', mappers['oidc-usermodel-realm-role-mapper']['multivalued'])

    def test_credentials_are_placeholders_and_password_grant_disabled(self):
        self.assertEqual('${CONTACTS_REPORTER_SECRET}', self.clients['contacts-reporter']['secret'])
        for client in self.clients.values():
            self.assertFalse(client['directAccessGrantsEnabled'])
        for user in self.realm['users']:
            for credential in user.get('credentials', []):
                self.assertTrue(credential['value'].startswith('${CONTACTS_'))

    def test_export_removes_real_secrets_and_real_users(self):
        raw = copy.deepcopy(self.realm)
        raw['clients'][1]['secret'] = 'REAL_CLIENT_SECRET'
        raw['users'] = [{'username': 'REAL_USER', 'credentials': [{'secretData': 'PASSWORD_HASH'}]}]
        raw['components'] = {'keys': [{'config': {'privateKey': 'PRIVATE_KEY'}}]}
        raw['smtpServer'] = {'password': 'SMTP_PASSWORD'}
        output = json.dumps(sanitize(raw, self.realm))
        for secret in ('REAL_CLIENT_SECRET', 'REAL_USER', 'PASSWORD_HASH', 'PRIVATE_KEY', 'SMTP_PASSWORD'):
            self.assertNotIn(secret, output)
        self.assertIn('${CONTACTS_REPORTER_SECRET}', output)

    def test_claim_decoder_handles_unpadded_payload(self):
        expected = {'sub': 'service-id', 'aud': 'contacts-api', 'roles': ['user']}
        encoded = base64.urlsafe_b64encode(json.dumps(expected).encode()).decode().rstrip('=')
        self.assertEqual(expected, claims('header.' + encoded + '.signature'))

    def test_live_check_accepts_string_and_array_audiences(self):
        payload = {'sub': 'service-id', 'iss': 'http://localhost:8081/realms/contacts',
                   'aud': 'contacts-api', 'exp': 200, 'roles': ['user'], 'azp': 'contacts-reporter'}
        validate_reporter_claims(payload, now=100)
        payload['aud'] = ['contacts-api', 'account']
        validate_reporter_claims(payload, now=100)

    def test_live_check_rejects_wrong_or_overprivileged_tokens(self):
        valid = {'sub': 'service-id', 'iss': 'http://localhost:8081/realms/contacts',
                 'aud': 'contacts-api', 'exp': 200, 'roles': ['user'], 'azp': 'contacts-reporter'}
        for field, invalid in [('sub', ''), ('iss', 'wrong-realm'), ('aud', 'wrong-api'),
                               ('exp', 99), ('roles', ['user', 'admin']), ('roles', []),
                               ('azp', 'wrong-client')]:
            with self.subTest(field=field, invalid=invalid):
                payload = dict(valid, **{field: invalid})
                with self.assertRaises(ValueError):
                    validate_reporter_claims(payload, now=100)

if __name__ == '__main__':
    unittest.main()
