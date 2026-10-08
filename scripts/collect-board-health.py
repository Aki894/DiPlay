#!/usr/bin/env python3
"""Read-only soak capture over the box Wi-Fi; never restart or replay commands."""
import argparse
import getpass
import json
import os
import time
import urllib.request
from pathlib import Path


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('url',help='Example: http://192.168.49.1:8765')
    parser.add_argument('--minutes',type=float,default=60)
    parser.add_argument('--interval',type=float,default=15)
    parser.add_argument('--output',type=Path,default=Path('wukong-soak.jsonl'))
    args=parser.parse_args()
    if not 0<args.minutes<=1440 or not 2<=args.interval<=300:parser.error('Invalid duration/interval')
    token=getpass.getpass('Management token: ').strip()
    args.output.parent.mkdir(parents=True,exist_ok=True)
    fd=os.open(args.output,os.O_WRONLY|os.O_CREAT|os.O_APPEND,0o600)
    end=time.monotonic()+args.minutes*60
    active=failures=0
    with os.fdopen(fd,'a',encoding='utf-8') as output:
        while time.monotonic()<end:
            started=time.monotonic()
            try:
                request=urllib.request.Request(args.url.rstrip('/')+'/api/v1/status',headers={'Authorization':'Bearer '+token})
                with urllib.request.urlopen(request,timeout=8) as response:status=json.load(response)
                # Maintenance passwords never belong in diagnostic captures.
                (status.get('management',{}).get('maintenanceHotspot') or {}).pop('passphrase',None)
                status['captureTime']=time.time()
                output.write(json.dumps(status,ensure_ascii=False)+'\n');output.flush()
                if status.get('state') in ('WirelessActive','active'):active+=1
                print(status.get('state'),status.get('uptimeMs'),'ms',status.get('pssKiB'),'KiB',status.get('videoFrames'),'frames')
            except Exception as error:
                failures+=1
                output.write(json.dumps({'captureTime':time.time(),'captureError':str(error)})+'\n');output.flush()
                print('Capture failed:',error)
            time.sleep(min(max(0,args.interval-(time.monotonic()-started)),max(0,end-time.monotonic())))
    print(f'Capture saved: {args.output}; active samples={active}, request failures={failures}. Review transitions and frame progression; these counts alone are not a pass.')

if __name__=='__main__':main()
