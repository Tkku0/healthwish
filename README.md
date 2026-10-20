# HealthWish 💚 - Personal Couple Diet + Sleep App
Personal use only. Android app + PWA web app, same Firestore data.

## 🌐 Web App (PWA)
**[💚 Open HealthWish Web](https://shubhamkothar.github.io/healthwish/)**

Chrome/Android → "Add to Home Screen" (📲 Install banner bhi aayega) · iOS Safari → Share → Add to Home Screen. Lifetime free (GitHub Pages). AI keysite ke Settings me save karo (browser me hi rehti hai).

## 📥 Direct Download (Final App)

**[⬇️ Download HealthWish.apk (v1.0 Final)](https://github.com/Shubhamkothar/healthwish/releases/download/v1.0-final/HealthWish.apk)**

Phone me kholo > Install > Tkku/Shumu select karo > login karo. Bas!

## Kya-kya ready hai
- Today: breakfast/lunch/dinner/snacks + water + photo note ready
- Rituals: Morning Walk, Exercise, Meditation, Evening Walk + `+ Custom Task` (sirf uss din ke liye)
- Sleep: wakeup + sleep time + `neend kaisi thi` emoji
- Day close: `day kaisa raha` emoji + one-line note
- Me / GF live tabs (Firestore realtime, 2 sec sync + offline cache)
- Lock: Biometric + 4-digit PIN (EncryptedSharedPreferences)
- Dark mode default (rose cozy theme)
- Auto backup: daily encrypted ZIP (password tumhara, bina password ke open nahi)
- Export: full CSV for Excel/Sheets summary

## Tumhe ab sirf ye 3 kaam karne hai

### 1. Firebase setup (5 min, ek baar)
1. console.firebase.google.com -> New project `healthwish`
2. Build > Authentication > Sign-in method > Email/Password > Enable
3. Authentication > Users > Add user: tumhara email + GF ka email (2 user)
4. Build > Firestore Database > Create database > Start in production mode
5. Firestore > Rules tab > `firestore.rules` file ka content paste karke Publish
6. Project Settings > Android app add: package `com.healthwish.app` > `google-services.json` download karke `app/` folder me rakho

### 2. Couple pairing (2 min, ek baar)
1. App me dono login karo
2. Kisi ek phone me Settings/debug se apna UID dekho (Logcat me ya Firebase Users me)
3. Firestore > `couples/LOVE1` document banao:
```
members: [ "UID_TUMHARA", "UID_GF" ]
```

### 3. Run + Share
1. Android Studio Ladybug+ me `HealthWish` folder open karo > Sync > Run (arm64 phone pe)
2. APK share: `./gradlew assembleDebug` -> `app/build/outputs/apk/debug/app-debug.apk` WhatsApp se GF ko bhejo
3. Settings me jaake: PIN set karo, Backup password set karo (ex: tum dono ki anniversary), Dark mode ON rakho

Backup password bhoolna mat — wahi encryption key hai. Bhool gaye to backup recover nahi hoga.
