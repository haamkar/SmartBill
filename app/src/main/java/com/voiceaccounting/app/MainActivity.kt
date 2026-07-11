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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

// ==========================================
// ۱. مدل‌ها و ساختار پایگاه داده (ROOM DATABASE)
// ==========================================
@Entity(tableName = "app_counterparties")
data class FinalCounterparty(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)

@Entity(tableName = "app_transactions")
data class FinalTransaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val counterpartyId: Long,
    val personName: String, // برای دسترسی سریع و فیلترها
    val type: String,       // BUY یا SELL
    val amount: Double,
    val currencyType: String,
    val exchangeRate: Double,
    val tomanAmount: Double,
    val isDelivered: Boolean,
    val dateString: String, // فرمت YYYY-MM-DD جهت فیلتر روزانه
    val audioPath: String? = null
)

@Dao
interface ApplicationMasterDao {
    @Query("SELECT * FROM app_transactions ORDER BY id DESC")
    fun getAllTransactions(): Flow<List<FinalTransaction>>

    @Query("SELECT * FROM app_counterparties ORDER BY id DESC")
    fun getAllCounterparties(): Flow<List<FinalCounterparty>>

    @Query("SELECT * FROM app_counterparties WHERE name = :name LIMIT 1")
    suspend fun findCounterpartyByName(name: String): FinalCounterparty?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addTransaction(tx: FinalTransaction)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addCounterparty(cp: FinalCounterparty): Long

    @Delete
    suspend fun removeTransaction(tx: FinalTransaction)
}

@Database(entities = [FinalCounterparty::class, FinalTransaction::class], version = 1, exportSchema = false)
abstract class ProductionDatabase : RoomDatabase() {
    abstract fun applicationMasterDao(): ApplicationMasterDao
}

// ==========================================
// ۲. لایه اصلی کنترلر و مدیریت سیستم صوت و مجوزها
// ==========================================
class MainActivity : ComponentActivity() {
    private lateinit var appDb: ProductionDatabase
    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordedPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        appDb = Room.databaseBuilder(
            applicationContext,
            ProductionDatabase::class.java, "firmware_accounting_v3.db"
        ).fallbackToDestructiveMigration().build()

