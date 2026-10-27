# KidGuard v2 — Overlooked Abduction-Prevention Variables (Areas C1–C5)

**Research date:** 2026-10-05
**Budget spent:** 10/10 WebSearch, 6/6 WebFetch. Two fetches failed outright (US State Dept Hague country list → HTTP 403; ONPE France 2024 PDF → HTTP 404). One fetch (DGSN Algeria) failed at network level.
**Method note — read this before citing anything below.** I distinguish three evidence states throughout:

| State | Meaning |
|---|---|
| **VERIFIED** | I retrieved the page/document and read the content in this session. |
| **LOCATED, NOT FETCHED** | The source appeared in search results with a title, publisher and (often) a date, but I did not retrieve its body. I make claims about *what the source is*, not about *what it says*. Treat every substantive claim attached to these as **UNVERIFIED**. |
| **NOT FOUND** | I searched and found nothing usable. Genuine evidence gap. |

Sources are numbered **[40]–[59]**.

---

## C1 — Family / parental abduction: the largest category, and the biggest hole in the current design

### C1.1 Incidence split — what I could and could not verify

**VERIFIED:** The Fourth National Incidence Studies of Missing, Abducted, Runaway, and Thrownaway Children (**NISMART-4**), authored by Sedlak et al. and published February 2022 by the National Institute of Justice / OJJDP, is the current best US dataset [41]. However, the NIJ landing page I retrieved is a **metadata record only** — it carries no incidence counts, no family/non-family split, and no perpetrator-relationship breakdown. **I could not verify any NISMART-4 numbers in this session.** Anyone quoting "X% of abductions are familial" from my work should not attribute it to me; go to the full NISMART-4 report or its NISMART-3 predecessor directly.

**LOCATED, NOT FETCHED:** "Family abduction in a national sample of US children", *Child Abuse & Neglect* (2016) [44] is a peer-reviewed treatment of the exact variable the design is missing. I did not read it. Its existence confirms the construct "family abduction" is a distinct, separately-measured category in the US literature — but I cannot report its rates.

