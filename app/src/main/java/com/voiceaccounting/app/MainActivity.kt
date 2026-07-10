package com.voiceaccounting.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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

class MainActivity : ComponentActivity() {
    private lateinit var db: AppDatabase
    private val voiceProcessor = VoiceProcessor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // راه‌اندازی دیتابیس داخلی
        db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java, "voice_accounting.db"
        ).fallbackToDestructiveMigration().build()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF121212) // تم دارک
                ) {
                    AccountingScreen(db, voiceProcessor) { message ->
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountingScreen(db: AppDatabase, processor: VoiceProcessor, showToast: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var transactions by remember { mutableStateOf(listOf<Transaction>()) }
    var counterparties by remember { mutableStateOf(listOf<Counterparty>()) }
    var voiceInputText by remember { mutableStateOf("") }
    
    // دریافت اطلاعات از دیتابیس
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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "📋 دستیار صوتی حسابداری",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            textAlign = TextAlign.Center
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("🎙️ شبیه‌ساز دستور صوتی فارسی:", color = Color.Gray, fontSize = 14.sp)
                OutlinedTextField(
                    value = voiceInputText,
                    onValueChange = { voiceInputText = it },
                    placeholder = { Text("مثال: فروختم ۱۰۰۰ دلار در ۱۵۵ به جعفر تحویل نشد", color = Color.DarkGray) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00E676)
                    )
                )
                Button(
                    onClick = {
                        if (voiceInputText.isBlank()) return@Button
                        scope.launch(Dispatchers.IO) {
                            val rawText = voiceInputText
                            var personName = ""
                            
                            if (rawText.contains("به ")) personName = rawText.substringAfter("به ").substringBefore(" ")
                            if (rawText.contains("از ")) personName = rawText.substringAfter("از ").substringBefore(" ")
                            
                            if (personName.isBlank()) {
                                withContext(Dispatchers.Main) { showToast("خطا: نام طرف حساب تشخیص داده نشد!") }
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
                                    showToast("تراکنش با موفقیت ثبت شد.")
                                    voiceInputText = ""
                                }
                            } else {
                                withContext(Dispatchers.Main) { showToast("خطا: ساختار متن اشتباه است.") }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("پردازش و ثبت فاکتور", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }

        Text("📑 دفتر روزنامه", color = Color.LightGray, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
                            Text(text = "نوع: ${tx.type} | مقدار: ${tx.amount} ${tx.currencyType}", color = Color.Gray, fontSize = 13.sp)
                            Text(text = "نرخ: ${formatter.format(tx.exchangeRate)} تومان", color = Color.Gray, fontSize = 13.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "${formatter.format(tx.tomanAmount)} تومان",
                                color = if (tx.type == "BUY" || tx.type == "PAY_TOMAN") Color(0xFFFF5252) else Color(0xFF00E676),
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
    }
}
