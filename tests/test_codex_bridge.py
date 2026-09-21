import importlib.util,json,socket,sys,threading,time,unittest
from pathlib import Path
BASE=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('bridge',BASE/'codex-bridge/bridge.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class BridgeTests(unittest.TestCase):
    def session(self,idle=False,goal=False):
        a,b=socket.socketpair();a.settimeout(5)
        request=json.loads((BASE/'build/tool-schema.json').read_text());request.update(op='start',goal='test',goalMode=goal)
        command=[sys.executable,str(BASE/'tests/fake_codex_server.py')]+(['idle'] if idle else [])+(['goal'] if goal else [])
        session=m.Session(b,command=command)
        def run():
            try:session.run(request)
            except (EOFError,BrokenPipeError,ConnectionResetError):pass
            finally:b.close()
        worker=threading.Thread(target=run);worker.start()
        return a,session,worker
    def test_real_protocol_tool_round_trip(self):
        client,session,worker=self.session()
        try:
            while True:
                event=m.receive(client)
                if event['event']=='tool':
                    self.assertEqual(event['name'],'am')
                    client.sendall(m.encode({'event':'result','id':event['id'],'result':{'ok':True,'output':'Activity manager'}}))
                elif event['event']=='done':
                    self.assertEqual(event['text'],'verified');break
        finally:client.close();worker.join(5)
        self.assertFalse(worker.is_alive());self.assertIsNotNone(session.proc.poll())
    def test_disconnect_stops_subprocess(self):
        client,session,worker=self.session(idle=True)
        while m.receive(client)['event']!='status':pass
        client.close();worker.join(5)
        self.assertFalse(worker.is_alive());self.assertIsNotNone(session.proc.poll())
    def test_goal_continues_text_and_more_than_25_tools(self):
        client,session,worker=self.session(goal=True);calls=0
        try:
            while True:
                event=m.receive(client)
                if event['event']=='tool':
                    calls+=1
                    result={'ok':True,'output':'Activity manager'}
                    if event['name']=='complete_task':result.update(goalCompleted=True,summary='done')
                    client.sendall(m.encode({'event':'result','id':event['id'],'result':result}))
                elif event['event']=='done':
                    self.assertEqual(event['text'],'Goal 已完成：done');break
        finally:client.close();worker.join(5)
        self.assertGreater(calls,25);self.assertFalse(worker.is_alive())
    def test_reasoning_capability_validation(self):
        supported={'supportedReasoningEfforts':[{'reasoningEffort':v} for v in ['low','medium','high']]}
        self.assertEqual(m.resolve_effort('enabled',supported),'medium')
        self.assertEqual(m.resolve_effort('low',supported),'low')
        self.assertIsNone(m.resolve_effort('auto',supported))
        with self.assertRaises(ValueError):m.resolve_effort('disabled',supported)
        self.assertEqual(m.resolve_effort('disabled',{'supportedReasoningEfforts':[{'reasoningEffort':'none'}]}),'none')
    def test_fragmented_frame_and_unicode(self):
        a,b=socket.socketpair();value={'text':'中文🌏'};data=m.encode(value)
        def send():
            for byte in data:b.sendall(bytes([byte]))
            b.close()
        t=threading.Thread(target=send);t.start();self.assertEqual(m.receive(a),value);a.close();t.join()
    def test_bad_length_rejected(self):
        a,b=socket.socketpair();b.sendall(b'\x7f\xff\xff\xff')
        with self.assertRaises(ValueError):m.receive(a)
        a.close();b.close()
    def test_search_dynamic_tool_allowed(self):
        schema={'type':'object','properties':{'query':{'type':'string'}},'required':['query']}
        tool={'function':{'name':'web_search','description':'Search','parameters':schema}}
        self.assertEqual(m.dynamic_tools([tool])[0]['name'],'web_search')
        self.assertEqual(m.dynamic_tools([tool])[0]['inputSchema'],schema)
    def test_unknown_tool_rejected(self):
        with self.assertRaises(ValueError):m.dynamic_tools([{'function':{'name':'shell'}}])
if __name__=='__main__':unittest.main()
