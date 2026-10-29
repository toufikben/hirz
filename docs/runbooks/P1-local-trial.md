# P1 — تجربة حيّة محلية (Runbook)

**الهدف:** إثبات بوابة P1 على هاتفين حقيقيين:
- SOS من هاتف المرافق يُظهر إنذارًا بملء الشاشة على هاتف الولي.
- زمن الوصول مقيس على 10 تجارب.
- الخروج من مكان يصل إنذارًا P1.
- صمت الهاتف يظهر P2 بلا صوت.
- رمز الإكراه يُطلق P0 والشاشة عند المرافق عادية.

## 0) المتطلبات (Task 0)
- مشروع Firebase اسمه `hirz`، فيه تطبيق Android بالحزمة `dz.hirz.app` وبصمة SHA-1 للنسخة التجريبية `00:52:77:62:D9:52:81:D8:26:7C:4E:2F:E1:67:CC:81:D9:CC:10:3B` (مفتاح التطوير الثابت في `app/debug.keystore`، تطبعه CI أيضًا)، و**Authentication → Google** مفعّل.
- ملف `google-services.json` محفوظ في سرّ GitHub باسم `GOOGLE_SERVICES_JSON`، مرمّزًا base64:
  ```bash
  base64 -w0 google-services.json | gh secret set GOOGLE_SERVICES_JSON --repo toufikben/hirz
  ```
  بعدها أعد تشغيل CI، فيبني APK يعمل فيه الدخول.
- مفتاح حساب الخدمة (service account) خارج المستودع، مثلًا `C:\Users\Administrator\hirz-secrets\firebase-admin.json`.
- قاعدة Supabase مع امتداد `postgis` مفعّل، ورابط الاتصال (session pooler).
- `cloudflared` مثبّت، وJDK 21 أو أحدث (JDK 25 موجود على الحاسوب ويعمل).

**تنبيهات قبل البدء:**
- كل إعادة تشغيل لـ`cloudflared` تعطي عنوانًا جديدًا يجب إدخاله في الهاتفين.
- يُنصح بشبكة جيدة للخادم: إن تأخر إرسال الإشعارات أكثر من 10 ثوانٍ قد يعيد المرافق إرسال SOS، والخادم يتجاهل التكرار.

## 1) تشغيل الخادم
1. نزّل الخادم والتطبيق من آخر تشغيل CI ناجح:
   ```bash
   gh run download --repo toufikben/hirz -n hirz-core -D hirz-core
   ```
   ```bash
   gh run download --repo toufikben/hirz -n hirz-debug-apk -D apk
   ```
2. في PowerShell عرّف المتغيرات. لا تضعها في أي ملف داخل المستودع:
   ```powershell
   $env:HIRZ_DATABASE_URL = "jdbc:postgresql://<pooler-host>:5432/postgres?sslmode=require"
   $env:HIRZ_DATABASE_USER = "postgres.<project-ref>"
   $env:HIRZ_DATABASE_PASSWORD = "<كلمة السر>"
   $env:HIRZ_FIREBASE_PROJECT_ID = "<firebase-project-id>"
   # مفتاح تشفير كلمة سر العائلة: 32 بايت عشوائية بصيغة base64. ولّده مرة واحدة واحفظه في core.env، فتغييره يُفقد كلمات السر المحفوظة.
   # التوليد: $b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
   $env:HIRZ_DATA_KEY = "<base64-32-bytes>"
   $env:GOOGLE_APPLICATION_CREDENTIALS = "C:\Users\Administrator\hirz-secrets\firebase-admin.json"
   $env:JAVA_HOME = "C:\Program Files\Java\jdk-25.0.2"
   ```
3. شغّل الخادم:
   ```powershell
   .\hirz-core\bin\server.bat
   ```
   علامة النجاح: يجيب `http://localhost:8080/health` بـ `{"status":"ok"...}`.
4. افتح النفق في نافذة ثانية:
   ```powershell
   cloudflared tunnel --url http://localhost:8080
   ```
   انسخ العنوان `https://….trycloudflare.com`.

