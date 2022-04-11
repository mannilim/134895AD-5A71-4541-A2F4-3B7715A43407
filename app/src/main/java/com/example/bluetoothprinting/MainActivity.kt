package com.example.bluetoothprinting

import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import wpprinter.printer.WpPrinter as PosSDK

class MainActivity : AppCompatActivity() {

    private var connectingStartTime = System.currentTimeMillis()
    private var connectingFinishTime = System.currentTimeMillis()
    private var address = ""
    private lateinit var builder:AlertDialog.Builder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        builder = AlertDialog.Builder(this)
        setContent { BaseLayout() }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // 關閉應用
    private fun quitApp() {
        finish()
    }

    // 範例票內容
    private fun sampleInvoice(): ByteArray {
        val connectingTime = connectingFinishTime - connectingStartTime

        return binaryOf(
            0x1b, 0x40,
            0x1d, 0x61, 0xff,
            0x1b, 0x02,
            0x1b, 0x61, 1,
            0x1d, 0x21, 0x00,
            "Current connection to\r\n", 0x1b, 0x4a, 16,
            0x1d, 0x21, 0x11,
            "$address\r\n", 0x1b, 0x4a, 40,
            0x1d, 0x21, 0x00,
            "takes\r\n", 0x1b, 0x4a, 16,
            0x1d, 0x21, 0x44,
            connectingTime.toString().padStart(5, '_'),
            0x1d, 0x21, 0x11,
            "ms\r\n", 0x1b, 0x4a, 160,
            0x1b, "m",
            0x1d, 0x07, 2, 1, 1,
        )
    }

    // binary數據轉換
    private fun binaryOf(vararg items: Any): ByteArray {
        val buffer = mutableListOf<Byte>()
        for (item in items) {
            when (item) {
                is Boolean -> buffer += if (item) 1 else 0
                is Number -> buffer += item.toByte()
                is String -> item.encodeToByteArray().forEach { buffer += it }
                is ByteArray -> item.forEach { buffer += it }
                else -> {
                    println("${item::class.qualifiedName}")
                    buffer.clear()
                    break
                }
            }
        }
        return buffer.toByteArray()
    }

    // 顯示訊息對話框
    private fun popupAlert(title: String, message: String) {
        runOnUiThread {
            val alert: AlertDialog =
                builder.setMessage(message).setTitle(title).create()
            alert.show()
        }
    }

    // 應用主程式段
    @Composable
    fun BaseLayout() {

        lateinit var printer: PosSDK
        val maxPrintingLength = 12f
        val defaultPrintingLength = 3
        var autoGenerateInvoice = false

        var printingLengthSlider by remember { mutableStateOf(defaultPrintingLength / maxPrintingLength) }

        var isClaiming by remember { mutableStateOf(false) }
        var isClaimed by remember { mutableStateOf(false) }
        var isReleasing by remember { mutableStateOf(false) }

        // 結束藍芽裝置連線
        fun releaseDevice() {
            when {
                !isClaiming && !isReleasing && isClaimed -> {
                    Log.i("winpos_example", "releasing device...")
                    isReleasing = true
                    printer.disconnect()
                }
                else -> return
            }
        }

        // 建立藍芽裝置連線
        fun claimDevice(device: BluetoothDevice) {
            when {
                !isClaiming && !isReleasing && !isClaimed -> {
                    connectingStartTime = System.currentTimeMillis()
                    address = device.address
                    Log.i("winpos_example", "claiming device...")
                    isClaiming = true
                    printer.connect(address)
                }
                else -> return
            }
        }

        // 取得已配對裝置清單
        fun onDevicesRetrieved(devices: Set<*>?) {
            devices?.filterIsInstance<BluetoothDevice>()?.firstOrNull()?.also { device ->
                Log.i("winpos_example", "${device.address} retrieved.\n")
                claimDevice(device)
            } ?: kotlin.run {
                Log.w("winpos_example", "no paired device found.")
                popupAlert("Device State", "No paired device found.")
            }
        }

        // 詢問已配對藍芽裝置
        fun retrievePairedBluetoothDevices() {
            Log.i("winpos_example", "retrieving device...")
            printer.findBluetoothPrinters()
        }

        // 虛擬列印時長換算
        fun fakePrintingTime(): Int {
            return (printingLengthSlider * maxPrintingLength).toInt()
        }

        // 模擬出票
        fun processPrinting() {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                Log.i("winpos_example", "printing invoice.")

                when {
                    autoGenerateInvoice -> printer.executeDirectIo(sampleInvoice(), true)
                    else -> {
                        val limit = fakePrintingTime()
                        for (count in 1..limit) {
                            printer.executeDirectIo(byteArrayOf(0x1d, 0x07, 1, 1, 1), true)
                            delay(1000)
                        }
                    }
                }
                when {
                    !isClaimed -> {
                        Log.i("winpos_example", "failed printing invoice.\n")
                        popupAlert("Invoice State", "Failed printing invoice.")
                    }
                    else -> {
                        Log.i("winpos_example", "invoice printed.\n")
                        releaseDevice()
                    }
                }

            }
        }

