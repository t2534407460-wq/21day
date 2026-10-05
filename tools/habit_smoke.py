"""Habit UI checks on the disposable emulator; synthetic fixtures only."""
import json, os, re, time, uuid, struct, xml.etree.ElementTree as ET
from datetime import date, timedelta
from pathlib import Path
import ui_smoke as u

pkg='com.twentyone.rhythm'
today=date.fromisoformat(u.adb('shell','date','+%Y-%m-%d').decode().strip())
start=today-timedelta(days=10)
habits=[];entries=[]
for name,mode,unit,target,days in [('阅读','AT_LEAST','分钟',20,list(range(1,8))),('运动','AT_LEAST','分钟',30,[1,3,5]),('戒烟','AT_MOST','支',0,list(range(1,8)))]:
    hid=str(uuid.uuid4());habits.append(dict(id=hid,name=name,start=str(start),mode=mode,unit=unit,archivedOn=None,rules=[dict(from_=str(start),days=days,target=target,reminder=1260)]))
    habits[-1]['rules'][0]['from']=habits[-1]['rules'][0].pop('from_')
    for i in range(10):
        d=start+timedelta(days=i)
        if d.isoweekday() not in days or i in [2,6]:continue
        value=([20,10,30,0,25][i%5] if mode=='AT_LEAST' else [3,2,0,1,0][i%5])
        entries.append(dict(habitId=hid,date=str(d),value=value,note='测试：回看当日原因',recordedAt=1790953500000+i))
root=ET.Element('map');ET.SubElement(root,'string',name='habits').text=json.dumps(dict(plans=habits,entries=entries),ensure_ascii=False)
fixture=u.OUT/'0.7.0-habit-ui-fixture.xml';ET.ElementTree(root).write(fixture,encoding='utf-8',xml_declaration=True)
u.adb('shell','am','force-stop',pkg);u.adb('push',str(fixture),'/data/local/tmp/habit-ui.xml')
u.adb('shell','run-as',pkg,'cp','/data/local/tmp/habit-ui.xml','shared_prefs/rhythm.xml')
u.adb('shell','settings','put','system','font_scale','1.0')
u.adb('shell','settings','put','system','accelerometer_rotation','0')
u.adb('shell','settings','put','system','user_rotation','0')
u.adb('shell','am','start','-n',pkg+'/.MainActivity');time.sleep(1)
u.tap('习惯');u.screenshot('habit-list')
u.tap('查看阅读',scroll=True);u.screenshot('habit-detail-top')
u.tap('每周趋势',scroll=True);u.screenshot('habit-trend')
u.adb('shell','input','keyevent','4');u.tap('查看阅读',scroll=True)
u.tap('调整计划');u.screenshot('habit-edit')
editors=[n for n in u.nodes() if n.get('class')=='android.widget.EditText']
assert len(editors)>=2
def set_field(node,text):
    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
    u.adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
    u.adb('shell','input','keyevent','KEYCODE_MOVE_END')
    for _ in range(10):u.adb('shell','input','keyevent','KEYCODE_DEL')
    u.adb('shell','input','text',text)
set_field(editors[1],'15')
if b'mInputShown=true' in u.adb('shell','dumpsys','input_method'):u.adb('shell','input','keyevent','4')
u.tap('保存习惯');time.sleep(.5)
def data():
    nodes=ET.fromstring(u.adb('shell','run-as',pkg,'cat','shared_prefs/rhythm.xml'))
    return json.loads(next(n.text for n in nodes if n.get('name')=='habits'))
read=next(h for h in data()['plans'] if h['name']=='阅读')
assert read['rules'][0]['target']==20 and read['rules'][-1]['target']==15
assert read['rules'][-1]['from']==str(today+timedelta(days=1))
u.tap('归档此计划',scroll=True);u.tap('确认归档');time.sleep(.5)
assert next(h for h in data()['plans'] if h['name']=='阅读')['archivedOn']==str(today)
u.adb('shell','input','keyevent','4');u.tap('已结束 / 归档 1');u.screenshot('habit-archive')
u.tap('查看阅读',scroll=True);u.tap('开始新一轮',scroll=True);u.screenshot('habit-renew');u.tap('取消')
u.adb('shell','input','keyevent','4')
u.adb('shell','wm','user-rotation','lock','1');time.sleep(2)
u.adb('shell','input','swipe','1200','720','1200','300','350');u.tap('添加习惯')
u.screenshot('habit-landscape-editor')
image=(u.OUT/(os.environ.get('RHYTHM_SCREENSHOT_PREFIX','')+'habit-landscape-editor.png')).read_bytes()
width,height=struct.unpack('>II',image[16:24]);assert width>height
u.tap('取消')
u.adb('shell','wm','user-rotation','lock','0')
u.adb('shell','settings','put','system','font_scale','1.3');time.sleep(1)
u.tap('添加习惯');u.screenshot('habit-large-font-editor');u.tap('取消')
u.adb('shell','settings','put','system','font_scale','1.0')
print('PASS: three habits, quantitative trends, edit effective tomorrow, archive, renew dialog, landscape and large font controls.')
