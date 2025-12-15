package com.example.uart_screen

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import kotlin.math.abs
import kotlin.math.roundToInt

// --- CẤU HÌNH MÀU SẮC ---
val ColorBgRed = Color(0xFFFDEDEC)
val ColorBgBlue = Color(0xFFE8F6F3)
val ColorPanelRed = Color(0xFFFADBD8)
val ColorPanelBlue = Color(0xFFD6EAF8)
val ColorSidebar = Color(0xFFF7F9FA)

// Các khối
val ColorR1 = Color(0xFFFF69B4)
val ColorFake = Color(0xFFE74C3C)
val ColorReal = Color(0xFF27AE60)

// Màu nền lưới
val ColorGridDark = Color(0xFF1E4D1E)   // Màu xanh rêu tối (cho các ô chẵn)
val ColorGridOlive = Color(0xFF8F9E48)  // Màu Olive (cho ô 6, 8)
val ColorGridOdd = Color(42, 113, 56)   // MÀU MỚI: RGB(42, 113, 56) cho các ô lẻ

val ColorHighlight = Color(0xFFFFFF00) // Vàng chanh
val ColorTermBg = Color(0xFF2C3E50)
val ColorTermText = Color(0xFF2ECC71)

class MainActivity : ComponentActivity() {
    private val ACTION_USB_PERMISSION = "com.example.uart_screen.USB_PERMISSION"
    private var usbPort: UsbSerialPort? = null
    private var usbManager: UsbManager? = null

    // State trạng thái kết nối
    private val _isConnected = mutableStateOf(false)

