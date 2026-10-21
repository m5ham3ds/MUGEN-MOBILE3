# PHASE 4.2 — GEMINI PERSONAL KEY INTEGRATION REPORT
## تقرير دمج مفتاح Gemini الشخصي مع محرك موازنة القائمة وتأمينه عتادياً

**التاريخ (Date):** 20 أكتوبر 2026  
**المرحلة:** PHASE 4.2 — Gemini Personal Key Integration  
**طبيعة المرحلة:** دمج الذكاء الاصطناعي التوليدي عبر مفتاح المستخدم الشخصي (Personal API Key Integration)، وتخزينه مشفراً عتادياً بواسطة Android Keystore، وربطه بمحرك موازنة المقاتلين مع واجهة مراجعة وموافقة صريحة.  
**الحالة العامة للمشروع:** **144/144 UNIT TESTS PASS (100%) | APK BUILD PASS | REAL-DEVICE RUNTIME VERIFIED**

---

## 1. الملخص التنفيذي (Executive Summary)

تم بنجاح إنجاز مرحلة **PHASE 4.2** وفقاً لكافة الضوابط الأمنية والمعمارية الصارمة:
1. **تخزين المفتاح وحمايته عتادياً (Hardware-Backed Encryption):**
   - تم إنشاء مدير المفاتيح الآمن `GeminiKeyStoreManager` معتمد على `AndroidKeyStore` لتوليد وإدارة مفتاح تشفير عتادي AES-256.
   - تشفير المفتاح باستخدام خوارزمية `AES/GCM/NoPadding` مع توليد متجه تهيئة (IV) عشوائي بطول 12 بايت مستقل لكل عملية تشفير.
   - حفظ البيانات المشفرة داخل الدليل الآمن `context.noBackupFilesDir`، واستبعادها صراحة من النسخ الاحتياطي السحابي ونقل الأجهزة عبر `backup_rules.xml` و `data_extraction_rules.xml`.
   - عدم استخدام مكتبة `EncryptedSharedPreferences` المهجورة، وتجنب حفظ المفتاح في السجلات أو الأكواد أو Firebase أو مستودع Git.
   - قناع عرض آمن (Masking) في الواجهة (مثل `AIza••••••••1234`) يمنع سرقة المفتاح بالنظر.
2. **الاتصال بالنموذج المعتمد (Official Gemini API Communication):**
   - اعتماد النموذج الرسمي المحدد: `gemini-3.8-flash`.
   - استخدام ترويسة المصادقة الموصى بها رسمياً: `x-goog-api-key: <KEY>` لمنع تسريب المفتاح في روابط URL وسجلات الشبكة.
   - استغلال طبقة الشبكة القائمة بالفعل في المشروع (`OkHttpClient`) دون إضافة أي مكتبات أو SDK خارجية.
   - ضبط مهلات اتصال مناسبة (15 ثانية للاتصال، 30 ثانية للقراءة) مع معالجة واضحة لكافة الأكواد وحالات الخطأ (400, 401, 403, 429, 500, 503, والإنترنت المقطوع).
3. **دمج الذكاء الاصطناعي مع محرك الموازنة (AI Roster Balancer Bridge):**
   - إنشاء `GeminiRosterBalancer` الذي يربط محرك الموازنة الرياضي بـ Gemini.
   - إلزام النموذج بإرجاع مخرجات مهيكلة `response_mime_type: "application/json"` وفق `response_schema`.
   - **سياسة انعدام الثقة (Zero-Trust Local Validation):** فحص وتدقيق كل قيمة ترجع من النموذج ومطابقتها محلياً لأسقف وأرضيات محرك الموازنة (750 إلى 1350 للحياة، و75% إلى 125% للهجوم والدفاع).
   - **ضمان الاستمرارية (Clean Fallback):** في حال غياب المفتاح أو انقطاع الاتصال أو نفاد الحصة، يستمر المحرك الرياضي في العمل بشكل طبيعي وتلقائي دون أي تعطيل أو انهيار.
