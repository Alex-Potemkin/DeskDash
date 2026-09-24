package com.deskdash;

import android.content.Intent;
import android.service.dreams.DreamService;

/** Screensaver: the same dashboard shows up by itself while the phone is charging. */
public class DashDream extends DreamService {
    private DashboardView dash;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true);
        setFullscreen(true);
        setScreenBright(true);
        dash = new DashboardView(this, getWindow(), () -> {
            startActivity(new Intent(this, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        });
        setContentView(dash);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        dash.refresh();
        dash.start();
    }

    @Override
    public void onDreamingStopped() {
        dash.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (dash != null) dash.shutdown();
        super.onDetachedFromWindow();
    }
}
