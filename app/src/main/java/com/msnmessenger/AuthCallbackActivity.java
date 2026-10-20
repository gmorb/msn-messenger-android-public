package com.msnmessenger;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Receives the browser's reply after SSO or OAuth sign-in and hands it to the sign-in screen. */
public class AuthCallbackActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        if (in != null && in.getData() != null)
            startActivity(new Intent(this, LoginActivity.class).setData(in.getData())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }
}
