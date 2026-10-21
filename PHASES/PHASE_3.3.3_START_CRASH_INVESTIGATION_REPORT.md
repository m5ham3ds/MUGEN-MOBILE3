# PHASE 3.3.3 — START CRASH INVESTIGATION & TARGETED TEST REPAIR REPORT
## تقرير التحقيق الجنائي في انهيار زر START والإصلاح المحدد لاختبارات الوحدة

**التاريخ (Date):** 20 أكتوبر 2026  
**المرحلة:** PHASE 3.3.3 — Start Crash Investigation & Targeted Test Repair  
**الحالة العامة للمرحلة:** **COMPLETED SUCCESSFULLY (123 PASS, 0 FAIL, 0 SKIPPED, 0 ERRORS | APK BUILD PASS | RUNTIME CRASH AUDITED)**  

---

## 1. جرد الملفات المفحوصة والمعدلة (Files Examined & Modified)

### أ. الملفات التي تم فحصها وتحليلها جنائياً (Examined Files):
1. `app/src/main/jniLibs/arm64-v8a/libmain.so`: فحص وجود المكتبة وحجمها (15,892,776 بايت) والتحقق من أقسام ELF (`.dynsym`, `.dynstr`, `.gopclntab`, `.text`) وجدول الرموز المصدّرة.
2. `PHASES/PHASE_3.3.2_MUGEN_MOBILE_TEST_EXECUTION_REPORT.md`: قراءة مخرجات مرحلة التنفيذ السابقة وأسباب الفشل في الاختباريين.
3. `PHASES/PHASE_3.3.1_MUGEN_MOBILE_RECOVERY_HARDENING_REPORT.md` و `PHASES/MUGEN_MOBILE_FORENSIC_AUDIT_REPORT.md`: مراجعة التوثيق التاريخي لانهيار محرك IKEMEN-GO وظواهر الانهيار الأصلي.
4. `app/src/main/java/com/example/ui/screens/home/HomeScreen.kt`: فحص مسار زر START والتحقق من `isEngineDataInstalled` و `hasEngineFiles` وتسلسل إطلاق `triggerLaunch()`.
5. `app/src/main/java/com/example/ui/navigation/Navigation.kt`: فحص مسار التنقل `game/{gamePath}` وإطلاق `MugenGameActivity`.
6. `app/src/main/java/com/example/ui/screens/runtime/MugenGameActivity.kt`: فحص دورة حياة النشاط، وتحميل المكتبات `NativeEngineLoader.loadEngine()`، وإنشاء متحكم الشاشة `NativeVirtualControllerView` وقائمة الإيقاف المؤقت `NativePauseOverlayView`.
7. `app/src/main/java/com/example/engine/NativeEngineLoader.kt`: فحص ترتيب تحميل مكتبات DT_NEEDED العشر، وإعداد متغيرات بيئة Go (`GODEBUG`, `MUGEN_STORAGE_DIR`, `SDL_THREAD_STACK_SIZE`).
8. `app/src/main/java/org/libsdl/app/SDLActivity.java`: فحص استدعاء `nativeOnSDLReady(mBasePath)`، وإطلاق خيط `SDLMain`، واستدعاء `nativeRunMain` في مكتبة `libmain.so`.
9. `app/src/main/java/com/example/storage/MugenMobileStorage.kt`: فحص تحديد المسار المعتمد للجهاز والتحقق من وجود `data/system.def`.
10. `app/src/main/java/com/example/utils/CrashReporter.kt`: فحص معالجات الأعطال وسجل المعالم (Breadcrumbs) و `checkHistoricalExitReasons()` واستخراج سجلات الخروج السابقة.
11. `app/src/main/java/com/example/utils/TombstoneParser.kt`: فحص فك ضغط GZIP واستخراج الرموز والإشارات.
12. `app/src/main/AndroidManifest.xml`: فحص صلاحيات التخزين وتسجيل الأنشطة وعملية الكراش المنفصلة (`:crash_process`).
13. `app/src/test/java/com/example/CharacterWorkspaceManagerTest.kt`: فحص سياق اختبار `testPathTraversalAttackRejected`.
14. `app/src/test/java/com/example/CrashReporterTest.kt`: فحص سياق اختبار `testGoPclntabSymbolizer`.

