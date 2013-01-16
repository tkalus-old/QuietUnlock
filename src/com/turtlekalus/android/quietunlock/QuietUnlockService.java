/*
 * This is the background serivce class to QuiteUnlock
 *
 * This handles:
 *   Acitivating Vibrate/Silent based on user choice
 *     in Activity Class (check box in dialog window)
 *   Listen for OK/Cancel messages from Activity and
 *     behave appropriately
 *   Listen for Screen Activity
 *     - If the Lock Screen is Disabled
 *     * Restore Ringer and Exit Service
 *   Listen for Device Unlock
 *     - If the User Unlocks Device
 *     * Restore Ringer and Exit Service
 *   Deal with Telephony creating unlock-false-positives
 *     - If the device has been unlocked for more than 10 seconds
 *     * Restore Ringer and Exit Service
 *   Listen for RINGER_MODE_CHANGED
 *     - User (or another entity) changed Ringer Mode while device
 *       was still locked (E.G. Volume Keys while viewing Lock Screen)
 *     * Exit Service w/out Restoring Ringer
 *
 * Author: Turtle Kalus (turtlekalus.com)
 *   Date: 2012-12-28
 *
 *   TODO:
 *     Only flip to Vibe/Silent _after_ OK is pressed.
 */

package com.turtlekalus.android.quietunlock;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.Service;
import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Toast;
import java.lang.Runnable;
import com.philippheckel.service.AbstractService;

public class QuietUnlockService extends AbstractService {
    public  static final int     MSG_INIT     = 1;
    public  static final int     MSG_SET_RING = 2;
    public  static final int     MSG_LOCK     = 3;
    public  static final int     MSG_CANCEL   = 4;
    public  static final int     RING_NORMAL  = AudioManager.RINGER_MODE_NORMAL;
    public  static final int     RING_SILENT  = AudioManager.RINGER_MODE_SILENT;
    public  static final int     RING_VIBRATE = AudioManager.RINGER_MODE_VIBRATE;
    public  static final int     REQUEST_CODE_ENABLE_ADMIN = 1;

    private static final String  TAG = "QuietUnlockService";
    private static final boolean START_SILENT = false;
    private static final int     TELEPHONE_DELAY = 10; // Seconds


    public  static boolean mIsSilent           = START_SILENT;
    private static int     mRestoreRingerMode  = START_SILENT ? RING_SILENT : RING_VIBRATE;
    private static boolean mServiceActive      = false;
    private static boolean mTelephoneWasActive = false;

    private static ComponentName mAdminComponent = null;
    private static DevicePolicyManager mDevicePolicyManager = null;

    private BroadcastReceiver mBroadcastReceiver;
    private IntentFilter mIntentFilter = new IntentFilter();
    private Handler mHandler = new Handler();
    private Runnable mRunRestoreRinger = new Runnable() {
        public void run() {
            handleRestoreRinger();
        }
    };

