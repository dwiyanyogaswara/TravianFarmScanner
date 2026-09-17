package com.example.travcofarm

import android.annotation.SuppressLint
import android.webkit.*
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONTokener
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WebViewRunner(private val web: WebView) {
    private var waiter: ((Boolean)->Unit)? = null

    init {
        @SuppressLint("SetJavaScriptEnabled")
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = userAgentString + " TravcoOasisFarmlist/1.0"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,true)
    }

    suspend fun load(url:String) = suspendCancellableCoroutine<Unit> { cont ->
        web.webViewClient = object: WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (cont.isActive) cont.resume(Unit)
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true && cont.isActive)
                    cont.resumeWithException(IllegalStateException(error?.description?.toString() ?: "WebView error"))
            }
        }
        web.loadUrl(url)
        cont.invokeOnCancellation { web.stopLoading() }
    }

    suspend fun js(expression:String): Any? = suspendCancellableCoroutine { cont ->
        web.evaluateJavascript(expression) { raw ->
            try {
                val value = JSONTokener(raw).nextValue()
                if (cont.isActive) cont.resume(value)
            } catch (e:Exception) { if(cont.isActive) cont.resumeWithException(e) }
        }
    }

    suspend fun jsString(expression:String):String = (js(expression) as? String) ?: ""
    suspend fun jsBoolean(expression:String):Boolean = js(expression) as? Boolean ?: false
    fun currentUrl() = web.url ?: ""
}