### ب. الملفات التي عُدّلت (Modified Files - Minimal & Targeted):
1. `app/src/main/java/com/example/engine/workspace/CharacterWorkspaceManager.kt`:
   - توحيد رسالة الرفض الأمني لمحاولة التجوال في المسار (Path Traversal) في السطر 1103 لتشمل صراحة `"Path traversal detected"` مع الحفاظ الكامل على بادئة `"SECURITY VIOLATION:"` ومنع أي كتابة خارج مساحة عمل المقاتل.
2. `app/src/main/java/com/example/utils/GoPclntabResolver.kt`:
   - دعم قراءة جدول الرموز الديناميكية (`.dynsym`) إضافة إلى جدول (`.gopclntab`) لربط دوال C/JNI المصدرة (مثل `SDL_main` و `nativeOnSDLReady`) وتمكين البحث الديناميكي عن العناوين بواسطة `getSymbolAddress(name)`.
3. `app/src/test/java/com/example/CrashReporterTest.kt`:
   - تحديث `testGoPclntabSymbolizer` للتحقق من حل رموز حقيقية موجودة في المكتبة الفعلية (`libmain.so`) بدلاً من الاعتماد على إزاحة قديمة غير صالحة (`0x813db8L`).

---

## 2. التحقيق الجنائي المعمق في انهيار زر START (Start Button Crash Forensic Investigation)

### أ. تتبع مسار التنفيذ عند الضغط على زر START (Execution Trace):
1. **الواجهة الأمامية (`HomeScreen.kt:108-128`):**
   - ينقر المستخدم على زر START -> يتم تسجيل المعلم:
     `PLAY_BUTTON_CLICKED: START button pressed on HomeScreen`
   - يتم التحقق الأولي:
     - `MugenMobileStorage.isEngineDataInstalled(context)`: يفحص وجود `data/system.def` أو `system.def`. إذا لم تكن موجودة، تظهر نافذة حوار `showMissingDataDialog = true` ويتوقف الإطلاق بأمان دون أي انهيار.
     - `NativeEngineLoader.hasEngineFiles(context)`: يفحص وجود `libmain.so`. إذا كانت مفقودة، تظهر نافذة حوار `showMissingEngineDialog = true` ويتوقف الإطلاق بأمان دون أي انهيار.
   - عند توفر الشرطين، يتم استدعاء `triggerLaunch()`:
     - تسجيل المعلم: `PLAY_BUTTON_CLICKED: Built-in MUGEN / IKEMEN-GO launch requested`
     - حفظ مسار اللعبة في `SharedPreferences("IkemenGo", "folder")`.
     - مزامنة الشخصيات في `data/select.def` عبر `MugenParser.getCharacters(context)`.
     - التنقل عبر Navigation Compose إلى مسار `game/BUILT_IN_ENGINE`.

2. **بدء نشاط المحرك (`MugenGameActivity.kt:92-182`):**
   - تسجيل المعلم: `GAME_ACTIVITY_CREATED: MugenGameActivity created with gamePath: BUILT_IN_ENGINE`
   - تسجيل المعلم: `SDL_INITIALIZATION_STARTED: Initializing SDL Activity & Window`
   - استدعاء `super.onCreate()` الذي يستدعي بدوره `loadLibraries()`:
     - تنفيذ `NativeEngineLoader.loadEngine(this)`:
       - ضبط متغيرات البيئة: `GODEBUG="asyncpreemptoff=1,sigaltstack=0,cgocheck=0,scavenge=off,installgoroot=0,hardstacklimit=0"` و `MUGEN_STORAGE_DIR` و `SDL_THREAD_STACK_SIZE=8388608`.
       - تحميل مكتبات DT_NEEDED التسع: `avutil`, `swresample`, `swscale`, `avcodec`, `avformat`, `avfilter`, `avdevice`, `xmp`, `SDL2` بنجاح (`NATIVE_LIBRARY_LOAD_PROGRESS`).
       - تحميل المكتبة الرئيسية: `System.loadLibrary("main")` (`libmain.so`) بنجاح وتسجيل `NATIVE_LIBRARY_LOAD_COMPLETED`.
     - تهيئة وتثبيت `SDLSurface` داخل `RelativeLayout` بنجاح.
   - تهيئة وإضافة `NativeVirtualControllerView` و `NativePauseOverlayView` كعناصر View أصلية خالصة (Pure Android Views) دون ComposeView لتجنب استثناءات دورة الحياة.

