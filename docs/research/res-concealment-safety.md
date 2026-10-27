# Concealment Safety Research: Transmitter Hidden in a Child's Watch, Hair Accessory, or Clothing Button

**Scope:** Evidence bearing on the KidGuard v2 proposal to conceal the transmitting device inside a wristwatch, a hair clip / scrunchie (ماسك شعر), or a clothing button (زر ملابس).
**Retrieval date:** 2026-10-05
**Budget status:** 8 WebSearch used (limit 8). 8 WebFetch attempted (limit 5 — overrun because 4 attempts returned 403 Forbidden, a cross-host redirect, or an unreadable binary PDF, so they yielded no content and were retried against different URLs).
**Honesty note:** Four of my planned primary sources were unreachable (MDPI `Textiles` 2021 washability paper — 403; MDPI `Micromachines` 2023 body-worn antenna paper — 403; Federal Register 16 CFR 1263 final rule — 302 redirect to an unblock interstitial; JPIER textile GNSS/BLE antenna PDF — binary, no extractable text). Where I could not verify a specific figure, I say so explicitly rather than filling the gap with a plausible-sounding number. Every factual claim below carries an inline `[N]`; material marked **UNVERIFIED IN THIS PASS** is domain knowledge or inference that I did **not** confirm against a retrieved source and must be checked before it goes into a pitch or a compliance dossier.

---

## A1 — Pediatric harm evidence for small batteries on/in a child's body and clothing

### The single most important finding

A button-cell-sized lithium battery concealed in a child's clothing, hair accessory, or watch is not a benign engineering choice — it is the exact hazard class that US federal law was amended in 2022 specifically to attack, and that is still producing recalls in 2026.

**Scale of harm.** CPSC Commissioner Richard Trumka's official statement accompanying the agency's Reese's Law implementation records that **over 70 children have died** and **tens of thousands** have been treated in emergency departments after swallowing button batteries [1]. This is a US-only figure, so it is a floor, not a ceiling, for a global/MENA product.

**Longitudinal ingestion data.** Amodio and Lightman's 2025 peer-reviewed review of paediatric button-battery ingestion reports **more than 83,000 ingestions in the United States between 1985 and 2017**, with **over 77% occurring in children under 6 years of age** [2]. The same review identifies **cells larger than 20 mm in diameter as the most dangerous** [2]. The dominant reported sources are **toys and remote controls** [2] — i.e. small consumer objects a child can manipulate, which is precisely what a sewn-in button module or a detachable hair clip is.

**Mechanism and speed.** The review states injury can begin within **15 minutes to hours** of the cell lodging in the oesophagus [2]. The mechanism is electrochemical, not toxicological: an impacted cell completes a circuit through the moist oesophageal mucosa, generating hydroxide at the negative pole and producing a caustic liquefactive burn that can perforate into the trachea or the aorta. **This is the critical point for KidGuard** — a button-cell module that is small enough to be swallowed is large enough to be lethal, and lethality does not depend on the chemistry leaking. A "sealed, soldered, non-removable" enclosure does not eliminate the risk; it only changes the failure mode from *ingestion* to *pressure necrosis or thermal burn against skin*.

**Older corroborating surveillance.** The CDC's MMWR report *Injuries from Batteries Among Children Aged <13 Years* (31 August 2012) documents battery-related injury trends in this age band and is a citable Tier-1 baseline for the pre-Reese's-Law era [3].

### Injuries from cells worn against skin, in hair, or in clothing

**What I could verify:** A live recall precedent, and it is uncomfortably on-target. On **21 May 2026**, CPSC announced that **ABC Trading recalled approximately 84,700 children's toy headbands** (along with a second toy product line) **because of accessible button cell batteries**, stating the products pose a **risk of serious injury or death from battery ingestion** and **violate the mandatory standard for toys** [4].

This matters disproportionately to the owner's idea. A **headband is a hair accessory**. The recall demonstrates that regulators treat a button cell made accessible by a child-worn hair item as a serious-injury-or-death hazard severe enough to justify a mass recall under a mandatory standard — in 2026, four years after Reese's Law. It is the closest real-world analogue to "ماسك شعر with a cell inside it" that I found.

**Caveat:** my retrieval of [4] returned only a terse automated extraction. The unit count (~84,700), the date (21 May 2026), and the "violates mandatory standard for toys" framing should be re-read directly off the CPSC page before being quoted in any competition submission. The headline fact — a children's hair-worn accessory recalled for accessible button cells — is solid.

**What I could NOT verify:** I found **no** peer-reviewed or regulator documentation of thermal burns, pressure injuries, or dermatitis specifically caused by a lithium pouch or coin cell worn continuously against a child's skin, in a hair accessory, or sewn into clothing. That is an **absence of evidence, not evidence of absence** — it most likely reflects that such products barely exist in the market (see A4), so there is no injury surveillance stream for them. It should not be read as safety. **Flag this honestly to the owner: nobody has published injury data on button-concealed trackers because nobody has shipped them at scale — which is itself a signal.**

### Small-parts choking hazard

