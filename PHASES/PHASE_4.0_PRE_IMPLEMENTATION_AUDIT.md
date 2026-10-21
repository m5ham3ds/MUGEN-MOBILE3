# PHASE 4.0 — PRE-IMPLEMENTATION AUDIT & AUTOMATIC ROSTER BALANCING REPORT
## تقرير التدقيق المعماري المسبق وتصميم نظام الموازنة التلقائية لقائمة المقاتلين

**التاريخ (Date):** 20 أكتوبر 2026  
**المرحلة:** PHASE 4.0 — Pre-Implementation Audit & Automatic Roster Balancing Design  
**طبيعة المرحلة:** تدقيق معماري، فحص أصول وخدمات، وتصميم هندسي كامل — **دون تعديل كود المصدر البرمجي ودون تطبيق أي موازنة في هذه المرحلة** (Documentation, Audit & Architecture Design Only).  
**الحالة العامة للمشروع الحالية:** **123/123 UNIT TESTS PASS (100%) | APK BUILD PASS | REAL-DEVICE RUNTIME CONFIRMED**  

---

## 1. فحص وجرد الميزات والخدمات الموجودة بالفعل (Existing Architecture & Code Inventory)

تم إجراء فحص شامل لكافة ملفات المشروع لتحديد الخدمات والنماذج المسؤولة عن دورة حياة المقاتل والتي ستشكل الركيزة الأساسية لنظام الموازنة:

### أ. الخدمات والنماذج القائمة في المنظومة:

| المكون / الخدمة | مسار الملف الفعلي | الدوال والنماذج المتاحة للاستخدام في الموازنة | الوظيفة المنجزة والمعتمدة |
|---|---|---|---|
| **مسح واكتشاف القائمة** | `app/src/main/java/com/example/engine/MugenParser.kt` | `MugenParser.getCharacters(context)`, `data class CharacterData` | فحص دليل `chars/` واكتشاف كافة الشخصيات الصالحة مع ملفات `.def` و `.cns` ومزامنتها مع `select.def`. |
| **محرر ومحلل ملفات CNS** | `app/src/main/java/com/example/engine/MugenParser.kt` | `class CnsEditor(val cnsFile: File)`, `getProperty(section, key)`, `setProperty(section, key, newValue)`, `reparseLines()` | قراءة وتعديل خصائص المقاتل (`life`, `attack`, `defence`) في قسم `[Data]` مع الحفاظ على التعليقات والأسطر غير المعدلة. |
| **محرر ومحلل ملفات DEF** | `app/src/main/java/com/example/engine/DefEditor.kt` | `class DefEditor(val defFile: File)`, `getProperty(section, key)`, `getDependentFiles()` | قراءة بيانات الهوية من `[Info]` والملفات المرتبطة من `[Files]`. |
| **محرك التحقق الرقمي والحدود** | `app/src/main/java/com/example/engine/CharacterEditorValidator.kt` | `validateLife(v)`, `validateAttack(v)`, `validateDefence(v)`, `FieldValidationResult` | التحقق الصارم من صحة الأرقام ومنع القيم السالبة أو الصفرية أو المتطرفة جداً (1 إلى 100,000 للحياة، و1 إلى 10,000 للهجوم والدفاع). |
| **إدارة مساحة العمل والخزينة الذرية** | `app/src/main/java/com/example/engine/workspace/CharacterWorkspaceManager.kt` | `saveCnsChanges()`, `saveCompleteCharacterEdits()`, `ensureVaultSnapshot()`, `restoreSingleCharacter()`, `planBatchRestore()`, `executeBatchRestore()` | تأمين النسخ الأصلية النقية في الخزينة `.mugen_vault/` تلقائياً قبل أي كتابة، إنشاء نسخ احتياطية `.pre_atomic_bak`، التراجع التلقائي عند الفشل، وتوثيق سجل التعديلات مع ترقية رقم الإصدار. |
| **واجهة استعراض المقاتلين** | `app/src/main/java/com/example/ui/screens/characters/CharactersScreen.kt` | `CharactersScreen()`, `BatchRestoreDialog()`, `reloadData()` | شبكة عرض البطاقات، شارات الحالة، وأزرار المزامنة والاستعادة الجماعية. |
| **محرر المقاتل المتكامل** | `app/src/main/java/com/example/ui/screens/characters/CompleteCharacterEditorDialog.kt` | `CompleteCharacterEditorDialog()`, شارات الفحص الجنائي، أزرار الحفظ والاستعادة الفردية | واجهة تحرير شاملة مقسمة لـ 4 أقسام مع البحث والتحقق الحي. |