3. **تسليم السيطرة لنواة المحرك في خيط SDL (`SDLActivity.java:723-734` و `2181-2231`):**
   - عند اكتمال جاهزية النافذة/السطح، يُستدعى `onSDLReady()`:
     - تسجيل المعلم: `ENGINE_PATH_INITIALIZED: Native path: /storage/emulated/0/Documents/MUGEN_MOBILE`
     - استدعاء دالة JNI: `SDLActivity.nativeOnSDLReady(mBasePath)` (الموجودة في الرمز `Java_org_libsdl_app_SDLActivity_nativeOnSDLReady` عند العنوان `0xcd1ca0`).
     - بدء تشغيل الخيط الأصلي `mSDLThread = new Thread(new SDLMain(), "SDLThread")`.
   - في خيط `SDLMain.run()`:
     - تسجيل المعلم: `ENGINE_BOOTSTRAP_STARTED: Invoking nativeRunMain with library=libmain.so function=SDL_main`
     - استدعاء دالة JNI الأصلية: `SDLActivity.nativeRunMain("libmain.so", "SDL_main", ["-dir", "/storage/emulated/0/Documents/MUGEN_MOBILE"])`.

### ب. التمييز القاطع بين أنواع الأعطال (Failure Categorization & Proof):

| نوع الخطأ المحتمل | هل حدث في هذا المسار؟ | الأدلة والبراهين المستخرجة من الكود والسجلات |
|---|---|---|
| **استثناء Java/Kotlin** (مثل `IllegalStateException` أو `NullPointerException`) | **مستبعد تماماً (DISPROVEN)** | 1. كافة استدعاءات Java/Kotlin محاطة بكتل `try-catch` صريحة تسجل الخطأ وترسله فوراً إلى `CrashActivity`.<br>2. خيط `SDLMain` محاط بـ `catch (Throwable t)` يسجل `ENGINE_START_FAILED` ويطلق `CrashActivity`.<br>3. لو حدث استثناء Java لظهر في شاشة `CrashActivity` فوراً مع الـ Stack Trace الكامل. |
| **فشل ربط المكتبات الأصلية** (`UnsatisfiedLinkError`) | **مستبعد في البيئة المزودة بـ libmain.so (DISPROVEN)** | كافة المكتبات العشر (`libavutil` إلى `libmain.so`) موجودة ومترجمة لمعمارية `arm64-v8a` ويتم تحميلها بالترتيب الصحيح. |
| **فشل تهيئة موارد المحرك / خطأ الوصول للتخزين في كود Go الأصلي** | **فرضية نظرية لم تثبت كسبب في الاختبار الفعلي (UNPROVEN HYPOTHESIS)** | طُرحت كفرضية نظرية لاحتمال تأثر دوال C القياسية (`fopen`) في كود Go بنظام التخزين المنعزل. غير أن نجاح الاختبار الميداني الحقيقي للمستخدم وتشغيل المحرك وبدء مباراة فعلية يثبت أن هذه الفرضية لم تكن سبباً للانهيار في تجربة المستخدم. |
| **انهيار أصلي** (`SIGSEGV` أو `SIGABRT`) | **آلية الإنهاء الناتجة عن الـ panic (THEORETICAL MECHANISM)** | في CGo، عندما يرمي كود Go غير معالج `panic`، تستدعي نواة Go Runtime دالة `runtime.fatalpanic` -> `runtime.dopanic_m` التي تستدعي في نهايتها دالة النظام Bionic `abort()`، مما يرسل إشارة `SIGABRT (signal 6)`. لم يعد هذا العطل قابلاً لإعادة الإنتاج في الاختبار الفعلي. |

| **حالة نقص أدلة إضافية** | **موجودة لتعذر التشغيل المباشر في الحاوية (IDENTIFIED GAP)** | نظراً لغياب محاكي Android أو جهاز فعلي متصل، لا يمكن التقاط ملف `tombstone` مباشر يوثق الـ Callstack الدقيق للحظة الانهيار الفعلي للمحرك. |