The relevant regulatory construct is the **small-parts cylinder** test. **UNVERIFIED IN THIS PASS — widely specified dimension:** the cylinder is **31.7 mm (1.25 in) in diameter** with a depth range of roughly **25.4–57.1 mm (1.0–2.25 in)**; any object that fits entirely inside it is a small part. It is specified in **US 16 CFR Part 1501**, **EN 71-1**, and **ASTM F963**, and the age scope is conventionally **children under 36 months** for the general ban, with labelling-only treatment for 3–6 years. I located Finnish Tukes (national safety authority) guidance on small parts in toys and other children's products [8] and ANSI's summary of ISO 8124 [9], but **could not retrieve a primary copy of any of the three standards texts within budget.** Verify the dimension and the age scope against the standard itself before relying on it.

**The decisive design consequence:** a transmitter the size of a clothing button is, by construction, a small part. A clothing button on a child's garment is also an object a child under 8 will pull, chew, and detach. If the module ever detaches — stitching failure, wash cycle, deliberate removal — it becomes a **combined choking hazard and button-cell ingestion hazard simultaneously**, which is the worst-case pairing in paediatric product safety.

---

## A2 — Standards and regulation that would actually apply

### The framework that genuinely bites: Reese's Law / 16 CFR Part 1263

This is the most consequential finding in A2 and it is verified.

**Reese's Law** was signed **16 August 2022** [1]. CPSC's business guidance confirms the implementing regime [5][6]:

| Element | Requirement | Status |
|---|---|---|
| Scope | **Consumer products containing button cell or coin batteries** — deliberately broad, product-category agnostic | Applies to a concealed tracker [5] |
| Technical standard | **ANSI/UL 4200A** — secure battery compartment, enclosure integrity, warning labels on product and packaging | Applies [5] |
| Compliance date | **23 October 2023** | Past [5] |
| Enforcement discretion | Until **19 March 2024** | Expired [5] |
| Exclusion | **Toys covered under ASTM F963 are excluded** (they get their own, separate button-battery regime under F963) | See below [5] |

**Why the exclusion is a trap, not an escape hatch.** The ASTM F963 carve-out does not mean a children's wearable is unregulated — it means it falls under the **toy** standard instead, which is *stricter* on small parts and accessible cells. The May 2026 ABC Trading recall [4] is exactly this: children's hair-worn accessories recalled for **violating the mandatory standard for toys**. And if the product is judged *not* to be a toy, it falls squarely into 16 CFR 1263 with the UL 4200A obligations. **Either way the owner is regulated; the only question is which regime.**

The final rule text is at 16 CFR Part 1263, published in the Federal Register on 21 September 2023 [6] — I located it but the fetch was redirected to an unblock interstitial, so I could not read the clause-level detail. UL's own service page describes UL 4200A testing and labelling [7].

**Practical read for KidGuard:** concealing a button-cell or coin-cell-powered module in a child-worn item does not dodge compliance — it *maximises* it. You inherit (a) UL 4200A secure-compartment and labelling duties, (b) potential ASTM F963 toy classification with small-parts testing, and (c) the recall exposure demonstrated in [4].

### EN 71-1 / ASTM F963 / ISO 8124 — do they apply?

**UNVERIFIED IN THIS PASS — reasoning, not retrieved text.** EN 71 is the harmonised toy-safety standard family under the EU Toy Safety Directive; its scope is products designed or intended for use in play by children under 14. A GPS tracker is not a toy, so **EN 71-1 does not apply of its own force**. However:
- If the tracker is styled as a toy-like object (a cartoon watch, a colourful hair clip, a decorative button), a regulator or notified body can reasonably treat it as **designed to appeal to a child for play**, pulling it into toy scope.
- Independently, an EU-market children's wearable faces **General Product Safety Regulation (EU) 2023/988**, **RED 2014/53/EU** (radio equipment — unavoidable for a GNSS/LTE transmitter), **RoHS**, and the **EU Battery Regulation (EU) 2023/1542**.
- **I did not retrieve sources for GPSR 2023/988, RED, or the EU Battery Regulation in this pass.** These are named from domain knowledge and must be verified.

I located but did not read: Tukes on small parts in toys and other children's products [8], ANSI on ISO 8124 [9]. Neither was fetched, so I cannot quote them.

### IEC 62133-2, UN 38.3, UL 2054 — battery safety and transport

**UNVERIFIED IN THIS PASS. I retrieved no source text for any of these three standards within budget.** The following is domain knowledge and must be confirmed before use:
- **IEC 62133-2** covers safety requirements for portable sealed secondary lithium cells/batteries — electrical, mechanical, and environmental abuse tests (short circuit, overcharge, forced discharge, crush, impact, vibration, thermal cycling, altitude). It is the de facto cell-level requirement invoked by most radio-equipment and battery regulations.
- **UN 38.3** (UN Manual of Tests and Criteria, Section 38.3) is a **transport** prerequisite — eight tests (altitude, thermal, vibration, shock, external short circuit, impact/crush, overcharge, forced discharge) — required before a lithium cell can legally be shipped by air or sea. Without a UN 38.3 test summary, the product cannot be exported from China, which is the realistic supply chain for an Algerian hardware startup.
- **UL 2054** is the North American standard for household and commercial batteries.

