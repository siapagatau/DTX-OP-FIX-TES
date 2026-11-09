package com.kendang.realpads

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.util.UUID

private const val MIDI_CONN_PREFS = "realpads_midi_conn"
private const val KEY_LAST_BLE_ADDRESS = "last_ble_address"

// Service UUID standar BLE MIDI (dipakai semua perangkat BLE MIDI class-compliant).
private val BLE_MIDI_SERVICE_UUID: UUID = UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")
private const val BLE_SCAN_TIMEOUT_MS = 15_000L

// Perangkat BLE MIDI yang ketemu pas scan.
data class BleMidiDevice(val name: String, val address: String)

// Dengerin semua perangkat MIDI yang terhubung ke HP - USB class-compliant lewat
// OTG, atau BLE MIDI (wireless) - dan ngubah pesan Note On jadi trigger pad lewat
// KeyTriggerBus: jalur yang SAMA dengan keyboard fisik & sentuhan jari, jadi
// suara, kedip pad, dan efek GLER otomatis konsisten. Velocity (kekuatan pukulan)
// ikut diteruskan sebagai pengali volume (lihat MidiSettings.gainFor).
//
// Istilah Android yang gampang kebalik: port bertipe OUTPUT milik perangkat itu
// adalah port tempat perangkat NGIRIM data ke kita, jadi itu yang kita buka
// (openOutputPort) dan kita sambungin receiver-nya.
//
// Semua perubahan state jalan di main thread. Cuma MidiReceiver.onSend yang jalan
// di thread MIDI - di situ kita cuma nulis ke KeyTriggerBus (thread-safe) atau
// post ke main thread.
object MidiInputManager {
    // Nama perangkat yang lagi kebuka, ditampilin di dialog Pengaturan.
    var connectedNames by mutableStateOf<List<String>>(emptyList())
        private set

    // --- State BLE (dibaca UI) ---
    var bleScanning by mutableStateOf(false)
        private set
    var bleFound by mutableStateOf<List<BleMidiDevice>>(emptyList())
        private set
    var bleStatus by mutableStateOf("")
        private set

