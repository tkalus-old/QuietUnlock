/*
 * This inheritence class for Android's DeviceAdminReciever is required
 * because the On{Enabled,Disable}() functions are required, but not
 * implemented in the parent class
 *
 * Author: Turtle Kalus (turtlekalus.com)
 *   Date: 2012-12-28
 */

package com.turtlekalus.android.quietunlock;

import android.app.admin.DeviceAdminReceiver;

public class DarClass extends DeviceAdminReceiver{
    void OnEnabled(){
    }
    void onDisable(){
    }
}
