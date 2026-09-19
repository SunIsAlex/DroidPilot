"""Opt-in device test: isolated fixture only; never runs a model or touches other apps."""
import json, re, shlex, subprocess, time


def root(command):
    result = subprocess.run(['su', '-c', command], text=True, capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError(result.stderr[-1000:])
    return result.stdout


def call(**request):
    return json.loads(root('CLASSPATH=/data/local/tmp/navi-agent.apk app_process /system/bin dev.navix.agent.Wire '
                           + shlex.quote(json.dumps(request, ensure_ascii=False))))


def focused_display():
    return int(re.search(r'FocusedDisplayId: (\d+)', root('dumpsys input')).group(1))


def observe(display):
    page = call(op='observe', display_id=display)
    assert page.get('ok') and page.get('display_id') == display, page
    assert page['package'] == 'dev.navix.agent'
    assert 'fixture-secret' not in json.dumps(page)
    return page


def find(page, name):
    return next(n for n in page['nodes'] if n['resourceId'] == 'dev.navix.agent:id/' + name)


state = call(op='background_status')
assert state.get('ok') and state['display_id'] < 0, 'Close existing background display before this test'
before = focused_display()
assert before == 0, 'Primary display must initially own input focus'
display = call(op='background_open')['display_id']
try:
    assert display > 0
    launched = call(op='start_intent', component='dev.navix.agent/.FixtureActivity',
                    flags='0x18000000', display_id=display)
    assert launched.get('ok'), launched
    time.sleep(0.5)
    page = observe(display)
    assert call(op='click', node=find(page, 'test_click')['node'], display_id=display)['ok']
    page = observe(display)
    assert find(page, 'test_status')['text'] == '点击成功', page
    assert call(op='long_click', node=find(page, 'test_long')['node'], display_id=display)['ok']
    page = observe(display)
    assert find(page, 'test_status')['text'] == '长按成功', page
    assert call(op='set_text', node=find(page, 'test_input')['node'], text='后台独立输入 ✓', display_id=display)['ok']
    page = observe(display)
    assert find(page, 'test_input')['text'] == '后台独立输入 ✓', page
    assert not call(op='click', node=find(page, 'test_click')['node'], display_id=0)['ok'], 'Cross-display node accepted'
    assert not call(op='input_text', package='dev.navix.agent', text='must not input', display_id=display)['ok']
    assert not call(op='am', args=['start', '-a', 'android.settings.SETTINGS'], display_id=display)['ok']
    assert not call(op='observe', display_id=999999)['ok'], 'Invalid display fell back to primary'
    assert focused_display() == before, 'Virtual display stole primary focus'
    print('PASS: background observe/click/long-click/Unicode set_text; scope and IME/am guards; primary focus retained')
finally:
    state = call(op='background_status')
    for task in state.get('tasks', []):
        if task['component'] == 'dev.navix.agent/.FixtureActivity':
            root('cmd activity stack remove ' + str(int(task['task_id'])))
    call(op='background_close')
    call(op='release')
