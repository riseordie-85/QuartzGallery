package com.tim.gallery.personal

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal

/**
 * Unlocking with the phone's own security: fingerprint or face (whichever the phone has set up;
 * Android decides which to show, apps can't pick), and/or the phone's screen lock (pattern, PIN
 * or password).
 */
object VaultAuth {

    /** Fingerprint or face unlock is set up on this phone. */
    fun biometricAvailable(context: Context): Boolean {
        val bm = context.getSystemService(BiometricManager::class.java) ?: return false
        return bm.canAuthenticate(Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /** The phone has a pattern, PIN or password. */
    fun screenLockAvailable(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** What's allowed right now (settings intersected with what the phone has). */
    fun canUseSystemUnlock(context: Context): Boolean =
        (Vault.allowBiometric && biometricAvailable(context)) ||
            (Vault.allowDeviceCredential && screenLockAvailable(context))

    fun systemUnlockLabel(): String = when {
        Vault.allowBiometric && Vault.allowDeviceCredential -> "Use fingerprint, face or screen lock"
        Vault.allowBiometric -> "Use fingerprint or face"
        else -> "Use screen lock"
    }

    /**
     * Shows Android's unlock prompt. [onSuccess] runs on the main thread. Cancelling or failing
     * just closes it (wrong fingers or faces don't count toward the passcode limit; Android
     * locks out repeated failures itself).
     */
    fun prompt(context: Context, onSuccess: () -> Unit, onError: (String) -> Unit = {}) {
        val bio = Vault.allowBiometric && biometricAvailable(context)
        val cred = Vault.allowDeviceCredential && screenLockAvailable(context)
        if (!bio && !cred) return
        val authenticators = (if (bio) Authenticators.BIOMETRIC_WEAK else 0) or
            (if (cred) Authenticators.DEVICE_CREDENTIAL else 0)
        val executor = context.mainExecutor
        val builder = BiometricPrompt.Builder(context)
            .setTitle("Unlock personal folder")
            .setAllowedAuthenticators(authenticators)
        if (!cred) {
            // A "cancel" button is required when the screen lock isn't one of the options
            builder.setNegativeButton("Use passcode", executor) { _, _ -> }
        }
        try {
            builder.build().authenticate(
                CancellationSignal(),
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        onSuccess()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                            errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                        ) {
                            onError(errString.toString())
                        }
                    }
                },
            )
        } catch (e: Exception) {
            onError("Couldn't start unlock")
        }
    }
}
