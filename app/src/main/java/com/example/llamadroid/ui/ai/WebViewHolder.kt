package com.example.llamadroid.ui.ai

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest

/**
 * Singleton holder for WebViews to persist across navigation.
 * This prevents WebViews from being destroyed when user navigates away,
 * allowing long-running tasks (like image generation) to continue.
 */
object WebViewHolder {
    // Map of URL -> WebView
    private val webViews = mutableMapOf<String, WebView>()
    
    // Callbacks for loading/error states
    private val loadingCallbacks = mutableMapOf<String, (Boolean) -> Unit>()
    private val errorCallbacks = mutableMapOf<String, (String?) -> Unit>()
    
    /**
     * Get or create a WebView for the given URL.
     * If a WebView already exists for this URL, it will be reused.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreate(
        context: Context,
        url: String,
        onLoading: (Boolean) -> Unit = {},
        onError: (String?) -> Unit = {}
    ): WebView {
        // Store callbacks
        loadingCallbacks[url] = onLoading
        errorCallbacks[url] = onError
        
        // Return existing WebView if available
        webViews[url]?.let { existingWebView ->
            // Detach from any parent first
            (existingWebView.parent as? ViewGroup)?.removeView(existingWebView)
            return existingWebView
        }
        
        // Use the current Activity context so popup-backed controls such as
        // HTML select menus have a valid window token inside retained WebViews.
        val webView = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFocusable = true
            isFocusableInTouchMode = true
            applyKeyboardAwareInsetsFix()
            
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(true)
                allowFileAccess = true
                allowContentAccess = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                builtInZoomControls = true
                displayZoomControls = false
                loadWithOverviewMode = true
                useWideViewPort = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    view?.injectKeyboardViewportFix()
                    loadingCallbacks[url]?.invoke(false)
                }
                
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        loadingCallbacks[url]?.invoke(false)
                        errorCallbacks[url]?.invoke(error?.description?.toString())
                    }
                }
            }
            
            loadUrl(url)
        }
        
        webViews[url] = webView
        return webView
    }
    
    /**
     * Check if a WebView exists for the given URL
     */
    fun exists(url: String): Boolean = webViews.containsKey(url)
    
    /**
     * Reload the WebView for the given URL
     */
    fun reload(url: String) {
        webViews[url]?.reload()
    }

    /**
     * Clear all retained browser state for this WebView surface and destroy the
     * in-memory instance so the next open starts from a fresh WebView.
     */
    fun clearBrowserState(url: String) {
        webViews[url]?.let { webView ->
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.clearCache(true)
            webView.clearHistory()
            webView.clearFormData()
            webView.clearSslPreferences()
            webView.destroy()
        }
        webViews.remove(url)
        loadingCallbacks.remove(url)
        errorCallbacks.remove(url)
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        runCatching {
            WebStorage.getInstance().deleteAllData()
        }
    }
    
    /**
     * Destroy and remove the WebView for the given URL
     */
    fun destroy(url: String) {
        webViews[url]?.let { webView ->
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        webViews.remove(url)
        loadingCallbacks.remove(url)
        errorCallbacks.remove(url)
    }
    
    /**
     * Destroy all WebViews (call on app exit)
     */
    fun destroyAll() {
        webViews.values.forEach { webView ->
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        webViews.clear()
        loadingCallbacks.clear()
        errorCallbacks.clear()
    }
    
    /**
     * Get list of active WebView URLs
     */
    fun getActiveUrls(): List<String> = webViews.keys.toList()
}
