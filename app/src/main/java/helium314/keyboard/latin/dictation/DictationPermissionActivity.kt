// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity

/**
 * The small transparent activity that asks for the microphone: an input method cannot show the permission dialog,
 * an activity of the app can. The user then taps the mic again (DictationController).
 * Same as Transcribro, which asks RECORD_AUDIO from its recognition screen
 * https://github.com/soupslurpr/Transcribro/blob/main/app/src/main/kotlin/dev/soupslurpr/transcribro/ui/action_recognize_speech/ActionRecognizeSpeechScreen.kt
 * with Activity.requestPermissions and onRequestPermissionsResult of Android's guide
 * https://developer.android.com/training/permissions/requesting
 * and, once the user has refused twice (the dialog no longer comes), the app's page in Android's settings, as the
 * guide's "explain why" flow ends: https://developer.android.com/training/permissions/requesting#handle-denial
 */
class DictationPermissionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            finish()
            return
        }
        if (savedInstanceState == null) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        // refused and the system will not ask again: only Android's settings can allow it
        if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO))
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        finish()
    }

    private companion object {
        const val REQUEST = 1
    }
}
