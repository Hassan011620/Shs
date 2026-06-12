# طريقة البناء — حل مشكلة gradle-wrapper.jar

## المشكلة
ملف `gradle/wrapper/gradle-wrapper.jar` لا يمكن توليده بدون بيئة Java كاملة.
الملف الموجود هو stub يجب استبداله.

---

## الحل 1 — Android Studio (الأسهل) ✅

```
1. افتح Android Studio
2. File → Open → اختر مجلد NAMPlayer2
3. Android Studio سيولّد gradle-wrapper.jar تلقائياً
4. Build → Build APK(s)
```

---

## الحل 2 — سكريبت تلقائي (Linux/macOS/WSL)

```bash
cd NAMPlayer2
chmod +x setup_and_build.sh
./setup_and_build.sh
```

---

## الحل 3 — يدوي (إذا عندك Gradle مثبّت)

```bash
cd NAMPlayer2
gradle wrapper --gradle-version 8.4
chmod +x gradlew
./gradlew assembleDebug
```

تثبيت Gradle: https://gradle.org/install/

---

## الحل 4 — GitHub Actions (بناء سحابي مجاني)

```bash
# ارفع المشروع على GitHub
git init
git add .
git commit -m "NAMPlayer"
git remote add origin https://github.com/USERNAME/NAMPlayer.git
git push -u origin main
```

ثم اذهب إلى:
```
GitHub → Actions → Build APK → Run workflow
```

الـ APK سيكون جاهزاً للتحميل في Artifacts خلال ~5 دقائق.

---

## ملاحظة عن gradle-wrapper.jar

الملف ثابت لكل مشاريع Gradle 8.4:
- الحجم: ~59 KB
- SHA-256: `3e48873b03ca176e3cf5ccc3483e37223c16d577b9aae4b4d0f84c577cf61dd5`
- يمكن نسخه من أي مشروع Android آخر يستخدم Gradle 8.4

```bash
# نسخ من مشروع آخر
cp ~/OtherProject/gradle/wrapper/gradle-wrapper.jar \
   NAMPlayer2/gradle/wrapper/gradle-wrapper.jar
```
