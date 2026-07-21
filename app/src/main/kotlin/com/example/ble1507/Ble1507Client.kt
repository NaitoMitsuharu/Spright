package com.example.ble1507

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.UUID

class Ble1507Client(context: Context, private val listener: Listener) {
    interface Listener {
        fun onStatus(status: String)
        fun onBondState(status: String)
        fun onConnectionState(status: String)
        fun onMtuChanged(mtu: Int)
        fun onImuSample(packet: ImuPacket)
        fun onMessage(message: String)
        fun onCommandWrite(requestId: Long, status: Int)
    }

    private val context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (this.context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var pendingBondDevice: BluetoothDevice? = null
    private var lastDevice: BluetoothDevice? = null
    private val scanStateLock = Any()
    @Volatile
    private var scanning = false
    @Volatile
    private var ready = false
    private data class PendingWrite(
        val requestId: Long,
        val command: String,
        val frame: ByteArray,
    )

    private val writeQueue = ArrayDeque<PendingWrite>()
    private var inFlightWrite: PendingWrite? = null
    private var writeTimeoutRunnable: Runnable? = null
    private var nextWriteId = 1L
    private var notificationCount = 0L

    fun register() {
        context.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        context.registerReceiver(adapterStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(bondReceiver) }
        runCatching { context.unregisterReceiver(adapterStateReceiver) }
        disconnect()
    }

    fun isReady(): Boolean =
        adapter?.isEnabled == true && ready && gatt != null && characteristic != null

    @SuppressLint("MissingPermission")
    fun scanPairAndConnect() {
        if (!hasBluetoothPermission()) {
            postStatus("BLE permission is not granted")
            postConnection("disconnected")
            return
        }
        if (adapter?.isEnabled != true) {
            postStatus("Bluetooth is off")
            postConnection("disconnected")
            return
        }
        disconnect()
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            postStatus("BLE scanner is unavailable")
            return
        }

        postStatus("Scanning BLE1507...")
        postConnection("scanning")
        scanning = true
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, scanCallback)
        mainHandler.postDelayed(::handleScanTimeout, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    fun reconnectLastDeviceOrScan() {
        if (!hasBluetoothPermission()) {
            postStatus("BLE permission is not granted")
            postConnection("disconnected")
            return
        }
        if (adapter?.isEnabled != true) {
            postStatus("Bluetooth is off")
            postConnection("disconnected")
            return
        }
        val device = lastDevice
        if (device != null && device.bondState == BluetoothDevice.BOND_BONDED) {
            disconnect()
            postStatus("Reconnecting to BLE1507...")
            connect(device)
        } else {
            scanPairAndConnect()
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScanIfNeeded()
        cancelWriteTimeout()
        ready = false
        characteristic = null
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        failAllWrites(BluetoothGatt.GATT_FAILURE)
        postConnection("disconnected")
    }

    fun sendCommand(command: String): Long? {
        if (!hasBluetoothPermission()) {
            postStatus("BLE permission is not granted")
            return null
        }
        if (!isReady()) {
            postStatus("Not connected")
            return null
        }
        val pending = synchronized(writeQueue) {
            PendingWrite(
                requestId = nextWriteId++,
                command = command,
                frame = Cobs.encodeFrame(command.toByteArray(StandardCharsets.UTF_8)),
            ).also(writeQueue::addLast)
        }
        Log.d(TAG, "queued write id=${pending.requestId} command=${pending.command}")
        mainHandler.post(::pumpWrites)
        return pending.requestId
    }

    @SuppressLint("MissingPermission")
    private fun pumpWrites() {
        if (inFlightWrite != null) return
        val pending = synchronized(writeQueue) {
            if (writeQueue.isEmpty()) null else writeQueue.removeFirst()
        } ?: return
        val currentGatt = gatt
        val currentCharacteristic = characteristic
        if (!hasBluetoothPermission() || currentGatt == null || currentCharacteristic == null) {
            mainHandler.post { listener.onCommandWrite(pending.requestId, BluetoothGatt.GATT_FAILURE) }
            pumpWrites()
            return
        }
        inFlightWrite = pending
        Log.d(
            TAG,
            "starting write id=${pending.requestId} command=${pending.command} bytes=${pending.frame.size}",
        )
        val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val status = currentGatt.writeCharacteristic(currentCharacteristic, pending.frame, writeType)
        val accepted = if (status != BluetoothGatt.GATT_SUCCESS) {
            postStatus("Write failed: $status")
            false
        } else {
            true
        }
        if (!accepted) {
            inFlightWrite = null
            mainHandler.post { listener.onCommandWrite(pending.requestId, BluetoothGatt.GATT_FAILURE) }
            pumpWrites()
        } else {
            armWriteTimeout(pending)
        }
    }

    private fun failAllWrites(status: Int) {
        cancelWriteTimeout()
        val failed = synchronized(writeQueue) {
            buildList {
                inFlightWrite?.let(::add)
                addAll(writeQueue)
            }.also {
                inFlightWrite = null
                writeQueue.clear()
            }
        }
        failed.forEach { pending ->
            mainHandler.post { listener.onCommandWrite(pending.requestId, status) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanIfNeeded(): Boolean {
        val wasScanning = synchronized(scanStateLock) {
            if (!scanning) {
                false
            } else {
                scanning = false
                true
            }
        }
        if (!wasScanning) return false
        if (hasBluetoothPermission()) {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
        return true
    }

    private fun handleScanTimeout() {
        if (!scanning) return
        stopScanIfNeeded()
        postStatus("BLE1507 scan timed out")
        postConnection("disconnected")
    }

    private fun armWriteTimeout(pending: PendingWrite) {
        cancelWriteTimeout()
        val timeout = Runnable {
            if (inFlightWrite?.requestId != pending.requestId) return@Runnable
            inFlightWrite = null
            postStatus("BLE write timed out; reconnecting")
            listener.onCommandWrite(pending.requestId, BluetoothGatt.GATT_FAILURE)
            disconnect()
        }
        writeTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, ExhibitionRecoveryPolicy.BLE_WRITE_TIMEOUT_MS)
    }

    private fun cancelWriteTimeout() {
        writeTimeoutRunnable?.let(mainHandler::removeCallbacks)
        writeTimeoutRunnable = null
    }

    @SuppressLint("MissingPermission")
    private fun handleDeviceFound(device: BluetoothDevice) {
        // A low-latency scan can deliver several queued callbacks for the same
        // advertisement. Claim the scan once so only one callback creates GATT.
        if (!stopScanIfNeeded()) return
        val name = device.name
        postStatus("Found ${name ?: device.address}")
        lastDevice = device
        val bondState = device.bondState
        postBond(bondStateToString(bondState))
        if (bondState == BluetoothDevice.BOND_BONDED) {
            connect(device)
        } else {
            pendingBondDevice = device
            postStatus("Pairing...")
            if (!device.createBond()) {
                postStatus("Pairing request failed; connecting anyway")
                connect(device)
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun connect(device: BluetoothDevice) {
        if (!hasBluetoothPermission()) {
            postStatus("BLE permission is not granted")
            return
        }
        if (gatt != null) {
            Log.w(TAG, "Ignoring duplicate connect request for ${device.address}")
            return
        }
        postConnection("connecting")
        postStatus("Connecting...")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun enableNotification() {
        val currentGatt = gatt ?: return
        val currentCharacteristic = characteristic ?: return
        if (!hasBluetoothPermission()) {
            return
        }
        if (!currentGatt.setCharacteristicNotification(currentCharacteristic, true)) {
            postStatus("setCharacteristicNotification failed")
            disconnect()
            return
        }
        val descriptor = currentCharacteristic.getDescriptor(CLIENT_CONFIG_UUID)
        if (descriptor == null) {
            postStatus("CCCD not found")
            disconnect()
            return
        }
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val status = currentGatt.writeDescriptor(descriptor, value)
        if (status != BluetoothGatt.GATT_SUCCESS) {
            postStatus("Enable notify failed: $status")
            disconnect()
        }
    }

    private fun handleNotification(value: ByteArray) {
        notificationCount++
        if (notificationCount <= 3L || notificationCount % NOTIFICATION_LOG_INTERVAL == 0L) {
            Log.d(
                TAG,
                "notification count=$notificationCount bytes=${value.size} prefix=" +
                    value.take(12).joinToString(" ") { byte -> "%02X".format(byte.toInt() and 0xFF) },
            )
        }
        try {
            val raw = Cobs.decodeFrame(value)
            if (raw.isNotEmpty() && (raw[0].toInt() and 0xFF) == ImuPacket.TYPE_IMU_SAMPLE) {
                val packet = ImuPacket.parse(raw)
                mainHandler.post { listener.onImuSample(packet) }
            } else {
                val message = raw.toString(StandardCharsets.UTF_8)
                mainHandler.post { listener.onMessage(message) }
            }
        } catch (ex: IllegalArgumentException) {
            postStatus("Decode failed: ${ex.message}")
        }
    }

    private fun hasBluetoothPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val scanRecord = result.scanRecord
            val name = scanRecord?.deviceName
            if (DEVICE_NAME == name || scanRecord?.serviceUuids?.contains(ParcelUuid(SERVICE_UUID)) == true) {
                handleDeviceFound(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            postStatus("Scan failed: $errorCode")
            postConnection("disconnected")
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED != intent.action) {
                return
            }
            if (!hasBluetoothPermission()) {
                postStatus("BLE permission is not granted")
                return
            }
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            postBond(bondStateToString(state))
            if (device != null && state == BluetoothDevice.BOND_BONDED) {
                val wasPending = pendingBondDevice?.address == device.address
                val isLastDevice = lastDevice?.address == device.address
                pendingBondDevice = null
                if (wasPending || (isLastDevice && gatt == null)) {
                    // Connect after fresh pairing, or after OS-triggered re-pair dialog.
                    connect(device)
                }
            } else if (device != null && state == BluetoothDevice.BOND_NONE &&
                pendingBondDevice?.address == device.address
            ) {
                // Bond was removed (stale bond cleanup); trigger fresh pairing.
                postStatus("Bond removed; re-pairing...")
                if (!device.createBond()) {
                    postStatus("Re-pairing request failed; connecting anyway")
                    pendingBondDevice = null
                    connect(device)
                }
            }
        }
    }

    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED != intent.action) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF,
                BluetoothAdapter.STATE_OFF,
                -> handleAdapterUnavailable()

                BluetoothAdapter.STATE_ON -> {
                    Log.i(TAG, "Bluetooth adapter is on; requesting connection recovery")
                    postStatus("Bluetooth is on")
                    postConnection("disconnected")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleAdapterUnavailable() {
        val staleGatt = gatt
        synchronized(scanStateLock) {
            scanning = false
        }
        cancelWriteTimeout()
        ready = false
        characteristic = null
        gatt = null
        runCatching { staleGatt?.close() }
        failAllWrites(BluetoothGatt.GATT_FAILURE)
        Log.w(TAG, "Bluetooth adapter is off; cleared stale GATT state")
        postStatus("Bluetooth is off")
        postConnection("disconnected")
    }

    @SuppressLint("MissingPermission")
    private fun removeBondIfPossible(device: BluetoothDevice) {
        try {
            device.javaClass.getMethod("removeBond").invoke(device)
        } catch (error: Exception) {
            android.util.Log.w("SprightBle", "Could not remove stale bond for ${device.address}", error)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val isCurrentGatt = this@Ble1507Client.gatt === gatt
            Log.i(
                TAG,
                "connection state status=$status newState=$newState current=$isCurrentGatt " +
                    "bond=${bondStateToString(gatt.device.bondState)}",
            )
            if (!isCurrentGatt) {
                Log.w(TAG, "Ignoring callback from an obsolete GATT instance")
                gatt.close()
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                ready = false
                postConnection("connected")
                postStatus("Connected; requesting MTU 247")
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                gatt.requestMtu(247)
            } else {
                cancelWriteTimeout()
                ready = false
                characteristic = null
                failAllWrites(BluetoothGatt.GATT_FAILURE)
                this@Ble1507Client.gatt = null
                gatt.close()
                postConnection("disconnected")
                postStatus(
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        "Disconnected"
                    } else {
                        "Disconnected (GATT status=$status)"
                    },
                )

                // Only authentication/encryption failures imply a stale LTK.
                // Generic status 8/19/133 disconnects are transient radio or
                // peripheral failures and must not silently remove the bond.
                val device = gatt.device
                if (
                    status in AUTHENTICATION_FAILURE_STATUSES &&
                    device.bondState == BluetoothDevice.BOND_BONDED
                ) {
                    postStatus("BLE authentication failed; removing stale bond")
                    pendingBondDevice = device
                    removeBondIfPossible(device)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            postMtu(mtu)
            postStatus("MTU: $mtu")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (this@Ble1507Client.gatt !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                postStatus("Service discovery failed: $status")
                disconnect()
                return
            }
            val service: BluetoothGattService? = gatt.getService(SERVICE_UUID)
            if (service == null) {
                postStatus("BLE1507 service not found")
                disconnect()
                return
            }
            characteristic = service.getCharacteristic(CHARACTERISTIC_UUID)
            if (characteristic == null) {
                postStatus("BLE1507 characteristic not found")
                disconnect()
                return
            }
            Log.i(
                TAG,
                "characteristic ready properties=0x${characteristic?.properties?.toString(16)}",
            )
            postStatus("Service ready; enabling notify")
            enableNotification()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (this@Ble1507Client.gatt !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                ready = true
                postStatus("Notify enabled")
                postConnection("ready")
            } else {
                postStatus("Descriptor write failed: $status")
                disconnect()
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (this@Ble1507Client.gatt !== gatt) return
            val completed = inFlightWrite ?: return
            Log.i(
                TAG,
                "write completed id=${completed.requestId} command=${completed.command} status=$status",
            )
            cancelWriteTimeout()
            inFlightWrite = null
            mainHandler.post {
                listener.onCommandWrite(completed.requestId, status)
                pumpWrites()
            }
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleNotification(characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(value)
        }
    }

    private fun bondStateToString(state: Int): String = when (state) {
        BluetoothDevice.BOND_BONDED -> "bonded"
        BluetoothDevice.BOND_BONDING -> "pairing"
        BluetoothDevice.BOND_NONE -> "not paired"
        else -> "unknown"
    }

    private fun postStatus(status: String) = mainHandler.post { listener.onStatus(status) }
    private fun postBond(status: String) = mainHandler.post { listener.onBondState(status) }
    private fun postConnection(status: String) = mainHandler.post { listener.onConnectionState(status) }
    private fun postMtu(mtu: Int) = mainHandler.post { listener.onMtuChanged(mtu) }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("b1507000-7e6f-4d86-b9b5-6a5d80220001")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("b1507001-7e6f-4d86-b9b5-6a5d80220001")
        private val CLIENT_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val DEVICE_NAME = "BLE1507"
        private const val SCAN_TIMEOUT_MS = 12_000L
        private const val NOTIFICATION_LOG_INTERVAL = 300L
        private const val TAG = "SprightBle"
        private val AUTHENTICATION_FAILURE_STATUSES = setOf(
            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION,
            BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION,
        )
    }
}
