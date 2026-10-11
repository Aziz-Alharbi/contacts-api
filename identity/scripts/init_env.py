"""Create local development credentials without printing them or overwriting .env."""
import os
from pathlib import Path
import secrets

root = Path(__file__).resolve().parents[2]
path = root / '.env'
values = {'KC_BOOTSTRAP_ADMIN_USERNAME': 'bootstrap-admin'}
for name in ('KC_BOOTSTRAP_ADMIN_PASSWORD', 'CONTACTS_USER_PASSWORD',
             'CONTACTS_ADMIN_PASSWORD', 'CONTACTS_REPORTER_SECRET'):
    values[name] = secrets.token_urlsafe(32)
try:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
except FileExistsError:
    raise SystemExit('.env already exists; keep it and add any missing identity values manually.')
with os.fdopen(descriptor, 'w') as file:
    file.writelines(f'{key}={value}\n' for key, value in values.items())
print('Created .env with local credentials. Keep this file out of version control.')
