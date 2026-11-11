package com.alberto.tvfeeder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Re-arms the schedule and syncs once when the box boots.
 *
 * The periodic job is persisted, but OEM builds (this one included) are not
 * reliable about restoring it, so the boot broadcast is the guarantee.
 */
public final class BootReceiver extends BroadcastReceiver {

    /** Xiaomi/HTC style "quick boot" broadcast, sent instead of BOOT_COMPLETED. */
    static final String ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !ACTION_QUICKBOOT.equals(action)) {
            return;
        }
        SyncJobService.schedulePeriodic(context);
        SyncJobService.syncNow(context);
    }
}
