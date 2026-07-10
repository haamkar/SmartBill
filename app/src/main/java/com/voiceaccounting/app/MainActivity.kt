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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

// ==========================================
// ۱. ساختار دیتابیس خودمختار و اختصاصی برنامه
// ==========================================
@Entity(tableName = "secure_voice_transactions")
data class LocalTransaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personName: String,
    val type: String, // BUY, SELL
    val amount: Double,
    val rate: Double,
    val currency: String,
    val tomanAmount: Double,
    val audioPath: String? = null
)

@Dao
interface LocalTransactionDao {
    @Query("SELECT * FROM secure_voice_transactions ORDER BY id DESC")
    fun getAllTransactions(): kotlinx.coroutines.flow.Flow<List<LocalTransaction>>

    @Insert
    fun insertTransaction(tx: LocalTransaction)
}

@Database(entities = [LocalTransaction::class], version = 1, exportSchema = false)
abstract class LocalAppDatabase : RoomDatabase() {
    abstract fun localTransactionDao(): LocalTransactionDao
}

// ==========================================
// ۲. بدنه اصلی نرم افزار و مدیریت سخت افزار صوت
// ==========================================
class MainActivity : ComponentActivity() {
    private lateinit var localDb: LocalAppDatabase
    private var mediaRecorder: MediaRecorder? = null
    private var currentAudioPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        localDb = Room.databaseBuilder(applicationContext, LocalAppDatabase::class.java, "local_v_acc_secure.db")
            .fallbackToDestructiveMigration().build()

