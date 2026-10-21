# PHASE 2.3 — IMMUTABLE LEGACY CHARACTER SOURCE SEPARATION
## تقرير التدقيق الجنائي والفحص المعماري لفصل مصادر المقاتلين المسبقي الوجود

**تاريخ التدقيق:** 20 أكتوبر 2026  
**المرحلة التطويرية:** PHASE 2.3 — عزل وفصل المصادر الأصلية للمقاتلين القدامى عن مساحة عمل المحرك  
**المرجع التاريخي:** راجع ملف `MUGEN_MOBILE_FORENSIC_AUDIT_REPORT.md` لنتائج المراحل السابقة (PHASE 0 حتى PHASE 2.2).  
**حالة البناء والاختبارات:** `BUILD SUCCESSFUL` بنسبة 100% (22/22 اختبارات وحدة حقيقية ناجحة، 0 فشل، 0 أخطاء).

---

## 1. الفجوة المعمارية المستهدفة (The Architectural Gap)

في التدقيق الجنائي للمراحل السابقة (PHASE 2.1 و PHASE 2.2)، تم إثبات أن الشخصيات المستوردة حديثاً تخضع لبروتوكول الطبقات الأربع الصارم:
`المصدر الأصلي -> الخزينة المعزولة -> مساحة العمل -> محرك اللعبة`.

ومع ذلك، وُجدت فجوة معمارية متبقية تتعلق بـ **المقاتلين الموجودين مسبقاً (Legacy / Pre-existing Characters)** الذين يضعهم المستخدم يدوياً أو تأتي مع حزم اللعبة داخل مجلد مساحة العمل المباشرة `chars/<charId>`:
1. **غياب المجلد الفيزيائي للمصدر:** كان يتم إنشاء لقطة الخزينة داخل التخزين الداخلي الخاص بالتطبيق (`vault/characters/<charId>`)، ولكن لم يكن هناك مسار مادي للمصدر الأصلي داخل مساحة التخزين المشتركة (`sources/chars/<charId>`).
2. **خطر المساس عند غياب المصدر:** عند إجراء أي تعديل على المقاتل في مساحة العمل `chars/<charId>`، كانت النسخة الوحيدة القابلة للوصول في التخزين الخارجي هي النسخة المعدلة ذاتها.
3. **خطر تسجيل مساحة العمل كمصدر:** إذا سُجل مسار مساحة العمل بالخطأ كمصدر أصلي في `registeredOriginalSources`، لأدى ذلك إلى تعطيل إمكانية حفظ تعديلات المستخدم المشروعة على مساحة العمل الحية.

---

## 2. المعالجة الهندسية المنفذة في الشفرة المصدرية (Code Implementation)

تم تحديث `CharacterWorkspaceManager.kt` بالحلول المعمارية التالية:

### أ. حماية تسجيل المصادر (`registerOriginalSource`):
تمت إضافة حاجز أمني يمنع قطعياً تسجيل أي مجلد يقع داخل مساحة العمل النشطة `getWorkspaceCharsDir(context)` كمصدر أصلي:
```kotlin
if (effectiveCtx != null) {
    val workspaceCanonical = try { getWorkspaceCharsDir(effectiveCtx).canonicalPath } catch (_: Exception) { "" }
    if (workspaceCanonical.isNotEmpty() &&
        (normalized == workspaceCanonical || normalized.startsWith("$workspaceCanonical${File.separator}"))) {
        Log.w(TAG, "Skipping registration: path '$normalized' resides in active workspace and cannot be an immutable Original Source.")
        return
    }
}
```

### ب. الفصل الفيزيائي للمصادر القديمة (`separateLegacyCharacterSource`):
دالة مخصصة تقوم عند اكتشاف مقاتل قديم في مساحة العمل بما يلي:
1. التحقق من صلاحية المجلد ووجود ملف `.def`.
2. نسخ كامل الملفات والمجلدات الفرعية ذرياً إلى المسار المخصص للمصادر: `sources/chars/<charId>`.
3. ضبط سمة قراءة فقط (`setReadOnly()`) لجميع الملفات المنسوخة.
4. تسجيل المسار الجديد فوراً كـ `Original Source` دائم ومحصن في `registeredOriginalSources` وحفظه على القرص.