        setContent {
            var selectedTab by remember { mutableStateOf(0) }
            var isVoiceActive by remember { mutableStateOf(false) }
            var textResultState by remember { mutableStateOf("") }
            
            // وضعیت مدیریت ورود به صفحه اختصاصی هر شخص
            var activeDetailPerson by remember { mutableStateOf<FinalCounterparty?>(null) }
            
            val coroutineScope = rememberCoroutineScope()
            val context = LocalContext.current

            val speechIntentLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                    val decodedString = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0) ?: ""
                    textResultState = decodedString
                }
                stopHardwareRecording()
                isVoiceActive = false
            }

            val micPermissionRequest = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { _ -> }

            LaunchedEffect(Unit) {
                micPermissionRequest.launch(Manifest.permission.RECORD_AUDIO)
            }
            
            MaterialTheme(colorScheme = darkColorScheme()) {
                Scaffold(
                    bottomBar = {
                        if (activeDetailPerson == null) {
                            NavigationBar(containerColor = Color(0xFF161B22)) {
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
                        }
                    },
                    floatingActionButton = {
                        if (selectedTab == 0 && activeDetailPerson == null) {
                            FloatingActionButton(
                                onClick = {
                                    if (!isVoiceActive) {
                                        if (startHardwareRecording()) {
                                            isVoiceActive = true
                                            try {
                                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                                                    putExtra(RecognizerIntent.EXTRA_PROMPT, "دستور فاکتور را بگویید...")
                                                }
                                                speechIntentLauncher.launch(intent)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "موتور صوتی گوگل یافت نشد.", Toast.LENGTH_SHORT).show()
                                                stopHardwareRecording()
                                                isVoiceActive = false
                                            }
                                        }
                                    }
                                },
                                containerColor = if (isVoiceActive) Color.Red else Color(0xFF00E676),
                                shape = CircleShape
                            ) {
                                Text(if (isVoiceActive) "🛑" else "🎙️", fontSize = 24.sp)
                            }
                        }
                    }
                ) { paddingValues ->
                    Box(modifier = Modifier.padding(paddingValues).fillMaxSize().background(Color(0xFF0D1117))) {
                        val allTxList by appDb.applicationMasterDao().getAllTransactions().collectAsState(initial = emptyList())
                        val allCpList by appDb.applicationMasterDao().getAllCounterparties().collectAsState(initial = emptyList())

                        if (activeDetailPerson != null) {
                            // نمایش نمایشگر معین اختصاصی و صفحه شخصی فرد کلیک شده
                            PersonLedgerDetailScreen(
                                person = activeDetailPerson!!,
                                allTransactions = allTxList,
                                onBack = { activeDetailPerson = null }
                            )
                        } else {
                            if (selectedTab == 0) {
                                JournalTabMain(
                                    transactions = allTxList,
                                    counterparties = allCpList,
                                    voiceText = textResultState,
                                    onVoiceTextChange = { textResultState = it },
                                    onDeleteTx = { tx ->
                                        coroutineScope.launch(Dispatchers.IO) { appDb.applicationMasterDao().removeTransaction(tx) }
                                    },
                                    onCommitTx = { tx, rawText ->
                                        coroutineScope.launch(Dispatchers.IO) {
                                            var targetName = "ناشناس"
                                            val trimmed = rawText.trim()
                                            if (trimmed.contains("به ")) targetName = trimmed.substringAfter("به ").substringBefore(" ").trim()
                                            if (trimmed.contains("از ")) targetName = trimmed.substringAfter("از ").substringBefore(" ").trim()
                                            
                                            val matchedCp = appDb.applicationMasterDao().findCounterpartyByName(targetName)
                                            if (matchedCp == null) {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(context, "خطا: شخص '$targetName' در لیست اشخاص وجود ندارد! ابتدا او را دستی بسازید.", Toast.LENGTH_LONG).show()
                                                }
                                            } else {
                                                appDb.applicationMasterDao().addTransaction(
                                                    tx.copy(counterpartyId = matchedCp.id, personName = matchedCp.name, audioPath = currentRecordedPath)
                                                )
                                                currentRecordedPath = null
                                                withContext(Dispatchers.Main) { textResultState = "" }
                                            }
                                        }
                                    },
                                    onCommitManualTx = { tx, pName ->
                                        coroutineScope.launch(Dispatchers.IO) {
                                            val matchedCp = appDb.applicationMasterDao().findCounterpartyByName(pName)
                                            if (matchedCp == null) {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(context, "خطا: ابتدا این شخص را در تب اشخاص بسازید.", Toast.LENGTH_LONG).show()
                                                }
                                            } else {
                                                appDb.applicationMasterDao().addTransaction(tx.copy(counterpartyId = matchedCp.id, personName = matchedCp.name))
                                            }
                                        }
                                    }
                                )
                            } else {
                                CounterpartiesTabMain(
                                    counterparties = allCpList,
                                    transactions = allTxList,
                                    onAddPerson = { name ->
                                        coroutineScope.launch(Dispatchers.IO) {
                                            appDb.applicationMasterDao().addCounterparty(FinalCounterparty(name = name))
                                        }
                                    },
                                    onPersonClick = { cp -> activeDetailPerson = cp }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun startHardwareRecording(): Boolean {
        return try {
            val dir = File(getExternalFilesDir(null), "SecureAudioSystem")
            if (!dir.exists()) dir.mkdirs()
            val audioFile = File(dir, "AUDIO_SND_${System.currentTimeMillis()}.mp3")
            currentRecordedPath = audioFile.absolutePath

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(applicationContext) else MediaRecorder()
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(currentRecordedPath)
            recorder.prepare()
            recorder.start()
            mediaRecorder = recorder
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun stopHardwareRecording() {
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
// ۳. رابط کاربری تب دفتر روزنامه همراه با ماژول فیلترها
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalTabMain(
    transactions: List<FinalTransaction>,
    counterparties: List<FinalCounterparty>,
    voiceText: String,
    onVoiceTextChange: (String) -> Unit,
    onDeleteTx: (FinalTransaction) -> Unit,
    onCommitTx: (FinalTransaction, String) -> Unit,
    onCommitManualTx: (FinalTransaction, String) -> Unit
) {
    var manualName by remember { mutableStateOf("") }
    var manualAmount by remember { mutableStateOf("") }
    var manualRate by remember { mutableStateOf("") }
    var manualType by remember { mutableStateOf("SELL") }
    var manualCurrency by remember { mutableStateOf("دلار") }

    // وضعیت‌های فعال مربوط به فیلتر واحد ارز و روز فاکتورها
    var filterCurrencySelected by remember { mutableStateOf("همه") }
    var filterTodayOnlySelected by remember { mutableStateOf(false) }

    val todayDateStr = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }

    // فیلتر کردن داینامیک لیست بر اساس پلاراریته انتخابی کاربر
    val filteredList = transactions.filter { tx ->
        val matchCurrency = filterCurrencySelected == "همه" || tx.currencyType == filterCurrencySelected
        val matchDate = !filterTodayOnlySelected || tx.dateString == todayDateStr
        matchCurrency && matchDate
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("🎛️ جعبه ابزار فیلترهای پیشرفته فاکتور", color = Color.Gray, fontSize = 13.sp)
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { filterTodayOnlySelected = !filterTodayOnlySelected }, colors = ButtonDefaults.buttonColors(containerColor = if(filterTodayOnlySelected) Color.Yellow else Color.DarkGray)) {
                    Text(if(filterTodayOnlySelected) "فیلتر: فقط امروز" else "فیلتر: همه روزها", color = Color.Black)
                }
                Button(onClick = {
                    filterCurrencySelected = when(filterCurrencySelected) {
                        "همه" -> "دلار"
                        "دلار" -> "یورو"
                        "یورو" -> "تومان"
                        else -> "همه"
                    }
                }) {
                    Text("ارز: $filterCurrencySelected")
                }
            }
        }

        // ماژول صوت
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22))) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("🎙️ پردازش فاکتور صوتی هوشمند", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    OutlinedTextField(value = voiceText, onValueChange = onVoiceTextChange, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    Button(
                        onClick = {
                            if (voiceText.isBlank()) return@Button
                            val trimmed = voiceText.trim()
                            val digits = "[0-9]+".toRegex().findAll(trimmed).map { it.value.toDoubleOrNull() ?: 0.0 }.toList()
                            val amt = if(digits.isNotEmpty()) digits[0] else 0.0
                            val rate = if(digits.size > 1) digits[1] else 1.0
                            val cur = if(trimmed.contains("یورو")) "یورو" else if(trimmed.contains("تومان")) "تومان" else "دلار"
                            val tp = if(trimmed.contains("خرید") || trimmed.contains("خریدم")) "BUY" else "SELL"
                            val del = !trimmed.contains("تحویل نشد")

                            onCommitTx(
                                FinalTransaction(counterpartyId = 0, personName = "", type = tp, amount = amt, currencyType = cur, exchangeRate = rate, tomanAmount = amt * rate, isDelivered = del, dateString = todayDateStr),
                                trimmed
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("تحلیل و ثبت سند", color = Color.Black) }
                }
            }
        }

        // ماژول دستی فاکتورها
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22))) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("✏️ فرم ثبت دستی فاکتور بازار", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(value = manualName, onValueChange = { manualName = it }, label = { Text("نام") }, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = manualAmount, onValueChange = { manualAmount = it }, label = { Text("مقدار") }, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(value = manualRate, onValueChange = { manualRate = it }, label = { Text("نرخ") }, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = manualCurrency, onValueChange = { manualCurrency = it }, label = { Text("ارز") }, modifier = Modifier.weight(1f))
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { manualType = if(manualType == "SELL") "BUY" else "SELL" }) { Text(if(manualType == "SELL") "فروش" else "خرید") }
                        Button(onClick = {
                            val amt = manualAmount.toDoubleOrNull() ?: 0.0
                            val rt = manualRate.toDoubleOrNull() ?: 1.0
                            if (manualName.isBlank() || amt <= 0) return@Button
                            onCommitManualTx(
                                FinalTransaction(counterpartyId = 0, personName = manualName, type = manualType, amount = amt, currencyType = manualCurrency, exchangeRate = rt, tomanAmount = amt * rt, isDelivered = true, dateString = todayDateStr),
                                manualName
                            )
                            manualName = ""; manualAmount = ""; manualRate = ""
                        }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))) { Text("ثبت دستی", color = Color.Black) }
                    }
                }
            }
        }

        items(filteredList) { tx ->
            val fmt = NumberFormat.getInstance(Locale.US)
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF21262D))) {
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(tx.personName, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("${if(tx.type == "SELL") "فروش" else "خرید"} | ${tx.amount} ${tx.currencyType} (نرخ: ${tx.exchangeRate})", fontSize = 13.sp, color = Color.Gray)
                        Text("تاریخ: ${tx.dateString}", fontSize = 11.sp, color = Color.DarkGray)
                        if (!tx.audioPath.isNullOrBlank()) {
                            Text("▶️ پخش صدای معامله", color = Color(0xFF00E676), fontSize = 12.sp, modifier = Modifier.clickable {
                                try {
                                    val mp = MediaPlayer()
                                    mp.setDataSource(tx.audioPath)
                                    mp.prepare()
                                    mp.start()
                                } catch(e: Exception) {}
                            })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${fmt.format(tx.tomanAmount)} ت", color = if(tx.type == "BUY") Color.Red else Color.Green, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(12.dp))
                        IconButton(onClick = { onDeleteTx(tx) }) {
                            Icon(Icons.Default.Delete, contentDescription = "حذف", tint = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// ۴. تب اشخاص و مدیریت ساخت کاملاً دستی افراد
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CounterpartiesTabMain(
    counterparties: List<FinalCounterparty>,
    transactions: List<FinalTransaction>,
    onAddPerson: (String) -> Unit,
    onPersonClick: (FinalCounterparty) -> Unit
) {
    var newPersonName by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("👥 ماژول ساخت دستی طرف حساب جدید", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = newPersonName, onValueChange = { newPersonName = it }, placeholder = { Text("نام شخص را بنویسید...") }, modifier = Modifier.weight(1f))
            Button(onClick = {
                if (newPersonName.isNotBlank()) {
                    onAddPerson(newPersonName.trim())
                    newPersonName = ""
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))) {
                Icon(Icons.Default.Add, contentDescription = "افزودن", tint = Color.Black)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text("👥 لیست کل حساب‌های فعال", fontSize = 14.sp, color = Color.Gray)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
            items(counterparties) { cp ->
                val txs = transactions.filter { it.counterpartyId == cp.id }
                var balance = 0.0
                txs.forEach { if(it.type == "SELL") balance += it.tomanAmount else balance -= it.tomanAmount }
                
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                    modifier = Modifier.fillMaxWidth().clickable { onPersonClick(cp) }
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(cp.name, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(
                            text = if(balance > 0) "بدهکار: ${NumberFormat.getInstance(Locale.US).format(balance)} ت" else "بستانکار: ${NumberFormat.getInstance(Locale.US).format(abs(balance))} ت",
                            color = if(balance > 0) Color.Red else Color.Green
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// ۵. صفحه اختصاصی معین حساب و ریزفاکتورهای هر شخص
// ==========================================
@Composable
fun PersonLedgerDetailScreen(
    person: FinalCounterparty,
    allTransactions: List<FinalTransaction>,
    onBack: () -> Unit
) {
    val personTxs = allTransactions.filter { it.counterpartyId == person.id }
    var totalBalance = 0.0
    personTxs.forEach { if(it.type == "SELL") totalBalance += it.tomanAmount else totalBalance -= it.tomanAmount }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack) { Text("⬅️ بازگشت") }
            Spacer(modifier = Modifier.width(16.dp))
            Text("📊 کارنامه معین: ${person.name}", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)), modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("آخرین وضعیت تراز نهایی حساب:", color = Color.Gray, fontSize = 14.sp)
                Text(
                    text = if(totalBalance > 0) "بدهکار: ${NumberFormat.getInstance(Locale.US).format(totalBalance)} تومان" else "بستانکار: ${NumberFormat.getInstance(Locale.US).format(abs(totalBalance))} تومان",
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if(totalBalance > 0) Color.Red else Color.Green
                )
            }
        }

        Text("📋 تاریخچه تمام فاکتورهای صوتی و دستی این شخص", fontSize = 13.sp, color = Color.Gray)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f).padding(top = 8.dp)) {
            items(personTxs) { tx ->
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF21262D))) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("نوع: ${if(tx.type == "SELL") "فروش ارز" else "خرید ارز"}", color = Color.White)
                            Text("مقدار واحد: ${tx.amount} ${tx.currencyType} (نرخ: ${tx.exchangeRate})", fontSize = 12.sp, color = Color.Gray)
                        }
                        Text("${NumberFormat.getInstance(Locale.US).format(tx.tomanAmount)} تومان", fontWeight = FontWeight.Bold, color = if(tx.type == "BUY") Color.Red else Color.Green)
                    }
                }
            }
        }
    }
}
