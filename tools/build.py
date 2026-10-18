"""Reproducible build orchestrator. Downloads happen only with --provision."""
from __future__ import annotations
import argparse
import importlib.metadata
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

ROOT=Path(__file__).resolve().parents[1]
TOOLS=ROOT/'.tools'
APP=ROOT/'.build'/'app'
ENV=os.environ.copy()
ENV.update(DOTNET_CLI_HOME=str(TOOLS/'dotnet-home'),DOTNET_CLI_TELEMETRY_OPTOUT='1',
           DOTNET_SKIP_FIRST_TIME_EXPERIENCE='1',DOTNET_ADD_GLOBAL_TOOLS_TO_PATH='0',
           NUGET_PACKAGES=str(ROOT/'.cache'/'nuget'),PYTHONUTF8='1',DOTNET_GENERATE_ASPNET_CERTIFICATE='false')
PY=TOOLS/'build-python'/'Scripts'/'python.exe'
DOTNET=TOOLS/'dotnet'/'dotnet.exe'

def run(args,**kwargs):
    print('RUN', ' '.join(map(str,args)),flush=True)
    subprocess.run(list(map(str,args)),cwd=ROOT,env=ENV,check=True,**kwargs)

def icons():
    from PIL import Image,ImageDraw
    dest=ROOT/'installer'/'Assets';dest.mkdir(exist_ok=True,parents=True)
    base=Image.new('RGBA',(256,256),(0,0,0,0));d=ImageDraw.Draw(base)
    d.rounded_rectangle((8,8,248,248),radius=52,fill='#163D61')
    d.rounded_rectangle((62,40,178,210),radius=10,fill='#FFFFFF')
    d.polygon([(142,40),(178,76),(142,76)],fill='#A9D5E6')
    d.line([(86,111),(157,111)],fill='#163D61',width=10)
    d.line([(86,140),(157,140)],fill='#163D61',width=10)
    d.line([(86,169),(137,169)],fill='#163D61',width=10)
    base.save(ROOT/'src'/'Conversor.App'/'app.ico',sizes=[(16,16),(32,32),(48,48),(64,64),(256,256)])
    for name,size in [('StoreLogo',50),('Square150x150Logo',150),('Square44x44Logo',44)]: base.resize((size,size)).save(dest/(name+'.png'))

def native():
    APP.mkdir(parents=True,exist_ok=True)
    run([TOOLS/'w64devkit'/'bin'/'g++.exe','-std=c++17','-O2','-shared','-static','-static-libgcc','-static-libstdc++',
         ROOT/'src'/'Conversor.Shell'/'ContextMenu.cpp','-o',APP/'Conversor.Shell.dll','-lole32','-lshell32','-lshlwapi','-luuid','-Wl,--kill-at'])

def app():
    run([DOTNET,'publish','src/Conversor.App/Conversor.App.csproj','-c','Release','-r','win-x64','--self-contained','true',
         '-p:PublishSingleFile=true','-p:EnableCompressionInSingleFile=true','-p:IncludeNativeLibrariesForSelfExtract=true','-o',APP])
    notices=APP/'licenses';notices.mkdir(exist_ok=True)
    for name in ['LICENSE.txt','ThirdPartyNotices.txt']:
        shutil.copy2(TOOLS/'dotnet'/name,notices/('dotnet-'+name))

def engine():
    run([PY,'-m','PyInstaller','--noconfirm','--clean','--onedir','--console','--name','Conversor.Engine',
         '--distpath',ROOT/'.build'/'engine-dist','--workpath',ROOT/'.build'/'engine-temp','--specpath',ROOT/'.build',
         '--collect-all','pymupdf','--collect-data','docx','--collect-data','pptx','--hidden-import','pdf2docx',
         '--hidden-import','cv2','--hidden-import','PIL._imaging','--exclude-module','pytest','--exclude-module','reportlab',
         '--exclude-module','tkinter',ROOT/'engine'/'worker.py'])
    shutil.copytree(ROOT/'.build'/'engine-dist'/'Conversor.Engine',APP/'engine',dirs_exist_ok=True)

def office():
    candidate=ROOT/'.build'/'lo-extracted'/'LibreOffice'
    if not candidate.exists():
        matches=list((ROOT/'.build'/'lo-extracted').glob('**/program/soffice.com'))
        if not matches: raise RuntimeError('First extract the official LibreOffice MSI: see tools/prepare.ps1')
        candidate=matches[0].parent.parent
    shutil.copytree(candidate,APP/'libreoffice',dirs_exist_ok=True,ignore=shutil.ignore_patterns('*.msi'))
    # MSI normally installs these into System32. Keep them app-local for a clean PC.
    for dll in (candidate/'System64').glob('*.dll'): shutil.copy2(dll,APP/'libreoffice'/'program'/dll.name)
    private_fonts=APP/'libreoffice'/'share'/'fonts'/'truetype';private_fonts.mkdir(parents=True,exist_ok=True)
    for font in (candidate/'Fonts').glob('*.ttf'): shutil.copy2(font,private_fonts/font.name)
    for msi in (APP/'libreoffice').glob('*.msi'): msi.unlink()

def bundle_sources():
    dest=APP/'sources';dest.mkdir(exist_ok=True)
    with zipfile.ZipFile(dest/'ConversorLocal-source.zip','w',zipfile.ZIP_DEFLATED) as archive:
        for folder in ['src','engine','tools','installer','tests','docs','.github']:
            if not (ROOT/folder).exists(): continue
            for p in (ROOT/folder).rglob('*'):
                if p.is_file() and not any(x in p.parts for x in ['bin','obj','__pycache__']): archive.write(p,p.relative_to(ROOT))
        for name in ['README.md','LICENSE','THIRD-PARTY-NOTICES.md','global.json','.gitignore']:
            if (ROOT/name).exists():archive.write(ROOT/name,name)
    for name in ['README.md','LICENSE','THIRD-PARTY-NOTICES.md']:
        if (ROOT/name).exists():shutil.copy2(ROOT/name,APP/name)

def installer():
    run([TOOLS/'inno'/'ISCC.exe','/Qp',ROOT/'installer'/'ConversorLocal.iss'])

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--steps',default='icons,native,app,engine,office,sources,installer')
    opts=parser.parse_args()
    for step in opts.steps.split(','):
        print('BUILD STEP',step,flush=True)
        {'icons':icons,'native':native,'app':app,'engine':engine,'office':office,'sources':bundle_sources,'installer':installer}[step]()

if __name__=='__main__':main()
