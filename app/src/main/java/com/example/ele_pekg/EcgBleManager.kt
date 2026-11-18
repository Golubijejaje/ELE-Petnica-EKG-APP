package com.example.ele_pekg

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
val TX_UUID: UUID      = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
val CCCD_UUID: UUID    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

class EcgBleManager(context: Context) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    val bufferSize = 4000
    val sampleBuffer = FloatArray(bufferSize) { 2048f }
    @Volatile var writeIndex = 0
        private set

    private val lineBuilder = StringBuilder()

    // IIR High-Pass filter promenljive (fc = 0.5 Hz, fs = 1000 Hz)
    private var xPrev = 0f
    private var yPrev = 0f
    private var isFirstSample = true
    private val alpha = 0.996865f

    @SuppressLint("MissingPermission")
    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.e("EcgBle", "Bluetooth LE Scanner nije dostupan!")
            return
        }
        if (_connectionState.value != ConnectionState.DISCONNECTED) return

        _connectionState.value = ConnectionState.SCANNING

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        isFirstSample = true
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            try {
                adapter?.bluetoothLeScanner?.stopScan(this)
            } catch (_: Exception) {}

            _connectionState.value = ConnectionState.CONNECTING
            isFirstSample = true
            bluetoothGatt = device.connectGatt(null, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("EcgBle", "Scan failed with error: $errorCode")
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.requestMtu(128)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connectionState.value = ConnectionState.DISCONNECTED
                try { gatt.close() } catch (_: Exception) {}
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID) ?: return
                val txChar = service.getCharacteristic(TX_UUID) ?: return

                gatt.setCharacteristicNotification(txChar, true)
                val descriptor = txChar.getDescriptor(CCCD_UUID) ?: return

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
                _connectionState.value = ConnectionState.CONNECTED
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == TX_UUID) {
                parseIncomingBytes(value)
            }
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == TX_UUID) {
                characteristic.value?.let { parseIncomingBytes(it) }
            }
        }
    }

    private fun parseIncomingBytes(bytes: ByteArray) {
        val chunk = bytes.decodeToString()
        for (ch in chunk) {
            if (ch == '\n') {
                val line = lineBuilder.toString()
                lineBuilder.clear()
                processBatchLine(line)
            } else {
                lineBuilder.append(ch)
            }
        }
    }

    private fun processBatchLine(line: String) {
        val parts = line.split(',')
        for (part in parts) {
            val raw = part.trim().toFloatOrNull() ?: continue

            if (isFirstSample) {
                xPrev = raw
                yPrev = 0f
                isFirstSample = false
            }

            // IIR High-Pass filter (uklanja baseline drift < 0.5 Hz)
            val yCurr = alpha * (yPrev + raw - xPrev)
            xPrev = raw
            yPrev = yCurr

            // Centriranje signala tačno na sredinu opsega (2048)
            val processedValue = yCurr + 2048f

            sampleBuffer[writeIndex] = processedValue.coerceIn(0f, 4095f)
            writeIndex = (writeIndex + 1) % bufferSize
        }
    }
}