package com.voiceaccounting.app

import android.app.Activity
import android.content.Intent
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
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private lateinit var db: AppDatabase
    private val voiceProcessor = VoiceProcessor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java, "voice_accounting.db"
        ).fallbackToDestructiveMigration().build()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF121212)
                ) {
                    MainScreen(db, voiceProcessor)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(db: AppDatabase, processor: VoiceProcessor) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    
    var selectedTab by remember { mutableStateOf(0) }
    var transactions by remember { mutableStateOf(listOf<Transaction>()) }
    var counterparties by remember { mutableStateOf(listOf<Counterparty>()) }
    
    var voiceInputText by remember { mutableStateOf("") }
    
    var manualName by remember { mutableStateOf("") }
    var manualAmount by remember { mutableStateOf("") }
    var manualRate by remember { mutableStateOf("") }
    var manualType by remember { mutableStateOf("SELL") } 
    var manualCurrency by remember { mutableStateOf("دلار") }

    // ابزار رسمی سیستم‌عامل اندروید برای باز کردن میکروفون و تایپ گفتار فارسی
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0) ?: ""
            voiceInputText = spokenText
        }
    }

    LaunchedEffect(Unit) {
        db.transactionDao().getAllTransactionsJournal().collect { list ->
            transactions = list
        }
    }
    LaunchedEffect(Unit) {
        db.counterpartyDao().getAllCounterparties().collect { list ->
            counterparties = list
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color(0xFF1A1A1A),
            contentColor = Color(0xFF00E676)
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("📝 دفتر روزنامه", fontWeight = FontWeight.Bold) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("👥 حساب اشخاص", fontWeight = FontWeight.Bold) }
            )
        }

        if (selectedTab == 0) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = "🗄️ وضعیت دیتابیس: $transactions.size معامله ثبت شده است",
                        color = Color(0xFF00E676),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // بخش ثبت صوتی
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("🎙️ ثبت هوشمند با صوت فارسی", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            OutlinedTextField(
                                value = voiceInputText,
                                onValueChange = { voiceInputText = it },
                                placeholder = { Text("متن تشخیص داده شده اینجا ظاهر می‌شود...", color = Color.Gray) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        try {
                                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                                                putExtra(RecognizerIntent.EXTRA_PROMPT, "معامله را بگویید...")
                                            }
                                            speechLauncher.launch(intent)
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "خطا در باز کردن میکروفون سیستم", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("🎙️ صحبت کنید")
                                }

                                Button(
                                    onClick = {
                                        if (voiceInputText.isBlank()) return@Button
                                        scope.launch(Dispatchers.IO) {
                                            val rawText = voiceInputText
                                            var personName = ""
                                            if (rawText.contains("به ")) personName = rawText.substringAfter("به ").substringBefore(" ")
                                            if (rawText.contains("از ")) personName = rawText.substringAfter("از ").substringBefore(" ")
                                            
                                            if (personName.isBlank()) {
                                                withContext(Dispatchers.Main) { 
                                                    Toast.makeText(context, "نام شخص پیدا نشد!", Toast.LENGTH_SHORT).show() 
                                                }
                                                return@launch
                                            }

                                            var cp = db.counterpartyDao().getByName(personName)
                                            if (cp == null) {
                                                val newId = db.counterpartyDao().insert(Counterparty(name = personName))
                                                cp = Counterparty(id = newId, name = personName)
                                            }

                                            val transaction = processor.processVoiceText(rawText) { _ -> cp.id }
                                            if (transaction != null) {
                                                val finalTx = transaction.copy(counterpartyId = cp.id)
                                                db.transactionDao().insert(finalTx)
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(context, "فاکتور صوتی ثبت شد", Toast.LENGTH_SHORT).show()
                                                    voiceInputText = ""
                                                }
                                            } else {
                                                withContext(Dispatchers.Main) { 
                                                    Toast.makeText(context, "خطا در تحلیل متن", Toast.LENGTH_SHORT).show() 
                                                }
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("✅ تایید و ثبت متن", color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // بخش فرم کاملاً دستی
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("✏️ ثبت فاکتور به صورت کاملاً دستی", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = manualName, onValueChange = { manualName = it }, label = { Text("نام شخص") }, modifier = Modifier.weight(1f))
                                OutlinedTextField(value = manualAmount, onValueChange = { manualAmount = it }, label = { Text("مقدار") }, modifier = Modifier.weight(1f))
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = manualRate, onValueChange = { manualRate = it }, label = { Text("نرخ واحد") }, modifier = Modifier.weight(1f))
                                OutlinedTextField(value = manualCurrency, onValueChange = { manualCurrency = it }, label = { Text("نوع ارز") }, modifier = Modifier.weight(1f))
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = manualType == "SELL", onClick = { manualType = "SELL" })
                                    Text("فروش")
                                    Spacer(modifier = Modifier.width(8.dp))
                                    RadioButton(selected = manualType == "BUY", onClick = { manualType = "BUY" })
                                    Text("خرید")
                                }
                                
                                Button(
                                    onClick = {
                                        val amt = manualAmount.toDoubleOrNull() ?: 0.0
                                        val rate = manualRate.toDoubleOrNull() ?: 0.0
                                        if (manualName.isBlank() || amt <= 0 || rate <= 0) {
                                            Toast.makeText(context, "اطلاعات را درست وارد کنید", Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }
                                        scope.launch(Dispatchers.IO) {
                                            var cp = db.counterpartyDao().getByName(manualName)
                                            if (cp == null) {
                                                val newId = db.counterpartyDao().insert(Counterparty(name = manualName))
                                                cp = Counterparty(id = newId, name = manualName)
                                            }
                                            
                                            val manualTx = Transaction(
                                                type = manualType,
                                                counterpartyId = cp.id,
                                                amount = amt,
                                                currencyType = manualCurrency,
                                                exchangeRate = rate,
                                                tomanAmount = amt * rate,
                                                isDelivered = true
                                            )
                                            db.transactionDao().insert(manualTx)
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(context, "فاکتور دستی ذخیره شد", Toast.LENGTH_SHORT).show()
                                                manualName = ""; manualAmount = ""; manualRate = ""
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
                                ) {
                                    Text("ثبت فاکتور دستی", color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                item {
                    Text("📑 دفتر معاملات ثبت شده", color = Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }

                items(transactions) { tx ->
                    val partyName = counterparties.find { it.id == tx.counterpartyId }?.name ?: "نامشخص"
                    val formatter = NumberFormat.getInstance(Locale.US)
                    
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF252525)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(text = "طرف حساب: $partyName", color = Color.White, fontWeight = FontWeight.Bold)
                                Text(text = "نوع: ${if (tx.type == "SELL") "فروش" else "خرید"} | مقدار: ${tx.amount} ${tx.currencyType}", color = Color.Gray, fontSize = 13.sp)
                                Text(text = "نرخ: ${formatter.format(tx.exchangeRate)} تومان", color = Color.Gray, fontSize = 13.sp)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "${formatter.format(tx.tomanAmount)} تومان",
                                    color = if (tx.type == "BUY") Color(0xFFFF5252) else Color(0xFF00E676),
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (tx.isDelivered) "✓ تحویل شده" else "❌ تعهد تحویل",
                                    color = if (tx.isDelivered) Color.Gray else Color(0xFFFFD700),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text("👥 دفتر معین تومانی اشخاص (تراز زنده)", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                items(counterparties) { cp ->
                    val personTxs = transactions.filter { it.counterpartyId == cp.id }
                    var totalToman = 0.0
                    personTxs.forEach { tx ->
                        if (tx.type == "SELL") totalToman += tx.tomanAmount
                        else if (tx.type == "BUY") totalToman -= tx.tomanAmount
                    }

                    val statusText = if (totalToman > 0) "بدهکار" else if (totalToman < 0) "بستانکار" else "تراز صفر"
                    val statusColor = if (totalToman > 0) Color(0xFFFF5252) else if (totalToman < 0) Color(0xFF00E676) else Color.Gray
                    val formatter = NumberFormat.getInstance(Locale.US)

                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
                        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                            Text(cp.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.DarkGray))
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("وضعیت تراز مالی:", color = Color.Gray)
                                Text("$statusText: ${formatter.format(abs(totalToman))} تومان", color = statusColor, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