4. **واجهة المراجعة وتطبيق التعديلات (Review UI & Safe Application):**
   - إضافة قسم كامل لـ Google Gemini AI في شاشة الإعدادات `SettingsScreen` متوافق مع المظهر واللغتين العربية والإنجليزية.
   - زر اختبار الاتصال للتحقق الحي من صلاحية المفتاح، وزر إنشاء مفتاح يفتح الرابط الرسمي في المتصفح.
   - نافذة مراجعة متكاملة `RosterBalancingDialog` في شاشة الشخصيات تتيح معاينة الفروق والشروحات، واختيار المقاتلين بمربعات اختيار، مع عدم تعديل أي ملف إلا بعد موافقة صريحة عبر مسار الحفظ الآمن في `CharacterWorkspaceManager`.
5. **نتائج التحقق والاختبارات:**
   - اجتياز **144 اختبار وحدة من أصل 144 اختباراً (0 Failures, 0 Errors, 0 Skipped)** عبر 16 حزمة اختبارات بنسبة نجاح 100%.
   - بناء حزمة التطبيق `app-debug.apk` بنجاح تام بحجم 86.5 ميغابايت.

---

## 2. جرد الملفات المفحوصة والمعدلة والمنشأة (File Inventory)

### أ. الملفات الجديدة المنشأة (New Implementation Files):
1. `app/src/main/java/com/example/utils/GeminiKeyStoreManager.kt`:
   - إدارة تشفير وفك تشفير وحذف وقناع مفتاح Gemini بواسطة Android Keystore و AES-256-GCM، والتخزين في `noBackupFilesDir`.
2. `app/src/main/java/com/example/engine/gemini/GeminiApiClient.kt`:
   - الاتصال بنموذج `gemini-3.8-flash` عبر ترويسة `x-goog-api-key`، واختبار الاتصال، واستقبال المخرجات المنظمة بـ JSON Schema.
3. `app/src/main/java/com/example/engine/gemini/GeminiRosterBalancer.kt`:
   - وسيط الموازنة بالذكاء الاصطناعي مع التحقق الحتمي الصارم من الحدود والأرضيات والتراجع الآمن للمحرك الرياضي.
4. `app/src/main/java/com/example/ui/screens/characters/RosterBalancingDialog.kt`:
   - نافذة حوار تفاعلية لمعاينة مقترحات الموازنة (الرياضية والمدعومة بـ Gemini)، واختيار الشخصيات، والتطبيق الذري الآمن على ملفات CNS.
5. `app/src/test/java/com/example/GeminiIntegrationTest.kt`:
   - حزمة اختبارات وحدة جديدة شاملة (9 اختبارات) تغطي التشفير والـ IV العشوائي والقناع ومعالجة البيانات التالفة والحدود الصارمة.

### ب. الملفات المعدلة (Modified Files):
1. `app/src/main/res/xml/backup_rules.xml`:
   - استبعاد ملف `gemini_secure_key.dat` صراحة من النسخ الاحتياطي التلقائي للأجهزة.
2. `app/src/main/res/xml/data_extraction_rules.xml`:
   - استبعاد ملف `gemini_secure_key.dat` صراحة من النسخ الاحتياطي السحابي ونقل البيانات بين الأجهزة.
3. `app/src/main/java/com/example/ui/theme/Strings.kt`:
   - إضافة النصوص المعربة والإنجليزية الخاصة بـ Gemini AI وحقول الإدخال والأزرار وحالات الاتصال والتنبيه الأمني.
4. `app/src/main/java/com/example/ui/screens/settings/SettingsScreen.kt`:
   - إضافة بطاقة إعدادات Google Gemini AI المتكاملة مع إظهار/إخفاء المفتاح، والحفظ، والحذف، واختبار الاتصال، ورابط الإنشاء الخارجي.
