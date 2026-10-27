# KidGuard v2 — D1 Covert tracking of a minor, D2 Algerian device homologation, D3 alert channels, D4 crowd-find networks

Research date: 2026-10-05. Retrieval budget: 10 WebSearch + 6 WebFetch, fully consumed.

**How to read this file.** I distinguish four states of evidence and label them every time:
- **VERIFIED** — I fetched the page/PDF and read the returned content.
- **LOCATED-NOT-READ** — a real URL surfaced in search with a real title and date, but I never fetched it. I cite it as a pointer only and I do **not** assert its contents.
- **INFERENCE** — my reasoning from verified facts, explicitly flagged.
- **UNVERIFIED / GAP** — I could not find a credible source. I say so rather than filling the hole.

A significant caveat on the two most important Algerian sources: the ANPDP guide PDF and the ARPCE homologation page both returned **heavily compressed extractions**. The article numbers and fee figures below are reported as the extraction returned them. They are plausible and consistent with each other, but they are **not** independently corroborated against the JORADP text inside this budget. Treat every Algerian legal figure as "reported, re-verify before relying on it".

---

## D1 — Legality and ethics of a parent covertly tracking their own minor child

### D1.1 The EU/France position: a DPA has spoken directly on this exact product

The single most useful source found in this entire research run is the CNIL's dedicated page on geolocating children: *"Géolocalisation des enfants : quels enjeux pour leur vie privée"* (dated 2025-09-22). [61] **VERIFIED.**

The CNIL's position, as returned by the fetch:

- Geolocating children **harms their privacy**; the regulator frames it as a privacy risk, not as a neutral safety tool.
- Direct partial quotation returned by the page: *« Avoir recours à des dispositifs… comporte le risque d'habituer le mineur à être sous surveillance constante »* — "resorting to such devices carries the risk of accustoming the minor to being under constant surveillance." (The ellipsis is the extraction's, not mine; the exact full sentence must be re-read on the page.) [61]
- The CNIL's recommendation is to **inform the child** and to **obtain the child's own consent where the child is mature enough** to give it.
- The page's guidance, as extracted, is to **avoid covert tracking**. [61]

This is decisive for the concealed-device idea. The French DPA's objection is not primarily "the parent lacks a legal basis" — it is that **hiding the device from the child is the harm**. A transmitter sewn into a clothing button, or embedded in a hair clip so that the child does not know it is there, is the exact configuration a Tier-1 regulator has warned against.