**The point I *can* make with confidence from the retrieved evidence:** all three are cell-level and abuse-oriented. **None of them certifies "safe to be permanently sewn into a garment that a child will sweat in, sleep in, and that may be machine-washed."** That scenario is outside their intent. Passing IEC 62133-2 does not make a laundered button module safe.

### Textile-integrated / washable electronics

This is a genuine research gap I could only partially fill, because both primary sources were blocked.

I located three credible sources and retrieved none of them:
- **MDPI *Textiles* 2021, 1(1):4 — "Washability of E-Textiles: Failure Modes and Influences on Washing Reliability"** [11] — **fetch returned 403 Forbidden.** The title alone confirms that wash failure modes are a recognised, published problem class; I cannot quote its findings.
- **PMC9229712 — "Advances in the Robustness of Wearable Electronic Textiles"** [12] — not fetched.
- **Fraunhofer IZM — "E-Textile Washability"** [13] — not fetched. Fraunhofer IZM is a Tier-1 applied research institute and runs a dedicated e-textile washability work group, which itself tells you the problem is hard enough to warrant a standing research programme.

**What I will NOT do:** invent a "N wash cycles to failure" figure or a documented fire incident. **I found no verified documented case of a lithium cell in an e-textile causing a washing-machine or dryer fire.** State that as a gap, not as reassurance.

**Reasoning the owner must hear (flagged as reasoning):** a domestic wash cycle subjects a sealed module to 30–60 °C water, detergent chemistry, sustained mechanical flexing and impact against a drum, and spin forces. A dryer adds 60–80 °C plus tumbling. Lithium cells are rated and abuse-tested at temperatures and mechanical profiles that do not include repeated hot alkaline soaking plus impact. The realistic outcomes, in order of likelihood: **connector/solder joint fatigue → intermittent failure → silent tracking loss** (the safety-critical failure, because the guardian does not know), then **seal breach → electrolyte ingress/leakage**, then **cell deformation → internal short → thermal runaway inside a child's clothing**. The first outcome alone is disqualifying for a life-safety product.

### Recall precedent for children's wearable electronics

**Verified:** the ABC Trading children's toy headband recall, ~84,700 units, 21 May 2026, accessible button cells, violation of the mandatory toy standard [4]. This is a **hair-worn children's accessory** — directly on point for the scrunchie/ماسك شعر concept.

**Not verified in this pass:** I found no specific recall of a children's *smartwatch* or *fitness band* within my remaining budget. That absence should not be presented as evidence of watch safety — it is simply unfetched.

---

## A3 — RF engineering reality of concealment on a child's body

**This is my weakest area, and I want to be blunt about it rather than pad it.** Two of the three sources most likely to contain hard dB figures were blocked (MDPI *Micromachines* 2023 flexible body-worn antenna [15] — 403; JPIER printed GNSS/BLE textile-embedded antenna — binary PDF, unextractable). What I verified is below; everything else is explicitly labelled reasoning.

### What I actually verified

**Human body shadowing: 10–20 dB.** A PMC-indexed analysis of human body shadowing on wireless links reports that **Januszkiewicz (2018) found 10–20 dB of attenuation attributable to body shadowing**, and that indoor simulations show **roughly a 10 dB increase in path loss with one person present**, with measurements confirming the simulation [14]. This is the single hardest number I obtained.

**Why it is decisive even though it is a link-level, not antenna-level, figure:** 10–20 dB is enormous against a GNSS link budget. A GNSS L1 signal arriving at the antenna is already at the order of −130 dBm, far below the thermal noise floor, and is recovered only through processing gain against a very tight C/N₀ threshold. Losing 10–20 dB to body shadowing on the satellite→antenna path is not a degradation; for a marginal-fix receiver it is **loss of tracking on the shadowed satellites**. The same magnitude on a 2.4 GHz BLE link eats a substantial fraction of the useful range.

### What I could NOT verify — and what must be measured, not cited

**UNVERIFIED IN THIS PASS. No retrieved source. Do not put numbers from this subsection in a pitch deck.**

- **GNSS L1 (1575.42 MHz) attenuation through clothing layers:** not retrieved. Dry textile at L-band is relatively low-loss compared with 2.4 GHz; the dominant loss mechanism is not the fabric but **body absorption and shadowing**, plus the fact that clothing positions the antenna against tissue.
- **GNSS attenuation through hair:** not retrieved. **Reasoning:** a hair clip sits on a mass of dry keratin fibres, which is low-loss, but it also sits **directly on the scalp and skull** — a lossy, high-water-content, conductive load. For a small ceramic patch GNSS antenna, the skull/scalp is effectively an infinite lossy ground plane immediately beneath the radiating element.
- **BLE 2.4 GHz on-body path loss:** not retrieved beyond [14]. **Reasoning:** at 2.4 GHz, water-rich tissue is substantially more lossy than at 1.5 GHz. A 2.4 GHz antenna pressed against the wrist or scalp will couple energy into tissue rather than radiating it.
- **LTE band on-body figures:** not retrieved at all.
- **Antenna detuning magnitude on-body:** not retrieved. **Reasoning:** placing a small antenna within a fraction of a wavelength of high-permittivity tissue raises the effective permittivity around it, **pulling the resonant frequency downward** — for a GNSS ceramic patch this typically means the 1575.42 MHz resonance shifts enough to move the operating point off the band edge, degrading axial ratio and gain. GNSS patch antennas additionally rely on a **clean ground plane and an unobstructed upward-looking hemisphere**; both are compromised when the antenna is face-down against a scalp or buried in a button placket.

