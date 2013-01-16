/*
 * This is the Activity ("Front Door") class to QuiteUnlock
 *
 * This handles:
 *   Dialog choice for Vibrate/Silent, Start, and Cancel
 *   Starting QuietUnlockService
 *   Sending updates to Service based on user's selection
 *
 * Author: Turtle Kalus (turtlekalus.com)
 *   Date: 2012-12-28
 *
 *   TODO:
 *     Settings Menu to Activate/Deactivate use of DeviceAdmin
 *       (I.E. allow device lock on "OK" button push).
 *     Settings for default Vibrate/Silent; currently hard coded.
 */

package com.turtlekalus.android.quietunlock;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.Button;
import android.widget.Toast;
import java.util.ArrayList;
import com.philippheckel.service.ServiceManager;
import com.turtlekalus.android.quietunlock.R;

public class QuietUnlock extends Activity
{
    private final String TAG = "QuietUnlock";
    private final static int SELECT_SILENT  = 0;
    private final static int SELECT_VIBRATE = 1;

    private static int mRestoreRingerMode;
    private ServiceManager mServiceManager;

    // Main enterance into Program/Activity.
    // App hasthe effect of a Dialog Box floating over the Home Screen.
    // Transparent Theme for main activity is used to accomplish this
    // effect.
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.main);
        // Get default Silent/Vibe setting from Service
        // TODO Should be user-config'able
        // Start Service Manager for QuietUnlockService
        this.mServiceManager = new ServiceManager(this, QuietUnlockService.class, new Handler() {
            @Override
            public void handleMessage(Message msg) {
                // Non-Op
                super.handleMessage(msg);
            }
        });
        // Start QuietUnlockService
        mServiceManager.start();
        // Spin up GUI
        showDialog();
    }

    // Acitivity is destroyed, but Service lives on.
    @Override
    public void onDestroy() {
        super.onDestroy();
        mServiceManager.unbind();
    }

    // When App loses focus unintentionally (E.G. Home Key press),
    // Assume operation is cancelled.
    @Override
    public void onPause() {
        super.onPause();
        doCancel();
    }

    protected void doUpdateSilentVibe(int which) {
        switch(which) {
            case SELECT_SILENT:
                Log.d(TAG,"Sending Silent mode");
                sendMessageToService(QuietUnlockService.MSG_SET_RING, QuietUnlockService.RING_SILENT, 0);
                break;
            case SELECT_VIBRATE:
                Log.d(TAG,"Sending Vibrate mode");
                sendMessageToService(QuietUnlockService.MSG_SET_RING, QuietUnlockService.RING_VIBRATE, 0);
                break;
            default:
                Log.e(TAG,"Unknown Selection");
                doCancel();
                break;
        }
    }

    // Hook function called on "OK" button choice
    public void doOK() {
        Log.d(TAG,"Sending Lock");
        sendMessageToService(QuietUnlockService.MSG_LOCK, 0, 0);
        this.onDestroy();
        this.finish();
    }

    // Hook function called on "Cancel" button choice
    // Also called on other, external events
    public void doCancel() {
        Log.d(TAG, "Sending Cancel");
        sendMessageToService(QuietUnlockService.MSG_CANCEL, 0, 0);
        this.onDestroy();
        this.finish();
    }

    private void sendMessageToService(int command, int arg1, int arg2) {
        try {
            if(mServiceManager.isRunning()) {
                mServiceManager.send(Message.obtain(null, command, arg1, arg2, this));
            } else {
                Log.d(TAG, "Service not running, skippig send");
            }
        } catch (RemoteException e) {
            Log.e(TAG,"Error Sending Message to Service");
        }
    }

    // Main body of the UI.
    private void showDialog() {
        CharSequence[] items = {"",""};
        items[SELECT_SILENT] = this.getString(R.string.select_silent);
        items[SELECT_VIBRATE] = this.getString(R.string.select_vibrate);
        int checkedItem = QuietUnlockService.mIsSilent ? SELECT_SILENT : SELECT_VIBRATE;
        AlertDialog.Builder alertDialogBuilder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            alertDialogBuilder = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            alertDialogBuilder = new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK);
        } else {
            alertDialogBuilder = new AlertDialog.Builder(this);
        }
        AlertDialog alertDialog = alertDialogBuilder
            // Dialog Title "Quiet Unlock"; set in the res/xml
            .setTitle(R.string.app_name)
            // "Silent" / "Vibrate" Radio Buttons
            .setSingleChoiceItems(items, checkedItem,
                    new DialogInterface.OnClickListener() {
                        @Override
                public void onClick(DialogInterface dialog, int which) {
                    QuietUnlock.this.doUpdateSilentVibe(which);
                }})
            // "OK" Button
            .setPositiveButton(R.string.alert_dialog_ok,
                    new DialogInterface.OnClickListener() {
                        @Override
                public void onClick(DialogInterface dialog, int whichButton) {
                    QuietUnlock.this.doOK();
                }})
            // "Cancel" Button
            .setNegativeButton(R.string.alert_dialog_cancel,
                    new DialogInterface.OnClickListener() {
                        @Override
                public void onClick(DialogInterface dialog, int whichButton) {
                    QuietUnlock.this.doCancel();
                }})
            // Home/Back/(Any) Key Listener/Handler
            .setOnKeyListener(
                    new DialogInterface.OnKeyListener() {
                        @Override
                public boolean onKey(DialogInterface dialog,  int keyCode, KeyEvent event) {
                    if (KeyEvent.KEYCODE_MENU != keyCode) {
                        QuietUnlock.this.doCancel();
                    }
                    return true;
                }})
            .setIcon(R.drawable.noog)
            // Clicks/Touches outside the Dialog are non-reactive.
            // User must instead use the dialog buttons or Home or
            // Back keys
            .setCancelable(false)
            .show();

            // Fix candidate for where dialog doesn't show on some Phones.
            // from http://stackoverflow.com/questions/2306503/how-to-make-an-alert-dialog-fill-90-of-screen-size/6631310#6631310
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.copyFrom(alertDialog.getWindow().getAttributes());
            lp.width = WindowManager.LayoutParams.FILL_PARENT;
            lp.height = WindowManager.LayoutParams.FILL_PARENT;
            alertDialog.getWindow().setAttributes(lp);
    }
}