Two further CNIL pages are **LOCATED-NOT-READ** and are the obvious next pulls: *"Les droits numériques des mineurs"* (https://www.cnil.fr/fr/enjeux-numeriques/les-droits-numeriques-des-mineurs) and *"Géolocalisation et applications mobiles : quelles règles ?"* (dated 2026-07-07, https://www.cnil.fr/fr/geolocalisation-applications-mobiles-quelles-regles). [77] I did not read either; I do not assert their contents.

Note also that the CNIL maintains a separate, older doctrine on **geolocating employees' vehicles** (https://www.cnil.fr/fr/la-geolocalisation-des-vehicules-des-salaries) — LOCATED-NOT-READ, mentioned only because it is the reference text behind the EU AI Act Art. 5(1)(f) material you already have, and because "the person tracked must know they are tracked" is the consistent thread across CNIL geolocation guidance.

### D1.2 Algeria: who is the data subject, and who consents for a minor

**VERIFIED (via a compressed extraction).** The ANPDP's official *Guide du responsable de traitement*, V1.0 (2023) [62] was fetched. The extraction returned the following concept-to-article mapping for Law 18-07:

| Concept | Article (as returned) |
|---|---|
| *représentant légal* (legal representative) | Art. 4 |
| *autorisation préalable* for sensitive data **and data relating to minors** | Arts. 8–9 |
| *données de localisation* (location data) | Art. 10 |
| *transfert à l'étranger* (cross-border transfer) | Art. 15 |
| *sanctions pénales* | Arts. 76–77 |

Two readings matter for KidGuard:

1. **The child is the data subject, not the parent.** The law speaks of a *représentant légal* acting for the minor. That is an agency concept: the guardian exercises the minor's rights on the minor's behalf. It is **not** a concept under which the guardian becomes the owner of the data or acquires a right to process the child's data covertly. This is **INFERENCE** from the article structure, but it is the same structure as GDPR Art. 8 and the CNIL position in D1.1, and it is the reading a regulator would take.
2. **Location data of a minor sits where both strengthening triggers overlap.** Law 18-07 has a specific provision on *données de localisation* (Art. 10 as extracted) **and** requires *autorisation préalable* for processing concerning minors (Arts. 8–9 as extracted). A product that continuously geolocates a child therefore lands in the **authorisation** bucket, not the lighter **declaration** bucket. [62] This is the single most important compliance fact in this file, and it is *reported*, not corroborated against the JO text — re-verify against [63] before filing anything.

The full text of Law 18-07 is at ANPDP: https://anpdp.dz/wp-content/uploads/2023/01/2.1-Loi-N%C2%B018-07-2.pdf [63] **LOCATED-NOT-READ** in this session (your prior research already covers it).

### D1.3 Is there ANPDP guidance or a deliberation on geolocation or on minors' data?

**VERIFIED, and the answer is almost certainly no.** The Algerian state news agency APS reported on **2025-12-24** the *"Publication de la première délibération fixant les conditions de désignation du délégué à la protection des données à caractère personnel"* — the publication of the **first** deliberation, and it concerns the designation of DPOs. [65] **LOCATED-NOT-READ**, but the title itself is the finding.

**INFERENCE, stated plainly:** if the ANPDP's *first* published délibération dates from December 2025 and is about DPO designation, then as of 2026-10-05 there is **no body of published ANPDP deliberations on geolocation and no published ANPDP deliberation on minors' data**. The regulator is still building its instrument set. Consequences for the owner:
- Good: no adverse precedent specifically against child trackers exists.
- Bad: no safe harbour, no published guidance to rely on, and no predictable filing outcome. You will be an early test case, and the ANPDP may take months to answer or may impose conditions you cannot predict.
- The ANPDP's *Décisions* PDF (dated 03-06-2024, https://anpdp.dz/wp-content/uploads/2024/09/fr-03-06-2024.pdf) [64] is **LOCATED-NOT-READ** — this is the highest-value remaining pull for confirming whether any decision touches minors or location.

Separately, Algérie-Eco reported on **2026-07-18** that the ANPDP *"fixe les règles pour la publication de photos et de vidéos de personnes"* on social networks. [76] **LOCATED-NOT-READ.** Its relevance: the ANPDP is actively issuing rules in adjacent territory (images of identifiable people), which tells you the authority is operative and publishing — not dormant. Law firm Gide's July 2023 note on the ANPDP's establishment [75] is the standard Tier-2 orientation piece. LOCATED-NOT-READ.

### D1.4 Penal-code angle: is the use or *sale* of covert tracking devices restricted in Algeria?

**This is a genuine evidence gap and I will not paper over it.**

What I found: consolidated texts of the Algerian Penal Code — Ordonnance n° 66-156 du 8 juin 1966 (https://policehumanrightsresources.org/content/uploads/2016/07/Penal-Code-Algeria-1966.pdf), the 2015 consolidated PDF hosted by IEDJA [69], the Ministry of Justice's own article-by-article portal (e.g. Article 350, https://droit.mjustice.gov.dz/sites/default/files/portail/legisl_nouveau/code_penal_2010/fr/topics/588.html) [68], and the 2025 Code de procédure pénale on JORADP (https://www.joradp.dz/FTP/jo-francais/2025/F2025054.pdf). Also Algérie-Eco's 2024-05-08 report on new penal-code provisions published in the JO. All **LOCATED-NOT-READ**.

**What I could NOT verify, and what you must not assert:**
- I found **no** Algerian statutory provision, by article number, that specifically bans or restricts the **sale, import, possession or use of covert GPS tracking devices**. I am not saying it does not exist — the 1966 Code as amended (notably by Ordonnance 09-01 on cybercrime/information systems, and the 2024 amendments) plausibly contains *atteinte à la vie privée* provisions that would reach it, and French Art. 226-1 (https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000049312755, LOCATED-NOT-READ) is the classic model such provisions follow. But I have **not read the Algerian article text** and I will not give you a number I cannot stand behind.
- I found **no** Algerian text on concealed-surveillance devices specifically as applied to a **child**.
- I found **no** comparable MENA jurisdiction's ban on concealed trackers that I could verify in this budget.

**Actionable conclusion:** the penal-code question is **open and blocking-grade**. Before any hardware is designed, imported or sold, a qualified Algerian lawyer must read the *atteinte à la vie privée* chapter of the current consolidated Penal Code plus Ordonnance 09-01 and give a written opinion. This is not a research task; it is a legal opinion, and it should be budgeted.

One adjacent signal worth recording: Algerian trade pages discuss an **agrément** (licensing) regime for CCTV/surveillance installation companies (sidouni-technologie.com, 2026-06-29; grandstream.dz, 2023-12-28 — both **LOCATED-NOT-READ**, Tier 3 vendor content). **INFERENCE:** Algeria does regulate the *surveillance industry* as an activity, via installer licensing. If KidGuard v2 ever positions itself as a surveillance product or installs monitoring hardware for third parties, that regime may reach it. It almost certainly does **not** reach a consumer guardian app plus a parent-bought tracker, but this is inference, not sourced law.

### D1.5 The ethics dimension as it affects judging and public trust

The CNIL position in D1.1 is the strongest available authority and it is against concealment. [61] Framed for a competition jury, the argument the owner needs to hear:

- **The child's right to know is the defensible line.** A product whose stated design goal is *"so a kidnapper cannot find or discard it"* is a product whose second stated design goal is *"so the child cannot find or remove it"*. Those are the same engineering requirement. Judges and journalists will hear the second one. The CNIL has already written it down. [61]
- **Concealment defeats the product's own safety case.** A hidden device cannot be handed to a rescuer, cannot be shown to a teacher, cannot be switched off in a medical emergency, and cannot be found by the guardian when it needs charging. Its failure mode is silent.
- **Repurposing risk is the killer question.** A transmitter concealed in a child's clothing, whose location the child cannot see, is indistinguishable in hardware from a stalkerware device. The moment a jury asks *"what stops a controlling parent, or an ex-partner, or an abuser from using this on a 16-year-old?"*, the answer cannot be the hardware — it has to be policy, transparency and age gating. If the device is hidden by design, you have removed your only good answer.
- **The alternative framing is strictly stronger and costs nothing technically.** *"The child always knows the device is there. It is a visible watch. It is not hidden from the child; it is hard to remove for a stranger in the seconds after an abduction."* That is a real, defensible engineering goal — tamper-resistance and retention, not concealment — and it satisfies both the CNIL's objection and the owner's kidnapping scenario. Concealment and tamper-resistance are being conflated; they do not have to be.

**Recommendation, blunt:** drop concealment from the child. Keep it, if you must, in the *packaging* of a visible device (a watch whose module cannot be pried out in five seconds), never in the child's ignorance of it.

### D1.6 Custody-dispute exposure

**Weakest-verified area of D1. Everything below is LOCATED-NOT-READ and I assert nothing about the content of these pages.**

Located: Coker Legal (Texas family law firm), *"Can I Track My Spouse with a GPS or AirTag?"* (2025-06-24) [78]; MKFM Law, *"Can You Use GPS to Track Your Child During a Custody Dispute"* (2025-07-31) [79]; Hangley, *"Tracking or Stalking? Where do we Draw the Line?"* (2024-04-16); a Georgia r/Custody thread on Find-my-iPhone and stalking.

The only defensible statements I can make:
1. The existence of a distinct sub-genre of family-law writing on covert GPS tracking in custody disputes **is itself evidence** that the practice is litigated, in the US at least.
2. **UNVERIFIED:** I have no case law, no statute, and no Algerian authority on whether a court treats one parent's covert tracking as unlawful surveillance or harassment. This must not be asserted in a pitch deck.
3. **INFERENCE with high confidence:** in Algeria, where the ANPDP authorisation regime for minors' data likely applies [62], a tracking arrangement set up unilaterally by one guardian without the other's involvement is exposed twice — once as an unauthorised processing operation, and once in the family court. Design for **multi-guardian visibility by default** (which your 2–7 guardian architecture already gives you) and require **all guardians with parental authority to be notified/enrolled**. That is a product decision that mitigates a legal risk you cannot yet scope.

---

## D2 — Algeria: importing, homologating and selling a radio device

### D2.1 The ARPCE homologation regime

**VERIFIED (compressed extraction).** The official ARPCE page *Homologation* (https://www.arpce.dz/fr/service/agr) [60] returned:

- **Scope:** homologation covers **telecom equipment connected to public networks**.
- **Legal basis cited by the page:** *Decree 26-97, Arts. 9–10*. **Flag:** I could not verify this decree number against JORADP in budget. Algerian executive-decree numbering is `year-number`, so `26-97` would be a **2026** decree — consistent with a recent framework update (see D2.2). Re-verify.
- **Fees:** **17,500 DA administrative** + **150,000 DA technical**. (~167,500 DA total; at a rough 2026 official rate that is on the order of USD 1,200 — the FX conversion is my arithmetic, not a sourced figure.)
- **Validity:** certificates valid **5 years**.
- **Samples:** required within **5 days** (of request, presumably — the extraction does not say).
- **GPS/SIM devices:** the extraction returned *"GPS/SIM devices likely require homologation (TAC needed for cellular)"*. Note the hedge: the "likely" and the TAC reasoning came back as the extraction's, and the TAC (Type Allocation Code) point is an IMEI/GSM-Association concept, not an ARPCE term. **INFERENCE, high confidence:** a GT06-class tracker or a child smartwatch with a cellular modem is a terminal equipment attaching to a public mobile network and therefore falls squarely inside "telecom equipment connected to public networks" [60]. A **pure BLE tag with no cellular radio** is a materially different case and may fall outside — but that is unverified and needs an ARPCE written enquiry.

**Process duration: NOT VERIFIED.** No timeline was returned by the ARPCE page. The consultancy pages below will have it; I could not fetch them.

### D2.2 There is an ANF alongside ARPCE, and the framework was recently updated

**LOCATED-NOT-READ, but the titles are load-bearing.** Search surfaced:
- Applus Laboratories, *"Algeria Radio Type Approval (ARPCE | ANF)"* [70] — the pairing of **ARPCE** with **ANF (Agence Nationale des Fréquences)** in the title tells you there are **two** bodies and probably **two** filings: a spectrum/frequency matter (ANF) and a network-attachment/type-approval matter (ARPCE).
- IB Lenhardt, *"Type Approval Algeria — ANF / ARPCE Label and Certification"* [71].
- GMA/CAICT, *"Algeria: MPT updated type approval framework"* [72] — MPT = Ministère de la Poste et des Télécommunications. **INFERENCE:** the Algerian type-approval framework was recently revised, which is consistent with the `Decree 26-97` citation on the live ARPCE page [60]. Any cost/timeline figure from a 2024 or earlier consultancy page is likely stale.
- A LinkedIn piece, *"A Guide to ARPCE & ANF Certifications in 2026"* (Tier 3, anonymous-ish, not read).

**Practical consequence:** budget for **two** regulatory relationships, and get the current text of the framework before quoting any figure to a competition jury or an investor.

### D2.3 Are GPS trackers specifically restricted, and is there an import ban?

**UNVERIFIED.** I found no customs restriction, no import ban, and no ARPCE-specific rule naming GPS trackers or tracking devices. The Ouedkniss marketplace does maintain a **"gps enfant"** search category under *Loisirs & Divertissements* (https://www.ouedkniss.com/gps-enfant-r?lang=fr) [74], which would only exist if the query returns results — but my fetch of that URL returned a **JavaScript error page with no listing data**, so I **cannot confirm a single actual listing**. Treat the marketplace precedent as **unproven**.

Also located: garminalgerie.com (a Garmin distributor, not child trackers); a Morocco-based retailer selling child GPS watches (univers-espion.com); Chinese wholesale listings (made-in-china, grossiste-chinois-import). None of these is evidence of a **lawful Algerian retail channel**.

**Notably absent from search results:** any Mobilis, Djezzy or Ooredoo offer bundling a child GPS watch or tracker. **INFERENCE from absence (weak):** if the Algerian operators — who have the distribution reach and the regulatory relationships to do this properly — are not selling child trackers, the category is either too small to matter commercially or carries a regulatory/import friction that deters it. Absence of evidence is not evidence of a ban; but it is evidence that **the owner should not assume a ready market or a ready channel**.

### D2.4 The key question: if he only ships software, does he avoid homologation?

**INFERENCE — reasoned from [60], flagged as inference, and the most commercially useful conclusion in this file.**

The ARPCE page defines the scope of homologation by reference to the **equipment** ("telecom equipment connected to public networks") [60]. Type-approval regimes of this shape attach to a **radio terminal placed on the market by an economic operator**, not to software that talks to it over the internet. On that reading:

- **Model A — Guardian app only; the parent buys a third-party GT06-class tracker or uses an old Android phone.** The radio equipment is placed on the Algerian market by **its own importer/manufacturer**, who carries that equipment's homologation obligation. The developer ships an app and runs a Traccar server. **No device homologation falls on him.** This is the low-friction path and it is the one the architecture already assumes.
- **Model B — The developer designs, imports or sells the watch/bracelet/button device.** He becomes the economic operator placing radio equipment on the market. Homologation (ARPCE, and likely an ANF frequency matter) is **his** obligation, with fees, samples, a 5-year certificate cycle, and — critically — a **timeline he does not yet know**. For a solo developer on a competition clock, this is the difference between a software project and a hardware regulatory project.

**Caveats that must not be dropped:**
- This is inference from one compressed extraction of one official page. It is **not** a legal opinion.
- **Importing samples and development units** may itself trigger customs/homologation questions even under Model A. Unverified.
- **Model A does not transfer the data-protection obligation.** Whoever operates the Traccar instance and the relay is the *responsable de traitement* under Law 18-07, and per D1.2 that person is filing with the ANPDP regardless of who made the radio. [62]
- If the concealed-device idea proceeds, Model B is unavoidable — **you cannot conceal a device you did not build.** That means the concealment ambition and the homologation burden are the same decision.

### D2.5 Does operating the server require Algerian authorisation?

**PARTIALLY VERIFIED, mostly a gap.**
- **Data localisation:** Law 18-07 regulates **cross-border transfer** (Art. 15 as extracted, requiring authorisation) [62]. That is a *transfer* control, not a *hosting mandate*. **INFERENCE:** self-hosting Traccar **in-country** in Algeria is the design choice that avoids the Art. 15 transfer authorisation entirely, and it is therefore correct — but it is a mitigation of an obligation, not evidence of an absence of obligation. Your prior research already covers cross-border transfer authorisation; this adds only that in-country hosting is the way to sidestep it.
- **Hosting / ISP authorisation for running a server:** **UNVERIFIED.** I found no Algerian rule, in this budget, on whether operating a private application server requires a hosting licence, an ARPT/ARPCE authorisation, or an ICP-style registration. Algeria does licence **hosting providers and ISPs** as activities; whether an app developer self-hosting on a licensed provider's infrastructure inherits any obligation is **not established**. Ask the hosting provider and a lawyer.
- **Encryption:** not researched. Algeria historically restricts the import/use of cryptographic means; a Traccar instance using TLS may or may not engage this. **UNVERIFIED — flag for legal review.**

---

## D3 — Alert-channel legality and practicality

**Thin. Stated plainly: this area was under-researched by budget necessity, and the one verified fact is more restrictive than expected.**

### D3.1 A2P SMS to Algeria — VERIFIED

Twilio's official Algeria SMS guidelines page [66] returned:
- **Alphanumeric Sender IDs are supported**, but **domestic pre-registration is required, taking about 2 weeks**.
- **Mobilis rejects generic or numeric Sender IDs.**
- **No 2-way SMS.**

Operational consequences for KidGuard:
1. **A named Sender ID is a filing, not a config flag.** Build for it: reserve "KIDGUARD" (or the Arabic equivalent), start registration ~2 weeks before any pilot, and design the alert copy so it survives a **fallback to a numeric or unbranded originator** during the registration window.
2. **Mobilis is the hard operator.** If Mobilis rejects your chosen ID you may deliver unbranded or not at all to Mobilis subscribers — and Mobilis is the state operator with the widest rural footprint, i.e. exactly where a lost child is hardest to find. Test on all three networks separately.
3. **No 2-way SMS kills "reply STOP / reply 1 to confirm".** Your escalation relay must treat SMS as **fire-and-forget** and take acknowledgement through **in-app push** instead. This is an architecture constraint, and it is sourced.
4. **Corroborating pages, LOCATED-NOT-READ:** Vonage *"Algeria SMS Features and Restrictions"* (2025-07-17, https://api.support.vonage.com/hc/en-us/articles/204018093-Algeria-SMS-Features-and-Restrictions) [73]; sent.dm *"Algeria SMS Pricing 2025"* (https://sent.dm/en/resources/sms-pricing/algeria-sms-pricing); sms.to's international Sender ID regulations table (2026-05-29). Pull these for pricing — I did not, so **I am giving you no cost figure for Algerian A2P SMS.** Do not quote a price to anyone until you have.
5. **Local aggregators:** an r/algeria thread asks for SMS gateway providers for Mobilis/Djezzy/Ooredoo (Tier 3, LOCATED-NOT-READ). Its existence suggests local direct-aggregator options are not obvious to developers. **INFERENCE:** plan on an international aggregator with Algerian termination, and treat direct operator relationships as a later-stage task.
6. **ARPCE regulation of shortcodes/Sender IDs:** **UNVERIFIED.** Twilio tells you pre-registration is required; it does not tell you whether that registration runs through ARPCE, through the operator, or through the Ministry. I found no ARPCE text on numbering or shortcodes in this budget.

### D3.2 Automated voice calls to guardians

**UNVERIFIED.** I found no Algerian authority, statute or regulator page on robocalls / automated voice calls. No precedent located. What I can say without inventing anything: the absence of any located source in a targeted search is itself a weak signal that this is an **unregulated-in-writing** area rather than a clearly-permitted one, which in a MENA telecom context usually means **operator discretion** — your voice traffic can be blocked or your number suspended without a legal citation. **Recommendation:** treat voice as a **later** channel, and until verified, make the escalation chain not depend on it. Push + SMS + email must be sufficient on their own.

### D3.3 Auto-notifying police (1548) or gendarmerie (1055)

**UNVERIFIED as to any positive authority; my assessment is that it is not lawful and you should assume it is not.**

- I found **no** jurisdiction in this research run that permits a private application to **raise an official emergency alert** programmatically. Nothing located, nothing verified — in either direction.
- **INFERENCE, high confidence:** emergency numbers are staffed human call-answering services. A machine-originated call or SMS into 1548/1055 is, at best, unsolicited traffic on a life-safety line and, at worst, a criminal offence (false alarm / misuse of emergency services — a category that exists in essentially every penal code, including Algeria's, though I have **not** located the Algerian article number and will not supply one).
- **The lawful handoff, and this is the correct product design:** the app alerts **guardians**. A **human guardian** decides and places the call to 1548/1055. The app's contribution is to make that call fast and informed: a one-tap dialler, and a **shareable, human-readable location payload** (coordinates plus a maps link, last-fix time, battery, direction of travel) that the guardian can read aloud or forward. Add an on-screen line telling the guardian which number to call and what to say. That is a feature; auto-dialling the police is a liability.
- **Do not put "auto-notifies police" in a pitch deck.** It is the fastest way to lose a jury that includes anyone with a public-safety or legal background.

---

## D4 — The no-SIM secondary layer: crowd-sourced finding networks

**Thin and largely unverifiable within budget. I am flagging it as thin rather than padding it.**

### D4.1 Google Find Hub / Find My Device network — PARTIALLY VERIFIED

Google's official page *"Find Your Phone with Find Hub"* (https://www.android.com/learn-find-hub/) [67] returned:
- Find Hub **locates devices offline via a crowd-sourced network**.
- It references **UWB tags such as "Pixel Tag"** as participating hardware.
- It provides **alerts that detect unknown trackers** (the anti-stalking feature).
- **"Availability varies by region/carrier."**

**The country list was NOT returned, and I did not verify whether Algeria or any MENA country is covered.** Related pages LOCATED-NOT-READ: https://www.google.com/android/find/about and the Find Hub Play Store listing; androidpolice.com's 2024-04-08 launch report. **This is the single most important open question in D4 and it is unanswered.** Do not design a layer that depends on it until you have read Google's own availability statement.

The **anti-stalking alert is verified and it is fatal to the concealment plan.** Google ships unknown-tracker detection into Find Hub. [67] Apple has shipped the equivalent for years, and the two ecosystems now interoperate on it via the industry standard for unwanted-tracker detection. **Consequence, stated as inference with high confidence:** a concealed BLE tag on a child will be **announced to the phones of strangers around that child** — a teacher, a bus passenger, a shopkeeper — as a possible stalking device. A hidden tag does not stay hidden in a crowd network; the crowd network is *engineered* to find it. Concealment and crowd-network operation are **mutually exclusive by design**.

### D4.2 Apple Find My network in Algeria/MENA — UNVERIFIED

No Apple developer or support documentation was fetched. No country coverage verified.

### D4.3 iPhone density in Algeria — UNVERIFIED

I could not retrieve StatCounter or any market-share data in budget. **I am giving you no number and I will not estimate one.** What can be said without a source: the prior research in this project already establishes that the Algerian guardian app target is Android (Flutter, Google Play Families policy), which is consistent with Android dominance — but "consistent with" is not a citation. **Pull StatCounter Algeria before any D4 decision.**

### D4.4 Samsung SmartThings Find / Tile in MENA — UNVERIFIED

Not researched within budget. No coverage data located.

### D4.5 Is a concealed BLE tag a viable secondary locator in a low-density market?

**INFERENCE from verified technical facts, clearly labelled.** The physics and the market structure both say no, for Algeria:

1. **A crowd network's update latency is a function of passer-by density.** In a dense urban core you get minutes. Outside it — and a kidnapping is deliberately going to move a child outside it — you get **hours, days, or never**. A BLE tag in a rural or peri-urban Algerian setting, or inside a vehicle, may never produce a fix.
2. **BLE range is short and degrades through bodies, fabric, bags and vehicle bodies.** A tag in a shoe or a schoolbag pocket, on a moving child, has an effective range measured in a few metres at best.
3. **It cannot substitute for the cellular GPS layer, only back it up.** Its honest role is *"last known proximity when the SIM tracker dies or is jammed"*, in a city, during the first hours.
4. **Combining (1)–(3) with the D4.1 anti-stalking finding:** a BLE tag gives you unreliable, short-range, low-density coverage **and** actively broadcasts itself to strangers as a suspected stalker device. That is a negative-value component for a child product.
5. **Recommendation:** do not build the no-SIM layer on a *concealed* BLE tag. If you want a second locator, the defensible options are (a) a **visible** BLE tag on the schoolbag that the child knows about, sold as a redundancy feature, or (b) the **old-Android-phone uploader** you already have, which is GPS-capable, network-independent of crowd density, and already in the architecture.

### D4.6 Evidence on crowd-network trackers being used for children

**UNVERIFIED.** No study, DPA position, or vendor statement located in budget on crowd-network tags used on children, or on the child-safety framing of these networks.

---

## العوائق مقابل المخاطر القابلة للإدارة

### (أ) ما يجعل المنتج غير قانونياً أو غير قابل للبيع — عوائق حقيقية

1. **إخفاء جهاز التتبّع على جسد الطفل** (داخل ساعة، دبوس شعر، أو زرّ ملابس) بحيث لا يعلم به الطفل: هذا هو أخطر قرار في المشروع كله. لم أعثر على نصّ جزائري صريح يحرّمه باسمه ورقمه — وهذه ثغرة في بحثي لا في القانون. لكنّ الهيئة الفرنسية لحماية البيانات (CNIL) تنشر موقفاً مخصّصاً لهذا المنتج بعينه، وتحذّر حرفياً من أنّ اللجوء إلى هذه الأجهزة «يحمل خطر تعويد القاصر على أن يكون تحت مراقبة دائمة»، وتوصي بإعلام الطفل وأخذ موافقته إذا كان ناضجاً بما يكفي، وتوجّه إلى تجنّب التتبّع الخفيّ. [61] ومنطق قانون 18-07 يجعل **الطفل هو صاحب البيانات** والوليّ مجرّد «ممثّل قانوني» يمارس حقوقه نيابة عنه [62]، لا مالكاً للبيانات ولا مخوَّلاً بمعالجتها سرّاً. **الخلاصة: قانونياً منطقة رمادية خطيرة، وأخلاقياً وتنافسياً خطر مؤكّد يُسقط المشروع أمام لجان التحكيم. لا تُبنِ المنتج على الإخفاء.**
2. **الإخفاء يتناقض تقنياً مع شبكات البحث الجماعي.** جوجل تُضمّن Find Hub تنبيهاً يكشف أجهزة التتبّع المجهولة [67]. أي وسم BLE مخفيّ على طفل سيُبلَّغ عنه تلقائياً لهواتف الغرباء حوله — معلّم، راكب حافلة، بائع — باعتباره جهاز مطاردة محتملاً. **الشبكة الجماعية مصمَّمة للعثور على الجهاز المخفيّ. الإخفاء والبحث الجماعي لا يجتمعان.**
3. **تصميم أو استيراد أو بيع الجهاز بنفسك دون مصادقة (homologation) من ARPCE:** عائق قانوني صريح. المصادقة تشمل «معدّات الاتصالات المتصلة بالشبكات العمومية» [60]، وأي متتبّع بشريحة SIM أو ساعة طفل خليوية يقع داخل هذا التعريف. من يصنع الجهاز ويستورده ويبيعه هو من يتحمّل الالتزام — لا من يصنع التطبيق.
4. **أي ادّعاء طبي أو نفسي** (قياس النبض أو التنفّس للكشف عن «الخوف» أو «التوتّر»): يُخرج المنتج إلى تنظيم الأجهزة الطبية، وهو ما أثبتّه بحثك السابق. احذفه من كل خطاب تسويقي.
5. **التبليغ الآلي للشرطة (1548) أو الدرك (1055) من التطبيق:** لم أجد أي ولاية قضائية تسمح لتطبيق خاص بإطلاق إنذار رسمي. الأرجح أنه غير مشروع (إساءة استخدام خطوط الطوارئ). **لا تكتبه في عرض المشروع إطلاقاً.**

### (ب) ما يحتاج تصريحاً أو إيداع ملف، وله جدول زمني

1. **ANPDP — ترخيص مسبق.** دليل ANPDP الرسمي يربط «الترخيص المسبق» بالبيانات الحسّاسة **وببيانات القاصرين** (المواد 8–9 كما وردت في الاستخراج)، وينظّم «بيانات الموقع» (المادة 10) [62]. تتبّع موقع طفل قاصر يقع إذن في خانة **الترخيص** لا في خانة **التصريح** الأخف. *تنبيه: أرقام المواد مأخوذة من استخراج مضغوط ولم أتحقّق منها مقابل نصّ الجريدة الرسمية — تحقّق قبل أي إيداع.* [63]
2. **ARPCE (+ غالباً ANF) — مصادقة المعدّة الراديوية**، فقط إذا صنعتَ الجهاز أو استوردتَه. الرسوم كما وردت: **17.500 دج إدارية + 150.000 دج تقنية**، صلاحية الشهادة **5 سنوات**، وتقديم عيّنات خلال **5 أيام** [60]. **المدة الزمنية للمعالجة غير معروفة — لم أتمكّن من التحقّق منها.** وهناك جهتان على الأرجح لا واحدة: ARPCE و ANF (الوكالة الوطنية للترددات) [70][71]، وقد حُدِّث الإطار التنظيمي حديثاً [72] — فأي رقم قديم غير موثوق.
3. **تسجيل مُعرِّف المُرسِل (Sender ID) للرسائل النصية:** نحو **أسبوعين**، ومطلوب محلياً. **موبيليس ترفض المعرّفات العامّة أو الرقمية. ولا توجد رسائل نصية ثنائية الاتجاه** [66] — أي لا يمكن بناء «ردّ بـ 1 للتأكيد»، ويجب أن يمرّ الإقرار عبر الإشعار داخل التطبيق.
4. **نقل البيانات إلى الخارج:** القانون ينظّمه (المادة 15 كما وردت) [62]. استضافة Traccar **داخل الجزائر** هي القرار الذي يجنّبك هذا الإذن أصلاً — وهو قرار صحيح.
5. **رأي قانوني مكتوب من محامٍ جزائري** حول باب «المساس بالحياة الخاصة» في قانون العقوبات الحالي والأمر 09-01، قبل أي خطوة عتاد. **هذا ليس بحثاً؛ هذا أتعاب محاماة، ويجب رصدها في الميزانية.**

### (ج) ما هو مجرّد قرار تصميمي أو إفصاحي — بيدك أنت

1. **الجهاز ظاهر ومعلوم للطفل**: ساعة واضحة + إشعار دائم داخل التطبيق بأن التتبّع جارٍ. **هذا يحوّل أكبر خطر قانوني وأخلاقي إلى نقطة قوة تسويقية**، وبتكلفة هندسية صفر.
2. **التمييز بين «الإخفاء» و«مقاومة النزع»**: هدفك الحقيقي هو ألّا يستطيع الخاطف نزع الجهاز في ثوانٍ، لا ألّا يعلم الطفل به. الأول هندسة تثبيت وتغليف يمكن الدفاع عنها؛ الثاني مراقبة خفية لا يمكن الدفاع عنها. لا تخلط بينهما.
3. **اختيار مصدر الموقع ومن يتحمّل المصادقة**: تطبيق على هاتف أندرويد قديم، أو جهاز GT06 يشتريه الوليّ بنفسه (حينها لا تتحمّل أنت مصادقة المعدّة)، أو جهازك أنت (فتتحمّلها). النموذجان الأوّلان هما المسار منخفض الاحتكاك.
4. **رؤية متعدّدة الأولياء افتراضياً**: بنيتك الحالية (2–7 أولياء) هي أصلاً تخفيف لمخاطر نزاع الحضانة. اجعل إخطار/تسجيل كل الأولياء أصحاب السلطة الأبوية إلزامياً، ولا تسمح بتفعيل أحادي.
5. **سلسلة التنبيه**: إشعار داخل التطبيق + بريد + رسالة نصية أحادية الاتجاه، والتسليم اليدوي للإنسان الذي يتّصل بالشرطة. الصوت الآلي قناة لاحقة فقط.
6. **حجم البيانات المحفوظة ومدّة حفظها وتقليلها**، واستضافة الخادم داخل البلد.
7. **طبقة BLE الثانوية**: إن أردتها، فاجعلها وسماً **ظاهراً** على الحقيبة المدرسية يعلم به الطفل، بوصفها ميزة احتياطية — ولا تراهن على شبكات البحث الجماعي في سوق منخفض الكثافة.

---

## Blockers versus manageable risks

*(The Arabic section above is the authoritative version, per the brief. This is a one-line English index.)*

- **Blockers (i):** concealing the transmitter from the child [61][67]; selling/importing a radio device without ARPCE homologation [60]; medical/stress claims; auto-notifying 1548/1055.
- **Filings + timeline (ii):** ANPDP prior authorisation for minors' location data [62]; ARPCE (and likely ANF) type approval — 17,500 DA + 150,000 DA, 5-year certificate, sample delivery in 5 days, **duration unknown** [60][70][71][72]; SMS Sender ID pre-registration ~2 weeks, Mobilis rejects generic/numeric IDs, no 2-way SMS [66]; cross-border transfer authorisation if hosting leaves Algeria [62]; **plus a paid written legal opinion on the Penal Code's private-life chapter — this is not a research gap you can close by reading.**
- **Design/disclosure choices (iii):** visible device + child informed; tamper-resistance instead of concealment; who buys the radio (parent-bought tracker = no homologation on you); multi-guardian visibility by default; push/email/one-way-SMS escalation chain with a human making the emergency call; in-country hosting; retention minimisation; visible BLE tag as optional redundancy.

---

## Evidence gaps

Stated explicitly, because an unsourced legal claim is worse than none.

**D1**
1. **No Algerian penal-code article number** criminalising or restricting the sale, import, possession or use of covert tracking devices. Consolidated codes were located [68][69] but not read. **This is the most serious gap in the file and it is blocking-grade for the concealed-device idea.**
2. No verified Algerian authority on concealment applied specifically to a child.
3. No verified MENA comparator jurisdiction banning concealed trackers.
4. **No US case law, statute or DPA guidance verified** on parental covert tracking. Only family-law firm blogs located, none read [78][79].
5. **No custody-dispute authority of any kind verified** — the entire D1.6 section is inference from the existence of the literature, not from its content.
6. **The exact text of Law 18-07 articles was not read this session.** Every article number in this file (4, 8, 9, 10, 15, 76, 77) came from a compressed extraction of the ANPDP guide [62] and must be re-verified against the JO text [63].
7. The ANPDP *Décisions* PDF (2024) [64] was not read — it may contain relevant decisions.
8. No ANPDP guidance found on geolocation specifically (as distinct from minors' data generally).
9. The full CNIL sentences were returned truncated with an ellipsis [61] — the complete wording must be re-read before it is quoted publicly.

**D2**
10. **ARPCE processing duration: not found anywhere.**
11. The decree number `26-97` and its Articles 9–10 were not verified against JORADP [60].
12. Whether a **pure BLE tag with no cellular radio** requires homologation — not established.
13. **No customs or import restriction on tracking devices located** — neither confirmed nor refuted.
14. **Marketplace precedent unproven:** the Ouedkniss "gps enfant" fetch returned a JavaScript error page, so **zero actual listings were verified** [74].
15. Whether operating a self-hosted server in Algeria requires any hosting/ISP authorisation — not established.
16. Whether TLS/cryptographic use engages Algerian crypto-import rules — not researched.

**D3**
17. **No cost figure for A2P SMS to Algeria.** Pricing pages located, none read.
18. Whether Sender ID registration runs through ARPCE, the operator, or the Ministry — not established.
19. **Robocall/automated-voice legality in Algeria: completely unverified.** No source located in either direction.
20. No Algerian statute located on misuse of emergency numbers (1548/1055).
21. No local Algerian SMS aggregator verified.

**D4**
22. **Google Find Hub / Find My Device network country coverage: NOT VERIFIED. Whether it operates in Algeria or any MENA country is unknown.** [67] This is the decisive D4 question and it is unanswered.
23. **Apple Find My network coverage in Algeria/MENA: not researched.**
24. **iPhone market share in Algeria: no data retrieved. No number is given and none should be assumed.**
25. **Samsung SmartThings Find / Tile coverage in MENA: not researched.**
26. No evidence located on crowd-network tags being used on children, or on child-safety framing by those networks.
27. BLE update-latency figures for low-density markets are my inference from range/density physics, not sourced measurements.

**Cross-cutting**
28. Both key Algerian official sources (ARPCE page [60], ANPDP guide [62]) returned **compressed extractions**. Nothing in this file that rests on them should be quoted externally without re-reading the primary page.
29. No peer-reviewed legal scholarship on any of these questions was retrieved.

---

## Sources

**[60]** ARPCE (Autorité de Régulation de la Poste et des Communications Électroniques, Algérie) — *Homologation* — https://www.arpce.dz/fr/service/agr — Accessed 2026-10-05 — **Tier: 1** (official regulator page; VERIFIED via compressed extraction). The only primary Algerian source retrieved on type approval. Returned: scope = telecom equipment connected to public networks; legal basis cited as "Decree 26-97, Arts. 9–10" (decree number unverified); fees 17,500 DA administrative + 150,000 DA technical; certificates valid 5 years; samples required within 5 days; and a hedged statement that GPS/SIM devices likely require homologation. No processing timeline.

**[61]** CNIL (Commission Nationale de l'Informatique et des Libertés, France) — *Géolocalisation des enfants : quels enjeux pour leur vie privée* (2025-09-22) — https://www.cnil.fr/fr/geolocalisation-des-enfants-quels-enjeux-pour-leur-vie-privee — Accessed 2026-10-05 — **Tier: 1** (data protection authority; VERIFIED via compressed extraction). The strongest authority found against the concealed-device plan. Warns that geolocating children harms their privacy; partial quote: « Avoir recours à des dispositifs… comporte le risque d'habituer le mineur à être sous surveillance constante »; recommends informing the child and obtaining the child's consent where mature; guidance is to avoid covert tracking. Quote was returned truncated — re-read before public use.

**[62]** ANPDP (Autorité Nationale de Protection des Données à caractère Personnel, Algérie) — *Guide du responsable de traitement*, V1.0 (2023) — https://anpdp.dz/wp-content/uploads/2023/08/guide_responsable-de-traitement_V1.0.pdf — Accessed 2026-10-05 — **Tier: 1** (official regulator guidance; VERIFIED via a very compressed extraction — article mapping must be re-verified). Returned mapping to Law 18-07: Art. 4 "représentant légal"; Arts. 8–9 "autorisation préalable" for sensitive data and data concerning minors; Art. 10 "données de localisation"; Art. 15 "transfert à l'étranger"; Arts. 76–77 "sanctions pénales". Basis for the conclusion that a child-location product falls in the authorisation regime.

**[63]** ANPDP — *Loi n° 18-07 relative à la protection des données à caractère personnel* (texte) — https://anpdp.dz/wp-content/uploads/2023/01/2.1-Loi-N%C2%B018-07-2.pdf — Accessed 2026-10-05 — **Tier: 1** (LOCATED-NOT-READ this session). The primary statutory text. Must be read to confirm every article number reported in [62].

**[64]** ANPDP — *Décisions* (document dated 03-06-2024) — https://anpdp.dz/wp-content/uploads/2024/09/fr-03-06-2024.pdf — Accessed 2026-10-05 — **Tier: 1** (LOCATED-NOT-READ). Potentially the authoritative record of ANPDP decisions; highest-value remaining pull for D1.

**[65]** APS (Algérie Presse Service, official state news agency) — *Publication de la première délibération fixant les conditions de désignation du délégué à la protection des données à caractère personnel* (2025-12-24) — https://www.aps.dz/fr/algerie/actualite-nationale/mjkdts4u-publication-de-la-premiere-deliberation-fixant-les-conditions-de-designation-du-delegue-a-la-protection-des-donnees-a-caractere-personnel — Accessed 2026-10-05 — **Tier: 1** (LOCATED-NOT-READ; the title is the finding). Establishes that the ANPDP's *first* published deliberation dates from December 2025 and concerns DPO designation — from which it is inferred that no published ANPDP deliberation on geolocation or on minors' data exists.

**[66]** Twilio — *Algeria: SMS Guidelines* — https://www.twilio.com/en-us/guidelines/dz/sms — Accessed 2026-10-05 — **Tier: 2** (official vendor documentation; VERIFIED via compressed extraction). The only verified source on Algerian A2P SMS. Returned: alphanumeric Sender IDs supported but domestic pre-registration required (~2 weeks); Mobilis rejects generic/numeric Sender IDs; no 2-way SMS. No pricing.

**[67]** Google — *Find Your Phone with Find Hub: Locate Devices & Share* — https://www.android.com/learn-find-hub/ — Accessed 2026-10-05 — **Tier: 2** (official vendor documentation; VERIFIED via compressed extraction). Returned: Find Hub locates devices offline via a crowd-sourced network; participating hardware referenced includes UWB tags such as "Pixel Tag"; provides alerts that detect unknown trackers; "availability varies by region/carrier". **No country list was returned — Algeria/MENA coverage remains unverified.** The unknown-tracker alert is the verified fact that makes a concealed BLE tag self-defeating.

**[68]** Ministère de la Justice (Algérie), portail du droit — *Code pénal, Article 350* — https://droit.mjustice.gov.dz/sites/default/files/portail/legisl_nouveau/code_penal_2010/fr/topics/588.html — Accessed 2026-10-05 — **Tier: 1** (LOCATED-NOT-READ). The official ministry portal publishes the Penal Code article-by-article; this is the correct entry point for closing gap (1) on covert-surveillance offences.

**[69]** IEDJA — *Code pénal Algérie 2015* (consolidated PDF) — https://iedja.org/wp-content/uploads/2017/12/Code-p%C3%A9nal-Alg%C3%A9rie-2015.pdf — Accessed 2026-10-05 — **Tier: 2** (LOCATED-NOT-READ). Consolidated text useful for searching the *atteinte à la vie privée* chapter. Note it predates the 2024 amendments.

**[70]** Applus Laboratories — *Algeria Radio Type Approval (ARPCE | ANF)* — https://www.appluslaboratories.com/global/en/what-we-do/service-sheet/algeria-radio-type-approval-(arpce--anf) — Accessed 2026-10-05 — **Tier: 2** (LOCATED-NOT-READ). Title establishes that **two** Algerian bodies are involved — ARPCE and ANF (Agence Nationale des Fréquences) — implying two filings for a radio device.

**[71]** IB Lenhardt — *Type Approval Algeria — ANF / ARPCE Label and Certification* — https://ib-lenhardt.com/type-approval/algeria — Accessed 2026-10-05 — **Tier: 2** (LOCATED-NOT-READ). Second corroboration of the dual ARPCE/ANF structure; likely holds the processing-duration figure that the official page did not give.

**[72]** GMA / CAICT (China Academy of Information and Communications Technology) — *Algeria: MPT updated type approval framework* — https://gma.caict.ac.cn/en/update/46/1227 — Accessed 2026-10-05 — **Tier: 2** (LOCATED-NOT-READ). Signals a recent revision of the Algerian type-approval framework by the Ministry of Post and Telecommunications, consistent with the `Decree 26-97` citation on the live ARPCE page. Implication: pre-2026 consultancy figures on cost and duration are likely stale.

**[73]** Vonage — *Algeria SMS Features and Restrictions* (2025-07-17) — https://api.support.vonage.com/hc/en-us/articles/204018093-Algeria-SMS-Features-and-Restrictions — Accessed 2026-10-05 — **Tier: 2** (LOCATED-NOT-READ). Second operator-grade source to cross-check the Twilio findings on Sender ID and one-way SMS.

**[74]** Ouedkniss (Algerian marketplace) — category *gps enfant* (Loisirs & Divertissements) — https://www.ouedkniss.com/gps-enfant-r?lang=fr — Accessed 2026-10-05 — **Tier: 3** (FETCH ATTEMPTED, FAILED — returned a JavaScript error page with no listing data). The existence of the category hints that child GPS products are traded in Algeria, but **no listing was verified**. Marketplace precedent for D2.3 is therefore unproven.

**[75]** Gide (international law firm) — *Mise en place de l'Autorité Nationale de Protection des Données à caractère Personnel (ANPDP)* (2023-07-26) — https://www.gide.com/news-insights/mise-en-place-de-lautorite-nationale-de-protection-des-donnees-a-caractere-personnel-anpdp/ — Accessed 2026-10-05 — **Tier: 2** (LOCATED-NOT-READ). Standard law-firm orientation on the ANPDP's establishment and powers.

**[76]** Algérie-Eco — *Réseaux sociaux : l'ANPDP fixe les règles pour la publication de photos et de vidéos de personnes* (2026-07-18) — https://algerie-eco.com/2026-07-18/reseaux-sociaux-lanpdp-fixe-les-regles-pour-la-publication-de-photos-et-de-videos-de-personnes/ — Accessed 2026-10-05 — **Tier: 2** (LOCATED-NOT-READ). Evidence that the ANPDP is actively rule-making in adjacent territory in 2026, i.e. it is an operative authority, not a dormant one.

**[77]** CNIL — *Géolocalisation et applications mobiles : quelles règles ?* (2026-07-07) — https://www.cnil.fr/fr/geolocalisation-applications-mobiles-quelles-regles — Accessed 2026-10-05 — **Tier: 1** (LOCATED-NOT-READ). Recently updated CNIL doctrine on mobile geolocation; the obvious next pull alongside [61]. CNIL's *Les droits numériques des mineurs* (https://www.cnil.fr/fr/enjeux-numeriques/les-droits-numeriques-des-mineurs) was likewise located and not read.

**[78]** Coker Legal (Texas family law firm) — *Can I Track My Spouse with a GPS or AirTag?* (2025-06-24) — https://www.cokerlegal.com/blog/2025/june/can-i-track-my-spouse-with-a-gps-or-airtag-what-/ — Accessed 2026-10-05 — **Tier: 2/3** (law-firm blog; LOCATED-NOT-READ). Cited only as evidence that covert GPS tracking is a litigated question in US family law. **No content asserted.**

**[79]** MKFM Law — *Can You Use GPS to Track Your Child During a Custody Dispute* (2025-07-31) — https://www.mkfmlaw.com/blog/can-you-use-gps-to-track-your-child-during-a-custody-dispute — Accessed 2026-10-05 — **Tier: 3** (law-firm marketing blog; LOCATED-NOT-READ). Cited only as evidence that the child-tracking-in-custody question is a recognised practice area. **No content asserted; no legal proposition rests on it.**

---

### Additional pointers found but not citable (unread, no claims made)
- sent.dm — *Algeria SMS Pricing 2025* — https://sent.dm/en/resources/sms-pricing/algeria-sms-pricing (for D3 cost).
- sms.to — *International SMS Sender ID Regulations and Requirements* (2026-05-29) — https://support.sms.to/support/solutions/articles/43000562659-international-sms-sender-id-regulations-and-requirements-list-of-countries-
- Google — *Find Hub: Locate, Lock, or Erase Your Device* — https://www.google.com/android/find/about (for D4 country coverage).
- androidpolice.com — *Google finally launches its Find My Device network* (2024-04-08) — https://www.androidpolice.com/google-launches-find-my-device-network/
- r/algeria — *Looking for SMS Gateway Providers for Mobilis, Djezzy…* — https://www.reddit.com/r/algeria/comments/1ji0wzt/ (Tier 3).
- Legifrance — *Code pénal, Article 226-1* — https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000049312755 (French comparator for *atteinte à la vie privée*).
- JORADP — *Code de procédure pénale* (2025) — https://www.joradp.dz/FTP/jo-francais/2025/F2025054.pdf
- Algérie-Eco — *Code pénal : les nouvelles dispositions publiées au Journal officiel* (2024-05-08) — https://algerie-eco.com/2024-05-08/code-penal-les-nouvelles-dispositions-publiees-au-journal-officiel/
- Hangley — *Tracking or Stalking? Where do we Draw the Line?* (2024-04-16) — https://www.hangley.com/blog/tracking-or-stalking-where-do-we-draw-the-line
- sidouni-technologie.com (2026-06-29) and grandstream.dz (2023-12-28) — Algerian vendor pages discussing an *agrément* regime for surveillance/CCTV installation activity. Tier 3.