        setContent {
            var selectedTab by remember { mutableStateOf(0) }
            var isRecording by remember { mutableStateOf(false) }
            var textInputState by remember { mutableStateOf("") }
            val scope = rememberCoroutineScope()

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                if (!isGranted) {
                    Toast.makeText(this, "دسترسی میکروفون داده نشد!", Toast.LENGTH_LONG).show()
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
                                        Toast.makeText(this@MainActivity, "🎙️ در حال ضبط صدای معامله...", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    stopRecording()
                                    isRecording = false
                                    Toast.makeText(this@MainActivity, "✅ صدا ذخیره شد! جزئیات را وارد کنید.", Toast.LENGTH_LONG).show()
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
                        // دریافت زنده کل اطلاعات از دیتابیس اختصاصی
                        val transactionsList by localDb.localTransactionDao().getAllTransactions().collectAsState(initial = emptyList())

                        if (selectedTab == 0) {
                            JournalTabScreen(
                                transactions = transactionsList,
                                textInput = textInputState,
                                onTextInputChange = { textInputState = it },
                                onAddTransaction = { tx ->
                                    scope.launch(Dispatchers.IO) {
                                        localDb.localTransactionDao().insertTransaction(tx.copy(audioPath = currentAudioPath))
                                        currentAudioPath = null
                                        withContext(Dispatchers.Main) { textInputState = "" }
                                    }
                                }
                            )
                        } else {
                            CounterpartiesTabScreen(transactions = transactionsList)
                        }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun startRecording(): Boolean {
        return try {
            val outputDir = File(getExternalFilesDir(null), "SecureVoiceStorage")
            if (!outputDir.exists()) outputDir.mkdirs()

            val audioFile = File(outputDir, "REC_${System.currentTimeMillis()}.mp3")
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
}

// ==========================================
// ۳. رابط کاربری تب دفتر روزنامه (ثبت صوتی و دستی)
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalTabScreen(
    transactions: List<LocalTransaction>,
    textInput: String,
    onTextInputChange: (String) -> Unit,
    onAddTransaction: (LocalTransaction) -> Unit
) {
    var manualName by remember { mutableStateOf("") }
    var manualAmount by remember { mutableStateOf("") }
    var manualRate by remember { mutableStateOf("") }
    var manualType by remember { mutableStateOf("SELL") }
    var manualCurrency by remember { mutableStateOf("دلار") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("🗄️ تعداد فاکتورهای فعال دیتابیس: ${transactions.size}", color = Color(0xFF00E676), fontSize = 12.sp)
        
        // بخش پردازش متن فاکتور صوتی
        OutlinedTextField(
            value = textInput,
            onValueChange = onTextInputChange,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            placeholder = { Text("متن فاکتور صوتی را اینجا بنویسید") },
            trailingIcon = {
                IconButton(onClick = {
                    if (textInput.isBlank()) return@IconButton
                    
                    // موتور تجزیه متن هوشمند داخلی برای جلوگیری از تداخل کلاس‌ها
                    var detectedName = "ناشناس"
                    if (textInput.contains("به ")) detectedName = textInput.substringAfter("به ").substringBefore(" ")
                    if (textInput.contains("از ")) detectedName = textInput.substringAfter("از ").substringBefore(" ")
                    
                    val numbers = "[0-9]+".toRegex().findAll(textInput).map { it.value.toDoubleOrNull() ?: 0.0 }.toList()
                    val amt = if (numbers.isNotEmpty()) numbers[0] else 1.0
                    val rt = if (numbers.size > 1) numbers[1] else 1.0
                    val cur = if (textInput.contains("یورو")) "یورو" else "دلار"
                    val tp = if (textInput.contains("خریدم") || textInput.contains("خرید")) "BUY" else "SELL"

                    onAddTransaction(
                        LocalTransaction(
                            personName = detectedName,
                            type = tp,
                            amount = amt,
                            rate = rt,
                            currency = cur,
                            tomanAmount = amt * rt
                        )
                    )
                }) { Icon(Icons.Default.Add, "ثبت فاکتور") }
            }
        )

        Spacer(modifier = Modifier.height(6.dp))
        
        // فرم ثبت فاکتور به صورت دستی
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = manualName, onValueChange = { manualName = it }, label = { Text("نام شخص") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = manualAmount, onValueChange = { manualAmount = it }, label = { Text("مقدار ارز") }, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    OutlinedTextField(value = manualRate, onValueChange = { manualRate = it }, label = { Text("نرخ تبدیل") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = manualCurrency, onValueChange = { manualCurrency = it }, label = { Text("نوع ارز") }, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { manualType = if(manualType == "SELL") "BUY" else "SELL" }) {
                        Text(if(manualType == "SELL") "نوع: فروش" else "نوع: خرید")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Button(onClick = {
                        val amt = manualAmount.toDoubleOrNull() ?: 0.0
                        val rt = manualRate.toDoubleOrNull() ?: 1.0
                        if (manualName.isBlank() || amt <= 0) return@Button
                        
                        onAddTransaction(
                            LocalTransaction(
                                personName = manualName,
                                type = manualType,
                                amount = amt,
                                rate = rt,
                                currency = manualCurrency,
                                tomanAmount = amt * rt
                            )
                        )
                        manualName = ""; manualAmount = ""; manualRate = ""
                    }) { Text("ثبت دستی") }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // نمایش لیست فاکتورهای روزنامه
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(transactions) { tx ->
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF252525))) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(tx.personName, color = Color.White, fontWeight = FontWeight.Bold)
                            Text("${tx.amount} ${tx.currency} (نرخ: ${tx.rate})", color = Color.Gray, fontSize = 13.sp)
                            
                            if (!tx.audioPath.isNullOrBlank()) {
                                Button(
                                    onClick = {
                                        try {
                                            val player = MediaPlayer()
                                            player.setDataSource(tx.audioPath)
                                            player.prepare()
                                            player.start()
                                        } catch (e: Exception) { e.printStackTrace() }
                                    }, 
                                    modifier = Modifier.padding(top = 4.dp)
                                ) { Text("▶️ پخش صدای معامله", fontSize = 11.sp) }
                            }
                        }
                        val fmt = NumberFormat.getInstance(Locale.US)
                        Text("${fmt.format(tx.tomanAmount)} تومان", color = if(tx.type == "BUY") Color.Red else Color.Green, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ==========================================
// ۴. رابط کاربری تب حساب معین اشخاص (تراز زنده)
// ==========================================
@Composable
fun CounterpartiesTabScreen(transactions: List<LocalTransaction>) {
    // گروه بندی خودکار اشخاص و محاسبه ریاضی تراز بدهکار/بستانکار
    val uniqueNames = transactions.map { it.personName }.distinct()

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("👥 دفتر معین تومانی اشخاص", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        
        items(uniqueNames) { name ->
            val personTxs = transactions.filter { it.personName == name }
            var totalBalance = 0.0
            personTxs.forEach { tx ->
                if (tx.type == "SELL") totalBalance += tx.tomanAmount
                else totalBalance -= tx.tomanAmount
            }

            val statusText = if (totalBalance > 0) "بدهکار" else if (totalBalance < 0) "بستانکار" else "تراز صفر"
            val statusColor = if (totalBalance > 0) Color(0xFFFF5252) else if (totalBalance < 0) Color(0xFF00E676) else Color.Gray
            val fmt = NumberFormat.getInstance(Locale.US)

            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                    Text(name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.DarkGray))
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("وضعیت تراز مالی:", color = Color.Gray)
                        Text("$statusText: ${fmt.format(abs(totalBalance))} تومان", color = statusColor, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
