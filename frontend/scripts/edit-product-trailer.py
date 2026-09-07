"""Edit captured product scenes into an original 75-second trailer; no application tests."""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import json
import os
import subprocess
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(os.environ.get('TOWN_TRAILER_EDIT_DIR', ROOT / 'artifacts/product-trailer'))
EDIT = OUT / 'edit'
EDIT.mkdir(exist_ok=True)
FF = '/opt/homebrew/bin/ffmpeg'
data = json.loads((OUT / 'edit-source.json').read_text())['clips']
marks = data['workflow']['marks']

def focus(box):
    if not box:
        return None
    x = max(0, min(1920 - 900, round(box['x'] + box['width']/2 - 450)))
    y = max(0, min(1080 - 506, round(box['y'] + box['height']/2 - 253)))
    return x // 2 * 2, y // 2 * 2, 900, 506

# Cut boundaries follow 96 BPM; nearest output frame keeps the complete film exactly 75s.
specs = [
    ('coffee', 'coffee.webm', .6, 0, 5, None),
    ('world', 'reveal.webm', .2, 5, 12.5, None),
    ('academy', 'academy.webm', .5, 12.5, 15, None),
    ('gym', 'gym.webm', .5, 15, 17.5, None),
    ('park', 'park.webm', .3, 17.5, 20, None),
    ('academy-detail', 'academy.webm', 2.5, 20, 21.25, (220, 100, 1000, 562)),
    ('coffee-beat', 'coffee.webm', 2.2, 21.25, 22.5, None),
    ('pet-tease', 'homecoming.webm', 6.7, 22.5, 23.75, None),
    ('neighbours', 'social.webm', .25, 23.75, 27.5, None),
    ('thought', 'workflow.mp4', max(.1,marks['reply']-.4), 27.5, 31.25, focus(data['aiBox'])),
    ('step', 'workflow.mp4', marks['step'], 31.25, 35, focus(data['stepBox'])),
    ('goal', 'workflow.mp4', marks['goal']+.3, 35, 36.25, focus(data['goalBox'])),
    ('start', 'workflow.mp4', marks['task']+.4, 36.25, 38.75, focus(data['taskBox'])),
    ('practice-first', 'study.webm', .8, 38.75, 43.75, None),
    ('rhythm', 'rhythm.mp4', .35, 43.75, 48.75, focus(data['rhythmBox'])),
    ('practice', 'park-companion.webm', .5, 48.75, 52.5, None),
    ('complete', 'completion.mp4', .35, 52.5, 57.5, focus(data['doneBox'])),
    ('home', 'homecoming.webm', 2.8, 57.5, 65, None),
    ('memory', 'memento.mp4', .6, 65, 70, focus(data['mementoBox'])),
    ('return', 'home-ending.webm', .5, 70, 75, None),
]