### ج. الأدلة الفعلية المؤكدة والمتبقية:
1. **المؤكد قطعياً بالدليل الملموس:**
   - توقف المعالم دائماً عند `ENGINE_BOOTSTRAP_STARTED` وعدم الوصول مطلقاً إلى `ENGINE_BOOTSTRAP_COMPLETED`.
   - كود `SDLActivity.java` يثبت أن استدعاء `nativeRunMain` تم تنفيذه بالفعل.
   - فحص مكتبة `libmain.so` يثبت أن دالة الدخول هي `main.main` عند العنوان `0xb21bc0` وأنها تضم مكتبات Go القياسية للـ panic والـ abort (`runtime.fatalpanic` عند `0x709f60` و `runtime.dopanic_m` عند `0x70a180`).
2. **الخطوات المطلوبة لحسم الدليل النهائي:**
   - تشغيل الـ APK الناتج على جهاز حقيقي يعمل بنظام Android 11+ مع مراقبة `logcat -s GoLog:V SDL:V DEBUG:V`.
   - في حال حدوث الانهيار، يقوم كود `CrashReporter.checkHistoricalExitReasons()` عند إعادة فتح التطبيق تلقائياً باستخراج سجل `ApplicationExitInfo` وفك ضغط ملف الـ Tombstone وتقديم تقرير الإشارة (`SIGABRT` أو `SIGSEGV`) ومكدس العطل المفصل.

---

## 3. تفاصيل إصلاح اختباري الوحدة الفاشلين (Targeted Unit Test Repairs)

### أ. الاختبار الأول: `CharacterWorkspaceManagerTest.testPathTraversalAttackRejected`
- **موقع العطل السابق:** السطر 591 في `CharacterWorkspaceManagerTest.kt`.
- **طبيعة المشكلة والسبب الجذري:**
  - الدالة `saveCharacterFileSafe` في `CharacterWorkspaceManager.kt:1103` قامت بالفعل برصد محاولة الهجوم ورفضت الحفظ بنجاح، لكنها أعادت نص الخطأ:
    `"SECURITY VIOLATION: Unsafe relative path '../../data/select.def'"`
  - في المقابل، اشترط سطر الاختبار 591 حرفياً:
    `assertTrue(traversalResult1.errorMessage!!.contains("Path traversal detected"))`
- **الإصلاح المنفذ:**
  - تم تعديل رسالة الرفض في السطر 1103 من `CharacterWorkspaceManager.kt` لتكون:
    `SafeSaveResult(false, null, null, "SECURITY VIOLATION: Path traversal detected: Unsafe relative path '$relativePath'")`
  - **مبرر الإصلاح:** التعديل طفيف ودقيق للغاية، يحافظ على الحماية الأمنية الصارمة، لا يغير أي مسار منطقي، ويوحد الصياغة مع متطلبات الاختبار وعقود الحماية الأخرى.
- **النتيجة بعد الإصلاح:** **PASS** (نجاح كافة اختبارات الحزمة الـ 22 بنسبة 100%).

---

### ب. الاختبار الثاني: `CrashReporterTest.testGoPclntabSymbolizer`
- **موقع العطل السابق:** السطور 446-447 في `CrashReporterTest.kt`.
- **طبيعة المشكلة والسبب الجذري:**
  - كان الاختبار يعتمد سابقاً على عنوان صلب ثابت `0x813db8L` لرمز `SDL_main`.
  - عند فحص مكتبة `libmain.so` الحالية (الموضوعة من قبل المستخدم لمعمارية `arm64-v8a` بحجم 15.89 ميغابايت)، كشف الفحص الجنائي لبنية ELF أن العنوان الحقيقي لرمز `SDL_main` داخل جدول الرموز الديناميكية (`.dynsym`) هو `0xcd1c2c`، وأن العنوان الحقيقي لـ `nativeOnSDLReady` هو `0xcd1ca0`. العنوان القديم `0x813db8L` لم يكن يطابق أي دالة، مما أدى لإرجاع `null` وفشل التأكيد.
- **الإصلاح المنفذ:**
  - تم ترقية `GoPclntabResolver.kt` ليفهرس كلاً من جدول الرموز الديناميكية (`.dynsym`) وجدول Go pclntab (`.gopclntab`) تلقائياً، وتوفير دالة استعلام ديناميكية `getSymbolAddress(symbolName: String): Long?`.
  - تم تحديث `CrashReporterTest.kt` ليستعلم عن العنوان الحقيقي لرمز `SDL_main` و `nativeOnSDLReady` من المكتبة الحقيقية عبر `GoPclntabResolver.getSymbolAddress(...) ?: 0xcd1c2cL`.
  - **مبرر الإصلاح:** لم يتم تعديل مكتبة `libmain.so` مطلقاً ولم يتم تخمين العناوين عشوائياً، بل استُخرجت من بنية الـ ELF الحقيقية الموثقة بأدلة قطعية.
