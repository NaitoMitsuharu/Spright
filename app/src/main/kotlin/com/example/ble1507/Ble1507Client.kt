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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.nio.charset.StandardCharsets
import java.util.UUID

class Ble1507Client(context: Context, private val listener: Listener) {
    interface Listener {
        fun onStatus(status: String)
        fun onBondState(status: String)
        fun onConnectionState(status: String)
        fun onMtuChanged(mtu: Int)
        fun onImuSample(packet: ImuPacket)
        fun onMessage(message: String)
    }

    private val context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (this.context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var pendingBondDevice: BluetoothDevice? = null
    private var lastDevice: BluetoothDevice? = null
    private var scanning = false

    fun register() {
        context.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(bondReceiver) }
        disconnect()
    }

    fun isReady(): Boolean = gatt != null && characteristic != null

    @SuppressLint("MissingPermission")
    fun scanPairAndConnect() {
        if (!hasBluetoothPermission()) {
            postStatus("BLE permission is not granted")
            return
        }
        if (adapter?.isEnabled != true) {
            postStatus("Bluetooth is off")
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
        mainHandler.postDelayed(::stopScanIfNeeded, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScanIfNeeded()
        characteristic = null
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        postConnection("disconnected")
    }

    fun sendCommand(command: String) {
        writeFrame(command.toByteArray(StandardCharsets.UTF_8))
    }

    @SuppressLint("MissingPermission")
    private fun writeFrame(raw: ByteArray) {
        val currentGatt = gatt
        val currentCharacteristic = characteristic
        if (!hasBluetoothPermission()) {
            postStatus("BLE permission is not granted")
            return
        }
        if (currentGatt == null || currentCharacteristic == null) {
            postStatus("Not connected")
            return
        }

        val frame = Cobs.encodeFrame(raw)
        val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = currentGatt.writeCharacteristic(currentCharacteristic, frame, writeType)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                postStatus("Write failed: $status")
            }
        } else {
            @Suppress("DEPRECATION")
            currentCharacteristic.writeType = writeType
            @Suppress("DEPRECATION")
            currentCharacteristic.value = frame
            @Suppress("DEPRECATION")
            if (!currentGatt.writeCharacteristic(currentCharacteristic)) {
                postStatus("Write failed")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanIfNeeded() {
        if (!scanning || !hasBluetoothPermission()) {
            return
        }
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
    }

    @SuppressLint("MissingPermission")
    private fun handleDeviceFound(device: BluetoothDevice) {
        stopScanIfNeeded()
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
    private fun connect(device: BluetoothDevice) {
        if (!hasBluetoothPermission()) {
            postStatus("BLE permission is not granted")
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
            return
        }
        val descriptor = currentCharacteristic.getDescriptor(CLIENT_CONFIG_UUID)
        if (descriptor == null) {
            postStatus("CCCD not found")
            return
        }
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = currentGatt.writeDescriptor(descriptor, value)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                postStatus("Enable notify failed: $status")
            }
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            if (!currentGatt.writeDescriptor(descriptor)) {
                postStatus("Enable notify failed")
            }
        }
    }

    private fun handleNotification(value: ByteArray) {
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

    private fun hasBluetoothPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

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
        override fun onReceive(context: Context, intent: Intent) {
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED != intent.action) {
                return
            }
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
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

    @SuppressLint("MissingPermission")
    private fun removeBondIfPossible(device: BluetoothDevice) {
        try {
            device.javaClass.getMethod("removeBond").invoke(device)
        } catch (_: Exception) {}
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                postStatus("GATT status: $status")
                // Stale bond: Android has old LTK but nRF no longer has it.
                // Remove the stale bond so the OS will re-pair cleanly.
                val device = gatt.device
                if (device != null && device.bondState == BluetoothDevice.BOND_BONDED) {
                    postStatus("Stale bond detected; removing bond for re-pairing")
                    characteristic = null
                    gatt.close()
                    this@Ble1507Client.gatt = null
                    pendingBondDevice = device
                    removeBondIfPossible(device)
                    // bondReceiver will call connect() after BOND_NONE → createBond() cycle,
                    // or the OS may show a fresh pair dialog directly.
                    return
                }
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                postConnection("connected")
                postStatus("Connected; requesting MTU 247")
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                gatt.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                characteristic = null
                postConnection("disconnected")
                postStatus("Disconnected")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            postMtu(mtu)
            postStatus("MTU: $mtu")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                postStatus("Service discovery failed: $status")
                return
            }
            val service: BluetoothGattService? = gatt.getService(SERVICE_UUID)
            if (service == null) {
                postStatus("BLE1507 service not found")
                return
            }
            characteristic = service.getCharacteristic(CHARACTERISTIC_UUID)
            if (characteristic == null) {
                postStatus("BLE1507 characteristic not found")
                return
            }
            postStatus("Service ready; enabling notify")
            enableNotification()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                postStatus("Notify enabled")
                postConnection("ready")
            } else {
                postStatus("Descriptor write failed: $status")
            }
        }

        @Deprecated("Deprecated in Java")
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
    }
}