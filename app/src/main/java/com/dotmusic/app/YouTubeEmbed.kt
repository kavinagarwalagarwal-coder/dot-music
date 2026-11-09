package com.dotmusic.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * YouTube's official embedded player (IFrame Player API) in a WebView, used for YouTube songs.
 * Following YouTube's rules for embeds: the player stays visible on screen while it plays (the video
 * card on the player screen), it identifies the app with an https://<app id> referrer, it is never
 * covered, and playback stops when Dot leaves the screen.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class YouTubeEmbed(context: Context) : FrameLayout(context) {
    interface Listener {
        fun onYouTubeReady()
        /** YT.PlayerState: -1 unstarted, 0 ended, 1 playing, 2 paused, 3 buffering, 5 cued. */
        fun onYouTubeState(state: Int)
        fun onYouTubeTime(positionMs: Long, durationMs: Long)
        fun onYouTubeError(code: Int)
    }

    var listener: Listener? = null
    var ready = false
        private set
    private val main = Handler(Looper.getMainLooper())
    private val web = WebView(context)

    init {
        setBackgroundColor(Color.BLACK)
        web.setBackgroundColor(Color.BLACK)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "Dot")
        addView(web, LayoutParams(-1, -1))
        // The base URL is the referrer YouTube sees: https://<application id>, as its embed rules require.
        web.loadDataWithBaseURL("$ORIGIN/", page(), "text/html", "utf-8", null)
    }

    private var pending: Pair<String, Long>? = null

    /** Load and play a video, from [startMs]. Waits for the player if it isn't ready yet. */
    fun load(videoId: String, startMs: Long = 0) {
        if (!ID.matches(videoId)) return
        if (ready) js("dotLoad('$videoId', ${startMs.coerceAtLeast(0) / 1000.0})") else pending = videoId to startMs
    }
    fun play() = js("dotCall('playVideo')")
    fun pause() = js("dotCall('pauseVideo')")
    fun stop() = js("dotCall('stopVideo')")
    fun seekTo(ms: Long) = js("dotSeek(${ms.coerceAtLeast(0) / 1000.0})")

    fun release() {
        listener = null
        runCatching { web.stopLoading(); web.loadUrl("about:blank"); web.destroy() }
    }

    private fun js(code: String) = main.post { runCatching { web.evaluateJavascript(code, null) } }

    private inner class Bridge {
        @JavascriptInterface fun ready() {
            main.post {
                ready = true
                pending?.let { (id, at) -> pending = null; load(id, at) }
                listener?.onYouTubeReady()
            }
        }
        @JavascriptInterface fun state(s: Int) { main.post { listener?.onYouTubeState(s) } }
        @JavascriptInterface fun time(pos: Double, dur: Double) {
            main.post { listener?.onYouTubeTime((pos * 1000).toLong(), (dur * 1000).toLong()) }
        }
        @JavascriptInterface fun error(code: Int) { main.post { listener?.onYouTubeError(code) } }
    }

    private fun page() = """
<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{width:100%;height:100%}</style>
</head><body><div id="p"></div><script>
var player=null, ready=false;
var tag=document.createElement('script'); tag.src='https://www.youtube.com/iframe_api';
document.head.appendChild(tag);
function onYouTubeIframeAPIReady(){
  player=new YT.Player('p',{width:'100%',height:'100%',
    playerVars:{playsinline:1,rel:0,controls:1,origin:'$ORIGIN',widget_referrer:'$ORIGIN'},
    events:{
      onReady:function(){ready=true;Dot.ready();},
      onStateChange:function(e){Dot.state(e.data);},
      onError:function(e){Dot.error(e.data);}
    }});
}
function dotLoad(id,s){ if(ready){player.loadVideoById({videoId:id,startSeconds:s||0});} }
function dotCall(f){ if(ready&&player[f]){player[f]();} }
function dotSeek(s){ if(ready){player.seekTo(s,true);} }
setInterval(function(){ if(ready&&player.getCurrentTime){ Dot.time(player.getCurrentTime()||0, player.getDuration()||0); } },500);
</script></body></html>
""".trimIndent()

    companion object {
        /** https://<application id>: how YouTube identifies an embedding Android app. */
        const val ORIGIN = "https://com.dotmusic.app"
        private val ID = Regex("[A-Za-z0-9_-]{6,20}")
    }
}
