package com.voiceaccounting.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

// ==========================================
// ۱. لایه داده (DATA LAYER): معماری پایدار ROOM DB
// ==========================================
@Entity(tableName = "engineered_transactions")
data class MasterTransaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personName: String,
    val type: String,          // BUY (خرید), SELL (فروش)
    val amount: Double,        // مقدار ارز
    val currencyType: String,  // دلار، یورو، تومان
    val exchangeRate: Double,  // نرخ واحد
    val tomanAmount: Double,   // ارزش کل تومانی محاسبه شده
    val isDelivered: Boolean,  // وضعیت تحویل (تعهد ارزی)
    val audioPath: String? = null // آدرس فیزیکی ویس معامله
)

@Dao
interface MasterTransactionDao {
    @Query("SELECT * FROM engineered_transactions ORDER BY id DESC")
    fun fetchJournal(): Flow<List<MasterTransaction>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveTransaction(tx: MasterTransaction)
}

@Database(entities = [MasterTransaction::class], version = 1, exportSchema = false)
abstract class EngineeringDatabase : RoomDatabase() {
    abstract fun masterTransactionDao(): MasterTransactionDao
}

// ==========================================
// ۲. لایه کنترلر و سیستم سخت‌افزار صوتی برنامه
// ==========================================
class MainActivity : ComponentActivity() {
    private lateinit var engineeringDb: EngineeringDatabase
    private var mediaRecorder: MediaRecorder? = null
    private var currentVoicePath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        engineeringDb = Room.databaseBuilder(
            applicationContext,
            EngineeringDatabase::class.java, "production_voice_acc.db"
        ).fallbackToDestructiveMigration().build()

        setContent {
            var selectedTab by remember { mutableStateOf(0) }
            var voiceTranscriptText by remember { mutableStateOf("") }
            var isVoiceRecordingActive by remember { mutableStateOf(false) }
            val coroutineScope = rememberCoroutineScope()
            val currentContext = LocalContext.current

            val sttOverlayLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                    val speechToTextOutput = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0) ?: ""
                    voiceTranscriptText = speechToTextOutput
                }
                stopAudioRecording()
                isVoiceRecordingActive = false
            }

            val micPermissionRequest = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                if (!isGranted) {
                    Toast.makeText(currentContext, "دسترسی به میکروفون برای کارکرد بخش صوتی الزامی است", Toast.LENGTH_LONG).show()
                }
            }

            LaunchedEffect(Unit) {
                micPermissionRequest.launch(Manifest.permission.RECORD_AUDIO)
            }
            
            MaterialTheme(colorScheme = darkColorScheme()) {
                Scaffold(
                    bottomBar = {
                        NavigationBar(containerColor = Color(0xFF151922)) {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Icon(Icons.Default.Home, contentDescription = "دفتر روزنامه") },
                                label = { Text("دفتر روزنامه") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.Default.Person, contentDescription = "معین اشخاص") },
                                label = { Text("معین اشخاص") }
                            )
                        }
                    },
                    floatingActionButton = {
                        FloatingActionButton(
                            onClick = {
                                if (!isVoiceRecordingActive) {
                                    if (startAudioRecording()) {
                                        isVoiceRecordingActive = true
                                        try {
                                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                                                putExtra(RecognizerIntent.EXTRA_PROMPT, "معامله را با لحن طبیعی بگویید...")
                                            }
                                            sttOverlayLauncher.launch(intent)
                                        } catch (e: Exception) {
                                            Toast.makeText(currentContext, "گوگل STT روی گوشی شما فعال نیست.", Toast.LENGTH_SHORT).show()
                                            stopAudioRecording()
                                            isVoiceRecordingActive = false
                                        }
                                    }
                                }
                            },
                            containerColor = if (isVoiceRecordingActive) Color(0xFFFF5252) else Color(0xFF00E676),
                            shape = CircleShape
                        ) {
                            Text(if (isVoiceRecordingActive) "🛑" else "🎙️", fontSize = 26.sp)
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding).fillMaxSize().background(Color(0xFF0B0E14))) {
                        val liveTransactionsData by engineeringDb.masterTransactionDao().fetchJournal().collectAsState(initial = emptyList())

                        if (selectedTab == 0) {
                            EngineeredJournalScreen(
                                dataList = liveTransactionsData,
                                textState = voiceTranscriptText,
                                onTextStateChange = { voiceTranscriptText = it },
                                onCommitTx = { transaction ->
                                    coroutineScope.launch(Dispatchers.IO) {
                                        engineeringDb.masterTransactionDao().saveTransaction(transaction.copy(audioPath = currentVoicePath))
                                        currentVoicePath = null
                                        withContext(Dispatchers.Main) { voiceTranscriptText = "" }
                                    }
                                }
                            )
                        } else {
                            EngineeredLedgerScreen(dataList = liveTransactionsData)
                        }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun startAudioRecording(): Boolean {
        return try {
            val audioFolder = File(getExternalFilesDir(null), "CoreVoiceAccounting")
            if (!audioFolder.exists()) audioFolder.mkdirs()

            val file = File(audioFolder, "TX_AUDIO_${System.currentTimeMillis()}.mp3")
            currentVoicePath = file.absolutePath

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(applicationContext)
            } else {
                MediaRecorder()
            }
            
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(currentVoicePath)
            recorder.prepare()
            recorder.start()
            
            mediaRecorder = recorder
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun stopAudioRecording() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            mediaRecorder = null
        }
    }
}

