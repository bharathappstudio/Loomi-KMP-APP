package com.echo.loomi

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wallet.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.echo.loomi.ui.theme.LoomiTheme
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
class PaymentActivity : ComponentActivity() {

    private lateinit var paymentsClient: PaymentsClient
    private val merchantId = "BCR2DN7TTCLJXQB4"
    private val upiId = "7094589909@okbizaxis"
    private val name = "Bharath"
    private val amount = "10.00"

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 9001) {
            when (resultCode) {
                RESULT_OK -> {
                    val paymentData = data?.let { PaymentData.getFromIntent(it) }
                    handlePaymentSuccess(paymentData)
                }
                RESULT_CANCELED -> {
                    Toast.makeText(this, "Payment Cancelled", Toast.LENGTH_SHORT).show()
                }
                AutoResolveHelper.RESULT_ERROR -> {
                    val status = AutoResolveHelper.getStatusFromIntent(data)
                    Toast.makeText(this, "Payment Error: ${status?.statusMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)

        paymentsClient = Wallet.getPaymentsClient(
            this,
            Wallet.WalletOptions.Builder()
                .setEnvironment(WalletConstants.ENVIRONMENT_PRODUCTION)
                .build()
        )

        setContent {
            LoomiTheme {
                var showSheet by remember { mutableStateOf(true) }
                
                LaunchedEffect(showSheet) {
                    if (!showSheet) finish()
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    Image(
                        painter = painterResource(id = R.drawable.b1),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    
                    if (showSheet) {
                        ModalBottomSheet(
                            onDismissRequest = { showSheet = false },
                            containerColor = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp),
                            dragHandle = {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 16.dp)
                                        .width(40.dp)
                                        .height(5.dp)
                                        .clip(RoundedCornerShape(100))
                                        .background(if (isSystemInDarkTheme()) Color.White.copy(0.2f) else Color(0xFF2A2A2A))
                                )
                            },
                            scrimColor = Color.Transparent
                        ) {
                            PaymentSheetContent(
                                amount = amount,
                                onPayClick = { requestPayment() },
                                onCancel = { showSheet = false }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun requestPayment() {
        val paymentDataRequestJson = getPaymentDataRequest()
        val request = PaymentDataRequest.fromJson(paymentDataRequestJson.toString())
        
        // Use ENVIRONMENT_PRODUCTION for real payments. 
        // Note: OR_BIBED_11 often happens if the app is not signed with the production 
        // certificate registered in the Google Pay Business Console.
        val task = paymentsClient.loadPaymentData(request)
        AutoResolveHelper.resolveTask(task, this, 9001)
    }

    private fun getPaymentDataRequest(): JSONObject {
        return JSONObject()
            .put("apiVersion", 2)
            .put("apiVersionMinor", 0)
            .put("allowedPaymentMethods", JSONArray().put(getUpiPaymentMethod()))
            .put("transactionInfo", JSONObject()
                .put("totalPrice", amount)
                .put("totalPriceStatus", "FINAL")
                .put("currencyCode", "INR")
                .put("transactionId", UUID.randomUUID().toString())
            )
            .put("merchantInfo", JSONObject()
                .put("merchantId", merchantId)
                .put("merchantName", "Loomi") // Ensure this matches your registered name
            )
    }

    private fun getUpiPaymentMethod(): JSONObject {
        return JSONObject()
            .put("type", "UPI")
            .put("parameters", JSONObject()
                .put("payeeVpa", upiId)
                .put("payeeName", name)
                .put("mcc", "5817") // MCC for Digital Goods: Software Applications
                .put("transactionReferenceId", UUID.randomUUID().toString())
                .put("transactionNote", "Loomi Pro Subscription")
            )
            .put("tokenizationSpecification", JSONObject()
                .put("type", "DIRECT")
            )
    }

    private fun handlePaymentSuccess(paymentData: PaymentData?) {
        val paymentInformation = paymentData?.toJson() ?: return
        try {
            val paymentMethodData = JSONObject(paymentInformation).getJSONObject("paymentMethodData")
            // For UPI, the response might contain specific transaction details
            savePaymentToFirebase(paymentMethodData.toString())
            Toast.makeText(this, "Payment Successful!", Toast.LENGTH_LONG).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Error processing payment details", Toast.LENGTH_SHORT).show()
        }
    }

    private fun savePaymentToFirebase(paymentDetails: String) {
        val auth = FirebaseAuth.getInstance()
        val uid = auth.currentUser?.uid ?: return
        val database = FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").reference
        
        val encryptedData = EncryptionUtils.encrypt(paymentDetails)

        val paymentId = database.child("payments").child(uid).push().key ?: return
        val paymentData = hashMapOf(
            "amount" to amount,
            "status" to "SUCCESS",
            "timestamp" to System.currentTimeMillis(),
            "data" to encryptedData,
            "proEnabled" to true
        )

        database.child("payments").child(uid).child(paymentId).setValue(paymentData)
        database.child("users").child(uid).child("isPro").setValue(true)
    }
}

// Reuse the PaymentSheetContent from previous implementation
@Composable
fun PaymentSheetContent(amount: String, onPayClick: () -> Unit, onCancel: () -> Unit) {
    val isDark = isSystemInDarkTheme()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Upgrade to Loomi Pro 🫐",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            "Get access to higher limits, cloud storage, and E2E encryption in Realtime Database.",
            fontSize = 14.sp,
            color = if (isDark) Color.White.copy(0.6f) else Color(0xFF6B6B6B),
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(if (isDark) Color.White.copy(0.05f) else Color(0xFFF9FBE7))
                .border(1.dp, if (isDark) Color.White.copy(0.1f) else Color.Transparent, RoundedCornerShape(20.dp))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Total Amount", fontSize = 14.sp, color = if (isDark) Color.White.copy(0.4f) else Color.Gray)
                Text("₹$amount", fontSize = 42.sp, fontWeight = FontWeight.ExtraBold, color = if (isDark) Color.White else Color(0xFF1C1C1C))
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(if (isDark) Color.White else Color(0xFF1C1C1C))
                .clickable { onPayClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Pay with Google Pay",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isDark) Color.Black else Color.White
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        TextButton(onClick = onCancel) {
            Text("Cancel", color = if (isDark) Color.White.copy(0.6f) else Color.Gray)
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Secure payment via Google Pay API",
            fontSize = 12.sp,
            color = Color(0xFF6E6E6E),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
