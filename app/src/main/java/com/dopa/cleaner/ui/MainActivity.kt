package com.dopa.cleaner.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private val vm: MainViewModel by viewModels()
    private var consentPurpose: ConsentPurpose? = null

    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r: ActivityResult ->
        val purpose = consentPurpose ?: return@registerForActivityResult
        consentPurpose = null
        vm.onConsentResult(purpose, r.resultCode == RESULT_OK, r.data)
    }

    private val treeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.addSafFolder(uri)
    }

    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.consent.collect { pc ->
                        if (pc != null && consentPurpose == null) {
                            consentPurpose = pc.purpose
                            consentLauncher.launch(IntentSenderRequest.Builder(pc.intent.intentSender).build())
                        }
                    }
                }
                launch { vm.messageFlow.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() } }
                launch { vm.urlFlow.collect { openUrl(it) } }
            }
        }
        setContent {
            DopaTheme {
                val settings by vm.settings.collectAsStateWithLifecycle()
                val locked by vm.locked.collectAsStateWithLifecycle()
                val s = settings
                when {
                    s == null -> LockScreen(onUnlock = {}, waiting = true)
                    s.appLock && locked -> LockScreen(onUnlock = ::authenticate, waiting = false)
                    else -> DopaRoot(
                        vm = vm,
                        onRequestAllFiles = ::requestAllFiles,
                        onAddFolder = { treeLauncher.launch(null) },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshPermissions()
    }

    override fun onStart() {
        super.onStart()
        vm.onAppForegrounded(vm.settings.value?.appLock ?: true)
    }

    override fun onStop() {
        vm.onAppBackgrounded()
        super.onStop()
    }

    private fun authenticate() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val status = BiometricManager.from(this).canAuthenticate(authenticators)
        if (status != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, "Configure uma trava de tela no Android para usar o bloqueio", Toast.LENGTH_LONG).show()
            vm.unlock()
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    vm.unlock()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_CANCELED) {
                        Toast.makeText(this@MainActivity, errString, Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Desbloquear Dopa Cleaner")
                .setAllowedAuthenticators(authenticators)
                .build(),
        )
    }

    private fun requestAllFiles() {
        val specific = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
        try {
            startActivity(specific)
        } catch (e: ActivityNotFoundException) {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                .onFailure { Toast.makeText(this, "Abra Configurações > Apps > Acesso especial > Todos os arquivos", Toast.LENGTH_LONG).show() }
        }
    }

    private fun openUrl(url: String) {
        try {
            CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(url))
        } catch (e: ActivityNotFoundException) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                .onFailure { Toast.makeText(this, "Nenhum navegador disponível", Toast.LENGTH_LONG).show() }
        }
    }
}
