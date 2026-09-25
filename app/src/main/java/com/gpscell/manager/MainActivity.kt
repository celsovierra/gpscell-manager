package com.gpscell.manager

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var fcmToken: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isTokenRegistered = false

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileUploadLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data
                if (data != null && data.dataString != null) {
                    filePathCallback?.onReceiveValue(arrayOf(Uri.parse(data.dataString)))
                } else if (data != null && data.clipData != null) {
                    val clipData = data.clipData
                    val uris = arrayOfNulls<Uri>(clipData!!.itemCount)
                    for (i in 0 until clipData.itemCount) {
                        uris[i] = clipData.getItemAt(i).uri
                    }
                    filePathCallback?.onReceiveValue(uris.filterNotNull().toTypedArray())
                } else {
                    filePathCallback?.onReceiveValue(null)
                }
            } else {
                filePathCallback?.onReceiveValue(null)
            }
            filePathCallback = null
        }

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                webView.reload()
            }
        }

    private val syncRunnable = object : Runnable {
        override fun run() {
            if (!isTokenRegistered) {
                injectTokenScript()
                handler.postDelayed(this, 3000)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        askNotificationPermission()
        setupFirebase()

        webView = findViewById(R.id.webView)
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
            setGeolocationEnabled(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.addJavascriptInterface(WebAppInterface(), "AndroidNative")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectTokenScript()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                request?.grant(request.resources)
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback != null) {
                    filePathCallback?.onReceiveValue(null)
                }
                filePathCallback = callback

                val intent = fileChooserParams?.createIntent()
                try {
                    if (intent != null) {
                        fileUploadLauncher.launch(intent)
                    } else {
                        return false
                    }
                } catch (e: ActivityNotFoundException) {
                    filePathCallback = null
                    return false
                }
                return true
            }
        }

        webView.loadUrl("https://gpscell.site/")

        handler.post(syncRunnable)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(syncRunnable)
    }

    private fun injectTokenScript() {
        val token = fcmToken ?: return
        if (isTokenRegistered) return

        val js = """
            (function() {
                if (window.isRegisteringToken) return;
                window.isRegisteringToken = true;

                fetch('/api/session')
                    .then(function(res) {
                        if (!res.ok) throw new Error('not_logged');
                        return res.json();
                    })
                    .then(function(user) {
                        if (!user || !user.id) return;
                        
                        var tokens = user.attributes && user.attributes.notificationTokens 
                            ? user.attributes.notificationTokens.split(',') 
                            : [];
                        
                        if (tokens.indexOf('$token') !== -1) {
                            AndroidNative.onTokenSuccess();
                            return;
                        }

                        tokens.push('$token');
                        user.attributes = user.attributes || {};
                        user.attributes.notificationTokens = tokens.join(',');

                        fetch('/api/users/' + user.id, {
                            method: 'PUT',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify(user)
                        }).then(function(putRes) {
                            if (putRes.ok) {
                                AndroidNative.onTokenSuccess();
                            }
                        }).catch(function(err) {});
                    })
                    .catch(function(err) {
                        window.isRegisteringToken = false;
                    });
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun onTokenSuccess() {
            isTokenRegistered = true
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupFirebase() {
        FirebaseMessaging.getInstance().subscribeToTopic("all")
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                fcmToken = task.result
            }
        }
    }
}
