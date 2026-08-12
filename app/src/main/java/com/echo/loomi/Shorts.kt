package com.echo.loomi

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    var videos by remember { mutableStateOf(fallbackVideos().shuffled()) }
    var isInitialLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    androidx.activity.compose.BackHandler {
        onBack()
    }

    LaunchedEffect(Unit) {
        // Force a 3-second professional loading boot
        launch {
            delay(3000)
            isInitialLoading = false
        }
        
        val fetchedVideos = withContext(Dispatchers.IO) { fetchYouTubeShorts() }
        if (fetchedVideos.isNotEmpty()) {
            videos = fetchedVideos.shuffled()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (isInitialLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        } else {
            val pagerState = rememberPagerState(pageCount = { videos.size })

            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 2,
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
                                    pagerState.animateScrollToPage(
                                        page + 1,
                                        animationSpec = tween(durationMillis = 250, easing = androidx.compose.animation.core.FastOutSlowInEasing)
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

    LaunchedEffect(isVisible, isPlayerReady) {
        if (isVisible && isPlayerReady) {
            webViewRef?.evaluateJavascript(
                "(function() { if (typeof player !== 'undefined' && player.playVideo) { player.playVideo(); player.unMute(); player.setPlaybackQuality('small'); } })();",
                null
            )
        } else {
            webViewRef?.evaluateJavascript(
                "(function() { if (typeof player !== 'undefined' && player.pauseVideo) { player.pauseVideo(); } })();",
                null
            )
        }
    }

    // ONLY the WebView. No play/pause icon, no Shorts branding, no username/bio, no thumbnail — nothing on top of the video.
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onVideoEnd() { onVideoEnd() }
                        @JavascriptInterface
                        fun onReady() { isPlayerReady = true }
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
                    // preconnect hints + solid black bg = fastest possible boot, no flash, no gap
                    val html = """
                        <!DOCTYPE html>
                        <html>
                        <head>
                            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                            <link rel="preconnect" href="https://www.youtube.com">
                            <link rel="preconnect" href="https://i.ytimg.com">
                            <link rel="dns-prefetch" href="https://www.youtube.com">
                            <style>
                                html, body { margin: 0; padding: 0; background: #000; overflow: hidden; width: 100vw; height: 100vh; }
                                #player { width: 100vw; height: 100vh; pointer-events: none; opacity: 0; transition: opacity 0.2s; }
                                .playing #player { opacity: 1; }
                                iframe { border: 0; }
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
                                            'fs': 0,
                                            'disablekb': 1,
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
                                                    document.body.classList.add('playing');
                                                }
                                                if (event.data == YT.PlayerState.BUFFERING) {
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
    }
}

private fun fallbackVideos(): List<ShortVideo> = listOf(
    ShortVideo("j_S7Yj5QY_o", "Tamil Comedy Short", "TamilFun", "Funny Tamil short"),
    ShortVideo("S-z_pXm6W8s", "Tamil Food Vlog", "VillageCooking", "Traditional Tamil food"),
    ShortVideo("k_PqWp8aM-Y", "Tamil Movie Scene", "TamilCinema", "Popular scene"),
    ShortVideo("m_XyZq9bL1o", "Tamil Nature Vlog", "GreenTamil", "Beautiful Tamil Nadu"),
    ShortVideo("R96_B1-rUic", "Tamil Fun Challenge", "TamilVibe", "Funny game"),
    ShortVideo("w-8yK5rX6oY", "Tamil Street Food", "FoodTamil", "Delicious food"),
    ShortVideo("E7p7O_l4P5w", "Tamil Tech Short", "TechTamil", "Gadget review"),
    ShortVideo("6z6_m_XyZq9", "Tamil Gaming", "TamilGamer", "Pro play"),
    ShortVideo("pXm6W8s_S-z", "Tamil Lifestyle", "VibeTamil", "Daily vlog")
)

private suspend fun fetchYouTubeShorts(): List<ShortVideo> {
    val apiKey = "AIzaSyAzthkBb-hKb7EnqxaNe4ZGrCeZchamSoI"

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