#!/usr/bin/env python3
"""
Standalone Cross-Platform CloudStream Plugin Compiler & Packager for Kitsunekko Enricher.
Supports Windows (os.pathsep, kotlinc.bat) and Linux/macOS.
"""
import os
import sys
import shutil
import zipfile
import hashlib
import json
import urllib.request
import subprocess

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
CACHE_DIR = os.path.join(PROJECT_DIR, ".build_cache")
DIST_DIR = os.path.join(PROJECT_DIR, "dist")
SRC_DIR = os.path.join(PROJECT_DIR, "KitsunekkoEnricher", "src", "main", "kotlin")

# Reference sibling cache to reuse already downloaded jars if present
SIBLING_CACHE_DIR = os.path.join(os.path.dirname(PROJECT_DIR), "StremioSubtitleEnricher", ".build_cache")

ANDROID_JAR_URL = "https://raw.githubusercontent.com/Sable/android-platforms/master/android-34/android.jar"
R8_JAR_URL = "https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.2.42/r8-8.2.42.jar"
KOTLINC_ZIP_URL = "https://github.com/JetBrains/kotlin/releases/download/v2.1.0/kotlin-compiler-2.1.0.zip"
CLOUDSTREAM_JAR_URL = "https://github.com/recloudstream/cloudstream/releases/download/pre-release/classes.jar"

DEPS = [
    ("nicehttp.jar", "https://jitpack.io/com/github/Blatzar/NiceHttp/0.4.11/NiceHttp-0.4.11.jar"),
    ("jackson-annotations.jar", "https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-annotations/2.13.1/jackson-annotations-2.13.1.jar"),
    ("gson.jar", "https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar"),
    ("okhttp.jar", "https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar"),
    ("kotlinx-coroutines.jar", "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.0/kotlinx-coroutines-core-jvm-1.8.0.jar"),
    ("kotlinx-serialization-core.jar", "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.6.3/kotlinx-serialization-core-jvm-1.6.3.jar"),
    ("kotlinx-serialization-json.jar", "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/1.6.3/kotlinx-serialization-json-jvm-1.6.3.jar")
]

def acquire_file(url, target_path, desc=""):
    if os.path.exists(target_path) and os.path.getsize(target_path) > 100:
        print(f"[*] Already cached: {os.path.basename(target_path)}")
        return target_path
    
    fname = os.path.basename(target_path)
    sibling_path = os.path.join(SIBLING_CACHE_DIR, fname)
    if os.path.exists(sibling_path) and os.path.getsize(sibling_path) > 100:
        print(f"[*] Reusing cached dependency from sibling: {fname}")
        shutil.copy2(sibling_path, target_path)
        return target_path

    print(f"[*] Downloading {desc or fname}...")
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req) as resp, open(target_path, "wb") as f:
        shutil.copyfileobj(resp, f)
    print(f"[+] Download complete: {fname} ({os.path.getsize(target_path):,} bytes)")
    return target_path

def compute_sha256(filepath):
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return "sha256-" + h.hexdigest()

