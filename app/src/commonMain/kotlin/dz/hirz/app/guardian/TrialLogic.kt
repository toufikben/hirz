package dz.hirz.app.guardian

import dz.hirz.shared.api.CONSENT_VERSION
import dz.hirz.shared.api.Outcome
import dz.hirz.shared.api.ProblemReportRequest

private fun t(lang: String, ar: String, fr: String, en: String) = when (lang) { "ar" -> ar; "fr" -> fr; else -> en }

/** What the field-trial screens say and send (P8). Pure, so it is tested. */
object TrialLogic {
    /** The consent text, version [CONSENT_VERSION]; changing it means bumping the version on both sides. */
    fun consentText(lang: String): String = t(lang,
        "تجربة حِرز الميدانية (النسخة $CONSENT_VERSION). بالانضمام توافق على أن تُجمع عن دائرتك أرقام مجمّعة فقط: " +
            "عدد الإنذارات لكل قاعدة، وهل كانت حقيقية أم كاذبة كما تقيّمها أنت، وسرعة وصول الإشعارات واستلامها، وكم ساعة كان الجهاز متصلًا. " +
            "لا تُرسل للمشرف على التجربة أسماء ولا مواقع ولا أي إحداثيات، ويرى دائرتك برمز مستعار فقط. " +
            "يمكنك الانسحاب في أي وقت من هذه الشاشة، وعندها تُحذف بيانات التجربة الخاصة بدائرتك. حِرز يبقى يعمل كالمعتاد.",
        "Essai terrain Hirz (version $CONSENT_VERSION). En rejoignant, vous acceptez que seuls des chiffres agrégés soient collectés " +
            "sur votre cercle : nombre d'alertes par règle, si elles étaient réelles ou fausses selon votre avis, rapidité de réception " +
            "et de prise en charge des notifications, et heures de connexion de l'appareil. Aucun nom, aucune localisation ni coordonnée " +
            "n'est envoyé au responsable de l'essai, qui ne voit votre cercle que sous un pseudonyme. Vous pouvez quitter à tout moment " +
            "depuis cet écran ; les données d'essai de votre cercle sont alors supprimées. Hirz continue de fonctionner normalement.",
        "Hirz field trial (version $CONSENT_VERSION). By joining you agree that only aggregate numbers are collected about your circle: " +
            "how many alarms each rule raised, whether you judged them real or false, how fast notifications arrived and were " +
            "acknowledged, and how many hours the device was connected. No names, no location and no coordinates are sent to the " +
            "trial's operator, who sees your circle only under a pseudonym. You can leave at any time from this screen; your " +
            "circle's trial data is then deleted. Hirz keeps working as usual.",
    )

    enum class LeaveStep { CONFIRM, LEAVE }

    /** Leaving takes two taps: the first asks, the second leaves. */
    fun leaveTap(confirming: Boolean): LeaveStep = if (confirming) LeaveStep.LEAVE else LeaveStep.CONFIRM

    val OUTCOMES = listOf(Outcome.REAL, Outcome.FALSE_DEVICE, Outcome.FALSE_RULE, Outcome.TEST, Outcome.UNSURE)

    fun outcomeLabel(o: Outcome, lang: String): String = when (o) {
        Outcome.REAL -> t(lang, "كان حقيقيًا", "C'était réel", "It was real")
        Outcome.FALSE_DEVICE -> t(lang, "عطل في الجهاز", "Problème d'appareil", "Device problem")
        Outcome.FALSE_RULE -> t(lang, "القاعدة أخطأت", "La règle s'est trompée", "The rule was wrong")
        Outcome.TEST -> t(lang, "كان اختبارًا", "C'était un test", "It was a test")
        Outcome.UNSURE -> t(lang, "لست متأكدًا", "Je ne sais pas", "Not sure")
    }

    /** A problem report: text, app version and device; never a location. */
    fun report(circleId: String, text: String, appVersion: String, device: String) = ProblemReportRequest(circleId, text.trim(), appVersion, device)
}