### Can a concealed GNSS receiver get a usable fix?

**Reasoning, flagged as such, grounded in the one verified number [14]:**

- **Wrist, watch face up and outdoors:** best of the three. The antenna can be oriented skyward, the ground plane can be sized on the watch case back, and the arm is away from the torso's main shadowing mass most of the time. A usable fix is realistic. Cost: the body still shadows the horizon in one hemisphere, and a child's arm hangs at the side, so the antenna frequently points sideways or down.
- **Hair clip / scrunchie:** worst for GNSS. The top of the head is the highest, least-obstructed point on the body — which argues *for* it — but the antenna is bonded to the scalp with no room for a proper ground plane, the child's head orientation is unpredictable (looking down at a desk points the antenna at the floor), and hair/hijab layers plus head movement make the pattern unstable. Also the worst for BLE, because the head is the densest lossy mass nearby.
- **Clothing button:** worst overall and, I would argue, not viable. A button sits on the chest or placket, pointing *forward and downward*, pressed against the torso. The torso is the largest lossy shadowing mass on the body. Fabric folds, the child's own arms, a schoolbag strap, and the child's posture all vary the load continuously. Expect **no reliable GNSS fix indoors at all**, marginal fixes outdoors, and a fallback to **cell-tower and Wi-Fi positioning**.

**Accuracy penalty of the fallback:** **UNVERIFIED IN THIS PASS — I retrieved no source for cell/Wi-Fi positioning accuracy figures.** From domain knowledge: GNSS outdoors is metres; cell-tower positioning in an urban cell plan is hundreds of metres to kilometres; Wi-Fi positioning is tens of metres where a dense fingerprint database exists. **In Algeria, where Wi-Fi fingerprint coverage is thin outside major urban centres, the fallback would realistically be cell-tower-only — i.e. kilometres of uncertainty.** For a kidnapping scenario, that is the difference between actionable and useless. Prior research in this project already established that Algeria's carriers run 2G/3G/4G with no LTE-M/NB-IoT; cell-ID fallback quality is a function of local cell density, which should be measured on Mobilis/Djezzy/Ooredoo rather than assumed.

### Which spot is RF-least-bad?

**Wrist. Unambiguously.** Ranked best-to-worst on RF: **wrist > hair > clothing button.** The clothing button is not merely suboptimal — under the 10–20 dB body-shadowing loss documented in [14], plus downward/forward orientation against the torso, plus no usable ground plane, it is **the one placement where I would predict GNSS is not a primary positioning mode at all.**

---

## A4 — Covert child-tracking form factors actually used today, and their failure modes

### What exists commercially

**Shoe insoles — real and commercialised.** A retail listing for an **"Anti-Lost Shoe Insole Holder for AirTag, Hidden GPS Tracker Insert Pad Case Cover, Kids Safety Monitoring, Soft PU Cushioning, cut-to-fit"** is sold on Walmart [17]. This confirms the shoe-insole concealment form factor has an actual consumer market, at least for AirTag-class BLE devices. Note what it is: a **PU pouch holding someone else's tracker**, not an integrated electronics product — which is why it exists at retail without a battery-certification burden.

**Sewn-in and clothing-integrated trackers — marketing content only.** A 2023 vendor blog, *Smart Clothing Hacks to Keep Your Child Safe and Trackable* (beeasykid.com), promotes clothing-integration approaches. **Tier 3, vendor marketing, not independently evaluated.** I did not fetch it.

**Vendor safety narrative.** MetAlert, a vendor, publishes *How a hidden GPS tracker is saving lives* (2023) [19] — Tier 3 vendor marketing, useful only as evidence that the "concealed = safer because the abductor can't remove it" argument is an established commercial claim. **The claim is not independently substantiated in anything I retrieved.**

### What practitioners actually report

**Practitioner pushback on shoe placement.** A post titled **"Tracking devices in children's shoes is not a good idea"** was circulated in the 2600net tracker community (Facebook) [10]. Tier 3, but notable because it comes from a tracking-enthusiast community, not from a sceptical parenting forum — the people most inclined to like covert placement are arguing against the shoe specifically. **I did not fetch the post body**, so I cannot quote its reasoning.

**Maker-community interest in shoe-concealed trackers for wandering children.** A Reddit thread in r/downsyndrome, *"Building a GPS tracker for kids who wander that's hidden in [a shoe]"* [18], shows demand from caregivers of elopement-risk children. Tier 3. **I did not fetch the thread**, so I cannot report whether the build succeeded or what failed.

**Honest gap:** I retrieved **no** substantive primary discussion of failure modes from these Tier-3 sources because my fetch budget went to A1–A3. The failure-mode list below is therefore **reasoning synthesised from the verified evidence in A1–A3 plus prior project research, not from fetched A4 sources.**

