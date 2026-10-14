"""Redact local authentication secrets from Playwright traces before artifact upload."""
import json
from pathlib import Path
import re
import sys
import zipfile

SECRET = {'authorization', 'cookie', 'set-cookie', 'password', 'access_token', 'refresh_token', 'id_token',
          'code_verifier', 'client_secret', 'session_code'}

def text(value):
    value = re.sub(r'\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b', '[REDACTED_JWT]', value)
    value = re.sub(r'LocalDevOnly![A-Za-z0-9._-]+', '[REDACTED_PASSWORD]', value)
    return re.sub(r'(?i)\b(password|code_verifier|client_secret|session_code|code|access_token|refresh_token|id_token)=[^&\s"\\]+', r'\1=[REDACTED]', value)

def clean(value):
    if isinstance(value, dict):
        if str(value.get('name', '')).lower() in SECRET:
            return {k: '[REDACTED]' if k == 'value' else clean(v) for k, v in value.items()}
        return {k: ([] if k.lower() == 'cookies' else '[REDACTED]') if k.lower() in SECRET | {'cookies'} else clean(v) for k, v in value.items()}
    if isinstance(value, list): return [clean(v) for v in value]
    return text(value) if isinstance(value, str) else value

if sys.argv[1:] == ['--self-check']:
    example = {'headers': [{'name': 'Authorization', 'value': 'Bearer secret'}], 'access_token': 'secret',
               'postData': 'password=LocalDevOnly!maker&code_verifier=secret', 'org_id': 'org-one'}
    result = json.dumps(clean(example))
    assert 'secret' not in result and 'LocalDevOnly!' not in result and 'org-one' in result
    print('PASS trace secret redaction preserves organisation evidence')
    raise SystemExit()

for path in Path(sys.argv[1]).rglob('*.zip'):
    temporary = path.with_suffix('.redacted.zip')
    with zipfile.ZipFile(path) as source, zipfile.ZipFile(temporary, 'w', compression=zipfile.ZIP_DEFLATED) as target:
        for item in source.infolist():
            data = source.read(item.filename)
            try:
                original = data.decode('utf-8')
                lines = []
                for line in original.splitlines():
                    try: lines.append(json.dumps(clean(json.loads(line)), ensure_ascii=False))
                    except json.JSONDecodeError: lines.append(text(line))
                data = '\n'.join(lines).encode('utf-8')
            except UnicodeDecodeError: pass
            target.writestr(item.filename, data)
    temporary.replace(path)
    print('Redacted authentication fields in trace: ' + path.name)