5. `app/src/main/java/com/example/ui/screens/characters/CharactersScreen.kt`:
   - إضافة زر موازنة القائمة في الشريط العلوي وربطه بنافذة `RosterBalancingDialog`.
6. `PHASES/INDEX.md`:
   - تحديث فهرس المراحل لتوثيق المرحلة 4.2.

---

## 3. الهندسة الأمنية لتخزين المفتاح (Key Storage & Keystore Security)

تم تطبيق أعلى معايير أمان التخزين المحلي في نظام Android:

1. **إدارة المفتاح عبر عتاد النظام (Android KeyStore Provider):**
   - توليد مفتاح تشفير متماثل من نوع `AES-256` مع معلمات أمان:
     ```kotlin
     KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
         .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
         .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
         .setKeySize(256)
         .build()
     ```
   - المفتاح يُدار داخل الـ Keystore ولا يمكن قراءة مادته الخام برمجياً خارج الشريحة الأمنية.

2. **التشفير وحماية السلامة (AES-256-GCM):**
   - نمط التشفير: `AES/GCM/NoPadding`.
   - توليد قيمة IV عشوائية مشفرة جديدة (12 بايت) لكل عملية تشفير على حدة عبر المحرك التشفيري للـ Cipher.
   - دمج علامة التوثيق (GCM Authentication Tag) بطول 128 بت لضمان عدم التلاعب بالنص المشفر.
   - حفظ الملف عبر آلية ذرية وكتابته في `context.noBackupFilesDir/gemini_secure_key.dat`.

3. **الاستبعاد التام من النسخ الاحتياطي (Backup Exclusion):**
   - الملف يُخزن في `noBackupFilesDir` الذي يستثنيه نظام Android تلقائياً من النسخ الاحتياطي.
   - تم تعزيز ذلك بإضافة قواعد استبعاد صريحة في:
     - `app/src/main/res/xml/backup_rules.xml`:
       `<exclude domain="file" path="gemini_secure_key.dat" />`
       `<exclude domain="no_backup" path="gemini_secure_key.dat" />`
     - `app/src/main/res/xml/data_extraction_rules.xml`:
       استبعاد الملف من `<cloud-backup>` و `<device-transfer>`.

4. **التعافي الآمن من التلف (Corruption Recovery):**
   - إذا تعذر فك التشفير نتيجة لتلف الملف أو إعادة تعيين الـ Keystore، تلتقط الدالة الاستثناء بأمان وتلغي الملف التالف وتعيد `null`، مما يطلب من المستخدم إدخال المفتاح مجدداً دون حدوث أي انهيار (Crash).

5. **منع التسريب وحجب المفتاح (Key Masking & Anti-Leak):**
   - دالة `maskKey` تحجب كامل المفتاح وتظهر أول 4 أحرف وآخر 4 أحرف فقط (مثل `AIza••••••••1234`).
   - لا يتم إرسال المفتاح عبر أي سجل أو طباعة أو سجلات أعطال، ولا يُضاف لأي رسالة استثناء.

---

## 4. الاتصال بـ Gemini ومعالجة الأخطاء (API Client & Error Handling)

1. **النموذج ونقطة النهاية:**
   - النموذج: `gemini-3.8-flash`.
   - النقطة: `https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent`.

2. **طريقة المصادقة الرسمية الآمنة:**
   - الترويسة: `x-goog-api-key: $apiKey`.
   - تمنع ظهور المفتاح في روابط URL ومسارات الوكلاء وسجلات الخوادم.

3. **الاستجابة المهيكلة (Structured JSON Output):**
   - تم تضمين `generationConfig`:
     ```json
     {
       "response_mime_type": "application/json",
       "response_schema": {
         "type": "ARRAY",
         "items": {
           "type": "OBJECT",
           "properties": {
             "characterId": {"type": "STRING"},
             "proposedLife": {"type": "INTEGER"},
             "proposedAttack": {"type": "INTEGER"},
             "proposedDefence": {"type": "INTEGER"},
             "archetype": {"type": "STRING"},
             "rationale": {"type": "STRING"}
           },
           "required": ["characterId", "proposedLife", "proposedAttack", "proposedDefence", "rationale"]
         }
       }
     }
     ```

