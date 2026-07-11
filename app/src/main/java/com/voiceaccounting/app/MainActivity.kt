package com.voiceaccounting.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
// ۱. ساختار پایگاه داده محلی (ROOM DATABASE CORE)
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
    val personName: String,
    val type: String,       // BUY, SELL, RECEIVE_TOMAN, PAY_TOMAN
    val amount: Double,
    val currencyType: String,
    val exchangeRate: Double,
    val tomanAmount: Double,
    val isDelivered: Boolean,
    val dateString: String,
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

    @Update
    suspend fun updateTransaction(tx: FinalTransaction)

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
// ۲. بدنه اصلی برنامه SMART EXCHANGER
// ==========================================
class MainActivity : ComponentActivity() {
    private lateinit var appDb: ProductionDatabase
    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordedPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        appDb = Room.databaseBuilder(
            applicationContext,
            ProductionDatabase::class.java, "smart_exchanger_production_v12.db"
        ).fallbackToDestructiveMigration().build()

        setContent {
            var selectedTab by remember { mutableStateOf(0) }
            var isVoiceActive by remember { mutableStateOf(false) }
            var textResultState by remember { mutableStateOf("") }
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
                                                    putExtra(RecognizerIntent.EXTRA_PROMPT, "معامله بازار را بگویید...")
                                                }
                                                speechIntentLauncher.launch(intent)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "سیستم صوتی آماده نیست.", Toast.LENGTH_SHORT).show()
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
                                    onUpdateTx = { tx ->
                                        coroutineScope.launch(Dispatchers.IO) { appDb.applicationMasterDao().updateTransaction(tx) }
                                    },
                                    onCommitManualTx = { tx, pName ->
                                        coroutineScope.launch(Dispatchers.IO) {
                                            val matchedCp = appDb.applicationMasterDao().findCounterpartyByName(pName)
                                            if (matchedCp == null) {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(context, "خطا: ابتدا شخص را در تب اشخاص بسازید.", Toast.LENGTH_LONG).show()
                                                }
                                            } else {
                                                val finalTx = if (currentRecordedPath != null) {
                                                    tx.copy(counterpartyId = matchedCp.id, personName = matchedCp.name, audioPath = currentRecordedPath)
                                                } else {
                                                    tx.copy(counterpartyId = matchedCp.id, personName = matchedCp.name)
                                                }
                                                appDb.applicationMasterDao().addTransaction(finalTx)
                                                currentRecordedPath = null
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
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "SmartExchanger")
            if (!dir.exists()) dir.mkdirs()
            val audioFile = File(dir, "SEC_REC_${System.currentTimeMillis()}.mp3")
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
// ۳. رابط کاربری تب دفتر روزنامه (Smart Exchanger)
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalTabMain(
    transactions: List<FinalTransaction>,
    counterparties: List<FinalCounterparty>,
    voiceText: String,
    onVoiceTextChange: (String) -> Unit,
    onDeleteTx: (FinalTransaction) -> Unit,
    onUpdateTx: (FinalTransaction) -> Unit,
    onCommitManualTx: (FinalTransaction, String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    var manualName by remember { mutableStateOf("") }
    var manualAmount by remember { mutableStateOf("") }
    var manualRate by remember { mutableStateOf("") }
    var manualType by remember { mutableStateOf("SELL") }

    var filterCurrencySelected by remember { mutableStateOf("همه") }
    var filterDateMode by remember { mutableStateOf("همه") }
    var startDateInput by remember { mutableStateOf("") }
    var endDateInput by remember { mutableStateOf("") }
    
    var editingTx by remember { mutableStateOf<FinalTransaction?>(null) }
    val todayDateStr = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }

    val filteredList = transactions.filter { tx ->
        val matchCurrency = filterCurrencySelected == "همه" || tx.currencyType == filterCurrencySelected
        val matchDate = when (filterDateMode) {
            "امروز" -> tx.dateString == todayDateStr
            "بازه" -> tx.dateString >= startDateInput && tx.dateString <= endDateInput
            else -> true
        }
        matchCurrency && matchDate
    }

    if (editingTx != null) {
        var editAmount by remember { mutableStateOf(editingTx!!.amount.toString()) }
        var editRate by remember { mutableStateOf(editingTx!!.exchangeRate.toString()) }
        AlertDialog(
            onDismissRequest = { editingTx = null },
            title = { Text("✏️ ویرایش فاکتور") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = editAmount, onValueChange = { editAmount = it }, label = { Text("مقدار جدید") })
                    if (editingTx!!.type == "BUY" || editingTx!!.type == "SELL") {
                        OutlinedTextField(value = editRate, onValueChange = { editRate = it }, label = { Text("نرخ جدید") })
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val amt = editAmount.toDoubleOrNull() ?: editingTx!!.amount
                    val rt = editRate.toDoubleOrNull() ?: editingTx!!.exchangeRate
                    onUpdateTx(editingTx!!.copy(amount = amt, exchangeRate = rt, tomanAmount = amt * rt))
                    editingTx = null
                }) { Text("بروزرسانی") }
            }
        )
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("📊 Smart Exchanger", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E676))
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    filterDateMode = when(filterDateMode) {
                        "همه" -> "امروز"
                        "امروز" -> "بازه"
                        else -> "همه"
                    }
                }, modifier = Modifier.weight(1f)) { Text("زمان: $filterDateMode") }
                Button(onClick = {
                    filterCurrencySelected = when(filterCurrencySelected) {
                        "همه" -> "دلار"
                        "دلار" -> "یورو"
                        "یورو" -> "تومان"
                        else -> "همه"
                    }
                }, modifier = Modifier.weight(1f)) { Text("ارز: $filterCurrencySelected") }
            }

            if (filterDateMode == "بازه") {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = startDateInput, onValueChange = { startDateInput = it }, placeholder = { Text("از: YYYY-MM-DD") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = endDateInput, onValueChange = { endDateInput = it }, placeholder = { Text("تا: YYYY-MM-DD") }, modifier = Modifier.weight(1f))
                }
            }
        }

        // بخش صوتی و انتقال به کادرهای دستی صرافی
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22))) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("🎙️ رادار واژه‌کاو صوتی بازار", fontWeight = FontWeight.Bold)
                    OutlinedTextField(value = voiceText, onValueChange = onVoiceTextChange, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    Button(
                        onClick = {
                            if (voiceText.isBlank()) return@Button
                            val trimmed = voiceText.trim()
                            
                            var detectedName = "ناشناس"
                            if (trimmed.contains("به ")) detectedName = trimmed.substringAfter("به ").substringBefore(" ").trim()
                            if (trimmed.contains("از ")) detectedName = trimmed.substringAfter("از ").substringBefore(" ").trim()
                            
                            val personExists = counterparties.any { it.name == detectedName }
                            if (!personExists) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                Toast.makeText(context, "خطا: شخص '$detectedName' یافت نشد! ابتدا او را دستی بسازید.", Toast.LENGTH_LONG).show()
                            } else {
                                val digits = "[0-9]+".toRegex().findAll(trimmed).map { it.value }.toList()
                                manualName = detectedName
                                manualAmount = if(digits.isNotEmpty()) digits[0] else ""
                                manualRate = if(digits.size > 1) digits[1] else ""
                                manualType = if(trimmed.contains("خرید") || trimmed.contains("خریدم")) "BUY" else "SELL"
                                
                                Toast.makeText(context, "اطلاعات ویس به فرم دستی پایین منتقل شد.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("تحلیل و انتقال به فرم", color = Color.Black) }
                }
            }
        }

        // ماژول دستی فاکتورها
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22))) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("✏️ ماژول تایید و ثبت فاکتور نهایی", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(value = manualName, onValueChange = { manualName = it }, label = { Text("نام شخص") }, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = manualAmount, onValueChange = { manualAmount = it }, label = { Text("مقدار") }, modifier = Modifier.weight(1f))
                    }
                    if (manualType == "SELL" || manualType == "BUY") {
                        OutlinedTextField(value = manualRate, onValueChange = { manualRate = it }, label = { Text("نرخ واحد تبدیل") }, modifier = Modifier.fillMaxWidth())
                    }
                    
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = { manualType = "SELL" }, colors = ButtonDefaults.buttonColors(containerColor = if(manualType=="SELL") Color(0xFF00E676) else Color.DarkGray), modifier = Modifier.weight(1f)) { Text("فروش", fontSize = 11.sp) }
                        Button(onClick = { manualType = "BUY" }, colors = ButtonDefaults.buttonColors(containerColor = if(manualType=="BUY") Color(0xFFFF5252) else Color.DarkGray), modifier = Modifier.weight(1f)) { Text("خرید", fontSize = 11.sp) }
                        Button(onClick = { manualType = "RECEIVE_TOMAN" }, colors = ButtonDefaults.buttonColors(containerColor = if(manualType=="RECEIVE_TOMAN") Color.Cyan else Color.DarkGray), modifier = Modifier.weight(1f)) { Text("+تومان", fontSize = 11.sp) }
                        Button(onClick = { manualType = "PAY_TOMAN" }, colors = ButtonDefaults.buttonColors(containerColor = if(manualType=="PAY_TOMAN") Color.Magenta else Color.DarkGray), modifier = Modifier.weight(1f)) { Text("-تومان", fontSize = 11.sp) }
                    }

                    Button(onClick = {
                        val amt = manualAmount.toDoubleOrNull() ?: 0.0
                        val rt = if (manualType == "RECEIVE_TOMAN" || manualType == "PAY_TOMAN") 1.0 else (manualRate.toDoubleOrNull() ?: 1.0)
                        val curType = if (manualType == "RECEIVE_TOMAN" || manualType == "PAY_TOMAN") "تومان" else "دلار"
                        if (manualName.isBlank() || amt <= 0) return@Button
                        
                        onCommitManualTx(
                            FinalTransaction(counterpartyId = 0, personName = manualName, type = manualType, amount = amt, currencyType = curType, exchangeRate = rt, tomanAmount = amt * rt, isDelivered = true, dateString = todayDateStr),
                            manualName
                        )
                        manualName = ""; manualAmount = ""; manualRate = ""
                    }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)), modifier = Modifier.fillMaxWidth()) { 
                        Text("ذخیره قطعی فاکتور", color = Color.Black, fontWeight = FontWeight.Bold) 
                    }
                }
            }
        }

        items(filteredList) { tx ->
            val fmt = NumberFormat.getInstance(Locale.US)
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF21262D))) {
                Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tx.personName, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        Text(
                            text = "${when(tx.type){"SELL"->"فروش" "BUY"->"خرید" "RECEIVE_TOMAN"->"دریافت نقدی" else->"پرداخت نقدی"}} ➡️ ${fmt.format(tx.amount)} ${tx.currencyType}", 
                            fontSize = 22.sp, 
                            fontWeight = FontWeight.Bold, 
                            color = Color.Yellow
                        )
                        if(tx.type == "BUY" || tx.type == "SELL") {
                            Text("نرخ معامله: ${fmt.format(tx.exchangeRate)} تومان", fontSize = 14.sp, color = Color.Gray)
                        }
                        if (!tx.audioPath.isNullOrBlank()) {
                            Text("▶️ پخش فایل صوتی معامله", color = Color(0xFF00E676), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp).clickable {
                                try {
                                    val mp = MediaPlayer()
                                    mp.setDataSource(tx.audioPath)
                                    mp.prepare()
                                    mp.start()
                                } catch(e: Exception) {}
                            })
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${fmt.format(tx.tomanAmount)} ت", color = if(tx.type == "BUY" || tx.type == "PAY_TOMAN") Color(0xFFFF5252) else Color(0xFF00E676), fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                        Row {
                            IconButton(onClick = { editingTx = tx }) { Icon(Icons.Default.Edit, "ویرایش", tint = Color.Cyan) }
                            IconButton(onClick = { onDeleteTx(tx) }) { Icon(Icons.Default.Delete, "حذف", tint = Color.Red) }
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
        Text("👥 ساخت طرف حساب دستی جدید", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = newPersonName, onValueChange = { newPersonName = it }, placeholder = { Text("نام حساب جدید...") }, modifier = Modifier.weight(1f))
            Button(onClick = {
                if (newPersonName.isNotBlank()) {
                    onAddPerson(newPersonName.trim())
                    newPersonName = ""
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))) {
                Icon(Icons.Default.Add, contentDescription = "افزودن", tint = Color.Black)
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
            items(counterparties) { cp ->
                val txs = transactions.filter { it.counterpartyId == cp.id }
                var balance = 0.0
                txs.forEach { 
                    if(it.type == "SELL" || it.type == "PAY_TOMAN") balance += it.tomanAmount 
                    else balance -= it.tomanAmount 
                }
                
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                    modifier = Modifier.fillMaxWidth().clickable { onPersonClick(cp) }
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(cp.name, fontWeight = FontWeight.ExtraBold, color = Color.White, fontSize = 22.sp)
                        Text(
                            text = if(balance > 0) "بدهکار: ${NumberFormat.getInstance(Locale.US).format(balance)} ت" else "بستانکار: ${NumberFormat.getInstance(Locale.US).format(abs(balance))} ت",
                            color = if(balance > 0) Color(0xFFFF5252) else Color(0xFF00E676),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// ۵. صفحه اختصاصی معین حساب و فیلترهای زمانی هر فرد
// ==========================================
@Composable
fun PersonLedgerDetailScreen(
    person: FinalCounterparty,
    allTransactions: List<FinalTransaction>,
    onBack: () -> Unit
) {
    var pCurrencyFilter by remember { mutableStateOf("همه") }
    var pDateFilterMode by remember { mutableStateOf("همه") }
    var pStartRange by remember { mutableStateOf("") }
    var pEndRange by remember { mutableStateOf("") }

    val personTxs = allTransactions.filter { tx ->
        val matchPerson = tx.counterpartyId == person.id
        val matchCurrency = pCurrencyFilter == "همه" || tx.currencyType == pCurrencyFilter
        val matchDate = pDateFilterMode == "همه" || (tx.dateString >= pStartRange && tx.dateString <= pEndRange)
        matchPerson && matchCurrency && matchDate
    }

    var totalBalance = 0.0
    allTransactions.filter { it.counterpartyId == person.id }.forEach { 
        if(it.type == "SELL" || it.type == "PAY_TOMAN") totalBalance += it.tomanAmount 
        else totalBalance -= it.tomanAmount 
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack) { Text("⬅️ بازگشت") }
            Spacer(modifier = Modifier.width(16.dp))
            Text("📊 حساب معین: ${person.name}", fontWeight = FontWeight.Bold, fontSize = 22.sp)
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)), modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("تراز کلی حساب شخص در بازار:", color = Color.Gray, fontSize = 14.sp)
                Text(
                    text = if(totalBalance > 0) "بدهکار به ما: ${NumberFormat.getInstance(Locale.US).format(totalBalance)} تومان" else "بستانکار از ما: ${NumberFormat.getInstance(Locale.US).format(abs(totalBalance))} تومان",
                    fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = if(totalBalance > 0) Color(0xFFFF5252) else Color(0xFF00E676)
                )
            }
        }

        Text("🗛 تنظیم فیلترهای زمانی حساب معین شخص:", color = Color.Gray, fontSize = 12.sp)
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { pDateFilterMode = if(pDateFilterMode=="همه") "بازه" else "همه" }, modifier = Modifier.weight(1f)) { Text("زمان: $pDateFilterMode") }
            Button(onClick = { pCurrencyFilter = when(pCurrencyFilter){ "همه"->"دلار" "دلار"->"یورو" "یورو"->"تومان" else->"همه" } }, modifier = Modifier.weight(1f)) { Text("ارز: $pCurrencyFilter") }
        }
        if (pDateFilterMode == "بازه") {
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(value = pStartRange, onValueChange = { pStartRange = it }, placeholder = { Text("از: YYYY-MM-DD") }, modifier = Modifier.weight(1f))
                OutlinedTextField(value = pEndRange, onValueChange = { pEndRange = it }, placeholder = { Text("تا: YYYY-MM-DD") }, modifier = Modifier.weight(1f))
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(personTxs) { tx ->
                val fmt = NumberFormat.getInstance(Locale.US)
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF21262D))) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("نوع: ${when(tx.type){"SELL"->"فروش ارز" "BUY"->"خرید ارز" "RECEIVE_TOMAN"->"دریافت نقدی" else->"پرداخت نقدی"}}", color = Color.White, fontSize = 16.sp)
                            Text("مقدار: ${fmt.format(tx.amount)} ${tx.currencyType}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.Yellow)
                        }
                        Text("${fmt.format(tx.tomanAmount)} ت", fontWeight = FontWeight.ExtraBold, color = if(tx.type == "BUY" || tx.type == "PAY_TOMAN") Color.Red else Color.Green, fontSize = 20.sp)
                    }
                }
            }
        }
    }
}
