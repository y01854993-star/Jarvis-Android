1. ارفع المحتوى كله على repo جديد في GitHub.
2. Settings > Secrets and variables > Actions > New secret: اسمه GEMINI_API_KEY.
3. استبدل app/src/main/assets/jarvis-hud.html بالـ HUD بتاعك (نفس الـ IDs: whatsapp-status, call-status, log-screen).
4. عدّل app/src/main/assets/config.json (allowed_caller, pc_mac, contacts).
5. Actions > Build APK > Run workflow، وبعدها نزّل jarvis-apk من Artifacts.
