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
انسخ `infra/staging/core.env.example` إلى `/opt/hirz/core.env` واملأه:
```bash
sudo cp /opt/hirz/repo/infra/staging/core.env.example /opt/hirz/core.env && sudo chmod 600 /opt/hirz/core.env
openssl rand -base64 32   # HIRZ_DATA_KEY (نفس قيمة P1/P2 إن كانت موجودة: تغييرها يُفقد كلمات سر العائلات)
openssl rand -base64 48   # HIRZ_TRACCAR_SECRET (32 حرفًا على الأقل)
openssl rand -hex 24      # HIRZ_TRACCAR_DB_PASSWORD
```
- `HIRZ_PUBLIC_BASE_URL=https://<host>`، `HIRZ_DEVICE_HOST=<host أو IP>` (العنوان الذي تُرسل إليه الأجهزة).
- `HIRZ_TRACCAR_URL=http://traccar:8082`، و`HIRZ_TRACCAR_USER`/`HIRZ_TRACCAR_PASSWORD`: حساب مدير Traccar (الخطوة 5).
- `GOOGLE_APPLICATION_CREDENTIALS=/opt/hirz-secrets/firebase-admin.json`، وانسخ الملف إلى `/opt/hirz/firebase-admin.json` (mode 600).

## 4) التشغيل
```bash
cd /opt/hirz/repo/infra/staging
sudo ./render.sh                              # يكتب /opt/hirz/traccar.xml ويتحقق منه
export HIRZ_HOST=<host>
./deploy.sh <sha من آخر تشغيل CI على main>     # أو main
```
`deploy.sh` ينتظر حتى يجيب `https://<host>/health`، وإلا يطبع آخر 80 سطرًا من سجل Core.

## 5) مدير Traccar (مرة واحدة)
واجهة Traccar غير عامة. افتح نفق SSH من حاسوبك:
```bash
ssh -L 8082:localhost:8082 ubuntu@<ip> "docker exec -it hirz-traccar-1 true; sleep infinity"
```
ثم من الآلة نفسها: `docker compose port traccar 8082` غير منشور، فاستعمل `docker run --rm --network hirz_hirz -p 127.0.0.1:8082:8082 alpine/socat tcp-listen:8082,fork,reuseaddr tcp-connect:traccar:8082` على الآلة، ثم افتح `http://localhost:8082` من حاسوبك عبر النفق.
- أنشئ الحساب الأول فورًا (يصبح المدير)، ثم من **Settings → Server** عطّل **Registration**.
- ضع هذا الحساب في `HIRZ_TRACCAR_USER`/`HIRZ_TRACCAR_PASSWORD` وأعد `./deploy.sh`.

## 6) النسخ الاحتياطي
```bash
( crontab -l 2>/dev/null; echo "30 2 * * * /opt/hirz/repo/infra/staging/backup.sh >> /opt/hirz/backup.log 2>&1" ) | crontab -
```

## 7) التحقق (يُسجَّل في `docs/evidence/P3-gate.md`)
- `curl -fsS https://<host>/health`.
- من خارج الآلة: `nmap -Pn -p 22,80,443,5013,5023,5055,8082 <ip>` → المفتوح فقط 22، 80، 443، 5013، 5023.
- `docker compose ps` كل الخدمات `running`، وsha الصورة.
