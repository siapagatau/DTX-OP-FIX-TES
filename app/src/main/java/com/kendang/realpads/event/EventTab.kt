package com.kendang.realpads.event

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kendang.realpads.BuildConfig
import com.kendang.realpads.CtrlPanelColor
import com.kendang.realpads.PadLineColor
import com.kendang.realpads.PadTextColor
import com.kendang.realpads.R
import com.kendang.realpads.TextDim
import com.kendang.realpads.ThemeState
import com.kendang.realpads.skin.SkinManager
import com.kendang.realpads.skin.SkinRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Isi tab "Event" di SkinPickerDialog (gaya sama: baris CtrlPanelColor rounded 8dp, aksen ThemeState.accentColor).
 * Dipanggil di dalam Column berlebar 300.dp + verticalScroll milik dialog.
 * [onUpgrade]: dipanggil di versi FREE (skin digembok) - mis. openProUpgradeLink(context).
 */
@Composable
fun EventTab(onUpgrade: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snap by EventRepository.snapshot.collectAsState()
    val prog by EventProgressManager.state.collectAsState()
    val backup by EventBackupManager.status.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    var busyEvent by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var selectedSkin by remember { mutableStateOf(SkinManager.selectedId(context)) }
    var tick by remember { mutableStateOf(0) }   // naik tiap unduh/pakai selesai -> status unduhan di Koleksi dihitung ulang
    val configured = remember { EventRepository.eventsUrl(context) != null }

    fun refresh(ignoreThrottle: Boolean) {
        if (refreshing) return
        scope.launch {
            refreshing = true
            EventRepository.refresh(context, force = true, ignoreThrottle = ignoreThrottle)
            refreshing = false
        }
    }
    LaunchedEffect(Unit) { EventBackupManager.loadStatus(context); refresh(ignoreThrottle = false) }   // refresh saat tab dibuka (dibatasi 60 dtk)

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        EventBackupManager.markPermissionAsked(context)
        if (granted) scope.launch { EventBackupManager.backupNow(context, force = true) }
        else EventBackupManager.onPermissionDenied(context)
    }
    // API 26-28: izin diminta SEKALI saat fitur backup pertama dipakai (tab dibuka).
    LaunchedEffect(Unit) {
        if (EventBackupManager.needsLegacyPermission() && !EventBackupManager.hasPermission(context) && !EventBackupManager.permissionAsked(context)) {
            permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = when (val r = EventBackupManager.restoreFrom(context, uri)) {
                is EventBackupManager.RestoreResult.Ok ->
                    context.getString(R.string.backup_restore_ok, r.merged) +
                        if (r.skinPending) "\n" + context.getString(R.string.backup_restore_skin_later) else ""
                is EventBackupManager.RestoreResult.Bad ->
                    context.getString(R.string.backup_restore_bad, context.getString(r.resId))
                is EventBackupManager.RestoreResult.Failed ->
                    context.getString(R.string.backup_restore_bad, r.reason)
            }
            selectedSkin = SkinManager.selectedId(context)
        }
    }

    // Konten netral: ripple/efek klik abu-abu, bukan ungu bawaan Material.
    CompositionLocalProvider(LocalContentColor provides PadTextColor) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Baris status sumber data + tombol muat ulang
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (snap.source) {
                    DataSource.ONLINE -> stringResource(R.string.event_source_online)
                    DataSource.CACHE -> stringResource(R.string.event_source_cache)
                    DataSource.BUNDLED -> stringResource(R.string.event_source_bundled)
                    DataSource.NONE -> stringResource(R.string.event_source_none)
                },
                color = TextDim, fontSize = 11.sp, modifier = Modifier.weight(1f)
            )
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                if (refreshing) CircularProgressIndicator(color = ThemeState.accentColor, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                else IconButton(onClick = { refresh(ignoreThrottle = true) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Refresh, stringResource(R.string.event_refresh_desc), tint = TextDim, modifier = Modifier.size(18.dp))
                }
            }
        }
        if (!configured) Text(stringResource(R.string.event_not_configured), color = TextDim, fontSize = 11.sp)
        val now = System.currentTimeMillis()
        val df = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

        // Skin yang SUDAH dibuka = koleksi permanen. Diambil dari progres lokal (bukan dari events.json), jadi tetap ada
        // walau event-nya berakhir / dihapus dari repo, dan semua event yang pernah dibuka tampil bersamaan (multi event).
        val owned = prog.events.entries
            .filter { it.value.status == EventStatus.UNLOCKED && (it.value.claimedSkinId ?: it.value.skinId).isNotEmpty() }
            .sortedByDescending { it.value.joinedAtMs }
        val ownedIds = owned.map { it.key }.toSet()
        // Event yang masih bisa diikuti/diklaim: berjalan & siap klaim di atas, yang berakhir di bawah.
        val listed = snap.events.filter { it.id !in ownedIds }.sortedBy { ev ->
            val st = prog.events[ev.id]?.status ?: EventStatus.IS_LOCKED
            when {
                st == EventStatus.READY_TO_CLAIM -> 0
                ev.hasEnded(now) -> 4
                !ev.hasStarted(now) -> 3
                prog.events[ev.id]?.joined == true -> 1
                else -> 2
            }
        }

        if (listed.isEmpty() && owned.isEmpty() && !refreshing) Text(stringResource(R.string.event_empty), color = TextDim, fontSize = 12.sp)

        // ================= KOLEKSI =================
        if (owned.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${stringResource(R.string.event_collection_title)} (${owned.size})", color = PadTextColor, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(stringResource(R.string.event_collection_desc), color = TextDim, fontSize = 11.sp, lineHeight = 14.sp)
            }
            owned.forEach { (evId, rec) ->
                key(evId) {
                    CollectionCard(
                        eventId = evId, rec = rec, ev = snap.events.firstOrNull { it.id == evId },
                        selectedSkin = selectedSkin, tick = tick,
                        busy = busyEvent == evId, anyBusy = busyEvent != null,
                        onUpgrade = onUpgrade,
                        onUse = { skinId, name ->
                            busyEvent = evId; message = null
                            scope.launch {
                                val r = EventClaimer.useUnlocked(context, skinId, name)
                                message = context.getString(r.resId, r.arg ?: "")
                                selectedSkin = SkinManager.selectedId(context); tick++; busyEvent = null
                            }
                        }
                    )
                }
            }
        }

        // ================= EVENT =================
        if (owned.isNotEmpty()) {
            Text(stringResource(R.string.event_section_events), color = PadTextColor, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (listed.isEmpty()) Text(stringResource(R.string.event_none_other), color = TextDim, fontSize = 12.sp)
        }
        listed.forEach { ev ->
          key(ev.id) {
            val rec = prog.events[ev.id]
            val joined = rec?.joined == true
            val status = rec?.status ?: EventStatus.IS_LOCKED
            val doneCount = ev.quests.count { (prog.quests[it.id] ?: 0) >= it.target }
            val banner by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, ev.id, ev.banner, snap.source) {
                value = withContext(Dispatchers.IO) {
                    EventRepository.ensureBanner(context, ev)?.let { EventRepository.decodeBanner(it, 1000) }?.asImageBitmap()
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().background(CtrlPanelColor, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                banner?.let {
                    Image(it, null, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().aspectRatio(it.width.toFloat() / it.height.toFloat()).clip(RoundedCornerShape(8.dp)))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ev.title, color = PadTextColor, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text("$doneCount/${ev.quests.size}", color = ThemeState.accentColor, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                        modifier = Modifier.background(ThemeState.accentColor.copy(alpha = 0.15f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp))
                }
                // Label status event: SATU chip yang jelas (belum ikut / diikuti / siap klaim / segera / berakhir).
                val (stateRes, stateStrong) = when {
                    status == EventStatus.READY_TO_CLAIM -> R.string.event_state_claim to true
                    ev.hasEnded(now) -> R.string.event_state_ended to false
                    !ev.hasStarted(now) -> R.string.event_state_soon to false
                    joined -> R.string.event_state_joined to false
                    else -> R.string.event_state_open to false
                }
                StatusChip(stringResource(stateRes), strong = stateStrong, dim = ev.hasEnded(now) && status != EventStatus.READY_TO_CLAIM)
                val period = when {
                    ev.hasEnded(now) -> stringResource(R.string.event_period_ended)
                    !ev.hasStarted(now) -> stringResource(R.string.event_period_not_started, df.format(Date(ev.startAtMs!!)))
                    ev.endAtMs != null -> stringResource(R.string.event_period_until, df.format(Date(ev.endAtMs)))
                    else -> null
                }
                if (period != null) Text(period, color = ThemeState.accentColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                // Penjelasan event: selalu tampil (deskripsi dari repo, atau teks cara main bila kosong).
                Text(
                    if (ev.description.isNotBlank()) ev.description else stringResource(R.string.event_howto),
                    color = PadTextColor, fontSize = 12.sp, lineHeight = 16.sp
                )
                HorizontalDivider(color = PadLineColor)
                Text(stringResource(R.string.event_quests_header), color = TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)

                ev.quests.forEach { q ->
                    val cur = minOf(prog.quests[q.id] ?: 0, q.target)
                    val done = cur >= q.target
                    val hint = if (q.description.isNotBlank()) q.description else when (q.type) {
                        QuestType.PAD_HITS -> stringResource(R.string.event_quest_hint_PAD_HITS)
                        QuestType.SESSIONS -> stringResource(R.string.event_quest_hint_SESSIONS)
                        QuestType.RECORDINGS -> stringResource(R.string.event_quest_hint_RECORDINGS)
                        QuestType.PRESETS_SAVED -> stringResource(R.string.event_quest_hint_PRESETS_SAVED)
                        QuestType.ACTIVE_DAYS -> stringResource(R.string.event_quest_hint_ACTIVE_DAYS)
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.fillMaxWidth().background(PadTextColor.copy(alpha = 0.06f), RoundedCornerShape(8.dp)).padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(q.title, color = PadTextColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(8.dp))
                            if (done) Icon(Icons.Filled.Check, null, tint = ThemeState.accentColor, modifier = Modifier.size(14.dp))
                            Text(
                                if (done) stringResource(R.string.event_quest_done)
                                else stringResource(R.string.event_quest_progress, "$cur", "${q.target}"),
                                color = if (done) ThemeState.accentColor else TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = if (done) 3.dp else 0.dp)
                            )
                        }
                        Text(hint, color = TextDim, fontSize = 11.sp, lineHeight = 14.sp)
                        LinearProgressIndicator(
                            progress = { cur.toFloat() / q.target },
                            color = ThemeState.accentColor, trackColor = PadTextColor.copy(alpha = 0.15f),
                            modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50))
                        )
                    }
                }

                // Aksi: Ikut / Klaim (tombol) atau kotak status yang SELALU terlihat (Terkunci / Berakhir).
                val busy = busyEvent == ev.id
                when {
                    status == EventStatus.READY_TO_CLAIM && !BuildConfig.IS_PREMIUM -> ActionButton(stringResource(R.string.event_btn_premium), strong = false, busy = false, enabled = true) { onUpgrade() }
                    status == EventStatus.READY_TO_CLAIM -> ActionButton(stringResource(R.string.event_btn_claim), strong = true, busy = busy, enabled = busyEvent == null) {
                        busyEvent = ev.id; message = null
                        scope.launch {
                            val r = EventClaimer.claim(context, ev)
                            message = context.getString(r.resId, r.arg ?: "")
                            selectedSkin = SkinManager.selectedId(context); tick++; busyEvent = null
                        }
                    }
                    !joined && ev.hasEnded(now) -> StatusBox(stringResource(R.string.event_btn_ended))
                    !joined && !ev.hasStarted(now) -> StatusBox(stringResource(R.string.event_state_soon))
                    !joined -> ActionButton(stringResource(R.string.event_btn_join), strong = true, busy = false, enabled = busyEvent == null) {
                        if (!EventProgressManager.joinEvent(ev.id)) message = context.getString(R.string.event_join_failed)
                    }
                    else -> StatusBox(stringResource(R.string.event_btn_locked, doneCount, ev.quests.size))
                }
            }
          }
        }

        message?.let { Text(it, color = PadTextColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }

        // ---- Backup & pemulihan ----
        Column(
            modifier = Modifier.fillMaxWidth().background(CtrlPanelColor, RoundedCornerShape(12.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(R.string.backup_title), color = PadTextColor, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(stringResource(R.string.backup_desc), color = TextDim, fontSize = 11.sp, lineHeight = 14.sp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (backup.lastBackupMs > 0) stringResource(R.string.backup_last, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(backup.lastBackupMs)))
                    else stringResource(R.string.backup_never),
                    color = PadTextColor, fontSize = 11.sp
                )
                Text(stringResource(R.string.backup_folder, backup.folder), color = TextDim, fontSize = 10.sp)
                if (backup.usedFallbackFolder) Text(stringResource(R.string.backup_folder_fallback), color = TextDim, fontSize = 10.sp)
                if (backup.msgRes != 0) {
                    Text(if (backup.msgArg != null) stringResource(backup.msgRes, backup.msgArg!!) else stringResource(backup.msgRes),
                        color = PadTextColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            HorizontalDivider(color = PadLineColor)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.backup_auto), color = PadTextColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.backup_auto_desc), color = TextDim, fontSize = 11.sp, lineHeight = 14.sp)
                }
                Spacer(Modifier.width(8.dp))
                // Warna sama dengan toggle lain di app (PadVolumeDialog): aksen app, bukan ungu bawaan.
                Switch(
                    checked = backup.autoEnabled,
                    onCheckedChange = { EventBackupManager.setAutoEnabled(context, it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ThemeState.accentColor,
                        checkedTrackColor = ThemeState.accentColor.copy(alpha = 0.5f),
                        uncheckedThumbColor = TextDim,
                        uncheckedTrackColor = PadTextColor.copy(alpha = 0.15f),
                        uncheckedBorderColor = TextDim.copy(alpha = 0.5f)
                    )
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        if (!EventBackupManager.hasPermission(context)) permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        else scope.launch { EventBackupManager.backupNow(context, force = true) }
                    },
                    enabled = !backup.busy,
                    colors = ButtonDefaults.buttonColors(containerColor = ThemeState.accentColor, contentColor = CtrlPanelColor),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.weight(1f).height(42.dp)
                ) { Text(stringResource(R.string.backup_now), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, textAlign = TextAlign.Center, lineHeight = 13.sp) }
                OutlinedButton(
                    onClick = { restoreLauncher.launch(arrayOf("*/*")) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = PadTextColor),
                    border = BorderStroke(1.dp, PadTextColor.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.weight(1f).height(42.dp)
                ) { Text(stringResource(R.string.backup_restore), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, textAlign = TextAlign.Center, lineHeight = 13.sp) }
            }
        }
    }
    }
}


