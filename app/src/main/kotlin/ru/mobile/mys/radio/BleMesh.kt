package ru.mobile.mys.radio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import ru.mobile.mys.mesh.MeshNode
import ru.mobile.mys.mesh.Reassembler
import ru.mobile.mys.mesh.Wire
import ru.mobile.mys.mesh.WireClosed
import ru.mobile.mys.mesh.chunk

/**
 * Mesh через Bluetooth LE. Каждый телефон — реклама + GATT-сервер и сканер + клиент;
 * к соседу подключается тот, чей случайный id в рекламе меньше (одна связь на пару).
 * Кадры линка режутся по MTU ([chunk]); шифрование — в Link, как у любого провода.
 */
@SuppressLint("MissingPermission") // разрешения проверяет вызывающий: [hasPermissions]
class BleMesh(private val ctx: Context, private val node: MeshNode, private val scope: CoroutineScope) {
    private val manager = ctx.getSystemService(BluetoothManager::class.java)
    private val myId = Random.nextBytes(ID_LEN)
    private var server: BluetoothGattServer? = null
    private val serverWires = ConcurrentHashMap<String, ServerWire>()   // по адресу клиента
    private val clientWires = ConcurrentHashMap<String, ClientWire>()   // по id соседа
    private val notifyLock = Mutex() // значение TX общее для всех клиентов
    @Volatile private var notifyDone: CompletableDeferred<Boolean>? = null

    val neighbors: Int get() = serverWires.size + clientWires.size