        // 成功連線
        fun onDeviceClaimed() {
            connectingFinishTime = System.currentTimeMillis()
            Log.i("winpos_example", "device claimed( 1-2 ).\n")
            isClaiming = false
            isClaimed = true
            processPrinting()
        }

        // 連線失敗
        fun onFailedClaimingDevice() {
            connectingFinishTime = System.currentTimeMillis()
            Log.w("winpos_example", "failed claiming device( 1-0 ).\n")
            popupAlert("Device State", "Failed claiming device.")
            isClaiming = false
        }

        // 順利結束連線
        fun onDeviceReleased() {
            Log.i("winpos_example", "device released( 1-0 ).\n")
            CoroutineScope(Dispatchers.Default).launch {
                delay(600)
                isReleasing = false
                isClaimed = false
            }
        }

        // 連線非預期中斷
        fun onLostConnection() {
            Log.e("winpos_example", "lost connection( 1-0 ).\n")
            popupAlert("Device State", "Lost connection")
            isClaimed = false
        }

        // 點擊事件
        fun onButtonClicked(fakePrinting: Boolean = false) {
            autoGenerateInvoice = !fakePrinting
            retrievePairedBluetoothDevices()
        }

        // 應用無關的回傳
        fun onUninterestedMessage(message: Message) {
            val payload = message.data
            val received = payload.getByteArray(PosSDK.KEY_STRING_DIRECT_IO) ?: byteArrayOf()
            when {
                received.isNotEmpty() -> {
                    Log.i(
                        "winpos_example",
                        "message callback( ${message.what}-${message.arg1}-$received )."
                    )
                }
                !payload.isEmpty -> {
                    Log.i(
                        "winpos_example",
                        "message callback( ${message.what}-${message.arg1}-$payload )."
                    )
                }
                else -> {
                    Log.i("winpos_example", "message callback( ${message.what}-${message.arg1} ).")
                }
            }
        }

        // 接受並處理sdkLib回傳
        Handler(Looper.getMainLooper(), Handler.Callback { message ->
            when (message.what) {
                PosSDK.MESSAGE_BLUETOOTH_DEVICE_SET -> onDevicesRetrieved(message.obj as? Set<*>)
                PosSDK.MESSAGE_STATE_CHANGE -> {
                    when (message.arg1) {
                        PosSDK.STATE_CONNECTED -> true
                        PosSDK.STATE_NONE -> false
                        else -> null
                    }?.also { stateClaimed ->
                        when {
                            isClaiming && stateClaimed -> onDeviceClaimed()
                            isClaiming && !stateClaimed -> onFailedClaimingDevice()
                            isReleasing && !stateClaimed -> onDeviceReleased()
                            isClaimed && !stateClaimed -> onLostConnection()
                            else -> onUninterestedMessage(message)
                        }
                    } ?: run {
                        onUninterestedMessage(message)
                    }
                }
                else -> onUninterestedMessage(message)
            }
            return@Callback true
        }).also { handler ->
            printer = PosSDK(applicationContext, handler, null)
        }

        // 應用程式主介面
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            Button(
                modifier = Modifier
                    .fillMaxWidth(),
                onClick = { onButtonClicked() },
                content = { Text("SAMPLE INVOICE PRINTING") },
                enabled = !isClaiming && !isClaimed && !isReleasing && (fakePrintingTime() > 0),
            )
            Button(
                modifier = Modifier
                    .fillMaxWidth(),
                onClick = { onButtonClicked(true) },
                content = { Text("FAKE PRINTING LAST FOR ${fakePrintingTime()} SECOND(S)") },
                enabled = !isClaiming && !isClaimed && !isReleasing && (fakePrintingTime() > 0),
            )
            Slider(
                value = printingLengthSlider,
                onValueChange = { printingLengthSlider = it },
                enabled = !isClaiming && !isClaimed && !isReleasing && (fakePrintingTime() > 0),
            )
            TextButton(
                modifier = Modifier
                    .fillMaxWidth(),
                onClick = { quitApp() },
                content = { Text("QUIT APPLICATION") },
                enabled = !isClaiming && !isClaimed && !isReleasing,
            )
        }
    }
}