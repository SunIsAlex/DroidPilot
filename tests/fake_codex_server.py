import json,sys
turns=0;count=0
mode='goal' if 'goal' in sys.argv else 'normal'
def send(event):print(json.dumps(event),flush=True)
def tool():
    return {'id':'tool-'+str(count),'method':'item/tool/call','params':{'tool':'complete_task' if mode=='goal' and count>=27 else 'am','arguments':{'summary':'done','evidence':'mock result'} if mode=='goal' and count>=27 else {'args':['help']}}}
for line in sys.stdin:
    q=json.loads(line); method=q.get('method')
    if method=='initialize': r={'id':q['id'],'result':{}}
    elif method=='initialized': continue
    elif method=='thread/start': r={'id':q['id'],'result':{'thread':{'id':'test-thread'},'model':'fake'}}
    elif method=='model/list':r={'id':q['id'],'result':{'data':[{'id':'fake','model':'fake','supportedReasoningEfforts':[{'reasoningEffort':e} for e in ['none','low','medium']]}]}}
    elif method=='turn/start':
        turns+=1
        send({'id':q['id'],'result':{'turn':{'id':'test-turn-'+str(turns)}}})
        if 'idle' in sys.argv:continue
        if mode=='goal' and turns==1:
            send({'method':'item/completed','params':{'item':{'type':'agentMessage','text':'still working'}}})
            r={'method':'turn/completed','params':{'turn':{'status':'completed'}}}
        else:r=tool()
    elif 'result' in q:
        assert q['id']=='tool-'+str(count) and q['result']['success']
        assert q['result']['contentItems'][0]['type']=='inputText'
        if mode=='goal':
            count+=1
            if count>27:continue
            r=tool()
        else:
            send({'method':'item/completed','params':{'item':{'type':'agentMessage','text':'verified'}}})
            r={'method':'turn/completed','params':{'turn':{'status':'completed'}}}
    elif method=='turn/interrupt':break
    else:raise RuntimeError(method)
    send(r)
