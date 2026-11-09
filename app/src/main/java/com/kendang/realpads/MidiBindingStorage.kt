package com.kendang.realpads

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private const val MIDI_BIND_PREFS = "realpads_pad_midibind"

// Nyimpen binding NOMOR NOTE MIDI (0..127) ke tiap pad (P1..P16). Konsepnya
// sama persis dengan PadKeyBindingStorage (keyboard fisik): GLOBAL, gak ikut
// preset, dimuat sekali pas app start supaya jalur pemukulan real-time gak
// perlu baca SharedPreferences.
//
// Yang dicocokkan cuma nomor note-nya, channel diabaikan - drum pad/e-drum
// biasanya ngirim di channel 10 (index 9), controller keyboard di channel 1,
// dan user gak perlu peduli soal itu.
object MidiBindingStorage {
    // pad -> nomor note MIDI
    val bindings = mutableStateMapOf<Int, Int>()

    private fun key(pad: Int) = "note_pad$pad"

    // Salinan immutable note -> pad khusus dibaca thread MIDI (MidiInputManager),
    // biar gak baca state Compose dari thread non-UI. Dibangun ulang tiap binding
    // berubah (jarang terjadi, murah).
    @Volatile
    private var noteToPad: Map<Int, Int> = emptyMap()

    private fun rebuild() {
        noteToPad = bindings.entries.associate { it.value to it.key }
    }

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(MIDI_BIND_PREFS, Context.MODE_PRIVATE)
        bindings.clear()
        for (pad in 1..PresetStorage.MAX_PAD) {
            val k = key(pad)
            if (prefs.contains(k)) bindings[pad] = prefs.getInt(k, -1)
        }
        rebuild()
    }

    // 1 note cuma boleh nge-trigger 1 pad - kalau note yang sama udah kepasang
    // di pad lain, dilepas dulu dari situ.
    fun set(context: Context, pad: Int, note: Int) {
        val prefs = context.getSharedPreferences(MIDI_BIND_PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val existingPad = bindings.entries.firstOrNull { it.value == note && it.key != pad }?.key
        if (existingPad != null) {
            bindings.remove(existingPad)
            editor.remove(key(existingPad))
        }
        bindings[pad] = note
        editor.putInt(key(pad), note)
        editor.apply()
        rebuild()
    }

    fun reset(context: Context, pad: Int) {
        bindings.remove(pad)
        context.getSharedPreferences(MIDI_BIND_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(key(pad))
            .apply()
        rebuild()
    }

    fun resetAll(context: Context) {
        bindings.clear()
        context.getSharedPreferences(MIDI_BIND_PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        rebuild()
    }

    fun padForNote(note: Int): Int? = noteToPad[note]
}

// Status "lagi nunggu 1 note MIDI buat direkam" - null berarti gak lagi rekam,
// note MIDI yang masuk jalan normal sebagai trigger pad. Mirip KeyBindRecorder.
object MidiBindRecorder {
    var recordingPad by mutableStateOf<Int?>(null)
}

private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

// 60 -> "C4 (60)". Konvensi umum: note 60 = middle C = C4.
fun midiNoteDisplayName(note: Int): String {
    if (note !in 0..127) return "Note $note"
    return "${NOTE_NAMES[note % 12]}${note / 12 - 1} ($note)"
}

private const val MIDI_SETTINGS_PREFS = "realpads_midi_settings"
private const val KEY_VELOCITY_ENABLED = "velocity_enabled"
private const val KEY_VELOCITY_SENSITIVITY = "velocity_sensitivity"

// Setelan umum input MIDI. Velocity aktif secara default: pukulan pelan di
// pad MIDI terdengar lebih pelan, pukulan keras terdengar penuh.
object MidiSettings {
    // Kepekaan 0..1. Makin tinggi = pukulan pelan makin cepat terdengar keras;
    // makin rendah = harus dipukul lebih kuat baru terdengar penuh. Default 0.7
    // (setara kurva pangkat ~0.76 yang dipakai sebelum ada slider ini).
    const val DEFAULT_SENSITIVITY = 0.7f

    var velocityEnabled by mutableStateOf(true)
        private set
    var sensitivity by mutableStateOf(DEFAULT_SENSITIVITY)
        private set

    // Salinan plain buat dibaca thread MIDI tanpa nyentuh state Compose.
    @Volatile
    private var velocityFlag = true
    @Volatile
    private var sensitivityFlag = DEFAULT_SENSITIVITY

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(MIDI_SETTINGS_PREFS, Context.MODE_PRIVATE)
        velocityEnabled = prefs.getBoolean(KEY_VELOCITY_ENABLED, true)
        sensitivity = prefs.getFloat(KEY_VELOCITY_SENSITIVITY, DEFAULT_SENSITIVITY).coerceIn(0f, 1f)
        velocityFlag = velocityEnabled
        sensitivityFlag = sensitivity
    }

    fun setVelocityEnabled(context: Context, value: Boolean) {
        velocityEnabled = value
        velocityFlag = value
        context.getSharedPreferences(MIDI_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_VELOCITY_ENABLED, value).apply()
    }

    // Dipanggil terus selama slider digeser: cuma update memori (efeknya langsung
    // kedengeran di pukulan berikutnya), belum nulis ke disk.
    fun updateSensitivity(value: Float) {
        val v = value.coerceIn(0f, 1f)
        sensitivity = v
        sensitivityFlag = v
    }

    // Dipanggil sekali pas jari dilepas dari slider (atau tombol reset).
    fun saveSensitivity(context: Context) {
        context.getSharedPreferences(MIDI_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_VELOCITY_SENSITIVITY, sensitivity).apply()
    }

    // Kepekaan 0..1 -> eksponen kurva. 0 -> 1.6 (kurva "keras": pukulan pelan
    // jauh lebih kecil dari pukulan keras), 1 -> 0.4 (kurva "peka": pukulan
    // pelan pun sudah cukup terdengar). Eksponen 1.0 = linear.
    private fun exponentFor(sensitivity: Float): Double =
        1.6 - 1.2 * sensitivity.coerceIn(0f, 1f)

    // Fungsi murni (gak baca setelan aktif) - dipakai juga UI buat nampilin
    // contoh "velocity 32 -> sekian %" sesuai posisi slider yang lagi digeser.
    fun gainAt(velocity: Int, sensitivity: Float): Float {
        val v = velocity.coerceIn(1, 127) / 127.0
        return Math.pow(v, exponentFor(sensitivity)).toFloat().coerceIn(0.05f, 1f)
    }

    // Velocity MIDI (1..127) -> pengali volume 0..1, dengan lantai 0.05 supaya
    // pukulan paling pelan pun tetap kedengeran. Velocity dimatiin -> selalu 1.
    fun gainFor(velocity: Int): Float {
        if (!velocityFlag) return 1f
        return gainAt(velocity, sensitivityFlag)
    }
}
