"""Synthetic Gradle config/copy checks using an existing runtime; no login or device calls."""
from pathlib import Path
import argparse,json,os,subprocess,tempfile
from check_build_readiness import stage_config_inputs

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo",type=Path,default=Path(__file__).resolve().parents[2])
    parser.add_argument("--gradle")
    args=parser.parse_args()
    if args.gradle:
        gradle=args.gradle
    else:
        home=Path(os.environ.get("GRADLE_USER_HOME",str(Path.home()/".gradle")))
        binary="gradle.bat" if os.name=="nt" else "gradle"
        paths=list(home.glob("wrapper/dists/gradle-9.1.0-bin/*/gradle-9.1.0/bin/"+binary))
        assert len(paths)==1,"An existing Gradle 9.1.0 runtime is required; run the normal build first"
        gradle=str(paths[0])
    with tempfile.TemporaryDirectory(prefix="openridesync-client-config-") as temporary:
        root=Path(temporary).resolve()
        staged,_=stage_config_inputs(args.repo,root/"project")
        config=root/"client.json"
        valid={key:"synthetic-client-value" for key in ["projectId","apiKey","applicationId","webClientId","authDomain","webApiKey"]}
        config.write_text(json.dumps(valid))
        (root/"neighbor-private.txt").write_text("synthetic-neighbor-must-not-be-copied")
        common=[gradle,"--offline","--no-daemon","--project-dir",str(staged),
                "-Dorg.gradle.java.installations.auto-download=false","-Dandroid.builder.sdkDownload=false",
                "-PwithHondaConfig=true","-PwithOemUiAssets=false","-PsourceCommit="+"0"*40,
                "-PhondaConfigFile="+str(config)]
        def run(task):
            return subprocess.run(common+[task],capture_output=True,text=True,errors="replace",timeout=180)
        result=run(":app:prepareHondaClientConfig")
        assert result.returncode==0,result.stdout+result.stderr
        generated=staged/"app/build/generated/privateAuthAssets"
        assert [p.name for p in generated.iterdir()]==["auth-client.json"]
        assert json.loads((generated/"auth-client.json").read_text())==valid
        assert (generated/"auth-client.json").read_bytes()==config.read_bytes()
        marker="synthetic-value-must-not-be-echoed"
        config.write_text(json.dumps({**valid,"accessToken":marker}))
        result=run(":app:prepareHondaClientConfig");output=result.stdout+result.stderr
        assert result.returncode!=0 and "only the reviewed client-configuration fields" in output
        assert marker not in output
        config.write_text('{"apiKey":"'+marker)
        result=run(":app:prepareHondaClientConfig");output=result.stdout+result.stderr
        assert result.returncode!=0 and "Invalid private auth-client.json" in output
        assert marker not in output
        config.write_text("x"*17000)
        result=run(":app:prepareHondaClientConfig");output=result.stdout+result.stderr
        assert result.returncode!=0 and "exceeds the supported size" in output
    print("PASS synthetic single-file client copy, field allowlist and sanitized malformed-input rejection")

if __name__=="__main__":
    main()
