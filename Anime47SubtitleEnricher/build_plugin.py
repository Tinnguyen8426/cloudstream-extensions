#!/usr/bin/env python3
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
SRC_DIR = os.path.join(PROJECT_DIR, "Anime47Enricher", "src", "main", "kotlin")

ANDROID_JAR_URL = "https://raw.githubusercontent.com/Sable/android-platforms/master/android-34/android.jar"
R8_JAR_URL = "https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.2.42/r8-8.2.42.jar"
KOTLINC_ZIP_URL = "https://github.com/JetBrains/kotlin/releases/download/v2.1.0/kotlin-compiler-2.1.0.zip"
CLOUDSTREAM_JAR_URL = "https://github.com/recloudstream/cloudstream/releases/download/pre-release/classes.jar"

DEPS = [
    ("nicehttp.jar", "https://jitpack.io/com/github/Blatzar/NiceHttp/0.4.11/NiceHttp-0.4.11.jar"),
    ("jackson-annotations.jar", "https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-annotations/2.13.1/jackson-annotations-2.13.1.jar"),
    ("gson.jar", "https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar"),
    ("okhttp.jar", "https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar"),
    ("kotlinx-coroutines.jar", "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.0/kotlinx-coroutines-core-jvm-1.8.0.jar")
]

def download_file(url, target_path, desc=""):
    if os.path.exists(target_path) and os.path.getsize(target_path) > 100:
        print(f"[*] Already cached: {os.path.basename(target_path)}")
        return target_path
    print(f"[*] Downloading {desc or os.path.basename(target_path)}...")
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req) as resp, open(target_path, "wb") as f:
        shutil.copyfileobj(resp, f)
    print(f"[+] Download complete: {os.path.basename(target_path)} ({os.path.getsize(target_path):,} bytes)")
    return target_path

def compute_sha256(filepath):
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return "sha256-" + h.hexdigest()

def main():
    print("=" * 60)
    print(" CloudStream Plugin Builder: Anime47 Subtitle Enricher")
    print("=" * 60)

    os.makedirs(CACHE_DIR, exist_ok=True)
    os.makedirs(DIST_DIR, exist_ok=True)

    # 1. Download core dependencies
    android_jar = os.path.join(CACHE_DIR, "android.jar")
    download_file(ANDROID_JAR_URL, android_jar, "Android 34 SDK Jar")

    r8_jar = os.path.join(CACHE_DIR, "r8.jar")
    download_file(R8_JAR_URL, r8_jar, "Google R8 / D8 Dexer")

    cs_jar = os.path.join(CACHE_DIR, "cloudstream.jar")
    download_file(CLOUDSTREAM_JAR_URL, cs_jar, "Cloudstream Library Classes Jar")

    classpath_jars = [android_jar, cs_jar]

    for fname, url in DEPS:
        fpath = os.path.join(CACHE_DIR, fname)
        download_file(url, fpath, fname)
        classpath_jars.append(fpath)

    # 2. Setup kotlinc 2.1.0
    kotlinc_dir = os.path.join(CACHE_DIR, "kotlinc-2.1.0")
    kotlinc_bin = os.path.join(kotlinc_dir, "bin", "kotlinc")
    if not os.path.exists(kotlinc_bin) or not os.access(kotlinc_bin, os.X_OK):
        kotlinc_zip = os.path.join(CACHE_DIR, "kotlinc-2.1.0.zip")
        download_file(KOTLINC_ZIP_URL, kotlinc_zip, "Kotlin Compiler 2.1.0")
        print("[*] Extracting kotlinc 2.1.0...")
        with zipfile.ZipFile(kotlinc_zip, 'r') as z:
            # zip contains 'kotlinc/' directory
            z.extractall(os.path.join(CACHE_DIR, "tmp_kotlinc"))
        shutil.move(os.path.join(CACHE_DIR, "tmp_kotlinc", "kotlinc"), kotlinc_dir)
        shutil.rmtree(os.path.join(CACHE_DIR, "tmp_kotlinc"), ignore_errors=True)

        subprocess.run(["termux-fix-shebang"] + [os.path.join(kotlinc_dir, "bin", f) for f in os.listdir(os.path.join(kotlinc_dir, "bin"))], check=False)
        for f in os.listdir(os.path.join(kotlinc_dir, "bin")):
            os.chmod(os.path.join(kotlinc_dir, "bin", f), 0o755)

    # 3. Collect Kotlin sources
    kt_files = []
    for root, _, files in os.walk(SRC_DIR):
        for f in files:
            if f.endswith(".kt"):
                kt_files.append(os.path.join(root, f))
    print(f"[*] Found {len(kt_files)} Kotlin source files to compile:")
    for kf in kt_files:
        print(f"    - {os.path.relpath(kf, PROJECT_DIR)}")

    # 4. Compile Kotlin to .class files
    classes_out_dir = os.path.join(CACHE_DIR, "classes")
    if os.path.exists(classes_out_dir):
        shutil.rmtree(classes_out_dir)
    os.makedirs(classes_out_dir, exist_ok=True)

    # Add stubs jar if present
    stubs_jar = os.path.join(CACHE_DIR, "stubs_classes.jar")
    if os.path.exists(stubs_jar):
        classpath_jars.append(stubs_jar)

    classpath_str = ":".join(classpath_jars)
    print("[*] Compiling Kotlin code with kotlinc 2.1.0...")
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

    # 5. Dex with Google D8
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
    print(f"[+] Dexing complete! Output classes.dex created.")

    # 6. Generate manifest.json
    manifest_data = {
        "pluginClassName": "com.anime47.enricher.Anime47Plugin",
        "name": "Anime47Enricher",
        "version": 3,
        "requiresResources": False
    }
    manifest_file = os.path.join(CACHE_DIR, "manifest.json")
    with open(manifest_file, "w") as f:
        json.dump(manifest_data, f, separators=(',', ':'))

    # 7. Package into Anime47Enricher.cs3
    cs3_file = os.path.join(DIST_DIR, "Anime47Enricher.cs3")
    dex_file = os.path.join(dex_out_dir, "classes.dex")

    print("[*] Packaging into Anime47Enricher.cs3...")
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
            "url": "https://raw.githubusercontent.com/Tinnguyen8426/cloudstream-extensions/builds/Anime47Enricher.cs3",
            "status": 1,
            "version": 3,
            "name": "Anime47 Subtitle Enricher",
            "internalName": "Anime47Enricher",
            "authors": [
                "Anime47 Enricher"
            ],
            "description": "Tự động trích xuất và nhúng phụ đề Tiếng Việt (softsub) từ Anime47 vào CloudStream (tương thích hoàn hảo với Torrentio, TorraStream, StremioX, AnimePahe...)",
            "fileSize": file_size,
            "repositoryUrl": "https://github.com/Tinnguyen8426/cloudstream-extensions",
            "language": "vi",
            "tvTypes": [
                "Anime",
                "AnimeMovie",
                "OVA"
            ],
            "iconUrl": "https://anime47.best/favicon.ico",
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
        "name": "Anime47 Subtitle Enricher Repo",
        "iconUrl": "https://anime47.best/favicon.ico",
        "description": "Kho tiện ích mở rộng tự động cào và nhúng phụ đề Vietsub từ Anime47 vào CloudStream",
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
