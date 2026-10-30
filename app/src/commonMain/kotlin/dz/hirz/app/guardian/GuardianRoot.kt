package dz.hirz.app.guardian

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dz.hirz.app.AlarmReadinessCard
import dz.hirz.app.AppEnvironment
import dz.hirz.app.platformPushToken
import dz.hirz.app.api.HirzClient
import dz.hirz.app.generated.resources.*
import dz.hirz.shared.api.ActiveWindow
import dz.hirz.shared.api.AssetDto
import dz.hirz.shared.api.AssetLiveDto
import dz.hirz.shared.api.CircleDto
import dz.hirz.shared.api.CloseReason
import dz.hirz.shared.api.CreateAssetRequest
import dz.hirz.shared.api.CreateInviteRequest
import dz.hirz.shared.api.CreatePairingRequest
import dz.hirz.shared.api.CreatePlaceRequest
import dz.hirz.shared.api.EmergencyNumberDto
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.LiveEvent
import dz.hirz.shared.api.MemberDto
import dz.hirz.shared.api.PlaceDto
import dz.hirz.shared.api.PlaceKind
import dz.hirz.shared.api.Role
import dz.hirz.shared.asset.AssetType
import dz.hirz.shared.rules.freshnessOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Live state of the guardian screens, fed by the snapshot and the event stream of `WS /v1/live`. */
private class LiveState {
    val assets = mutableStateMapOf<String, AssetLiveDto>()
    val incidents = mutableStateMapOf<String, IncidentDto>()
    var connected by mutableStateOf(false)

    fun apply(e: LiveEvent) {
        when (e) {
            is LiveEvent.Snapshot -> {
                assets.clear(); e.assets.forEach { assets[it.assetId] = it }
                incidents.clear(); e.incidents.forEach { incidents[it.id] = it }
            }
            is LiveEvent.PositionUpdate -> assets[e.position.assetId]?.let { a ->
                if ((a.last?.fixTimeMs ?: 0) <= e.position.fixTimeMs) assets[a.assetId] = a.copy(last = e.position)
            }
            is LiveEvent.IncidentUpdate -> if (e.incident.state == "open") incidents[e.incident.id] = e.incident else incidents.remove(e.incident.id)
            is LiveEvent.MemberRemoved -> { assets.clear(); incidents.clear() }
        }
    }
}

private enum class GuardianTab(val label: StringResource) {
    MAP(Res.string.tab_map), PLACES(Res.string.tab_places), CIRCLE(Res.string.tab_circle), INCIDENTS(Res.string.tab_incidents)
}

@OptIn(ExperimentalTime::class)
private fun nowMs() = Clock.System.now().toEpochMilliseconds()

@Composable
fun GuardianRoot(env: AppEnvironment, client: HirzClient) {
    val scope = rememberCoroutineScope()
    var circles by remember { mutableStateOf<List<CircleDto>?>(null) }
    var error by remember { mutableStateOf(false) }
    suspend fun reload() { runCatching { client.circles() }.onSuccess { circles = it; error = false }.onFailure { error = true } }
    LaunchedEffect(client) {
        reload()
        platformPushToken()?.let { token -> runCatching { client.setPushToken(token) } }
    }

    val list = circles
    when {
        list == null -> Column(Modifier.padding(24.dp)) {
            Text(stringResource(if (error) Res.string.error_generic else Res.string.loading))
            if (error) Button({ scope.launch { reload() } }) { Text(stringResource(Res.string.reconnecting)) }
        }
        list.isEmpty() -> CreateOrJoin(client) { scope.launch { reload() } }
        else -> CircleHome(env, client, list.first())
    }
}

