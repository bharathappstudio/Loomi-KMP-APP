package com.echo.loomi

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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

class ShortsJSInterface(private val onEnd: () -> Unit) {
    @JavascriptInterface
    fun onVideoEnd() {
        onEnd()
    }
}

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
            // RELIABLE TAMIL FALLBACK VIDEOS
            videos = listOf(
                ShortVideo("j_S7Yj5QY_o", "Tamil Comedy Short", "TamilFun", "Funny Tamil short"),
                ShortVideo("S-z_pXm6W8s", "Tamil Food Vlog", "VillageCooking", "Traditional Tamil food"),
                ShortVideo("k_PqWp8aM-Y", "Tamil Movie Scene", "TamilCinema", "Popular scene"),
                ShortVideo("m_XyZq9bL1o", "Tamil Nature Vlog", "GreenTamil", "Beautiful Tamil Nadu")
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
                beyondViewportPageCount = 3, // Preload more to avoid black screen
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
                                    pagerState.animateScrollToPage(page + 1)
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
    
    // Manage playback state based on visibility
    LaunchedEffect(isVisible) {
        if (isVisible) {
            webViewRef?.evaluateJavascript(
                "(function() { " +
                "   if (typeof player !== 'undefined' && player.playVideo) { " +
                "       player.playVideo(); " +
                "       player.unMute(); " +
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
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    addJavascriptInterface(ShortsJSInterface(onVideoEnd), "Android")
                    
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (isVisible) {
                                view?.evaluateJavascript(
                                    "if (typeof player !== 'undefined' && player.playVideo) { player.playVideo(); player.unMute(); }",
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
                        cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.6167.164 Mobile Safari/537.36"
                    }
                    setBackgroundColor(android.graphics.Color.BLACK)
                    
                    val domain = "https://echo-loomi-app.firebaseapp.com"
                    val html = """
                        <!DOCTYPE html>
                        <html>
                        <head>
                            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                            <meta name="referrer" content="strict-origin-when-cross-origin">
                            <style>
                                body { margin: 0; padding: 0; background: black; overflow: hidden; width: 100vw; height: 100vh; display: flex; align-items: center; justify-content: center; }
                                #player { width: 100vw; height: 100vh; }
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
                                            'origin': '$domain'
                                        },
                                        events: {
                                            'onReady': onPlayerReady,
                                            'onStateChange': onPlayerStateChange
                                        }
                                    });
                                }

                                function onPlayerReady(event) {
                                    event.target.playVideo();
                                }

                                function onPlayerStateChange(event) {
                                    if (event.data == YT.PlayerState.ENDED) {
                                        Android.onVideoEnd();
                                    }
                                }
                            </script>
                        </body>
                        </html>
                    """.trimIndent()
                    
                    loadDataWithBaseURL(domain, html, "text/html", "utf-8", null)
                    webViewRef = this
                }
            },
            onRelease = { 
                it.stopLoading()
                it.destroy() 
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

private suspend fun fetchYouTubeShorts(): List<ShortVideo> {
    val apiKey = "AIzaSyAzthkBb-hKb7EnqxaNe4ZGrCeZchamSoI"
    // Use a random factor to ensure fresh content every time
    val randomPageToken = listOf("", "CAoQAA", "CBkQAA", "CCIQAA", "CDIQAA").random()
    val urlString = "https://www.googleapis.com/youtube/v3/search?part=snippet&q=tamil+shorts&type=video&videoDuration=short&maxResults=15&relevanceLanguage=ta&pageToken=$randomPageToken&key=$apiKey"
    
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
