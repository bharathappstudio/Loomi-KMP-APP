package com.echo.loomi

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ShortVideo(
    val id: String,
    val title: String,
    val channelTitle: String,
    val description: String
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ShortsScreen(onBack: () -> Unit) {
    var videos by remember { mutableStateOf<List<ShortVideo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // Handle system back button to go back to MainActivity
    androidx.activity.compose.BackHandler {
        onBack()
    }

    LaunchedEffect(Unit) {
        val fetchedVideos = withContext(Dispatchers.IO) { fetchYouTubeShorts() }
        if (fetchedVideos.isNotEmpty()) {
            videos = fetchedVideos.shuffled() // Shuffle for new variety every time
        } else {
            // RELIABLE TAMIL FALLBACK VIDEOS (Expanded Variety)
            videos = listOf(
                ShortVideo("j_S7Yj5QY_o", "Tamil Comedy Short", "TamilFun", "Funny Tamil short"),
                ShortVideo("S-z_pXm6W8s", "Tamil Food Vlog", "VillageCooking", "Traditional Tamil food"),
                ShortVideo("k_PqWp8aM-Y", "Tamil Movie Scene", "TamilCinema", "Popular scene"),
                ShortVideo("m_XyZq9bL1o", "Tamil Nature Vlog", "GreenTamil", "Beautiful Tamil Nadu"),
                ShortVideo("R96_B1-rUic", "Tamil Fun Challenge", "TamilVibe", "Funny game"),
                ShortVideo("w-8yK5rX6oY", "Tamil Street Food", "FoodTamil", "Delicious food"),
                ShortVideo("E7p7O_l4P5w", "Tamil Tech Short", "TechTamil", "Gadget review"),
                ShortVideo("E7p7O_l4P5w", "Tamil Joke Time", "ComedyJunction", "Super jokes"),
                ShortVideo("6z6_m_XyZq9", "Tamil Gaming", "TamilGamer", "Pro play"),
                ShortVideo("pXm6W8s_S-z", "Tamil Lifestyle", "VibeTamil", "Daily vlog")
            ).shuffled()
        }
        isLoading = false
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White
            )
        } else if (videos.isNotEmpty()) {
            val pagerState = rememberPagerState(pageCount = { videos.size })
            
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 2, // Optimized preloading (prevents CPU/Memory choke)
                pageSpacing = 0.dp,
                contentPadding = PaddingValues(0.dp)
            ) { page ->
                val isVisible = pagerState.currentPage == page
                key(videos[page].id) {
                    ShortVideoPlayer(
                        video = videos[page],
                        isVisible = isVisible,
                        onVideoEnd = {
                            if (page < videos.size - 1) {
                                scope.launch {
                                    // Extreme Fast and "Butter" smooth scroll
                                    pagerState.animateScrollToPage(
                                        page + 1,
                                        animationSpec = tween(durationMillis = 300, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ShortVideoPlayer(video: ShortVideo, isVisible: Boolean, onVideoEnd: () -> Unit) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isPlayerReady by remember { mutableStateOf(false) }
    var isVideoPlaying by remember { mutableStateOf(false) }
    
    // Manage playback state based on visibility
    LaunchedEffect(isVisible, isPlayerReady) {
        if (isVisible && isPlayerReady) {
            webViewRef?.evaluateJavascript(
                "(function() { " +
                "   if (typeof player !== 'undefined' && player.playVideo) { " +
                "       player.playVideo(); " +
                "       player.unMute(); " +
                "       player.setPlaybackQuality('small'); " +
                "   } " +
                "})();",
                null
            )
        } else {
            webViewRef?.evaluateJavascript(
                "(function() { " +
                "   if (typeof player !== 'undefined' && player.pauseVideo) { " +
                "       player.pauseVideo(); " +
                "   } " +
                "})();",
                null
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // --- 1. LIGHTWEIGHT WEBVIEW (The Video Engine) ---
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    
                    // The "Github FastBoot" Bridge
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onVideoEnd() { onVideoEnd() }
                        @JavascriptInterface
                        fun onReady() { isPlayerReady = true }
                        @JavascriptInterface
                        fun onPlaying() { isVideoPlaying = true }
                    }, "Android")
                    
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (isVisible) {
                                view?.evaluateJavascript(
                                    "if (typeof player !== 'undefined' && player.playVideo) { player.playVideo(); player.unMute(); player.setPlaybackQuality('small'); }",
                                    null
                                )
                            }
                        }
                    }
                    webChromeClient = WebChromeClient()
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        mediaPlaybackRequiresUserGesture = false
                        cacheMode = WebSettings.LOAD_DEFAULT
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        // Performance Hacks from top GitHub Repos
                        databaseEnabled = true
                        @Suppress("DEPRECATION")
                        setRenderPriority(WebSettings.RenderPriority.HIGH)
                        @Suppress("DEPRECATION")
                        enableSmoothTransition()
                        
                        setGeolocationEnabled(false)
                        allowFileAccess = false
                        allowContentAccess = false
                        
                        userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.6167.164 Mobile Safari/537.36"
                    }
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                    setBackgroundColor(0) 
                    
                    val domain = "https://echo-loomi-app.firebaseapp.com"
                    // --- 2. ULTRA LIGHTWEIGHT HTML (Minimalist Loader) ---
                    val html = """
                        <!DOCTYPE html>
                        <html>
                        <head>
                            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                            <style>
                                body { margin: 0; padding: 0; background: transparent; overflow: hidden; width: 100vw; height: 100vh; }
                                #player { width: 100vw; height: 100vh; pointer-events: none; }
                            </style>
                        </head>
                        <body>
                            <div id="player"></div>
                            <script>
                                var tag = document.createElement('script');
                                tag.src = "https://www.youtube.com/iframe_api";
                                var firstScriptTag = document.getElementsByTagName('script')[0];
                                firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);

                                var player;
                                var retryCount = 0;
                                function onYouTubeIframeAPIReady() {
                                    player = new YT.Player('player', {
                                        height: '100%',
                                        width: '100%',
                                        videoId: '${video.id}',
                                        playerVars: {
                                            'autoplay': 1,
                                            'mute': 1,
                                            'controls': 0,
                                            'modestbranding': 1,
                                            'loop': 0,
                                            'rel': 0,
                                            'showinfo': 0,
                                            'iv_load_policy': 3,
                                            'playsinline': 1,
                                            'vq': 'small',
                                            'origin': '$domain',
                                            'widget_referrer': '$domain'
                                        },
                                        events: {
                                            'onReady': function(event) {
                                                Android.onReady();
                                                event.target.setPlaybackQuality('small');
                                                if (${isVisible}) {
                                                    event.target.playVideo();
                                                    event.target.unMute();
                                                }
                                            },
                                            'onStateChange': function(event) {
                                                if (event.data == YT.PlayerState.PLAYING) {
                                                    Android.onPlaying();
                                                } else if (event.data == YT.PlayerState.BUFFERING) {
                                                    // Ensure we keep trying to play if it gets stuck buffering
                                                    if (${isVisible}) event.target.playVideo();
                                                } else if (event.data == YT.PlayerState.ENDED) {
                                                    Android.onVideoEnd();
                                                }
                                            },
                                            'onError': function() { Android.onVideoEnd(); }
                                        }
                                    });
                                }
                            </script>
                        </body>
                        </html>
                    """.trimIndent()
                    
                    loadDataWithBaseURL(domain, html, "text/html", "utf-8", null)
                    webViewRef = this
                }
            },
            update = { /* No-op, handled by LaunchedEffect */ },
            onRelease = { 
                it.stopLoading()
                it.destroy() 
            },
            modifier = Modifier.fillMaxSize()
        )

        // --- 3. PRO THUMBNAIL OVERLAY (The "Instant Play" Illusion) ---
        // Hidden only when the actual video is confirmed to be playing
        AnimatedVisibility(
            visible = !isVideoPlaying,
            exit = fadeOut(animationSpec = tween(300))
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data("https://img.youtube.com/vi/${video.id}/maxresdefault.jpg")
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

private suspend fun fetchYouTubeShorts(): List<ShortVideo> {
    val apiKey = "AIzaSyAzthkBb-hKb7EnqxaNe4ZGrCeZchamSoI"
    
    // Use a pool of diverse queries to ensure variety every time
    val queries = listOf(
        "tamil+shorts",
        "tamil+comedy+shorts",
        "tamil+funny+shorts",
        "tamil+vlog+shorts",
        "tamil+food+shorts",
        "tamil+gaming+shorts",
        "tamil+tech+shorts",
        "tamil+status+shorts"
    )
    val randomQuery = queries.random()
    
    // Use a random factor to ensure fresh content every time
    val randomPageToken = listOf("", "CAoQAA", "CBkQAA", "CCIQAA", "CDIQAA", "CPoBEAA", "CKwBEAA").random()
    val urlString = "https://www.googleapis.com/youtube/v3/search?part=snippet&q=$randomQuery&type=video&videoDuration=short&maxResults=25&relevanceLanguage=ta&pageToken=$randomPageToken&key=$apiKey"
    
    return try {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        
        val responseCode = connection.responseCode
        if (responseCode != 200) {
            val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
            android.util.Log.e("ShortsAPI", "Error $responseCode: $error")
            return emptyList()
        }
        
        val response = connection.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(response)
        val items = json.optJSONArray("items") ?: return emptyList()
        
        val videoList = mutableListOf<ShortVideo>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val idObj = item.optJSONObject("id")
            val videoId = idObj?.optString("videoId") ?: continue
            val snippet = item.optJSONObject("snippet") ?: continue
            val title = snippet.optString("title")
            val channelTitle = snippet.optString("channelTitle")
            val description = snippet.optString("description")
            
            videoList.add(ShortVideo(videoId, title, channelTitle, description))
        }
        videoList
    } catch (e: Exception) {
        android.util.Log.e("ShortsAPI", "Failed to fetch shorts", e)
        emptyList()
    }
}
