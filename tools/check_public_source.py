"""Check tracked and visible source paths without printing matched sensitive values."""
from pathlib import Path, PurePosixPath
import hashlib,json,subprocess,sys
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools/validation'))
from check_build_readiness import PATTERNS
WRAPPER_SHA256="76805e32c009c0cf0dd5d206bddc9fb22ea42e84db904b764f3047de095493f3"

def inspect_source(path, relative):
    parts=PurePosixPath(relative.lower()).parts
    forbidden={"private","captures","device-validation","node_modules",".gradle","build"}
    suffix=Path(relative).suffix.lower()
    if (any(p in forbidden for p in parts) or
        Path(relative).name.lower() in {"auth-client.json","google-services.json"} or
        suffix in {".jks",".keystore",".p12",".pfx",".apk",".btsnoop",".zip",".tar"}):
        return [{"path":relative,"category":"private_or_generated_path"}]
    if path.is_symlink():
        return [{"path":relative,"category":"symbolic_link"}]
    data=path.read_bytes()
    if suffix==".jar":
        if relative!="gradle/wrapper/gradle-wrapper.jar" or hashlib.sha256(data).hexdigest()!=WRAPPER_SHA256:
            return [{"path":relative,"category":"unreviewed_binary"}]
        return []
    return [{"path":relative,"category":name,"line":data[:m.start()].count(b"\n")+1}
            for name,pattern in PATTERNS.items() for m in pattern.finditer(data)]

def main():
    names=subprocess.check_output(["git","ls-files","-z","--cached","--others","--exclude-standard"],cwd=ROOT).decode().split("\0")
    names=sorted(set(filter(None,names)))
    findings=[]
    for name in names:
        path=ROOT/name
        if path.is_file() or path.is_symlink():
            findings.extend(inspect_source(path,name))
    print(json.dumps({"source_files":len(names),"findings":findings}))
    return 1 if findings or not names else 0

if __name__=="__main__":
    raise SystemExit(main())
