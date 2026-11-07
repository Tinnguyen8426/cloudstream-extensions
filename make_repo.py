#!/usr/bin/env python3
import os
import json
import hashlib

REPO_OWNER = "Tinnguyen8426"
REPO_NAME = "cloudstream-extensions"
BASE_URL = f"https://raw.githubusercontent.com/{REPO_OWNER}/{REPO_NAME}/builds"

ROOT_DIR = os.path.dirname(os.path.abspath(__file__))

def compute_sha256(filepath):
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return "sha256-" + h.hexdigest()

plugins = [
    {
        "url": f"{BASE_URL}/StremioSubEnricher.cs3",
        "status": 1,
        "version": 1,
        "name": "Stremio Subtitle Enricher",
        "internalName": "StremioSubEnricher",
        "authors": [
            "Stremio Subtitle Enricher"
        ],
        "description": "Tự động trích xuất và nhúng phụ đề đa ngôn ngữ từ Kitsu, OpenSubtitles v3 và Cinemeta vào CloudStream",
        "fileSize": os.path.getsize(os.path.join(ROOT_DIR, "StremioSubtitleEnricher", "dist", "StremioSubEnricher.cs3")),
        "repositoryUrl": f"https://github.com/{REPO_OWNER}/{REPO_NAME}",
        "language": "en",
        "tvTypes": [
            "Anime",
            "AnimeMovie",
            "OVA",
            "Movie",
            "TvSeries"
        ],
        "iconUrl": "https://stremio.com/favicon.ico",
        "apiVersion": 1,
        "fileHash": compute_sha256(os.path.join(ROOT_DIR, "StremioSubtitleEnricher", "dist", "StremioSubEnricher.cs3"))
    },
    {
        "url": f"{BASE_URL}/KitsunekkoEnricher.cs3",
        "status": 1,
        "version": 1,
        "name": "Kitsunekko Japanese Subtitle Enricher",
        "internalName": "KitsunekkoEnricher",
        "authors": [
            "Kitsunekko Japanese Subtitle Enricher"
        ],
        "description": "Tự động trích xuất và nhúng phụ đề tiếng Nhật (.ass, .srt, .zip) từ Kitsunekko vào CloudStream",
        "fileSize": os.path.getsize(os.path.join(ROOT_DIR, "KitsunekkoSubtitleEnricher", "dist", "KitsunekkoEnricher.cs3")),
        "repositoryUrl": f"https://github.com/{REPO_OWNER}/{REPO_NAME}",
        "language": "ja",
        "tvTypes": [
            "Anime",
            "AnimeMovie",
            "OVA"
        ],
        "iconUrl": "https://kitsunekko.net/favicon.ico",
        "apiVersion": 1,
        "fileHash": compute_sha256(os.path.join(ROOT_DIR, "KitsunekkoSubtitleEnricher", "dist", "KitsunekkoEnricher.cs3"))
    },
    {
        "url": f"{BASE_URL}/Anime47Enricher.cs3",
        "status": 1,
        "version": 3,
        "name": "Anime47 Subtitle Enricher",
        "internalName": "Anime47Enricher",
        "authors": [
            "Anime47 Enricher"
        ],
        "description": "Tự động trích xuất và nhúng phụ đề Tiếng Việt (softsub) từ Anime47 vào CloudStream (tương thích hoàn hảo với Torrentio, TorraStream, StremioX, AnimePahe...)",
        "fileSize": os.path.getsize(os.path.join(ROOT_DIR, "Anime47SubtitleEnricher", "dist", "Anime47Enricher.cs3")),
        "repositoryUrl": f"https://github.com/{REPO_OWNER}/{REPO_NAME}",
        "language": "vi",
        "tvTypes": [
            "Anime",
            "AnimeMovie",
            "OVA"
        ],
        "iconUrl": "https://anime47.best/favicon.ico",
        "apiVersion": 1,
        "fileHash": compute_sha256(os.path.join(ROOT_DIR, "Anime47SubtitleEnricher", "dist", "Anime47Enricher.cs3"))
    },
    {
        "url": f"{BASE_URL}/AiSubtitleTranslator.cs3",
        "status": 1,
        "version": 1,
        "name": "AI Subtitle Translator",
        "internalName": "AiSubtitleTranslator",
        "authors": [
            "AI Subtitle Translator"
        ],
        "description": "Dịch phụ đề phim tự động bằng AI (Gemini, OpenAI, Claude) trực tiếp trên CloudStream với độ trễ 0ms và hỗ trợ xử lý ngữ cảnh đa ngôn ngữ",
        "fileSize": os.path.getsize(os.path.join(ROOT_DIR, "AiSubtitleTranslator", "dist", "AiSubtitleTranslator.cs3")),
        "repositoryUrl": f"https://github.com/{REPO_OWNER}/{REPO_NAME}",
        "language": "vi",
        "tvTypes": [
            "Anime",
            "AnimeMovie",
            "OVA",
            "Movie",
            "TvSeries",
            "AsianDrama",
            "Live"
        ],
        "iconUrl": "https://translate.google.com/favicon.ico",
        "apiVersion": 1,
        "fileHash": compute_sha256(os.path.join(ROOT_DIR, "AiSubtitleTranslator", "dist", "AiSubtitleTranslator.cs3"))
    }
]

repo_data = {
    "name": "CloudStream Subtitle Enrichers",
    "iconUrl": "https://raw.githubusercontent.com/recloudstream/cloudstream/master/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png",
    "description": "Kho tiện ích mở rộng tự động cào và nhúng phụ đề đa ngôn ngữ (Stremio, Kitsunekko, Anime47, AiSubtitleTranslator) vào CloudStream",
    "manifestVersion": 1,
    "pluginLists": [
        f"{BASE_URL}/plugins.json"
    ]
}

# Write root repo.json & plugins.json
with open(os.path.join(ROOT_DIR, "plugins.json"), "w", encoding="utf-8") as f:
    json.dump(plugins, f, indent=4, ensure_ascii=False)

with open(os.path.join(ROOT_DIR, "repo.json"), "w", encoding="utf-8") as f:
    json.dump(repo_data, f, indent=4, ensure_ascii=False)

# Update subfolder dist jsons
for idx, p in enumerate(plugins):
    folder_map = {
        0: "StremioSubtitleEnricher",
        1: "KitsunekkoSubtitleEnricher",
        2: "Anime47SubtitleEnricher",
        3: "AiSubtitleTranslator"
    }
    fdir = os.path.join(ROOT_DIR, folder_map[idx], "dist")
    with open(os.path.join(fdir, "plugins.json"), "w", encoding="utf-8") as f:
        json.dump([p], f, indent=4, ensure_ascii=False)
    sub_repo = {
        "name": f"{p['name']} Repo",
        "iconUrl": p["iconUrl"],
        "description": p["description"],
        "manifestVersion": 1,
        "pluginLists": [
            f"{BASE_URL}/plugins.json"
        ]
    }
    with open(os.path.join(fdir, "repo.json"), "w", encoding="utf-8") as f:
        json.dump(sub_repo, f, indent=4, ensure_ascii=False)

print("[+] Successfully generated plugins.json and repo.json for all extensions!")