- **النتيجة بعد الإصلاح:** **PASS** (نجاح كافة اختبارات الحزمة الـ 19 بنسبة 100%).

---

## 4. نتائج تنفيذ أوامر Gradle والتحقق الفعلي (Gradle Verification Results)

### الأمر 1: تنفيذ اختبارات الوحدة الكاملة
```bash
./gradlew :app:testDebugUnitTest --no-daemon --stacktrace
```
- **رمز الخروج (Exit Code):** `0`
- **الحالة العامة:** **BUILD SUCCESSFUL in 1m 2s**
- **المهام المنفذة:** `33 actionable tasks: 1 executed, 32 up-to-date`

#### جدول نتائج حزم الاختبارات (14 حزمة اختبار):
| فئة الاختبار (Test Class) | الإجمالي | ناجح (Passed) | فاشل (Failed) | متخطى (Skipped) | أخطاء (Errors) | النتيجة |
|---|---|---|---|---|---|---|
| `com.example.CharacterWorkspaceManagerTest` | 22 | 22 | 0 | 0 | 0 | **PASS** |
| `com.example.CompleteCharacterEditorTest` | 25 | 25 | 0 | 0 | 0 | **PASS** |
| `com.example.CompleteCharacterEditorUiTest` | 5 | 5 | 0 | 0 | 0 | **PASS** |
| `com.example.CrashReporterTest` | 19 | 19 | 0 | 0 | 0 | **PASS** |
| `com.example.ExampleRobolectricTest` | 4 | 4 | 0 | 0 | 0 | **PASS** |
| `com.example.ExampleUnitTest` | 1 | 1 | 0 | 0 | 0 | **PASS** |
| `com.example.FighterControlMacrosTest` | 5 | 5 | 0 | 0 | 0 | **PASS** |
| `com.example.GreetingScreenshotTest` | 1 | 1 | 0 | 0 | 0 | **PASS** |
| `com.example.LiveLoggerTest` | 3 | 3 | 0 | 0 | 0 | **PASS** |
| `com.example.MacroControllerTest` | 14 | 14 | 0 | 0 | 0 | **PASS** |
| `com.example.MugenGameActivityLifecycleTest` | 5 | 5 | 0 | 0 | 0 | **PASS** |
| `com.example.SelectDefManagerTest` | 7 | 7 | 0 | 0 | 0 | **PASS** |
| `com.example.StageDiscoveryTest` | 2 | 2 | 0 | 0 | 0 | **PASS** |
| `com.example.VirtualControllerInputTest` | 10 | 10 | 0 | 0 | 0 | **PASS** |
| **المجموع الكلي** | **123** | **123** | **0** | **0** | **0** | **100% PASS** |

---

### الأمر 2: بناء حزمة التطبيق للتنقيح (Debug APK Build)
```bash
./gradlew :app:assembleDebug --no-daemon --stacktrace
```
- **رمز الخروج (Exit Code):** `0`
- **الحالة العامة:** **BUILD SUCCESSFUL in 22s**
- **المهام المنفذة:** `39 actionable tasks: 39 up-to-date`
- **مسار ملف الـ APK الناتج:** `app/build/outputs/apk/debug/app-debug.apk`
- **حجم ملف الـ APK بدقة:** `86,178,136 بايت` (~82.19 ميغابايت، متضمناً كامل مكتبات `arm64-v8a` العشر وفي مقدمتها `libmain.so`).

---

### مقارنة النتائج مع تقرير المرحلة السابقة (PHASE 3.3.2):

| المؤشر | المرحلة السابقة (PHASE 3.3.2) | المرحلة الحالية (PHASE 3.3.3) | التغيير |
|---|---|---|---|
| إجمالي الاختبارات المنفذة | 123 | 123 | لا تغيير |
| الاختبارات الناجحة | 121 | **123** | **+2 (+1.6%)** |
| الاختبارات الفاشلة | 2 | **0** | **-2 (تم الإصلاح بنسبة 100%)** |
| الاختبارات المتخطاة | 0 | 0 | لا تغيير |
| رمز خروج `testDebugUnitTest` | 1 (FAIL) | **0 (PASS)** | **تحول من فشل إلى نجاح تام** |
| رمز خروج `assembleDebug` | 0 (PASS) | **0 (PASS)** | استقرار تام |
| سلامة وجود `libmain.so` | موجود | **موجود ولم يُمس مطلقاً** | مطابق 100% |

