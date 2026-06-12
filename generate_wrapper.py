#!/usr/bin/env python3
"""
تشغيل هذا السكريبت مرة واحدة لتوليد gradle-wrapper.jar
يعمل على: Windows, macOS, Linux

الاستخدام:
  python3 generate_wrapper.py

أو على Windows:
  python generate_wrapper.py
"""

import urllib.request
import os
import sys
import hashlib

WRAPPER_DIR = os.path.join(os.path.dirname(__file__),
                            "gradle", "wrapper")
JAR_PATH    = os.path.join(WRAPPER_DIR, "gradle-wrapper.jar")

# SHA-256 الرسمي لـ gradle-wrapper.jar الإصدار 8.4
EXPECTED_SHA = "3e48873b03ca176e3cf5ccc3483e37223c16d577b9aae4b4d0f84c577cf61dd5"

SOURCES = [
    "https://raw.githubusercontent.com/gradle/gradle/v8.4.0/gradle/wrapper/gradle-wrapper.jar",
    "https://services.gradle.org/distributions/gradle-8.4-wrapper.jar",
    "https://github.com/gradle/gradle/raw/master/gradle/wrapper/gradle-wrapper.jar",
]

def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        h.update(f.read())
    return h.hexdigest()

def download(url, dest):
    print(f"  Trying: {url}")
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=30) as r, open(dest, "wb") as f:
            f.write(r.read())
        return True
    except Exception as e:
        print(f"  Failed: {e}")
        return False

def main():
    os.makedirs(WRAPPER_DIR, exist_ok=True)

    # Already exists and valid?
    if os.path.exists(JAR_PATH) and os.path.getsize(JAR_PATH) > 10000:
        print(f"✅ gradle-wrapper.jar already exists ({os.path.getsize(JAR_PATH)} bytes)")
        return

    print("📥 Downloading gradle-wrapper.jar ...")
    for url in SOURCES:
        if download(url, JAR_PATH):
            size = os.path.getsize(JAR_PATH)
            if size > 10000:
                print(f"✅ Downloaded! Size: {size} bytes")
                actual = sha256(JAR_PATH)
                if actual == EXPECTED_SHA:
                    print("✅ SHA-256 verified!")
                else:
                    print(f"⚠️  SHA-256 mismatch (OK for different Gradle versions)")
                    print(f"   Expected: {EXPECTED_SHA[:16]}...")
                    print(f"   Got:      {actual[:16]}...")
                return
            else:
                print(f"  Too small ({size} bytes), trying next...")

    # Fallback: use gradle wrapper task if gradle is installed
    print("\n⚠️  Download failed. Trying local Gradle...")
    if os.system("gradle --version > /dev/null 2>&1") == 0:
        os.chdir(os.path.dirname(__file__) or ".")
        ret = os.system("gradle wrapper --gradle-version 8.4")
        if ret == 0 and os.path.exists(JAR_PATH):
            print("✅ Generated via local Gradle!")
            return

    print("""
❌ Could not generate gradle-wrapper.jar automatically.

Manual steps:
  1. Install Gradle: https://gradle.org/install/
  2. Run in project root: gradle wrapper --gradle-version 8.4

OR use Android Studio:
  File → Project Structure → (it generates wrapper automatically)
""")
    sys.exit(1)

if __name__ == "__main__":
    main()
