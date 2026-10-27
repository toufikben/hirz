# KidGuard v2 — Human Factors (Alarm Fatigue, Retention) & the Investigative Interface

Research retrieval report. Areas E1–E4. Prepared for the KidGuard v2 design owner (Algeria/MENA, Arabic-first, Traccar 6.16 + escalation relay).

---

## ⚠️ RETRIEVAL STATUS — READ THIS FIRST

This report ran under a hard budget (10 WebSearch + 6 WebFetch). **The search half worked; the fetch half mostly failed.** Of six WebFetch attempts:

| # | Target | Result |
|---|---|---|
| 1 | PMC12181921 — alarm-fatigue scoping review | Retrieved, but the returned text carried **no quantitative figures** |
| 2 | SAGE `10.1177/1541931215591141` — False Alarms vs. Misses | **HTTP 403 Forbidden** |
| 3 | FindLaw — *Easter v. State* (Md. Ct. Spec. App. 2015) | **HTTP 403 Forbidden** |
| 4 | ROSAP `dot_59041_DS1.pdf` — DOT review of alarms/alerts/warnings | **Unparseable PDF binary stream** |
| 5 | Justia — *Hale County A&M Transport v. City of Kansas City* | **HTTP 403 Forbidden** |
| 6 | NCMEC First-Responder Investigative Checklist (PDF) | **Unparseable PDF binary stream** |

**Consequence:** almost everything below is a *verified source inventory*, not verified content. Every claim is tagged:

- **V-C** = I retrieved and read the content myself.
- **V-E** = the search engine returned this exact title/URL/publisher, so the source exists and its title is real — **but I did not read the body.** Do not treat its contents as established.
- **INF** = my own arithmetic or reasoning. Not sourced. Labelled as such everywhere it appears.

I have deliberately **not** filled the gaps with remembered statistics. Where the brief asks for a number I could not verify (clinical false-alarm percentages, measured Android battery-drain percentages, CCTV retention days), I say so and point at the exact document that would settle it. Several sections are therefore thinner than the brief wanted, and E4 in particular is thin — as anticipated.

---

## E1 — Alarm fatigue and the cry-wolf effect

### What I actually verified

**V-C —** *Alarm fatigue in healthcare: a scoping review of definitions…* [80] is a real, retrievable scoping review on PubMed Central. The text I retrieved **defines alarm fatigue as desensitisation resulting from frequent alarms**, and — in the portion returned to me — **did not surface any percentage for false-alarm rates, any counted fatal/missed events, or any numeric compliance threshold.** I am flagging that explicitly because the widely-quoted "85–99% of clinical alarms are not actionable" figure is exactly the kind of number I would be guessing at if I asserted it here. **Unverified.**

**V-E — source map for E1** (real titles/URLs, contents unread):

| Ref | Title as returned | Venue / Tier | Why it matters |
|---|---|---|---|
| [81] | *Alarm Fatigue: A Patient Safety Concern* | AACN Advanced Critical Care, DOI `10.1097/nci.0b013e3182a903f9` — **Tier 1** | The canonical peer-reviewed framing of alarm fatigue as a patient-safety hazard; the DOI is confirmed in the URL |
| [82] | *Improving alarm management to reduce alarm fatigue in…* | ScienceDirect, PII `S0964339725003167` (Intensive & Critical Care Nursing) — **Tier 1** | An intervention study: what actually reduced alarm burden |
| [83] | *False Alarms vs. Misses: Subjective Trust as a Mediator between Reliability and Alarm Reaction Measures* | Proc. HFES Annual Meeting, DOI `10.1177/1541931215591141` — **Tier 1** | **The single most design-relevant paper found.** It is an experimental manipulation of alarm *reliability* measuring the resulting *reaction* — i.e. the direct empirical curve from false-alarm rate to response rate |
| [84] | *A Review of the Literature on Alarms, Alerts, and Warnings* | US DOT / ROSAP, `dot_59041_DS1.pdf` — **Tier 1** | Government literature review; the place a defensible "tolerable false-alarm rate" threshold would live |
| [85] | *A Process Model of Trust in Automation: A Signal Detection…* | DTIC `ADA618894` — **Tier 1** | Signal-detection formalisation of trust → compliance/dependence |
| [86] | *Understanding reliance on automation: effects of error type…* | PMC4307024 — **Tier 1** | Separates false alarms from misses in driving automation *reliance* — the automation-complacency / automation-bias question in the brief |
| [87] | *Clinical Alarm Awareness, Management Challenges, and…* | Research Square preprint `rs-7117686` — **Tier 2 (preprint, unreviewed)** | Survey-style data on how often clinicians are aware of / silence alarms |

### Analysis (INF unless stated)

1. **The brief's own premise is the load-bearing one.** The design already accepts that naive smartphone geofence *departure* detection is ~18% reliable in the real world. That is a **false-positive rate near 82% on the departure channel alone**. If KidGuard's P1 tier fires substantially off raw geofence departure, the effective false-alarm rate delivered to guardians is catastrophic by any human-factors standard — which is why the ≥50 m/100 m buffers and the 3-reading debounce exist. **The debounce is not a nice-to-have; it is the mechanism that makes the alert tier psychologically survivable.** That reasoning is INF, but the ~18% figure is given in the brief as prior verified research.

