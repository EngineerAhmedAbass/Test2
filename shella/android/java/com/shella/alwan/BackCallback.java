package com.shella.alwan;

import android.app.Activity;
import android.webkit.ValueCallback;

/** Back button: let the page close its own popups first, otherwise send the app to the background. */
final class BackCallback implements ValueCallback<String> {
    private final Activity activity;

    BackCallback(Activity activity) {
        this.activity = activity;
    }

    @Override
    public void onReceiveValue(String value) {
        if (!"true".equals(value)) activity.moveTaskToBack(true);
    }
}
