"""Compile the production starter against explicit host doubles; no Android/device access."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kotlin-home", required=True, type=Path)
    parser.add_argument("--java-home", required=True, type=Path)
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    android = here.parents[1]
    production = android / "app/src/main/java/org/openridesync/app/media"
    suffix = ".bat" if os.name == "nt" else ""
    java = args.java_home.resolve() / "bin" / ("java.exe" if os.name == "nt" else "java")
    compiler = args.kotlin_home.resolve() / "bin" / ("kotlinc" + suffix)
    coroutines = args.kotlin_home.resolve() / "lib/kotlinx-coroutines-core-jvm.jar"
    env = dict(os.environ, JAVA_HOME=str(args.java_home.resolve()))
    with tempfile.TemporaryDirectory(prefix="favorite-start-host-") as directory:
        output = Path(directory) / "checks.jar"
        subprocess.run([
            str(compiler), str(production / "FavoriteMusicStart.kt"),
            str(production / "FavoriteSessionAcquisition.kt"), str(production / "FavoriteStartGate.kt"),
            *map(str, sorted(here.glob("*.kt"))), "-cp", str(coroutines),
            "-include-runtime", "-d", str(output),
        ], check=True, env=env)
        subprocess.run([
            str(java), "-cp", os.pathsep.join(map(str, [output, coroutines])),
            "org.openridesync.app.media.CheckKt",
        ], check=True, env=env)


if __name__ == "__main__":
    main()
