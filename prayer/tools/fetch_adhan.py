#!/usr/bin/env python3
"""
بيجيب تسجيل أذان برخصة حرة من Wikimedia Commons ويحطه في res/raw/adhan.<ext>
وقت بناء الـ APK على GitHub Actions.

لو عايز صوت أذان معين: حط الملف بنفسك في
  prayer/android/app/src/main/res/raw/adhan.mp3
(واختياري adhan_fajr.mp3 لأذان الفجر) والسكربت ده مش هيعمل حاجة.
"""
import json
import os
import re
import sys
import urllib.parse
import urllib.request

API = "https://commons.wikimedia.org/w/api.php"
UA = "PrayerTimesTV-build/1.0 (GitHub Actions)"
EXT = {"audio/mpeg": "mp3", "audio/ogg": "ogg", "application/ogg": "ogg",
       "audio/wav": "wav", "audio/x-wav": "wav", "audio/x-flac": "flac", "audio/flac": "flac"}
GOOD = re.compile(r"adhan|azan|athan|adzan|أذان|اذان", re.I)
BAD = re.compile(r"iqama|iqamah|إقامة|dua|recitation|quran|sermon|khutba", re.I)


def get(params):
    url = API + "?" + urllib.parse.urlencode(dict(params, format="json"))
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def candidates():
    seen = set()
    for q in ("adhan filemime:audio", "azan filemime:audio", "athan filemime:audio", "أذان filemime:audio"):
        data = get({
            "action": "query", "generator": "search", "gsrsearch": q, "gsrnamespace": 6,
            "gsrlimit": 50, "prop": "imageinfo", "iiprop": "url|mime|size",
        })
        pages = sorted(data.get("query", {}).get("pages", {}).values(), key=lambda p: p.get("index", 0))
        for p in pages:
            title = p.get("title", "")
            if title in seen:
                continue
            seen.add(title)
            ii = (p.get("imageinfo") or [{}])[0]
            mime, size, url = ii.get("mime", ""), ii.get("size", 0), ii.get("url", "")
            if mime not in EXT or not url:
                continue
            # أذان كامل: مش أقل من 400KB ومش أكبر من 15MB
            if not (400_000 <= size <= 15_000_000):
                continue
            if not GOOD.search(title) or BAD.search(title):
                continue
            # mp3 الأول عشان شغال على كل الأجهزة
            yield (0 if EXT[mime] == "mp3" else 1, title, url, EXT[mime])


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    if any(f.startswith("adhan.") for f in os.listdir(out)):
        print("adhan file already present, skipping download")
        return
    found = sorted(candidates(), key=lambda c: c[0])
    for _, title, url, ext in found:
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=120) as r:
                data = r.read()
            with open(os.path.join(out, "adhan." + ext), "wb") as f:
                f.write(data)
            print("downloaded", title, len(data), "bytes")
            print("source: https://commons.wikimedia.org/wiki/" + urllib.parse.quote(title.replace(" ", "_")))
            return
        except Exception as e:  # جرب اللي بعده
            print("failed", title, e)
    print("no adhan audio found; the app will fall back to the device alarm sound")


if __name__ == "__main__":
    main()