### ج. استرجاع المصدر النقي من الخزينة عند التعديل المسبق (`separateLegacySourceFromVault`):
في حال كان المقاتل القديم قد عُدّل مسبقاً في مساحة العمل قبل عملية الفصل، فإن النظام لا ينسخ الملفات المعدلة كمصدر، بل يعيد بناء مسار المصدر `sources/chars/<charId>` مباشرة من ملفات الخزينة النقية (`vault/characters/<charId>/files`) والمؤكدة ببصمات SHA-256، مما يضمن أن المصدر المفصول يحتوي على البيانات الأصلية النقية بنسبة 100%.

### د. الفصل الجماعي الشامل (`separateAllLegacyCharacterSources`):
فحص تلقائي لكافة مجلدات `chars/` وفصل أي مقاتل يفتقر إلى مجلد مصدر مخصص في `sources/chars/`.

### هـ. تحديث `ensureVaultSnapshot`:
تم دمج خطوة الفصل التلقائي بحيث تضمن أنه قبل السماح بأي عملية تحرير أو حفظ، يتم تأكيد وجود المصدر الفيزيائي المفصول في `sources/chars/<charId>` وتسجيله، مع ضبط وسم الأصل في المانيفست على:
`sourceOrigin = "LEGACY_WORKSPACE_SEPARATED"`

---

## 3. التتبع الجنائي الدقيق للمسارات بعد الفصل (Empirical Path Tracing)

| الكيان المعماري | المسار الفعلي (Real Canonical Path) | الصلاحية | الضمانات الأمنية الجنائية |
|---|---|---|---|
| **Original Source** (المصدر الأصلي المفصول) | `/storage/emulated/0/Documents/MUGEN_MOBILE/sources/chars/<charName>/` | **قراءة فقط (READ-ONLY)** | محمي ومسجل في `registeredOriginalSources`. يُرفض أي تعديل عبر `CnsEditor.save()` أو `saveCharacterFileSafe` مع إلقاء `SecurityException`. |
| **Original Vault** (الخزينة المحمية المشفرة) | `/data/user/0/com.example/files/vault/characters/<charName>/` | **ساندبوكس النظام (Sandbox)** | محمية بصلاحيات نظام أندرويد الخاصة بالتطبيق، ومقفلة ببصمات SHA-256 في `manifest.json`. |
| **Character Workspace** (مساحة العمل الحية) | `/storage/emulated/0/Documents/MUGEN_MOBILE/chars/<charName>/` | **قراءة وكتابة ذرية (R/W)** | مخصصة لملفات المقاتل القابلة للتعديل والتحميل من المحرك. الحفظ يتم ذرياً عبر ملفات مؤقتة واستبدال آمن مع سجل الميتاداتا. |
| **Engine Runtime Directory** (مسار تشغيل المحرك) | `/storage/emulated/0/Documents/MUGEN_MOBILE/` | **تشغيل المحرك** | يتم تمريره عبر وسيط التشغيل `-dir` لمحرك IKEMEN-GO في `MugenGameActivity.kt`. |

---

## 4. السجل الفعلي لتشغيل الاختبارات الآلية (Real Test Execution Record)

- **أمر التنفيذ:**
  ```bash
  chmod +x ./gradlew && ./gradlew testDebugUnitTest --tests "com.example.CharacterWorkspaceManagerTest"
  ```
- **تقرير الاختبار المستخرج من `TEST-com.example.CharacterWorkspaceManagerTest.xml`:**
  - **إجمالي الاختبارات:** 22
  - **حالات الفشل (Failures):** 0
  - **الأخطاء (Errors):** 0
  - **المتخطاة (Skipped):** 0
  - **الزمن الكلي للتنفيذ:** 7.508 ثانية
  - **النتيجة الكلية:** `BUILD SUCCESSFUL`

### تفصيل الاختبارات الأربعة الجديدة المضافة في PHASE 2.3:

1. **`testLegacyCharacterSourceSeparationOnWorkspaceDiscovery` [VERIFIED]:**
   - التحقق من عدم وجود مجلد في `sources/chars/LegacyHero` قبل عملية الفصل.
   - تشغيل `ensureVaultSnapshot` على مجلد المقاتل القديم في مساحة العمل.
   - إثبات إنشاء مجلد المصدر المفصول في `sources/chars/LegacyHero`.
   - إثبات تطابق بصمة SHA-256 لكل ملف بين الأصل ومجلد المصدر المفصول بنسبة 100% بايت-ببايت.
   - إثبات التعرف على الملف كمصدر أصلي (`isOriginalSource == true`) وكمحمية محظورة (`isProtectedImmutableFile == true`).
   - إثبات رمي `SecurityException` عند محاولة الكتابة المباشرة عبر `CnsEditor.save()`.
   - إثبات وسم المانيفست بـ `LEGACY_WORKSPACE_SEPARATED` وتوجيه مسار المصدر الأصلي إليه.

