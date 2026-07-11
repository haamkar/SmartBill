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
            ProductionDatabase::class.java, "secure_smart_exchanger_v12.db"
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
                            NavigationBar(containerColor = Color(0xFF16