    fun start(): Boolean {
        val adapter = manager?.adapter ?: return false
        if (!adapter.isEnabled) return false
        val tx = BluetoothGattCharacteristic(TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ)
            .apply { addDescriptor(BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ)) }
        val rx = BluetoothGattCharacteristic(RX, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE)
        server = manager.openGattServer(ctx, serverCallback)?.apply {
            addService(BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
                addCharacteristic(rx); addCharacteristic(tx)
            })
        } ?: return false
        adapter.bluetoothLeAdvertiser?.startAdvertising(
            AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED).setConnectable(true).build(),
            // 128-битный UUID внутри service data + id: 29 байт из 31
            AdvertiseData.Builder().setIncludeDeviceName(false).addServiceData(ParcelUuid(SERVICE), myId).build(),
            advertiseCallback,
        )
        adapter.bluetoothLeScanner?.startScan(
            listOf(ScanFilter.Builder().setServiceData(ParcelUuid(SERVICE), ByteArray(0)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),
            scanCallback,
        )
        return true
    }

    fun stop() {
        val adapter = manager?.adapter
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        clientWires.values.forEach { it.shut() }
        serverWires.values.forEach { it.shut() }
        clientWires.clear()
        serverWires.clear()
        runCatching { server?.close() }
        server = null
    }

    // -- провод: общий приём кусков

    private abstract inner class BleWire : Wire {
        private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        private val parts = Reassembler()
        @Volatile var mtu = 23

        fun feed(piece: ByteArray) {
            val frame = try { parts.push(piece) } catch (e: WireClosed) { shut(); return } ?: return
            inbox.trySend(frame)
        }

        override suspend fun recv(): ByteArray =
            try { inbox.receive() } catch (e: ClosedReceiveChannelException) { throw WireClosed("BLE: связь потеряна") }

        override suspend fun close() = shut()

        open fun shut() { inbox.close() }

        protected fun pieces(frame: ByteArray) = chunk(frame, mtu - 3)
    }

    // -- мы сервер: сосед пишет в RX, мы шлём уведомления TX

    private inner class ServerWire(private val device: BluetoothDevice) : BleWire() {
        override suspend fun send(frame: ByteArray) = notifyLock.withLock {
            val srv = server ?: throw WireClosed("BLE: сервер закрыт")
            val tx = srv.getService(SERVICE)?.getCharacteristic(TX) ?: throw WireClosed("BLE: нет TX")
            for (p in pieces(frame)) {
                val done = CompletableDeferred<Boolean>().also { notifyDone = it }
                val queued = if (Build.VERSION.SDK_INT >= 33) {
                    srv.notifyCharacteristicChanged(device, tx, false, p) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION") run { tx.value = p; srv.notifyCharacteristicChanged(device, tx, false) }
                }
                if (!queued || withTimeoutOrNull(OP_TIMEOUT_MS) { done.await() } != true) { shut(); throw WireClosed("BLE: уведомление не ушло") }
            }
        }

        override fun shut() {
            super.shut()
            if (serverWires.remove(device.address, this)) runCatching { server?.cancelConnection(device) }
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) serverWires[device.address]?.shut()
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            pendingMtu[device.address] = mtu
            serverWires[device.address]?.mtu = mtu
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (descriptor.uuid != CCCD || serverWires.containsKey(device.address)) return
            // клиент подписался на TX — провод готов
            val wire = ServerWire(device).apply { mtu = pendingMtu[device.address] ?: 23 }
            serverWires[device.address] = wire
            scope.launch { runCatching { node.serve(wire) }; wire.shut() }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (characteristic.uuid == RX && value != null) serverWires[device.address]?.feed(value)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            notifyDone?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }
    private val pendingMtu = ConcurrentHashMap<String, Int>()

    // -- мы клиент: пишем в RX соседа, получаем его TX

    private inner class ClientWire(private val key: String, private val gatt: BluetoothGatt) : BleWire() {
        private val lock = Mutex()
        @Volatile var writeDone: CompletableDeferred<Boolean>? = null

        override suspend fun send(frame: ByteArray) = lock.withLock {
            val rx = gatt.getService(SERVICE)?.getCharacteristic(RX) ?: throw WireClosed("BLE: нет RX")
            for (p in pieces(frame)) {
                val done = CompletableDeferred<Boolean>().also { writeDone = it }
                val queued = if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeCharacteristic(rx, p, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION") run {
                        rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        rx.value = p
                        gatt.writeCharacteristic(rx)
                    }
                }
                if (!queued || withTimeoutOrNull(OP_TIMEOUT_MS) { done.await() } != true) { shut(); throw WireClosed("BLE: запись не прошла") }
            }
        }

        override fun shut() {
            super.shut()
            if (clientWires.remove(key, this)) runCatching { gatt.disconnect(); gatt.close() }
        }
    }

    private inner class ClientCallback(private val key: String) : BluetoothGattCallback() {
        @Volatile var wire: ClientWire? = null

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> gatt.requestMtu(MTU)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    wire?.shut() ?: run { clientWires.remove(key); gatt.close() }
                    connecting.remove(key)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            wireMtu = mtu
            gatt.discoverServices()
        }
        @Volatile private var wireMtu = 23

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val tx = gatt.getService(SERVICE)?.getCharacteristic(TX)
            if (status != BluetoothGatt.GATT_SUCCESS || tx == null) { gatt.disconnect(); return }
            val w = ClientWire(key, gatt).apply { mtu = wireMtu }
            wire = w
            clientWires[key] = w
            connecting.remove(key)
            gatt.setCharacteristicNotification(tx, true)
            val cccd = tx.getDescriptor(CCCD)
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION") run { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; gatt.writeDescriptor(cccd) }
            }
            if (!ok) w.shut()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val w = wire ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) { w.shut(); return }
            scope.launch { runCatching { node.serve(w) }; w.shut() }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            wire?.writeDone?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == TX) wire?.feed(value)
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33 && characteristic.uuid == TX) wire?.feed(characteristic.value ?: return)
        }
    }
    private val connecting = ConcurrentHashMap.newKeySet<String>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val id = result.scanRecord?.getServiceData(ParcelUuid(SERVICE)) ?: return
            if (id.size != ID_LEN || compare(myId, id) >= 0) return // подключается меньший id
            val key = id.joinToString("") { "%02x".format(it) }
            if (clientWires.containsKey(key) || !connecting.add(key)) return
            result.device.connectGatt(ctx, false, ClientCallback(key), BluetoothDevice.TRANSPORT_LE)
                ?: connecting.remove(key)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {}

    companion object {
        val SERVICE: UUID = UUID.fromString("6d79732d-6d65-7368-0000-000000000001")
        val RX: UUID = UUID.fromString("6d79732d-6d65-7368-0000-000000000002")
        val TX: UUID = UUID.fromString("6d79732d-6d65-7368-0000-000000000003")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val ID_LEN = 8
        const val MTU = 517
        const val OP_TIMEOUT_MS = 5_000L

        fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        }

        fun hasPermissions(ctx: Context) = permissions().all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

        private fun compare(a: ByteArray, b: ByteArray): Int {
            for (i in a.indices) { val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff); if (d != 0) return d }
            return 0
        }
    }
}
