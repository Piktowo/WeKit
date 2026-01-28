package moe.ouom.wekit.util.common;

import android.content.Context;
import android.widget.Toast;

import moe.ouom.wekit.util.log.WeLogger;

public class Toasts {
    static public void showToast(Context ctx, String msg) {
        WeLogger.i("Toasts", "showToast: " + msg);
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }
}