    // --- 1. BROADCAST RECEIVER (XỬ LÝ TỰ ĐỘNG) ---
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (ACTION_USB_PERMISSION == action) {
                synchronized(this) {
                    val device: android.hardware.usb.UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.apply { connectUsb(true) }
                    } else {
                        Toast.makeText(context, "Quyền USB bị từ chối!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            // Tự động kết nối khi cắm dây hoặc bật OTG
            else if (UsbManager.ACTION_USB_DEVICE_ATTACHED == action) {
                connectUsb(false)
            }
            // Tự động ngắt khi rút dây
            else if (UsbManager.ACTION_USB_DEVICE_DETACHED == action) {
                disconnectUsb()
                Toast.makeText(context, "Đã ngắt kết nối", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        // Đăng ký lắng nghe: Quyền, Cắm dây, Rút dây
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)

        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Quét ngay khi mở app
        connectUsb(false)

        setContent {
            val connectionState by _isConnected

            RobotControlScreen(
                isConnected = connectionState,
                onSendUart = { lane, hasR1, frontR1, sideReal ->
                    sendDataOverUart(lane, hasR1, frontR1, sideReal)
                }
            )
        }
    }

    // Khi người dùng bật OTG xong quay lại app -> Tự kết nối lại
    override fun onResume() {
        super.onResume()
        if (!_isConnected.value) {
            connectUsb(false)
        }
    }

    // --- 2. HÀM KẾT NỐI THÔNG MINH ---
    private fun connectUsb(forceConnect: Boolean) {
        if (_isConnected.value) return

        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) return

        val driver = availableDrivers[0]

        // Kiểm tra quyền
        if (!forceConnect && !usbManager!!.hasPermission(driver.device)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager?.requestPermission(driver.device, permissionIntent)
            return
        }

        // Mở kết nối
        val connection = usbManager?.openDevice(driver.device) ?: return
        usbPort = driver.ports[0]
        try {
            usbPort?.open(connection)
            usbPort?.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            usbPort?.dtr = true
            usbPort?.rts = true
            _isConnected.value = true
            Toast.makeText(this, "Đã kết nối UART!", Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            try { usbPort?.close() } catch (ignored: IOException) {}
            usbPort = null
            _isConnected.value = false
        }
    }

    private fun disconnectUsb() {
        try { usbPort?.close() } catch (e: IOException) {}
        usbPort = null
        _isConnected.value = false
    }

   /////// --- 3. GỬI UART ---
    private fun sendDataOverUart(laneIdx: Int, hasR1: Boolean, frontR1: Boolean, sideReal: Boolean) {
        if (usbPort == null) {
            Toast.makeText(this, "Chưa kết nối UART! Hãy bật OTG.", Toast.LENGTH_LONG).show()
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (e:Exception){}
            return
        }
        val b1 = (laneIdx + 1).toByte()
        val b2 = if (hasR1) 1.toByte() else 0.toByte()
        val b3 = if (frontR1) 1.toByte() else 0.toByte()
        val b4 = if (sideReal) 1.toByte() else 0.toByte()
        val packet = ByteArray(7)
        packet[0] = 0xAA.toByte(); packet[1] = 4
        packet[2] = b1; packet[3] = b2; packet[4] = b3; packet[5] = b4
        val sum = 0xAA + 4 + b1 + b2 + b3 + b4
        packet[6] = (sum and 0xFF).toByte()

        try {
            for(i in 0 until 3) { usbPort?.write(packet, 100); Thread.sleep(20) }
            Toast.makeText(this, "Đã gửi lệnh!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            disconnectUsb()
            Toast.makeText(this, "Lỗi gửi: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (e: Exception) {}
        disconnectUsb()
    }
}

@Composable
fun RobotControlScreen(
    isConnected: Boolean,
    onSendUart: (Int, Boolean, Boolean, Boolean) -> Unit
) {
    val context = LocalContext.current

    // --- STATE ---
    var isSwitched by remember { mutableStateOf(false) }
    var selectedBlock by remember { mutableStateOf<String?>(null) }
    var squareValues by remember { mutableStateOf(mapOf<Int, String>()) }
    var selectedPathIndex by remember { mutableStateOf(-1) }
    var logs by remember { mutableStateOf(listOf("Hệ thống sẵn sàng...")) }

    // State Drag & Drop
    var draggingBlockType by remember { mutableStateOf<String?>(null) }
    var dragPosition by remember { mutableStateOf(Offset.Zero) }
    var dragSourceIndex by remember { mutableStateOf<Int?>(null) }

    val cellBounds = remember { mutableStateMapOf<Int, Rect>() }
    val cellCenters = remember { mutableStateMapOf<Int, Offset>() }
    val paletteCenters = remember { mutableStateMapOf<String, Offset>() }

    val numbersOriginal = listOf(listOf(10, 11, 12), listOf(7, 8, 9), listOf(4, 5, 6), listOf(1, 2, 3))
    val numbersSwitched = listOf(listOf(12, 11, 10), listOf(9, 8, 7), listOf(6, 5, 4), listOf(3, 2, 1))
    val currentGrid = if (isSwitched) numbersSwitched else numbersOriginal

    LaunchedEffect(isSwitched) { cellBounds.clear(); cellCenters.clear() }

    fun appendLog(msg: String) { logs = logs + "> $msg"; if(logs.size > 20) logs = logs.takeLast(20) }

    fun canAddBlock(type: String): Boolean {
        val count = squareValues.values.count { it == type }
        return when(type) { "Fake"->count<1; "Real"->count<4; "R1"->count<3; else->true }
    }

    fun handleCellClick(num: Int) {
        if (selectedBlock != null) {
            val currentBlock = squareValues[num]
            if (currentBlock == selectedBlock) {
                val newMap = squareValues.toMutableMap(); newMap.remove(num); squareValues = newMap
            } else {
                if (canAddBlock(selectedBlock!!)) {
                    val newMap = squareValues.toMutableMap(); newMap[num] = selectedBlock!!; squareValues = newMap
                } else {
                    Toast.makeText(context, "Đã đạt giới hạn $selectedBlock!", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            if (squareValues.containsKey(num)) {
                val newMap = squareValues.toMutableMap(); newMap.remove(num); squareValues = newMap
            }
        }
    }

    fun handleDrop() {
        if (draggingBlockType != null) {
            val targetCell = cellBounds.entries.firstOrNull { (_, rect) -> rect.contains(dragPosition) }
            if (targetCell != null) {
                val targetNum = targetCell.key
                if (dragSourceIndex == null) {
                    if (canAddBlock(draggingBlockType!!)) {
                        val newMap = squareValues.toMutableMap(); newMap[targetNum] = draggingBlockType!!; squareValues = newMap
                    } else { Toast.makeText(context, "Đã đạt giới hạn ${draggingBlockType}!", Toast.LENGTH_SHORT).show() }
                } else {
                    val newMap = squareValues.toMutableMap()
                    newMap.remove(dragSourceIndex!!)
                    newMap[targetNum] = draggingBlockType!!
                    squareValues = newMap
                }
            }
            draggingBlockType = null; dragPosition = Offset.Zero; dragSourceIndex = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxSize().background(if (isSwitched) ColorBgBlue else ColorBgRed).padding(5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. CỘT TRÁI (Công cụ)
            Column(
                modifier = Modifier.width(80.dp).fillMaxHeight().background(ColorSidebar, RoundedCornerShape(10.dp)).padding(5.dp),
                verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("R1", "Real", "Fake").forEach { type ->
                        val color = when(type) { "R1"->ColorR1; "Real"->ColorReal; else->ColorFake }
                        val isSelected = selectedBlock == type
                        Box(modifier = Modifier.onGloballyPositioned { paletteCenters[type] = it.boundsInRoot().center }) {
                            Button(
                                onClick = { selectedBlock = if (selectedBlock == type) null else type },
                                modifier = Modifier.size(60.dp).border(if (isSelected) 3.dp else 0.dp, Color.Black, RoundedCornerShape(10.dp))
                                    .pointerInput(Unit) {
                                        detectDragGestures(
                                            onDragStart = { dragPosition = paletteCenters[type] ?: Offset.Zero; draggingBlockType = type; dragSourceIndex = null },
                                            onDrag = { change, dragAmount -> change.consume(); dragPosition += dragAmount },
                                            onDragEnd = { handleDrop() }
                                        )
                                    },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) color else color.copy(alpha=0.6f)),
                                contentPadding = PaddingValues(0.dp)
                            ) { Text(type, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallBtn(
                        text = if (isSwitched) "Đỏ" else "Xanh",
                        color = if (isSwitched) Color(0xFFD9534F) else Color(0xFF00CCFF),
                        onClick = { isSwitched = !isSwitched; selectedPathIndex = -1; selectedBlock = null }
                    )
                    Button(onClick = { squareValues = emptyMap(); selectedPathIndex = -1; selectedBlock = null; appendLog("Reset All") },
                        shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF39C12)),
                        modifier = Modifier.size(50.dp), contentPadding = PaddingValues(0.dp)) { Text("Reset", fontSize = 10.sp) }
                }
            }

            // 2. CỘT GIỮA - ĐIỀU KHIỂN & LOG
            Column(modifier = Modifier.width(180.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {

                // Khoảng cách trên cùng
                Spacer(modifier = Modifier.height(50.dp))

                // LOG SCREEN
                Column(modifier = Modifier.fillMaxWidth().height(100.dp).background(ColorTermBg, RoundedCornerShape(8.dp)).border(2.dp, Color(0xFF34495E), RoundedCornerShape(8.dp)).padding(5.dp)) {
                    Text("SYSTEM LOG", color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Divider(color = Color.Gray, thickness = 0.5.dp)
                    val listState = rememberLazyListState()
                    LaunchedEffect(logs) { listState.animateScrollToItem(if(logs.isNotEmpty()) logs.lastIndex else 0) }
                    LazyColumn(state = listState) { items(logs.size) { i -> Text(logs[i], color = ColorTermText, fontSize = 10.sp, fontFamily = FontFamily.Monospace) } }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // --- TRẠNG THÁI USB (CHỈ LÀ ĐÈN BÁO, KHÔNG PHẢI NÚT BẤM) ---
                Card(
                    colors = CardDefaults.cardColors(containerColor = if (isConnected) Color(0xFF27AE60) else Color(0xFFE74C3C)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.width(120.dp).height(35.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(8.dp).background(if (isConnected) Color.Green else Color.White, CircleShape))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = if (isConnected) "USB: OK" else "NO USB", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
                if (!isConnected) { Text("(Bật OTG hoặc cắm dây)", color = Color.Gray, fontSize = 9.sp, modifier = Modifier.padding(top = 2.dp)) }

                Spacer(modifier = Modifier.height(20.dp))

                // --- NÚT START ---
                ActionButton(
                    text = "START ►",
                    color = Color(0xFF2ECC71)
                ) {
                    if (selectedPathIndex == -1) {
                        Toast.makeText(context, "Vui lòng chọn làn (▲) trước!", Toast.LENGTH_SHORT).show()
                        appendLog("⚠ Lỗi: Chưa chọn làn!")
                    } else {
                        val i = selectedPathIndex
                        val logicalLaneIndex = if (isSwitched) 2 - i else i
                        val displayedLane = logicalLaneIndex + 1
                        appendLog("🚀 Gửi lệnh Làn $displayedLane")
                        val cellsInLane = mutableListOf<Int>()
                        for (row in currentGrid.reversed()) cellsInLane.add(row[i])
                        val hasR1 = cellsInLane.any { squareValues[it] == "R1" }
                        var frontR1 = false
                        for (k in 1 until 4) if (squareValues[cellsInLane[k]] == "R1") { frontR1 = true; break }
                        var sideReal = false
                        val neighborCols = mutableListOf<Int>()
                        if (i == 0) neighborCols.add(1) else if (i == 2) neighborCols.add(1) else { neighborCols.add(0); neighborCols.add(2) }
                        for (ncol in neighborCols) { for (row in currentGrid) { val cellNum = row[ncol]; if (squareValues[cellNum] == "Real") { sideReal = true; break } } }
                        onSendUart(logicalLaneIndex, hasR1, frontR1, sideReal)
                    }
                }

                // --- FOOTER CREDITS (ĐÃ CẬP NHẬT) ---
                Spacer(modifier = Modifier.weight(1f)) // Đẩy xuống đáy
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 10.dp)) {
                    Text("Supervisor: Nguyen Dinh Dai", fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Medium)
                    Text("Developed by the IDS Team", fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Normal)
                }
            }

            // 3. CỘT PHẢI (Bàn cờ)
            Column(modifier = Modifier.weight(1f).fillMaxHeight().background(if(isSwitched) ColorPanelBlue else ColorPanelRed, RoundedCornerShape(12.dp)).padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (row in currentGrid) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (num in row) {
                                key(num) {
                                    val visualColIdx = row.indexOf(num)
                                    val isSelectedLane = visualColIdx == selectedPathIndex

                                    // --- LOGIC MÀU SẮC MỚI ---
                                    var cellColor = ColorGridDark
                                    if (num % 2 != 0) { // Nếu là số lẻ (1, 3, 5, 7, 9, 11) -> Màu Mới
                                        cellColor = ColorGridOdd
                                    } else if (listOf(6, 8).contains(num)) { // Nếu là 6 hoặc 8 -> Màu Olive cũ
                                        cellColor = ColorGridOlive
                                    }

                                    var isHighlight = false
                                    if (selectedPathIndex != -1) {
                                        val isNeighbor = abs(visualColIdx - selectedPathIndex) == 1
                                        if (isNeighbor && squareValues[num] == "Real") isHighlight = true
                                    }
                                    val blockType = squareValues[num]
                                    val isBeingDragged = (dragSourceIndex == num && draggingBlockType != null)

                                    Box(
                                        modifier = Modifier.size(64.dp)
                                            .onGloballyPositioned { cellBounds[num] = it.boundsInRoot(); cellCenters[num] = it.boundsInRoot().center }
                                            .background(if (isSelectedLane) ColorHighlight else Color.Transparent, RoundedCornerShape(8.dp))
                                            .padding(if (isSelectedLane) 3.dp else 0.dp)
                                            .background(cellColor, RoundedCornerShape(6.dp))
                                            .border(if(isHighlight) 3.dp else 0.dp, ColorHighlight, RoundedCornerShape(6.dp))
                                            .pointerInput(Unit) { detectTapGestures(onTap = { handleCellClick(num) }) }
                                            .pointerInput(blockType) {
                                                if (blockType != null) {
                                                    detectDragGestures(
                                                        onDragStart = { dragPosition = cellCenters[num] ?: Offset.Zero; draggingBlockType = blockType; dragSourceIndex = num },
                                                        onDrag = { change, dragAmount -> change.consume(); dragPosition += dragAmount },
                                                        onDragEnd = { handleDrop() }
                                                    )
                                                }
                                            }, contentAlignment = Alignment.Center
                                    ) {
                                        if (blockType != null && !isBeingDragged) {
                                            Box(modifier = Modifier.fillMaxSize().padding(2.dp).background(when(blockType) { "R1"->ColorR1; "Fake"->ColorFake; else->ColorReal }, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                                                Text(blockType, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            }
                                        } else if (blockType == null) { Text(num.toString(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))

                // --- HÀNG NÚT MŨI TÊN ---
                Row(horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                    for (i in 0..2) {
                        Button(
                            onClick = { selectedPathIndex = i; selectedBlock = null; appendLog("Đã chọn làn ${i + 1}") },
                            modifier = Modifier.size(64.dp, 35.dp),
                            shape = RoundedCornerShape(5.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if(isSwitched) Color(0xFF3498DB) else Color(0xFFD9534F)),
                            border = if(selectedPathIndex == i) BorderStroke(3.dp, ColorHighlight) else null,
                            contentPadding = PaddingValues(0.dp)
                        ) { Text("▲", fontSize = 12.sp, color = Color.White) }
                    }
                }
            }
        }
        if (draggingBlockType != null) {
            Box(modifier = Modifier.offset { IntOffset(dragPosition.x.roundToInt() - 75, dragPosition.y.roundToInt() - 75) }) {
                Box(modifier = Modifier.size(50.dp).background(when (draggingBlockType) { "R1" -> ColorR1.copy(alpha = 0.9f); "Fake" -> ColorFake.copy(alpha = 0.9f); else -> ColorReal.copy(alpha = 0.9f) }, RoundedCornerShape(8.dp)).border(2.dp, Color.White, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { Text(draggingBlockType!!, color = Color.White, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

// --- Helper Buttons ---
@Composable fun SmallBtn(text: String, color: Color, onClick: () -> Unit) { Button(onClick = onClick, modifier = Modifier.width(60.dp).height(35.dp), shape = RoundedCornerShape(6.dp), colors = ButtonDefaults.buttonColors(containerColor = color), contentPadding = PaddingValues(0.dp)) { Text(text, fontSize = 10.sp) } }
@Composable fun ActionButton(text: String, color: Color, onClick: () -> Unit) { Button(onClick = onClick, modifier = Modifier.width(100.dp).height(40.dp), shape = RoundedCornerShape(6.dp), colors = ButtonDefaults.buttonColors(containerColor = color), contentPadding = PaddingValues(0.dp)) { Text(text, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold) } }