// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.compat;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.os.Build;

public class ClipboardManagerCompat {

    public static void clearPrimaryClip(ClipboardManager cm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                cm.clearPrimaryClip();
            } catch (Exception e) {
                // workaround for system-caused crash in https://github.com/HeliBorg/HeliBoard/issues/203
                cm.setPrimaryClip(ClipData.newPlainText("", ""));
            }
        } else {
            cm.setPrimaryClip(ClipData.newPlainText("", ""));
        }
    }

    public static Long getClipTimestamp(ClipData cd) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final long timestamp = cd.getDescription().getTimestamp();
            if (timestamp > 0) // timestamp is 0 if not set
                return timestamp;
        }
        return System.currentTimeMillis();
    }

    public static Boolean getClipSensitivity(final ClipDescription cd) {
        // Keyboard: ClipDescription.EXTRA_IS_SENSITIVE exists from API 33, as FlorisBoard's ClipboardItem.fromClipData
        // (https://github.com/florisboard/florisboard/blob/main/app/src/main/kotlin/dev/patrickgold/florisboard/ime/clipboard/provider/ClipboardDatabase.kt)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return cd != null && cd.getExtras() != null && cd.getExtras().getBoolean(ClipDescription.EXTRA_IS_SENSITIVE);
        }
        return null; // can't determine
    }
}
