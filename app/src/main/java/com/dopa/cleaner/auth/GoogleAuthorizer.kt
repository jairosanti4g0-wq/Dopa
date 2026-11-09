package com.dopa.cleaner.auth

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.dopa.cleaner.core.AuthRequiredException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

object GoogleScopes {
    const val DRIVE = "https://www.googleapis.com/auth/drive"
    const val PHOTOS_PICKER = "https://www.googleapis.com/auth/photospicker.mediaitems.readonly"
}

sealed interface AuthOutcome {
    data class Granted(val accessToken: String) : AuthOutcome
    data class NeedsConsent(val pendingIntent: PendingIntent) : AuthOutcome
}

class GoogleAuthorizer(private val context: Context) {

    private fun request(scopes: List<String>): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(scopes.map { Scope(it) })
            .build()

    suspend fun authorize(scopes: List<String>): AuthOutcome {
        val result: AuthorizationResult = Identity.getAuthorizationClient(context).authorize(request(scopes)).await()
        return toOutcome(result)
    }

    fun parseConsentResult(data: Intent?): AuthOutcome {
        val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        return toOutcome(result)
    }

    suspend fun tokenOrThrow(scopes: List<String>): String =
        when (val outcome = authorize(scopes)) {
            is AuthOutcome.Granted -> outcome.accessToken
            is AuthOutcome.NeedsConsent -> throw AuthRequiredException("Autorização Google necessária: abra o app e reconecte")
        }

    private fun toOutcome(result: AuthorizationResult): AuthOutcome {
        val pending = result.pendingIntent
        if (result.hasResolution() && pending != null) return AuthOutcome.NeedsConsent(pending)
        val token = result.accessToken ?: throw AuthRequiredException("Google não retornou access token")
        return AuthOutcome.Granted(token)
    }
}