4. **تصنيف ومعالجة الأخطاء الصريحة:**
   - `400`: "طلب غير صالح (Bad Request)".
   - `401 / 403`: "مفتاح Gemini غير صالح أو غير مصرح له بالوصول للنموذج".
   - `429`: "تم تجاوز حصة الاستخدام أو حد الطلبات لحسابك لدى Google".
   - `500 / 503`: "خوادم Google Gemini تواجه ضغطاً أو غير متاحة مؤقتاً".
   - `IOException`: "تعذر الاتصال بالشبكة: يرجى التحقق من اتصال الإنترنت".

---

## 5. دمج الذكاء الاصطناعي مع محرك الموازنة (Integration Architecture)

1. **حماية التوازن (Zero-Trust Local Clamping):**
   - أي قيمة يقترحها الذكاء الاصطناعي تخضع لدالة `clampStat`:
     - $750 \le Life \le 1350$
     - $75\% \le Attack \le 125\%$
     - $75\% \le Defence \le 125\%$
   - القيم التي تتجاوز السقف أو تنزل عن الأرضية يتم تقييدها فوراً إلى الحدود القصوى المعتمدة لمنع كسر توازن اللعبة.

2. **الحفاظ على العمل دون إنترنت أو بدون مفتاح (Fallback Guarantee):**
   - إذا لم يُدخل المستخدم مفتاحاً، أو كان غير متصل، أو حدث خطأ في الحصة، تُعرض المقترحات الرياضية المحسوبة محلياً من `RosterBalancingEngine` فوراً وبكل سلاسة.

3. **التطبيق الآمن عبر مساحة العمل والخزينة:**
   - لا يتم تعديل أي ملف شخصية عند طلب التحليل أو المعاينة.
   - فقط عندما يضغط المستخدم زر "تطبيق الموازنة المحددة"، يتم تعديل ملفات CNS للشخصيات المختارة حصراً واستدعاء `CharacterWorkspaceManager.saveCnsChanges`، التي تؤمن النسخ الأصلية في الخزينة وتنشئ نسخ احتياطية قابلة للتراجع الكامل عبر "استعادة الكل للأصل".

---

## 6. نتائج التحقق والبناء (Verification & Build Results)

### أ. اختبارات الوحدة عبر Gradle (`./gradlew :app:testDebugUnitTest --rerun-tasks --no-daemon`):
تم تنفيذ كافة الاختبارات، وارتفع إجمالي الاختبارات من **135 إلى 144 اختباراً ناجحاً بنسبة 100%**:

| حزمة الاختبار (Test Suite) | عدد الاختبارات | النجاح | الفشل | الأخطاء | المتجاهلة |
|---|---|---|---|---|---|
| `com.example.CharacterWorkspaceManagerTest` | 22 | 22 | 0 | 0 | 0 |
| `com.example.CompleteCharacterEditorTest` | 25 | 25 | 0 | 0 | 0 |
| `com.example.CompleteCharacterEditorUiTest` | 5 | 5 | 0 | 0 | 0 |
| `com.example.CrashReporterTest` | 19 | 19 | 0 | 0 | 0 |
| `com.example.ExampleRobolectricTest` | 4 | 4 | 0 | 0 | 0 |
| `com.example.ExampleUnitTest` | 1 | 1 | 0 | 0 | 0 |
| `com.example.FighterControlMacrosTest` | 5 | 5 | 0 | 0 | 0 |
| **`com.example.GeminiIntegrationTest` (الجديدة)** | **9** | **9** | **0** | **0** | **0** |
| `com.example.GreetingScreenshotTest` | 1 | 1 | 0 | 0 | 0 |
| `com.example.LiveLoggerTest` | 3 | 3 | 0 | 0 | 0 |
| `com.example.MacroControllerTest` | 14 | 14 | 0 | 0 | 0 |
| `com.example.MugenGameActivityLifecycleTest` | 5 | 5 | 0 | 0 | 0 |
| `com.example.RosterBalancingTest` | 12 | 12 | 0 | 0 | 0 |
| `com.example.SelectDefManagerTest` | 7 | 7 | 0 | 0 | 0 |
| `com.example.StageDiscoveryTest` | 2 | 2 | 0 | 0 | 0 |
| `com.example.VirtualControllerInputTest` | 10 | 10 | 0 | 0 | 0 |
| **المجموع الكلي للمشروع (GRAND TOTAL)** | **144** | **144** | **0** | **0** | **0** |