@Composable
private fun CreateOrJoin(client: HirzClient, done: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(Res.string.create_circle), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(Res.string.circle_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button({ scope.launch { runCatching { client.createCircle(name) }.onSuccess { done() } } }, enabled = name.isNotBlank()) {
            Text(stringResource(Res.string.create))
        }
        HorizontalDivider()
        Text(stringResource(Res.string.join_with_code), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(code, { code = it }, label = { Text(stringResource(Res.string.code)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button({ scope.launch { runCatching { client.acceptInvite(code.trim()) }.onSuccess { done() } } }, enabled = code.length >= 8) {
            Text(stringResource(Res.string.join))
        }
    }
}

@Composable
private fun CircleHome(env: AppEnvironment, client: HirzClient, circle: CircleDto) {
    val live = remember(circle.id) { LiveState() }
    var tab by remember { mutableStateOf(GuardianTab.MAP) }
    var now by remember { mutableStateOf(nowMs()) }
    LaunchedEffect(Unit) { while (true) { delay(5_000); now = nowMs() } }
    LaunchedEffect(client) {
        while (true) {
            runCatching { client.live().collect { live.connected = true; live.apply(it) } }
            live.connected = false
            delay(5_000)
        }
    }
    Column(Modifier.fillMaxSize()) {
        TabRow(tab.ordinal) {
            GuardianTab.entries.forEach { t ->
                val badge = if (t == GuardianTab.INCIDENTS && live.incidents.isNotEmpty()) " (${live.incidents.size})" else ""
                Tab(tab == t, { tab = t }, text = { Text(stringResource(t.label) + badge) })
            }
        }
        if (!live.connected) Text(stringResource(Res.string.reconnecting), Modifier.padding(8.dp), color = MaterialTheme.colorScheme.error)
        when (tab) {
            GuardianTab.MAP -> MapTab(live, now)
            GuardianTab.PLACES -> PlacesTab(client, circle)
            GuardianTab.CIRCLE -> CircleTab(env, client, circle)
            GuardianTab.INCIDENTS -> IncidentsTab(client, circle, live)
        }
    }
}

@Composable
private fun MapTab(live: LiveState, now: Long) {
    val lang = currentLang()
    val assets = live.assets.values.sortedBy { it.name }
    val markers = assets.mapNotNull { a ->
        a.last?.let { p -> MapMarker(a.assetId, a.name, p.position, p.accuracyM, markerStyle(freshnessOf(now - p.fixTimeMs))) }
    }
    Column(Modifier.fillMaxSize()) {
        AlarmReadinessCard()
        HirzMap(markers, emptyList(), emptyList(), Modifier.fillMaxWidth().weight(1f)) {}
        if (assets.isEmpty()) Text(stringResource(Res.string.no_assets), Modifier.padding(16.dp))
        assets.forEach { a ->
            val age = a.last?.let { now - it.fixTimeMs }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(a.name, style = MaterialTheme.typography.titleMedium)
                Text(Captions.age(age, a.last?.accuracyM, lang), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private val PLACE_KINDS = listOf(
    PlaceKind.HOME to Res.string.kind_home, PlaceKind.SCHOOL to Res.string.kind_school, PlaceKind.MOSQUE to Res.string.kind_mosque,
    PlaceKind.LESSONS to Res.string.kind_lessons, PlaceKind.PARK to Res.string.kind_park, PlaceKind.RELATIVE to Res.string.kind_relative,
    PlaceKind.FORBIDDEN to Res.string.kind_forbidden, PlaceKind.HAZARD to Res.string.kind_hazard,
    PlaceKind.PAST_ADDRESS to Res.string.kind_past_address, PlaceKind.OTHER to Res.string.kind_other,
)

/** Offered only under "more", with a neutral label (design §12.2 COASTAL_NIGHT: set by hand, never promoted). */
private val MORE_PLACE_KINDS = listOf(PlaceKind.COAST to Res.string.kind_coast)

/** Sunday–Thursday 07:30–16:30, the Algerian school week (ISO days: 7, 1, 2, 3, 4). */
private val SCHOOL_HOURS = listOf(ActiveWindow(setOf(7, 1, 2, 3, 4), 7 * 60 + 30, 16 * 60 + 30))

@Composable
private fun PlacesTab(client: HirzClient, circle: CircleDto) {
    val scope = rememberCoroutineScope()
    var places by remember { mutableStateOf<List<PlaceDto>>(emptyList()) }
    suspend fun reload() { runCatching { client.places(circle.id) }.onSuccess { places = it } }
    LaunchedEffect(circle.id) { reload() }
    var polygon by remember { mutableStateOf(false) }
    val points = remember { mutableStateListOf<LatLon>() }
    var radius by remember { mutableStateOf(150f) }
    var margin by remember { mutableStateOf(50f) }
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(PlaceKind.HOME) }
    var schoolHours by remember { mutableStateOf(false) }

    val circleDraft = points.firstOrNull()?.let { CircleDraft(it, radius.toDouble(), margin.roundToInt()) }
    val polygonDraft = PolygonDraft(points.toList())
    val canSave = name.isNotBlank() && if (polygon) polygonDraft.isValid else circleDraft?.isValid == true

    Column(Modifier.fillMaxSize()) {
        HirzMap(emptyList(), places, points.toList(), Modifier.fillMaxWidth().height(280.dp)) { tap ->
            if (polygon) points += tap else { points.clear(); points += tap }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(stringResource(Res.string.add_place), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(Res.string.tap_map_hint), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!polygon, { polygon = false; points.clear() }, { Text(stringResource(Res.string.shape_circle)) })
                    FilterChip(polygon, { polygon = true; points.clear() }, { Text(stringResource(Res.string.shape_polygon)) })
                    if (polygon) TextButton({ if (points.isNotEmpty()) points.removeAt(points.lastIndex) }) { Text(stringResource(Res.string.undo)) }
                }
                if (!polygon) {
                    Text(stringResource(Res.string.radius_m, radius.roundToInt().toString()))
                    Slider(radius, { radius = it }, valueRange = 30f..2000f)
                }
                Text(stringResource(Res.string.margin_m, margin.roundToInt().toString()))
                Slider(margin, { margin = it }, valueRange = 50f..500f)
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(Res.string.place_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PLACE_KINDS.take(4).forEach { (k, label) -> FilterChip(kind == k, { kind = k }, { Text(stringResource(label)) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PLACE_KINDS.drop(4).take(4).forEach { (k, label) -> FilterChip(kind == k, { kind = k }, { Text(stringResource(label)) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PLACE_KINDS.drop(8).forEach { (k, label) -> FilterChip(kind == k, { kind = k }, { Text(stringResource(label)) }) }
                    var more by remember { mutableStateOf(false) }
                    if (!more) TextButton({ more = true }) { Text(stringResource(Res.string.kind_more)) }
                    else MORE_PLACE_KINDS.forEach { (k, label) -> FilterChip(kind == k, { kind = k }, { Text(stringResource(label)) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(schoolHours, { schoolHours = it })
                    Text(stringResource(Res.string.school_hours_only))
                }
                Button({
                    scope.launch {
                        val shape = if (polygon) polygonDraft.shape() else circleDraft!!.shape()
                        val req = CreatePlaceRequest(name.trim(), kind, shape, margin.roundToInt(), if (schoolHours) SCHOOL_HOURS else emptyList())
                        runCatching { client.createPlace(circle.id, req) }.onSuccess { name = ""; points.clear(); reload() }
                    }
                }, enabled = canSave) { Text(stringResource(Res.string.save)) }
                HorizontalDivider()
            }
            items(places, key = { it.id }) { p ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name)
                    TextButton({ scope.launch { runCatching { client.deletePlace(circle.id, p.id) }; reload() } }) { Text(stringResource(Res.string.delete)) }
                }
            }
        }
    }
}

private val ASSET_TYPES = listOf(
    AssetType.CHILD to Res.string.type_child, AssetType.TEEN to Res.string.type_teen,
    AssetType.ELDER to Res.string.type_elder, AssetType.SPECIAL_NEEDS to Res.string.type_special_needs,
)

private val VEHICLE_TYPES = setOf(AssetType.CAR, AssetType.MOTORCYCLE, AssetType.TRUCK, AssetType.BUS, AssetType.MACHINERY, AssetType.GENERATOR)

@Composable
private fun CircleTab(env: AppEnvironment, client: HirzClient, circle: CircleDto) {
    val scope = rememberCoroutineScope()
    var members by remember { mutableStateOf<List<MemberDto>>(emptyList()) }
    var assets by remember { mutableStateOf<List<AssetDto>>(emptyList()) }
    var invite by remember { mutableStateOf<String?>(null) }
    var pairing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var assetName by remember { mutableStateOf("") }
    var assetType by remember { mutableStateOf(AssetType.CHILD) }
    var cancelPin by remember { mutableStateOf("") }
    var duressPin by remember { mutableStateOf("") }
    var wizardFor by remember { mutableStateOf<String?>(null) }
    suspend fun reload() {
        runCatching { client.members(circle.id) }.onSuccess { members = it }
        runCatching { client.assets(circle.id) }.onSuccess { assets = it }
    }
    LaunchedEffect(circle.id) { reload() }
    wizardFor?.let { id -> TrackerWizard(client, id) { wizardFor = null; scope.launch { reload() } } }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(stringResource(Res.string.members), style = MaterialTheme.typography.titleMedium) }
        items(members, key = { it.memberId }) { m -> Text("${m.priority}. ${m.displayName ?: m.memberId.take(8)} — ${m.role.name.lowercase()}") }
        if (circle.myRole == Role.OWNER) item {
            OutlinedButton({ scope.launch { runCatching { client.invite(circle.id, CreateInviteRequest(Role.GUARDIAN)) }.onSuccess { invite = it.code } } }) {
                Text(stringResource(Res.string.invite))
            }
            invite?.let { Text(stringResource(Res.string.invite_code, it), style = MaterialTheme.typography.titleLarge) }
        }
        item { HorizontalDivider(); Text(stringResource(Res.string.people_and_things), style = MaterialTheme.typography.titleMedium) }
        items(assets, key = { it.id }) { a ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(a.name, style = MaterialTheme.typography.titleMedium)
                    if (a.type !in VEHICLE_TYPES) ProfileCard(client, a, isOwner = circle.myRole == Role.OWNER)
                    if (a.type in VEHICLE_TYPES) {
                        VehicleCard(client, a, isOwner = circle.myRole == Role.OWNER, canEdit = circle.myRole == Role.OWNER || circle.myRole == Role.GUARDIAN)
                        if (circle.myRole == Role.OWNER || circle.myRole == Role.GUARDIAN) OutlinedButton({ wizardFor = a.id }) {
                            Text(when (currentLang()) { "ar" -> "جهاز تتبع"; "fr" -> "Traceur"; else -> "Tracker" })
                        }
                        return@Column
                    }
                    OutlinedTextField(cancelPin, { cancelPin = it.filter(Char::isDigit).take(8) }, label = { Text(stringResource(Res.string.cancel_pin)) }, singleLine = true)
                    OutlinedTextField(duressPin, { duressPin = it.filter(Char::isDigit).take(8) }, label = { Text(stringResource(Res.string.duress_pin)) }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({
                            scope.launch {
                                val req = CreatePairingRequest(cancelPin.ifBlank { null }, duressPin.ifBlank { null })
                                runCatching { client.pairingCode(a.id, req) }.onSuccess { pairing = a.id to it.code }
                            }
                        }) { Text(stringResource(Res.string.pair_device)) }
                        OutlinedButton({ scope.launch { runCatching { client.checkIn(a.id) } } }) { Text(stringResource(Res.string.ask_checkin)) }
                        RingButton(client, a.id)
                        if (circle.myRole == Role.OWNER || circle.myRole == Role.GUARDIAN) OutlinedButton({ wizardFor = a.id }) {
                            Text(when (currentLang()) { "ar" -> "جهاز تتبع"; "fr" -> "Traceur"; else -> "Tracker" })
                        }
                    }
                    pairing?.takeIf { it.first == a.id }?.let { Text(stringResource(Res.string.pairing_code, it.second), style = MaterialTheme.typography.titleLarge) }
                }
            }
        }
        item {
            OutlinedTextField(assetName, { assetName = it }, label = { Text(stringResource(Res.string.asset_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ASSET_TYPES.forEach { (t, label) -> FilterChip(assetType == t, { assetType = t }, { Text(stringResource(label)) }) }
                FilterChip(assetType == AssetType.CAR, { assetType = AssetType.CAR }, {
                    Text(when (currentLang()) { "ar" -> "سيارة"; "fr" -> "Voiture"; else -> "Car" })
                })
            }
            Button({ scope.launch { runCatching { client.createAsset(circle.id, CreateAssetRequest(assetType, assetName.trim())) }.onSuccess { assetName = ""; reload() } } },
                enabled = assetName.isNotBlank()) { Text(stringResource(Res.string.add_asset)) }
        }
        item { HorizontalDivider(); CodeWordCard(client, circle) }
        item { ContactsCard(client, circle) }
        item { SignOutButton(client, { platformPushToken() }, { env.auth.signOut() }) }
    }
}

@Composable
private fun IncidentsTab(client: HirzClient, circle: CircleDto, live: LiveState) {
    var view by remember(circle.id) { mutableStateOf(CircleView(emptyMap(), 1, null)) }
    var numbers by remember(circle.id) { mutableStateOf<List<EmergencyNumberDto>>(emptyList()) }
    LaunchedEffect(circle.id) {
        val me = runCatching { client.me().memberId }.getOrNull()
        runCatching { client.members(circle.id) }.onSuccess { ms ->
            view = CircleView(
                names = ms.associate { it.memberId to (it.displayName ?: it.memberId.take(8)) },
                editors = ms.count { it.role == Role.OWNER || it.role == Role.GUARDIAN },
                myMemberId = me,
            )
        }
        runCatching { client.emergencyNumbers(circle.id) }.onSuccess { numbers = it }
    }
    val open = live.incidents.values.sortedWith(compareBy({ it.tier }, { -it.openedAtMs }))
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (open.isEmpty()) item { Text(stringResource(Res.string.no_open_incidents)) }
        items(open, key = { it.id }) { inc -> IncidentCard(client, inc, view, numbers) { live.apply(LiveEvent.IncidentUpdate(it)) } }
        item {
            Text(stringResource(Res.string.emergency_numbers), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                numbers.forEach { n -> OutlinedButton({ dial(n.number) }) { Text("${stringResource(Res.string.call)} ${n.number}" + if (n.verifiedAtMs == null) " ⚠" else "") } }
            }
        }
    }
}
