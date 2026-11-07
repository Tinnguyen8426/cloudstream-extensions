#!/usr/bin/env python3
import os
import sys
import subprocess

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

    out_dir = os.path.join(root, 'tests', 'build', 'p3_auditor')
    os.makedirs(out_dir, exist_ok=True)

    ai_src_dir = os.path.join(root, 'AiSubtitleTranslator', 'AiTranslator', 'src', 'main', 'kotlin')
    src_files = []
    for r, _, fs in os.walk(ai_src_dir):
        for f in fs:
            if f.endswith('.kt'):
                src_files.append(os.path.join(r, f))

    auditor_test = os.path.join(root, 'tests', 'src', 'AuditorPhase3ForensicTest.kt')
    if os.path.exists(auditor_test):
        src_files.append(auditor_test)

    print(f"[*] Found {len(src_files)} Kotlin source files to compile for auditor test.")

    cp_str = ';'.join(cp_jars)
    src_str = ' '.join(f'"{f}"' for f in src_files)

    cmd = f'"{kbin}" -cp "{cp_str}" -d "{out_dir}" -jvm-target 11 -Xskip-metadata-version-check {src_str}'
    print("[*] Running kotlinc compilation...")
    res = subprocess.run(cmd, shell=True)
    if res.returncode != 0:
        print(f"[!] Compilation failed with code {res.returncode}")
        sys.exit(res.returncode)

    print(f"[+] kotlinc compilation SUCCEEDED! Classes emitted to {out_dir}")

    # Execute auditor test suite on JVM
    print("[*] Executing tests.adversarial.AuditorPhase3ForensicTestKt on JVM...")
    run_cp = ';'.join([out_dir] + cp_jars)
    java_cmd = f'java -Dfile.encoding=UTF-8 -cp "{run_cp}" tests.adversarial.AuditorPhase3ForensicTestKt'
    res_run = subprocess.run(java_cmd, shell=True)
    if res_run.returncode != 0:
        print(f"[!] Auditor forensic tests failed with code {res_run.returncode}")
        sys.exit(res_run.returncode)
    print("[+] Auditor forensic tests PASSED cleanly.")

if __name__ == "__main__":
    main()