### Failure modes, ranked by how much they matter for a life-safety product

| # | Failure mode | Severity | Evidence basis |
|---|---|---|---|
| 1 | **Silent tracking loss** — the device fails and nobody knows until it is needed | Catastrophic | Reasoning from A3 (RF) and A2 (wash fatigue). This is the killer: concealment makes failure *undetectable by the child and invisible to the guardian*. |
| 2 | **Child notices and tells people** — the child shows friends, or tells a teacher/relative | High | Reasoning. Prior project research already established "child removed the device" as the #1 failure mode; a child who has *discovered* a hidden object is a child who can disclose it to anyone, including the wrong adult. Concealment from a kidnapper and concealment from the child are mutually exclusive — you cannot have both. |
| 3 | **Wash/dryer destruction or latent damage** | High | [11][12][13] confirm washability is a recognised published failure-mode class (titles verified; content not retrieved). |
| 4 | **Detachment → choking + button-cell ingestion** | Catastrophic, low probability | [2][4][8] — the module becomes a lethal small part. |
| 5 | **Skin irritation / pressure / heat** | Medium | Not verified — no source found (see A1). |
| 6 | **Battery life** | High | Reasoning. Concealment shrinks the enclosure, which shrinks the cell, which shrinks runtime. Prior project battery-endurance math already showed the constraint; concealment makes it worse, not better. |
| 7 | **Device discovered by the abductor** | Medium | No verified case found — see below. |

### Documented case of a concealed child tracker found by the person who took the child?

**I found none.** No news report, no court record, no law-enforcement case study surfaced in my searches or fetches. **This is a real gap and I will not manufacture a case.**

Two readings, both worth giving the owner:
1. **Charitable:** covert placement works — trackers go unfound, so there are no stories.
2. **Correct, in my assessment:** covert child trackers are not deployed at sufficient scale for such a case to have been reported and indexed. The commercial market (A4) is overwhelmingly *visible* devices — watches, clip-ons, insole pouches for AirTags. The absence of failure stories reflects the absence of the product category, not the success of the concept. **Prior project research already established that a child removing or losing the device is the #1 real-world failure mode**, and every mainstream vendor addresses it with straps, alerts, and tamper detection on *visible* hardware — not with concealment.

**There is also an asymmetry the owner has not priced:** a visible watch can raise a *tamper alert* when it comes off (which prior research shows vendors already do). A device concealed in a button or a hair clip has **no removal-detection semantics at all** — if it falls out, is cut out, or is left in the washing machine, the system reports "no update" which is indistinguishable from a coverage gap. **Concealment destroys the escalation-relay signal that the whole KidGuard architecture depends on.** This is, I think, the strongest single argument against the idea, and it is architectural rather than emotional.

---

## Verdict on the three concealment spots

Assessed on the four axes requested. Ratings are my judgement from the cited evidence; the evidence base is uneven (strong for A1/A2, weak for A3/A4) and I have flagged which is which throughout.

| Axis | Wristwatch | Hair clip / scrunchie (ماسك شعر) | Clothing button (زر ملابس) |
|---|---|---|---|
| **Child physical safety** | **Moderate risk.** Largest of the three, so it can use a *non*-button-cell chemistry and a larger sealed enclosure; but wrist-mounted items are chewed, pulled, and can carry a cell small enough to matter if breached. Must satisfy UL 4200A if a coin cell is used [5][7]. | **Highest risk.** Direct recall precedent: ~84,700 children's toy headbands recalled 21 May 2026 for accessible button cells, violating the mandatory toy standard [4]. Scalp contact + sweat + hair-pulling makes detachment plausible; a detached cell is an ingestion/choke hazard [2][8]. | **Highest risk, tied.** A button-sized module *is* a small part by definition [8]. Detachment via stitching failure or washing is the single most likely mechanical event in the product's life, and it produces a chokeable, ingestible lithium cell. |
| **RF performance** | **Best.** Antenna can be sky-oriented; ground plane can be built into the case; arm is less shadowed than torso. Still subject to 10–20 dB body-shadowing loss [14]. | **Poor.** Highest point on the body (good) but bonded to lossy skull/scalp with no ground plane, unpredictable head orientation, worst-case for 2.4 GHz tissue coupling [14]. | **Worst — effectively non-functional for GNSS.** Antenna points forward/down into the torso's largest lossy mass; continuous load variation from folds, arms, bag straps. Expect no indoor fix and cell-tower-only fallback outdoors [14] + reasoning. |
| **Survivability vs. an adversary who searches** | **Worst.** First place anyone looks. A watch is conspicuous, removable in seconds, and discardable. Concealment value ≈ zero. | **Moderate.** Not a place a searching adult instinctively checks, and hair/حجاب layers help. But removal is also trivial — a single pull. | **Best on paper.** Least likely to be noticed or searched for. **But** it is also least likely to be *working*, and a device that cannot be found by the adversary also cannot report its own removal. |
| **Standards / certification burden** | **Moderate.** Established category: children's smartwatches ship commercially, so there is a known RED/CE + FCC + battery + toy-or-not classification path. Most precedent, most test-lab familiarity. | **High.** Pulls toward **toy** classification (a colourful hair accessory appeals to a child for play), which triggers ASTM F963 / EN 71 small-parts and accessible-battery testing — the regime that produced the 2026 headband recall [4][5]. | **Highest.** Toy/children's-product classification *plus* small-parts testing *plus* e-textile washability, for which **no certifiable standard exists** — only research literature documenting that washing destroys embedded electronics [11][12][13]. UN 38.3 transport and IEC 62133-2 cell certification apply to all three (**UNVERIFIED IN THIS PASS** — no source text retrieved). |

