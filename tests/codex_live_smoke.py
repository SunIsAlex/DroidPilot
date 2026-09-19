"""One real Codex round trip. The only executed phone command is read-only am help."""
import importlib.util, json, shlex, socket, subprocess, threading
from pathlib import Path
BASE=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('bridge',BASE/'codex-bridge/bridge.py'); bridge=importlib.util.module_from_spec(spec);spec.loader.exec_module(bridge)
request=json.loads((BASE/'build/tool-schema.json').read_text())
request.update(op='start',goal='这是接入测试。仅调用一次 am 工具，args 为 ["help"]；确认结果包含 Activity manager 后回复“Codex 手机工具连接成功”。不要读屏或执行任何其它操作。')
a,b=socket.socketpair(); a.settimeout(180)
def server():
    try: bridge.Session(b).run(request)
    except Exception as e: b.sendall(bridge.encode({'event':'error','message':str(e)}))
    finally:b.close()
t=threading.Thread(target=server);t.start();called=False
try:
    while True:
        event=bridge.receive(a)
        if event['event']=='tool':
            assert event['name']=='am' and event['arguments']=={'args':['help']},event
            q=json.dumps({'op':'am','args':['help']})
            result=subprocess.run(['su','-c','CLASSPATH=/data/local/tmp/navi-agent.apk app_process /system/bin dev.navix.agent.Wire '+shlex.quote(q)],capture_output=True,text=True,timeout=20)
            reply=json.loads(result.stdout); assert reply['ok'] and 'Activity manager' in reply['output']
            a.sendall(bridge.encode({'event':'result','id':event['id'],'result':reply}));called=True
            print('PASS: real Codex requested am(help); real Root result returned',flush=True)
        elif event['event']=='done':
            assert called,event
            print('PASS: Codex completed with tool result:',event['text'],flush=True);break
        elif event['event']=='error':raise AssertionError(event['message'])
        elif event['event']=='status':print('STATUS:',event['text'],flush=True)
finally:
    a.close();t.join(timeout=10)
