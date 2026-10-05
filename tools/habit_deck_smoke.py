"""0.8.0 visual checks on the disposable emulator only; all records/reports are synthetic."""
import json, os, re, time, uuid, struct, subprocess, xml.etree.ElementTree as ET
from datetime import date, timedelta, datetime, timezone
import ui_smoke as u

pkg='com.twentyone.rhythm'
today=date.fromisoformat(u.adb('shell','date','+%Y-%m-%d').decode().strip())
start=today-timedelta(days=5)
plans=[];entries=[]
for name,kind,mode,unit,target,smoking in [('阅读','TIMER','AT_LEAST','分钟',20,False),('运动','TIMER','AT_LEAST','分钟',30,False),('戒烟','DAILY','AT_MOST','支',0,True),('控烟','COUNT','AT_MOST','支',10,True)]:
    hid=str(uuid.uuid4())
    plans.append(dict(id=hid,name=name,start=str(start),input=kind,mode=mode,unit=unit,smoking=smoking,archivedOn=None,rules=[{'from':str(start),'days':list(range(1,8)),'target':target,'reminder':1260}]))
    for i in range(5):
        if i==2:continue
        entries.append(dict(habitId=hid,date=str(start+timedelta(days=i)),value=20 if kind=='TIMER' else 0 if kind=='DAILY' else i+1,note='合成测试记录',recordedAt=int(time.time()*1000)))
past=dict(plans[0],id=str(uuid.uuid4()),name='阅读 · 上一轮',start=str(today-timedelta(days=21)),archivedOn=str(today-timedelta(days=1)),rules=[{'from':str(today-timedelta(days=21)),'days':list(range(1,8)),'target':20,'reminder':1260}])
plans.append(past)
for i in range(21):entries.append(dict(habitId=past['id'],date=str(today-timedelta(days=21-i)),value=20 if i%3 else 10,note='',recordedAt=int(time.time()*1000)))
root=ET.Element('map');ET.SubElement(root,'string',name='habits').text=json.dumps(dict(plans=plans,entries=entries),ensure_ascii=False)
fixture=u.OUT/'0.8.0-ui-fixture.xml';ET.ElementTree(root).write(fixture,encoding='utf-8',xml_declaration=True)
reports=ET.Element('map');ET.SubElement(reports,'string',name='cycle:'+past['id']).text=json.dumps(dict(status='done',facts='合成测试事实',answer='本期事实\n这轮共有21个计划日，已记录21天，其中14天达到20分钟目标。\n\n趋势观察\n本次演示数据里，记录持续保留。\n\n下一周期\n先保持每天20分钟，把阅读安排在方便开始的固定时段。\n\n这是用于界面检查的合成报告，不是真实AI结果。',at=int(time.time()*1000)),ensure_ascii=False)
reportfile=u.OUT/'0.8.0-ui-reports.xml';ET.ElementTree(reports).write(reportfile,encoding='utf-8',xml_declaration=True)
u.adb('shell','am','force-stop',pkg)
for source,target in [(fixture,'rhythm.xml'),(reportfile,'habit_reviews.xml')]:
    u.adb('push',str(source),'/data/local/tmp/'+target);u.adb('shell','run-as',pkg,'cp','/data/local/tmp/'+target,'shared_prefs/'+target)
u.adb('shell','wm','user-rotation','lock','0');u.adb('shell','settings','put','system','font_scale','1.0')
u.adb('shell','am','start','-n',pkg+'/.MainActivity');time.sleep(1)
list(u.nodes())
u.screenshot('home-deck')

def point(label):
    for n in u.nodes():
        if n.get('text')==label or n.get('content-desc')==label:
            x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
            return (x1+x2)//2,(y1+y2)//2
    raise AssertionError(label)

def hold(label):
    x,y=point(label);u.adb('shell','input','swipe',str(x),str(y),str(x),str(y),'1000');time.sleep(.15)

hold('长按开始');u.screenshot('timer-running')
u.adb('shell','am','force-stop',pkg);u.adb('shell','am','start','-n',pkg+'/.MainActivity');time.sleep(1)
assert point('长按结束');hold('长按结束');u.screenshot('timer-finished')
u.tap('下一张习惯卡片');u.tap('下一张习惯卡片');u.screenshot('quit-daily');hold('长按确认零支');u.screenshot('quit-confirmed')
u.tap('下一张习惯卡片');u.screenshot('smoking-count')
x,y=point('长按 +1 支')
process=subprocess.Popen(['adb','-s',u.SERIAL,'shell','input','swipe',str(x),str(y),str(x),str(y),'1300'],stdout=subprocess.DEVNULL)
time.sleep(.9);u.screenshot('smoke-feedback');process.wait()
u.screenshot('count-recorded')
# Drag the paper itself while taking an intermediate frame.
process=subprocess.Popen(['adb','-s',u.SERIAL,'shell','input','swipe',str(x),str(y-220),str(x-400),str(y-220),'1800'],stdout=subprocess.DEVNULL)
time.sleep(.9);u.screenshot('paper-mid-swipe');process.wait();time.sleep(.5)
u.tap('设置');u.screenshot('settings-categories');u.tap('AI 助手');u.screenshot('ai-settings')
u.adb('shell','input','keyevent','4');u.tap('习惯');u.tap('已结束 / 归档 1',scroll=True);u.tap('查看阅读 · 上一轮',scroll=True);u.tap('查看该习惯的 AI 复盘');u.screenshot('cycle-review')
u.tap('每周复盘');u.screenshot('weekly-review');u.adb('shell','input','keyevent','4')
u.tap('今天');u.adb('shell','settings','put','system','font_scale','1.3');time.sleep(1);u.screenshot('deck-large-font')
u.adb('shell','wm','user-rotation','lock','1');time.sleep(2);u.screenshot('deck-landscape')
blob=(u.OUT/(os.environ.get('RHYTHM_SCREENSHOT_PREFIX','')+'deck-landscape.png')).read_bytes()
w,h=struct.unpack('>II',blob[16:24]);assert w>h
u.adb('shell','wm','user-rotation','lock','0');u.adb('shell','settings','put','system','font_scale','1.0')
print('PASS: cards, timer restart/end, daily abstinence, count/smoke, paper swipe, categories, per-habit report tabs, font and actual landscape screenshots.')