def run(args):
    result = subprocess.run(args, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(result.stderr[-3000:])

def segment(spec):
    name, file, start, beginning, end, crop = spec
    frames = round(end*30)-round(beginning*30)
    vf = []
    if crop:
        x,y,w,h = crop
        vf.append(f'crop={w}:{h}:{x}:{y}')
    vf += ['scale=1920:1080:flags=lanczos', 'setsar=1', 'fps=30', 'tpad=stop_mode=clone:stop_duration=1']
    if crop:
        vf.append('drawbox=x=1350:y=0:w=570:h=1080:color=0x171b17@0.23:t=fill')
    if name == 'coffee':
        vf.append('fade=t=in:st=0:d=0.45')
    if name == 'rhythm':
        vf.append('fade=t=in:st=0:d=0.15')
    if name == 'return':
        vf.append('drawbox=x=0:y=0:w=iw:h=ih:color=0x20160e@0.12:t=fill')
    output = EDIT / (name+'.mp4')
    run([FF,'-y','-loglevel','error','-ss',str(max(0,start)),'-i',str(OUT/file),'-vf',','.join(vf),'-frames:v',str(frames),'-an','-c:v','libx264','-preset','fast','-crf','18','-pix_fmt','yuv420p','-threads','2',str(output)])
    return output

with ThreadPoolExecutor(max_workers=3) as pool:
    files=list(pool.map(segment,specs))
(EDIT/'concat.txt').write_text('\n'.join(f"file '{f}'" for f in files))
run([FF,'-y','-loglevel','error','-f','concat','-safe','0','-i',str(EDIT/'concat.txt'),'-c','copy',str(EDIT/'picture.mp4')])

SANS='/System/Library/Fonts/STHeiti Medium.ttc'
SERIF='/System/Library/Fonts/Supplemental/Songti.ttc'
def font(size, serif=False):
    try:return ImageFont.truetype(SERIF if serif else SANS,size)
    except OSError:return ImageFont.truetype(SANS,size)

titles=[]
def title(name, lines, start, end, x, y, width=1500, height=320, shade=False):
    im=Image.new('RGBA',(width,height));d=ImageDraw.Draw(im)
    if shade:
        for px in range(width):
            alpha=round(125 * min(1,px/120))
            d.line((px,0,px,height),fill=(18,38,31,alpha))
    for text,size,py,serif in lines:
        f=font(size,serif)
        d.text((3,py+3),text,font=f,fill=(6,18,14,170),stroke_width=1)
        d.text((0,py),text,font=f,fill=(250,240,218,250))
    path=EDIT/(name+'.png');im.save(path)
    titles.append(dict(path=path,start=start,end=end,x=x,y=y))

title('hook',[('你今天的一小步，',62,0,True),('会留下些什么？',62,85,True)],.35,4.85,90,100)
title('name',[('成长小镇',80,0,True),('把现实的成长，带进一座小镇。',31,110,False)],5.3,11.9,90,100)
title('places',[('给想成为的自己，',38,0,True),('留一个位置。',38,54,True)],14,22.8,100,890,height=140)
title('idea',[('一句「我想……」',50,0,True)],28,31.05,1380,185,width=530,height=100)
title('action',[('变成今天',54,0,True),('做得到的一步。',54,80,True)],31.6,38.45,1380,235,width=530,height=230)
title('breathe',[('走慢一点，',56,0,True),('也算在路上。',56,82,True)],44.1,48.45,1380,300,width=530,height=240)
title('real-world',[('做完之后，回来记下。',34,0,False)],49,52.2,90,930,height=80)
title('record',[('做过的，',56,0,True),('都会留下。',56,82,True)],53,57.1,1380,300,width=530,height=230)
title('home-title',[('回家，总有一份欢迎。',36,0,True)],58.5,64.3,90,930,height=80)
title('remember',[('让努力，',54,0,True),('有处可回望。',54,80,True)],65.35,69.75,1380,300,width=530,height=230)
title('cta',[('成长小镇',78,0,True),('今天，从一小步开始。',34,116,False),('开启属于你的成长日常',26,185,False)],70.3,75,1110,340,width=780,height=290)
title('provenance',[('产品运行画面 · 演示数据与 AI 示例',18,0,False)],0,75,60,1036,width=960,height=35)
title('staged-social',[('互动情境编排',18,0,False)],23.75,27.5,1700,1036,width=220,height=35)

args=[FF,'-y','-loglevel','error','-i',str(EDIT/'picture.mp4'),'-i',str(OUT/'score.wav')]
for t in titles:args += ['-loop','1','-i',str(t['path'])]
filters=[]
last='0:v'
for i,t in enumerate(titles):
    index=i+2;fade=.32 if t['start']>0 else .05
    filters.append(f'[{index}:v]format=rgba,fade=t=in:st={t["start"]}:d={fade}:alpha=1,fade=t=out:st={max(t["start"],t["end"]-.3)}:d=0.3:alpha=1[caption{i}]')
    filters.append(f'[{last}][caption{i}]overlay={t["x"]}:{t["y"]}:enable=\'between(t,{t["start"]},{t["end"]})\'[v{i}]')
    last=f'v{i}'
filters.append(f'[{last}]fade=t=out:st=74.5:d=0.5[v]')
args += ['-filter_complex',';'.join(filters),'-map','[v]','-map','1:a','-t','75','-c:v','libx264','-preset','medium','-crf','18','-pix_fmt','yuv420p','-threads','4','-c:a','aac','-b:a','192k','-movflags','+faststart',str(OUT/'growth-town-product-trailer.mp4')]
run(args)
(OUT/'edit-decision.json').write_text(json.dumps(dict(duration=75,fps=30,shots=[dict(name=s[0],source=s[1],sourceIn=s[2],start=s[3],end=s[4],crop=s[5])for s in specs],music='Original synthesized score, 96 BPM',provenance='Production UI and renderer with explicitly labeled demo data / AI examples'),ensure_ascii=False,indent=2))
print(OUT/'growth-town-product-trailer.mp4')
