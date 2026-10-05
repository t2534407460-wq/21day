"""Interactive smoke checks against a disposable Android emulator only."""
import os, re, subprocess, time, xml.etree.ElementTree as ET
from pathlib import Path

SERIAL = os.environ.get('RHYTHM_TEST_DEVICE', 'emulator-5556')
assert SERIAL.startswith('emulator-'), 'Only run this script on a disposable emulator.'
OUT = Path(__file__).resolve().parents[1] / 'artifacts'
OUT.mkdir(exist_ok=True)

def adb(*args):
    return subprocess.check_output(['adb', '-s', SERIAL, *args], stderr=subprocess.STDOUT)

def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/rhythm-smoke.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/rhythm-smoke.xml')).iter('node')

def tap(text, scroll=False):
    for _ in range(9 if scroll else 2):
        for node in nodes():
            if node.get('text') == text or node.get('content-desc') == text:
                x1,y1,x2,y2 = map(int,re.findall(r'\d+', node.get('bounds')))
                adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
                time.sleep(.8)
                return
        if scroll: adb('shell','input','swipe','540','1800','540','750','350')
    raise AssertionError(f'Missing UI element: {text}')

def screenshot(name):
    (OUT / f'{os.environ.get("RHYTHM_SCREENSHOT_PREFIX", "")}{name}.png').write_bytes(adb('exec-out','screencap','-p'))

if __name__ == '__main__':
    adb('shell','am','start','-n','com.twentyone.rhythm/.MainActivity')
    time.sleep(1)
    tap('设置我的 21 天')
    screenshot('plan-editor')
    tap('保存计划')
    screenshot('today')
    tap('开始睡前打卡',scroll=True)
    tap('也可以点选')
    tap('有点困')
    tap('也可以点选')
    tap('平静')
    tap('也可以点选')
    tap('手机')
    screenshot('record-summary')
    tap('完成睡前打卡',scroll=True)
    tap('知道了')
    tap('21 天')
    screenshot('plan')
    tap('变化')
    screenshot('trends')
    tap('设置')
    screenshot('settings')
    tap('起床验证')
    tap('我的起床二维码',scroll=True)
    screenshot('qr-code')
    tap('关闭')
    tap('试一次起床验证',scroll=True)
    screenshot('wake')
    tap('开始起床 · 安静验证 3 分钟')
    screenshot('wake-walk')
    tap('结束试用',scroll=True)
    tap('回到今天')
    print('PASS: plan creation, logging, calendar, trends, settings, QR display, alarm start, quiet walk, emergency stop.')