// ==========================================
// ۳. لایه رابط کاربری (UI) - تب اصلی دفتر روزنامه
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineeredJournalScreen(
    dataList: List<MasterTransaction>,
    textState: String,
    onTextStateChange: (String) -> Unit,
    onCommitTx: (MasterTransaction) -> Unit
) {
    var mName by remember { mutableStateOf("") }
    var mAmount by remember { mutableStateOf("") }
    var mRate by remember { mutableStateOf("") }
    var mType by remember { mutableStateOf("SELL") }
    var mCurrency by remember { mutableStateOf("دلار") }
    var mDelivered by remember { mutableStateOf(true) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("📈 سیستم مانیتورینگ فاکتورها: ${dataList.size} سند معتبر", color = Color(0xFF00E676), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("🤖 پردازشگر گرامر صوتی (Regex Parsing Engine)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = textState,
                        onValueChange = textStateChange,
                        placeholder = { Text("متن ویس شما خودکار اینجا پردازش می‌شود...") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (textState.isBlank()) return@Button
                            
                            var name = "ناشناس"
                            val trimmed = textState.trim()
                            if (trimmed.contains("به ")) {
                                val after = trimmed.substringAfter("به ").trim()
                                name = if (after.contains(" ")) after.substringBefore(" ") else after
                            } else if (trimmed.contains("از ")) {
                                val after = trimmed.substringAfter("از ").trim()
                                name = if (after.contains(" ")) after.substringBefore(" ") else after
                            }
                            
                            val digitsList = "[0-9]+".toRegex().findAll(trimmed).map { it.value.toDoubleOrNull() ?: 0.0 }.toList()
                            val amount = if (digitsList.isNotEmpty()) digitsList[0] else 0.0
                            val rate = if (digitsList.size > 1) digitsList[1] else 1.0
                            
                            val currency = if (trimmed.contains("یورو")) "یورو" else if (trimmed.contains("تومان")) "تومان" else "دلار"
                            val type = if (trimmed.contains("خریدم") || trimmed.contains("بخر") || trimmed.contains("خریداری") || trimmed.contains("خرید")) "BUY" else "SELL"
                            val delivered = !trimmed.contains("تحویل نشد")

                            onCommitTx(
                                MasterTransaction(
                                    personName = name,
                                    type = type,
                                    amount = amount,
                                    exchangeRate = rate,
                                    currencyType = currency,
                                    tomanAmount = amount * rate,
                                    isDelivered = delivered
                                )
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                        modifier = Modifier.modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("تجزیه و ثبت نهایی سند صوتی", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("✏️ ماژول ثبت فاکتور به صورت دستی", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = mName, onValueChange = { mName = it }, label = { Text("نام شخص") }, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = mAmount, onValueChange = { mAmount = it }, label = { Text("مقدار") }, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                        OutlinedTextField(value = mRate, onValueChange = { mRate = it }, label = { Text("نرخ واحد") }, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = mCurrency, onValueChange = { mCurrency = it }, label = { Text("نوع ارز") }, modifier = Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(checked = mDelivered, onCheckedChange = { mDelivered = it })
                        Text("ارز تحویل فیزیکی داده شد", fontSize = 12.sp, color = Color.Gray)
                    }
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = mType == "SELL", onClick = { mType = "SELL" })
                            Text("فروش")
                            Spacer(modifier = Modifier.width(8.dp))
                            RadioButton(selected = mType == "BUY", onClick = { mType = "BUY" })
                            Text("خرید")
                        }
                        Button(onClick = {
                            val amt = mAmount.toDoubleOrNull() ?: 0.0
                            val rate = mRate.toDoubleOrNull() ?: 1.0
                            if (mName.isBlank() || amt <= 0) return@Button

                            onCommitTx(
                                MasterTransaction(
                                    personName = mName,
                                    type = mType,
                                    amount = amt,
                                    exchangeRate = rate,
                                    currencyType = mCurrency,
                                    tomanAmount = amt * rate,
                                    isDelivered = mDelivered
                                )
                            )
                            mName = ""; mAmount = ""; mRate = ""
                        }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))) {
                            Text("ثبت فاکتور دستی", color = Color.Black)
                        }
                    }
                }
            }
        }

        item { Text("📑 اسناد و ریزمعاملات دفتر روزنامه", color = Color.Gray, fontSize = 13.sp, fontWeight = FontWeight.Bold) }

        items(dataList) { tx ->
            val numFormat = NumberFormat.getInstance(Locale.US)
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF21262D)), shape = RoundedCornerShape(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(tx.personName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("نوع معامله: ${if(tx.type == "SELL") "فروش" else "خرید"} | ${tx.amount} ${tx.currencyType}", color = Color.LightGray, fontSize = 13.sp)
                        Text("نرخ واحد تبدیل: ${numFormat.format(tx.exchangeRate)} تومان", color = Color.Gray, fontSize = 12.sp)
                        Text(if(tx.isDelivered) "✓ ارز تحویل شد" else "❌ تعهد تسویه فیزیکی", color = if(tx.isDelivered) Color.Gray else Color(0xFFFFD700), fontSize = 11.sp)
                        
                        if (!tx.audioPath.isNullOrBlank()) {
                            Button(
                                onClick = {
                                    try {
                                        val player = MediaPlayer()
                                        player.setDataSource(tx.audioPath)
                                        player.prepare()
                                        player.start()
                                    } catch (e: Exception) {
                                        // امن‌سازی سخت‌افزار صوت
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F141C)),
                                modifier = Modifier.padding(top = 8.dp).height(30.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                            ) {
                                Text("▶️ شنیدن صدای واقعی معامله", color = Color(0xFF00E676), fontSize = 11.sp)
                            }
                        }
                    }
                    Text(
                        text = "${numFormat.format(tx.tomanAmount)} تومان",
                        color = if(tx.type == "BUY") Color(0xFFFF5252) else Color(0xFF00E676),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

// ==========================================
// ۴. لایه رابط کاربری (UI) - تب حساب معین افراد
// ==========================================
@Composable
fun EngineeredLedgerScreen(dataList: List<MasterTransaction>) {
    val namesList = dataList.map { it.personName }.distinct()

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("👥 دفتر معین تومانی و ارزی اشخاص (تراز زنده)", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) }

        items(namesList) { name ->
            val userTransactions = dataList.filter { it.personName == name }
            var balanceToman = 0.0
            userTransactions.forEach { tx ->
                if (tx.type == "SELL") balanceToman += tx.tomanAmount else balanceToman -= tx.tomanAmount
            }

            val label = if (balanceToman > 0) "بدهکار (Debtor)" else if (balanceToman < 0) "بستانکار (Creditor)" else "تراز صفر"
            val color = if (balanceToman > 0) Color(0xFFFF5252) else if (balanceToman < 0) Color(0xFF00E676) else Color.Gray
            val numFormat = NumberFormat.getInstance(Locale.US)

            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22))) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                    Text(name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.DarkGray))
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("آخرین وضعیت تراز مالی:", color = Color.Gray, fontSize = 14.sp)
                        Text("$label: ${numFormat.format(abs(balanceToman))} تومان", color = color, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