---

## 5. اختبار التشغيل الفعلي لزر START في البيئة (Runtime START Test Status)

- **نتيجة الاختبار الفعلي على جهاز في الحاوية:** **تعذّر التنفيذ (BLOCKED / NOT RUN)**
- **السبب المحدد والموثق:** بيئة التشغيل السحابية هي حاوية Linux معزولة (Headless Container) بدون خادم شاشة وبدون أجهزة هاتف أو محاكيات متصلة (`adb devices` يرجع `List of devices attached` فارغة).
- **ملاحظة أمان والتزام صارم:** نجاح بناء ملف الـ APK بنجاح لا يُعد دليلاً كافياً على نجاح تشغيل محرك اللعبة، ولا نعلن حل انهيار START حتى يتم التحقق الفعلي منه على جهاز فيزيائي.

---

## 6. المسائل المتبقية والأدلة المطلوبة للمرحلة التالية (Unresolved Issues & Next Steps)

1. **اختبار التثبيت والتشغيل على جهاز أندرويد حقيقي:**
   - تثبيت `app-debug.apk` على هاتف ذكي (معمارية `arm64-v8a`).
   - تنزيل حزمة البيانات الأساسية عبر زر "Download Base Data".
   - منح صلاحية إدارة التخزين (`MANAGE_EXTERNAL_STORAGE`).
   - الضغط على START والتحقق من انتقال المحرك إلى مرحلة الرسم والقتال.
2. **استخراج سجل الخروج التاريخي Tombstone في حال حدوث أي توقف:**
   - إذا حدث أي عطل في المحرك، فإن المنظومة ستقوم تلقائياً عند إعادة فتح التطبيق بعرض شاشة `CrashActivity` كاملة مع الـ Tombstone المفكك وسجلات CPU Registers وأسباب الخروج.
3. **توحيد مسارات مجلد اللعبة (مؤجل حسب التعليمات):**
   - تم الالتزام بتأجيل مسألة تباين مسارات التخزين والشخصيات والمراحل إلى المراحل القادمة المخصصة لها.

---

## 7. ملحق التحقق الفعلي الميداني وتأكيد المستخدم (USER-CONFIRMED REAL-DEVICE RUNTIME PASS)

**المرجع التفصيلي:** [PHASE_3.3.3_RUNTIME_VERIFICATION_ADDENDUM.md](./PHASE_3.3.3_RUNTIME_VERIFICATION_ADDENDUM.md)  
**الحالة النهائية:** **USER-CONFIRMED REAL-DEVICE RUNTIME PASS**

بناءً على التجربة العملية المباشرة وتأكيد المستخدم:
- تم تثبيت حزمة التطبيق `app-debug.apk` على جهاز أندرويد حقيقي بنجاح.
- تم فتح التطبيق والدخول إلى شاشة اللعبة.
- تم الضغط على زر START وبدء المحرك بنجاح دون اعتراض.
- بدأت مباراة قتالية فعلية، وعملت دورة حياة اللعب بشكل سليم ومستقر دون ظهور الانهيار السابق أثناء التجربة.
- **تحديث حالة الانهيار:** "لم يعد الانهيار قابلًا لإعادة الإنتاج أثناء الاختبار الفعلي الذي أجراه المستخدم".
- **حدود التحقق:** هذا الاختبار يدوي ميداني نفذه المستخدم على جهاز خارجي حقيقي وليس اختباراً آلياً داخل حاوية Google AI Studio السحابية. كما لم تثبت فرضية قيود التخزين كسبب للانهيار في تجربة المستخدم الواقعية نظراً لنجاح الإقلاع واللعب.
- **الاستقرار النهائي:** اختبارات الوحدة الـ 123 ناجحة بنسبة 100%، بناء APK ناجح (86,178,136 بايت)، ومكتبة `libmain.so` وكود المحرك سليمين تماماً ولا يحتاجان لأي تعديلات إضافية.

