# JARVIS Autonomous Engineer

نظام بيراقب المشاكل، يشخّصها، يجهّز إصلاح على branch مستقل، يختبره، ويفتح Pull Request. **مفيش أي دمج من غير موافقتك.**

## الدورة
1. **اكتشاف:** التطبيق بيبعت تقرير المشاكل (كراشات، أخطاء WebView/JS، فشل الـ Accessibility، `diagnose`) كـ Issue بـ label `jarvis-problem`. وفشل البناء بيتحول لـ Issue تلقائياً (`build-monitor.yml`).
2. **تشخيص:** `autofix.yml` بيقرأ الـ Issue ويطلب من النموذج (Claude أولاً، Gemini بديل) السبب الجذري والتصنيف: `confirmed_bug` / `possible_bug` / `environment` / `configuration` / `api_failure` / `permission`. غير الأول والتاني **مش بيتعدّل فيهم كود**، بيتشرحوا بس.
3. **إصلاح:** تعديلات صغيرة على branch `autofix/issue-N` (مش main أبداً).
4. **اختبار:** بناء APK + Unit tests + Lint (للعلم) + تحقق: الـ package id ما اتغيرش، مفيش صلاحيات اتشالت، الملفات الأساسية والـ Accessibility في المانيفست موجودين.
5. **موافقة:** لو كله نجح بيتفتح Pull Request فيه: رقم المشكلة، السبب، الحل، الملفات، الـ diff، الأمان، نتايج الاختبار والبناء، المخاطر، خطة التراجع.
6. **قرارك من GitHub على الموبايل:**
   - **APPROVE:** Approve ثم **Merge**. الدمج بيبني الـ APK المستقر (`latest`).
   - **REJECT:** Close للـ PR.
   - **REQUEST CHANGES:** Review بنوع Request changes وملاحظتك، والمهندس بيحاول تاني.
   - **INVESTIGATE MORE:** اكتب على الـ Issue تعليق `/fix <اللي عايزه>`.
7. **حالة المشكلة:** labels على الـ Issue: `status:investigating`، `status:awaiting-approval`، `status:resolved`، `status:rejected`، `status:failed`، `status:no-code-fix`.

من التطبيق: قول "جارفيس شوف المشاكل" (`diagnose`)، "اقرأ الإصلاحات" (`fixes_list`)، "ادمج الإصلاح رقم N" (`fixes_apply`، بتأكيد صوتي، وبيدمج PR تبع البوت بس).

## الإعداد من الموبايل (مرة واحدة)
1. ادمج الـ Pull Request اللي فيه الـ workflows في `main` (الـ workflows بتشتغل من `main` بس).
2. Settings ← Secrets and variables ← Actions: ضيف `ANTHROPIC_API_KEY` (النموذج الأساسي، اختياري) و`GEMINI_API_KEY` (بديل، موجود لو بتبني بيه). اسم نموذج Claude قابل للتغيير بمتغير `AUTOFIX_CLAUDE_MODEL`.
3. Settings ← Actions ← General ← Workflow permissions: فعّل **Allow GitHub Actions to create and approve pull requests**.
4. في التطبيق: توكن GitHub (Fine-grained، على ريبو `Jarvis-Android` فقط، صلاحيات Issues وPull requests: Read & write، وContents: Read) عشان التقارير ولوحة ENGINEER تشتغل.
5. (اختياري) Settings ← Rules: احمِ `main` ويتطلب Pull Request. المستودعات الخاصة المجانية قد لا تدعمه.

## الأمان
- محتوى التقارير **بيانات غير موثوقة**: بيتشال منه أي شكل مفتاح، النموذج متعلّم ما يتبعش تعليمات جواه، ومفيش أي أمر منه بيتنفذ.
- بيشتغل فقط على Issues كاتبها صاحب الريبو (أو تقارير البناء الآلية). التعليقات والـ reviews من صاحب الريبو بس.
- النموذج بيعدّل ملفات `app/src/main` (kt, java, xml, js, html, css) بنص قديم/جديد حرفي. **مفيش تعديل في الـ workflows أو Gradle أو الصلاحيات أو التوقيع**: دي بتتعدّل بيدك.
- صلاحيات الـ workflows بأقل قدر، والمفاتيح من Secrets بس.
- مفيش تثبيت تلقائي ولا نشر. الـ APK التجريبي بيتحمّل بإيدك من Artifacts.
- سجل المراجعة: تعليقات الـ Issue + الـ PR + سجل Actions.
- الخصوصية: التقارير فيها نصوص أخطاء وأماكنها فقط (أرقام وإيميلات بتتخفى). مفيش رسائل ولا محتوى شخصي.

## اللي اتنفذ واللي لأ
| البند | الحالة |
|---|---|
| تقارير المشاكل (ملف المشاكل ← Issues) | منفذ |
| رصد الكراشات وأخطاء JS | منفذ (مجرّب بالبناء بس) |
| رصد فشل البناء | منفذ، لسه ما اشتغلش فعلياً |
| تشخيص + Claude + Gemini بديل | منفذ. Claude محتاج `ANTHROPIC_API_KEY` منك |
| PR + بناء + اختبارات + تحقق APK | منفذ |
| لوحة ENGINEER في الـ HUD | منفذ |
| GPT للمراجعة المستقلة / Groq للتصنيف | **غير منفذ** |
| Lint | بيشتغل للعلم ومش بيمنع الـ PR |
| Regression tests تلقائية | غير منفذ (المشروع مفيهوش اختبارات حالياً) |
