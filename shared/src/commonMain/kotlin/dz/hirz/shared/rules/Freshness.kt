package dz.hirz.shared.rules

/** How fresh the last fix is, as shown on the map (design §6.1). */
enum class Freshness { FRESH, STALE, SILENT }

/** A missing fix is SILENT: no data must never read as "safe". */
fun freshnessOf(ageMs: Long?, config: RuleConfig = RuleConfig.DEFAULT): Freshness = when {
    ageMs == null -> Freshness.SILENT
    ageMs >= config.silentAfterMs -> Freshness.SILENT
    ageMs >= config.staleAfterMs -> Freshness.STALE
    else -> Freshness.FRESH
}
