#!/usr/bin/env python3
"""Guard the generic CarLife fork against reintroducing vehicle-specific integrations."""
from pathlib import Path
import re
import sys
root = Path(__file__).resolve().parent.parent
errors = []
for module in ('common', 'shared', 'mobile'):
    for path in (root / module / 'src').rglob('*'):
        if not path.is_file():
            continue
        if path.suffix in ('.kt', '.java'):
            text = path.read_text()
            if re.search(r'\b(?:import|package)\s+com\.shilapi\.xcertplay\.(?:hud|adb)\b', text):
                errors.append(f'vendor module reference: {path.relative_to(root)}')
        if path.name == 'AndroidManifest.xml':
            text = path.read_text()
            if re.search(r'com\.byd\.|com\.ts\.car\.someip|SYSTEM_ALERT_WINDOW|PACKAGE_USAGE_STATS|WRITE_SETTINGS', text):
                errors.append(f'vendor service/permission: {path.relative_to(root)}')
for folder in ('shared/src/main/assets/byd-hud-icons', 'shared/src/main/java/com/shilapi/xcertplay/hud',
               'shared/src/main/java/com/shilapi/xcertplay/adb'):
    if (root / folder).exists():
        errors.append(f'vendor folder remains: {folder}')
if errors:
    print('\n'.join(errors), file=sys.stderr)
    sys.exit(1)
print('Generic receiver check passed: no vehicle output, ADB module or vendor permissions.')