---

## 2. التحقق الجنائي من الحالة الدقيقة لـ "Restore All Originals" (Audit of Batch Restore)

أجرى التدقيق فحصاً دقيقاً لتنفيذ ميزة الاستعادة الجماعية للأصل:

### أ. التنفيذ في طبقة منطق الأعمال (`CharacterWorkspaceManager.kt`):
1. **دالة التخطيط `planBatchRestore(context)` (الأسطر 1626-1656):**
   - تفحص محتويات الخزينة المشفرة في `.mugen_vault/characters/`.
   - تتحقق من سلامة بصمات التجزئة عبر `verifyVaultIntegrity(charId, context)`.
   - تصنف المقاتلين بدقة إلى: `modifiedCharacters` (المعدلين الذين يحتاجون استعادة)، `pristineCharacters` (المطابقين للأصل)، و `corruptedCharacters` (التالفين).
2. **دالة التنفيذ `executeBatchRestore(characterIds, context)` (الأسطر 1662-1695):**
   - إذا تم تمرير `null`، تستهدف تلقائياً كافة الشخصيات المعدلة والتالفة.
   - تنفذ حلقة استعادة ذرية عبر `restoreSingleCharacter(id, context)` لكل شخصية على حدة.
   - تضمن عزل العمليات: نجاح أو فشل شخصية لا يؤثر على سلامة استعادة الشخصيات الأخرى، وتُرجع تقريراً مفصلاً `BatchRestoreReport(success, restoredCharacters, failedCharacters)`.

### ب. الربط بالواجهة الأمامية (`CharactersScreen.kt`):
1. **زر الوصول:** أيقونة درع الأمان (`Icons.Default.Security`) في الشريط العلوي للشاشة باللون الأخضر (السطر 247).
2. **نافذة الحوار `BatchRestoreDialog` (الأسطر 478-608):**
   - تعرض إحصائيات الخزينة (الإجمالي، المعدلة، النقية، التالفة).
   - في حال وجود شخصيات معدلة، يظهر زر أحمر صريح:  
     `استعادة الكل للأصل (${plan.modifiedCharacters.size})`
   - عند النقر، يتم استدعاء `executeBatchRestore(null, context)` داخل خيط مع مؤشر تقدم دائري (`CircularProgressIndicator`)، وعرض إشعار `Toast` بالنتيجة، وإعادة تحميل القائمة فوراً عبر `reloadData()`.

### ج. سلوك الأمان عند الفشل وحماية المصادر الأصلية:
1. **عند حدوث خطأ أثناء الاستعادة:** تقوم `restoreSingleCharacter` بالتراجع الذري وإعادة النسخة الاحتياطية لمساحة العمل أو وسم الشخصية كـ `CORRUPTED` وحفظ النسخ الاحتياطية `.restore_backup_<id>_<timestamp>`، مع إضافة الشخصية لقائمة `failedCharacters` دون إيقاف استعادة باقي المقاتلين.
2. **بقاء المصادر الأصلية دون أدنى تعديل (Immutability Proof):**
   - ملفات المصادر في `sources/chars/` وملفات الخزينة في `vault/characters/` محمية ومحظورة من أي كتابة برمجية بواسطة `isProtectedImmutableFile()`.
   - دالة الاستعادة تفتح ملفات الخزينة للقراءة والنسخ فقط (`vaultFile.copyTo(stagedFile, overwrite = false)`).
   - الاختبار الآلي `testBatchRestorePreservesOriginalSourcesBitForBit` في `CharacterWorkspaceManagerTest.kt:320-365` يُثبت تطابق بصمات SHA-256 بنسبة 100% بايت-ببايت قبل وبعد الاستعادة الجماعية.

---

## 3. ما هو موجود وما هو ناقص في نظام الموازنة (Gap Analysis)