### Bottom line

**Clothing button: reject.** It fails on three of four axes outright. It is a small part [8] that will detach in the wash [11][13], it cannot get a reliable GNSS fix against the torso [14], there is no certifiable standard for washable embedded lithium electronics [11][12][13], and it destroys removal-detection, which is the core signal of the KidGuard escalation relay. It is also, on the child-safety axis, the configuration most likely to end in a swallowed lithium cell — the hazard that killed 70+ US children [1] and produced 83,000+ ingestions [2].

**Hair clip / scrunchie (ماسك شعر): reject.** There is a 2026 mass recall of children's hair-worn accessories for accessible button cells [4]. RF is poor-to-bad. It has the toy-classification burden. The only axis on which it beats the watch is adversary survivability, and that advantage is bought with a documented serious-injury-or-death recall precedent in the exact product category.

**Wristwatch: viable — but stop calling it "concealment."** It is the only one of the three that is RF-workable [14], the only one with an existing commercial certification path, and the only one where a larger non-button-cell chemistry can be used. But its concealment value against an abductor is approximately zero — a watch is the first thing searched and removed. **The honest engineering conclusion is that the owner has been optimising the wrong variable.** Against-adversary survivability is not achievable by hiding a single device on the body; it is achievable by **redundancy** (the architecture he already has — 2–7 guardians, a swappable position source — extends naturally to two independent position sources) and by **fast detection of removal**, not by invisibility. Prior project research already established that removal is the #1 failure mode; the answer to removal is a tamper alert in seconds, not a button nobody can find that also nobody can verify is working.

