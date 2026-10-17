from pathlib import Path
import os, subprocess, sys
from PIL import Image, ImageDraw

ROOT=Path(__file__).resolve().parents[1]
ARCHIVE=Path(r'C:\Users\qwe17\OneDrive\backup\CPM-mod-CPM-mod\CPM-Collection')
groups=[('legacy',ARCHIVE/'Legacy-Models',['1-Black Rock Shooter','10-Kirisame Marisa','100-Dragonoid','114-Fox','154-Default (Classic)','169-zoroark_1.12.2']),
        ('modern',ARCHIVE/'Remake-Models',['175-Asada Shino','177-Hakurei Reimu','180-Kirisame Marisa','184-Syameimaru Aya','186-Yukari Yakumo','192-zoroark_1.16.5_v2','193-prototgen_1.16.5','200-Ori'])]
out=ROOT/'build'/'cpm-renders'/'batch'; out.mkdir(parents=True,exist_ok=True)
env=os.environ.copy(); env['JAVA_HOME']=r'C:\Users\qwe17\.jdks\corretto-17.0.4.1'; env['Path']=env['JAVA_HOME']+r'\bin;'+env.get('Path','')
rows=[]
for kind,base,names in groups:
    for name in names:
        root=base/name
        if not root.is_dir(): continue
        safe=f'{kind}-{name}'.replace(' ','_').replace("'",'')
        snap=out/(safe+'.json'); render_dir=out/safe
        # A snapshot is tied to the converter implementation.  Set
        # CPM_FORCE=1 after changing parser/pose code to regenerate it.
        if os.environ.get('CPM_FORCE') == '1' or not snap.exists():
            cmd=[str(ROOT/'gradlew.bat'),'-p','runtime',':ysm-runtime-java-cpm:offlineSnapshot',f'-Pcpm.snapshotDir={root}',f'-Pcpm.snapshotOut={snap}','--no-configuration-cache']
            subprocess.run(cmd,cwd=ROOT,env=env,check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        if os.environ.get('CPM_FORCE') == '1' or not list(render_dir.glob('*-000.png')):
            subprocess.run([sys.executable,str(ROOT/'tools'/'cpm_offline_render.py'),str(root),str(snap),str(render_dir)],cwd=ROOT,check=True,stdout=subprocess.DEVNULL)
        rows.append((safe,Image.open(next(render_dir.glob('*-000.png'))).convert('RGB')))
for snap in sorted(out.glob('*.json')):
    safe=snap.stem; render_dir=out/safe; pngs=list(render_dir.glob('*-000.png'))
    if pngs and not any(label==safe for label,_ in rows): rows.append((safe,Image.open(pngs[0]).convert('RGB')))
for frame in (0,10,20):
    sheet=Image.new('RGB',(4*360,((len(rows)+3)//4)*390),(25,28,34)); draw=ImageDraw.Draw(sheet)
    for i,(label,_) in enumerate(rows):
        render_dir=out/label; pngs=list(render_dir.glob(f'*-{frame:03d}.png'))
        if not pngs: continue
        x=(i%4)*360; y=(i//4)*390; sheet.paste(Image.open(pngs[0]).convert('RGB').resize((360,360)),(x,y)); draw.text((x+6,y+364),label,fill='white')
    sheet.save(out/f'contact-sheet-frame{frame}.png')
print(out/'contact-sheet-frame0.png')
