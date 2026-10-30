# P3 — تشغيل staging على Oracle Cloud (Runbook)

**الهدف:** Core وTraccar على عنوان عام: HTTPS للتطبيق، ومنفذا الأجهزة 5023 (GT06) و5013 (H02). القاعدة تبقى Supabase.

## 0) المتطلبات
- حساب Oracle Cloud Free (البطاقة الدولية).
- آلة **Ampere A1** (2 OCPU / 12 GB ضمن المجاني)، Ubuntu 24.04، وعنوان IPv4 عام محجوز.
- اسم نطاق أو نطاق فرعي يشير إلى ذلك العنوان، مثلًا `staging.<domain>` (DuckDNS مقبول للتجربة).
- مشروع Supabase الحالي (PostGIS مفعّل) وملف `firebase-admin.json`.

## 1) الشبكة في Oracle
في VCN → Security List للشبكة الفرعية، أضف قواعد Ingress (TCP، المصدر `0.0.0.0/0`) للمنافذ **80، 443، 5023، 5013** فقط. لا تفتح 8082 ولا 5055.

## 2) الآلة
```bash
sudo apt-get update && sudo apt-get install -y docker.io docker-compose-v2 ufw git
sudo usermod -aG docker $USER   # ثم أعد الدخول
sudo git clone https://github.com/toufikben/hirz /opt/hirz/repo
sudo /opt/hirz/repo/infra/staging/firewall.sh
```

## 3) الأسرار (لا شيء منها في المستودع)
كل أوامر الخادم تُشغَّل بـ `sudo`: الملفات السرية ملك root وبصلاحية 600، و`docker compose` يقرؤها كـ root.
```bash
sudo install -m 600 /opt/hirz/repo/infra/staging/core.env.example /opt/hirz/core.env
sudo nano /opt/hirz/core.env
openssl rand -base64 32   # HIRZ_DATA_KEY (نفس قيمة P1/P2 إن كانت موجودة: تغييرها يُفقد كلمات سر العائلات)
openssl rand -hex 32      # HIRZ_TRACCAR_SECRET (64 حرفًا)
openssl rand -hex 24      # HIRZ_TRACCAR_DB_PASSWORD
```
- `HIRZ_PUBLIC_BASE_URL=https://<host>`، `HIRZ_DEVICE_HOST=<host أو IP>` (العنوان الذي تُرسل إليه الأجهزة).
- `HIRZ_TRACCAR_URL=http://traccar:8082`. اترك `HIRZ_TRACCAR_USER`/`HIRZ_TRACCAR_PASSWORD` فارغين حتى الخطوة 5.
- `GOOGLE_APPLICATION_CREDENTIALS=/opt/hirz-secrets/firebase-admin.json`. Core يعمل داخل الحاوية بالمستخدم 10001، فالملف له:
  ```bash
  sudo install -m 400 -o 10001 -g 10001 firebase-admin.json /opt/hirz/firebase-admin.json
  ```

## 4) Traccar أولًا
```bash
cd /opt/hirz/repo/infra/staging
sudo ./render.sh                                   # يكتب /opt/hirz/traccar.xml ويتحقق منه
sudo HIRZ_CORE_TAG=main HIRZ_HOST=<host> docker compose up -d traccar
```

## 5) مدير Traccar (مرة واحدة)
واجهة Traccar غير منشورة. على الآلة، اربطها بـ localhost فقط ما دمت تحتاجها:
```bash
sudo docker run --rm -d --name traccar-ui --network hirz_hirz -p 127.0.0.1:8082:8082 alpine/socat:1.8.0.0 tcp-listen:8082,fork,reuseaddr tcp-connect:traccar:8082
```
ومن حاسوبك افتح نفق SSH ثم المتصفح على `http://localhost:8082`:
```bash
ssh -L 8082:localhost:8082 ubuntu@<ip>
```
- أنشئ الحساب الأول فورًا (يصبح المدير)، ثم من **Settings → Server** عطّل **Registration**.
- ضع هذا الحساب في `HIRZ_TRACCAR_USER`/`HIRZ_TRACCAR_PASSWORD` داخل `/opt/hirz/core.env`.
- عند الانتهاء: `sudo docker stop traccar-ui`.

## 6) Core والنسخ الاحتياطي
```bash
sudo HIRZ_HOST=<host> ./deploy.sh <sha من آخر تشغيل CI على main>     # أو main
( sudo crontab -l 2>/dev/null; echo "30 2 * * * /opt/hirz/repo/infra/staging/backup.sh >> /opt/hirz/backup.log 2>&1" ) | sudo crontab -
```
`deploy.sh` ينتظر حتى يجيب `https://<host>/health`، وإلا يطبع آخر 80 سطرًا من سجل Core.
تحقق أن pg_dump ليس أقدم من نسخة Supabase (`select version()`)، وإلا ضع `PG_IMAGE=postgres:17-alpine` في سطر cron.

## 7) التحقق (يُسجَّل في `docs/evidence/P3-gate.md`)
- `curl -fsS https://<host>/health`.
- من خارج الآلة: `nmap -Pn -p 22,80,443,5013,5023,5055,8082 <ip>` → المفتوح فقط 22، 80، 443، 5013، 5023.
- `docker compose ps` كل الخدمات `running`، وsha الصورة.