// ------------------------------------------------------------------ komponen kecil (selalu terlihat, tidak pernah "hilang" ke warna latar)

/** Chip status kecil. strong = isi aksen penuh; dim = redup (mis. event berakhir); selain itu outline aksen. */
@Composable
private fun StatusChip(text: String, strong: Boolean = false, dim: Boolean = false, color: Color? = null) {
    val c = color ?: if (dim) TextDim else ThemeState.accentColor
    val shape = RoundedCornerShape(50)
    Text(
        text, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        color = if (strong) CtrlPanelColor else c,
        modifier = Modifier
            .then(if (strong) Modifier.background(c, shape) else Modifier.border(1.dp, c.copy(alpha = 0.7f), shape))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    )
}

/** Kotak status (bukan tombol): outline + teks, tinggi sama dengan tombol supaya kartu tidak melompat. */
@Composable
private fun StatusBox(text: String, accent: Boolean = false, showCheck: Boolean = false) {
    val c = if (accent) ThemeState.accentColor else TextDim
    val shape = RoundedCornerShape(10.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().height(42.dp)
            .background(c.copy(alpha = if (accent) 0.12f else 0.06f), shape)
            .border(1.dp, c.copy(alpha = if (accent) 0.9f else 0.35f), shape)
    ) {
        if (showCheck) { Icon(Icons.Filled.Check, null, tint = c, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, color = c, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ActionButton(label: String, strong: Boolean, busy: Boolean, enabled: Boolean, icon: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (strong) ThemeState.accentColor else PadTextColor.copy(alpha = 0.14f),
            contentColor = if (strong) CtrlPanelColor else PadTextColor,
            disabledContainerColor = PadTextColor.copy(alpha = 0.08f),
            disabledContentColor = TextDim
        ),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        modifier = Modifier.fillMaxWidth().height(42.dp)
    ) {
        if (busy) CircularProgressIndicator(
            color = if (strong) CtrlPanelColor else ThemeState.accentColor,
            strokeWidth = 2.dp, modifier = Modifier.size(16.dp)
        )
        else {
            if (icon) { Icon(Icons.Filled.CloudDownload, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Satu skin event yang sudah dimiliki. Status SELALU eksplisit:
 *  Sedang dipakai | Belum diunduh | Ada update | Siap dipakai  (+ tombol yang sesuai, atau kotak "Sedang dipakai").
 * [ev] = definisi event bila masih ada di repo (untuk banner/judul); null bila event sudah dihapus dari repo - koleksi tetap tampil.
 */
@Composable
private fun CollectionCard(
    eventId: String, rec: EventRecord, ev: GameEvent?, selectedSkin: String, tick: Int,
    busy: Boolean, anyBusy: Boolean, onUpgrade: () -> Unit, onUse: (skinId: String, name: String) -> Unit
) {
    val context = LocalContext.current
    val skinId = rec.claimedSkinId ?: rec.skinId
    val downloaded = remember(tick, skinId) { SkinRepository.isDownloaded(context, skinId) }
    val entry = remember(tick, skinId) { SkinRepository.cachedEntries(context).firstOrNull { it.id == skinId } }
    val needsUpdate = remember(tick, skinId, downloaded) {
        downloaded && entry != null && SkinRepository.cachedVersion(context, skinId).let { it != null && it != entry.version }
    }
    val fallback = stringResource(R.string.event_collection_unknown)
    val title = ev?.title ?: rec.title.ifBlank { entry?.name ?: fallback }
    val isActive = selectedSkin == skinId

    // Banner utuh (rasio aslinya, tidak dipotong jadi kotak). Event hilang dari repo -> pakai banner yang sudah tersimpan.
    val banner by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, rec.title, ev?.id, ev?.banner) {
        value = withContext(Dispatchers.IO) {
            val f = if (ev != null) EventRepository.ensureBanner(context, ev) else null
            (f ?: EventRepository.cachedBanner(context, eventId))?.let { EventRepository.decodeBanner(it, 1000) }?.asImageBitmap()
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(CtrlPanelColor, RoundedCornerShape(12.dp))
            .then(if (isActive) Modifier.border(1.5.dp, ThemeState.accentColor, RoundedCornerShape(12.dp)) else Modifier)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        banner?.let {
            Image(it, null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(it.width.toFloat() / it.height.toFloat()).clip(RoundedCornerShape(8.dp)))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = PadTextColor, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            when {
                isActive -> StatusChip(stringResource(R.string.event_status_in_use), strong = true)
                !downloaded -> StatusChip(stringResource(R.string.event_status_not_downloaded), color = Color(0xFFE0A030))
                needsUpdate -> StatusChip(stringResource(R.string.event_status_update), color = Color(0xFFE0A030))
                else -> StatusChip(stringResource(R.string.event_status_ready))
            }
        }
        when {
            isActive -> StatusBox(stringResource(R.string.event_btn_in_use), accent = true, showCheck = true)
            !BuildConfig.IS_PREMIUM -> ActionButton(stringResource(R.string.event_btn_premium), strong = false, busy = false, enabled = true) { onUpgrade() }
            !downloaded -> ActionButton(stringResource(R.string.event_btn_download_use), strong = true, busy = busy, enabled = !anyBusy, icon = true) { onUse(skinId, title) }
            needsUpdate -> ActionButton(stringResource(R.string.event_btn_update_use), strong = true, busy = busy, enabled = !anyBusy, icon = true) { onUse(skinId, title) }
            else -> ActionButton(stringResource(R.string.event_btn_use), strong = true, busy = busy, enabled = !anyBusy) { onUse(skinId, title) }
        }
    }
}
