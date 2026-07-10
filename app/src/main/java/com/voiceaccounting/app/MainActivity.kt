package com.voiceaccounting.app

import android.Manifest
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.Room
import com.voiceaccounting.app.data.AppDatabase
import com.voiceaccounting.app.data.model.Counterparty
import com.voiceaccounting.app.data.model.Transaction
import com.voiceaccounting.app.domain.VoiceProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
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

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                if (!isGranted) {
                    Toast.makeText(this, "دسترسی میکروفون تایید نشد", Toast.LENGTH_LONG).show()
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
                                icon = { Icon(Icons.Default.Home, contentDescription = "روزنامه") },
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
                                    if (startRecording()) {
                                        isRecording = true
                                        Toast.makeText(this@MainActivity, "در حال ضبط واقعی صدا...", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    stopRecording()
                                    isRecording = false
                                    Toast.makeText(this@MainActivity, "صدا ذخیره شد. متن را وارد و ثبت کنید.", Toast.LENGTH_LONG).show()
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

    @Suppress("DEPRECATION")
    private fun startRecording(): Boolean {
        return try {
            val outputDir = File(getExternalFilesDir(null), "VoiceAccounting")
            if (!outputDir.exists()) outputDir.mkdirs()

            val timeStamp = SimpleDateFormat("HH-mm-ss", Locale.US).format(Date())
            val audioFile = File(outputDir, "AUDIO_$timeStamp.mp3")
            currentAudioPath = audioFile.absolutePath

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(applicationContext)
            } else {
                MediaRecorder()
            }
            
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(currentAudioPath)
            recorder.prepare()
            recorder.start()
            
            mediaRecorder = recorder
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            mediaRecorder = null
        }
    }

    private fun playAudio(path: String) {
        try {
            mediaPlayer?.release()
            val player = MediaPlayer()
            player.setDataSource(path)
            player.prepare()
            player.start()
            mediaPlayer = player
        } catch (e: Exception) {
            e.printStackTrace()
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
        Text("تعداد کل فاکتورها: ${transactions.size}", color = Color(0xFF00E676), fontSize = 12.sp)
        
        OutlinedTextField(
            value = textInput,
            onValueChange = { textInput = it },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            placeholder = { Text("متن صوتی فاکتور را اینجا بنویسید") },
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
                }) { Icon(Icons.Default.Add, "ثبت") }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))
        
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = manualName, onValueChange = { manualName = it }, label = { Text("نام شخص") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = manualAmount, onValueChange = { manualAmount = it }, label = { Text("مقدار") }, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    OutlinedTextField(value = manualRate, onValueChange = { manualRate = it }, label = { Text("نرخ") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = manualCurrency, onValueChange = { manualCurrency = it }, label = { Text("ارز") }, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { manualType = if(manualType == "SELL") "BUY" else "SELL" }) {
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
                    }) {
                        Text("ثبت دستی")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

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
                Text(partyName, color = Color.White, fontWeight = FontWeight.Bold)
                Text("${tx.amount} ${tx.currencyType} (نرخ: ${tx.exchangeRate})", color = Color.Gray, fontSize = 13.sp)
                if (!tx.audioFilePath.isNullOrBlank()) {
                    Button(onClick = { onPlayAudio(tx.audioFilePath) }, modifier = Modifier.padding(top = 4.dp)) {
                        Text("▶️ پخش صدای معامله", fontSize = 11.sp)
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                val fmt = NumberFormat.getInstance(Locale.US)
                Text("${fmt.format(tx.tomanAmount)} تومان", color = if(tx.type == "BUY") Color.Red else Color.Green, fontWeight = FontWeight.Bold)
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
        items(list) { cp ->
            val personTxs = transactions.filter { it.counterpartyId == cp.id }
            var totalToman = 0.0
            personTxs.forEach { tx ->
                if (tx.type == "SELL" || tx.type == "RECEIVE_TOMAN") totalToman += tx.tomanAmount
                else if (tx.type == "BUY" || tx.type == "PAY_TOMAN") totalToman -= tx.tomanAmount
            }

            val statusText = if (totalToman > 0) "بدهکار" else if (totalToman < 0) "بستانکار" else "تراز صفر"
            val fmt = NumberFormat.getInstance(Locale.US)

            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                    Text(cp.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.DarkGray))
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("وضعیت تراز مالی:", color = Color.Gray)
                        Text("$statusText: ${fmt.format(abs(totalToman))} تومان", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
