package com.blainemiller.scripturealone.wear;

import android.content.ContentResolver;
import android.provider.Settings;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowSettings;

/**
 * A watch that keeps the {@code reduce_motion} global setting from apps targeting past API 34 — the
 * SecurityException in the Play vitals reports. (Java: Kotlin cannot override the stock shadow's static
 * {@code getInt} — same JVM signature.)
 */
@Implements(Settings.Global.class)
public class RestrictedSettingsGlobal extends ShadowSettings.ShadowGlobal {
    @Implementation
    protected static int getInt(ContentResolver cr, String name, int def) {
        if ("reduce_motion".equals(name)) {
            throw new SecurityException("Settings key: <reduce_motion> is only readable to apps with targetSdkVersion lower than or equal to: 34");
        }
        return ShadowSettings.ShadowGlobal.getInt(cr, name, def);
    }
}