### أ. الموجود حالياً (Available Building Blocks):
- القدرة على قراءة وتعديل وحفظ خصائص `life` و `attack` و `defence` ذرياً عبر `CnsEditor` و `CharacterWorkspaceManager`.
- وجود آلية الخزينة والاستعادة الفردية والجماعية (`restoreSingleCharacter` و `executeBatchRestore`).
- وجود واجهة اكتشاف وسرد الشخصيات `MugenParser.getCharacters()`.

### ب. الناقص والمطلوب بناؤه في المرحلة القادمة (Missing Features):
1. **محلل إحصائيات القائمة (Roster Statistical Analyzer):**
   - لا توجد حالياً خدمة تحسب المتوسط الحسابي، والوسيط، والانحراف المعياري، والحدود الربيعية لقيم القائمة بالكامل.
2. **محرك كشف وتصنيف القيم الشاذة (Outlier Detection Engine):**
   - لا توجد خوارزمية تميز بين الشخصيات المتوازنة والشخصيات الشاذة (Overpowered Broken Chars / Extremely Fragile Chars).
3. **خوارزمية الضغط التدريجي المحافظة على الهوية (Identity-Preserving Damping Algorithm):**
   - لا توجد معادلات لتقريب القيم الشاذة دون تسطيحها برقم موحد (عدم مساواة الجميع بـ 1000).
4. **نموذج مقترحات الموازنة الشارحة (Explainable Balance Proposal Model):**
   - نموذج بيانات يحمل: اسم المقاتل، القيمة الحالية، القيمة المقترحة، نسبة التعديل، وسبب الاقتراح باللغة العربية.
5. **واجهة المراجعة والموافقة المسبقة (Roster Balance Review Dialog):**
   - واجهة مخصصة تعرض جدولاً مفصلاً للمقترحات مع مربعات اختيار (Checkboxes) لكل مقاتل لتمكين المستخدم من قبول أو استثناء شخصيات بعينها قبل التطبيق.

---

## 4. التصميم المعماري المقترح لنظام الموازنة التلقائية (Architecture Design)

### أ. تحليل الخصائص المدعومة فعلياً في ملفات MUGEN:
من خلال فحص ملفات `.cns` الفعلية وكود `CnsEditor` و `CharacterEditorValidator`:
- **الخصائص المؤثرة والقابلة للمقارنة الحسابية السليمة:**
  1. **`life` (نقاط الحياة الأساسية):** القيمة المعيارية في محرك MUGEN هي `1000`. النطاق الصحي الشائع للشخصيات العادية بين `850` و `1200`. القيم الأكبر من `1400` تمثل شذوذاً تدميرياً أو زعيماً مفرط القوة، والقيم الأقل من `750` تجعل الشخصية تسقط في جولتين.
  2. **`attack` (مضاعف قوة الهجوم):** القيمة المعيارية `100` (أي 100%). النطاق المتوازن المقبول بين `85%` و `115%`. القيم فوق `130%` تسبب ضرراً مفرطاً، وفوق `200%` تنهي الجولة بضربة واحدة.
  3. **`defence` (مضاعف الدفاع):** القيمة المعيارية `100` (أي 100%). النطاق المتوازن المقبول بين `85%` و `115%`. القيم فوق `130%` تجعل الشخصية لا تتأثر بالضربات.
- **الخصائص المستبعدة من الموازنة التلقائية:**
  - أبعاد الرسوميات (`xscale`, `yscale`) لأنها بصرية ولا تمثل قوة قتالية.
  - سرعات الحركة والقفز (`velocity`, `movement`) لأنها وثيقة الارتباط بحركات المقاتل والرسوم المتحركة؛ وتعديلها تلقائياً قد يكسر توقيت الضربات أو يتسبب في اختلال السقوط.

---

### ب. خوارزمية كشف القيم الشاذة والموازنة التدريجية (Identity-Preserving Damping):

الهدف الأساسي هو **كبح القيم المكسورة وغير العادلة مع الحفاظ الكامل على الطابع الفردي لكل مقاتل** (فالمقاتل الضخم يظل أثقل وأقوى، والمقاتل السريع يظل أخف وأقل دفاعاً):

