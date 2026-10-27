# استضافة خادم Traccar لمطوّر مقيم في الجزائر — تقرير بحثي

Contexte: un développeur solo à Alger, produit de sécurité enfantine (kidguard), backend Traccar auto-hébergé, besoin: VPS Linux ~2 vCPU/4 GB toujours allumé + domaine + HTTPS. Budget: ~0 € les premières semaines, puis 3–4 €/mois.

Plan du rapport:
1. Les fournisseurs EU bon marché acceptent-ils réellement un résident algérien? (Hetzner, Contabo, Netcup, OVHcloud)
2. Hébergeurs algériens / paiement en DZD (CIB, Edahabia, virement)
3. Voies vraiment gratuites ou quasi-gratuites — et leur honnêteté (Oracle, Fly.io, GCP e2-micro, tunnels)
4. Domaine + HTTPS (.xyz/.online, .dz, Cloudflare, Let's Encrypt)
5. Moyens de paiement internationaux disponibles pour un particulier algérien
6. Tableau de recommandation finale

Légende: chaque affirmation est sourcée par URL; ce qui n'a pas pu être vérifié est marqué **[UNVERIFIED]**.

---

## 1. الفودرالات الأوروبية الرخيصة: هل تقبل فعلاً مكتتباً مقيماً في الجزائر؟

خلاصة مبكرة: **المشكلة ليست السعر بل بوابة الـ KYC/verification والدفع الدولي.** كل الأربعة يطلبون تحققاً من الهوية والعنوان، والتجارب المبلغ عنها من دول شمال أفريقيا متقلبة. لا يوجد فودر منهم يحظر الجزائر رسمياً بشكل صريح في الوثائق التي تم الوصول إليها — لكن هذا ≠ قبول مضمون.

### Contabo
- التحقق إلزامي لكل الطلبات تقريباً: «Contabo is legally required to perform customer data verification for your orders» ويطلبون وثيقة عنوان (جواز سفر/رخصة سياقة أو فاتورة خدمات باسم وعنوان مطابقين)، مع احتمال «manual verification» يدوي للطلب. المصدر: [Contabo Help — Why do I need to verify my purchase?](https://help.contabo.com/en/support/solutions/articles/103000348466-why-do-i-need-to-verify-my-purchase-) (تم جلب النص والتحقق منه).
- حالة الطلب يمكن تتبعها بعد الدفع: [How can I check the status of my order?](https://help.contabo.com/en/support/solutions/articles/103000268796-how-can-i-check-the-status-of-my-order-)
- مشاكل دفع بالبطاقة موثقة في KB الرسمي: [Why Did My Payment Fail?](https://contabo.com/blog/kb/103000342048-why-did-my-payment-fail/)
- طبقة anecdotal (يُنظر إليها كرواية مستخدمين لا كسياسة): ريدّيت يسأل عن إلزامية التحقق: [Contabo Mandatory verification? : r/VPS](https://www.reddit.com/r/VPS/comments/1b87wua/contabo_mandatory_verification/) — محتوى النقاش لم يُجلب كاملاً **[نصThread UNVERIFIED]**.
- الانطباع العام القابل للدفاع: Contabo عملياً أكثر تساهلاً مع البطاقات الأجنبية من Hetzner، لكن البطاقة يجب أن تكون **Visa/Mastercard دولية صالحة للدفع الإلكتروني**، وهذا هو عائق الجزائر الحقيقي (انظر §5)، وليس الفودر نفسه.

### Hetzner
- لدى Hetzner FAQ رسمي لمكافحة الاحتيال يفرض تحققاً (هوية/عنوان) ويُرجَح أنه يشدد على الدول خارج SEPA/خارج الاتحاد الأوروبي: [Fraud prevention FAQ — Hetzner Docs](https://docs.hetzner.com/general/security-and-identify/fraud-prevention-faq/) — **الصفحة لم تُجلب بنجاح (JS-only)، فالمضمون التفصيلي [UNVERIFIED]**، لكن وجودها وارتباطها بنقاشات HN/ريدّيت موثق:
- HN 2025: «Hetzner asking for passport for new account?» — يبين أن جواز السفر يُطلب حتى من مكتتبين أوروبيين/غربيين: [news.ycombinator.com/item?id=46050904](https://news.ycombinator.com/item?id=46050904)
- ريدّيت (تجربة سلبية معروفة في المجال): «Hetzner banned me after passport verification»: [r/VPS](https://www.reddit.com/r/VPS/comments/1pjyg4a/hetzner_banned_me_after_passport_verification/) — anecdote، لم يُقرأ نصه كاملاً **[UNVERIFIED]**.
- Hetzner لا يقدم تجربة مجانية؛ الدفع ببطاقة ائتمانية/PayPal (حساب PayPal موثق)/SEPA debit — التأكيد النهائي لقائمة الطرق لكل دولة **[UNVERIFIED]**. للمقيمين في خارج EU/until SEPA: يُبلّغ عن رفض طلبات أكثر من الفودرات الأخرى (anecdotal).
- **حكم عملي**: أعلى نسبة رفض/تعطّل حساب في هذا المشروع. لا يُنصح به كخيار أول لشخص بدون بطاقة دولية مثبتة.

### Netcup (ألمانيا، رخيص جداً لكن بألمانية/إنجليزية)
- صفحة طرق الدفع الرسمية: [netcup.com Payment Methods](https://www.netcup.com/en/helpcenter/documentation/general/payment-methods) — **لم تُجلب (JS-only)**؛ من الذاكرة الوثائقية Netcup يقبل: بطاقات (Visa/MC/Amex)، PayPal، SEPA (Lastschrift قد يتطلب حساباً أوروبياً)، وGiropay/فواتير داخل DE. التفاصيل الدقيقة **[UNVERIFIED]**.
- Netcup معروف بمراجعة يدوية للحسابات الجديدة من خارج ألمانيا/الاتحاد الأوروبي، وتجميد حتى دفع أول ناجح بصاحب نفس اسم البطاقة (anecdotal، لم أعثر على مصدر جزائري محدد **[UNVERIFIED]**).

### OVHcloud
- لا يوجد حظر موثق للجزائر؛ توثيق الحساب والـ KYC موجود: [OVHcloud account management FAQ](https://docs.ovhcloud.com/en/guides/account-and-service-management/account-information/faq-account-management) و[Creating an OVHcloud account](https://support.us.ovhcloud.com/hc/en-us/articles/115001286870-Creating-an-OVHcloud-account) — **لم تُجلب نصوصها هنا**، فمدى تشدد KYC لمقيمين جزائريين **[UNVERIFIED]**.
- anecdotal: نقاش ريدّيت «OVH & Verification»: [r/VPS](https://www.reddit.com/r/VPS/comments/1s12q6e/ovh_verification/)؛ شكاوى تحقق موثقة أيضاً في Trustpilot: [reviews p.2](https://www.trustpilot.com/review/ovhcloud.com?page=2).
- ميزة ذات صلة: OVH لديه داتا سنتر في **المغرب** (قرب/زمن استجابة أفضل للجزائر): [Datacenter Morocco](https://www.ovhcloud.com/en/datacenter/africa/morocco/) — لكنه للمقيمين بالجزائر لا يغيّر شيئاً في الـ billing.

### هل بحثت تحديداً عن تجارب جزائريين؟
بحث مركّز عن مطورين جزائريين/مغاربة/تونسيين مع هذه الفودرات لم يُخرج مصادر جزائرية صريحة قوية؛ أقرب ما وجدته عام (ريدّيت/HN أعلاه) + نتيجة واحدة تخص تونس على Google Play (غير ذات صلة بالفودرات). **إذن: «تجربة جزائري 2024–2026 مع Hetzner/Contabo/Netcup/OVH» = [UNVERIFIED] كمعلومة موثقة؛ اعتمادي على سياسة الفودر نفسها.**

---

## 2. البدائل الجزائرية: موجودة فعلاً، لكن خارج الـ budget

توجد فودرات جزائرية تبيع VPS بـ root كامل و Linux، وبعضها يقبل الدفع المحلي (CIB/Edahabia/virement) — لكن الأسعار بالـ DZD تجعل حتى أصغر بلان أعلى بكثير من 3–4 يورو/شهر (3–4 € ≈ 400–550 DZD فقط). أي أن «الأرخص نظرياً في الجزائر» هو في الحقيقة الأغلى مقارنة بالـ EU.

- **Cirtasoft** (تم الجلب والتحقق): Starter 1 vCPU/2 GB/60 GB SSD = **3,900 DZD/شهر** (+19% TVA، فوترة سنوية) ≈ 29 €/شهر؛ Advanced **2 vCPU/4 GB/120 GB = 5,900 DZD/شهر** ≈ 44 €/شهر؛ «Full root SSH access» مع Ubuntu/Debian/CentOS/Fedora/AlmaLinux/Rocky. طرق الدفع غير مذكورة في الصفحة **[طرق الدفع UNVERIFIED]**. المصدر: [cirtasoft.com/en/serveurs-vps-algerie](https://www.cirtasoft.com/en/serveurs-vps-algerie)
- **UBIKO (ubiko.host)** (تم الجلب والتحقق): LS2 Linux = 2 vCPU/4 GB/100 GB NVMe بـ **93,600 DZD/سنة (~7,800 DZD/شهر ≈ 58 €/شهر)**، «Accès Root» — لكن الداتا سنتر معلَن «USA / Europe / ASIA» وليس داخل الجزائر، وطرق الدفع المحلية غير مذكورة **[UNVERIFIED]**. المصدر: [ubiko.host/hebergement-vps-algerie](https://ubiko.host/hebergement-vps-algerie)
- **Icosnet**: فودر جزائري معروف (cloud/data center/ISP) يبيع VPS: [icosnet.com.dz/vps-algerie](https://icosnet.com.dz/vps-algerie/) — الصفحة JS-only ولم تُجلب أسعارها **[الأسعار وطرق الدفع UNVERIFIED]**؛ ملفهم: [datacenters.com/providers/icosnet](https://www.datacenters.com/providers/icosnet).
- **Algérie Télécom** يعرض خدمة hébergement للمؤسسات: [algerietelecom.dz — hebergement](https://www.algerietelecom.dz/en/entreprises/hebergement-de-site-web-prod14) — استضافة مواقع أكثر منها VPS بصلاحيات root **[التفاصيل UNVERIFIED]**.
- عن Djezzy Cloud / Ooredoo business cloud / Bidaya Cloud / Nebula / Startinet: لم أعثر خلال هذا البحث على صفحة VPS فعلية قابلة للتحقق **[UNVERIFIED — لا تُبنَ عليها الخطة]**. نتيجة navicosoft.com وcirtasoft-style وhostzealot هي تسويق SEO لفودرات أجنبية تدّعي «Algeria» — تحقق منها قبل الشراء.

**استنتاج §2**: إن فشل الدفع الدولي تماماً، فالملاذ المحلي هو Cirtasoft Starter (~3,900 DZD/شهر ≈ 29 €) — يجب تحذيره بوضوح أن هذا لا يندرج تحت «3–4 €/شهر»، بل خطة طوارئ مدفوعة بالـ DZD.

---

## 3. المسارات «المجانية»: أين تكون صادقة وأين تخدع

- **Oracle Cloud Always Free**: مجاني فعلاً (ARM Ampere حتى 4 ع/24 GB) لكن التسجيل يتطلب بطاقة للتحقق، ونسب رفض البطاقات الأجنبية مرتفعة ومبلّغ عنها بكثافة: [ITNEXT — Why Creating an OCI Free Tier Account Is So Hard](https://itnext.io/why-creating-an-oci-free-tier-account-is-so-hard-and-what-you-can-do-instead-7a06b83ec576)، [r/oraclecloud — Unable to Sign Up](https://www.reddit.com/r/oraclecloud/comments/1h0b1g8/help_unable_to_sign_up_for_oracle_cloud_free_tier/)، [Oracle Free Tier FAQ](https://www.oracle.com/apac/cloud/free/faq/)، وصفحة المنطقة الإفريقية [oracle.com/africa/cloud/free](https://www.oracle.com/africa/cloud/free/). قبول بطاقة جزائرية/دولة الجزائر في التسجيل: **[UNVERIFIED — الأرجح رفض؛ اختبرها قبل الاعتماد عليها]**. وحتى مع النجاح: Always Free بلا SLA وقد تُسحب الموارد — لا تضع أطفالاً في مرحلة التتبع الحقيقي عليها.
- **Fly.io**: لم يعد مجانياً — «free tier died»: [saaspricepulse — Fly.io Free Tier 2026](https://www.saaspricepulse.com/tools/flyio)، [expresstech.io — after the free tier died](https://expresstech.io/7-fly-io-alternatives-in-2026-real-pricing-after-the-free-tier-died/)، [fly.io/pricing](https://fly.io/pricing/)، [community.fly.io — Billing for small applications](https://community.fly.io/t/billing-for-small-applications/24434) (بطاقة مطلوبة وفوترة حتى للتطبيقات الصغيرة). لا PayPal **[UNVERIFIED]**. ⇒ ليس حلاً صفرياً.
- **Google Cloud e2-micro Always Free**: موثّق في [cloud.google.com/free](https://cloud.google.com/free) و[docs — free features](https://docs.cloud.google.com/free/docs/free-cloud-features) لكنه يتطلب **billing account ببطاقة ائتمان** عند التسجيل؛ دعم البطاقات/الدولة الجزائر: **[UNVERIFIED]**؛ وتحذيرات فوترة غير متوقعة حتى على free tier: [r/googlecloud](https://www.reddit.com/r/googlecloud/comments/1t3q4y4/free_tier_still_get_billed/)، [discuss.google.dev — charged despite free tier](https://discuss.google.dev/t/charged-95-for-compute-engine-e2-micro-despite-free-tier-usage/192936).
- **Hetzner trial مجاني**: لا يوجد شيء موثق **[UNVERIFIED]**.
- **GitHub Codespaces / Glitch-type ephemeral**: البيئات تنام وتُصفَّر ولا تملك منفذ TCP دائم 24/7 — **غير صالحة إطلاقاً لاستقبال أجهزة** (قول بنيوي: جهازي GT06 يحتاج socket listener دائماً عند IP:port ثابت).
- **الأنفاق مع home connection — النقطة الحاسمة (TCP وليس HTTP)**:
  - **cloudflared Tunnel (free)**: ingress عام لـ TCP خام غير متاح في الخطة المجانية؛ الـ TCP الموجود (`cloudflared access`) يفترض عميل cloudflared على جهاز العميل — مستحيل على tracker. فشل موثّق في forum Traccar الرسمي: «one can not use CF tunnels to protect Traccar protocol ports» (منفذ 5055/GT06): [Traccar forum thread](https://www.traccar.org/forums/topic/traccar-port-8082-and-port-5055-not-working-with-cloudflare-tunnel/) — **تم الجلب والتحقق من المضمون**. نقاش Cloudflare community عن TCP: [How To Setup a TCP Cloudflared Tunnel](https://community.cloudflare.com/t/how-to-setup-a-tcp-cloudflared-tunnel/372176).
  - **Tailscale / Tailscale Funnel**: Funnel للـ HTTP(S) فقط على 443/80/8443 **[من المعرفة العامة — UNVERIFIED في هذا البحث]**؛ لا يستقبل GT06 socket. (Tailscale بين جهازين يعمل لكن الجهاز لا يركّب agent.)
  - **ngrok free**: يتضمن عادة نفق TCP واحد بمنفذ عشوائي لكل إعادة تشغيل **[UNVERIFIED هنا]**؛ المنفذ العشوائي + أن أجهزة GT06 تُضبط غالباً على IP ثابت لا hostname ⇒ غير صالح للإنتاج.
  - **CGNAT لدى ISP جزائري**: حتى PC منزلي دائم التشغيل لا يستقبل TCP من الخارج بلا IPv4 عام: [Traccar forum — self-hosted with CG-NAT problem](https://www.traccar.org/forums/topic/self-hosted-server-with-cg-nat-problem/). إمكانية طلب IPv4 عام من Algérie Télécom **[UNVERIFIED — اسأل الدعم]**.
  - **الخلاصة**: الأنفاق المجانية تصلح فقط لتجربة واجهة Web/App عبر HTTPS من هاتفك؛ مسار الـ device ingestion الحقيقي الوحيد بدون VPS شبه معدوم. البدائل الجادة: VPS بـ IP ثابت (~€4) أو Tinc/self-hosted proxy على VPS.

---

## 4. الدومين + HTTPS

- **.xyz**: أول سنة ~$2 ([Namecheap .xyz](https://www.namecheap.com/domains/registration/gtld/xyz/)) لكن **فخ التجديد حقيقي**: إشعار رفع أسعار .xyz: [r/Domains](https://www.reddit.com/r/Domains/comments/1v6tc52/price_change_notice_for_xyz_domains_increasing/) وتحليل: [rayname — .xyz Rising Renewal Costs](https://www.rayname.com/blog/xyz-domains-and-rising-renewal-costs-what-registrants-should-prepare-for)؛ مقارنة تجديديات بين الـ registrars: [tld-list.com/tld/xyz](https://tld-list.com/tld/xyz) والسعر الرسمي من Registry: [gen.xyz/pricing](https://gen.xyz/pricing) ⇒ التجديد ~$12–14/سنة؛ صمّم الميزانية السنوية عليه. (نمط .online/.site نفسه: رخيص أولاً ~$1–4 ثم تجديد بـ 10–30$: [blog.webhostmost — Domain Renewal Cost](https://blog.webhostmost.com/domain-renewal-cost/) — مصدر ثانوي.)
- **.dz**: السجل الرسمي [nic.dz](https://www.nic.dz/)؛ شرح شروط/خطوات .dz: [sakinadz](https://www.sakinadz.com/blog/services/nom-de-domaine-dz-obtenir-faut-il-prendre)؛ registrar معتمد: [hostarts.dz](https://hostarts.dz/nom-de-domaine-dz)؛ ومصدر يدّعي أنها مجانية: [octenium.com](https://octenium.com/domaine/extension/dz) — **مصادر متضاربة؛ أهلية الأفراد والتكلفة الحالية [UNVERIFIED]** — راسل DZ.NIC قبل أي وعد. دومين أجنبي رخيص + Cloudflare DNS يكفي للمشروع عملياً.
- **Cloudflare free tier**: DNS مجاني + proxy/HTTPS مجاني للوحة Traccar — لكن **سجل A الخاص بمنفذ TCP ضعه DNS-only (رمادي) بلا proxy** حتى لا يُحجب 5055 (توصية forum Traccar الموثقة أعلاه).
- **Let's Encrypt**: يعمل من أي بلد عبر HTTP-01 (يتطلب 80/443 مفتوحين على VPS — عادي) أو DNS-01 مع Cloudflare (لا يحتاج منافذ مفتوحة إطلاقاً) ⇒ ISP الجزائري لا يمثل عائقاً **[معرفة عامة — لم تُجلب وثيقة في هذا البحث]**.

---

## 5. مسارات الدفع الدولية لفرد جزائري — العائق الحقيقي للمشروع كله

- **البطاقات المحلية (Edahabia / CIB / ATM-Hebé/GIE-CB)**: بمنظومة التسوية الداخلية فقط؛ مقالة جزائرية متخصصة (2026) تنص أن CIB/Edahabia تخدم «منظومات محلية وليست شبكات تسوية دولية» ⇒ **لا تُقبل لدى Hetzner/Contabo/Netcup/OVH/Oracle/Fly/GCP**. المصدر (ثانوي، تم الجلب): [zoomalgerie.com/paypal-algerie](https://zoomalgerie.com/paypal-algerie/). هذا هو الجدار الأول للمشروع كله.
- **بطاقة Visa/Mastercard دولية من بنك جزائري**: موجودة كأفق (مثال معلن: Djezzy×BDL لإطلاق e-paiement ببطاقة Visa — مصدر Facebook ضعيف: [Le Chiffre d'Affaires](https://www.facebook.com/LeChiffredAffaires/posts/1263941502399877/)؛ ونقاش تهيئة الدفع Visa/MC محلياً: [webservices.dz](https://webservices.dz/faq-ssl/223-paiement-par-visa-et-mastercard-pour-les-sites-locaux-bientot-possible-en-algerie)). **الشروط الفعلية لفرد (compte en devise، سقف السحب، هل تُفعّل للـ e-commerce دولي؟) تختلف بين CPA/BEA/BDA/NEF/البريد: [UNVERIFIED — سؤال مصرفه هو المصدر الوحيد الموثوق]**. عملياً حتى البطاقات الصالحة تُرفض أحياناً لدى مزودات SaaS/كلاود بسبب BIN/3DS — **اختبرها بدفعة ~$2 قبل أي التزام**.
- **PayPal**: paypal.com له صفحات locale بالجزائر، ومقالة zoomalgerie تقول إن التسجيل بعنوان جزائري ممكن وأن الدفع يعمل ما سمحت البطاقة، بينما **الاستقبال** يتطلب «carte Visa confirmée ou compte bancaire américain»؛ الوضع الرسمي التاريخي أن الجزائر ليست دولة دعم كاملة لدى PayPal **[متنازع — UNVERIFIED]**. PayPal عملياً لن يفيدك كثيراً: الفودرات التي تقبله تشترط تمويلاً صالحاً لنفسها أصلاً. (صفحة PayPal عن البطاقات المقبولة: [paypal.com/dz/cshelp help612](https://www.paypal.com/dz/cshelp/article/quelles-cartes-bancaires-puis-je-utiliser-avec-paypal%C2%A0-help612?locale.x=fr_DZ))
- **Wise / Payoneer**: لم أعثر على مصدر قوي في هذا البحث؛ الوضع المتعارف عليه: Wise لا يوفر حسابات/بطاقات ل residents الجزائر وPayoneer يتطلب نشاطاً تجارياً أجنبياً مثبتاً **[UNVERIFIED في هذا الجلسة — لا تبني الخطة عليهما]**.
- **فودرات تقبل Crypto**: التداول/استخدام العملات المشفرة كان محظوراً تشريعياً في الجزائر (مرسوم 2015/qانون المالية 2018؛ نقاشات تنظيم لاحقة) **[UNVERIFIED هنا — تحقق قانونياً قبل أي فكرة من هذا النوع؛ لا أوصي به]**.

**الخلاصة الصادقة**: لا يوجد طريق جزائري *مضمون بالكامل* لدفع خدمة أجنبية اليوم. الرهان الوحيد الواقعي: **استخراج بطاقة Visa/Mastercard دولية من بنك جزائري وتفعيلها للإلكترونيات** — إجراء مصرفي (أسابيع) يسبق كل الكود.

---

## 6. التوصية النهائية — sequence عملي (1 € ≈ 135 DZD رسمي؛ السوق الموازي أعلى **[سعر الصرف آني — UNVERIFIED]**)

| المرحلة | أين يشتغل Traccar | الكلفة | مخاطرة الدفع | ملاحظات |
|---|---|---|---|---|
| **Week-0 lab (0 €، من اليوم)** | Docker على حاسوبه الشخصي + تطبيق الأندرويد على نفس الشبكة Wi‑Fi؛ cloudflared free **لواجهة الويب فقط** لتجربة الهاتف من الخارج | 0 € | صفر | لا جهاز GT06 حقيقي بعد — الأنفاق لا تستقبل device TCP (§3، موثق) |
| **أول اختبار حقيقي (يوليو/أغسطس: ~€4)** | **Hetzner CX22 (~€3.79)** أو Contabo VPS entry (~€5) أو Netcup (بديل) — Linux + Docker + nginx، IP ثابت، DNS A grey-cloud + Let's Encrypt | ~3–5 € | **مرهون entirely بنجاح البطاقة الدولية** (KYC مسبق: جواز + فاتورة) | خطة ب فوراً إن رُفض الدفع: **Cirtasoft Starter 3,900 DZD/شهر** (root + DZD، لكن ~29 €)، أو **محاولة Oracle Always Free** بالتوازي (إن قبلت بطاقته: صفر € لمراحل مبكرة، بلا SLA) |
| **الإنتاج (عند €3–4/شهر)** | نفس VPS: CX22→CX32 (~€7) حسب الحمل؛ دومين .xyz (~$2 أول سنة، **التجديد ~$12**) أو .com.dz؛ Cloudflare free DNS؛ LE بـ DNS-01 | 4–8 € شهرياً لاحقاً + ~1.5 €/شهر معدّل دومين | نفس شرط البطاقة | €3–4 تغطي Hetzner entry + دومين بمعدّل سنوي؛ راقب تجديد .xyz قبل فواته |

**ما يفعله اليوم تحديداً (ترتيب إزالة المخاطرة):**
1. **البنك غداً**: سؤال رسمي: «بطاقة Visa/Mastercard دولية مفعّلة للدفع الإلكتروني عبر الإنترنت (e-commerce)» + شروط التغذية والحدود — هذا حاسم لكل المشروع، وكل ما عداه تفصيل.
2. عند استلامها: **اختبار بدفع ~$2 لشراء دومين .xyz** — إن نجح فقد أثبتت البطاقة BIN+3DS وأصبح Hetzner/Contabo/OVH كلها متاحة نظرياً.
3. فتح حساب **Cloudflare مجاني** وتوثيقه (يجهّز DNS + LE لاحقاً، صفر كلفة).
4. **قبل كتابة الكود الجاد**: يقدّم طلب/تسجيل في Hetzner أو Contabo مع وثائق الهوية (جواز + فاتورة) ليمر الـ KYC مبكراً بدل أن يرفضوه يوم الإطلاق؛ Netcup/OVH اختياران ثانويان.
5. يعامل Cirtasoft/UBIKO كـ **خطة طوارئ** (3,900–7,800 DZD/شهر ≈ 9–13× الميزانية) — مدفوعة بالـ DZD لكنها ليست «الحل الرخيص».

## Sources
(كل الروابط مضمنة داخل الأقسام؛ أهمها هنا)
- [Contabo — Why do I need to verify my purchase?](https://help.contabo.com/en/support/solutions/articles/103000348466-why-do-i-need-to-verify-my-purchase-) · [Contabo — payment fail KB](https://contabo.com/blog/kb/103000342048-why-did-my-payment-fail/) · [r/VPS — Contabo mandatory verification](https://www.reddit.com/r/VPS/comments/1b87wua/contabo_mandatory_verification/)
- [Hetzner Fraud prevention FAQ](https://docs.hetzner.com/general/security-and-identify/fraud-prevention-faq/) · [HN — Hetzner asking for passport](https://news.ycombinator.com/item?id=46050904) · [r/VPS — Hetzner banned after passport](https://www.reddit.com/r/VPS/comments/1pjyg4a/hetzner_banned_me_after_passport_verification/)
- [Netcup Payment Methods](https://www.netcup.com/en/helpcenter/documentation/general/payment-methods) · [OVH account FAQ](https://docs.ovhcloud.com/en/guides/account-and-service-management/account-information/faq-account-management) · [r/VPS — OVH & Verification](https://www.reddit.com/r/VPS/comments/1s12q6e/ovh_verification/) · [OVH Datacenter Morocco](https://www.ovhcloud.com/en/datacenter/africa/morocco/)
- [Cirtasoft VPS Algérie](https://www.cirtasoft.com/en/serveurs-vps-algerie) · [UBIKO VPS Algérie](https://ubiko.host/hebergement-vps-algerie) · [Icosnet VPS](https://icosnet.com.dz/vps-algerie/) · [Icosnet على datacenters.com](https://www.datacenters.com/providers/icosnet) · [Algérie Télécom hébergement](https://www.algerietelecom.dz/en/entreprises/hebergement-de-site-web-prod14)
- [Traccar forum — Cloudflare Tunnel يفشل مع 5055](https://www.traccar.org/forums/topic/traccar-port-8082-and-port-5055-not-working-with-cloudflare-tunnel/) · [Traccar forum — CG-NAT](https://www.traccar.org/forums/topic/self-hosted-server-with-cg-nat-problem/) · [Cloudflare community — TCP tunnel](https://community.cloudflare.com/t/how-to-setup-a-tcp-cloudflared-tunnel/372176)
- [ITNEXT — OCI free tier signup](https://itnext.io/why-creating-an-oci-free-tier-account-is-so-hard-and-what-you-can-do-instead-7a06b83ec576) · [r/oraclecloud signup](https://www.reddit.com/r/oraclecloud/comments/1h0b1g8/help_unable_to_sign_up_for_oracle_cloud_free_tier/) · [Oracle Free FAQ](https://www.oracle.com/apac/cloud/free/faq/) · [oracle.com/africa/cloud/free](https://www.oracle.com/africa/cloud/free/) · [Fly.io pricing](https://fly.io/pricing/) · [saaspricepulse Fly free tier 2026](https://www.saaspricepulse.com/tools/flyio) · [cloud.google.com/free](https://cloud.google.com/free) · [r/googlecloud billed on free tier](https://www.reddit.com/r/googlecloud/comments/1t3q4y4/free_tier_still_get_billed/)
- [Namecheap .xyz $2](https://www.namecheap.com/domains/registration/gtld/xyz/) · [r/Domains .xyz increase](https://www.reddit.com/r/Domains/comments/1v6tc52/price_change_notice_for_xyz_domains_increasing/) · [rayname .xyz renewals](https://www.rayname.com/blog/xyz-domains-and-rising-renewal-costs-what-registrants-should-prepare-for) · [tld-list .xyz](https://tld-list.com/tld/xyz) · [gen.xyz pricing](https://gen.xyz/pricing)
- [nic.dz](https://www.nic.dz/) · [sakinadz .dz](https://www.sakinadz.com/blog/services/nom-de-domaine-dz-obtenir-faut-il-prendre) · [hostarts .dz](https://hostarts.dz/nom-de-domaine-dz) · [octenium .dz gratuit](https://octenium.com/domaine/extension/dz)
- [zoomalgerie — PayPal Algérie](https://zoomalgerie.com/paypal-algerie/) · [Djezzy×BDL Visa](https://www.facebook.com/LeChiffredAffaires/posts/1263941502399877/) · [webservices.dz Visa/MC](https://webservices.dz/faq-ssl/223-paiement-par-visa-et-mastercard-pour-les-sites-locaux-bientot-possible-en-algerie)

## ملاحظة منهجية (حدود هذا البحث)
- لم أعثر خلال الميزانية المحدودة على thread جزائري صريح 2024–2026 يروي نجاح/فشل تسجيل في Hetzner/Contabo/Netcup/OVH ⇒ كل ما يخص «تجربة جزائري» معمم من تجارب دول قريبة وأنباء عامّة وموسوم **[UNVERIFIED]**.
- صفحات Hetzner FAQ وNetcup وIcosnet رجعت JS-only للأداة ⇒ السياسات التفصيلية لم تُقرأ نصياً وأُبقيت **[UNVERIFIED]**.