**NOT FOUND — France:** The ONPE (Observatoire national de la protection de l'enfance) report *Les disparitions de mineurs en France en 2024*, tied to the 116 000 hotline and indexed as published 2025-06-04 [43], returned **HTTP 404** at the URL surfaced by search. The URL as indexed looks malformed (the publication date appears to have been concatenated into the filename: `.../2025-06-116000_rapport_les-disparitions...`). The correct path is likely `.../2025/06/116000_rapport_...`. **All French 2024 figures are therefore UNVERIFIED.** A secondary French-language item, "Disparitions de mineurs : des signalements en hausse de 6…" on droitdenfance.org (2026-05-25) [58], was likewise only located, not read.

**NOT FOUND — Algeria / MENA:** This is the most important negative result of Area C1. I found **no Algerian national statistic on child disappearance or abduction, and no Algerian or MENA family-vs-stranger split.** The only Algeria-specific series surfaced was Perspective Monde (Université de Sherbrooke) mirroring the World Bank / UNODC `CRIME.KIDNAP.TOT.NN` indicator for DZA [50] — which measures **total kidnappings of all ages**, not child abductions, and does not disaggregate by perpetrator relationship. The RCMP Gazette "Les faits — Disparitions et enlèvements d'enfants" (2026-08-14) and missingchildren.ch "Etudes & Statistiques" were located only [not numbered; not read].

**Operational conclusion for C1.1:** the design cannot be calibrated on Algerian data because none exists publicly. Any local figure KidGuard puts in a competition deck must be labelled as an extrapolation from foreign datasets, or omitted. This is itself a roadmap item: a "no national baseline" disclosure is more defensible to a judging panel than an invented Algerian rate.

### C1.2 Algeria is NOT a party to the 1980 Hague Child Abduction Convention

**VERIFIED (Tier 1).** From the official Hague Conference on Private International Law status table for Convention 24 (Civil Aspects of International Child Abduction), table dated **14-XI-2022**, **103 Contracting Parties**: **Algeria is not listed as a Contracting Party.** Among the African/MENA states the extraction returned, the parties were **Morocco, Tunisia and Iraq** [40].

> ⚠️ **Caveat on the MENA list:** the fetch returned a compressed answer and I did not see the raw table rows. I am confident in the **Algeria = not a party** finding (it was returned directly and unambiguously) but I would treat the "Morocco, Tunisia, Iraq" enumeration as **possibly incomplete** — Jordan, Lebanon and Turkey's statuses were not reported either way. Verify against the raw HCCH table before publishing a country list.

**Why this matters more than any other single finding in this brief:**

1. **There is no treaty return mechanism.** If a child is taken out of Algeria by a parent (or into Algeria by a parent living abroad), the 1980 Convention's central remedy — a summary administrative/judicial return proceeding through a Central Authority — does not exist. Recovery falls back on Algerian domestic law and consular/diplomatic pressure, which is slow and discretionary.
2. **Therefore real-time location is worth more, not less — but for a different reason.** In a Hague state, the legal system supplies the recovery mechanism and a tracker supplies evidence. In a non-Hague state, the tracker may be the *only* mechanism that establishes where the child physically is before the trail goes cold. The product's value proposition in Algeria shifts from "assist the legal process" to "preserve recoverability."
3. **It changes what the evidence artifact must be.** A non-Hague recovery is a diplomatic and police operation that will be argued about for years. Location history that is **exportable, timestamped, tamper-evident, and defensible as to provenance** is the deliverable. Live map dots are the demo; the forensic log is the product.
4. **It is also a competitive moat.** Every foreign kid-tracker assumes a Hague-style legal backstop. None of them design for "the remedy is unavailable." A MENA-first product that says so explicitly is doing something the incumbents structurally cannot.

**NOT VERIFIED:** Algeria's *Code de la famille* (Law 84-11 as amended) custody/hadana provisions, and how they interact with a location-sharing product. I did not search this in-session and make no claim about it. It is a required pre-launch legal review, not a design input.

### C1.3 Tracking a child in a custody dispute — the legal-risk literature

**What I verified:** essentially nothing at the level of holdings. What I found is a dense cluster of **US family-law firm blog posts** whose *titles alone* establish that this is a live, contested, jurisdiction-specific question, and one Reddit custody thread:

| Source | Date | Tier | Fetched? |
|---|---|---|---|
| "Can You Use GPS to Track Your Child During a Custody Dispute?" (mkfmlaw.com) [53] | 2025-07-31 | 3 | No |
| "Tracking Your Child During Florida Divorce" (barnettwoolums.com — slug: `illegal-track-child-divorce-florida`) [54] | 2026-07-02 | 3 | No |
| "Can you track your child during your co-parent's time with them?" (maddoxandgerock.com) [55] | 2023-10-02 | 3 | No |
| "Can I Track My Spouse with a GPS or AirTag? What Texas Law Says" (cokerlegal.com) [56] | 2025-06-24 | 3 | No |
| r/Custody "[GA] GPS Tracking & Stalking (Find my iPhone)" [59] | undated | 3 | No |

Note the **slug on [54] literally contains the word `illegal`**. That is a signal about the framing of the practice, not a legal finding, and I am not going to pretend otherwise.

**UNVERIFIED — and stated explicitly because the brief asked:** **I could not locate a specific documented case or court action in which one parent's tracking of a child was used against them, or in which a tracker was alleged to have facilitated harassment.** I found the *question* asked repeatedly by practitioners, and one anonymous forum thread. There is no case citation in this brief. Do not put one in the product report.

**What is nevertheless design-actionable, and is inference rather than research:** the pattern across all five titles is that legality turns on **(a) whose parenting time it is** and **(b) whether the tracking incidentally surveils the other parent**. A tracker on a child is, in practice, a tracker on the adult holding that child — because the child's location *is* the adult's location. That is the exposure. Consequences for KidGuard:

- **A "custody window" mode is mandatory, not optional.** During a court-ordered or agreed parenting-time block, geofence-departure alarms must be suppressed (the child is *supposed* to be elsewhere), while logging continues at full fidelity. Without this, the app generates a P0 every single transfer and trains the family to ignore alerts — which destroys the "silence = alarm" premise.
- **The app should refuse to render the other parent's location.** Show the *child* at a place ("child is at 12 Rue X"), never a tracked adult. This is both a privacy-law posture (Law 18-07 — already covered in prior research) and the single largest reduction in stalking-liability surface.
- **Per-guardian, revocable authorized-picker lists.** In the current design the "safe zone" concept is geographic. In a family-abduction scenario the threat is *authorized-looking*: the person picking the child up is on the list, or is a guardian. KidGuard needs an identity axis (who is allowed) that is independent of the geography axis (where is allowed), and each guardian must be able to revoke another's picker rights — with the revocation logged and notified to all others, not applied silently.

### C1.4 The adversary-is-a-guardian problem (structural, highest-severity design gap)

The whole KidGuard model — "the app lives ONLY on the guardians' phones", 2–7 guardians per child, parallel fan-out — assumes guardians are **allies**. In the single largest category of child abduction they are not. Three concrete failure modes follow, and none of them appear in the described architecture:

1. **Silent removal.** Any guardian can switch off the child's position source, uninstall the uploader, or remove the SIM. Today, per the design, that produces *silence*, which the ladder reads as an alarm — good. But it produces an alarm that is **indistinguishable from a dead battery or a coverage hole**, and the accusing guardian has no evidence of *who* did it. → Need: **tamper events attributed to a specific device/account**, plus a rule that a position source disabled from a *guardian's* phone notifies all *other* guardians by name.
2. **Authority capture.** Whoever holds admin rights over the child's Traccar device can delete history, edit geofences, or add themselves as a guardian. → Need: **multi-party authority** — no single guardian can unilaterally delete history, change the guardian set, or widen a geofence without co-confirmation from a second guardian (a quorum-of-2 model). This is the single most important server-side control in the product and it does not exist in the described architecture.
3. **Alert suppression.** A guardian who is the abductor simply mutes or acks the alert. With a 60-second ack window and parallel fan-out, one malicious ack closes the incident for everyone. → Need: **ack does not close; it assigns.** An ack from guardian A should convert the incident to "A owns it" while B…G remain notified and can escalate/override. A "no-confidence" path — any guardian can force the incident back to full fan-out and to police — must exist.

**This is the answer to "what has this design overlooked":** KidGuard is built for a *stranger* adversary and a *cooperative* guardian set. The modal real-world abduction has a *known* adversary who is *inside* the guardian set. Every assumption above inverts.

---

## C2 — The older-child cohort: autonomy, consent, and rejection

### C2.1 Measured rates of teens defeating tracking: they do not exist

**VERIFIED (Tier 1, peer-reviewed review).** *Digital Location Tracking of Children and Adolescents* (PMC11843666) [42] was retrieved. What I can report from it:

- **Youth circumvention is common but unmeasured.** The review states youth can leave their devices (specifically noting **watches**) behind to circumvent tracking, and lists this in its Table 1 of circumvention routes. **No quantitative rate of teens disabling, removing, hiding or resenting tracking is reported, and the review indicates no such measurement exists in the literature.**
- **No consent rates exist.** There is no published figure for what proportion of tracked adolescents consent to being tracked.
- **Harms** identified include **reduced trust** and **reduced autonomy**.
- **Data quality** is degraded by devices being **left behind or turned off**.
- **No custody-dispute guidance** is provided by the review.
- The review supports **age differentiation** in how much location is shared, driven by adolescents' developing privacy needs.

**Direct answer to the brief's question "measured rates if any exist":** **No. As of this review, there is no measured rate.** Any deck claim like "X% of teens disable tracking" is unsupported. The defensible claim is: *the peer-reviewed literature documents circumvention as a common, known failure mode and has not been able to quantify it* — which is arguably a stronger argument for designing against it than a number would be.

**LOCATED, NOT FETCHED** (relevant secondary commentary, contents unverified): *Scientific American*, "How GPS Tracking of Teens 24/7 Impacts Parent-Child Relationships" (2024-10-18) [51]; *The New York Times*, "Should You Track Your Teen's Location?" (2018-08-29) [52]; paradigmtreatment.com (Tier 3, treatment-centre content, **do not cite**).

### C2.2 Is continuous tracking of teens counter-productive? — an honest, partial answer

The brief asks me to say so directly if an assumed feature is contradicted by evidence. Here is what the evidence actually supports, and no more:

- **Supported:** [42] identifies reduced trust and reduced autonomy as harms of digital location tracking of children and adolescents, and identifies circumvention as a common response. A design that assumes a teen will passively accept continuous, covert, 24/7 tracking is **contradicted by the literature on circumvention**, because circumvention is the documented behaviour.
- **Not supported:** I found **no study** comparing outcomes (tracking adherence, trust, actual safety incidents) between continuous tracking and geofence-only or negotiated-tracking models. The recommendation for "negotiated tracking / teen-controlled sharing / geofence-only" is widely made in practitioner commentary and is *consistent with* [42]'s age-differentiation finding, but it is **not backed by a controlled evaluation that I could locate.** Label it as design rationale, not evidence.
- **Not supported:** I found **no product** documented as having "solved" teen consent well. No named exemplar. Evidence gap.

**The load-bearing design inference** (inference, not research): if circumvention is common and unmeasurable, then **a product whose safety guarantee depends on the teen not circumventing it has no safety guarantee.** Therefore for the older cohort the architecture must stop treating the phone as the position source of record. Two consequences:

1. **KidGuard's existing swappable-position-source spine is the right answer** — but for the teen cohort the *non-phone* source (bracelet, or a second cheap device the teen cannot quietly park) is what carries the guarantee, and the teen's own phone becomes a *corroborating* signal. This should be stated in the design rationale; today the phone appears to be treated as a peer option.
2. **Consent is a safety feature, not a compliance feature.** A teen who agreed to geofence-only sharing and can see what is shared defeats the tracker far less often than a teen who is covertly tracked. Transparency is the anti-circumvention mechanism.

### C2.3 "Phone left behind" detection — NOT FOUND as research; here is the engineering answer

**NOT FOUND.** I located no product literature, no vendor documentation, and no research on detecting that a tracked phone is not on the tracked person. My one search slot that could have covered this was spent on Algeria emergency numbers, which the brief prioritised. Treat everything below as **design inference, unverified**.

The general problem is *device-person divergence*, and it has four detectable signatures, in decreasing order of reliability:

| Signature | Detector | Failure mode |
|---|---|---|
| **Divergence between two sources** | Bracelet/uploader and phone report positions >N m apart, or one goes silent while the other moves | Only works if the child carries two sources — expensive |
| **Motion mismatch** | Phone IMU shows ~0 step energy while the child's schedule says they should be walking (school-dismissal window); or phone is stationary for >T minutes at a location that is not a known safe zone | Works well for the 3–4 p.m. peak-risk window specifically |
| **Charging / connection state** | Phone reports charging, or on a known home Wi-Fi/BSSID, while a departure was expected | Cheap, high specificity for "left at home" |
| **Proximity to the guardian** | Phone BLE-visible from a guardian's handset but child expected to be at school | Only in the reverse case (phone brought home) |

The **practical minimum viable version** for KidGuard, given the existing 3-reading debounce and safe-zone machinery: a **"expected-departure not observed"** rule. If a safe zone (school) has a configured dismissal time and the position source has produced **zero net displacement** for 15 minutes past dismissal, raise a distinct alert type — `SOURCE_STATIONARY_AT_ZONE` — that is **separate from** `GEOFENCE_EXIT`. Today, a teen who leaves their phone in a locker produces *no* event at all: the phone never exits the geofence, so the system reads "child is safely at school" for hours. **That is a false-negative that the current design cannot detect, and it is the exact inverse of the alert the product exists to raise.** This is the highest-value single fix in Area C2.

---

## C3 — Procedural and organizational variables

**Honest scope statement:** Area C3 is the area I under-served. My search budget was consumed by C1, C2, C4 and C5. The following C3 sub-questions were **never searched** and I have nothing to report on them:

- School dismissal / release-authorization procedures, photo-ID verification of the picker, authorized-picker lists, sign-out logs — **no search run.**
- Child ID kits, medical-ID and contact-info bracelets for non-verbal or disabled children, and whether they measurably help — **no search run.**
- Community notification and crowd-search effectiveness, including Facebook/WhatsApp child-search groups in MENA — **no search run.**

What I did run:

### C3.1 Safe word / family code word — I found advice, not evidence

**NOT FOUND — empirical evaluation.** I located **no study, evaluation, or agency assessment of whether family code words reduce abduction risk.** What the search surfaced instead is the code word appearing *inside official and semi-official prevention materials*: a Utah state government parent-and-guardian handout on child sexual abuse and human trafficking (utah.gov/pmn, PDF `1177101.pdf`, dated 09-30-24) [57], and a self-published practitioner ebook, *Parenting in the Digital World*, 4th ed. (cybersafetycop.com) — Tier 3, not numbered. **Neither was fetched**; I am reporting their existence and provenance only.

**Answer to the brief's direct question:** as far as this search shows, **the family code word is folklore with institutional endorsement, not an evidence-based intervention.** Its presence in a state government handout makes it *authoritative-sounding*, which is not the same as *validated*. I could not find any measurement of whether children remember the word under stress, whether they use it, or whether an abductor who has surveilled the family can elicit it. **KidGuard should not claim that a code-word feature prevents abduction.**

**However — a genuinely useful reframing I did find.** The top organic result for the code-word search was "Why Your Family Needs a Code Word in the Age of AI Kidnapping Scams" (motheroohq.com, Tier 3, not numbered). Whatever the merits of that source, it points at a real and *different* function: in 2026 the code word's highest-value use is **authenticating an inbound caller** — i.e. defeating AI voice-clone "your child has been taken, send money" extortion — **not authenticating an outbound picker.** Those are different threat models with different requirements. A KidGuard code-word feature designed as a **caller-verification challenge inside the alert flow** ("the person claiming to have your child must produce the family word") addresses an active, documented-by-media fraud vector, and does not require KidGuard to make an unevidenced abduction-prevention claim. That is a reframe worth taking to the roadmap; the underlying scam trend is **unverified in this session** and should be confirmed from a law-enforcement source before it is used in a deck.

### C3.2 "Tricky people" vs "stranger danger" — the critique is evidenced, the replacement is not

**LOCATED, NOT FETCHED (Tier 1):** "Parental Understandings of 'Stranger Danger': Balancing…" in *Children & Society* (Wiley, DOI 10.1111/chso.13001) [45]. This is peer-reviewed research on how parents understand and operationalise stranger danger. **I did not read it** and will not summarise its findings.

**Located, Tier 3:** protectyoungeyes.com, "Tricky People – Stranger Danger in the Digital Age"; a HuffPost social post asserting "child safety experts now recommend teaching the concept of 'tricky people'"; a Tim Tebow Foundation post ("many unsafe people aren't strangers"). All **not fetched**, all **unverified**, and the latter two are social media — I would not cite either in a competition deck.

**Honest verdict:**

- **Supported by the wider literature and by KidGuard's own prior research:** the *critique* of "stranger danger" — that most harm to children comes from people known to them, so a stranger-centric frame mis-directs protective effort. This is consistent with the family-abduction predominance documented in NISMART-series work [41][44] and with the ONPE framing [43].
- **NOT supported:** any claim that "tricky people" is an *evidence-based* replacement. "Tricky people" appears to be a practitioner/child-safety-educator framing disseminated through advocacy content. **I found no controlled or quasi-experimental evaluation of it.** (The attribution of the phrase to a specific named educator is something I believe to be true but did **not** verify in this session — omit the name from the report.)

**Design implication:** KidGuard's alerting taxonomy should not be organised around "stranger". A safe-zone breach caused by a *known* person is the higher-probability event, so the RiskEngine's contributing factors should include **known-person proximity / authorized-picker mismatch** as a first-class factor, not just unknown-device or zone-exit signals. Note this is only implementable if the child's position source can distinguish accompanying adults — which a GT06 cannot. That is a capability gap worth naming.

---

## C4 — Guardian-side variables

### C4.1 Parallel fan-out is a diffusion-of-responsibility machine

**LOCATED, NOT FETCHED (Tier 1):** Darley & Latané's classic "Bystander intervention in emergencies: Diffusion of responsibility" (full text mirrored at scispace) [46]; EBSCO Research Starters, "Diffusion of responsibility"; The Decision Lab reference guide (2025-06-18); and a recent arXiv paper, "Bystander effect emerges from individual psychological…" (arXiv 2601.06709, 2026-01-10) [47]. I did not read any of them.

Diffusion of responsibility is one of the most replicated findings in social psychology, and its core structure maps **exactly** onto the KidGuard escalation ladder:

> When N people are alerted simultaneously and none is individually designated, each person's subjective probability that *someone else* has already acted rises with N, and per-person response probability falls.

**This directly challenges a stated design assumption.** "Parallel fan-out to all guardians" + "silence = alarm" + "60-second acknowledgement window" is a *group* alert with an *undifferentiated* audience. The 60-second window is a timer, not a role assignment. The predictable failure: at 15:42, five guardians each get a push; each sees four other recipients; each assumes another will ack; nobody acks; the system correctly escalates — but **30–180 seconds have been burned on a coordination failure, not on the threat.** In the >2-hour realization-to-report gap that prior research already established, the last thing the product should add is self-inflicted latency.

**Concrete mitigations (design inference grounded in [46][47]'s construct, not in a fetched study):**

1. **Named first responder, deterministically chosen.** At incident creation the relay picks ONE guardian as owner by a published rule (proximity to last fix → custody schedule for the current time block → rotation). The push reads "**Amina — you are first. Acknowledge by 15:43:00.**" Directed requests are the standard counter to diffusion; a group broadcast is not.
2. **Ack assigns, it does not close.** After A acks, B–E are re-notified with **distinct named tasks**, not the same generic alert: *B — call 1548 and report the incident ID. C — drive to last fix. D — call the school. E — contact the other household.* Role differentiation converts a diffuse crowd into a small incident team.
3. **Duplicate-emergency-call suppression.** If both B and E call 1548/1055 for the same event, the police receive two unlinked reports about the same child, which fragments the case and wastes the golden hour. The app needs a shared **"1548 called — by B — at 15:44:12"** state flag, and should show a pre-formatted report (see C5.3) rather than letting each guardian improvise.
4. **The single biggest structural precedent is the Incident Command System's "single incident commander / unified command" principle** from emergency management. **I did not search for it and cannot cite it.** It is flagged here as a lead for whoever continues this thread, not as a finding.

### C4.2 Divorced / separated / cross-border guardians

**NOT FOUND** as a discrete research literature. What I have is the intersection of two verified facts: (a) family abduction is the largest category [41][44], and (b) Algeria is not a Hague party [40]. Combining them gives a concrete product requirement that no foreign-designed tracker carries:

- **Location sharing between two households must be modelled as a consent object, not an account property.** Separate, revocable, expiring, and logged — with the log exportable. In a non-Hague jurisdiction the sharing history is evidence: it shows who could see the child, when, and who revoked.
- **Custody-calendar awareness.** The system must know whose parenting time it is, or it will mis-alarm on every lawful transfer (see C1.3).
- **Cross-border is the worst case and deserves an explicit product answer:** if the position source reports a fix outside Algeria, that is a **P0 of a different kind** — not "child is lost" but "child is leaving the jurisdiction", where the correct response is airport/port/police immediately and the available legal remedy is much weaker [40]. Today, per the described design, a border crossing would just look like a geofence exit.

### C4.3 Guardian incapacitation / no guardian acknowledges

**NOT FOUND.** No precedent located for who should receive escalation when the entire guardian set is unreachable. This is an open design question, and the honest answer is that KidGuard has to *invent* its policy and label it as a design decision, not as a researched standard. A defensible default: escalate past the guardian set in the order **co-parent/second household → pre-registered neighbour or extended-family contact → the child's school → police (1548/1055)**, with each rung timed and logged, and with the school rung requiring a pre-established relationship (the school must be onboarded, which is a deployment constraint the current design does not acknowledge).

### C4.4 Deaf / hard-of-hearing / elderly guardians

**NOT SEARCHED — skipped.** No research retrieved. Recording the requirement only, as an accessibility obligation rather than a finding: a P0 alert cannot be audio-only. Needs simultaneous **strong patterned vibration, full-screen high-contrast visual takeover, repeating text alert in the guardian's language, and an in-app text-first acknowledgement path** (so a guardian who cannot hear the alarm can still ack). For elderly guardians, large-target single-tap ack and no gesture-based interaction. **No source; label as requirement.**

---

## C5 — Algeria / MENA-specific variables

### C5.1 Algerian emergency numbers — partially verified, one explicitly unverified

The brief asked me to verify **police 1548** and **gendarmerie 1055**.

| Number | Status | Basis |
|---|---|---|
| **1548** — DGSN (Direction générale de la sûreté nationale / national police) | **CORROBORATED, page not read** | The official DGSN domain surfaced the URL `https://www.dgsn.dz/?Ayez-le-reflexe-1548` — the slug itself is the campaign line *"Ayez le réflexe 1548"* ("have the 1548 reflex"). The fetch **failed at network level**, so I did not read the page. The number is on the official police domain's own URL, which is strong corroboration but not a read source [48]. |
| **1055** — Gendarmerie nationale | **UNVERIFIED** | Nothing authoritative retrieved in this session. The only Algeria number-guide found was fibladi.com (Tier 3, 2026-07-09) [49], not fetched. **Check against gendarmerie.dz before shipping a dial button.** |
| **14** — Protection civile (ambulance/fire) | **UNVERIFIED** | Same basis as 1055 — named in the fibladi page title only [49]. |
| **17** — police (legacy/landline short code) | **UNVERIFIED** | Referenced by the French Wikipedia "Numéro d'appel d'urgence" page in search results; not fetched, not numbered. |

**Design consequence:** KidGuard should not hardcode these as literals it cannot source. Ship an **editable, country-prefilled emergency-number registry** with a first-run confirmation step ("Tap to confirm these are the correct numbers for your wilaya"), and surface the DGSN URL slug as provenance. A wrong number on a panic screen is a worse failure than no number.

### C5.2 Mosque / Quranic school / shop as a "safe node" — NOT FOUND for any Arab country

**NOT FOUND.** My search for a documented "safe place" / "safe shop" scheme for children in an Arab country returned nothing on-topic (results were UNICEF and UNHCR annual reports, a Turkey travel advisory, a JICA bulletin, an ILO plenary transcript — none relevant). **I have no evidence that any such scheme operates in Algeria or elsewhere in MENA.**

This cuts two ways, and the second is more useful:

- **It is an evidence gap, not a green light.** KidGuard cannot claim "we integrate with an existing national safe-shop network" because no such network was found.
- **It is also an opening.** The dense, universally distributed, socially trusted physical infrastructure in Algeria — mosques, Quranic schools (*kouttab*), neighbourhood *hanout* shops — is exactly the substrate such a scheme would need, and nobody appears to have formalised it. A **"safe node" registry** (guardian-nominated, photo-verified, geocoded, appearing as a distinct map layer with a "walk me to the nearest node" instruction during an alert) is a credible differentiation and a strong competition narrative. **It must be presented as a novel proposal with no efficacy evidence, never as a proven intervention.** Analogous schemes in the US and UK exist in my background knowledge but I did **not** verify them this session — do not cite them without checking.
- **The child-side requirement is the hard part:** a safe node is useless unless the child has been taught to go to one. That is a curriculum/behaviour-change dependency outside the app, and it belongs in the risk register of the proposal.

### C5.3 Pre-formatted police report — no research, but the inference is strong

**NOT FOUND** as a studied intervention. However, prior verified research in this project established a **>2-hour realization-to-police-reporting gap**. Reducing friction at the reporting step is the obvious lever on that gap, and a **one-tap, pre-filled report** is the cheapest possible friction reduction. It should contain: child's full name, date of birth, height/weight, what they were wearing *today* (a field the app should prompt the guardian to fill each morning — a novel and cheap data capture), a current photo, last known fix with timestamp and accuracy radius, the direction of travel if derivable, guardian names and callback numbers, the incident ID, and the correct emergency number for the wilaya. It should be **exportable as text and image** (for WhatsApp/SMS forwarding to police or to a search group), in **Arabic first with a French rendering**, and **printable** — because an Algerian police station interaction may well be on paper. **Label: design inference from prior verified research, not an evaluated intervention.**

### C5.4 How Algerian children travel to school — NOT SEARCHED

Skipped for budget. No data retrieved on walking distances, school transport, or rural/urban split. This is a **real gap that materially affects geofence design**: buffer sizes (50 m / 100 m on roads) and debounce windows are only correct if you know the mode and duration of the commute. Rural Algerian children may walk several kilometres with intermittent 2G coverage — which interacts badly with the GT06-class tracker's 2G dependency already flagged in the architecture notes. **Prioritise this in the next research pass.**

### C5.5 Literacy and language (Arabic / French / Tamazight) — NOT SEARCHED

Skipped. No guidance retrieved on designing for low digital literacy among guardians. Recording only that the alert path must not be text-dependent at the moment of panic (icon + colour + vibration pattern + voice), and that Tamazight coverage is a distinct question from Arabic/French — **unverified, no source.**

---

## What this changes in the design

Ranked by severity × novelty relative to the described architecture.

1. **Add a quorum-of-2 authority model on the server (P0, new).** No single guardian may unilaterally delete location history, widen/remove a geofence, or alter the guardian set without a second guardian's confirmation. Rationale: the largest abduction category has an adversary *inside* the guardian set (C1.1, C1.4). Without this, KidGuard is a tool a determined guardian-abductor can neutralise and then erase.
2. **Split "ack" into "ack = take ownership" and add a no-confidence override (P0, new).** Any guardian can force an acked incident back to full fan-out and to police. Prevents a single malicious or panicked ack from closing a real incident (C1.4, C4.1).
3. **Introduce a deterministic named first-responder role and role-differentiated downstream tasks (P0, change to existing feature).** Replace undifferentiated parallel fan-out with "YOU are first — ack by HH:MM:SS", then assign call-police / go-to-last-fix / call-school / contact-other-household to named individuals. Directly counters diffusion of responsibility (C4.1).
4. **Add `SOURCE_STATIONARY_AT_ZONE` — the "phone left behind" alert (P0, new).** If a position source shows zero net displacement past an expected departure time at a known zone, raise a distinct alert. Today a teen's parked phone produces *no* event and the system reports a false "safe at school" for hours (C2.3). This is a false-negative in the exact window the product exists to cover.
5. **Add custody-window / parenting-time mode (P1, new).** Suppress geofence-departure alarms during scheduled transfers while logging at full fidelity; raise a distinct **P0-jurisdiction** alert if a fix appears outside national borders (C1.3, C4.2). Algeria's non-Hague status makes the border case the single highest-stakes event the product can witness [40].
6. **Make the forensic log the primary artifact (P1, reframing).** Immutable, timestamped, tamper-evident, one-tap-exportable location and event history, plus a printable Arabic-first pre-formatted police report. In a non-Hague jurisdiction, evidence preservation and reporting-latency reduction *are* the product (C1.2, C5.3).
7. **Rebuild the teen cohort around consent + a non-phone source of record (P1, change to existing feature).** Geofence-only default, teen-visible "what is shared with whom" screen, teen-controllable temporary sharing pause with guardian notification. Because circumvention is documented and unmeasurable [42], transparency is the anti-circumvention mechanism; the phone must be treated as a corroborating signal, not the guarantee.
8. **Add a revocable, per-guardian, notified authorized-picker list — an identity axis orthogonal to the geography axis (P1, new).** Every revocation is logged and broadcast to all other guardians. Addresses the "authorized-looking threat" that safe zones cannot see (C1.3, C3.2).
9. **Add tamper attribution (P1, new).** Position-source power-off, SIM-removal, and app-uninstall events attributed to the specific device and account, with all *other* guardians notified by name. Converts today's ambiguous silence into evidence (C1.4).
10. **Add a caller-verification code word inside the alert flow — not a picker code word (P2, new, reframed).** Defeats AI voice-clone "we have your child" extortion. Ship with **no** abduction-prevention efficacy claim, because none exists (C3.1).
11. **Add a guardian-nominated, photo-verified "safe node" map layer (P2, new, unproven).** Mosques, Quranic schools, neighbourhood shops. Present as a novel proposal with zero efficacy evidence; flag the child-education dependency (C5.2).
12. **Ship an editable emergency-number registry with first-run confirmation (P2, new).** Do not hardcode 1548/1055/14 — only 1548 is corroborated, and only by a URL slug (C5.1).
13. **Add duplicate-emergency-call state ("1548 called by B at 15:44") (P2, new).** Prevents two unlinked police reports fragmenting one case (C4.1).
14. **Accessibility baseline for the alert path (P2, requirement, unsourced).** Patterned vibration + full-screen visual + repeating text + text-only ack path; large single-tap targets (C4.4).
15. **Add a "no national baseline" disclosure to the pitch (P2, integrity).** Do not present extrapolated US/French rates as Algerian statistics (C1.1).
16. **De-prioritise "stranger" framing in the RiskEngine taxonomy (P2, change to existing feature).** Known-person / authorized-picker-mismatch should be a first-class contributing factor. Note this needs a position source that can distinguish accompanying adults — a GT06 cannot (C3.2).

**One assumed feature actively challenged by evidence:** *"parallel fan-out to all guardians with silence = alarm"* is structurally vulnerable to diffusion of responsibility [46][47]. It should become *directed first responder + role-differentiated fan-out + named override*.

**One assumed feature not contradicted, but unsupported:** *"family safe word for the picker"*. No efficacy evidence exists (C3.1). Keep it, but reframe it to caller verification and drop the prevention claim.

---

## Evidence gaps

**Could not verify — highest priority:**
1. **Any NISMART-4 numbers.** The NIJ page is metadata only [41]. Need the full report for the family/non-family/stereotypical-kidnapping split.
2. **France ONPE 2024 missing-minor figures.** The indexed URL 404s [43]; the path is likely malformed. Retry with `.../2025/06/116000_rapport_...`.
3. **Algerian or MENA child-disappearance statistics of any kind.** None found. Only an all-ages UNODC kidnapping series for DZA [50].
4. **Algerian family-abduction incidence.** None found.
5. **A specific case or ruling where a parent's tracking was used against them, or where a tracker was alleged to facilitate harassment.** NOT FOUND. Only law-firm blog titles [53][54][55][56] and one anonymous forum thread [59]. **Do not assert a case exists.**
6. **Measured rates of adolescents disabling/removing/hiding parental tracking.** The peer-reviewed review reports circumvention as common and **unmeasured**, and reports **no consent rates** [42]. This gap is itself the finding.
7. **Any product that solved teen consent well.** NOT FOUND.
8. **Any evaluation of family code words.** NOT FOUND.
9. **Any evaluation of "tricky people" as a pedagogy.** NOT FOUND. The *stranger-danger critique* is evidenced [45][41][44]; the *replacement* is not.
10. **Any "safe place / safe shop" scheme documented in an Arab country.** NOT FOUND.
11. **1055 (Gendarmerie) and 14 (Protection civile).** UNVERIFIED — Tier 3 guide only [49]. **1548** corroborated by the official DGSN URL slug but the page was not read [48].
12. **Algeria's Code de la famille custody provisions** and their interaction with location sharing. Not searched.
13. **The full MENA/African list of 1980 Hague parties.** The extraction returned only Morocco, Tunisia, Iraq and may be incomplete [40]. Verify against the raw HCCH table.

**Skipped for budget — never searched:**
14. School dismissal / release-authorization procedures, photo-ID picker verification, sign-out logs (C3).
15. Child ID kits and medical-ID/contact bracelets for non-verbal or disabled children — efficacy (C3).
16. Community notification and crowd-search effectiveness, incl. MENA WhatsApp/Facebook child-search groups (C3).
17. How Algerian children travel to school — walking distances, school transport, rural vs urban (C5). **This one materially affects geofence buffer and debounce tuning; prioritise it.**
18. Arabic/French/Tamazight literacy and low-digital-literacy design guidance (C5).
19. Deaf/hard-of-hearing and elderly guardian alert-modality accessibility requirements (C4).
20. Guardian-incapacitation escalation precedent (extended family / school / neighbours / police) (C4).
21. Emergency-management literature on role assignment in multi-responder families (e.g. Incident Command System) — flagged as a lead, not researched (C4.1).
22. US/UK "safe place" scheme documentation — noted from background knowledge, **not verified this session**; do not cite.

**Fetch failures:** travel.state.gov Hague country list → HTTP 403; ONPE PDF → HTTP 404; dgsn.dz → network failure. Three of six fetch slots produced no content.

---

## Sources

**[40] Hague Conference on Private International Law (HCCH) — Status table, Convention of 25 October 1980 on the Civil Aspects of International Child Abduction (Cid 24) — https://www.hcch.net/en/instruments/conventions/status-table/?cid=24 — Accessed 2026-10-05 — Tier: 1 — VERIFIED.**
The authoritative registry of Contracting Parties to the 1980 Hague Child Abduction Convention; the table retrieved is dated 14-XI-2022 and lists 103 Contracting Parties. **Algeria is not a Contracting Party**; the extraction named Morocco, Tunisia and Iraq among African/MENA parties (this enumeration may be incomplete and should be re-checked against the raw table). This is the single most consequential finding for KidGuard: no treaty return mechanism exists for cross-border parental abduction to or from Algeria.

**[41] Sedlak, A.J. et al. / National Institute of Justice & OJJDP — Fourth National Incidence Studies of Missing, Abducted, Runaway, and Thrownaway Children (NISMART-4) — https://nij.ojp.gov/library/publications/fourth-national-incidence-studies-missing-abducted-runaway-and-thrownaway — Accessed 2026-10-05 — Tier: 1 — VERIFIED (metadata only).**
The current best US national incidence study of missing and abducted children, published February 2022. The page retrieved is a bibliographic/metadata record and contains **no incidence counts, no family-vs-non-family split and no perpetrator-relationship breakdown**. Cited here to establish the authoritative dataset and its publication details, not for any statistic.

**[42] Digital Location Tracking of Children and Adolescents (peer-reviewed review) — https://pmc.ncbi.nlm.nih.gov/articles/PMC11843666/ — Accessed 2026-10-05 — Tier: 1 — VERIFIED.**
A review of location tracking of children and adolescents covering devices, motives, and outcomes. It reports that **youth circumvention is common but unmeasured** (noting in its Table 1 that youth can leave devices — specifically watches — behind to defeat tracking), that **no consent rates exist**, that harms include **reduced trust and reduced autonomy**, that data quality degrades when devices are **left behind or turned off**, that it offers **no custody-dispute guidance**, and that **age differentiation** in sharing is warranted. The primary evidence base for Area C2.

**[43] ONPE — Observatoire national de la protection de l'enfance / 116 000 — Rapport : les disparitions de mineurs en France en 2024 — https://onpe.france-enfance-protegee.fr/wp-content/uploads/2025-06-116000_rapport_les-disparitions-de-mineurs-en-france-en-2024_vdef.pdf — Accessed 2026-10-05 — Tier: 1 — NOT RETRIEVED (HTTP 404).**
The French national child-protection observatory's annual report on missing minors, indexed as published 2025-06-04 and tied to the 116 000 hotline. The URL as surfaced returned 404 and appears malformed (the publication date seems concatenated into the filename); the correct path is probably `.../2025/06/116000_rapport_...`. **No French figure in this brief is verified.**

**[44] Child Abuse & Neglect (Elsevier) — Family abduction in a national sample of US children — https://www.sciencedirect.com/science/article/abs/pii/S0145213416302599 — Accessed 2026-10-05 — Tier: 1 — LOCATED, NOT FETCHED.**
A 2016 peer-reviewed article in a leading child-maltreatment journal treating "family abduction" as a distinct measured category in a US national sample. Located to confirm the construct is separately studied; **contents not read, so no rate from it is reported here.**

**[45] Children & Society (Wiley) — Parental Understandings of 'Stranger Danger': Balancing… — https://onlinelibrary.wiley.com/doi/full/10.1111/chso.13001 — Accessed 2026-10-05 — Tier: 1 — LOCATED, NOT FETCHED.**
Peer-reviewed research on how parents understand and act on the "stranger danger" construct. Located as the strongest available source on the stranger-danger framing question in Area C3; **contents not read and findings not summarised.**

**[46] Darley, J.M. & Latané, B. — Bystander intervention in emergencies: Diffusion of responsibility — https://scispace.com/pdf/bystander-intervention-in-emergencies-diffusion-of-4v8swxtbpe.pdf — Accessed 2026-10-05 — Tier: 1 — LOCATED, NOT FETCHED.**
The classic, heavily replicated study establishing that the presence of other potential helpers reduces the probability that any individual intervenes, because responsibility is diffused across the group. Cited for the construct that underpins the challenge to KidGuard's undifferentiated parallel fan-out (C4.1); **full text not retrieved.**

**[47] arXiv 2601.06709 — Bystander effect emerges from individual psychological… — https://arxiv.org/html/2601.06709v1 — Accessed 2026-10-05 — Tier: 1 (preprint) — LOCATED, NOT FETCHED.**
A January 2026 preprint modelling the bystander effect as emerging from individual-level psychological processes rather than group-level apathy. Noted as a recent restatement of the diffusion-of-responsibility mechanism supporting the C4.1 alerting redesign; **not read, and a preprint rather than peer-reviewed.**

**[48] Direction générale de la sûreté nationale (DGSN), Algeria — "Ayez le réflexe 1548" — https://www.dgsn.dz/?Ayez-le-reflexe-1548 — Accessed 2026-10-05 — Tier: 1 — PAGE NOT RETRIEVED (network failure).**
The official website of the Algerian national police. The fetch failed, so the page content was **not read**; however the URL slug returned by search is the campaign phrase *"Ayez le réflexe 1548"*, which corroborates **1548** as a DGSN-published emergency number. This is corroboration-by-URL, not a read source. **1055 and 14 are not corroborated by this.**

**[49] fibladi.com — Numéros d'urgence en Algérie : ambulance, police, gendarmerie nationale et protection civile — https://fibladi.com/fr/guide/numeros-durgence-en-algerie-ambulance-police-gendarmerie-nationale-et-protection-civile-1542705fr — Accessed 2026-10-05 — Tier: 3 — LOCATED, NOT FETCHED.**
A 2026-07-09 community/expat guide listing Algerian emergency numbers. Its existence and title are the only reason the gendarmerie and protection-civile numbers appear in this brief at all; **it is not authoritative, was not read, and 1055/14 remain UNVERIFIED.**

**[50] Perspective Monde / Université de Sherbrooke — Algérie : Enlèvements (statistiques) — https://perspective.usherbrooke.ca/bilan/servlet/BMTendanceStatPays/?codeStat=CRIME.KIDNAP.TOT.NN&codePays=DZA&codeTheme=16 — Accessed 2026-10-05 — Tier: 2 — LOCATED, NOT FETCHED.**
A statistical mirror of the World Bank / UNODC `CRIME.KIDNAP.TOT.NN` indicator for Algeria. Located as the only Algeria-specific kidnapping series surfaced; it measures **total kidnappings of all ages** and does not disaggregate children or perpetrator relationship, so it cannot support any child-abduction claim. **Not fetched; no value reported.**

**[51] Scientific American — How GPS Tracking of Teens 24/7 Impacts Parent-Child Relationships (2024-10-18) — https://www.scientificamerican.com/article/how-gps-tracking-of-teens-24-7-impacts-parent-child-relationships/ — Accessed 2026-10-05 — Tier: 2 — LOCATED, NOT FETCHED.**
Mainstream science journalism on the relational effects of continuous GPS tracking of adolescents. Located as a relevant secondary source for Area C2; **contents not read and no claim from it is made here.**

**[52] The New York Times — Should You Track Your Teen's Location? (2018-08-29) — https://www.nytimes.com/2018/08/29/well/family/should-you-track-your-teens-location.html — Accessed 2026-10-05 — Tier: 2 — LOCATED, NOT FETCHED.**
A *Well/Family* advice-and-evidence piece on parental location tracking of teenagers. Located for Area C2; **not read**, and pre-dates the 2025 review [42] so should not be preferred over it.

**[53] mkfmlaw.com (US family-law practice) — Can You Use GPS to Track Your Child During a Custody Dispute? (2025-07-31) — https://www.mkfmlaw.com/blog/can-you-use-gps-to-track-your-child-during-a-custody-dispute — Accessed 2026-10-05 — Tier: 3 — LOCATED, NOT FETCHED.**
Law-firm marketing/commentary on the legality of GPS-tracking a child during contested custody. Cited only as evidence that the question is live in US family-law practice; **not read, US-specific, and not a legal authority for Algeria.**

**[54] Barnett & Woolums (US family-law practice) — Tracking Your Child During Florida Divorce (2026-07-02) — https://www.barnettwoolums.com/blog/illegal-track-child-divorce-florida — Accessed 2026-10-05 — Tier: 3 — LOCATED, NOT FETCHED.**
Florida-specific law-firm commentary; notable because the **URL slug contains the word `illegal`**, indicating the practice is framed as legally hazardous in at least some US jurisdictions. **Not read**; the slug is a framing signal, not a legal finding.

**[55] Maddox & Gerock (US family-law practice) — Can you track your child during your co-parent's time with them? (2023-10-02) — https://www.maddoxandgerock.com/blog/can-you-track-your-child-during-your-co-parents-time-with-them — Accessed 2026-10-05 — Tier: 3 — LOCATED, NOT FETCHED.**
Law-firm commentary squarely on the fact pattern most relevant to KidGuard's custody-window requirement: tracking a child *while the child is with the other legal guardian*. **Not read.** Its title alone establishes that per-parenting-time tracking legality is a recognised issue.

**[56] Coker Legal (Texas) — Can I Track My Spouse with a GPS or AirTag? What Texas Law Says (2025-06-24) — https://www.cokerlegal.com/blog/2025/june/can-i-track-my-spouse-with-a-gps-or-airtag-what-/ — Accessed 2026-10-05 — Tier: 3 — LOCATED, NOT FETCHED.**
Texas law-firm commentary on consumer trackers and electronic-surveillance/stalking exposure. **Not read.** Relevant because a child tracker incidentally surveils whichever adult is holding the child — the core liability identified in C1.3.

**[57] State of Utah, Prevention & Mission Network (utah.gov/pmn) — CSA/HT: Parent and Guardian handout (09-30-24) — https://www.utah.gov/pmn/files/1177101.pdf — Accessed 2026-10-05 — Tier: 1 — LOCATED, NOT FETCHED.**
A US state-government parent/guardian handout on child sexual abuse and human trafficking that surfaced in the code-word search. Cited to establish that **family code words appear inside official government prevention materials** — i.e. they are institutionally endorsed — while **no evaluation of their efficacy was found anywhere.** Not read.

**[58] Droit d'Enfance — Disparitions de mineurs : des signalements en hausse de 6… (2026-05-25) — https://www.droitdenfance.org/2026-05-25/disparitions-de-mineurs-2/ — Accessed 2026-10-05 — Tier: 2/3 — LOCATED, NOT FETCHED.**
A French child-rights commentary piece reporting rising missing-minor reports. Located alongside the ONPE report [43] as a secondary route to French 2024 figures after the primary PDF 404'd. **Not read; the truncated title does not permit any figure to be reported.**

**[59] Reddit r/Custody — "[GA] GPS Tracking & Stalking (Find my iPhone)" — https://www.reddit.com/r/Custody/comments/vm14f7/ga_gps_tracking_stalking_find_my_iphone/ — Accessed 2026-10-05 — Tier: 3 — LOCATED, NOT FETCHED.**
An anonymous forum thread from a Georgia custody dispute in which location tracking was characterised as stalking. Included **only** to document that the "tracker-as-harassment-tool" allegation occurs in real custody disputes at the level of practitioner and litigant discourse. **It is anecdotal, unfetched, and must not be cited as evidence of any legal outcome.**