2. **`testLegacyCharacterEditsDoNotTouchSeparatedOriginalSource` [VERIFIED]:**
   - تعديل قيم المقاتل القديم في مساحة العمل (`life = 3333`, `attack = 222`).
   - إثبات تغير بصمة ملف مساحة العمل وبقاء بصمة المصدر المفصول في `sources/chars/` مطابقة للأصل بنسبة 100%.
   - استعادة المقاتل (`restoreSingleCharacter`) وإثبات عودة ملف مساحة العمل لحالته الأصلية دون المساس بالمصدر المفصول إطلاقاً.

3. **`testReconstructSeparatedSourceFromVaultForAlreadyModifiedLegacyCharacter` [VERIFIED]:**
   - محاكاة حالة مقاتل تم التعديل عليه في مساحة العمل قبل إنشاء مجلد المصدر المفصول.
   - إثبات أن دالة `separateLegacySourceFromVault` تسترجع البيانات من الخزينة النقية الأصلية، وتنشئ المصدر المفصول بالقيم النقية (`life = 1000`) رافضةً الاعتماد على ملف مساحة العمل المعدل (`life = 9999`).

4. **`testWorkspaceDirectoryRegistrationAsOriginalSourceRejected` [VERIFIED]:**
   - محاولة تسجيل مجلد مساحة العمل كمصدر أصلي في `registerOriginalSource`.
   - إثبات رفض التسجيل وحماية مساحة العمل، مما يضمن استمرار إمكانية الحفظ والتعديل المشروعة دون أخطاء أمنية زائفة.

---

## 5. مصفوفة التصنيف الجنائي لـ PHASE 2.3 (Forensic Classifications)

| التصنيف الجنائي | الحالة | البيان التوثيقي المعتمد |
|---|---|---|
| **`VERIFIED`** | مؤكد بنسبة 100% (22/22 اختبار وحدة ناجح) | 1. الفصل الفيزيائي التام لمصادر المقاتلين المسبقي الوجود في `sources/chars/<charId>`.<br>2. حظر الكتابة على المصادر المفصولة أمنياً وبرمجياً.<br>3. بقاء المصادر المفصولة مطابقة للبصمات الأصلية بايت-ببايت أثناء التعديل والاستعادة.<br>4. إعادة بناء المصدر المفصول من الخزينة النقية عند وجود تعديلات مسبقة في مساحة العمل.<br>5. منع تسجيل مجلدات مساحة العمل كمصادر أصلية. |
| **`IMPLEMENTED`** | منجز بالكامل في الكود المصدري | 1. دوال الفصل `separateLegacyCharacterSource` و `separateLegacySourceFromVault` و `separateAllLegacyCharacterSources`.<br>2. قيد التحقق الأمني في `registerOriginalSource`.<br>3. وسم `LEGACY_WORKSPACE_SEPARATED` في `capturePristineSnapshot`.<br>4. حزمة اختبارات الوحدة الموسعة لـ 22 اختباراً في `CharacterWorkspaceManagerTest.kt`. |
| **`NOT TESTED`** | لم يُختبر على جهاز فيزيائي | اختبار تحميل وتعديل أكثر من 100 شخصية قديمة دفعة واحدة على بطاقة ذاكرة خارجية (MicroSD) بهاتف أندرويد حقيقي. |
| **`BLOCKED`** | منعدم | لا توجد أي عوائق تقنية أو تضاربات معمارية. |
| **`PROPOSED`** | جاهز للانطلاق | **PHASE 3:** الشروع في بناء واجهة محرر الشخصيات المتكاملة للهواتف المحمولة (Mobile Character Editor). |

---

## 6. الخلاصة الهندسية

تم إغلاق الفجوة المعمارية لعزل المقاتلين المسبقي الوجود بنجاح تام وفق أعلى معايير النزاهة الهندسية. مساحة عمل المحرك تعمل الآن بمعزل تام وفيزيائي عن مصادر المقاتلين الأصلية، وكافة نتائج التدقيق موثقة ومثبتة باختبارات آلية حقيقية.
