package dz.hirz.app.guardian

import dz.hirz.shared.api.CloseReason
import dz.hirz.shared.api.EscalationViewDto
import dz.hirz.shared.api.PendingCloseDto

private fun t(lang: String, ar: String, fr: String, en: String) = when (lang) { "ar" -> ar; "fr" -> fr; else -> en }

/** One line about the escalation ladder (design §5.3): who is being asked and until when. */
object LadderText {
    /** [clock] formats an epoch-ms deadline as local wall time ("15:43:00"). Null when there is nothing to wait for. */
    fun render(view: EscalationViewDto?, names: Map<String, String>, lang: String, clock: (Long) -> String): String? {
        view ?: return null
        val until = view.deadlineMs?.let(clock)
        return when (view.stage) {
            "GUARDIANS" -> {
                val who = view.waitingForMemberId?.let { names[it] } ?: t(lang, "عضو", "un membre", "a member")
                if (until == null) t(lang, "بانتظار $who", "En attente de $who", "Waiting for $who")
                else t(lang, "بانتظار $who حتى $until", "En attente de $who jusqu'à $until", "Waiting for $who until $until")
            }
            "CONTACTS" -> t(lang, "نُبّهت جهات الاتصال برسالة SMS", "Contacts alertés par SMS", "Contacts alerted by SMS")
            "EVERYONE_UNAVAILABLE" -> t(lang, "لم يردّ أحد — إعادة تنبيه الجميع", "Personne n'a répondu — nouvelle alerte à tous", "Nobody answered — alerting everyone again")
            else -> null
        }
    }
}

enum class PendingAction { NONE, WAIT_FOR_OTHER, CONFIRM }

/** Why a close may need a second person (design §5.7), said before the tap, never after a surprise. */
object CloseHint {
    fun forReason(reason: CloseReason, tierP0: Boolean, editors: Int, lang: String): String = when {
        reason == CloseReason.FOUND_SAFE -> t(
            lang,
            "«وُجد سالمًا» يُغلق فورًا إذا كان آخر موقع حديث في البيت أو المدرسة أو عند قريب، أو كان وليّ قربه؛ وإلا يحتاج تأكيد وليّ ثانٍ.",
            "« Retrouvé » ferme aussitôt si la dernière position récente est à la maison, à l'école ou chez un proche, ou si un tuteur est à côté ; sinon un second tuteur doit confirmer.",
            "\"Found safe\" closes at once if the latest recent position is at home, school or a relative's, or a guardian is next to it; otherwise a second guardian must confirm.",
        )
        tierP0 && editors >= 2 -> t(lang, "إغلاق حالة عاجلة يحتاج تأكيد وليّ ثانٍ.", "Fermer une urgence demande la confirmation d'un second tuteur.", "Closing an emergency needs a second guardian to confirm.")
        tierP0 -> t(lang, "أنت الوليّ الوحيد: اضغط «تأكيد» مرة أخرى خلال دقيقتين.", "Vous êtes le seul tuteur : confirmez encore une fois dans les 2 minutes.", "You are the only guardian: confirm again within 2 minutes.")
        else -> ""
    }

    fun pending(p: PendingCloseDto?, myMemberId: String?, editors: Int): PendingAction = when {
        p == null -> PendingAction.NONE
        p.requestedBy == myMemberId && editors >= 2 -> PendingAction.WAIT_FOR_OTHER
        else -> PendingAction.CONFIRM
    }
}

/**
 * The "post" text for neighbours and social media (design §5.8): name, clothes, where and when last seen (a place
 * name, never coordinates), the family phone. The code word is never an input, so it can never leak here.
 */
object PostText {
    fun compose(name: String, clothes: String?, lastSeenPlace: String?, lastSeenAt: String?, familyPhone: String, lang: String): String = buildString {
        appendLine(t(lang, "مفقود: $name", "Disparu : $name", "Missing: $name"))
        clothes?.takeIf { it.isNotBlank() }?.let { appendLine(t(lang, "اللباس: $it", "Vêtements : $it", "Wearing: $it")) }
        val seen = listOfNotNull(lastSeenPlace?.takeIf { it.isNotBlank() }, lastSeenAt?.takeIf { it.isNotBlank() }).joinToString(" — ")
        if (seen.isNotEmpty()) appendLine(t(lang, "آخر مرة شوهد: $seen", "Vu pour la dernière fois : $seen", "Last seen: $seen"))
        append(t(lang, "من يراه يتصل فورًا: $familyPhone", "Si vous le voyez, appelez : $familyPhone", "If you see them, call: $familyPhone"))
    }
}

/**
 * The guardian's own position during an open incident (design §5.11): only while the incident is open and its
 * screen is visible, every 30 s, so the nearest guardian can be asked first. Nothing runs in the background.
 */
object LocationReporter {
    const val INTERVAL_MS = 30_000L

    fun shouldRun(open: Boolean, visible: Boolean) = open && visible

    suspend fun run(active: () -> Boolean, send: suspend () -> Unit, wait: suspend (Long) -> Unit) {
        while (active()) {
            runCatching { send() }
            wait(INTERVAL_MS)
        }
    }
}