    private class Opened(
        val device: MidiDevice,
        val ports: List<MidiOutputPort>,
        val name: String
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val opened = HashMap<Int, Opened>()
    private val opening = HashSet<Int>()
    private var manager: MidiManager? = null
    private var appContext: Context? = null
    private var started = false

    private var bleScanner: BluetoothLeScanner? = null
    private val stopScanRunnable = Runnable { stopBleScan(timedOut = true) }

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) {
            openDeviceIfNeeded(device)
        }

        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            closeDevice(device.id)
        }
    }

    // ------------------------------------------------------------------
    // Izin BLE
    // ------------------------------------------------------------------

    // Izin runtime yang dibutuhin buat scan + konek BLE. Android 12+ (API 31) pakai
    // izin Bluetooth baru; di bawahnya scan BLE butuh izin lokasi (BLUETOOTH &
    // BLUETOOTH_ADMIN cuma izin biasa, otomatis diberi lewat manifest).
    fun requiredBlePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasBlePermissions(context: Context): Boolean =
        requiredBlePermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    fun notifyBlePermissionDenied() {
        bleStatus = "Izin Bluetooth ditolak - BLE MIDI butuh izin itu"
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    // Aman dipanggil berulang (mis. tiap onResume) - kalau udah start, diabaikan.
    fun start(context: Context) {
        if (started) return
        val ctx = context.applicationContext
        val mgr = ctx.getSystemService(Context.MIDI_SERVICE) as? MidiManager ?: return
        appContext = ctx
        manager = mgr
        started = true
        mgr.registerDeviceCallback(deviceCallback, mainHandler)
        // Perangkat yang SUDAH kepasang sebelum app dibuka gak akan dapet
        // onDeviceAdded, jadi harus di-scan manual sekali di sini.
        openAlreadyConnectedDevices(mgr)
        // Sambung ulang perangkat BLE terakhir (koneksi BLE ikut putus tiap app ke
        // background, jadi tanpa ini user harus scan ulang tiap balik ke app).
        reconnectLastBle()
    }

    // Dipisah jadi fungsi sendiri supaya @Suppress nempel ke deklarasi (bukan ke
    // ekspresi) - MidiManager.getDevices() ditandai deprecated di Android 14+,
    // tapi masih jalan dan satu-satunya cara sinkron di minSdk 26.
    @Suppress("DEPRECATION")
    private fun openAlreadyConnectedDevices(mgr: MidiManager) {
        mgr.devices.forEach { openDeviceIfNeeded(it) }
    }

    fun stop() {
        if (!started) return
        stopBleScan()
        started = false
        manager?.unregisterDeviceCallback(deviceCallback)
        opened.keys.toList().forEach { closeDevice(it) }
        opening.clear()
        manager = null
    }

    // ------------------------------------------------------------------
    // Buka / tutup perangkat (USB & BLE lewat jalur yang sama)
    // ------------------------------------------------------------------

    private fun openDeviceIfNeeded(info: MidiDeviceInfo) {
        // outputPortCount = jumlah port tempat perangkat ngirim data ke kita.
        // 0 berarti perangkat output-only (mis. modul suara) - gak ada yang didengerin.
        if (info.outputPortCount <= 0) return
        val id = info.id
        if (opened.containsKey(id) || !opening.add(id)) return
        val mgr = manager ?: run { opening.remove(id); return }

        mgr.openDevice(info, MidiManager.OnDeviceOpenedListener { device ->
            opening.remove(id)
            attach(device, info)
        }, mainHandler)
    }

    // Titik tunggal buat mulai dengerin 1 MidiDevice, dipakai jalur USB (openDevice)
    // maupun BLE (openBluetoothDevice). Dijaga per id: begitu perangkat BLE terbuka,
    // sistem juga ngirim onDeviceAdded buat perangkat yang sama, jadi kita bisa
    // kedatangan 2 handle - yang kedua langsung ditutup biar port gak kepasang dobel.
    private fun attach(device: MidiDevice?, info: MidiDeviceInfo) {
        if (device == null) return
        val id = info.id
        if (!started || opened.containsKey(id)) {
            runCatching { device.close() }
            return
        }
        val baseName = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "Perangkat MIDI"
        val name = if (info.type == MidiDeviceInfo.TYPE_BLUETOOTH) "$baseName (BLE)" else baseName
        val ports = ArrayList<MidiOutputPort>()
        for (i in 0 until info.outputPortCount) {
            val port = device.openOutputPort(i) ?: continue
            port.connect(NoteReceiver())
            ports.add(port)
        }
        if (ports.isEmpty()) {
            runCatching { device.close() }
            return
        }
        opened[id] = Opened(device, ports, name)
        publishNames()
    }

    private fun closeDevice(id: Int) {
        opening.remove(id)
        val o = opened.remove(id) ?: return
        o.ports.forEach { runCatching { it.close() } }
        runCatching { o.device.close() }
        publishNames()
    }

    private fun publishNames() {
        connectedNames = opened.values.map { it.name }
    }

    // ------------------------------------------------------------------
    // BLE MIDI: scan & konek
    // ------------------------------------------------------------------

    private fun bluetoothAdapter(context: Context): BluetoothAdapter? =
        (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addFound(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { addFound(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            bleScanning = false
            bleStatus = "Scan gagal (kode $errorCode)"
        }
    }

    @SuppressLint("MissingPermission")
    private fun addFound(result: ScanResult) {
        val address = result.device.address ?: return
        if (bleFound.any { it.address == address }) return
        val name = result.scanRecord?.deviceName
            ?: runCatching { result.device.name }.getOrNull()
            ?: "Perangkat BLE MIDI"
        bleFound = bleFound + BleMidiDevice(name, address)
        bleStatus = "Ketemu ${bleFound.size} perangkat - tap buat menyambung"
    }

    // Pemanggil (MainActivity) wajib memastikan izin udah diberikan sebelum manggil ini.
    @SuppressLint("MissingPermission")
    fun startBleScan(context: Context) {
        if (bleScanning) return
        val ctx = context.applicationContext
        if (!hasBlePermissions(ctx)) {
            notifyBlePermissionDenied()
            return
        }
        val adapter = bluetoothAdapter(ctx)
        if (adapter == null) {
            bleStatus = "Perangkat ini tidak punya Bluetooth"
            return
        }
        if (!adapter.isEnabled) {
            bleStatus = "Nyalakan Bluetooth dulu"
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            bleStatus = "Scan BLE tidak tersedia"
            return
        }
        // Filter service UUID MIDI -> yang muncul cuma perangkat BLE MIDI beneran,
        // bukan semua headset/jam tangan di sekitar.
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(BLE_MIDI_SERVICE_UUID)).build()
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            bleFound = emptyList()
            bleStatus = "Mencari perangkat BLE MIDI..."
            bleScanning = true
            bleScanner = scanner
            scanner.startScan(filters, settings, bleScanCallback)
            mainHandler.removeCallbacks(stopScanRunnable)
            mainHandler.postDelayed(stopScanRunnable, BLE_SCAN_TIMEOUT_MS)
        } catch (e: SecurityException) {
            bleScanning = false
            notifyBlePermissionDenied()
        } catch (e: IllegalStateException) {
            bleScanning = false
            bleStatus = "Scan BLE gagal dimulai"
        }
    }

    @SuppressLint("MissingPermission")
    fun stopBleScan(timedOut: Boolean = false) {
        mainHandler.removeCallbacks(stopScanRunnable)
        if (bleScanning) {
            runCatching { bleScanner?.stopScan(bleScanCallback) }
            bleScanning = false
            if (timedOut) {
                bleStatus = if (bleFound.isEmpty()) {
                    "Tidak ada perangkat BLE MIDI ketemu. Pastikan perangkat menyala & mode pairing aktif"
                } else {
                    "Scan selesai - tap perangkat buat menyambung"
                }
            }
        }
        bleScanner = null
    }

    @SuppressLint("MissingPermission")
    fun connectBle(context: Context, address: String) {
        connectBleInternal(context.applicationContext, address, userInitiated = true)
    }

    @SuppressLint("MissingPermission")
    private fun connectBleInternal(ctx: Context, address: String, userInitiated: Boolean) {
        val mgr = manager
        if (mgr == null) {
            if (userInitiated) bleStatus = "MIDI belum siap, coba lagi"
            return
        }
        if (!hasBlePermissions(ctx)) {
            if (userInitiated) notifyBlePermissionDenied()
            return
        }
        val btDevice: BluetoothDevice? = try {
            bluetoothAdapter(ctx)?.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            null
        }
        if (btDevice == null) {
            if (userInitiated) bleStatus = "Alamat perangkat tidak valid"
            return
        }
        stopBleScan()
        if (userInitiated) bleStatus = "Menyambung..."
        try {
            mgr.openBluetoothDevice(btDevice, MidiManager.OnDeviceOpenedListener { device ->
                if (device == null) {
                    if (userInitiated) bleStatus = "Gagal menyambung - perangkat di luar jangkauan atau sedang dipakai app lain"
                    return@OnDeviceOpenedListener
                }
                attach(device, device.info)
                // Ingat perangkat ini buat disambung otomatis pas app dibuka lagi.
                ctx.getSharedPreferences(MIDI_CONN_PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_LAST_BLE_ADDRESS, address).apply()
                bleStatus = "Tersambung"
            }, mainHandler)
        } catch (e: SecurityException) {
            if (userInitiated) notifyBlePermissionDenied()
        }
    }

    private fun reconnectLastBle() {
        val ctx = appContext ?: return
        val address = ctx.getSharedPreferences(MIDI_CONN_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_BLE_ADDRESS, null) ?: return
        if (!hasBlePermissions(ctx)) return
        if (bluetoothAdapter(ctx)?.isEnabled != true) return
        // Diam-diam: kalau perangkatnya lagi mati/jauh, gagalnya gak perlu
        // ditampilin sebagai error ke user.
        connectBleInternal(ctx, address, userInitiated = false)
    }

    // ------------------------------------------------------------------
    // Parsing pesan MIDI
    // ------------------------------------------------------------------

    // Dipanggil dari thread MIDI tiap ada Note On (velocity > 0).
    private fun handleNoteOn(note: Int, velocity: Int) {
        if (MidiBindRecorder.recordingPad != null) {
            // Mode rekam: note pertama yang masuk jadi binding pad itu. Dipindah
            // ke main thread karena nulis state Compose + SharedPreferences, dan
            // dicek ulang di sana biar 2 note beruntun gak nulis dobel.
            mainHandler.post {
                val pad = MidiBindRecorder.recordingPad ?: return@post
                val ctx = appContext ?: return@post
                MidiBindingStorage.set(ctx, pad, note)
                MidiBindRecorder.recordingPad = null
            }
            return
        }
        val pad = MidiBindingStorage.padForNote(note) ?: return
        KeyTriggerBus.emit(pad, MidiSettings.gainFor(velocity))
    }

    private class NoteReceiver : MidiReceiver() {
        // Status 0 = belum ada status valid. Running status (data byte tanpa status
        // baru di depannya) didukung karena banyak perangkat ngirim begitu.
        private var status = 0
        private var data1 = -1
        private var inSysex = false

        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            // 1 buffer bisa isi beberapa pesan sekaligus, jadi diparse byte per byte.
            for (i in offset until offset + count) {
                val b = msg[i].toInt() and 0xFF
                if (b >= 0xF8) continue // real-time (clock dsb), boleh nyelip di mana aja
                if (b >= 0x80) {
                    when {
                        b == 0xF0 -> { inSysex = true; status = 0 }
                        b == 0xF7 -> { inSysex = false; status = 0 }
                        b >= 0xF1 -> { inSysex = false; status = 0 } // system common, diabaikan
                        else -> { inSysex = false; status = b; data1 = -1 }
                    }
                    continue
                }
                if (inSysex || status == 0) continue
                when (status and 0xF0) {
                    0xC0, 0xD0 -> Unit // program change / channel pressure: 1 data byte, gak dipakai
                    else -> {
                        if (data1 < 0) {
                            data1 = b
                        } else {
                            // Note On dengan velocity 0 itu sama dengan Note Off -> jangan di-trigger.
                            if ((status and 0xF0) == 0x90 && b > 0) handleNoteOn(data1, b)
                            data1 = -1
                        }
                    }
                }
            }
        }
    }
}
