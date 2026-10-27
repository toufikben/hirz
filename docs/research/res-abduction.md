# Research: Child-abduction signal set — empirical basis for the alert-classification spec

Method: ~12 web searches/fetches against NCMEC, OJJDP/DOJ, state AG research, peer-reviewed literature (PMC), and industry telemetry sources. Evidence grades: **[V]** = read in full/partial via fetch; **[S]** = headline figure from search result only; **[U]** = unverified / absent from literature.

---

## Q1. Empirical modus operandi of non-family child abduction

### Base rates (the "1 in X" that matters)
- Non-family (stereotypical) abductions **"make up only 1% of the missing children cases reported to NCMEC"** — NCMEC, "Non-family Abductions & Attempts" [V-partial](https://www.missingkids.org/theissues/nonfamily).
- US total reported missing children ≈ hundreds of thousands/yr vs **fewer than 350** genuine nonfamily abductions by persons unknown [S](CRS RL31655, 2005; OJJDP *National Estimates of Missing Children* 2002, [PDF](https://www.ojp.gov/pdffiles1/ojjdp/196465.pdf)). The canonical characterization study is Finkelhor et al., *Nonfamily Abducted Children: National Estimates and Characterizations* (CCRC/NCMEC 2002, ~115 classic stereotypical abductions/yr); the direct PDF was 403-blocked, so the exact 115/attempted-3,500 splits are **[U]** pending a second copy — but the order of magnitude (<1%) is confirmed by the CRS/OJJDP/NCMEC triangulation above.
- Implication for the product: **prior probability of a true abduction is roughly 10^-5 per child-day.** Any alerting design whose FP rate exceeds ~10^-4/child-day will drown the guardian. This is why false-alarm engineering is the product.

### Where and when
- Location: attempted abductions "most often occur **on the street while children are playing, walking, or riding bikes**" — NCMEC [V-partial]. i.e., the public street corridor, not "isolated places" — this is exactly the geofence-able commute zone.
- Time: peak risk **7–9 a.m., 3–4 p.m. (school commute windows) and 6–7 p.m.** — NCMEC [V-partial]. The product's armed-mode schedule (school fence by day + route corridor + evening fence) maps directly onto the empirical risk windows.

### Lure mechanics
- NCMEC public data (their own social/marketing summary of their research): of approaches, ~23% "ask questions", ~22% "**offer a ride**" [S](https://www.facebook.com/ncmec/posts/1114017397426716/) — the "offer ride / walk away with a familiar-looking adult" pattern means the **onset is often a voluntary-looking walk with an adult, not a violent grab**: velocity alone will not catch it; route-deviation + fence-exit + offline/jam fusion will.
- Finkelhor 2002 detail on forced-vehicle percentages: **[U]** this session (PDF blocked).

### Time-to-harm / time-to-report (refines the numbers you already have)
- Washington State AG Child Abduction Murder Research (HITS follow-up) [V](https://www.atg.wa.gov/child-abduction-murder-research): confirms 76% of abducted-then-murdered children died within **3 h**, 88.5% within **24 h** — and adds the number this product cares about most: **in nearly 60% of cases, more than 2 hours passed between the realization the child was missing and notification of police.** The guardian-realization→polise gap, not the abduction→realization gap, is the exploitable interval; an automated P0 alert collapses it to minutes.
- Corroborating operational docs: OJJDP *Case Management for Missing Children Homicide Investigation* [S](https://www.ojp.gov/sites/ojp/files/media/document/201316.pdf); Canadian *Abducted then Murdered Children* study [S](https://protectchildren.ca/en/resources-research/abducted-then-murdered-children-report/).

### What operationally saves children (and its clocks)
- **No waiting period**: missing-child entry must be immediate (US federal policy; missingkids.org/AMBER pages) [V-partial]. AMBER criteria (OJJDP guidelines [S](https://amberalert.ojp.gov/about/guidelines-for-issuing-alerts)) require *confirmed abduction + imminent danger + enough descriptive info* — a product alert gives the guardian the descriptive package (last location, time, heading, speed) to satisfy those criteria at first contact with police.
- **CCTV preservation**: no single national retention standard found this session; commercial/transport DVR retention commonly 24–90 days, but *request speed* matters because small shops overwrite in days — treat "ask for CCTV within the first day" as counsel, sourced **[U]**.
- **Roadblocks / hoursof alert broadcast**: AMBER activation itself is the roadblock mechanism; studies of alert effectiveness exist but were not fetched **[U]**.
- **Cell-site record (CDR) retrieval**: requires legal process (subpoena/emergency disclosure); hours-to-days, not minutes — one more reason the child *device's own* live telemetry beats carrier data. **[U]** for exact timelines.
- UK context: HM Inspectorate *Missing presumed...?* (police response to missing children) documents first-hours failings [S](http://library.college.police.uk/docs/hopolicers/fprs114.pdf).

---

## Q2. Which abduction-relevant variables are detectable from the locked signal set, and their FP profile

### Geofence exit (school / route corridor) — the workhorse, but the engineering literature says naive fences fail both ways
- *Geofencing in location-based behavioral research* (Na et al. 2023, PMC11362315) [V](https://pmc.ncbi.nlm.nih.gov/articles/PMC11362315/) — best published quantification of real smartphone geofence events:
  - Controlled detection 82.5% overall; **drops to ~69% when the person pauses inside the zone**.
  - Field: arrival hit-rate 70%, **departure hit-rate only 18%** (missed exits are the dominant failure).
  - Spatial lag: arrivals ~87 m late past the boundary; **exits ~234 m after crossing; exit notification latency averaged 178 s**.
  - 10 m radii unreliable; platform guidance 100–150 m; connectivity loss silently suppresses notifications.
  - Spec consequences: (1) fences ≥50–100 m; (2) never treat *absence of an exit event* as safety (18% departure detection); (3) confirm exit with 2–3 consecutive Traccar fixes + dwell timer; (4) drift-suppression → FP floor exists but is manageable (vendor refs: Radar geofence accuracy [S](https://radar.com/blog/how-accurate-is-geofencing), GPS-drift suppression [S](https://www.whitelabeltracking.com/what-is-gps-drift-and-how-tracking-service-providers-can-suppress-it/)).
- No study has measured geofence-exit FP *as an abduction indicator*; the PMC numbers bound it. Estimated FP with buffering+debounce: **0.2–1 spurious armed-fence exits per 1000 child-days** (from published drift/lag figures × typical urban accuracy; reasoning stated in the table below).

### "Device moved while GPS froze" / signal collapse (jam or powered-off in a bag)
- **No published quantification exists** for this as an abduction signal in children [U-by-absence]. Known confounders that dominate: battery death, tracker deep-sleep, tunnels/dead zones — these are *far* more common than jammers (jamming of cellular/GPS is rare and illegal in most jurisdictions; FCC enforcement material). Verdict: usable only as a **fused corroborator** (offline while mid-route + last fix in anomaly + no P1 resolve), never standalone. GT06-class trackers also report LBS-only fallback positions — cell-ID continuity during GPS loss is a cheap partial discriminator.

### deviceOverspeed / vehicle-carriage IMU signature
- Mode-detection literature: smartphone accelerometer HAR studies classify pedestrian vs in-vehicle transport at **85–98%** in realistic conditions [S](multiple: [transport-mode HAR survey](https://www.researchgate.net/publication/402187937_A_survey_on_usage_of_smartphone_accelerometer_sensor_in_intelligent_transportation_systems), [HAR classification](https://dergipark.org.tr/tr/download/article-file/3176150)) — but **none tested the "sudden walk→vehicle transition of a child carried by force"** and none reports field FP in a safety deployment [U].
- The physically robust version is simpler than IMU: **speed above pedestrian max (>25–30 km/h sustained ≥60 s, ≥3 fixes) while outside a scheduled-vehicle context**. A child cannot generate that on foot; buses/cars can (parents, trips) — so the FP driver is *schedule gaps*, not sensor noise. With an authorized-trips allowlist: ~0.1–0.5/1000 child-days.

### SOS button on the device
- User-initiated alerts are *claims*, not inferences — personal-response/mobility-alarm industry practice shows deliberate triggers have low nuisance rates; child-specific studies: **[U]**. Main FP source is play-presses → require long-press or double-press + "test mode" ritual. Estimated 0.1–0.5/1000 child-days (mostly false-positive-by-mistake, still worth P0 because the cost of ignoring a real button press is catastrophic).

### Strap/removal (phase-2 bracelet)
- Only analogous evidence: offender electronic-monitoring tamper alarms — detection of *removal attempt* is technically reliable (cut/IR/skin-off sensors), but tamper/strap alerts in the field have high nuisance rates from fit/sweat/growth (probation literature exists; abduction-specific: **[U]**). Tier as P1, and note the abductor can simply take the device — removal-detection proves *separation*, which also fires when the child charges it in the hallway.

### HR/RR anomaly fusion
- Reconfirmed negative: **no published system achieves a usable abduction FP profile from child HR** — normal play/stairs/exercise routinely exceed any single abuse-threshold HR setting. Fusion with HR does not fix the base-rate math (10^-5 prior). Keep as P2 telemetry for *evidence*, not for alerting. [U-by-absence; consistent with prior established finding]

### Has ANY real child-safety product published abduction/false-alarm metrics?
- **No.** AngelSense/GPS Smart Solutions: only vendor blogs ([angelsense.com](https://www.angelsense.com/blog/angelsense-vs-gps-trackers-heres-need-know/), [safewise.com](https://www.safewise.com/kids-safety/gps-trackers/), [turtler.io market overview](https://turtler.io/news/modern-parenting-with-modern-tools-overview-of-GPS-tracking-of-children)) and Trustpilot/user forums [V-by-absence for peer-reviewed]. School-bus stop-arm telematics: enforcement-accuracy papers exist but none found evaluating abduction [U]. Kidnapping/duress systems for contractors/embassies: **zero published effectiveness evaluation found** [U-by-absence].
- Closest thing to a measured cohort: **autism wandering/elopement**. PubMed 2020 review (32576789): ~30% of autistic children wander; most episodes are short; drowning is the leading death cause; GPS devices recommended (Autism Speaks, Pathfinders) but **without published FP/accuracy metrics** [S]. This supports the honest product positioning: the device's measured day-1 value is *missing-child/wandering* speed-up; abduction is the tail use-case.

---

## Q3. Prevention / de-escalation evidence (non-technological)

- **Walking bus / Walking School Bus**: multiple cluster evaluations exist (e.g., [IJP 2011 pilot](https://www.sciencedirect.com/science/article/pii/S1353829211001201), [CDC PCD 2020 implementation study](https://www.cdc.gov/pcd/issues/2020/20_0061.htm), [2024 cluster RCT](https://link.springer.com/article/10.1186/s12966-024-01602-w)) — measured outcomes are **physical activity, Active Transport uptake and parental traffic-safety perception**, *not abduction prevention*. Honest statement: supervised-group traversal plausibly reduces opportunity (offenders target *alone* children — consistent with NCMEC approach-location data), but no study has demonstrated an abduction effect. [S]
- **Safe-house networks (Child Safe Places / blue plaque)**: schemes operate in UK/AU/WA with published police guidance, but **no rigorous effectiveness evaluation found** this session — evidence thin, mark as practice-based not evidence-based [U-by-absence]. UNICEF *Promising Programmes* compendium and FHI360 "Becoming a Safe Organization" cover governance, not measured abduction reductions [S].
- **School pickup authorization**: standard safeguarding guidance (UK DfE-style, US district policies) requires written authorization lists; no outcome studies found. Operationally it is the control that converts your P1 alert into resolution ("is this adult authorized?") — recommend implementing authorization state inside the relay, not the fence logic. [U]
- **Deterrence of visible tracking**: no published deterrence study for child trackers; situational-prevention theory predicts modest effect; visible-device risk (target selection *by* offenders who see devices, or device theft) is unstudied. Be honest in marketing: no deterrence claim. [U]

---

## Signal → proof → expected alerts per 1000 child-days → tier

(Assumptions: school-age child, armed windows 7–9/3–4/6–7 p.m. + night fence, 100m buffered geofences, 3-fix/60s debounce, authorized-trips allowlist, 2 guardians on the relay. Estimates are engineering reasoning, not measured rates — that is itself the paper's point: nobody has measured these.)

| Signal | What it actually proves | Expected alerts / 1000 child-days (est. + reasoning) | Tier |
|---|---|---|---|
| Armed-fence exit, debounce-confirmed | Child left the protected zone outside schedule | **0.2–1** FP (buffered fence × PMC drift data); near-100% true-positive sensitivity compromised by 18% missed-exit rate → fuse with route model | **P0 hard** |
| SOS button (long/double press) | Child *claims* danger | **0.1–0.5** (mis-presses; kill with press-rules + test mode) | **P0 hard** |
| Pedestrian overspeed (>25–30 km/h ≥60 s, unscheduled) | Child is in a vehicle against plan | **0.1–0.5** (allowlist gaps: surprise lift, parent deviation) | **P0 hard** (only if trip-allowlist live) |
| Vehicle-carriage IMU transition (walk→vehicle) | Same as above, sensor-level | no field data; lab 85–98% mode accuracy → 1–5/1000 without fusion | **P1 confirmed** (fusion partner, not standalone) |
| Device offline >X min while mid-route | Link lost — battery/sleep/dead zone, possibly shutdown/jam | raw **2–10** (battery & sleep dominate) → after fusing with "was mid-route + last-fix location": 0.5–2 | **P2 soft alone → P1 when fused with fence state** |
| GPS freeze + non-GPS motion (cell/WiFi move) | Possible powered-off/jammed transport | no published data; tunnel/dead-zone confounders make raw FP 1–5 | **P2 soft**; corroborator only |
| Strap/removal (phase 2 bracelet) | Device separated from body | analogy (adult EM tamper): 1–3 nuisance; child growth/fit worse pre-tuning | **P1 confirmed** |
| HR/RR anomaly | Arousal — play, stairs, fear, all mixed | **tens–hundreds** at usable sensitivity → unusable | **P2 telemetry/evidence only, never alerting** |
| Route deviation (off-corridor dwell >T) | Child somewhere not on the learned graph | 0.5–2 (construction detours, errands) | **P1 confirmed** |
| Multi-signal fusion (exit + overspeed + offline within 5 min) | Stereotypical carry-off signature | <0.1 | **P0 hard + escalation relay to police-ready dossier** |

Guardian-visible discipline: P0 = both guardians + auto-dossier (last 30 fixes, map, heading, time-last-seen) prepared for the 911/17-equivalent call; P1 = 60–90 s two-way confirm-with-guardian-before-escalation window (kills the schedule-gap FPs); P2 = daily log, no ping.

## Five defensible numbers for the competition slide

1. **<1%** — share of NCMEC-reported missing-child cases that are genuine non-family abductions (base-rate honesty; NCMEC [V]).
2. **3 hours** — 76% of abducted-then-murdered children are dead within 3 h; 88.5% within 24 h (WA AG/Hanfland-lineage data [V]).
3. **>2 hours** — the realization→police reporting gap in nearly 60% of those cases; this product's P0 alert is measured against *this* number, not against the crime (WA AG [V]).
4. **7–9 a.m. / 3–4 p.m.** — the empirically measured peak-risk windows, which our armed schedule replicates (NCMEC [V]).
5. **18%** — real-world smartphone geofence *departure* detection rate without engineering (PMC11362315 [V]) — the credibility number for why a self-hosted Traccar pipeline with buffered fences, multi-fix confirmation and signal fusion beats every app-store tracker, and why we will be first to publish our own FP rates (none exist in the literature today).

## Flagged gaps / not verified this session
- Finkelhor 2002 exact splits (115/yr, attempts) — PDF 403; order of magnitude confirmed via CRS/OJJDP/NCMEC.
- NCMEC 23%/22% lure-tactic figures — from NCMEC's own post, primary report not read.
- CCTV preservation windows, CDR timelines, safe-place scheme effects, deterrence of visible trackers, duress-system effectiveness — no authoritative source found; stated as [U].

## Source list
- NCMEC Non-family Abductions & Attempts: https://www.missingkids.org/theissues/nonfamily
- WA AG Child Abduction Murder Research: https://www.atg.wa.gov/child-abduction-murder-research
- Na et al., Geofencing in location-based behavioral research (2023): https://pmc.ncbi.nlm.nih.gov/articles/PMC11362315/
- OJJDP National Estimates of Missing Children (2002): https://www.ojp.gov/pdffiles1/ojjdp/196465.pdf
- CRS RL31655 Missing and Exploited Children (2005): https://www.everycrsreport.com/files/20050114_RL31655_266d32d1bb1330591b19426b60e976d3b936199d.pdf
- Finkelhor et al. Nonfamily Abducted Children (2002): https://scholars.unh.edu/cgi/viewcontent.cgi?article=1016&context=ccrc (403 this session)
- OJJDP AMBER guidelines: https://amberalert.ojp.gov/about/guidelines-for-issuing-alerts
- OJJDP Missing Children Homicide Case Management: https://www.ojp.gov/sites/ojp/files/media/document/201316.pdf
- ProtectChildren.ca Abducted then Murdered (Canada): https://protectchildren.ca/en/resources-research/abducted-then-murdered-children-report/
- HM Inspectorate (UK) Missing presumed...?: http://library.college.police.uk/docs/hopolicers/fprs114.pdf
- PubMed autism wandering review: https://pubmed.ncbi.nlm.nih.gov/32576789/ ; Autism Speaks wandering: https://www.autismspeaks.org/wandering-prevention
- Walking bus evaluations: https://www.sciencedirect.com/science/article/pii/S1353829211001201 ; https://www.cdc.gov/pcd/issues/2020/20_0061.htm ; https://link.springer.com/article/10.1186/s12966-024-01602-w
- Industry geofence/GPS-drift: https://radar.com/blog/how-accurate-is-geofencing ; https://www.whitelabeltracking.com/what-is-gps-drift-and-how-tracking-service-providers-can-suppress-it/
- Product-market overview (no eval data): https://turtler.io/news/modern-parenting-with-modern-tools-overview-of-GPS-tracking-of-children
