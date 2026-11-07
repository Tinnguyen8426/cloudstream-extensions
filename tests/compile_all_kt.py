#!/usr/bin/env python3
import os
import subprocess
import sys

def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    os.environ["JAVA_TOOL_OPTIONS"] = "-Dfile.encoding=UTF-8"
    kbin = os.path.join(root, 'StremioSubtitleEnricher', '.build_cache', 'kotlinc-2.1.0', 'bin', 'kotlinc.bat')

    cp_jars = [
        os.path.join(root, 'StremioSubtitleEnricher', '.build_cache', 'android.jar'),
        os.path.join(root, 'StremioSubtitleEnricher', '.build_cache', 'cloudstream.jar'),
        os.path.join(root, 'tests', '.build_cache', 'okio.jar'),
        os.path.join(root, 'tests', '.build_cache', 'jackson-core.jar'),
        os.path.join(root, 'tests', '.build_cache', 'jackson-databind.jar'),
        os.path.join(root, 'tests', '.build_cache', 'jackson-module-kotlin.jar'),
        os.path.join(root, 'StremioSubtitleEnricher', '.build_cache', 'jackson-annotations.jar'),
        os.path.join(root, 'StremioSubtitleEnricher', '.build_cache', 'okhttp.jar'),
        os.path.join(root, 'StremioSubtitleEnricher', '.build_cache', 'nicehttp.jar'),
        os.path.join(root, 'StremioSubtitleEnricher', '.build_cache', 'kotlinx-coroutines.jar'),
        os.path.join(root, 'StremioSubtitleEnricher', '.build_cache', 'kotlinc-2.1.0', 'lib', 'kotlin-stdlib.jar'),
        os.path.join(root, 'StremioSubtitleEnricher', '.build_cache', 'kotlinc-2.1.0', 'lib', 'kotlin-reflect.jar'),
    ]

    out_dir = os.path.join(root, 'tests', 'build', 'full_project_compile')
    os.makedirs(out_dir, exist_ok=True)

    ai_src_dir = os.path.join(root, 'AiSubtitleTranslator', 'AiTranslator', 'src', 'main', 'kotlin')
    src_files = []
    for r, _, fs in os.walk(ai_src_dir):
        for f in fs:
            if f.endswith('.kt'):
                src_files.append(os.path.join(r, f))

    src_files.sort()
    print(f"[*] Found {len(src_files)} project Kotlin source files in AiSubtitleTranslator:")
    for f in src_files:
        print(f"    - {os.path.relpath(f, root)}")

    cp_str = ';'.join(cp_jars)
    src_str = ' '.join(f'"{f}"' for f in src_files)

    cmd = f'"{kbin}" -cp "{cp_str}" -d "{out_dir}" -jvm-target 11 -Xskip-metadata-version-check {src_str}'
    print("[*] Running kotlinc compilation of all project Kotlin files...")
    res = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    print(f"Exit code: {res.returncode}")
    if res.stdout:
        print("STDOUT:\n" + res.stdout)
    if res.stderr:
        print("STDERR:\n" + res.stderr)

    if res.returncode != 0:
        print(f"[!] Compilation failed with code {res.returncode}")
        sys.exit(res.returncode)

    print(f"[+] kotlinc full compilation SUCCEEDED with code 0! Classes emitted to {out_dir}")

if __name__ == "__main__":
    main()
