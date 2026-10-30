package dz.hirz.shared.devices

import dz.hirz.shared.api.SmsCommandDto
import kotlinx.serialization.Serializable

/** Design §3: CERTIFIED = tested by us on its firmware; SUPPORTED = protocol in Traccar, not tested; REJECTED = never. */
@Serializable
enum class SupportLevel { CERTIFIED, SUPPORTED, REJECTED }

/**
 * One setup SMS. [purpose]: password, server, apn, interval, sos_number, status. Placeholders: {password} (current),
 * {newPassword}, {host}, {port}, {apn}, {seconds}, {phone}. [verified] only after the device trial on real firmware.
 */
@Serializable
data class CommandTemplate(val purpose: String, val template: String, val verified: Boolean = false)

@Serializable
data class DeviceModel(
    val id: String,
    val name: String,
    val protocol: String,
    val port: Int,
    val level: SupportLevel,
    /** Contract fields this model reports (design §3): sos, battery, battery_level, power_cut, removal, buffering… */
    val capabilities: Set<String> = emptySet(),
    /** What it does not report; the wizard says so plainly before the owner commits. */
    val missing: Set<String> = emptySet(),
    val defaultPassword: String? = null,
    val commands: List<CommandTemplate> = emptyList(),
    val rejectedReason: String? = null,
)

/**
 * The models Hirz knows (design §3, v2 §11). Commands come from public documentation and the v2 research; none is
 * verified until the P3 device trial proves it on the exact firmware, and only then may a model become CERTIFIED.
 */
object DeviceCatalog {
    val models: List<DeviceModel> = listOf(
        DeviceModel(
            id = "gt06n", name = "GT06N (GT06 family)", protocol = "gt06", port = 5023, level = SupportLevel.SUPPORTED,
            // engine_cut needs the relay wired to the fuel pump or starter; Traccar sends engineStop/engineResume.
            capabilities = setOf("sos", "battery", "power_cut", "buffering", "ignition", "vibration", "engine_cut"), missing = setOf("removal"),
            defaultPassword = "123456",
            commands = listOf(
                CommandTemplate("password", "PASSWORD,{password},{newPassword}#"),
                CommandTemplate("server", "SERVER,1,{host},{port},0#"),
                CommandTemplate("apn", "APN,{apn}#"),
                CommandTemplate("interval", "TIMER,{seconds}#"),
                CommandTemplate("sos_number", "SOS,A,{phone}#"),
                CommandTemplate("status", "STATUS#"),
            ),
        ),
        DeviceModel(
            id = "sinotrack-st901", name = "SinoTrack ST-901", protocol = "h02", port = 5013, level = SupportLevel.SUPPORTED,
            capabilities = setOf("sos", "power_cut", "buffering"), missing = setOf("removal", "battery_level"),
            defaultPassword = "0000",
            commands = listOf(
                CommandTemplate("password", "777{newPassword}{password}"),
                CommandTemplate("server", "804{newPassword} {host} {port}"),
                CommandTemplate("apn", "803{newPassword} {apn}"),
                CommandTemplate("interval", "805{newPassword} {seconds}"),
                CommandTemplate("sos_number", "{phone}{newPassword} 1"),
                CommandTemplate("status", "RCONF"),
            ),
        ),
        DeviceModel(
            id = "setracker-class", name = "SeTracker / CocoTracker kids' watches", protocol = "watch", port = 0, level = SupportLevel.REJECTED,
            rejectedReason = "Fixed vendor server: it cannot be redirected to Hirz by SMS.",
        ),
        DeviceModel(
            id = "wonlex-kt12", name = "Wonlex KT12", protocol = "watch", port = 0, level = SupportLevel.REJECTED,
            rejectedReason = "Fixed IP that cannot be changed through SMS (Traccar forum).",
        ),
    )

    fun byId(id: String): DeviceModel? = models.firstOrNull { it.id == id }

    private val placeholder = Regex("\\{([a-zA-Z]+)\\}")

    /** Renders every command in catalogue order (the password change first); a missing value is an error, never a blank. */
    fun render(model: DeviceModel, values: Map<String, String>, to: String? = null): List<SmsCommandDto> = model.commands.map { c ->
        val text = placeholder.replace(c.template) { m ->
            values[m.groupValues[1]] ?: throw IllegalArgumentException("${model.id}/${c.purpose}: missing ${m.groupValues[1]}")
        }
        SmsCommandDto(to, text, c.purpose)
    }
}