2. **The direction of the reliability→response relationship is not in question; its slope is.** Every source found ([80] definition, [81], [83], [85], [86]) treats rising false-alarm rate as degrading alarm response and trust, and treats misses and false alarms asymmetrically ([83]'s very title). What I **cannot** give you is the numeric slope — the "% response at X% reliability" curve — because [83] and [84] both failed to retrieve. **Any threshold quoted for KidGuard without reading [83] or [84] would be invented.**

3. **Asymmetry direction is the real design risk, and it cuts against the brief.** The cry-wolf literature frames *false alarms* as the hazard. But the automation-bias / reliance literature ([85], [86]) frames the opposite hazard: **users of a reliable-looking alarm stop doing their own checking.** For a child-safety product this is the more dangerous failure mode — a guardian who has KidGuard installed may relax their own supervision, so a *missed* detection costs more than it would have without the product. **This is a real, under-considered risk in the current architecture, and I have Tier-1 sources pointing at it even though I could not read them.** INF on the consequence; V-E on the sources.

4. **"Silence = alarm" is a double-edged mitigation.** Treating no-data as a signal is a legitimate answer to alarm fatigue (it converts a silent failure into an explicit one), but it *manufactures* false alarms from ordinary causes: dead battery, tunnel, Doze mode, killed background process, SIM out of coverage, Algeria connectivity dips. Given the brief's own prior research on Android push failure modes, **the dominant generator of P0/P1 in this product will not be a real incident — it will be an uploader that stopped reporting.** That is INF but it follows directly from stated architecture.

### Blunt assessment of the brief's two numbers

- **≤0.5 false P1 per 1000 child-days = 0.05% per child-day** (INF, pure arithmetic). Per child that is **one false P1 every 2000 child-days ≈ 5.5 years**. For a 5-guardian family it is one nuisance escalation across the whole group every ~400 child-days. **By any common-sense human-factors reading this is very strict, not lax.**
- **However: the metric as written is the wrong metric, and this is a design defect I can defend without a citation (INF).** A *false P1* is one nuisance event. A **false P0** is a heart attack, an unnecessary police call, and — after two or three of them — the uninstall. The target says nothing about P0 false positives, and nothing about *burstiness* (five false P1s in one week do far more damage than five spread over ten years, even at the same average rate).
- **On the 60-second ack window: I have no verified evidence either way and I will not pretend otherwise.** What I can say (INF) is that 60 s is a *latency* budget dressed as a *human* budget, and it is measured from notification delivery — but the brief's own prior research establishes that Android push delivery is itself unreliable and can be delayed arbitrarily. **A 60 s timer that starts on server-side dispatch will therefore fire escalations against guardians who never received the notification.** That generates escalation cascades that look like false alarms to the recipient and will be perceived as the app "crying wolf" — fatigue induced by *infrastructure*, not by *detection*. This is the highest-confidence criticism in this report.

---

## E2 — Retention and abandonment in real parental-monitoring products

**This is the weakest area of the report.** The only peer-reviewed-adjacent sources returned were blocked or unread; what surfaced instead was a wall of Tier-3 vendor content marketing.

### V-E source map

| Ref | Title as returned | Tier | Note |
|---|---|---|---|
| [88] | *Examining Parent Versus Child Reviews of Parental Control Apps on Google Play* (2019-08-05) | **Tier 1/2** | App-store-review mining study — directly on the brief's "review themes" ask. Body unread |
| [89] | *How Parental-Control Apps May be Counterproductive* — UCF News, 2018-04-02 | **Tier 2** | University newsroom reporting a peer-reviewed result; the headline itself asserts that these apps can be counterproductive |
| [90] | *About background location and battery life* — Android Developers | **Tier 2 (official platform doc)** | The authoritative reference for how Android restricts background location, and what an uploader app must do to avoid being battery-killed |
| [91] | *Battery drain across phones for a constant location tracking workload* (table from a ResearchGate-indexed publication, fig. ID 329972323) | **Tier 1/2** | **This is the measured-percentage source the brief asked for.** It is a per-device table for a constant-tracking workload. I could not read the numbers |
| [92] | *Why Do Parents Abandon Parenting Apps and How to Retain…* — ideausher.com | **Tier 3** | Vendor blog; not citable as evidence |
| [93] | *Protection or punishment? Navigating parental control apps…* — digitalchild.org.au | **Tier 2** | NGO/practitioner framing of child resistance |
| [94] | *How Long Is CCTV Footage Kept? 14–90 Days* — greatwhitesecurity.com | **Tier 3** | Vendor blog. Listed here as a *pointer*, not evidence — see E4 |

### Analysis

1. **Measured battery-drain percentages: NOT VERIFIED.** [91] is the right document and I could not read it. [90] is the right official guidance and I did not spend a fetch on it. **The specific ask — "measured percentages" — is an open gap.** What I can say safely (INF, from platform behaviour the brief already researched): on a repurposed older Android handset acting as a continuous position source, continuous high-accuracy GNSS plus cellular upload is a multi-hour-per-day battery cost, and Android's Doze/App-Standby/background-restriction machinery will kill a poorly-declared uploader. The mitigation set is well known (foreground service with a persistent notification, adaptive/passive location, batched upload, exemption from battery optimisation, and — for a dedicated child handset — device-owner/kiosk provisioning). **None of that is sourced here; it is engineering practice, and it must be verified against [90] before it goes into a spec.**

2. **"Installed after a scare, deleted when the scare passes" — no verified evidence found.** The brief names this as an important killer. The search returned no survey, cohort study or churn dataset confirming it. **Unverified.** The closest real signal is [89]'s headline claim that these apps may be *counterproductive*, which is about efficacy, not retention.

3. **What drives daily use rather than crisis use — no verified evidence found.** Nothing retrieved addressed habit formation, check-in rituals, or non-safety utility ("arrived at school" notifications) as retention levers. **Unverified. This is a genuine gap, not a retrieval failure dressed up as a finding.**

4. **The one structural point I can make with confidence (INF):** for KidGuard the retention risk is *inverted* relative to a normal consumer app. The child does not carry the app; the **guardians** carry it. Guardian-side retention is driven by (a) nuisance rate — see E1, and (b) whether opening the app gives something worth seeing on a normal day. A product whose only output is an alarm has no daily-use surface at all, so it will be judged entirely by its false-alarm behaviour. **That makes E1 and E2 the same problem, not two.**

---

## E3 — What investigators actually need from a civilian tracking system

### V-E source map (both primary court decisions were 403-blocked)

| Ref | Source | Tier | Status |
|---|---|---|---|
| [95] | ***Hale County A&M Transport, LLC v. City of Kansas City, Missouri***, U.S. District Court, W.D. Mo., No. 4:2012cv00265, opinion doc 104 (via Justia) | **Tier 1 — court decision** | Exists; **403, unread** |
| [96] | ***Easter v. State***, Maryland Court of Special Appeals (2015), FindLaw 1702388 | **Tier 1 — court decision** | Exists; **403, unread** |
| [97] | Unreported opinion, Maryland Courts, 2016/1533s15.pdf | **Tier 1 (unreported, weak precedential value)** | Exists; unread |
| [98] | *Authentication of Surveillance Video by Lay Witness Under Silent Witness Doctrine* — ediscoveryllc.com, 2024-12-09 | **Tier 3 (law-firm blog)** | Exists; unread. Useful as a *signpost* to the "silent witness" theory, which is the usual route for authenticating machine-generated records without a human eyewitness |
| [99] | *Navigating mobile device searches: Legal processes and best practices for law enforcement* — Police1, 2024-06-22 | **Tier 2 (trade press, practitioner audience)** | Exists; unread. The closest thing found to published guidance on what law enforcement expects when it takes device/location data |
| [100] | *Litigation Holds: Ten Tips in Ten Minutes* — U.S. District Court for the District of Nebraska, CLE material | **Tier 1 (federal-court-issued)** | Exists; unread. Directly on the preservation-duty question |
| [101] | *Spoliation: When the Duty to Preserve Data Outweighs the Obligation to Delete* — Troutman Pepper, 2024-08-05 | **Tier 2 (law firm)** | Exists; unread |
| [102] | *Using Legal Holds for Electronic Discovery*, T.M. O'Shea, 2014 (William Mitchell Law Review, indexed) | **Tier 1 (law review)** | Exists; unread |
| [103] | ACLU, *VICTORY! Federal Appeals Court Rules Warrant Required…*, 2013-10-22 | **Tier 2 (NGO advocacy)** | Exists; unread. Title indicates an appellate holding that a warrant is required for GPS tracking; **I have not verified which circuit, which case, or the precise holding** |

### What is established versus what is not

**General legal framework (stated as framework, NOT verified against retrieved primary sources in this session):** in US federal practice, machine-generated location records typically have to clear two independent gates — an **authentication** gate ("evidence describing a process or system and showing it produces an accurate result") and a **hearsay** gate (usually the business-records exception, which requires the record to have been made at or near the time by someone with knowledge, kept in the course of a regularly conducted activity, as a regular practice). Authentication of a *process* is usually satisfied by testimony from a custodian or a qualified witness who can describe how the system captures, timestamps and stores data. **This is the standard structure; the specific rule numbers and the case authority must be re-verified from [95] and [96] before anything is written into a KidGuard legal document.**

**Case law on consumer GPS-tracker data specifically: NOT VERIFIED.** Both cases that would have answered it were blocked. [95] is a civil case about a trucking company's GPS/telematics data; [96] is a Maryland criminal appeal. I found no retrieved holding, no quoted reasoning, no admissibility outcome. **Do not let a downstream report cite either case's holding — only its existence and citation.**

**Documented cases of consumer tracker data used in a child-abduction or missing-child case: NONE FOUND.** The search surfaced instead: a Western Australia report of a *mother charged* for attaching an AirTag to her own toddler [Tier 2, watoday.com.au, 2023-12-21 — not fetched], a vendor blog titled "Police Use GPS To Track Missing Children" [Tier 3], and Facebook posts [Tier 3, not citable]. **There is no verified instance in this retrieval of a consumer GPS tracker's location history being used as evidence in a child-abduction prosecution, and no verified investigator statement about its usefulness or shortcomings.** That absence is itself a finding worth reporting to the owner: **the product's evidentiary value is an assumption, not a demonstrated fact.**

**Published standard for a civilian handing over location history: NONE FOUND.** No government or police document specifying export format, time zone, coordinate reference system, device identity or completeness attestation was retrieved. The nearest thing is [99], a trade-press practitioner article. **This is a real gap — and, as noted in the design section, a gap KidGuard can turn into a differentiator rather than a liability.**

**Preservation duties:** [100], [101], [102] establish that a duty-to-preserve regime exists and that failure to preserve is sanctionable as spoliation. **I could not verify how that regime attaches to a private company holding a child's location history, whether a police request (as opposed to a litigation hold) triggers it, or what the retention guidance is.** In US practice the trigger is normally *reasonable anticipation of litigation*, not a police request — which is a meaningful distinction for a solo developer, because a police phone call may create no formal duty at all while creating enormous reputational and moral exposure. **INF, and it needs a qualified answer from counsel in Algeria and in any target market.**

**Self-hosting: does it help or hurt evidentiary weight?** — **INF, clearly labelled, and it hurts more than it helps.**
- *Hurts:* a self-hosted instance has no independent custodian. The person who controls the server is the person who can alter the data, and there is no third-party attestation, no SLA, no audit log held outside the family's control, and no established track record a court can take notice of. A defence lawyer's first question — "who had write access to this database?" — has an awkward answer when it is the child's father or a solo developer in Algiers. Self-hosting also collides with the hearsay/business-records route, which presumes a *regularly conducted business activity*; a family's private server is not a business.
- *Helps:* self-hosting avoids the commercial provider's data-retention limits and terms of service that may permit deletion, and it keeps the raw, unaggregated fix stream rather than a vendor's processed summary. Raw data with full precision generally authenticates better than a vendor's derived product.
- *Net:* **the evidentiary weakness of self-hosting is fixable by engineering; the loss of raw precision from a commercial provider is not.** Therefore: keep self-hosting, and buy the trustworthiness with append-only logs, cryptographic hashing, an independent time source and a documented chain of custody. That is a design answer to a legal problem, and it is the most valuable output of E3.

---

## E4 — The first hour: timelines the product must beat or feed

**Thin, as the brief anticipated. No quantitative timeline was verified.**

| Ref | Source | Tier | Status |
|---|---|---|---|
| [104] | *What You Should Do When Your Child Is First Missing* — OJJDP / U.S. Dept. of Justice, Office of Juvenile Justice and Delinquency Prevention | **Tier 1 — official government publication** | Exists; unread. This is the *family-facing* first-hour guidance, and it is the single best source for "where would a private app's data fit" |
| [105] | *Investigative Checklist for First Responders* — NCMEC | **Tier 1 — official** | Exists; **PDF unparseable, unread** |
| [106] | *Investigative Checklist* — National Criminal Justice Training Center / Fox Valley Technical College (`RS00307130.pdf`) | **Tier 1 — official training body** | Exists; unread |
| [107] | *Missing and Endangered Persons* — City of Little Falls PD policy PDF | **Tier 1 — local government policy** | Exists; unread. Useful as a concrete example of a written departmental missing-persons procedure |
| [108] | *How Long Is CCTV Footage Kept? 14–90 Days* — greatwhitesecurity.com | **Tier 3 — vendor blog** | **NOT evidence.** Title asserts a 14–90 day range. Unverified, jurisdiction-specific to Australia per the URL, and vendor-authored |

### Findings and gaps

1. **CCTV retention windows: NO CREDIBLE SOURCED FIGURE.** Every result was a Tier-3 vendor blog (evolutiondatadigital, secureredact, greatwhitesecurity, armex, technotrading, a LinkedIn pulse post). The range they converge on — roughly **days to a few months, with small-business and residential DVR/NVR systems at the short end** — is plausible and consistent across independent vendors, but **it is a consensus of marketing content, not a measurement, and it is not sourced to any regulator or study.** **Unverified.** Notably, no UK ICO / Surveillance Camera Code, no EU, and no Algerian source appeared. For KidGuard the practical implication is unchanged and I will state it as INF: **the app should treat nearby CCTV as perishable on a scale of days and tell the family to request it immediately**, because that is true whether the real number is 7 days or 30.

2. **How quickly police can obtain CDR/cell-site data: NOT VERIFIED.** The searches returned overwhelmingly law-firm marketing and legal-encyclopedia content about the *warrant requirement* (post-*Carpenter* US law) rather than about *elapsed time*. **No published timeline was found** — no figure for hours-to-days from request to delivery, and nothing at all on Algerian operator practice. [103] and the surrounding results confirm only that in the US a warrant is generally required for historical cell-site location information; the *delay* consequence of that requirement is asserted nowhere in what I retrieved. **Open gap.**

3. **"Golden hour" in missing-child investigations: the term was NOT found in any official document title or snippet.** What *was* found is better than the term: two Tier-1 official first-responder checklists ([105], [106]) and a Tier-1 official family-facing guide ([104]). The existence of a formal, ordered first-responder checklist is itself the finding — it means there **is** a documented sequence of first-hour actions, and a civilian app's data has a defined insertion point in it. **But I could not read the sequence, so I will not describe it.**

4. **Self-organised search in the first hour — benefit, risks, scene contamination: NOTHING FOUND.** No search was spent on it (budget), and nothing in the other results touched it. **Completely unverified.** I flag one INF caveat that the owner should treat seriously regardless: KidGuard's parallel fan-out to 2–7 guardians *is* a self-organised search mechanism. If the product tells five adults "your child left the safe zone" and they all drive off in different directions, the product has created the misdirected-effort problem the brief asked about — and if one of them finds something, they may contaminate it. **This is an unmitigated consequence of the current architecture and it is not addressed anywhere in the brief.**

---

## What this changes in the design — ما يجب تغييره في التصميم

> **تنبيه على الصدق:** معظم المصادر أدناه لم أتمكّن من قراءة محتواها (رُمِّز لها **V-E** = العنوان والرابط مؤكدان، المحتوى غير مقروء؛ **V-C** = مقروء؛ **INF** = استنتاجي أنا، بلا مصدر). لا يوجد هنا أي رقم مُختلَق. حيث لم أجد رقماً، قلتُ ذلك صراحة.

### 1. هدف «≤0.5 إنذار P1 كاذب لكل 1000 يوم-طفل» — الحساب صحيح لكنه المقياس الخطأ

- **الحساب (INF):** 0.5 لكل 1000 يوم-طفل = 0.05% يومياً = إنذار كاذب واحد لكل طفل كل **2000 يوم ≈ 5.5 سنة**. لأسرة فيها 5 أولياء = إزعاج واحد لكل المجموعة كل ~400 يوم-طفل. **هذا سقف صارم جداً، وليس متساهلاً.** من أراد تبرير تخفيفه يحتاج دليلاً، لا حدساً.
- **النقص القاتل في المقياس:** لا يقول شيئاً عن **P0 الكاذب**. الـ P1 الكاذب إزعاج؛ الـ P0 الكاذب أزمة قلبية وبلاغ شرطة غير مبرّر — وبعد اثنين أو ثلاثة، يُحذَف التطبيق. **أضف هدفاً مستقلاً وأشدّ: صفر P0 كاذب تقريباً، ويُقاس على حدة.** (INF)
- **النقص الثاني: التكتّل (burstiness).** خمسة إنذارات كاذبة في أسبوع واحد تُدمّر الثقة أكثر بكثير من خمسة موزّعة على سنة، رغم تساوي المعدّل. **المقياس يجب أن يكون مزدوجاً: المعدّل السنوي + الحدّ الأقصى في أي 7 أيام متتالية.** (INF)

### 2. نافذة الإقرار بـ60 ثانية: أخطر عيب معماري في التصميم الحالي

- **لا أملك دليلاً منشوراً يقول إنها قصيرة جداً أو كافية — وأرفض اختلاق مرجع.** لكن هناك نقد بنيوي مؤكد (INF):
- **المؤقّت يبدأ من الإرسال الخادمي، والتسليم نفسه غير موثوق.** بحثكم السابق أثبت أن دفع إشعارات Android يفشل ويتأخّر. **النتيجة: سلّم التصعيد يشتغل ضد أولياء لم يصلهم الإشعار أصلاً.** الولي يرى إشعارات متتالية «لم تُقرّ» وهو لم يرَ التنبيه الأول — فيختبر التطبيق كأنه **يكذب عليه**. هذا إجهاد إنذاري (alarm fatigue) سببه البنية التحتية لا الكشف، وهو **أسوأ أنواعه** لأنه غير مفهوم للمستخدم.
- **الإصلاحات المطلوبة:**
  1. **المؤقّت يبدأ من تأكيد التسليم (delivery receipt) لا من الإرسال.** إذا لم يُؤكَّد التسليم، لا تُحسب النافذة على هذا الولي — انتقل فوراً للتالي مع وسم «لم يُسلَّم».
  2. **اجعل النافذة قابلة للضبط، وافتراضها مختلف حسب الدرجة:** اقترح اختبار **P0 = 60 ث، P1 = 180 ث، P2 = 900 ث** (INF — قيم ابتدائية للتجربة، لا مُوصى بها كدليل).
  3. **قِس قبل أن تُثبّت:** في التجربة الأولى سجّل **توزيع زمن الإقرار الحقيقي** (P50/P95) عند أولياء حقيقيين. إذا كان P95 > 60 ثانية، فالنافذة خاطئة بحكم القياس لا بحكم المرجع. **هذا قابل للاختبار وهو ما يجب فعله.**

### 3. «الصمت = إنذار» سيصنع معظم الإنذارات الكاذبة — ويجب تمييز سبب الصمت

- **التحليل (INF):** بما أن الطفل لا يحمل التطبيق، فإن المصدر الأول لإنذارات P0/P1 لن يكون حادثة حقيقية، بل **جهاز رفع توقّف عن الإرسال**: بطارية ميتة، نفق، وضع Doze، عملية أُنهيت، شريحة خارج التغطية، انقطاع شبكة في الجزائر.
- **الإصلاح:** **لا تُصدر P0 من «صمت» مجرّد.** ميّز على الأقل ثلاث حالات: (أ) صمت + آخر نبض < X دقائق + بطارية معروفة منخفضة ⇒ **P2 «فقدان إشارة»**؛ (ب) صمت + تجاوز منطقة آمنة معروفة ⇒ P1؛ (ج) صمت + خرق نمط متوقّع (لم يصل المدرسة في وقته) ⇒ P1. **وأنشئ «نبض صحة الجهاز» (device heartbeat) منفصلاً عن نبض الموقع**، حتى تعرف الفرق بين «الجهاز مات» و«الطفل خارج التغطية».
- **اعرض السبب دائماً.** كل إنذار يجب أن يحمل سطراً يقول **لماذا** أُطلق: «خرج من منطقة المدرسة 3 قراءات متتالية، آخر موقع 14:07:22، الدقة 18 م». إظهار السبب هو أحد الإجراءات المخفِّفة لإجهاد الإنذار المذكورة في الملخّص المطلوب (انظر [80]، [82] — **V-E، لم أقرأ التفاصيل**).

### 4. التصدير الجنائي للأدلة: أكبر فرصة منتج في كل هذا التقرير

- **الحقيقة المؤكدة:** **لم أجد أي معيار منشور** يطلبه الشرطة من مدني يسلّم سجل مواقع، و**لم أجد أي حالة موثّقة** استُخدم فيها سجل متتبّع GPS استهلاكي كدليل في قضية اختطاف طفل. (V-E للسلب — بحثت ولم يظهر.) **قيمة منتجك القانونية افتراض، لا حقيقة مُثبتة.**
- **الاستنتاج (INF):** الفراغ فرصة. ابنِ «**حزمة الشرطة**» (Police Packet) بضغطة واحدة، بمواصفات تُرضي متطلبات المصادقة العامة (توثيق العملية/النظام، سجلات منتظمة، طابع زمني، سلسلة حيازة):
  - **تنسيق:** CSV + GPX/KML + PDF موقّع، كلها من نفس اللقطة.
  - **الزمن:** كل سجل بعمودين — **UTC ISO-8601** و**التوقيت المحلي (+01:00 الجزائر)** — لأن اختلاف المنطقة الزمنية هو أشهر سبب لرفض الخط الزمني.
  - **الإحداثيات:** **WGS84** صراحةً، مع عمود **الدقة الأفقية (HDOP/accuracy m)** وعمود **مصدر التثليث (GNSS / شبكة / WiFi)**. نقطة بلا دقة معروفة لا تُقرأ جنائياً.
  - **هوية الجهاز:** IMEI/ICCID/معرّف الجهاز، وطرازه، وإصدار البرنامج، ومعرف خادم Traccar.
  - **شهادة الاكتمال (completeness attestation):** قائمة صريحة بكل **الفجوات الزمنية** («لا بيانات بين 14:09 و14:31 — سبب مفقود»). **الفجوة المعلنة أقوى من سجل يبدو كاملاً**؛ السجل الكامل بلا فجوات يثير شكّ المحامي.
  - **سلامة البيانات:** تجزئة (hash) لكل ملف + **توقيع زمني خارجي** (TSA) + **سجل إلحاقي غير قابل للتعديل (append-only)** لأحداث النظام. سجّل مَن صدّر ومتى ولمن.
- **التخزين الذاتي (self-hosting): يُضعف الوزن القانوني، ويمكن إصلاح ذلك هندسياً (INF).** الخادم الخاص لا يملك حارساً مستقلاً للبيانات؛ من يتحكّم بالخادم يستطيع تعديلها، ولا توجد شهادة طرف ثالث. **لكن التخزين الذاتي يحفظ البيانات الخام غير المُجمَّعة، وهذا أثمن قضائياً من ملخّص مورد تجاري.** الخلاصة: **ابقَ على الاستضافة الذاتية، واشترِ الموثوقية بالتقنية**: إلحاقي فقط، تجزئة متسلسلة، مصدر وقت مستقل، وتوثيق سلسلة حيازة.

### 5. واجب الحفظ: لا تحذف صامتاً أبداً

- **المؤكد (V-E):** توجد منظومة «أمر حفظ» (litigation hold) وعقوبات على إتلاف الأدلة (spoliation) — انظر [100] (محكمة فيدرالية أمريكية)، [101]، [102].
- **غير المؤكد:** كيف تنطبق على شركة خاصة تحفظ سجل مواقع طفل، وهل **طلب من الشرطة** (لا دعوى) يُنشئ واجباً قانونياً. **استنتاجي (INF):** في المنطق الأمريكي المُعتاد المُحفِّز هو «توقّع معقول للتقاضي»، لا مكالمة شرطي — أي أن مكالمة الشرطة قد لا تُنشئ واجباً قانونياً إطلاقاً مع أنها تُنشئ تعرّضاً أخلاقياً وسمعوياً هائلاً. **يلزم رأي محامٍ جزائري قبل الإطلاق.**
- **الإصلاح المعماري المطلوب الآن:**
  1. **زر «تجميد الحفظ» (Preservation Hold):** يوقف أي حذف تلقائي لبيانات طفل معيّن فوراً، ويُوثّق الوقت والمُطلِق.
  2. **لا حذف صامت ولا دورة حذف افتراضية قصيرة.** اجعل الحد الأدنى للحفظ طويلاً بما يكفي (اقترح ≥ 12 شهراً افتراضياً — INF، قيمة قرار لا قيمة مصدر)، وأي حذف يسبقه إشعار للأولياء.
  3. **ورقة الحادثة (incident sheet) إلحاقية فقط**، ولا يمكن تعديل سطر فيها بعد كتابته — التصحيح بإضافة سطر جديد لا بالكتابة فوقه.

### 6. الساعة الأولى: ما يجب أن يفعله التطبيق فعلياً

- **غير المؤكد:** مدد حفظ كاميرات المراقبة (كل ما وجدته مدوّنات مورّدين من المستوى الثالث، والمدى المتداول «أيام إلى ~90 يوماً» — **غير مُثبت**)؛ والزمن الذي تستغرقه الشرطة للحصول على بيانات الأبراج الخلوية (**لم أجد أي رقم منشور**).
- **ما هو مؤكد (V-E):** توجد **قوائم فحص رسمية للمستجيب الأول** من NCMEC [105] ومن المركز الوطني للتدريب الجنائي [106]، ودليل رسمي موجّه للأسرة من وزارة العدل الأمريكية [104]. **أي أن هناك تسلسلاً موثّقاً لإجراءات الساعة الأولى، ولبيانات تطبيق مدني موضع محدّد فيه.** لم أقرأ التسلسل، فلن أصفه.
- **الإصلاحات (INF، وتبقى صائبة مهما كانت الأرقام الحقيقية):**
  1. **في أول 60 دقيقة، اعرض «حزمة الساعة الأولى»:** آخر موقع مؤكد + الدقة، الخط الزمني للمناطق الآمنة، خريطة نصف قطرها 500 م/1 كم، و**تنبيه صريح: «اطلب تسجيلات الكاميرات المجاورة الآن — تُمحى خلال أيام»**.
  2. **احسب أن بياناتك هي الأسرع وصولاً.** تطبيقك يملك الموقع في ثوانٍ؛ الشرطة تحتاج مذكرة للحصول على CDR. **هذه هي حجّة البيع الأساسية**، وهي مبنية على بنية النظام لا على رقم غير مُثبت.
  3. **⚠️ خطر لم يُعالَج في التصميم إطلاقاً (INF):** التوازي إلى 2–7 أولياء **هو بحد ذاته آلية بحث ذاتي التنظيم**. إذا قلتَ لخمسة بالغين «طفلكم غادر المنطقة» فانطلقوا في خمسة اتجاهات، فقد صنَعْتَ مشكلة الجهد المُضلَّل، وربما لوَّث أحدهم مشهداً. **أضف: تعيين دور صريح عند P0** (واحد يبقى على الموقع/المنزل، واحد يتصل بالشرطة، واحد يبحث في اتجاه محدّد)، وسجّل في ورقة الحادثة **مَن فعل ماذا ومتى**.

### 7. الاحتفاظ بالتطبيق (Retention) — اعترف بأن التطبيق ليس له سطح استخدام يومي

- **لم أجد دليلاً مُثبتاً** على أسباب تخلّي الأولياء (لا مسح، لا بيانات churn، لا دراسة مراجعات متجر قرأتُها). [88] و[89] هما المصدران الصحيحان — **V-E، غير مقروءين**.
- **النقطة البنيوية المؤكدة (INF):** في KidGuard الطفل لا يحمل التطبيق، بل **الأولياء** يحملونه. فالتطبيق الذي مخرجه الوحيد إنذارٌ **ليس له أي سبب ليُفتح في يوم عادي**، وسيُحكَم عليه حصرياً بسلوك إنذاراته الكاذبة. **E1 و E2 مشكلة واحدة لا مشكلتان.**
- **الإصلاح:** أضِف **منفعة يومية غير أمنية** — «وصل إلى المدرسة 07:52»، «غادر 13:10»، ملخّص اليوم. هذا يحوّل المنتج من **أداة أزمة** إلى **عادة يومية**، ويجعل اليوم الذي لا يحدث فيه شيء يوماً تُرى فيه قيمة. **هذه فرضية تصميمية قابلة للقياس**: قِس معدل فتح التطبيق الأسبوعي مع المنفعة اليومية وبدونها.

### 8. ما لم يُحقَّق ويجب إغلاقه قبل الإطلاق

| الفجوة | المصدر الذي سيغلقها |
|---|---|
| النسبة المئوية المقاسة لنفاد البطارية بسبب التتبع المستمر | [91] (جدول قياس حسب الجهاز) + [90] (وثيقة Android الرسمية) |
| منحنى «معدل الإنذار الكاذب ← معدل الاستجابة»، وأي سقف مقبول | **[83]** و **[84]** — أعلى أولوية؛ كلاهما فشل في الجلب |
| هل يفرط الأولياء في الثقة فيتوقفون عن المراقبة بأنفسهم (automation bias) | **[85]**، **[86]** |
| ماذا تطلب الشرطة فعلياً بصيغة محدّدة | **[99]**، **[105]**، **[106]** |
| أحكام المحاكم في بيانات متتبّع GPS | **[95]**، **[96]** — كلاهما 403 |
| مدة حفظ كاميرات المراقبة برقم موثوق | لم يظهر أي مصدر حكومي/منظّم؛ **يحتاج بحثاً موجّهاً** (ICO، أنظمة الاتحاد الأوروبي، التشريع الجزائري) |
| زمن حصول الشرطة على CDR | **لم يُعثر على أي رقم منشور** |
| البحث الذاتي التنظيمي ومخاطره | **لم يُبحث — نفدت الميزانية** |

---

## Evidence gaps

1. **E1 has no verified numbers.** The reliability→response curve, any tolerable false-alarm-rate threshold, and any measured rate of disabling/muting exist in [83] and [84]; both failed to retrieve (403 and unparseable PDF). **Re-run with a non-paywalled mirror, a DTIC copy, or a university library proxy.**
2. **E2 is essentially empty of evidence.** No churn figure, no review-mining result, no measured battery percentage was read. [88], [89], [90], [91] are the right documents.
3. **E3 has verified *existence* of two directly on-point court decisions and zero verified holdings.** [95] and [96] must be read before any legal claim is made. The "does a police request create a preservation duty" question needs qualified counsel in Algeria and in each target market — I cannot answer it.
4. **E4 is thin, as the brief predicted.** No CCTV retention figure above Tier 3, no CDR acquisition timeline anywhere, no "golden hour" official wording, and the self-organised-search question was not researched at all.
5. **Nothing was found for any Arab or Algerian jurisdiction.** Every source is US, UK/EU-adjacent, or Australian. Law 18-07/ANPDP interaction with evidence preservation, and Algerian operator practice on CDR release, are **completely uncovered** and need a separate, targeted effort.
6. **The automation-bias / over-trust risk (guardians stopping their own supervision because KidGuard is watching) was identified from source titles but is nowhere addressed in the current architecture.** It is a product-safety issue, not a UX nicety.

---

## Sources

Verification key: **V-C** = content retrieved and read · **V-E** = title/URL/venue confirmed present in search results, body NOT read · tier as defined in the brief.

**[80]** PMC / peer-reviewed scoping review — *Alarm fatigue in healthcare: a scoping review of definitions…* — https://pmc.ncbi.nlm.nih.gov/articles/PMC12181921/ — Accessed 2026-10-05 — Tier: 1 — **V-C (partial).** Defines alarm fatigue as desensitisation arising from frequent alarms. The retrieved text surfaced no false-alarm percentages, no counted missed events and no numeric compliance threshold, so the quantitative asks in E1 remain unfilled by this source.

**[81]** AACN Advanced Critical Care — *Alarm Fatigue: A Patient Safety Concern* — https://www.ovid.com/journals/adcrc/fulltext/10.1097/nci.0b013e3182a903f9~alarm-fatigue-a-patient-safety-concern — Accessed 2026-10-05 — Tier: 1 — **V-E.** Peer-reviewed article in a critical-care nursing journal; the DOI `10.1097/nci.0b013e3182a903f9` is confirmed in the URL. Authors not verified. Would be the citable framing of alarm fatigue as a patient-safety hazard.

**[82]** Intensive and Critical Care Nursing (ScienceDirect, PII S0964339725003167) — *Improving alarm management to reduce alarm fatigue in…* — https://www.sciencedirect.com/science/article/pii/S0964339725003167 — Accessed 2026-10-05 — Tier: 1 — **V-E.** An intervention study on reducing alarm burden — the evidence base for mitigation design (tiering, thresholds, quiet periods). Body unread.

**[83]** Proceedings of the Human Factors and Ergonomics Society Annual Meeting — *False Alarms vs. Misses: Subjective Trust as a Mediator between Reliability and Alarm Reaction Measures* — https://journals.sagepub.com/doi/pdf/10.1177/1541931215591141 — Accessed 2026-10-05 (HTTP 403) — Tier: 1 — **V-E.** The most design-relevant single paper found: an experimental manipulation of alarm reliability measuring the resulting reaction and trust. Would supply the false-alarm-rate → response-rate curve. Could not be retrieved.

**[84]** U.S. Department of Transportation / ROSAP — *A Review of the Literature on Alarms, Alerts, and Warnings* — https://rosap.ntl.bts.gov/view/dot/59041/dot_59041_DS1.pdf — Accessed 2026-10-05 (unparseable PDF) — Tier: 1 — **V-E.** Government literature review; the most likely home of a defensible "tolerable false-alarm rate" threshold and of cry-wolf-effect magnitudes. Retrieval failed.

**[85]** DTIC — *A Process Model of Trust in Automation: A Signal Detection…* (ADA618894) — https://apps.dtic.mil/sti/tr/pdf/ADA618894.pdf — Accessed 2026-10-05 — Tier: 1 — **V-E.** Formalises trust in automation through signal detection theory — the compliance/dependence mechanism behind automation bias. Unread.

**[86]** PubMed Central — *Understanding reliance on automation: effects of error type…* (PMC4307024) — https://pmc.ncbi.nlm.nih.gov/articles/PMC4307024/ — Accessed 2026-10-05 — Tier: 1 — **V-E.** Separates the effects of false alarms from misses on reliance — directly on the brief's "do people over-trust and stop checking themselves" question. Unread.

**[87]** Research Square (preprint) — *Clinical Alarm Awareness, Management Challenges, and…* (rs-7117686) — https://www.researchsquare.com/article/rs-7117686/latest.pdf — Accessed 2026-10-05 — Tier: 2 — **V-E.** Survey-style data on clinician alarm awareness and silencing behaviour. Unreviewed preprint; treat as indicative only.

**[88]** ResearchGate-indexed study — *Examining Parent Versus Child Reviews of Parental Control Apps on Google Play* (listed 2019-08-05) — https://www.researchgate.net/publication/334344645_Examining_Parent_Versus_Child_Reviews_of_Parental_Control_Apps_on_Google_Play — Accessed 2026-10-05 — Tier: 1 — **V-E.** App-store review mining comparing parent and child complaints — the closest thing found to the brief's "app-store review themes" ask. Authors and findings not verified.

**[89]** University of Central Florida News — *How Parental-Control Apps May be Counterproductive* (2018-04-02) — https://www.ucf.edu/news/apps-keep-children-safe-online-may-counterproductive/ — Accessed 2026-10-05 — Tier: 2 — **V-E.** University newsroom reporting a peer-reviewed result; the headline asserts these apps can be counterproductive. Underlying study not identified or read.

**[90]** Android Developers (Google) — *About background location and battery life* — https://developer.android.com/develop/sensors-and-location/location/battery — Accessed 2026-10-05 — Tier: 2 — **V-E.** Official platform documentation on background location restrictions and battery behaviour. The authoritative reference for whether a continuously-uploading child handset will be battery-killed or background-restricted. Not fetched — budget spent on E1/E3.

**[91]** ResearchGate-indexed figure/table — *Battery drain across phones for a constant location tracking workload* (fig. ID 329972323) — https://www.researchgate.net/figure/Battery-drain-across-phones-for-a-constant-location-tracking-workload-Smartphone_tbl1_329972323 — Accessed 2026-10-05 — Tier: 1 — **V-E.** A per-device measurement table for a constant location-tracking workload — precisely the "measured percentages" the brief requested. The numbers were not read; the parent publication was not identified.

**[92]** ideausher.com — *Why Do Parents Abandon Parenting Apps and How to Retain…* — https://ideausher.com/blog/parenting-app-retention/ — Accessed 2026-10-05 — Tier: 3 — **V-E.** Vendor content marketing. Listed only to record that no better source surfaced for the abandonment question; **not citable as evidence.**

**[93]** Digital Child (Australia) — *Protection or punishment? Navigating parental control apps for keeping your children safe online* — https://digitalchild.org.au/protection-or-punishment-navigating-parental-control-apps-for-keeping-your-children-safe-online/ — Accessed 2026-10-05 — Tier: 2 — **V-E.** Practitioner/NGO framing of child resistance to monitoring — relevant to the brief's "the child's resistance" abandonment driver. Unread.

**[94]** WA Today (Nine) — *Perth woman charged for putting AirTag GPS tracker on child* (2023-12-21) — https://www.watoday.com.au/national/western-australia/this-perth-mum-put-a-gps-tracker-on-her-toddler-then-she-was-arrested-20231221-p5et5o.html — Accessed 2026-10-05 — Tier: 2 — **V-E.** Headline indicates a parent was criminally charged for attaching a tracker to her own toddler. **Characterised from the title only**; not fetched. Relevant as legal-risk context for KidGuard's guardian-attaches-tracker model, not as evidence of admissibility.

**[95]** U.S. District Court, Western District of Missouri — *Hale County A&M Transport, LLC v. City of Kansas City, Missouri*, No. 4:2012cv00265, doc. 104 (via Justia) — https://law.justia.com/cases/federal/district-courts/missouri/mowdce/4:2012cv00265/103053/104/ — Accessed 2026-10-05 (HTTP 403) — Tier: 1 — **V-E.** A federal district-court decision touching GPS/telematics data. Would address authentication of the process producing the record and whether expert testimony is required. **Holding unknown — do not cite its reasoning.**

**[96]** Maryland Court of Special Appeals — *Easter v. State* (2015), FindLaw 1702388 — https://caselaw.findlaw.com/md-court-of-special-appeals/1702388.html — Accessed 2026-10-05 (HTTP 403) — Tier: 1 — **V-E.** A state criminal appeal. Would address admissibility/authentication of tracking-device data in a criminal case. **Holding unknown — do not cite its reasoning.**

**[97]** Maryland Courts — unreported opinion, 2016/1533s15 — https://www.mdcourts.gov/sites/default/files/unreported-opinions/2016/1533s15.pdf — Accessed 2026-10-05 — Tier: 1 (unreported; weak precedential value) — **V-E.** Surfaced in the same query as [96]. Unread; unreported opinions carry little or no precedential weight and should be used only as context.

**[98]** eDiscovery LLC — *Authentication of Surveillance Video by Lay Witness Under Silent Witness Doctrine* (2024-12-09) — https://www.ediscoveryllc.com/authentication-of-surveillance-video-by-lay-witness-under-silent-witness-doctrine/ — Accessed 2026-10-05 — Tier: 3 — **V-E.** Law-firm blog. Valuable only as a signpost to the "silent witness" theory, the common route for authenticating machine-generated recordings without a human eyewitness — directly relevant to a server-side GPS trace. Unread.

**[99]** Police1 — *Navigating mobile device searches: Legal processes and best practices for law enforcement* (2024-06-22) — https://www.police1.com/investigations/navigating-mobile-device-searches-legal-processes-and-best-practices-for-law-enforcement — Accessed 2026-10-05 — Tier: 2 — **V-E.** Trade press written for a law-enforcement audience on legal process and handling of mobile-device data. The closest thing found to published guidance on what police expect when receiving location data. Unread.

**[100]** U.S. District Court, District of Nebraska — *Litigation Holds: Ten Tips in Ten Minutes* (CLE material, 2010-07) — https://www.ned.uscourts.gov/internetDocs/cle/2010-07/LitigationHoldTopTen.pdf — Accessed 2026-10-05 — Tier: 1 — **V-E.** Federal-court-issued guidance on the duty to preserve and on litigation holds. Directly relevant to whether KidGuard must preserve a child's location history on request, and to the consequences of deletion. Dated 2010 — pre-dates FRCP 37(e) as amended, so it needs updating before use.

**[101]** Troutman Pepper — *Spoliation: When the Duty to Preserve Data Outweighs the Obligation to Delete* (2024-08-05) — https://www.troutman.com/insights/spoliation-when-the-duty-to-preserve-data-outweighs-the-obligation-to-delete/ — Accessed 2026-10-05 — Tier: 2 — **V-E.** Law-firm analysis of the preservation/spoliation tension — useful precisely because KidGuard will face a conflict between a privacy-minimisation retention policy and a preservation duty. Unread.

**[102]** T.M. O'Shea (2014) — *Using Legal Holds for Electronic Discovery*, William Mitchell Law Review (indexed copy) — https://open.mitchellhamline.edu/cgi/viewcontent.cgi?article=1561&context=wmlr — Accessed 2026-10-05 — Tier: 1 — **V-E.** Law-review treatment of legal holds for ESI, with a named author and year. The most substantive preservation-duty source found. Unread.

**[103]** ACLU — *VICTORY! Federal Appeals Court Rules Warrant Required…* (2013-10-22) — https://www.aclu.org/news/national-security/victory-federal-appeals-court-rules-warrant-required — Accessed 2026-10-05 — Tier: 2 — **V-E.** Advocacy-organisation announcement that a federal appeals court required a warrant for GPS tracking. **Which circuit, which case and the precise holding are NOT verified** — the title is truncated in the search result. Treat as a pointer only.

**[104]** OJJDP / U.S. Department of Justice — *What You Should Do When Your Child Is First Missing* — https://ojjdp.ojp.gov/sites/ojjdp/files/pubs/childismissing/introck.html — Accessed 2026-10-05 — Tier: 1 — **V-E.** Official federal, family-facing first-hour guidance. The best available source for where a private app's data fits in the first hour. Unread.

**[105]** National Center for Missing & Exploited Children — *Investigative Checklist for First Responders* — https://www.amberadvocate.org/wp-content/uploads/2021/06/NCMEC_FIRST_RESPONDER_CHECKLIST.pdf — Accessed 2026-10-05 (unparseable PDF) — Tier: 1 — **V-E.** Official first-responder investigative checklist. Would document the ordered first-hour actions including canvassing, video and records requests. Retrieval failed.

**[106]** National Criminal Justice Training Center / Fox Valley Technical College — *Investigative Checklist* (RS00307130) — https://ncjfc-static.fvtc.edu/resources/RS00307130.pdf — Accessed 2026-10-05 — Tier: 1 — **V-E.** Official training-body investigative checklist; a second, independent Tier-1 source for the first-hour sequence. Unread. (URL host rendered as `ncjtc-static.fvtc.edu` in the search result — verify the exact host before citing.)

**[107]** City of Little Falls Police Department — *Missing and Endangered Persons* (policy PDF, doc 233) — https://www.cityoflittlefalls.com/DocumentCenter/View/233/Missing-and-Endangered-Persons-PDF — Accessed 2026-10-05 — Tier: 1 — **V-E.** A concrete written municipal missing-persons procedure — useful as an example of the actual operational steps and timings a department follows. Unread.

**[108]** Great White Security — *How Long Is CCTV Footage Kept? 14–90 Days* — https://www.greatwhitesecurity.com/blog/cctv-footage-retention-australia/ — Accessed 2026-10-05 — Tier: 3 — **V-E.** Vendor blog, Australia-scoped per the URL. Its title asserts a 14–90 day retention range; **this is marketing content, not a measurement, and no regulator or study backs it.** Included solely to record that no Tier-1/2 CCTV retention source was found and that this figure must not be presented as sourced.

---

### Additional sources identified but not retained (all Tier 3 — recorded for completeness, not citable)

`humanreliability.com` blog on alarm response psychology (2024-08); `scispace.com` hosted PDF *Combating Alarm Fatigue*; ResearchGate record 337555194 titled *Alarm Fatigue*; `hubstaff.com`, `digitalmatter.com`, `wellally.tech` battery/GPS vendor blogs; a Google Maps support-community thread on live-location battery drain; `evolutiondatadigital.com`, `secureredact.ai`, `armex.tech`, `technotrading.com.au` and a LinkedIn Pulse post on CCTV retention; `useboomerang.com` and `angelq.ai` vendor posts on parental-control apps; `gofleet.com` *Police Use GPS To Track Missing Children*; `cokerlegal.com` on tracking a spouse in Texas; `mrdfirm.com`, `hillslawoffice.com`, `nolo.com`, `findlaw.com` law-firm/legal-encyclopedia posts on cell-tower warrants; `goodpinelaw.com`, `complexbusinesslitigation.com`, `dps.apps.nsula.edu` on litigation holds; `ryanorsattilaw.com` on truck telematics; `employee-monitoring.net`; two Facebook posts (Fox 8 News; FBI Phoenix) and a `fedorainvestigations.com` page.
