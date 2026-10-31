# LoRaWAN for pastures without coverage (P6 Task 9)

**Question:** Should Hirz support LoRaWAN collars for herds in pastures with no mobile coverage? If so, how should the uplinks reach Core? This is research only; nothing is bought or built yet.

## What the sources say

| Topic | Finding | Confidence |
|---|---|---|
| Public LoRaWAN in Algeria | There is no public LoRaWAN operator: the LoRa Alliance's map of operators in 100+ countries has none in Algeria (already noted in `res-alerting.md`). A herd owner would need **their own gateway**. | High |
| Traccar support | Neither search found Traccar documentation of a LoRaWAN, ChirpStack or TTN integration. **Not confirmed.** Before relying on Traccar, check the protocol list in Traccar's own repository. | Unknown |
| Network server | ChirpStack is an open-source LoRaWAN network server ([overview](https://www.chirpstack.io/overview/), MIT per that page, though the page documents v3). It sends uplinks to HTTP webhooks, as the ThingsBoard integration shows ([ThingsBoard ChirpStack](https://thingsboard.io/docs/pe/user-guide/integrations/chirpstack/)). v4 imports device codecs from the TTN device repository ([ChirpStack TTN repository](https://www.chirpstack.io/docs/chirpstack/features/ttn-device-repository.html)). | Medium–high |
| Battery (GPS collar, 15 min) | In a field trial on beef cattle, batteries fell from 100% to about 35% over three months. Fixes were set to every 15 min, but only 0.08–1.3 h intervals were actually recovered ([NMSU Jornada](https://archive.jornada.nmsu.edu/node/55363)). A modelled custom node lasts about 4 months at 15 min ([arXiv 2406.06245](https://www.arxiv.org/abs/2406.06245)). One vendor claims up to 3 years at 1 fix per hour ([Zbotic](https://zbotic.in/animal-tracking-collar-gps-lora-for-livestock-monitoring/)). | Medium |
| Range (rural) | Only one measured livestock figure was found: about 10 km, from a gateway carried on a drone ([Sensors 2021](https://pubmed.ncbi.nlm.nih.gov/34372281/)). Flat open land is *modelled* at 5–8 km with a mast-mounted antenna. Vendors quote up to 15 km, with no method given ([Minew](https://www.minew.com/lorawan-range-overview/)). No measured ground-gateway figures were found for the steppe or for mountains. | Low–medium |
| Regulation | Algeria's short-range device guidelines come from Executive Decree 21-43 (January 2021). SRDs are approved by the ANF, other radio equipment by ARPCE ([Eleos](https://www.eleoscompliance.com/en/article/algeria-arpce-publish-shortrange-device-regulation), [Applus](https://appluslaboratories-test.applus.solutions/dam/PDFServices/Laboratories/GLOBAL/algeria-radio-type-approval-(arpce--anf)_en.pdf)). The **868 MHz power and duty-cycle figures were not confirmed**. A new type-approval framework was issued in 2026. | Low on the band details |

## What this means for Hirz

1. **LoRaWAN cannot be a feature a breeder switches on.** It needs a gateway on a mast or a hill, solar power, a backhaul (one SIM where any signal exists), and type-approved collars. That is a farm installation, not an app setting.
2. **Battery:** about three months at a 15 min interval is the one field figure. At 1 fix per hour it is likely a year or more. This is comparable to cellular collars but without the SIM cost. The real gain is coverage, not battery.
3. **Range is the big unknown.** In the steppe, 5–10 km from a good mast is plausible. In the mountains it is unknown. A bench trial and a field trial must measure it before anything is promised.
4. **Integration path:** ChirpStack self-hosted next to Traccar on the same VM, then an HTTP integration to a new Core endpoint `/v1/lorawan/uplink`. That endpoint would sit behind a shared secret and accept only registered DevEUIs, with proof of possession at registration as for BLE. The decoded position becomes a fix for the collar's asset, exactly like a BLE sighting. This path does not depend on unconfirmed Traccar support.
5. **P6 already covers the main gap without LoRaWAN:**
   - a cellular leader;
   - BLE collars on the rest of the herd;
   - the shepherd's phone as the relay;
   - store-and-forward for late fixes.

   LoRaWAN is for herds that graze without a shepherd, far from any signal.

## Recommendation
- **Now:** no LoRaWAN code. Ship P6 with the leader, BLE collars and the shepherd relay.
- **Bench trial (owner-dependent):**
  - Kit: one EU868 gateway (outdoor, with PoE, budget roughly the price of 3–4 cellular trackers) and two GNSS+LoRa collars. Prefer ones with codecs in the TTN device repository.
  - Run ChirpStack on the staging VM.
  - Measure range with the gateway on a 6–10 m mast in the steppe, and battery life at 15 min and 60 min.
  - Steps: `docs/runbooks/P6-lorawan-bench.md` (to be written when the kit is bought).
- **Before any sale:** confirm the 868 MHz SRD rules and the collar's ANF approval with a local compliance consultant. Until then the decree text is the only source.

Sources: the links in the table above.
