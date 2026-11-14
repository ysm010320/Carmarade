import sys,re,subprocess,os
s,t=sys.argv[1],sys.argv[2]
d=os.environ.get('TEMP','.')
f=os.path.join(d,'u_%s.xml'%s)
subprocess.run(['adb','-s',s,'shell','uiautomator','dump','/sdcard/u.xml'],capture_output=True)
subprocess.run(['adb','-s',s,'pull','/sdcard/u.xml',f],capture_output=True)
x=open(f,encoding='utf-8').read()
for m in re.finditer(r'<node [^>]*>',x):
    n=m.group(0)
    txt=re.search(r' text="([^"]*)"',n); cd=re.search(r'content-desc="([^"]*)"',n); b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',n)
    if b and ((txt and t in txt.group(1)) or (cd and t in cd.group(1))):
        cx=(int(b.group(1))+int(b.group(3)))//2; cy=(int(b.group(2))+int(b.group(4)))//2
        subprocess.run(['adb','-s',s,'shell','input','tap',str(cx),str(cy)]); print('tapped',t,cx,cy); break
else: print('not found',t)
