package com.kendang.realpads

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Nilai default (dipakai tombol reset per baris di dialog ini).
private const val DEFAULT_PAD_VOLUME = 1f
private const val DEFAULT_PAD_TUNE = 0f
private const val DEFAULT_PAD_CHOKE = true
private const val DEFAULT_PAD_LOOP = false
private const val DEFAULT_PAD_CROSS_CHOKE_ENABLED = false
private const val DEFAULT_PAD_CROSS_CHOKE_MASK = 0

// Dialog "Pengaturan": per pad (P1..P12) di preset yang lagi aktif, ada 2 slider -
// Volume dan Tune (semitone). Semua berubah
// langsung kedengeran efeknya (callback dipanggil live tiap geser, gak nunggu tombol
// konfirmasi) - konsisten sama slider volume kendang/musik yang udah ada. Tiap baris
// juga punya ikon reset sendiri (Icons.Filled.RestartAlt) buat balikin nilai itu ke
// default dengan sekali tap, gak perlu geser manual slider-nya pelan-pelan.
@Composable
fun PadVolumeDialog(
    preset: Int,
    padVolumes: Map<Int, Float>,
    padTunes: Map<Int, Float>,
    // Choke per pad (true = default aktif). Lihat catatan panjang di
    // PadEngine.setPadChoke/PadChokeStorage buat kenapa ini ada: fitur "potong
    // otomatis" (pukulan baru motong pukulan lama di pad yang sama) berguna buat
    // roll yang rapi, tapi bisa kedengeran "tet" (klik) kalau sample-nya masih
    // nyisa ekor pas dipotong paksa - jadi bisa dimatiin per pad.
    padChokes: Map<Int, Boolean>,
    // Loop per pad (default false, gaya DTX M12) - lihat catatan panjang di
    // PadEngine.setPadLoop: kalau aktif, sekali ketuk pad-nya muter sample itu
    // berulang tanpa jeda sampai diketuk lagi buat berhenti.
    padLoops: Map<Int, Boolean>,
    // "Stop" - choke ANTAR pad (fitur baru, beda dari padChokes di atas yang
    // choke SESAMA pad): padCrossChokeEnabled = switch on/off per pad,
    // padCrossChokeMask = bitmask 12-bit per pad (bit (targetPad-1) = target
    // yang tercentang) - lihat catatan panjang di PadCrossChokeStorage/
    // PadEngine.setPadCrossChokeMask buat detail lengkapnya.
    padCrossChokeEnabled: Map<Int, Boolean>,
    padCrossChokeMask: Map<Int, Int>,
    glerEnabled: Boolean,
    onGlerEnabledChange: (Boolean) -> Unit,
    // Geser pindah pad (glide/roll) - lihat catatan panjang di PadGrid & di
    // PadSlideRollState. Default OFF - lihat PadSlideRollState buat alasannya.
    slideRollEnabled: Boolean,
    onSlideRollEnabledChange: (Boolean) -> Unit,
    // "Pad Tambahan" (+4 pad, lihat ExtraPadState) - switch utama on/off, plus
    // scope-nya: applyToAll=true berarti aktif di SEMUA preset (P1-P8), false
    // berarti cuma preset yang dicentang user sendiri lewat
    // isExtraPadPresetSelected/onExtraPadPresetToggle. extraPadsActiveNow =
    // apakah preset yang LAGI DIBUKA (parameter `preset` di atas) SEKARANG
    // nampilin pad13-16 atau enggak - dipakai buat nentuin sampai pad berapa
    // baris PAD $pad di bawah harus digambar (lihat visiblePadCount).
    extraPadEnabled: Boolean,
    onExtraPadEnabledChange: (Boolean) -> Unit,
    extraPadApplyToAll: Boolean,
    // Jumlah slot preset yang lagi ada (1..50) - buat daftar centang "Preset Tertentu".
    presetCount: Int,
    onExtraPadApplyToAllChange: (Boolean) -> Unit,
    isExtraPadPresetSelected: (preset: Int) -> Boolean,
    onExtraPadPresetToggle: (preset: Int, checked: Boolean) -> Unit,
    extraPadsActiveNow: Boolean,
    // Mode skin (Stage 07): non-null = skin aktif TIDAK mendukung pilihan 12/16 -> switch dinonaktifkan + pesan ini.
    // visiblePadCountOverride = jumlah pad yang ditampilkan skin (null = perilaku UI klasik, tidak berubah).
    extraPadUnsupportedMessage: String? = null,
    visiblePadCountOverride: Int? = null,
    onVolumeChange: (pad: Int, volume: Float) -> Unit,
    onTuneChange: (pad: Int, semitones: Float) -> Unit,
    onChokeChange: (pad: Int, enabled: Boolean) -> Unit,
    onLoopChange: (pad: Int, enabled: Boolean) -> Unit,
    onCrossChokeEnabledChange: (pad: Int, enabled: Boolean) -> Unit,
    onCrossChokeMaskChange: (pad: Int, mask: Int) -> Unit,
    // Binding tombol keyboard fisik (USB/wireless) per pad - pad -> Android
    // KeyEvent.keyCode. Lihat PadKeyBindingStorage & KeyBindRecorder buat detail
    // gimana proses "rekam" 1 tombol fisik bekerja lewat MainActivity.dispatchKeyEvent.
    keyBindings: Map<Int, Int>,
    // Nomor pad yang lagi nunggu ditekan 1 tombol fisik buat direkam, null kalau
    // gak ada yang lagi dalam mode rekam.
    recordingPad: Int?,
    onStartKeyRecording: (pad: Int) -> Unit,
    onCancelKeyRecording: () -> Unit,
    onResetKeyBinding: (pad: Int) -> Unit,
    onResetAllKeyBindings: () -> Unit,
    // Dipanggil begitu 1 tombol fisik BERHASIL ketangkep selagi mode rekam aktif -
    // lihat Modifier.onKeyEvent di bawah buat kenapa ini gak lagi lewat
    // Activity.dispatchKeyEvent (dialog ini window Android terpisah, key event
    // gak nembus ke situ selama dialog fokus).
    onKeyCaptured: (pad: Int, keyCode: Int) -> Unit,
    // Binding note MIDI per pad (pad -> nomor note 0..127), lihat MidiBindingStorage.
    // Berbeda dari keyboard, note MIDI ditangkep di MidiInputManager (bukan lewat
    // key event dialog), jadi di sini cuma nampilin & nge-toggle mode rekam.
    midiBindings: Map<Int, Int>,
    midiRecordingPad: Int?,
    onStartMidiRecording: (pad: Int) -> Unit,
    onCancelMidiRecording: () -> Unit,
    onResetMidiBinding: (pad: Int) -> Unit,
    onVelocityEnabledChange: (Boolean) -> Unit,
    // Slider kepekaan velocity (0..1): onChange dipanggil terus selama digeser,
    // onChangeFinished sekali pas jari dilepas (buat nyimpan ke disk).
    onVelocitySensitivityChange: (Float) -> Unit,
    onVelocitySensitivityChangeFinished: () -> Unit,
    // Scan BLE MIDI: izin Bluetooth diurus pemanggil (MainActivity). State-nya
    // (lagi scan, perangkat ketemu, status) dibaca langsung dari MidiInputManager.
    onBleScanToggle: () -> Unit,
    onBleConnect: (address: String) -> Unit,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelColor,
        title = { Text("Pengaturan Pad - P$preset", color = PadTextColor, fontWeight = FontWeight.Bold) },
        text = {
            // Dialog Compose (AlertDialog) itu window Android TERPISAH dari
            // Activity begitu kebuka - jadi key event fisik gak akan pernah
            // nembus ke MainActivity.dispatchKeyEvent selama dialog ini fokus.
            // Makanya penangkapan tombol buat "rekam" HARUS dilakukan di sini,
            // di dalam compose tree dialog itu sendiri, lewat Modifier.onKeyEvent
            // yang dipasang ke node yang lagi pegang fokus (focusRequester di
            // bawah). Fokus di-minta ulang tiap kali mode rekam pad baru mulai,
            // biar gak kehilangan fokus gara-gara sempat nyentuh slider/tombol
            // lain di antara buka dialog dan mulai rekam.
            val dialogFocusRequester = remember { FocusRequester() }
            LaunchedEffect(recordingPad) {
                if (recordingPad != null) dialogFocusRequester.requestFocus()
            }

            Column(
                modifier = Modifier
                    .width(280.dp)
                    .heightIn(max = 460.dp)
                    .verticalScroll(scrollState)
                    .padding(end = 10.dp)
                    .focusRequester(dialogFocusRequester)
                    .onKeyEvent { keyEvent ->
                        val pad = recordingPad
                        if (pad != null && keyEvent.type == KeyEventType.KeyDown) {
                            // keyEvent.key.nativeKeyCode -> nativeKeyCode itu properti
                            // milik Key (androidx.compose.ui.input.key.Key), bukan
                            // langsung punya KeyEvent -> harus lewat .key dulu.
                            val code = keyEvent.key.nativeKeyCode
                            if (isBindableKeyCode(code)) {
                                onKeyCaptured(pad, code)
                                true // konsumsi - tombol yang berhasil kerekam
                            } else {
                                false // tombol sistem terlarang (back dsb) - biarin lewat normal
                            }
                        } else {
                            false
                        }
                    }
                    .focusable()
            ) {
                // Toggle efek GLER (layar bergetar pas mukul pad nada rendah/kick) -
                // biar user yang gak suka efeknya bisa matiin sesuai selera, tanpa
                // ngilangin deteksi bass otomatis di pad-nya sendiri.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Efek GLER",
                            color = PadTextColor,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            "Layar bergetar pas mukul pad bass/kick",
                            color = TextDim,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = glerEnabled,
                        onCheckedChange = onGlerEnabledChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = ThemeState.accentColor,
                            checkedTrackColor = ThemeState.accentColor.copy(alpha = 0.5f)
                        )
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = PadLineColor)
                Spacer(Modifier.height(12.dp))

                // Toggle "geser pindah pad" (glide/roll) - lihat catatan panjang di
                // PadGrid soal gimana ini bekerja. Default OFF (lihat PadSlideRollState)
                // biar geser jari yang gak sengaja gak ikut mindah/mukul pad lain,
                // kecuali user sendiri yang mau gaya main roll ini.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Geser Pindah Pad",
                            color = PadTextColor,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            "Geser jari lintas pad ikut bunyi (roll), tanpa angkat jari",
                            color = TextDim,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = slideRollEnabled,
                        onCheckedChange = onSlideRollEnabledChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = ThemeState.accentColor,
                            checkedTrackColor = ThemeState.accentColor.copy(alpha = 0.5f)
                        )
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = PadLineColor)
                Spacer(Modifier.height(12.dp))

                // Sistem Audio (v1.9.0): pilih jalur keluar audio ke hardware. Berlaku langsung,
                // tanpa restart app, dan dipilih otomatis kalau Oboe gagal di HP ini.
                AudioBackendSection()

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = PadLineColor)
                Spacer(Modifier.height(12.dp))

                // Toggle "Pad Tambahan" - nambah 4 pad ekstra (pad13-16) ke
                // PadGrid, di atas 12 pad bawaan (lihat catatan panjang di
                // ExtraPadState.kt). Beda dari switch lain di atas: fitur ini
                // punya pengaturan SCOPE tambahan (semua preset vs preset
                // tertentu) yang cuma muncul begitu switch-nya dinyalain.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Pad Tambahan (+4)",
                            color = PadTextColor,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            extraPadUnsupportedMessage ?: "Tambah 4 pad ekstra (PAD 13-16) di grid",
                            color = TextDim,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = extraPadEnabled && extraPadUnsupportedMessage == null,
                        onCheckedChange = onExtraPadEnabledChange,
                        enabled = extraPadUnsupportedMessage == null,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = ThemeState.accentColor,
                            checkedTrackColor = ThemeState.accentColor.copy(alpha = 0.5f)
                        )
                    )
                }

                if (extraPadEnabled && extraPadUnsupportedMessage == null) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        Text(
                            "Berlaku untuk:",
                            color = TextDim,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ScopeOptionChip(
                                label = "Semua Preset",
                                selected = extraPadApplyToAll,
                                onTap = { onExtraPadApplyToAllChange(true) },
                                modifier = Modifier.weight(1f)
                            )
                            ScopeOptionChip(
                                label = "Preset Tertentu",
                                selected = !extraPadApplyToAll,
                                onTap = { onExtraPadApplyToAllChange(false) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        // Checklist P1..P8 - cuma muncul kalau scope-nya "Preset
                        // Tertentu" (bukan "Semua Preset"), biar user pilih
                        // sendiri preset mana yang mau dikasih pad tambahan.
                        if (!extraPadApplyToAll) {
                            Column(
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .fillMaxWidth()
                            ) {
                                (1..presetCount).chunked(4).forEach { rowPresets ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        rowPresets.forEach { p ->
                                            val checked = isExtraPadPresetSelected(p)
                                            ChokeTargetChip(
                                                pad = p,
                                                checked = checked,
                                                onToggle = { onExtraPadPresetToggle(p, !checked) },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = PadLineColor)
                Spacer(Modifier.height(12.dp))

                // Header section tombol keyboard fisik + tombol reset SEMUA
                // binding sekaligus, biar user gak perlu reset satu-satu pas mau
                // mulai ulang dari kosong (mis. abis ganti keyboard fisik).
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Keyboard,
                        contentDescription = null,
                        tint = TextDim,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Tombol Keyboard Fisik",
                        color = PadTextColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "RESET SEMUA",
                        color = ThemeState.accentColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                        modifier = Modifier
                            // onTap (bukan onPress) - onPress kepicu begitu jari
                            // NYENTUH layar, sebelum tau bakal jadi tap beneran atau
                            // geser. Column di dialog ini bisa di-scroll, jadi kalau
                            // pake onPress, geser jari buat scroll aja udah keanggep
                            // "tap" ke tombol ini. onTap cuma kepicu kalau jari
                            // BENERAN naik lagi di tempat yang sama (tap bersih).
                            .pointerInput(Unit) { detectTapGestures(onTap = { onResetAllKeyBindings() }) }
                            .padding(4.dp)
                    )
                }
                Text(
                    "Tap kotak Key lalu tekan 1 tombol di keyboard USB/wireless-mu, atau tap kotak MIDI lalu pukul pad di perangkat MIDI-mu buat direkam",
                    color = TextDim,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
                )
                Text(
                    text = MidiInputManager.connectedNames.let { names ->
                        if (names.isEmpty()) "MIDI: belum ada perangkat terhubung (colok lewat OTG)"
                        else "MIDI terhubung: " + names.joinToString(", ")
                    },
                    color = if (MidiInputManager.connectedNames.isEmpty()) TextDim else ThemeState.accentColor,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                MidiSettingsSection(
                    velocityEnabled = MidiSettings.velocityEnabled,
                    onVelocityEnabledChange = onVelocityEnabledChange,
                    sensitivity = MidiSettings.sensitivity,
                    onSensitivityChange = onVelocitySensitivityChange,
                    onSensitivityChangeFinished = onVelocitySensitivityChangeFinished,
                    bleScanning = MidiInputManager.bleScanning,
                    bleFound = MidiInputManager.bleFound,
                    bleStatus = MidiInputManager.bleStatus,
                    onBleScanToggle = onBleScanToggle,
                    onBleConnect = onBleConnect
                )

                Spacer(Modifier.height(4.dp))
                HorizontalDivider(color = PadLineColor)
                Spacer(Modifier.height(12.dp))

                // Cuma sampai pad yang BENERAN keliatan di grid preset ini
                // sekarang (12, atau 16 kalau "Pad Tambahan" lagi aktif buat
                // preset ini - lihat extraPadsActiveNow) - biar gak nampilin
                // baris "PAD 13..16" buat preset yang pad tambahannya lagi
                // disembunyikan (data pad 13-16 tetap aman kesimpen, cuma gak
                // ditawarin diatur di sini selama gak keliatan di grid).
                val visiblePadCount = visiblePadCountOverride
                    ?: if (extraPadsActiveNow) PresetStorage.MAX_PAD else PresetStorage.BASE_PAD
                (1..visiblePadCount).forEach { pad ->
                    val volume = padVolumes[pad] ?: DEFAULT_PAD_VOLUME
                    val tune = padTunes[pad] ?: DEFAULT_PAD_TUNE
                    val choke = padChokes[pad] ?: DEFAULT_PAD_CHOKE
                    val loop = padLoops[pad] ?: DEFAULT_PAD_LOOP
                    val crossChokeEnabled = padCrossChokeEnabled[pad] ?: DEFAULT_PAD_CROSS_CHOKE_ENABLED
                    val crossChokeMask = padCrossChokeMask[pad] ?: DEFAULT_PAD_CROSS_CHOKE_MASK

                    Text(
                        "PAD $pad",
                        color = TextDim,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                    )

                    PadParamRow(
                        label = "Vol",
                        value = volume,
                        valueRange = 0f..1.5f,
                        valueLabel = "${(volume * 100).toInt()}%",
                        onValueChange = { onVolumeChange(pad, it) },
                        onReset = { onVolumeChange(pad, DEFAULT_PAD_VOLUME) },
                        resetDescription = "Reset volume PAD $pad ke default"
                    )
                    PadParamRow(
                        label = "Tune",
                        value = tune,
                        valueRange = -12f..12f,
                        valueLabel = (if (tune > 0f) "+" else "") + "${tune.toInt()}st",
                        onValueChange = { onTuneChange(pad, it) },
                        onReset = { onTuneChange(pad, DEFAULT_PAD_TUNE) },
                        resetDescription = "Reset tune PAD $pad ke default"
                    )
                    PadChokeRow(
                        pad = pad,
                        enabled = choke,
                        onEnabledChange = { onChokeChange(pad, it) }
                    )
                    PadCrossChokeRow(
                        pad = pad,
                        visiblePadCount = visiblePadCount,
                        enabled = crossChokeEnabled,
                        mask = crossChokeMask,
                        onEnabledChange = { onCrossChokeEnabledChange(pad, it) },
                        onTargetToggle = { targetPad, checked ->
                            val newMask = if (checked) {
                                crossChokeMask or (1 shl (targetPad - 1))
                            } else {
                                crossChokeMask and (1 shl (targetPad - 1)).inv()
                            }
                            onCrossChokeMaskChange(pad, newMask)
                        }
                    )
                    PadLoopRow(
                        pad = pad,
                        enabled = loop,
                        onEnabledChange = { onLoopChange(pad, it) }
                    )
                    PadKeyBindRow(
                        pad = pad,
                        boundKeyCode = keyBindings[pad],
                        isRecording = recordingPad == pad,
                        onStartRecording = { onStartKeyRecording(pad) },
                        onCancelRecording = onCancelKeyRecording,
                        onReset = { onResetKeyBinding(pad) }
                    )
                    // Jarak antara kotak rekam Key & MIDI biar gak nempel/gabung
                    Spacer(Modifier.height(8.dp))
                    PadKeyBindRow(
                        pad = pad,
                        boundKeyCode = midiBindings[pad],
                        isRecording = midiRecordingPad == pad,
                        onStartRecording = { onStartMidiRecording(pad) },
                        onCancelRecording = onCancelMidiRecording,
                        onReset = { onResetMidiBinding(pad) },
                        label = "MIDI",
                        recordingText = "Pukul pad MIDI... (tap buat batal)",
                        displayName = ::midiNoteDisplayName
                    )

                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider(color = PadLineColor.copy(alpha = 0.4f))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ThemeState.accentColor,
                    contentColor = ThemeState.accentTextColor
                )
            ) {
                Text("TUTUP", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
        }
    )
}

// 1 baris toggle "Pot" (Potong) per pad - kontrol choke/retrigger (lihat catatan panjang
// di PadEngine.setPadChoke). Tampilannya dibikin senada sama PadParamRow (label
// pendek di kiri, area kanan buat kontrolnya) biar satu keluarga sama baris
// Vol/Tune/Rev di atasnya, cuma kontrolnya Switch alih-alih Slider karena ini
// on/off, bukan rentang nilai.
@Composable
private fun PadChokeRow(
    pad: Int,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Pot",
            color = TextDim,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            modifier = Modifier.width(34.dp)
        )
        Text(
            "Pukulan baru motong pukulan lama di pad ini",
            color = TextDim,
            fontSize = 10.sp,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange,
            modifier = Modifier.scale(0.75f),
            colors = SwitchDefaults.colors(
                checkedThumbColor = ThemeState.accentColor,
                checkedTrackColor = ThemeState.accentColor.copy(alpha = 0.5f)
            )
        )
    }
}

// 1 baris toggle "Stop" (choke ANTAR PAD, fitur baru) - beda dari PadChokeRow di
// atas: PadChokeRow motong pukulan lama-baru DI PAD YANG SAMA, sedangkan ini
// bikin pad ini BERHENTI OTOMATIS kalau salah satu pad LAIN yang dicentang user
// dipukul (mis. pad 1 lagi bunyi, pad 2 dipukul, pad 1 langsung berhenti - kalau
// pad 2 dicentang di daftar pad 1). Begitu switch-nya diaktifkan, langsung muncul
// checklist semua pad LAIN (P1..P12 kecuali pad ini sendiri) tersusun grid 4
// kolom, tiap tap toggle centang satu pad target lewat onTargetToggle.
@Composable
private fun PadCrossChokeRow(
    pad: Int,
    // Sampai pad berapa checklist target di bawah ditawarin - ikut sama batas
    // yang lagi keliatan di grid (lihat visiblePadCount di PadVolumeDialog),
    // bukan selalu PresetStorage.MAX_PAD, biar gak nawarin centang ke pad
    // tambahan yang lagi disembunyikan.
    visiblePadCount: Int,
    enabled: Boolean,
    mask: Int,
    onEnabledChange: (Boolean) -> Unit,
    onTargetToggle: (targetPad: Int, checked: Boolean) -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Stop",
                color = TextDim,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                modifier = Modifier.width(34.dp)
            )
            Text(
                "Berhenti otomatis kalau pad yang dicentang di bawah dipukul",
                color = TextDim,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                modifier = Modifier.scale(0.75f),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = ThemeState.accentColor,
                    checkedTrackColor = ThemeState.accentColor.copy(alpha = 0.5f)
                )
            )
        }
        if (enabled) {
            val otherPads = (1..visiblePadCount).filter { it != pad }
            Column(
                modifier = Modifier
                    .padding(start = 34.dp, top = 4.dp, bottom = 2.dp)
                    .fillMaxWidth()
            ) {
                otherPads.chunked(4).forEach { rowPads ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        rowPads.forEach { targetPad ->
                            val checked = (mask and (1 shl (targetPad - 1))) != 0
                            ChokeTargetChip(
                                pad = targetPad,
                                checked = checked,
                                onToggle = { onTargetToggle(targetPad, !checked) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        // Isi slot kosong kalau baris terakhir kurang dari 4 pad, biar
                        // chip yang ADA tetep sama lebar (gak melebar nutupin sisa baris).
                        repeat(4 - rowPads.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

// 1 opsi (dari 2, saling eksklusif) buat scope "Pad Tambahan" - "Semua Preset
// (P1-P8)" vs "Preset Tertentu". Tampilannya mirip tab/segmented-control: yang
// lagi terpilih (selected=true) dikasih latar solid warna aksen, yang lain abu-
// abu redup - beda dari ChokeTargetChip di bawah (checklist multi-pilih), ini
// cuma boleh 1 yang aktif dalam satu waktu (klik salah satu otomatis
// "matiin" pilihan sebelahnya lewat onExtraPadApplyToAllChange di pemanggil).
@Composable
private fun ScopeOptionChip(
    label: String,
    selected: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(
                if (selected) ThemeState.accentColor else CtrlPanelColor,
                RoundedCornerShape(4.dp)
            )
            .clickable(onClick = onTap)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) ThemeState.accentTextColor else TextDim,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

// 1 chip kecil "P{n}" buat 1 pad target di checklist PadCrossChokeRow - tap buat
// toggle centang/hapus centang. Latar solid warna aksen kalau tercentang (kontras
// jelas terlihat), abu-abu redup kalau enggak.
@Composable
private fun ChokeTargetChip(
    pad: Int,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(
                if (checked) ThemeState.accentColor else CtrlPanelColor,
                RoundedCornerShape(4.dp)
            )
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "P$pad",
            color = if (checked) ThemeState.accentTextColor else TextDim,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
// pad-nya muter sample itu berulang TANPA JEDA sampai pad yang sama diketuk lagi
// buat berhenti (lihat catatan panjang di PadEngine.setPadLoop soal gimana
// "ketuk buat stop"-nya diimplementasi di native, terpisah dari choke di atas).
@Composable
private fun PadLoopRow(
    pad: Int,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Loop",
            color = TextDim,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            modifier = Modifier.width(34.dp)
        )
        Text(
            "Ketuk buat muter berulang, ketuk lagi buat berhenti",
            color = TextDim,
            fontSize = 10.sp,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange,
            modifier = Modifier.scale(0.75f),
            colors = SwitchDefaults.colors(
                checkedThumbColor = ThemeState.accentColor,
                checkedTrackColor = ThemeState.accentColor.copy(alpha = 0.5f)
            )
        )
    }
}

// 1 baris buat binding tombol keyboard fisik per pad - tampilannya sengaja
// dibikin senada sama PadParamRow (label pendek kiri, area utama tengah, tombol
// reset kanan) biar keliatan satu keluarga UI yang sama, walau di sini area
// tengahnya kotak yang bisa di-tap buat "rekam" (bukan slider).
@Composable
private fun PadKeyBindRow(
    pad: Int,
    boundKeyCode: Int?,
    isRecording: Boolean,
    onStartRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onReset: () -> Unit,
    // Dipakai bareng buat baris "Key" (keyboard) dan "MIDI" - default-nya
    // perilaku keyboard yang lama, jadi pemanggil lama gak berubah.
    label: String = "Key",
    recordingText: String = "Tekan tombol... (tap buat batal)",
    displayName: (Int) -> String = ::keyCodeDisplayName
) {
    // PENTING: Modifier.pointerInput(pad) di bawah cuma direstart kalau `pad`
    // berubah - BUKAN tiap kali isRecording berubah (pad-nya tetap sama dari
    // tap pertama ke tap kedua). Kalau onPress langsung nutup closure ke
    // `isRecording`/callback biasa, tap KEDUA (yang harusnya "batal") bakal
    // baca status yang UDAH BASI dari komposisi pas tap pertama - ini persis
    // bug "tap buat batal gak jalan". rememberUpdatedState nge-fix ini: closure
    // di dalam pointerInput SELALU baca nilai state PALING BARU pas beneran
    // dieksekusi, walau coroutine gesture-nya sendiri gak pernah direstart.
    val currentIsRecording by rememberUpdatedState(isRecording)
    val currentOnStart by rememberUpdatedState(onStartRecording)
    val currentOnCancel by rememberUpdatedState(onCancelRecording)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = TextDim,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            modifier = Modifier.width(34.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .background(
                    if (isRecording) ThemeState.accentColor.copy(alpha = 0.22f) else CtrlPanelColor,
                    RoundedCornerShape(4.dp)
                )
                .border(
                    width = 1.dp,
                    color = if (isRecording) ThemeState.accentColor else PadLineColor,
                    shape = RoundedCornerShape(4.dp)
                )
                .pointerInput(pad) {
                    // onTap, bukan onPress - biar geser jari buat scroll daftar pad
                    // gak keanggep "tap" mulai/batalin rekam. onTap cuma nyala kalau
                    // jari beneran turun-naik di tempat yang sama (tap murni), sesuai
                    // touch slop bawaan gesture detector - gesture yang bergerak
                    // (drag/scroll) otomatis GAK dihitung sebagai tap sama sekali.
                    detectTapGestures(onTap = {
                        if (currentIsRecording) currentOnCancel() else currentOnStart()
                    })
                }
                .padding(vertical = 7.dp, horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = when {
                    isRecording -> recordingText
                    boundKeyCode != null -> displayName(boundKeyCode)
                    else -> "Belum diatur - tap buat rekam"
                },
                color = if (isRecording) ThemeState.accentColor else PadTextColor,
                fontSize = 11.sp,
                maxLines = 1
            )
        }
        IconButton(
            onClick = onReset,
            modifier = Modifier.size(26.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.RestartAlt,
                contentDescription = "Reset tombol PAD $pad",
                tint = TextDim,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// 1 baris slider dipakai bareng buat Volume/Tune di PadVolumeDialog - biar
// keduanya konsisten tampilannya (label pendek di kiri, slider di tengah, nilai +
// tombol reset di kanan) tanpa nulis Row yang sama 2x dengan cuma beda parameter.
@Composable
private fun PadParamRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onValueChange: (Float) -> Unit,
    onReset: () -> Unit,
    resetDescription: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = TextDim,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            modifier = Modifier.width(34.dp)
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = ThemeState.accentColor,
                activeTrackColor = ThemeState.accentColor,
                inactiveTrackColor = CtrlPanelColor
            ),
            modifier = Modifier.weight(1f)
        )
        Text(
            valueLabel,
            color = TextDim,
            fontSize = 11.sp,
            modifier = Modifier.width(40.dp)
        )
        IconButton(
            onClick = onReset,
            modifier = Modifier.size(26.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.RestartAlt,
                contentDescription = resetDescription,
                tint = TextDim,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// Setelan MIDI di dialog Pengaturan: toggle velocity + scan/konek BLE MIDI.
// Sengaja dipisah jadi komposable sendiri biar isi dialog utama gak makin panjang.
@Composable
private fun MidiSettingsSection(
    velocityEnabled: Boolean,
    onVelocityEnabledChange: (Boolean) -> Unit,
    sensitivity: Float,
    onSensitivityChange: (Float) -> Unit,
    onSensitivityChangeFinished: () -> Unit,
    bleScanning: Boolean,
    bleFound: List<BleMidiDevice>,
    bleStatus: String,
    onBleScanToggle: () -> Unit,
    onBleConnect: (address: String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Velocity: kekuatan pukulan di pad MIDI ngatur keras-pelannya suara.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Velocity MIDI",
                    color = PadTextColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
                Text(
                    "Pukulan pelan = suara pelan, pukulan keras = suara penuh",
                    color = TextDim,
                    fontSize = 10.sp
                )
            }
            Switch(
                checked = velocityEnabled,
                onCheckedChange = onVelocityEnabledChange,
                modifier = Modifier.scale(0.75f),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = ThemeState.accentColor,
                    checkedTrackColor = ThemeState.accentColor.copy(alpha = 0.5f)
                )
            )
        }

        // Slider kepekaan - cuma relevan kalau velocity nyala, jadi dimatiin (dan
        // diredupin) kalau togglenya off, bukan disembunyiin, biar posisinya tetap.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Peka",
                color = TextDim,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                modifier = Modifier.width(34.dp)
            )
            Slider(
                value = sensitivity,
                onValueChange = onSensitivityChange,
                onValueChangeFinished = onSensitivityChangeFinished,
                valueRange = 0f..1f,
                enabled = velocityEnabled,
                colors = SliderDefaults.colors(
                    thumbColor = ThemeState.accentColor,
                    activeTrackColor = ThemeState.accentColor,
                    inactiveTrackColor = CtrlPanelColor
                ),
                modifier = Modifier.weight(1f)
            )
            Text(
                "${(sensitivity * 100).toInt()}%",
                color = TextDim,
                fontSize = 11.sp,
                modifier = Modifier.width(40.dp)
            )
            IconButton(
                onClick = {
                    onSensitivityChange(MidiSettings.DEFAULT_SENSITIVITY)
                    onSensitivityChangeFinished()
                },
                enabled = velocityEnabled,
                modifier = Modifier.size(26.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.RestartAlt,
                    contentDescription = "Reset kepekaan velocity",
                    tint = TextDim,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        // Contoh nyata hasil slider saat ini, dihitung dari kurva yang SAMA dengan
        // yang dipakai pas mukul - biar user tau efek geserannya tanpa nebak-nebak.
        Text(
            text = if (velocityEnabled) {
                "Pukulan pelan (vel 32) -> ${(MidiSettings.gainAt(32, sensitivity) * 100).toInt()}% volume, " +
                    "sedang (vel 64) -> ${(MidiSettings.gainAt(64, sensitivity) * 100).toInt()}%. " +
                    "Geser kanan = lebih peka"
            } else {
                "Velocity mati - semua pukulan MIDI berbunyi penuh"
            },
            color = TextDim,
            fontSize = 10.sp,
            modifier = Modifier.padding(start = 34.dp, bottom = 2.dp)
        )

        Spacer(Modifier.height(4.dp))

        // BLE MIDI: header + tombol CARI/BERHENTI. onTap (bukan onPress) supaya
        // geser jari buat scroll dialog gak keanggep tap.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "MIDI Bluetooth (BLE)",
                    color = PadTextColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
                Text(
                    "Buat drum pad/keyboard MIDI wireless",
                    color = TextDim,
                    fontSize = 10.sp
                )
            }
            Text(
                if (bleScanning) "BERHENTI" else "CARI",
                color = ThemeState.accentColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                modifier = Modifier
                    .pointerInput(Unit) { detectTapGestures(onTap = { onBleScanToggle() }) }
                    .padding(6.dp)
            )
        }

        if (bleStatus.isNotEmpty()) {
            Text(
                bleStatus,
                color = TextDim,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
            )
        }

        bleFound.forEach { dev ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .background(CtrlPanelColor, RoundedCornerShape(4.dp))
                    .border(1.dp, PadLineColor, RoundedCornerShape(4.dp))
                    .pointerInput(dev.address) {
                        detectTapGestures(onTap = { onBleConnect(dev.address) })
                    }
                    .padding(horizontal = 8.dp, vertical = 7.dp)
            ) {
                Text(
                    dev.name,
                    color = PadTextColor,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "SAMBUNG",
                    color = ThemeState.accentColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp
                )
            }
        }
    }
}

// Bagian "Sistem Audio" di dialog Pengaturan. State-nya dibaca langsung dari
// AudioBackendManager (mode = pilihan user, active = yang benar-benar jalan), jadi
// label "Sedang dipakai" ikut berubah sendiri kalau engine pindah backend otomatis.
@Composable
private fun AudioBackendSection() {
    val context = LocalContext.current
    val selected = AudioBackendManager.mode
    val active = AudioBackendManager.active
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Sistem Audio",
            color = PadTextColor,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp
        )
        Text(
            "Sedang dipakai: " + (if (active == 0) "-" else AudioBackendManager.nameOf(active)),
            color = TextDim,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(4.dp))
        for (id in listOf(
            AudioBackendManager.AUTO,
            AudioBackendManager.OBOE,
            AudioBackendManager.AAUDIO,
            AudioBackendManager.OPENSL,
            AudioBackendManager.AUDIOTRACK
        )) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { AudioBackendManager.setMode(context, id) }
            ) {
                RadioButton(
                    selected = selected == id,
                    onClick = { AudioBackendManager.setMode(context, id) },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = ThemeState.accentColor,
                        unselectedColor = TextDim
                    )
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(AudioBackendManager.nameOf(id), color = PadTextColor, fontSize = 13.sp)
                    Text(AudioBackendManager.descriptionOf(id), color = TextDim, fontSize = 11.sp)
                }
            }
        }
    }
}