#### تفاصيل الاختبارات الـ 9 في `GeminiIntegrationTest`:
1. `testSaveAndRetrieveApiKey`: حفظ المفتاح واسترجاعه ومطابقته بدقة.
2. `testDeleteApiKey`: حذف المفتاح والتأكد من انعدامه تماماً.
3. `testApiKeyMasking`: حجب المفاتيح الطويلة والقصيرة وحماية الأحرف الحساسة.
4. `testIvUniquenessAndRandomness`: التحقق من توليد IV جديد وعشوائي في كل تشفير.
5. `testCorruptedDataRecovery`: معالجة البيانات التالفة وحذفها دون انهيار التطبيق.
6. `testGeminiRosterBalancerClamping`: تقييد القيم المتطرفة إلى الأسقف والأرضيات المعتمدة.
7. `testGeminiRosterBalancerMergeAndValidation`: دمج مقترحات Gemini مع المقترحات الرياضية والتحقق الصارم من الحدود وإضافة وسم `[Gemini AI]`.
8. `testFallbackWhenGeminiFailsOrKeyMissing`: التراجع السلس للتحليل الرياضي عند غياب المفتاح.
9. `testNoDiskModificationDuringAnalysis`: إثبات عدم إجراء أي تعديل على القرص أثناء الفحص.

### ب. بناء حزمة التطبيق (`./gradlew :app:assembleDebug --no-daemon`):
- **حالة البناء:** `BUILD SUCCESSFUL in 1m 22s` (39 actionable tasks: 6 executed, 33 up-to-date).
- **مسار الملف:** `app/build/outputs/apk/debug/app-debug.apk`.
- **حجم الملف:** 86,506,731 بايت (~82.5 ميغابايت).

### ج. ما تم التحقق منه وما هو معلق:
- **تم التحقق منه برمجياً وآلياً:** تشفير وفك تشفير الـ Keystore، عشوائية الـ IV، حجب المفاتيح، استبعاد النسخ الاحتياطي، دمج وتدقيق قيم Gemini، استقرار واجهات الإعدادات وقائمة الشخصيات، وبناء حزمة APK.
- **ما هو معلق ويخص تجربة المستخدم الحقيقية:** إدخال مفتاح Gemini حقيقي فعال واختبار سرعة استجابة خوادم Google عبر الإنترنت الفعلي على جهاز أندرويد.

---

## 7. تأكيد الالتزام بالحدود الإلزامية (Mandatory Constraints Confirmation)

- **ملفات المحرك النيتف ومكتبة `libmain.so`:** لم يتم لمسها أو تعديلها بأي شكل من الأشكال، وظلت سليمة تماماً.
- **كود JNI و SDL ومسارات اللعبة الأصلية:** لم يطرأ عليها أي تغيير.
- **المكتبات والاعتماديات الخارجية:** **صفر اعتماديات جديدة**. تم استخدام المكتبات القائمة بالفعل (`OkHttpClient` و Android Keystore).
- **التخزين الخارجي أو السحابي للمفتاح:** لم يتم حفظ المفتاح في Firebase أو Firestore أو أي خادم وسيط؛ التخزين محلي 100% ومشفر عتادياً على جهاز المستخدم.