    @Override 
    public void onStartService() {
        mServiceActive = false;

        mAdminComponent = new ComponentName(this, DarClass.class);
        mDevicePolicyManager = (DevicePolicyManager)getSystemService(Context.DEVICE_POLICY_SERVICE);
        mRestoreRingerMode = ((AudioManager)getSystemService(Context.AUDIO_SERVICE)).getRingerMode();

        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if(Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    Log.i(TAG, "ACTION_SCREEN_OFF: Service Active");
                    // Screen Off Action activates service, always.
                    mServiceActive = true;
                    // Set "WasActive" to false and remove Runnable. We'll
                    // start things again when the screen comes back on.
                    mTelephoneWasActive = false;
                    mHandler.removeCallbacks(mRunRestoreRinger);
                    return;
                } else if(Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    if (mServiceActive) {
                        Log.d(TAG, "ACTION_SCREEN_ON");
                        handleRestoreRinger();
                    }
                    return;
                } else if(Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                    if (mServiceActive) {
                        Log.d(TAG, "ACTION_USER_PRESENT");
                        handleRestoreRinger();
                    }
                    return;
                } else if(AudioManager.RINGER_MODE_CHANGED_ACTION.equals(intent.getAction())) {
                    // Handle User possibly-changing Ringer Mode from Lock Screen
                    // using Vol Keys
                    if(mServiceActive) {
                        Log.i(TAG, "RINGER_MODE_CHANGED_ACTION: Dropping Service");
                        stopService(false);
                    }
                    return;
                }
            }
        };


        mIntentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        mIntentFilter.addAction(Intent.ACTION_SCREEN_ON);
        mIntentFilter.addAction(Intent.ACTION_USER_PRESENT); // Device Unlock
        // This Intent is Broadcast when the Ringer Mode changes
        // Want to catch case where user changes Ringer Mode from Lock Screen
        mIntentFilter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        registerReceiver(mBroadcastReceiver, mIntentFilter);

        setRinger(START_SILENT ? RING_SILENT : RING_VIBRATE);
        Log.d(TAG, "Service Started.");
    }

    @Override
    public void onStopService() {
        mServiceActive = false;
        Context context = getBaseContext();
        context.unregisterReceiver(mBroadcastReceiver);
        Log.d(TAG, "Service Stopped.");
    }

    @Override
    public void onReceiveMessage(Message msg) {
        switch(msg.what) {
            case MSG_SET_RING:
                setRinger(msg.arg1);
                break;
            case MSG_LOCK:
                doLockScreen((Activity)msg.obj);
                break;
            case MSG_CANCEL:
                stopService(true);
                break;
        }
    }

    private void handleRestoreRinger() {
        // If the service is no longer active, return
        if (!mServiceActive) return;

        // We do not want to restore ringer if Telephone is active.
        // Instead, we will spin up an Handler to check every n seconds
        // for telephone activity. Screen Off will remove Alarm.
        if (!isTelephoneActive()) {
            if(!mTelephoneWasActive) {
                if (!isKeyguardLocked(true)) {
                    // Only Stop Service and Unlock if Keyguard isn't active.
                    stopService(true);
                    return;
                } else {
                    Log.d(TAG, "Keyguard Locked; keep going");
                    return;
                }
            }
            // Telephone was active, but is no longer...
            mTelephoneWasActive = false;
        } else {
            // If Telephone was Active w/in last n seconds, requeue
            // one more time.
            mTelephoneWasActive = true;
        }
        Log.i(TAG, "Telephone Activity; checking in " + Integer.toString(TELEPHONE_DELAY)
                + " secs; (wasActive " + Boolean.toString(mTelephoneWasActive) + ")");
        // On have one Runner queue'd at a time
        mHandler.removeCallbacks(mRunRestoreRinger);
        mHandler.postDelayed(mRunRestoreRinger, (TELEPHONE_DELAY * 1000));
    }

    private boolean isKeyguardLocked(boolean includeSlide) {
        // isKeyguardSecure() excludes "Slide" Lock
        KeyguardManager kgm = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (includeSlide) {
            return (null != kgm) && kgm.isKeyguardLocked();
        } else {
            return (null != kgm) && kgm.isKeyguardLocked() && kgm.isKeyguardSecure();
        }
    }

    private boolean isTelephoneActive() {
        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        return (null != tm) && (TelephonyManager.CALL_STATE_IDLE != tm.getCallState());
    }

    private void doLockScreen(Activity activity) {
        if (null != mDevicePolicyManager) {
            if (mDevicePolicyManager.isAdminActive(mAdminComponent)) {
                Log.d(TAG, "Doing LockScreen");
                mDevicePolicyManager.lockNow();
            } else {
                /* TODO this should be run once, the first time
                 * to give the user an option of granting perms.
                 * Remembered in non-exposed prefs and an option
                 * to {en,dis}able from the Settings Menu
                Log.i(TAG, "Initializing Lock Perms");
                Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, mAdminComponent);
                activity.startActivityForResult(intent, REQUEST_CODE_ENABLE_ADMIN);
                stopService(true);
                */
                Toast.makeText(getBaseContext(), R.string.toast_active, Toast.LENGTH_SHORT).show();
            }
        } else {
            Log.e(TAG, "Problem accessing getting DevicePolicyManager");
        }
    }

    private void sendMessageToActivity(int command, int arg1, int arg2) {
        try {          
            send(Message.obtain(null, command, arg1, arg2, this));
        } catch (Exception e) {
            Log.e(TAG,"Error Sending Message to Activity");
        }
    }

    private void setRinger(int ringMode) {
        Log.d(TAG, "Setting Ringer");
        if(RING_SILENT == ringMode) {
            mIsSilent = true;
        } else {
            mIsSilent = false;
        }
        ((AudioManager)getSystemService(Context.AUDIO_SERVICE)).setRingerMode(ringMode);
    }

    private void stopService(boolean restoreRingMode) {
        if (restoreRingMode) {
            Log.i(TAG, "Restoring Ringer");
            setRinger(mRestoreRingerMode);
        }
        Log.d(TAG, "Stopping Service");
        mHandler.removeCallbacks(mRunRestoreRinger);
        mServiceActive = false;
        stopSelf();
    }
}