#### 1. النطاقات المرجعية المرنة:
- **نطاق التوازن الطبيعي:**
  - الحياة: `850` إلى `1150` HP.
  - الهجوم: `85%` إلى `115%`.
  - الدفاع: `85%` إلى `115%`.
- **تصنيف المقاتل (Archetype Categorization):**
  - **مقاتل ثقيل (Tank):** (حياة `1050-1150` / هجوم `90-100` / دفاع `105-115`) -> **لا يُعدل، فهو متوازن في فئته**.
  - **مقاتل خفيف وسريع (Glass Cannon / Speedster):** (حياة `850-950` / هجوم `105-115` / دفاع `85-95`) -> **لا يُعدل**.
  - **مقاتل زعيم شاذ (Overpowered / Boss Outlier):** (حياة `> 1400` و/أو هجوم `> 130` و/أو دفاع `> 130`) -> **يخضع للضغط التدريجي**.
  - **مقاتل ضعيف شاذ (Underpowered / Broken Outlier):** (حياة `< 750` و/أو هجوم `< 75` و/أو دفاع `< 75`) -> **يخضع للتعزيز التدريجي**.

#### 2. معادلات الضغط التدريجي غير الخطي (Soft-Knee Damping Formulas):
بدلاً من قطع القيمة بحد صلب موحد، نستخدم تقريباً تدريجياً:

1. **معادلة ضغط الحياة العالية ($Life > 1200$):**
   $$Life_{proposed} = 1150 + \left\lfloor \frac{Life_{current} - 1150}{3.5} \right\rfloor$$
   *(مع سقف أقصى للزعماء المحتفظين بهيبتهم عند 1350 HP)*.  
   **مثال:** مقاتل حياته `3000` تصبح `1150 + (1850 / 3.5) = 1678` -> بالضغط الأقصى تصبح `1350 HP`. يظل أقوى من المقاتل العادي (1000) بنسبة 35%، ولكن ضمن حدود تجعل المعركة ممكنة وقابلة للتحدي العادل.

2. **معادلة تعزيز الحياة المنخفضة ($Life < 850$):**
   $$Life_{proposed} = 850 - \left\lfloor \frac{850 - Life_{current}}{2.5} \right\rfloor$$
   *(مع أرضية صلبة لا تقل عن 750 HP)*.  
   **مثال:** مقاتل حياته `500` تصبح `850 - (350 / 2.5) = 710` -> بالأرضية تصبح `750 HP`.

3. **معادلة ضغط الهجوم والدفاع العاليين ($Stat > 115$):**
   $$Stat_{proposed} = 115 + \left\lfloor \frac{Stat_{current} - 115}{3.0} \right\rfloor$$
   *(مع سقف أقصى عند 125%)*.  
   **مثال:** مقاتل هجومه `250%` يصبح `115 + (135 / 3.0) = 160%` -> بالسقف الأقصى يصبح `125%`.

4. **معادلة تعزيز الهجوم والدفاع المنخفضين ($Stat < 85$):**
   $$Stat_{proposed} = 85 - \left\lfloor \frac{85 - Stat_{current}}{2.0} \right\rfloor$$
   *(مع أرضية لا تقل عن 75%)*.

---

### ج. هيكل البيانات المقترح لمقترحات الموازنة (Proposed Data Models):

```kotlin
data class StatAdjustment(
    val statName: String, // "الحياة (Life)", "الهجوم (Attack)", "الدفاع (Defence)"
    val originalValue: Int,
    val proposedValue: Int,
    val unit: String = "" // "", "%"
) {
    val delta: Int get() = proposedValue - originalValue
    val isNerfed: Boolean get() = delta < 0
    val isBuffed: Boolean get() = delta > 0
}

data class CharacterBalanceProposal(
    val characterId: String,
    val displayName: String,
    val folderName: String,
    val adjustments: List<StatAdjustment>,
    val explanation: String, // شرح تفسيري واضح
    var isSelectedForApply: Boolean = true
)

data class RosterAnalysisSummary(
    val totalCharactersScanned: Int,
    val charactersNeedingAdjustment: Int,
    val charactersAlreadyBalanced: Int,
    val averageLife: Double,
    val averageAttack: Double,
    val averageDefence: Double,
    val proposals: List<CharacterBalanceProposal>
)
```

---

### د. تدفق واجهة المستخدم وتجربة المراجعة (UI Review & Consent Flow):

