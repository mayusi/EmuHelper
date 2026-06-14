package io.github.mayusi.emuhelper.ui.login

import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.mayusi.emuhelper.ui.common.openUrl

private const val SIGNUP_URL = "https://archive.org/account/signup"

/** Hosts that the in-app WebView is allowed to navigate within. */
private fun isAllowedHost(host: String?): Boolean {
    if (host == null) return false
    // Internet Archive itself (and its subdomains)
    if (host == "archive.org" || host.endsWith(".archive.org")) return true
    // reCAPTCHA / gstatic domains frequently used by signup forms
    if (host == "www.google.com" || host.endsWith(".google.com")) return true
    if (host == "www.gstatic.com" || host.endsWith(".gstatic.com")) return true
    if (host == "www.recaptcha.net" || host.endsWith(".recaptcha.net")) return true
    return false
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignupWebViewScreen(
    onDone: () -> Unit,
    onOpenInBrowser: () -> Unit
) {
    val context = LocalContext.current

    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }

    // Remember a single WebView instance across recompositions.
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // File access stays at default-off — do not enable file access or
            // universal access from file URLs for third-party content.

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    isLoading = true
                    hasError = false
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    isLoading = false
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    // Only surface errors for the main frame, not sub-resource loads.
                    if (request.isForMainFrame) {
                        isLoading = false
                        hasError = true
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val host = request.url?.host
                    return if (isAllowedHost(host)) {
                        // Allow the WebView to handle it normally.
                        false
                    } else {
                        // Open external links in the system browser; block them in-app.
                        context.openUrl(request.url.toString())
                        true
                    }
                }
            }

            loadUrl(SIGNUP_URL)
        }
    }

    // Destroy the WebView when the composable leaves composition to avoid leaks.
    DisposableEffect(Unit) {
        onDispose { webView.destroy() }
    }

    // Handle the system back button: navigate WebView history first, then exit.
    BackHandler {
        if (webView.canGoBack()) webView.goBack() else onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create account") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (webView.canGoBack()) webView.goBack() else onDone()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        context.openUrl(webView.url ?: SIGNUP_URL)
                        onOpenInBrowser()
                    }) {
                        Icon(
                            Icons.Default.OpenInBrowser,
                            contentDescription = "Open in browser"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                hasError -> {
                    // Error state: friendly message + escape hatch.
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Couldn't load the signup page.",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Check your internet connection and try again, or open the page in your browser.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { webView.reload() }) {
                            Text("Try again")
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = {
                            context.openUrl(SIGNUP_URL)
                            onOpenInBrowser()
                        }) {
                            Text("Open in browser instead")
                        }
                    }
                }

                else -> {
                    // Normal WebView.
                    AndroidView(
                        factory = { webView },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Loading indicator overlaid on top of the WebView.
                    if (isLoading) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }

            // Always-visible "Done – back to sign in" FAB-style text button at bottom.
            TextButton(
                onClick = onDone,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
            ) {
                Text(
                    "Done — back to sign in",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
