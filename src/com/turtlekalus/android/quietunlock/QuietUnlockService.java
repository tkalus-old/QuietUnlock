/*
 * This is the background serivce class to QuiteUnlock
 *
 * This handles:
 *   Acitivating Vibrate/Silent based on user choice
 *     in Activity Class (check box in dialog window)
 *   Listen for OK/Cancel messages from Activity and
 *     behave appropriately
 *   Listen for Device Unlock (USER_PRESENT) action
 *     - User Unlocked Device
 *     * Restore Ringer and Exit Service
 *   Listen for RINGER_MODE_CHANGED
 *     - User (or another entity) changed Ringer Mode while device
 *       was still locked (E.G. Volume Keys while viewing Lock Screen).
 *     * Exit Service w/out Restoring Ringer
 *
 * Author: Turtle Kalus (turtlekalus.com)
 *   Date: 2012-12-28
 *
 *   TODO:
 *     Only flip to Vibe/Silent _after_ OK is pressed.
 *     Remove requirement for DeviceAdmin. In its absense,
 *       we should listen/wait for Device Lock/Screen Off
 *       with a short timeout.
 */

package com.turtlekalus.android.quietunlock;

import android.app.Activity;
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
import android.util.Log;
import android.widget.Toast;
import com.philippheckel.service.AbstractService;

public class QuietUnlockService extends AbstractService {
    private static final String TAG = "QuietUnlockService";

    private static DevicePolicyManager devicePolicyManager;
    private static ComponentName adminComponent;

    public static final int REQUEST_CODE_ENABLE_ADMIN = 1;

    public static final int RING_NORMAL  = AudioManager.RINGER_MODE_NORMAL;
    public static final int RING_SILENT  = AudioManager.RINGER_MODE_SILENT;
    public static final int RING_VIBRATE = AudioManager.RINGER_MODE_VIBRATE;

    public static final int MSG_INIT     = 1;
    public static final int MSG_SET_RING = 2;
    public static final int MSG_LOCK     = 3;
    public static final int MSG_CANCEL   = 4;

	public static final boolean mStartSilent = false;

    private static Intent QuietUnlockServiceIntent;
	private BroadcastReceiver ringerReceiver, unlockReceiver;
	private IntentFilter ringerFilter, unlockFilter;

	private static boolean mServiceActive;
    private static int     mRestoreRingerMode;

    @Override 
    public void onStartService() {
        mServiceActive = false;

        adminComponent = new ComponentName(this, DarClass.class);
        devicePolicyManager = (DevicePolicyManager)getSystemService(Context.DEVICE_POLICY_SERVICE);
        mRestoreRingerMode = ((AudioManager)getSystemService(Context.AUDIO_SERVICE)).getRingerMode();

        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Handle User Unlocking Device
                if(Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                    Log.i(TAG, "ACTION_USER_PRESENT: Restore Ringer");
                    stopService(true);
                // Handle User possibly-changing Ringer Mode from Lock Screen
                // using Vol Keys
                } else if(AudioManager.RINGER_MODE_CHANGED_ACTION.equals(intent.getAction())) {
                    if(mServiceActive) {
                        Log.i(TAG, "RINGER_MODE_CHANGED_ACTION: Dropping Service");
                        stopService(false);
                    }
                }
            }
        };

        unlockFilter = new IntentFilter();
        // This Intent is Broadcast when a User Unlocks their device
        unlockFilter.addAction(Intent.ACTION_USER_PRESENT);
        // This Intent is Broadcast when the Ringer Mode changes
        // Want to catch case where user changes Ringer Mode from Lock Screen
        unlockFilter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        registerReceiver(unlockReceiver, unlockFilter);

        setRinger(mStartSilent ? RING_SILENT : RING_VIBRATE);
        Log.d(TAG, "Service Started.");
    }

    @Override
    public void onStopService() {
        mServiceActive = false;
        Context context = getBaseContext();
        context.unregisterReceiver(unlockReceiver);
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

    private void doLockScreen(Activity activity) {
        if (!devicePolicyManager.isAdminActive(adminComponent)) {
            Log.i(TAG, "Initializing Lock Perms");
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
            activity.startActivityForResult(intent, REQUEST_CODE_ENABLE_ADMIN);
            //Toast.makeText(getBaseContext(), "Initializing Lock Perms", Toast.LENGTH_SHORT).show();
            stopService(true);
        }else{
            Log.d(TAG, "Doing LockScreen");
            devicePolicyManager.lockNow();
            mServiceActive = true;
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
        ((AudioManager)getSystemService(Context.AUDIO_SERVICE)).setRingerMode(ringMode);
    }

    private void stopService(boolean restoreRingMode) {
        if(restoreRingMode) {
            Log.i(TAG, "Restoring Ringer");
            setRinger(mRestoreRingerMode);
        }
        Log.d(TAG, "Stopping Service");
        mServiceActive = false;
        stopSelf();
    }


}