1. **الزر في واجهة `CharactersScreen`:**
   - إضافة زر "موازنة القائمة" (أيقونة `Icons.Default.Balance` أو `Tune`) في الشريط العلوي بجوار أيقونة الخزينة والمزامنة.
2. **نافذة المراجعة `RosterBalancingDialog`:**
   - **القسم العلوي:** بطاقة ملخص تحليلي للقائمة (عدد الشخصيات المفحوصة، عدد الشخصيات التي تحتاج تعديل، وعدد الشخصيات المتوازنة بالفعل).
   - **القسم الأوسط (جدول / بطاقات المعاينة):**
     - قائمة قابلة للتمرير لكل شخصية مقترح تعديلها.
     - مربع اختيار (Checkbox) مفعل تلقائياً للشخصيات المقترح تعديلها، مع إمكانية إلغاء التحديد لأي شخصية يريد المستخدم إبقاءها خارقة (مثل مقاتلي الزعماء المخصصين لأطوار الآركيد).
     - تفاصيل المقارنة بالألوان: `الحياة: 3000 -> 1350 (🔻 -1650)` و `الهجوم: 200% -> 125% (🔻 -75%)`.
     - سطر الشرح التفسيري (Rationale): مثال *"ضغط قيمة الزعيم الخارقة مع الحفاظ على كونه مقاتلاً أثقل من المتوسط بنسبة 35%"*.
   - **القسم السفلي (شريط الأمان والإجراءات):**
     - رسالة توضيحية: *"سيتم تطبيق التعديلات على مساحة العمل فقط مع بقاء الأصول الأصلية في الخزينة، ويمكنك التراجع في أي وقت عبر 'استعادة الكل للأصل'"*.
     - زر الإلغاء: `إغلاق`.
     - زر التنفيذ: `تطبيق الموازنة المحددة (${selectedCount})`.

---

### هـ. التطبيق الآمن والقابل للتراجع (Safe & Atomic Application):

1. **التطبيق عبر `CharacterWorkspaceManager` فقط:**
   - لكل شخصية مختارة:
     - تحميل ملف `cnsFile` عبر `CnsEditor`.
     - تطبيق القيم الجديدة على `[data]`: `setProperty("[data]", "life", proposal.life)` إلخ.
     - استدعاء `CharacterWorkspaceManager.saveCnsChanges(cnsEditor, charId, context)` التي تضمن:
       - إنشاء نسخة احتياطية `.pre_atomic_bak`.
       - تأمين نسخة الخزينة إن لم تكن مؤمنة.
       - ترقية الإصدار وتوثيق التغيير في `ChangeRecord` بمصدر `"AUTO_ROSTER_BALANCE"`.
2. **عدم المساس بالمصادر الأصلية مطلقاً:**
   - تظل مجلدات `sources/chars/` و `vault/characters/` محمية من أي تعديل بنسبة 100%.
3. **التراجع الفوري الكامل:**
   - يتيح زر `استعادة الكل للأصل` الموجود حالياً في `BatchRestoreDialog` إعادة كافة الشخصيات لحالتها الأصلية النقية بضغطة واحدة وبكل أمان.

---

## 5. خطة الاختبارات والتحقق المطلوبة للتنفيذ (Required Tests Suite)

سيتم إنشاء حزمة اختبارات وحدة مخصصة مستقلة باسم `RosterBalancingTest.kt` لضمان عدم المساس بالحزم الـ 14 القائمة:

1. **اختبار التحليل الإحصائي للقائمة (`testRosterBaselineStatisticsCalculation`):**
   - إنشاء قائمة تجريبية ذات إحصائيات متفاوتة والتحقق من صحة المتوسطات ونسب الانحراف.
2. **اختبار تمييز وفصل الشخصيات المتوازنة (`testBalancedCharactersAreNotModified`):**
   - التحقق من أن المقاتل المتوازن (مثلاً `Life=1000`, `Attack=100`, `Defence=100`) لا يُقترح له أي تعديل.
3. **اختبار خوارزمية ضغط الزعماء الشاذين (`testOverpoweredBossOutlierDampedGradually`):**
   - إدخال مقاتل خارق (`Life=5000`, `Attack=300%`) والتحقق من ضغطه نحو نطاق عادل (`1350 HP / 125% Attack`) مع بقائه أقوى من الشخصيات العادية.
