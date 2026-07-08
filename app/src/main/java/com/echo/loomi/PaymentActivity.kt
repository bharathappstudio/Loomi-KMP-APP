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
                val isDark = isSystemInDarkTheme()
                
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
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(modifier = Modifier.weight(1f))
                            
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .navigationBarsPadding()
                                    .padding(horizontal = 24.dp, vertical = 26.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.CenterHorizontally)
                                        .width(40.dp)
                                        .height(5.dp)
                                        .clip(RoundedCornerShape(100))
                                        .background(if (isDark) Color.White.copy(0.2f) else Color(0xFF2A2A2A))
                                )

                                Spacer(modifier = Modifier.height(22.dp))

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .clip(RoundedCornerShape(30.dp))
                                        .background(if (isDark) Color.White else Color(0xFF1C1C1C))
                                        .clickable { requestPayment() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Image(
                                            painter = painterResource(id = R.drawable.google),
                                            contentDescription = "Google",
                                            modifier = Modifier.size(30.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = "Pay with Google Pay",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isDark) Color.Black else Color.White
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(22.dp))

                                Text(
                                    text = "Secure payment via Google Pay API",
                                    fontSize = 12.sp,
                                    color = Color(0xFF6E6E6E),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun requestPayment() {
        val paymentDataRequestJson = getPaymentDataRequest()
        val request = PaymentDataRequest.fromJson(paymentDataRequestJson.toString())
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
                .put("merchantName", "Loomi")
            )
    }

    private fun getUpiPaymentMethod(): JSONObject {
        return JSONObject()
            .put("type", "UPI")
            .put("parameters", JSONObject()
                .put("payeeVpa", upiId)
                .put("payeeName", name)
                .put("mcc", "5817")
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
