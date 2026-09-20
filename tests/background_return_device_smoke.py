"""Opt-in return-card test: own fixture only, requires no existing virtual display."""
import json, subprocess, shlex, time, re

def root(cmd):
 p=subprocess.run(['su','-c',cmd],capture_output=True,text=True,timeout=30)
 assert p.returncode==0,p.stderr
 return p.stdout

def call(**q):
 r=json.loads(root('CLASSPATH=/data/local/tmp/navi-agent.apk app_process /system/bin dev.navix.agent.Wire '+shlex.quote(json.dumps(q))))
 assert r.get('ok'),r
 return r

assert call(op='background_status')['display_id']<0
primary_density=int(re.findall(r'\d+',root('wm density'))[-1])
created=[]
try:
 d=call(op='background_open')['display_id']
 call(op='start_intent',component='dev.navix.agent/.FixtureActivity',flags='0x18000000',display_id=d)
 time.sleep(1)
 t=call(op='background_status')['tasks'][0];created.append(t['task_id'])
 root('am start -n dev.navix.agent/.MainActivity -f 0x10020000')
 time.sleep(3)
 recent=root('dumpsys activity recents')
 cards=[part for part in recent.split('* Recent #') if 'dev.navix.agent/.BackgroundTaskActivity' in part and 'droidpilot://background/'+str(t['task_id']) in part]
 assert len(cards)==1, 'Expected exactly one proxy card'
 invalid=json.loads(root('CLASSPATH=/data/local/tmp/navi-agent.apk app_process /system/bin dev.navix.agent.Wire '+shlex.quote(json.dumps(dict(op='background_restore',task_id=t['task_id'],component='invalid/.Activity')))))
 assert not invalid.get('ok'), 'Restore accepted a mismatching task identity'
 print('PASS: one primary recents return card created for fixture task',t['task_id'])
 # Starting the matching document exercises the same non-exported Activity used by Recents.
 result=root('am start -W -n dev.navix.agent/.BackgroundTaskActivity -a android.intent.action.VIEW -d droidpilot://background/'+str(t['task_id'])+' -f 0x10080000 --ei task_id '+str(t['task_id'])+' --es component dev.navix.agent/.FixtureActivity')
 assert 'Error' not in result,result
 time.sleep(3)
 assert not call(op='background_status')['tasks'], 'Task was not restored'
 stacks=root('cmd activity stack list')
 block=next(b for b in stacks.split('RootTask ') if 'taskId='+str(t['task_id'])+':' in b)
 assert 'displayId=0' in block and str(primary_density)+'dpi' in block, 'Wrong restored configuration'
 assert 'dev.navix.agent/.BackgroundTaskActivity' not in root('dumpsys activity recents'), 'Proxy was not cleaned up'
 print('PASS: return entry restored same task to display 0 and removed its proxy')
 # Repeat for bulk close migration.
 call(op='switch_app',package='dev.navix.agent',display_id=d,launch_if_missing=False)
 # switch_app may pick MainActivity; do not assume which own activity it moved.
 tasks=call(op='background_status')['tasks'];assert tasks
 call(op='background_close')
 assert call(op='background_status')['display_id']<0
 print('PASS: close migrates remaining own task before releasing display')
finally:
 for task in created:
  root('cmd activity stack remove '+str(task))
 call(op='background_close')
 call(op='release')
