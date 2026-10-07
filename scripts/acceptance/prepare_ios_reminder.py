#!/usr/bin/env python3
"""Prepare one reminder fixture in a freshly created, isolated Companion Acceptance simulator."""
import argparse
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import subprocess
import time


def simctl(*args):
    return subprocess.check_output(['xcrun', 'simctl', *args], text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device', required=True)
    parser.add_argument('--delay-seconds', type=int, default=180)
    args = parser.parse_args()
    if not 120 <= args.delay_seconds <= 240:
        parser.error('delay must leave enough time for test startup (120..240 seconds)')
    devices = json.loads(simctl('list', 'devices', '--json'))['devices']
    device = next((d for group in devices.values() for d in group if d['udid'] == args.device), None)
    if not device or not device['name'].startswith('Companion Acceptance') or device['state'] != 'Booted':
        parser.error('use a booted, dedicated Companion Acceptance simulator, never a personal device')
    subprocess.run(['xcrun', 'simctl', 'terminate', args.device, 'com.openai.companion.ios'], capture_output=True)
    container = Path(simctl('get_app_container', args.device, 'com.openai.companion.ios', 'data'))
    path = container / 'Library/Application Support/OpenAICompanion/companion.sqlite'
    if not path.is_file():
        parser.error('install and launch the app once to initialize its database')
    now = int(time.time())
    at = now + args.delay_seconds
    with closing(sqlite3.connect(path)) as db, db:
        if db.execute('PRAGMA user_version').fetchone()[0] != 1:
            parser.error('fixture requires the current application schema')
        if db.execute("SELECT count(*) FROM proactive_tasks WHERE scenario!='acceptance:scheduled'").fetchone()[0]:
            parser.error('use an isolated database without other tasks')
        db.execute('UPDATE proactive_settings SET enabled=0')
        db.execute('INSERT OR REPLACE INTO proactive_tasks '
                   '(scenario,title,instruction,memory_query,allowed_tools_json,required_tools_json,enabled,'
                   'local_minute,weekday_mask,lead_minutes,deadline_lead_minutes,timezone_offset_minutes,'
                   'one_shot_at,next_run_at,next_event_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
                   ('acceptance:scheduled', 'Acceptance scheduled notification', '提醒打开 App', '',
                    '[]', '[]', 1, 0, 0, 0, 0, 480, at, at, at, now))
    print(f'Isolated reminder prepared for epoch {at}; run the notification UI test now.')


if __name__ == '__main__':
    main()
