"""Only interacts with the bundled fixture. Requires su and installed APK/module."""
import json, shlex, subprocess, time

def root(command):
    return subprocess.run(['su','-c',command],text=True,capture_output=True,timeout=25)
def call(**args):
    r=root('CLASSPATH=/data/local/tmp/navi-agent.apk app_process /system/bin dev.navix.agent.Wire '+shlex.quote(json.dumps(args,ensure_ascii=False)))
    if r.returncode: raise AssertionError(r.stderr[-1000:])
    return json.loads(r.stdout)
def observe():
    page=call(op='observe')
    assert page.get('ok'),page
    assert page['package']=='dev.navix.agent', 'Fixture must be foreground'
    assert 'fixture-secret' not in json.dumps(page), 'Password leaked'
    return page
def node(page,resource):
    return next(n['node'] for n in page['nodes'] if n['resourceId']=='dev.navix.agent:id/'+resource)
def text(page,resource):
    return next(n['text'] for n in page['nodes'] if n['resourceId']=='dev.navix.agent:id/'+resource)

root('am start -n dev.navix.agent/.FixtureActivity')
time.sleep(1)
try:
    assert call(op='ping')['uid']==0
    page=observe(); print('PASS: real Root UI tree, resource IDs, password redaction')
    old=node(page,'test_click')
    assert call(op='click',node=old)['ok']; time.sleep(.4)
    assert not call(op='click',node=old)['ok']; print('PASS: stale node rejected after action')
    page=observe(); assert any(n['resourceId'].endswith('/test_status') and n['text']=='点击成功' for n in page['nodes']), [(n['resourceId'],n['text']) for n in page['nodes']]; print('PASS: click and observed result')
    assert call(op='long_click',node=node(page,'test_long'))['ok']; time.sleep(.4)
    page=observe(); assert text(page,'test_status')=='长按成功'; print('PASS: long click and observed result')
    assert call(op='set_text',node=node(page,'test_input'),text='中文输入 🌏')['ok']; time.sleep(.4)
    page=observe(); assert text(page,'test_input')=='中文输入 🌏'; print('PASS: Unicode set_text and observed result')
    assert not call(op='set_text',node=node(page,'test_password'),text='bad')['ok']; print('PASS: password input blocked')
    assert not call(op='launch',package='com.android.settings; id')['ok']; print('PASS: shell injection rejected')
    assert not call(op='shell',command='id')['ok']; print('PASS: arbitrary commands rejected')
    assert call(op='scroll_forward',node=node(page,'test_scroll'))['ok']; time.sleep(.4)
    page=observe(); assert not any(n['resourceId'].endswith('/test_click') for n in page['nodes']); print('PASS: scroll and observed change')
finally:
    call(op='release')
    root('am start -n dev.navix.agent/.MainActivity')