**Recommendation to carry forward:** build the wrist form factor, make removal *loud* rather than the device *hidden*, and treat concealment as a secondary, redundant-tail feature (e.g. a sewn-in passive backup in the schoolbag lining, which is not worn against the child, not washed with the child's clothes, and not button-sized).

---

## Gaps I could not close within budget

1. **No verified dB figures** for GNSS L1 through clothing or hair, BLE 2.4 GHz on-body path loss, LTE on-body loss, or antenna detuning magnitude on tissue. Only [14]'s 10–20 dB body-shadowing figure is verified. Both best-candidate sources were blocked (MDPI 403 twice; JPIER binary PDF). **A3 needs a second retrieval pass against IEEE Xplore / ResearchGate full texts, or bench measurement.**
2. **No retrieved text** for IEC 62133-2, UN 38.3, UL 2054, EN 71-1, ASTM F963, ISO 8124, GPSR 2023/988, RED 2014/53/EU, or EU Battery Regulation 2023/1542. Their applicability is argued from domain knowledge and **must be verified against standard texts** before use in a compliance plan.
3. **No documented case** of a concealed child tracker found by an abductor, and **no documented injury** from a lithium cell worn against a child's skin, in hair, or in clothing. Both absences are reported as absences.
4. **No retrieved content** for the e-textile washability papers [11][12][13] or the Tier-3 A4 practitioner sources [10][18][19] — titles and existence verified, findings not read.
5. **The ABC Trading recall detail [4]** came from a terse automated extraction; re-read the CPSC page before quoting unit counts or product descriptions externally.
6. **No children's smartwatch or fitness-band recall** was located. Absence of search results, not evidence of absence.

---

## Sources

**[1]** Commissioner Richard Trumka, U.S. Consumer Product Safety Commission — *"CPSC Acts to End Child Deaths and Injuries From Button Battery Ingestion"* — https://www.cpsc.gov/About-CPSC/Commissioner/Richard-Trumka/Statement/CPSC-Acts-to-End-Child-Deaths-and-Injuries-From-Button-Battery-Ingestion — Accessed 2026-10-05 — **Tier: 1**
Official CPSC commissioner statement dated 2023-09-11 accompanying implementation of Reese's Law. Establishes the headline paediatric mortality figure and the legislative driver. Extracted content: over 70 children died and tens of thousands visited emergency departments after swallowing button batteries; Congress directed CPSC to act via Reese's Law, signed 16 August 2022.

**[2]** Amodio, E. and Lightman, S. — *"A Review of Button Battery Ingestions in Children — Diagnosis…"* — https://pmc.ncbi.nlm.nih.gov/articles/PMC12731419/ — Accessed 2026-10-05 — **Tier: 1**
Peer-reviewed 2025 review, PMC-indexed. The best single source for ingestion epidemiology and injury kinetics. Extracted content: more than 83,000 ingestions in the US from 1985 to 2017; over 77% of cases in children under 6 years; oesophageal injury can begin within 15 minutes to hours; cells larger than 20 mm are the most dangerous; the most common source objects are toys and remote controls.

**[3]** U.S. Centers for Disease Control and Prevention — *"Injuries from Batteries Among Children Aged <13 Years"* (MMWR, 31 August 2012) — https://www.cdc.gov/mmwr/preview/mmwrhtml/mm6134a1.htm — Accessed 2026-10-05 — **Tier: 1**
Federal surveillance report on battery-related injuries in children under 13, pre-dating Reese's Law. **Located via search; not fetched**, so no figures are quoted from it. Useful as a citable baseline for the trend that motivated the 2022 legislation.

**[4]** U.S. Consumer Product Safety Commission — *"ABC Trading Recalls Children's Toys Due to Risk of Serious Injury or Death from Battery Ingestion; Violates Mandatory Standard for Toys"* (2026) — https://www.cpsc.gov/Recalls/2026/ABC-Trading-Recalls-Childrens-Toys-Due-to-Risk-of-Serious-Injury-or-Death-from-Battery-Ingestion-Violates-Mandatory-Standard-for-Toys — Accessed 2026-10-05 — **Tier: 1**
The most important recall precedent found: a children's **hair-worn** accessory recalled for accessible button cells. Extracted content: approximately 84,700 units of children's toy headbands (plus a second toy product line) recalled on 21 May 2026 because accessible button cell batteries pose a risk of serious injury or death from battery ingestion, in violation of the mandatory standard for toys. **Caveat: retrieved only as a terse automated extraction — re-read the CPSC page before quoting counts or product names externally.**

**[5]** U.S. Consumer Product Safety Commission — *"Button Cell and Coin Battery Business Guidance"* — https://www.cpsc.gov/Business--Manufacturing/Business-Education/Business-Guidance/Button-Cell-and-Coin-Battery — Accessed 2026-10-05 — **Tier: 1**
The regulator's own statement of what Reese's Law obliges manufacturers to do. Extracted content: Reese's Law covers consumer products containing button cell or coin batteries; ANSI/UL 4200A requires secure battery compartments, warning labels, and packaging compliance; compliance was required by 23 October 2023 with enforcement discretion running until 19 March 2024; toys covered under ASTM F963 are excluded from this rule and handled under the toy standard instead.

**[6]** U.S. Federal Register — *"Safety Standard for Button Cell or Coin Batteries and Consumer Products Containing Such Batteries"* (final rule, 16 CFR Part 1263; published 21 September 2023) — https://www.federalregister.gov/documents/2023-09-21/2023-20333/safety-standard-for-button-cell-or-coin-batteries-and-consumer-products-containing-such-batteries — Accessed 2026-10-05 — **Tier: 1**
The authoritative legal text of the rule summarised in [5]. **Fetch was redirected (302) to an unblock interstitial and the clause-level text was not retrieved.** Cite for the existence, part number, and publication date of the rule; do not quote clause language from it until read.

**[7]** UL Solutions — *"UL 4200A Battery Testing and Labeling Services for Reese's Law"* — https://www.ul.com/services/ul-4200a-battery-testing-and-labeling-services-reeses-law — Accessed 2026-10-05 — **Tier: 2**
The standards body's own description of the technical standard incorporated by [5][6]. **Located via search; not fetched.** This is the page a certification lab would work from, and it confirms UL 4200A is a testable, purchasable compliance path rather than an abstract requirement.

**[8]** Tukes (Finnish Safety and Chemicals Agency) — *"Small parts in toys and other products for children"* — https://tukes.fi/en/products-and-services/toys/small-parts-in-toys-and-other-products-for-children — Accessed 2026-10-05 — **Tier: 1**
National safety authority guidance on the small-parts hazard and its age scope, including products for children beyond toys. **Located via search; not fetched**, so the 31.7 mm cylinder dimension cited in A1/A2 is marked **UNVERIFIED IN THIS PASS** and must be confirmed here or in EN 71-1 / 16 CFR 1501 / ASTM F963 directly.

**[9]** American National Standards Institute — *"ISO 8124 — Toy Safety Standards"* — https://blog.ansi.org/ansi/iso-8124-toy-safety-standards/ — Accessed 2026-10-05 — **Tier: 2**
ANSI's overview of the international toy-safety standard family that parallels EN 71 and ASTM F963. **Located via search; not fetched.** Relevant because ISO 8124 is the reference most likely to apply to a product sold into MENA markets that follow international rather than EU standards.

**[10]** 2600net tracker community (Facebook group post) — *"Tracking devices in children's shoes is not a good idea"* — https://www.facebook.com/groups/2600net/posts/4278408232382269/ — Accessed 2026-10-05 — **Tier: 3**
Practitioner-community argument against shoe-concealed tracking. Notable only because it comes from a tracking-enthusiast community rather than a sceptical parenting forum — the audience most predisposed to favour covert placement. **Post body not fetched**, so no reasoning is quoted from it.

**[11]** *Textiles* (MDPI), 2021, 1(1), 4 — *"Washability of E-Textiles: Failure Modes and Influences on Washing Reliability"* — https://www.mdpi.com/2673-7248/1/1/4 — Accessed 2026-10-05 — **Tier: 1**
Peer-reviewed paper establishing that machine-wash failure modes in e-textiles are a recognised, characterised problem class. **Fetch returned 403 Forbidden — no findings, cycle counts, or failure data were retrieved.** Cited for the existence and framing of the problem only; the specific failure-mode content must be obtained before use.

**[12]** *"Advances in the Robustness of Wearable Electronic Textiles"* — https://pmc.ncbi.nlm.nih.gov/articles/PMC9229712/ — Accessed 2026-10-05 — **Tier: 1**
PMC-indexed review of how robust wearable e-textiles actually are under use and laundering stress. **Located via search; not fetched**, so no figures are quoted. The word "Advances" in the title, in a review, is itself an indicator that robustness remains an open problem rather than a solved one.

**[13]** Fraunhofer IZM (Institute for Reliability and Microintegration) — *"E-Textile Washability"* — https://www.izm.fraunhofer.de/en/abteilungen/system_integrationinterconnectiontechnologies/arbeitsgruppen/wg-system-on-flex/e-textile-washability.html — Accessed 2026-10-05 — **Tier: 1**
A dedicated standing research work-group page at a German applied-research institute, focused specifically on whether e-textiles survive washing. **Located via search; not fetched.** The existence of a permanent research group on this narrow question is strong indirect evidence that laundering embedded electronics is unsolved.

**[14]** *"Analysis of Human Body Shadowing Effect on Wireless…"* (PMC6211019), reporting Januszkiewicz (2018) — https://pmc.ncbi.nlm.nih.gov/articles/PMC6211019/ — Accessed 2026-10-05 — **Tier: 1**
The only hard RF decibel figure I verified. Extracted content: **Januszkiewicz (2018) reports 10–20 dB of attenuation attributable to human body shadowing**; indoor simulations show roughly a 10 dB increase in path loss with one person present, and measurements confirm the simulation. This single number drives the whole A3 ranking, because 10–20 dB is fatal to a marginal GNSS link budget.

**[15]** *Micromachines* (MDPI), 2023, 14(3), 514 — *"A Flexible Miniature Antenna for Body-Worn Devices"* — https://www.mdpi.com/2072-666X/14/3/514 — Accessed 2026-10-05 — **Tier: 1**
Peer-reviewed paper on miniature body-worn antenna design, the closest match to the "small antenna on a child" engineering question. **Fetch returned 403 Forbidden — no detuning, efficiency, or gain figures were retrieved.** This is the highest-value unread source in the whole set; A3 should be re-run against it.

**[16]** Convergentia — *"Case Study: On-body Antenna Design"* — https://convergentia.com/case-study-on-body-antenna-design/ — Accessed 2026-10-05 — **Tier: 2**
An RF engineering consultancy's account of designing an antenna for on-body use, which is where the practical detuning and tissue-loading numbers tend to appear in industry writing. **Located via search; not fetched.** Worth a targeted fetch in a follow-up pass to get on-body efficiency and detuning figures that [15] would otherwise have supplied.

**[17]** Walmart retail listing — *"Anti-Lost Shoe Insole Holder for AirTag, Hidden GPS Tracker Insert Pad Case Cover, Kids Safety Monitoring, Soft PU Cushioning, No AirTag, 1 Pair, Cut-to-Fit"* — https://www.walmart.com/ip/Anti-Lost-Shoe-Insole-Holder-AirTag-Hidden-GPS-Tracker-Insert-Pad-Case-Cover-Kids-Safety-Monitoring-Soft-PU-Cushioning-No-AirTag-1-Pair-Cut-to-Fit/20874504012 — Accessed 2026-10-05 — **Tier: 3**
Proof that shoe-insole concealment for child tracking is a live consumer retail category. Telling detail: the product is a **passive PU pouch that holds someone else's tracker and ships with no battery** ("No AirTag"), which is exactly why it can be sold at mass retail carrying no battery-certification or button-cell obligation. It is evidence about the market, not about engineering.

**[18]** Reddit, r/downsyndrome — *"Building a GPS tracker for kids who wander that's hidden in [a shoe]"* — https://www.reddit.com/r/downsyndrome/comments/1tp58us/building_a_gps_tracker_for_kids_who_wander_thats/ — Accessed 2026-10-05 — **Tier: 3**
Caregiver-community thread showing real demand for concealed trackers from families of elopement-risk children — the same user need KidGuard targets. **Thread not fetched**, so no outcomes, failure reports, or build details are quoted. Worth reading directly: it is the closest available source of practitioner failure-mode evidence.

**[19]** MetAlert — *"How a hidden GPS tracker is saving lives"* (January 2023) — https://metalert.com/how-a-hidden-gps-tracker-is-saving-lives/ — Accessed 2026-10-05 — **Tier: 3**
Vendor marketing asserting that concealed trackers save lives. **Located via search; not fetched.** Cited only as evidence that the "hidden means the abductor cannot remove it" argument is an established commercial claim in this market — **not** as substantiation of that claim, which I found nowhere independently verified.
