package com.voiceaccounting.app

import android.Manifest
import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.Room
import com.voiceaccounting.app.data.AppDatabase
import com.voiceaccounting.app.data.model.Counterparty
import com.voiceaccounting.app.data.model.Transaction
import com.voiceaccounting.app.domain.VoiceProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private lateinit var db: AppDatabase
    private val voiceProcessor = VoiceProcessor()
    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentAudioPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "v_acc.db")
            .fallbackToDestructiveMigration().build()

        setContent {
            var selectedTab by remember { mutableStateOf(0) }
            var isRecording by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                if (!isGranted) {
                    Toast.makeText(this, "برای ضبط صدا باید دسترسی میکروفون را تایید کنید", Toast.LENGTH_LONG).show()
                }
            }

            LaunchedEffect(Unit) {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            
            MaterialTheme(colorScheme = darkColorScheme()) {
                Scaffold(
                    bottomBar = {
                        NavigationBar(containerColor = Color(0xFF1A1A1A)) {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Icon(Icons.Default.List, contentDescription = "دفتر روزنامه") },
                                label = { Text("روزنامه") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.Default.Person, contentDescription = "اشخاص") },
                                label = { Text("اشخاص") }
                            )
                        }
                    },
                    floatingActionButton = {
                        FloatingActionButton(
                            onClick = {
                                if (!isRecording) {
                                    val success = startRecording()
                                    if (success) {
                                        isRecording = true
                                        Toast.makeText(this@MainActivity, "در حال ضبط صدا... (دوباره کلیک کنید تا متوقف شود)", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    stopRecording()
                                    isRecording = false
                                    Toast.makeText(this@MainActivity, "صدا ضبط و ذخیره شد. اکنون متن آن را در کادر بنویسید تا فاکتور صوتی ثبت شود.", Toast.LENGTH_LONG).show()
                                }
                            },
                            containerColor = if (isRecording) Color.Red else Color(0xFF00E676),
                            shape = CircleShape
                        ) {
                            Text(if (isRecording) "🛑" else "🎙️", fontSize = 24.sp)
                        }
                    }
                ) { padding ->
                    Box(modifier = Modifier.padding(padding).fillMaxSize().background(Color(0xFF121212))) {
                        if (selectedTab == 0) {
                            JournalScreen(
                                db = db,
                                processor = voiceProcessor,
                                audioPathToSave = currentAudioPath,
                                onPlayAudio = { path -> playAudio(path) },
                                onClearAudioPath = { currentAudioPath = null }
                            )
                        } else {
                            CounterpartiesScreen(db)
                        }
                    }
                }
            }
        }
    }

    private fun startRecording(): Boolean {
        return try {
            val outputDir = File(getExternalFilesDir(null), "VoiceAccounting")
            if (!outputDir.exists()) outputDir.mkdirs()

            val timeStamp = SimpleDateFormat("HH-mm-ss", Locale.US).format(Date())
            val audioFile = File(outputDir, "AUDIO_$timeStamp.mp3")
            currentAudioPath = audioFile.absolutePath

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(applicationContext)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(currentAudioPath)
                prepare()
                start()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "خطا در ضبط صدا: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            mediaRecorder = null
        }
    }

    private fun playAudio(path: String) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(path)
                prepare()
                start()
            }
            Toast.makeText(this, "در حال پخش صدای معامله...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "خطا در پخش فایل صوتی یا فایل حذف شده است", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaRecorder?.release()
        mediaPlayer?.release()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(
    db: AppDatabase, 
    processor: VoiceProcessor, 
    audioPathToSave: String?,
    onPlayAudio: (String) -> Unit,
    onClearAudioPath: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var transactions by remember { mutableStateOf(listOf<Transaction>()) }
    var counterparties by remember { mutableStateOf(listOf<Counterparty>()) }
    var textInput by remember { mutableStateOf("") }

    var manualName by remember { mutableStateOf("") }
    var manualAmount by remember { mutableStateOf("") }
    var manualRate by remember { mutableStateOf("") }
    var manualType by remember { mutableStateOf("SELL") }
    var manualCurrency by remember { mutableStateOf("دلار") }

    LaunchedEffect(Unit) {
        db.transactionDao().getAllTransactionsJournal().collect { transactions = it }
    }
    LaunchedEffect(Unit) {
        db.counterpartyDao().getAllCounterparties().collect { counterparties = it }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("🗄️ تعداد کل فاکتورها در دیتابیس: ${transactions.size}", color = Color(0xFF00E676), fontSize = 12.sp)
        
        if (audioPathToSave != null) {
            Text("🎙️ یک فایل صوتی ضبط شده آماده اتصال به فاکتور است.", color = Color.Yellow, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text("🎙️ پردازش متن و تایید فاکتور صوتی", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        
        OutlinedTextField(
            value = textInput,
            onValueChange = { textInput = it },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            placeholder = { Text("متن فاکتور: فروختم ۱۰۰۰ دلار در ۱۵۵ به جعفر تحویل نشد") },
            trailingIcon = {
                IconButton(onClick = {
                    if (textInput.isBlank()) return@IconButton
                    scope.launch(Dispatchers.IO) {
                        var personName = ""
                        if (textInput.contains("به ")) personName = textInput.substringAfter("به ").substringBefore(" ")
                        if (textInput.contains("از ")) personName = textInput.substringAfter("از ").substringBefore(" ")
                        if (personName.isBlank()) personName = "ناشناس"

                        var cp = db.counterpartyDao().getByName(personName)
                        if (cp == null) {
                            val id = db.counterpartyDao().insert(Counterparty(name = personName))
                            cp = Counterparty(id = id, name = personName)
                        }
                        
                        val tx = processor.processVoiceText(textInput) { cp.id }
                        if (tx != null) {
                            db.transactionDao().insert(tx.copy(counterpartyId = cp.id, audioFilePath = audioPathToSave))
                            onClearAudioPath()
                        }
                        withContext(Dispatchers.Main) { textInput = "" }
                    }
                }) { Icon(Icons.Default.Add, "ثبت فاکتور صوتی", tint = Color(0xFF00E676)) }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))
        Text("✏️ ثبت فاکتور دستی (بدون نیاز به صدا)", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = manualName, onValueChange = { manualName = it }, label = { Text("نام شخص") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = manualAmount, onValueChange = { manualAmount = it }, label = { Text("مقدار") }, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    OutlinedTextField(value = manualRate, onValueChange = { manualRate = it }, label = { Text("نرخ واحد") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = manualCurrency, onValueChange = { manualCurrency = it }, label = { Text("نوع ارز") }, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { manualType = if(manualType == "SELL") "BUY" else "SELL" }, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) {
                        Text(if(manualType == "SELL") "نوع: فروش" else "نوع: خرید")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Button(onClick = {
                        val amount = manualAmount.toDoubleOrNull() ?: 0.0
                        val rate = manualRate.toDoubleOrNull() ?: 1.0
                        if (manualName.isBlank() || amount <= 0) return@Button
                        
                        scope.launch(Dispatchers.IO) {
                            var cp = db.counterpartyDao().getByName(manualName)
                            if (cp == null) {
                                val id = db.counterpartyDao().insert(Counterparty(name = manualName))
                                cp = Counterparty(id = id, name = manualName)
                            }
                            val newTx = Transaction(
                                type = manualType,
                                counterpartyId = cp.id,
                                amount = amount,
                                currencyType = manualCurrency,
                                exchangeRate = rate,
                                tomanAmount = amount * rate,
                                isDelivered = true,
                                timestamp = System.currentTimeMillis()
                            )
                            db.transactionDao().insert(newTx)
                            withContext(Dispatchers.Main) {
                                manualName = ""; manualAmount = ""; manualRate = ""
                            }
                        }
                    }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))) {
                        Text("ثبت فاکتور دستی", color = Color.Black)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text("📑 دفتر روزنامه (لیست معاملات)", color = Color.Gray, fontSize = 14.sp)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(transactions) { tx ->
                val name = counterparties.find { it.id == tx.counterpartyId }?.name ?: "ناشناس"
                TransactionItem(tx, name, onPlayAudio)
            }
        }
    }
}

@Composable
fun TransactionItem(tx: Transaction, partyName: String, onPlayAudio: (String) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF252525))) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("طرف حساب: $partyName", color = Color.White, fontWeight = FontWeight.Bold)
                Text("${tx.amount} ${tx.currencyType} (نرخ: ${tx.exchangeRate})", color = Color.Gray, fontSize = 13.sp)
                
                if (!tx.audioFilePath.isNullOrBlank()) {
                    Button(
                        onClick = { onPlayAudio(tx.audioFilePath) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A1A)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.padding(top = 4.dp).height(28.dp)
                    ) {
                        Text("▶️ پخش صدای معامله", color = Color(0xFF00E676), fontSize = 11.sp)
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                val fmt = NumberFormat.getInstance(Locale.US)
                Text("${fmt.format(tx.tomanAmount)} تومان", color = if(tx.type == "BUY" || tx.type == "PAY_TOMAN") Color(0xFFFF5252) else Color(0xFF00E676), fontWeight = FontWeight.Bold)
                Text(if(tx.isDelivered) "✓ تحویل شد" else "❌ تعهد تحویل", fontSize = 11.sp, color = Color.LightGray)
            }
        }
    }
}

@Composable
fun CounterpartiesScreen(db: AppDatabase) {
    var list by remember { mutableStateOf(listOf<Counterparty>()) }
    var transactions by remember { mutableStateOf(listOf<Transaction>()) }

    LaunchedEffect(Unit) {
        db.counterpartyDao().getAllCounterparties().collect { list = it }
    }
    LaunchedEffect(Unit) {
        db.transactionDao().getAllTransactionsJournal().collect { transactions = it }
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("👥 دفتر معین تومانی اشخاص", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        items(list) { cp ->
            val personTxs = transactions.filter { it.counterpartyId == cp.id }
            var totalToman = 0.0
            personTxs.forEach { tx ->
                if (tx.type == "SELL" || tx.type == "RECEIVE_TOMAN") totalToman += tx.tomanAmount
                else if (tx.type == "BUY" || tx.type == "PAY_TOMAN") totalToman -= tx.tomanAmount
            }

            val statusText = if (totalToman > 0) "بدهکار (Debtor)" else if (totalToman < 0) "بستانکار (Creditor)" else "تراز صفر"
            val statusColor = if (totalToman > 0) Color(0xFFFF5252) else if (totalToman < 0) Color(0xFF00E676) else Color.Gray
            val fmt = NumberFormat.getInstance(Locale.US)

            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                    Text(cp.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.DarkGray)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("وضعیت حساب:", color = Color.Gray)
                        Text("$statusText: ${fmt.format(abs(totalToman))} تومان", color = statusColor, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