4. **اختبار خوارزمية تعزيز الشخصيات الضعيفة (`testUnderpoweredOutlierBuffedToViableRange`):**
   - إدخال مقاتل ضعيف (`Life=500`, `Attack=60%`) والتحقق من رفعه للنطاق التنافسي الأدنى (`750 HP / 80% Attack`).
5. **اختبار الشرح التفسيري لكل مقترح (`testProposalsIncludeHumanReadableRationale`):**
   - التحقق من توليد نصوص عربية واضحة ومفسرة لسبب كل تعديل.
6. **اختبار احترام اختيارات المستخدم الجزئية (`testUserCanDeselectSpecificCharacters`):**
   - إلغاء تحديد مقاتل معين في المقترحات والتأكد من بقاء ملفاته دون أي تعديل على القرص.
7. **اختبار بقاء الخزينة والمصادر الأصلية مطابقة 100% (`testRosterBalancingPreservesSourcesAndVaultImmutability`):**
   - حساب بصمات SHA-256 للمصادر والخزينة قبل وبعد الموازنة والتأكد من عدم تغير بايت واحد.
8. **اختبار التراجع الجماعي الكامل بعد الموازنة (`testBatchRestoreRevertsAllBalancedCharactersToPristine`):**
   - تطبيق الموازنة على القائمة ثم استدعاء `executeBatchRestore` والتأكد من عودة جميع الشخصيات لحالتها النقية وتفريغ قائمة `modifiedCharacters`.

---

## 6. المخاطر والقرارات المطلوب حسمها قبل بدء التنفيذ (Risks & Decisions)

| المسألة / القرار | الخيارات المتاحة | التوصية المعمارية للتدقيق |
|---|---|---|
| **موقع زر الموازنة في الواجهة** | 1. زر إضافي في الشريط العلوي لـ `CharactersScreen`<br>2. خيار داخل نافذة `BatchRestoreDialog`<br>3. زر عائم في أسفل الشاشة | **الخيار 1 (زر مخصص في الشريط العلوي)** بجوار أيقونة الخزينة والمزامنة لتكون الواجهة متناسقة وواضحة. |
| **طبيعة تطبيق التعديل** | 1. تطبيق دفعة واحدة لجميع المقترحات<br>2. جدول معاينة مع Checkbox لكل شخصية | **الخيار 2 (جدول مراجعة وموافقة صريحة)** لمنع أي تعديل مفاجئ وتمكين المستخدم من استثناء شخصيات الزعماء التي يرغب في إبقائها صعبة. |
| **شدة الموازنة (Balancing Preset)** | 1. معادلة واحدة ثابتة ومحافظة على الهوية<br>2. نمطان: "محافظ / الحفاظ على التنوع" و"صارم / تنافسي" | البدء بـ **النمط المحافظ المتزن** كإعداد افتراضي قياسي يحقق العدالة دون تعقيد الواجهة. |
| **أداء فحص القوائم الكبيرة** | فحص ملفات CNS عبر خيط الخلفية `Dispatchers.IO` | تنفيذ الفحص والمعالجة بشكل غير متزامن مع شريط تقدم لمنع أي تجميد للواجهة. |

---

## 7. الخلاصة والخطوة التالية (Conclusion & Gate)

- **نتيجة التدقيق:** المنظومة تمتلك بنية تحتية صلبة ومثبتة للحفظ الذري والاستعادة والخزينة وحماية الملفات، وميزة الموازنة التلقائية غير منفذة حالياً وجاهزة للبناء وفق التصميم المفصل أعلاه.
- **الالتزام بالقواعد:** لم يتم تعديل أي ملف في كود المصدر ولم يتم المساس بـ `libmain.so` أو محرك اللعبة، وتم الاكتفاء بالتدقيق والتوثيق والتصميم الهندسي الكامل.
- **بانتظار توجيه وموافقة المستخدم:** بعد اعتماد هذا التقرير والتصميم، يمكن الانتقال في المرحلة القادمة إلى التنفيذ الفعلي للخوارزمية والواجهات واختبارات الوحدة المصاحبة.
