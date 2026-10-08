#!/usr/bin/env python3
"""Inspect the actual shrunk standalone board artifact, not just source manifests."""
import os
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

apk = Path(sys.argv[1])
sdk = Path(os.environ['ANDROID_HOME'])
analyzer = sdk / 'cmdline-tools/latest/bin/apkanalyzer'
manifest = subprocess.check_output([str(analyzer), 'manifest', 'print', str(apk)], text=True)
root = ET.fromstring(manifest)
android = '{http://schemas.android.com/apk/res/android}'
assert root.attrib['package'] == 'com.shihab.diplay.hudtest'
components = [x.attrib.get(android + 'name', '') for tag in ['activity', 'service', 'receiver'] for x in root.findall('application/' + tag)]
assert 'com.shilapi.xcertplay.board.BoardService' in components
# Tiramisu preview permission parsing can use a resource SDK newer than SDK_INT.
# Do not cap the declaration at 32: the root helper must be able to grant it.
permissions = {x.attrib[android + 'name']: x for x in root.findall('uses-permission')}
background_location = permissions['android.permission.ACCESS_BACKGROUND_LOCATION']
assert android + 'maxSdkVersion' not in background_location.attrib
board_service = next(x for x in root.findall('application/service')
                     if x.attrib.get(android + 'name') == 'com.shilapi.xcertplay.board.BoardService')
service_types = board_service.attrib[android + 'foregroundServiceType']
# apkanalyzer can render Android flag attributes as numeric values.
if 'location' in service_types.split('|'):
    has_location_type = True
else:
    has_location_type = bool(int(service_types, 0) & 0x8)  # ServiceInfo LOCATION
assert has_location_type, service_types
print('PASS: headless P2P location permission survives preview SDK parsing')

assert not any('CarPlayHostActivity' in x or 'MyCarAppService' in x for x in components)
assert not root.findall('.//category[@' + android + 'name="android.intent.category.LAUNCHER"]')
with zipfile.ZipFile(apk) as z:
    names = z.namelist()
    libs = [n for n in names if n.startswith('lib/') and n.endswith('.so')]
    assert libs and all(n.startswith('lib/armeabi-v7a/') for n in libs), libs
    assert all('assets/offline-mfi/' + n in names for n in ['identity.pk8', 'certificate.p7b'])
    assert 'assets/index.html' in names
    assert any(b'com/shilapi/xcertplay/board/BoardProvisioner' in z.read(n) for n in names if n.endswith('.dex'))
print('PASS: shrunk board APK, root entry point, service-only manifest, ARM32 libraries and required assets')
print('APK bytes:', apk.stat().st_size)
