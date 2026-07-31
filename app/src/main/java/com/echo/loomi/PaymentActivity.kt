package com.echo.loomi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echo.loomi.ui.theme.LoomiTheme

class PaymentActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)

        setContent {
            LoomiTheme {
                var showQr by remember { mutableStateOf(false) }
                val isDark = isSystemInDarkTheme()

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.b1),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(if (showQr) 20.dp else 0.dp),
                        contentScale = ContentScale.Crop
                    )

                    if (!showQr) {
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
                                        .clickable { showQr = true },
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
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable { showQr = false },
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.qr),
                                contentDescription = "QR Code",
                                modifier = Modifier
                                    .fillMaxWidth(0.8f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color.White)
                                    .padding(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