## 2) الهاتفان
1. ثبّت التطبيق على الهاتفين:
   ```bash
   adb install -r apk/app-debug.apk
   ```
2. على كل هاتف: افتح «الإعدادات» من شاشة الاختيار، والصق عنوان النفق.
3. **هاتف الولي:**
   1. اختر «وليّ / مالك» وادخل بحساب Google.
   2. أنشئ الدائرة.
   3. من «الدائرة» أضف طفلًا.
   4. ضع رمز إلغاء (مثلًا 1234) ورمز إكراه (مثلًا 9876)، ثم «ربط جهاز مرافق».
   5. من بطاقة «جاهزية الإنذار» اسمح بالإشعارات، وبملء الشاشة، وبتجاوز «عدم الإزعاج».
4. **هاتف المرافق:**
   1. اختر «مرافق».
   2. امسح رمز QR أو اكتب الرمز، ثم وافق.
   3. اسمح بالموقع «طوال الوقت».
   4. من إعدادات البطارية استثنِ حِرز من التحسين.
5. على هاتف الولي: من «الأماكن» ارسم دائرة حول البيت بهامش 50 م.

## 3) السيناريوهات
1. **10 × SOS:** على المرافق اضغط مطوّلًا ثانيتين. بعد كل محاولة:
   1. انتظر ظهور الإنذار على هاتف الولي.
   2. افتحه.
   3. أغلق الحادث من «الحوادث» بسبب «أمر عائلي».
2. **خروج من المكان:** امشِ بالمرافق إلى أبعد من الحد بـ 100 م على الأقل. يجب أن يصل إنذار P1. سجّل عدد القراءات والمسافة الظاهرة في الحادث.
3. **الصمت:** أطفئ هاتف المرافق 6 دقائق. يجب أن يظهر P2 في «الحوادث» **بلا صوت**.
4. **الإكراه:** أدخل رمز الإكراه في «إلغاء التنبيه». يجب أن يصل P0 للولي، وأن تُظهر شاشة المرافق «تم» كالمعتاد.

## 4) القياس
1. في محرر SQL في Supabase نفّذ الاستعلام التالي، واحفظ النتيجة (مصفوفة JSON) في `docs/evidence/p1-trials/sos.json`:
   ```sql
   SELECT json_agg(x) FROM (
     SELECT i.id, (extract(epoch FROM i.opened_at) * 1000)::bigint AS "openedAtMs",
            json_agg(json_build_object(
              'memberId', d.member_id,
              'sentAtMs', (extract(epoch FROM d.sent_at) * 1000)::bigint,
              'deliveredAtMs', (extract(epoch FROM d.delivered_at) * 1000)::bigint,
              'openedAtMs', (extract(epoch FROM d.opened_at) * 1000)::bigint,
              'error', d.error)) AS deliveries
     FROM incidents i JOIN deliveries d ON d.incident_id = i.id
     WHERE i.rule = 'SOS' GROUP BY i.id) x;
   ```
2. لخّص النتيجة:
   ```bash
   node tools/latency/summarize.mjs docs/evidence/p1-trials/sos.json
   ```
- **ما يُقاس:** المدة من فتح الحادث على الخادم إلى وصول الإيصال `delivered` ثم `opened`. الطرفان بساعة الخادم نفسها، فلا انحراف ساعات.
- **ما لا يُقاس:** المدة بين ضغط الزر ووصول الإشارة إلى الخادم. تُقدَّر من سجلّ الخادم، وتُذكر في التقرير كما هي.

اكتب `docs/evidence/P1-gate.md`، وفيه:
- قيم p50 وp95.
- طراز الهاتفين ونسخة Android.
- الشبكة المستعملة: Wi-Fi أو 4G، ومع أي مشغّل.
- لقطات: الإنذار على الشاشة المقفلة، والخريطة مع عمر الموقع ودقته، والحادث مع الإيصالات.
- كل فشل كما وقع.