def main():
    print("=" * 60)
    print(" CloudStream Plugin Builder: Kitsunekko Japanese Subtitle Enricher")
    print("=" * 60)

    os.makedirs(CACHE_DIR, exist_ok=True)
    os.makedirs(DIST_DIR, exist_ok=True)

    # 1. Acquire core dependencies
    android_jar = os.path.join(CACHE_DIR, "android.jar")
    acquire_file(ANDROID_JAR_URL, android_jar, "Android 34 SDK Jar")

    r8_jar = os.path.join(CACHE_DIR, "r8.jar")
    acquire_file(R8_JAR_URL, r8_jar, "Google R8 / D8 Dexer")

    cs_jar = os.path.join(CACHE_DIR, "cloudstream.jar")
    acquire_file(CLOUDSTREAM_JAR_URL, cs_jar, "Cloudstream Library Classes Jar")

    classpath_jars = [android_jar, cs_jar]

    for fname, url in DEPS:
        fpath = os.path.join(CACHE_DIR, fname)
        acquire_file(url, fpath, fname)
        classpath_jars.append(fpath)

    # 2. Setup kotlinc 2.1.0 with Windows portability
    kotlinc_dir = os.path.join(CACHE_DIR, "kotlinc-2.1.0")
    is_windows = (os.name == "nt")
    kotlinc_exec_name = "kotlinc.bat" if is_windows else "kotlinc"
    kotlinc_bin = os.path.join(kotlinc_dir, "bin", kotlinc_exec_name)

    needs_extract = not os.path.exists(kotlinc_bin)
    if not is_windows and os.path.exists(kotlinc_bin) and not os.access(kotlinc_bin, os.X_OK):
        needs_extract = True

    if needs_extract:
        sibling_kotlinc = os.path.join(SIBLING_CACHE_DIR, "kotlinc-2.1.0")
        if os.path.exists(os.path.join(sibling_kotlinc, "bin", kotlinc_exec_name)):
            print("[*] Reusing extracted kotlinc 2.1.0 from sibling...")
            shutil.copytree(sibling_kotlinc, kotlinc_dir, dirs_exist_ok=True)
        else:
            kotlinc_zip = os.path.join(CACHE_DIR, "kotlinc-2.1.0.zip")
            acquire_file(KOTLINC_ZIP_URL, kotlinc_zip, "Kotlin Compiler 2.1.0")
            print("[*] Extracting kotlinc 2.1.0...")
            tmp_extract = os.path.join(CACHE_DIR, "tmp_kotlinc")
            if os.path.exists(tmp_extract):
                shutil.rmtree(tmp_extract)
            with zipfile.ZipFile(kotlinc_zip, 'r') as z:
                z.extractall(tmp_extract)
            if os.path.exists(kotlinc_dir):
                shutil.rmtree(kotlinc_dir)
            shutil.move(os.path.join(tmp_extract, "kotlinc"), kotlinc_dir)
            shutil.rmtree(tmp_extract, ignore_errors=True)

        if not is_windows:
            bin_dir = os.path.join(kotlinc_dir, "bin")
            subprocess.run(["termux-fix-shebang"] + [os.path.join(bin_dir, f) for f in os.listdir(bin_dir)], check=False)
            for f in os.listdir(bin_dir):
                try:
                    os.chmod(os.path.join(bin_dir, f), 0o755)
                except OSError:
                    pass

    # 3. Collect Kotlin sources
    kt_files = []
    for root, _, files in os.walk(SRC_DIR):
        for f in files:
            if f.endswith(".kt"):
                kt_files.append(os.path.join(root, f))
    print(f"[*] Found {len(kt_files)} Kotlin source files to compile:")
    for kf in kt_files:
        print(f"    - {os.path.relpath(kf, PROJECT_DIR)}")

    if not kt_files:
        print("[!] No Kotlin source files found in: " + SRC_DIR, file=sys.stderr)
        sys.exit(1)

    # 4. Compile Kotlin to .class files
    classes_out_dir = os.path.join(CACHE_DIR, "classes")
    if os.path.exists(classes_out_dir):
        shutil.rmtree(classes_out_dir)
    os.makedirs(classes_out_dir, exist_ok=True)

    # Use os.pathsep (';' on Windows, ':' on POSIX)
    classpath_str = os.pathsep.join(classpath_jars)
    print(f"[*] Compiling Kotlin code with kotlinc 2.1.0 (pathsep='{os.pathsep}')...")
    if is_windows:
        cmd_parts = [
            f'"{kotlinc_bin}"',
            "-cp", f'"{classpath_str}"',
            "-d", f'"{classes_out_dir}"',
            "-jvm-target", "11",
            "-opt-in=kotlin.RequiresOptIn",
            "-Xskip-metadata-version-check",
            "-no-reflect"
        ] + [f'"{kf}"' for kf in kt_files]
        cmd_str = " ".join(cmd_parts)
        ret = subprocess.run(cmd_str, shell=True)
    else:
        compile_cmd = [
            kotlinc_bin,
            "-cp", classpath_str,
            "-d", classes_out_dir,
            "-jvm-target", "11",
            "-opt-in=kotlin.RequiresOptIn",
            "-Xskip-metadata-version-check",
            "-no-reflect"
        ] + kt_files
        ret = subprocess.run(compile_cmd)

    if ret.returncode != 0:
        print("[!] Compilation failed!", file=sys.stderr)
        sys.exit(1)
    print("[+] Kotlin compilation succeeded!")

    # 5. Dex with Google D8 (min-api 21)
    dex_out_dir = os.path.join(CACHE_DIR, "dex")
    if os.path.exists(dex_out_dir):
        shutil.rmtree(dex_out_dir)
    os.makedirs(dex_out_dir, exist_ok=True)

    print("[*] Dexing classes with Google D8 (minSdkVersion=21)...")
    compiled_classes = []
    for root, _, files in os.walk(classes_out_dir):
        for f in files:
            if f.endswith(".class"):
                compiled_classes.append(os.path.join(root, f))

    d8_cmd = [
        "java", "-cp", r8_jar,
        "com.android.tools.r8.D8",
        "--min-api", "21",
        "--output", dex_out_dir,
    ]
    for lib in classpath_jars:
        if os.path.exists(lib):
            d8_cmd.extend(["--lib", lib])
    stdlib_jar = os.path.join(kotlinc_dir, "lib", "kotlin-stdlib.jar")
    if os.path.exists(stdlib_jar):
        d8_cmd.extend(["--lib", stdlib_jar])
    d8_cmd.extend(compiled_classes)

    ret = subprocess.run(d8_cmd)
    if ret.returncode != 0:
        print("[!] D8 dexing failed!", file=sys.stderr)
        sys.exit(1)
    print("[+] Dexing complete! Output classes.dex created.")

    # 6. Generate manifest.json
    manifest_data = {
        "pluginClassName": "com.kitsunekko.enricher.KitsunekkoPlugin",
        "name": "KitsunekkoEnricher",
        "version": 1,
        "requiresResources": False
    }
    manifest_file = os.path.join(CACHE_DIR, "manifest.json")
    with open(manifest_file, "w") as f:
        json.dump(manifest_data, f, separators=(',', ':'))

    # 7. Package into KitsunekkoEnricher.cs3
    cs3_file = os.path.join(DIST_DIR, "KitsunekkoEnricher.cs3")
    dex_file = os.path.join(dex_out_dir, "classes.dex")

    print("[*] Packaging into KitsunekkoEnricher.cs3...")
    with zipfile.ZipFile(cs3_file, 'w', zipfile.ZIP_DEFLATED) as z:
        z.write(dex_file, "classes.dex")
        z.write(manifest_file, "manifest.json")

    file_size = os.path.getsize(cs3_file)
    file_hash = compute_sha256(cs3_file)
    print(f"[+] Successfully built: {cs3_file}")
    print(f"    - File Size: {file_size:,} bytes")
    print(f"    - SHA256: {file_hash}")

    # 8. Generate plugins.json
    plugins_entry = [
        {
            "url": "https://raw.githubusercontent.com/Tinnguyen8426/cloudstream-extensions/builds/KitsunekkoEnricher.cs3",
            "status": 1,
            "version": 1,
            "name": "Kitsunekko Japanese Subtitle Enricher",
            "internalName": "KitsunekkoEnricher",
            "authors": [
                "Kitsunekko Japanese Subtitle Enricher"
            ],
            "description": "Tự động trích xuất và nhúng phụ đề tiếng Nhật (.ass, .srt, .zip) từ Kitsunekko vào CloudStream",
            "fileSize": file_size,
            "repositoryUrl": "https://github.com/Tinnguyen8426/cloudstream-extensions",
            "language": "ja",
            "tvTypes": [
                "Anime",
                "AnimeMovie",
                "OVA"
            ],
            "iconUrl": "https://kitsunekko.net/favicon.ico",
            "apiVersion": 1,
            "fileHash": file_hash
        }
    ]

    plugins_json_file = os.path.join(DIST_DIR, "plugins.json")
    with open(plugins_json_file, "w", encoding="utf-8") as f:
        json.dump(plugins_entry, f, indent=4, ensure_ascii=False)
    print(f"[+] Generated: {plugins_json_file}")

    # 9. Generate repo.json
    repo_data = {
        "name": "Kitsunekko Japanese Subtitle Enricher Repo",
        "iconUrl": "https://kitsunekko.net/favicon.ico",
        "description": "Kho tiện ích mở rộng tự động cào và nhúng phụ đề tiếng Nhật từ kitsunekko.net vào CloudStream",
        "manifestVersion": 1,
        "pluginLists": [
            "https://raw.githubusercontent.com/Tinnguyen8426/cloudstream-extensions/builds/plugins.json"
        ]
    }

    repo_json_file = os.path.join(DIST_DIR, "repo.json")
    with open(repo_json_file, "w", encoding="utf-8") as f:
        json.dump(repo_data, f, indent=4, ensure_ascii=False)
    print(f"[+] Generated: {repo_json_file}")

    print("\n" + "=" * 60)
    print(" BUILD & RELEASE COMPLETED SUCCESSFULLY!")
    print("=" * 60)
    print(f"Plugin package: {cs3_file}")
    print(f"Repository file: {repo_json_file}")
    print(f"Plugins manifest: {plugins_json_file}")

if __name__ == "__main__":
    main()
