# اقتراحات مشاريع GitHub للتطوير

الأرقام جاية من GitHub API وقت الكتابة (الترخيص والنجوم وآخر تحديث والأرشفة). الحكم والتوصية رأيي بعد قراءة وصف كل مشروع، **ولسه ما جرّبتش أي واحد منهم داخل التطبيق**. أي مشروع قبل دمجه يمر على خط صائد المهارات (موافقتك + Pull Request).

| الفئة | المشروع | الترخيص | النجوم | آخر تحديث | الحالة | ليه ومتى |
|---|---|---|---|---|---|---|
| أدوات | [anthropics/skills](https://github.com/anthropics/skills) | غير محدد | 200180 | 2026-11-16 | نشط | نماذج Skills (مرجع). الترخيص مختلط: اقرأه قبل أي نسخ |
| أدوات | [modelcontextprotocol/servers](https://github.com/modelcontextprotocol/servers) | NOASSERTION | 92239 | 2026-11-25 | نشط | خوادم MCP جاهزة (ملفات، fetch، ذاكرة..) للابتوب أو السيرفر مش للموبايل |
| أدوات | [modelcontextprotocol/kotlin-sdk](https://github.com/modelcontextprotocol/kotlin-sdk) | NOASSERTION | 1492 | 2026-11-24 | نشط | MCP لـ Kotlin: يخلّي جارفيس يستخدم أي أداة MCP. **الترخيص ما اتحددش آلياً: راجعه يدوياً قبل الاستخدام** |
| تحكم | [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) | Apache-2.0 | 34624 | 2025-06-18 | نشط | بيدّي صلاحيات نظام بدون روت عبر Wireless debugging (تفعيله كل إعادة تشغيل). يفتح: wifi/بلوتوث/صلاحيات. **آخر تحديث قديم نسبياً** |
| تحكم | [droidrun/droidrun](https://github.com/droidrun/droidrun) | MIT | 10113 | 2026-11-24 | نشط | وكيل تحكم في أندرويد (مرجع أفكار) |
| ذاكرة | [mem0ai/mem0](https://github.com/mem0ai/mem0) | Apache-2.0 | 69447 | 2026-11-24 | نشط | طبقة ذاكرة (Python). مش لازمة للتطبيق الحالي |
| ذاكرة | [asg017/sqlite-vec](https://github.com/asg017/sqlite-vec) | Apache-2.0 | 8280 | 2026-05-18 | نشط | بحث متجهي جوه SQLite على الجهاز. بديل أخف لقاعدة الـ embeddings الحالية |
| ذكاء | [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) | MIT | 136198 | 2026-11-25 | نشط | موديل صغير أوفلاين على الجهاز كبديل لما النت يقع. بيحتاج موبايل قوي |
| ذكاء | [BerriAI/litellm](https://github.com/BerriAI/litellm) | NOASSERTION | 63866 | 2026-11-25 | نشط | موجّه موديلات كخدمة. **مش محتاجينه**: الـ Router المدمج كفاية |
| رؤية | [tesseract-ocr/tesseract](https://github.com/tesseract-ocr/tesseract) | Apache-2.0 | 77658 | 2026-11-24 | نشط | OCR أوفلاين بالعربي (بيانات ara). للنصوص على الشاشة من غير نت |
| رؤية | [adaptech-cz/Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) | Apache-2.0 | 951 | 2026-02-05 | نشط | تغليف Tesseract لأندرويد (الأنسب لدمجه) |
| صوت | [ggml-org/whisper.cpp](https://github.com/ggml-org/whisper.cpp) | MIT | 55504 | 2026-11-25 | نشط | Whisper على الجهاز (فيه مثال أندرويد). ممتاز للعربي لكنه تقيل على الموبايل والبطارية |
| صوت | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | Apache-2.0 | 16221 | 2026-11-24 | نشط | STT/TTS/VAD/كلمة تنبيه أوفلاين وعليه أمثلة أندرويد. أقوى مرشح للصوت المحلي. لازم تجربة دقته على المصري قبل الاعتماد |
| صوت | [alphacep/vosk-api](https://github.com/alphacep/vosk-api) | Apache-2.0 | 15285 | 2026-10-15 | نشط | STT أوفلاين خفيف، عربي موجود لكن جودته أقل من Whisper |
| صوت | [rhasspy/piper](https://github.com/rhasspy/piper) | MIT | 11335 | 2025-08-26 | مؤرشف | الأصلي **مؤرشف**: تجنّبه |
| صوت | [OHF-Voice/piper1-gpl](https://github.com/OHF-Voice/piper1-gpl) | GPL-3.0 | 6394 | 2026-11-24 | نشط | Piper الحالي. ترخيصه GPL: ما ينفعش يتدمج في التطبيق، يتشغّل كخدمة منفصلة لو احتجناه |
| صوت | [dscripka/openWakeWord](https://github.com/dscripka/openWakeWord) | Apache-2.0 | 2974 | 2025-12-30 | نشط | كلمة تنبيه حقيقية لـ "يا جارفيس". محتاج تدريب نموذج على GPU |
| لابتوب | [rustdesk/rustdesk](https://github.com/rustdesk/rustdesk) | AGPL-3.0 | 131371 | 2026-11-24 | نشط | تحكم عن بُعد. ترخيص AGPL: يُستخدم كتطبيق منفصل فقط |
| لابتوب | [tailscale/tailscale](https://github.com/tailscale/tailscale) | BSD-3-Clause | 39586 | 2026-11-25 | نشط | شبكة خاصة مشفّرة بين الموبايل واللابتوب من غير فتح منافذ. أساس Windows Companion الآمن |
| لابتوب | [KDE/kdeconnect-android](https://github.com/KDE/kdeconnect-android) | GPL-2.0 | 1527 | 2026-11-25 | نشط | نقل ملفات وإشعارات وتحكم. ترخيص GPL: تطبيق منفصل فقط |
| مستندات | [microsoft/markitdown](https://github.com/microsoft/markitdown) | MIT | 198354 | 2026-11-07 | نشط | تحويل PDF/Word/Excel/PowerPoint لـ Markdown. للخادم/اللابتوب، أساس RAG |
| منزل ذكي | [home-assistant/core](https://github.com/home-assistant/core) | Apache-2.0 | 92512 | 2026-11-25 | نشط | API رسمي (REST/WebSocket) بمفتاح طويل الأمد. أقصر طريق لإدارة المنزل الذكي: أمر `smart_home` واحد |
| منزل ذكي | [home-assistant/android](https://github.com/home-assistant/android) | Apache-2.0 | 4077 | 2026-11-24 | نشط | تطبيق Home Assistant الرسمي. مرجع لطريقة الاتصال |
| ويب | [browser-use/browser-use](https://github.com/browser-use/browser-use) | MIT | 121985 | 2026-11-24 | نشط | وكيل متصفح (Python). يشتغل على لابتوب/سيرفر مش على الموبايل |
| ويب | [microsoft/playwright](https://github.com/microsoft/playwright) | Apache-2.0 | 101023 | 2026-11-25 | نشط | أتمتة متصفح مستقرة. نفس ملاحظة المكان (لابتوب/سيرفر) |

## ترتيب مقترح (الأكثر فايدة مقابل الجهد)
1. **منزل ذكي عبر Home Assistant API:** أمر `smart_home` (تشغيل/إطفاء/مشاهد) بمفتاح تحطه إنت. ما محتاج مكتبة، HTTP بس. (محتاج Home Assistant شغّال عندك.)
2. **كلمة التنبيه الحقيقية:** openWakeWord + نموذج "يا جارفيس" (تدريب مرة واحدة على GPU، وأنا أجهّز السكربت).
3. **الصوت الأوفلاين:** تجربة sherpa-onnx أو whisper.cpp على صوتك المصري قبل القرار، لأن الجودة هي المقياس مش الحجم.
4. **اللابتوب:** Tailscale + وكيل صغير على الويندوز بقائمة أوامر مسموحة وموافقة على الخطر (تصميم مفصّل لما تقول نبدأ).
5. **بحث ويب حقيقي:** أداة بحث ومقارنة مصادر في الـ Agent (الأغلب على سيرفر/لابتوب مش الموبايل).
6. **OCR أوفلاين بالعربي:** Tesseract4Android للنصوص اللي مش بتطلع من خدمة الوصول.
7. **Shizuku (اختياري):** لو عايز تحكم أعمق في إعدادات النظام، مع شرح إنه محتاج تفعيل يدوي وإن صيانته أقل.

## مش هنستخدمه
LiteLLM وmem0 (مش محتاجينهم)، وأي مشروع ترخيصه GPL/AGPL داخل التطبيق نفسه (RustDesk وKDE Connect وPiper الحالي: بس كتطبيقات/خدمات منفصلة).
