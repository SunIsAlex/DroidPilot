"""Verifies direct app/Intent/am operations via the installed root bridge."""
import json, shlex, subprocess, time

def root(command):
    return subprocess.run(['su','-c',command],text=True,capture_output=True,timeout=25)
def call(**args):
    r=root('CLASSPATH=/data/local/tmp/navi-agent.apk app_process /system/bin dev.navix.agent.Wire '+shlex.quote(json.dumps(args,ensure_ascii=False)))
    if r.returncode: raise AssertionError(r.stderr[-1000:])
    return json.loads(r.stdout)
def require_ok(result):
    assert result.get('ok'),result
    return result
try:
    require_ok(call(op='release'))
    launched=require_ok(call(op='launch_app',package='com.android.settings'))
    assert launched['exitCode']==0 and 'com.android.settings' in launched['output'],launched
    assert not call(op='ping')['connected'], 'Direct launch must not require UI Automation'
    assert 'Status: ok' in launched['output'],launched
    print('PASS: direct launch_app -> Settings accepted by Android; no UI Automation prerequisite')
    opened=require_ok(call(op='start_intent',action='android.settings.DISPLAY_SETTINGS'))
    assert 'Status: ok' in opened['output'],opened
    print('PASS: settings Intent activity launched')
    fixture=require_ok(call(op='start_intent',component='dev.navix.agent/.FixtureActivity',action='android.intent.action.VIEW',data='navi-test://item?q=中文&x=2',flags='0x10000000',categories=['android.intent.category.DEFAULT'],extras=[{'key':'message','type':'string','value':'中文 ; $(id) `id` & spaces'},{'key':'enabled','type':'boolean','value':'true'},{'key':'count','type':'int','value':'7'}]))
    assert 'dev.navix.agent' in fixture['output'],fixture
    print('PASS: explicit Intent with URI, flags, categories and typed extras accepted by Android')
    help_result=require_ok(call(op='am',args=['help']))
    assert 'Activity manager' in help_result['output'] and len(help_result['output'].encode())<=16000
    print('PASS: raw am help, bounded output')
    failed=call(op='am',args=['start','-n','dev.navix.doesnotexist/.Missing'])
    assert not failed['ok'] and failed.get('output'),failed
    print('PASS: unresolved activity returns command failure and diagnostics')
    failed=call(op='am',args=['navi-nonexistent-command'])
    assert not failed['ok'],failed
    print('PASS: unknown am subcommand failure')
finally:
    call(op='release')
    root('am start -n dev.navix.agent/.MainActivity')
