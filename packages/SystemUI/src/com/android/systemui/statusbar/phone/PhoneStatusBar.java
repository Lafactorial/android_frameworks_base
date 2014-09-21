/*
 * Copyright (c) 2012-2013 The Linux Foundation. All rights reserved.
 * Not a Contribution.
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import static android.app.StatusBarManager.NAVIGATION_HINT_BACK_ALT;
import static android.app.StatusBarManager.WINDOW_STATE_HIDDEN;
import static android.app.StatusBarManager.WINDOW_STATE_SHOWING;
import static android.app.StatusBarManager.windowStateToString;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_OPAQUE;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_SEMI_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_TRANSLUCENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.annotation.ChaosLab;
import android.annotation.ChaosLab.Classification;
import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.res.Configuration;
import android.content.res.ThemeConfig;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Bundle;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.telephony.MSimTelephonyManager;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Log;
import android.util.Pair;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.HapticFeedbackConstants;
import android.view.IWindowManager;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewPropertyAnimator;
import android.view.ViewStub;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.ViewFlipper;


import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.BatteryMeterView.BatteryMeterMode;
import com.android.systemui.DemoMode;
import com.android.systemui.DockBatteryMeterView;
import com.android.systemui.EventLogTags;
import com.android.systemui.R;
import com.android.systemui.ReminderMessageView;
import com.android.systemui.BatteryMeterView;
import com.android.systemui.omni.StatusHeaderMachine;
import com.android.systemui.settings.BrightnessController;
import com.android.systemui.settings.ToggleSlider;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.GestureRecorder;
import com.android.systemui.statusbar.MSimSignalClusterView;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.NotificationData.Entry;
import com.android.systemui.statusbar.SignalClusterTextView;
import com.android.systemui.statusbar.SignalClusterView;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.policy.DateView;
import com.android.systemui.statusbar.policy.DockBatteryController;
import com.android.systemui.statusbar.policy.HeadsUpNotificationView;
import com.android.systemui.statusbar.policy.KeyButtonView;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.MSimNetworkController;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NotificationRowLayout;
import com.android.systemui.statusbar.policy.OnSizeChangedListener;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.StringBuilder;
import java.util.ArrayList;

import com.android.systemui.statusbar.notification.Hover;

public class PhoneStatusBar extends BaseStatusBar implements DemoMode,
        NetworkController.UpdateUIListener, BrightnessController.BrightnessStateChangeCallback {
    static final String TAG = "PhoneStatusBar";
    public static final boolean DEBUG = BaseStatusBar.DEBUG;
    public static final boolean SPEW = false;
    public static final boolean DUMPTRUCK = true; // extra dumpsys info
    public static final boolean DEBUG_GESTURES = false;

    public static final boolean DEBUG_WINDOW_STATE = false;

    public static final boolean SETTINGS_DRAG_SHORTCUT = true;

    // additional instrumentation for testing purposes; intended to be left on during development
    public static final boolean CHATTY = DEBUG;

    private static final String KEY_REMINDER_ACTION =
            "key_reminder_action";

    private static final String SCHEDULE_REMINDER_NOTIFY =
            "com.android.systemui.SCHEDULE_REMINDER_NOTIFY";

    public static final String ACTION_STATUSBAR_START
            = "com.android.internal.policy.statusbar.START";

    public static final String CUSTOM_LOCKSCREEN_STATE
            = "com.android.keyguard.custom.STATE";

    private static final int MSG_OPEN_NOTIFICATION_PANEL = 1000;
    private static final int MSG_CLOSE_PANELS = 1001;
    private static final int MSG_OPEN_SETTINGS_PANEL = 1002;
    private static final int MSG_OPEN_QS_PANEL = 1003;
    private static final int MSG_FLIP_TO_NOTIFICATION_PANEL = 1004;
    private static final int MSG_FLIP_TO_QS_PANEL = 1005;
    // 1020-1030 reserved for BaseStatusBar

    private static final boolean CLOSE_PANEL_WHEN_EMPTIED = true;

    // will likely move to a resource or other tunable param at some point
    private static final int INTRUDER_ALERT_DECAY_MS = 0; // disabled, was 10000;

    private static final int NOTIFICATION_PRIORITY_MULTIPLIER = 10; // see NotificationManagerService
    private static final int HIDE_ICONS_BELOW_SCORE = Notification.PRIORITY_LOW * NOTIFICATION_PRIORITY_MULTIPLIER;

    private static final int STATUS_OR_NAV_TRANSIENT =
            View.STATUS_BAR_TRANSIENT | View.NAVIGATION_BAR_TRANSIENT;
    private static final long AUTOHIDE_TIMEOUT_MS = 3000;

    private static final float BRIGHTNESS_CONTROL_PADDING = 0.15f;
    private static final int BRIGHTNESS_CONTROL_LONG_PRESS_TIMEOUT = 750; // ms
    private static final int BRIGHTNESS_CONTROL_LINGER_THRESHOLD = 20;

    private static final int CLOCK_STYLE_HIDDEN = 0;
    private static final int CLOCK_STYLE_DEFAULT = 1;
    private static final int CLOCK_STYLE_CENTERED = 2;

    // Extended SwipeHelper params
    public static final int GESTURE_POSITIVE = 0;
    public static final int GESTURE_NEGATIVE = 1;

    // fling gesture tuning parameters, scaled to display density
    private float mSelfExpandVelocityPx; // classic value: 2000px/s
    private float mSelfCollapseVelocityPx; // classic value: 2000px/s (will be negated to collapse "up")
    private float mFlingExpandMinVelocityPx; // classic value: 200px/s
    private float mFlingCollapseMinVelocityPx; // classic value: 200px/s
    private float mCollapseMinDisplayFraction; // classic value: 0.08 (25px/min(320px,480px) on G1)
    private float mExpandMinDisplayFraction; // classic value: 0.5 (drag open halfway to expand)
    private float mFlingGestureMaxXVelocityPx; // classic value: 150px/s

    private float mExpandAccelPx; // classic value: 2000px/s/s
    private float mCollapseAccelPx; // classic value: 2000px/s/s (will be negated to collapse "up")

    private float mFlingGestureMaxOutputVelocityPx; // how fast can it really go? (should be a little
                                                    // faster than mSelfCollapseVelocityPx)

    PhoneStatusBarPolicy mIconPolicy;

    // These are no longer handled by the policy, because we need custom strategies for them
    BluetoothController mBluetoothController;
    BatteryController mBatteryController;
    DockBatteryController mDockBatteryController;
    LocationController mLocationController;
    NetworkController mNetworkController;
    MSimNetworkController mMSimNetworkController;

    int mNaturalBarHeight = -1;
    int mIconSize = -1;
    int mIconHPadding = -1;
    int mCurrOrientation;
    Display mDisplay;
    Point mCurrentDisplaySize = new Point();
    int mCurrUiThemeMode;
    private float mHeadsUpVerticalOffset;
    private int[] mPilePosition = new int[2];

    StatusBarWindowView mStatusBarWindow;
    PhoneStatusBarView mStatusBarView;
    private int mStatusBarWindowState = WINDOW_STATE_SHOWING;

    int mPixelFormat;
    Object mQueueLock = new Object();

    // viewgroup containing the normal contents of the statusbar
    LinearLayout mStatusBarContents;

    // right-hand icons
    LinearLayout mSystemIconArea;

    // left-hand icons
    LinearLayout mStatusIcons;

    // the icons themselves
    IconMerger mNotificationIcons;
    // [+>
    View mMoreIcon;

    // expanded notifications
    NotificationPanelView mNotificationPanel; // the sliding/resizing panel within the notification window
    ScrollView mScrollView;
    View mExpandedContents;
    int mNotificationPanelGravity;
    int mNotificationPanelMarginBottomPx, mNotificationPanelMarginPx;
    float mNotificationPanelMinHeightFrac;
    boolean mNotificationPanelIsFullScreenWidth;
    TextView mNotificationPanelDebugText;
    
    // settings
    QuickSettingsController mQS;
    boolean mHasSettingsPanel, mHasFlipSettings;
    SettingsPanelView mSettingsPanel;
    View mFlipSettingsView;
    QuickSettingsContainerView mSettingsContainer;
    int mSettingsPanelGravity;
    private TilesChangedObserver mTilesChangedObserver;
    private SettingsObserver mSettingsObserver;

    // Ribbon settings
    private boolean mHasQuickAccessSettings;
    private boolean mQuickAccessLayoutLinked = true;
    private QuickSettingsHorizontalScrollView mRibbonView;
    private QuickSettingsController mRibbonQS;

    // Brightness slider
    private boolean mBrightnessSliderEnabled;
    private BrightnessController mBrightnessController;
    private View mBrightnessView;
    private ToggleSlider mSlider;
    private View mSetupButtonDivider;
    private ImageView mSetupButton;

    // top bar
    View mNotificationPanelHeader;
    View mDateTimeView;
    View mClearButton;
    ImageView mAddTileButton;
    ImageView mSettingsButton, mNotificationButton;

    // carrier/wifi label
    private TextView mCarrierLabel;
    private TextView mSubsLabel;
    private boolean mCarrierLabelVisible = false;
    private int mCarrierLabelHeight;
    private TextView mEmergencyCallLabel;
    private int mNotificationHeaderHeight;

    private boolean mShowCarrierInPanel = false;

    private SignalClusterView mSignalClusterView;
    private MSimSignalClusterView mMSimSignalClusterView;
    private SignalClusterTextView mSignalTextView;
    private BatteryMeterView mBatteryView;
    private DockBatteryMeterView mDockBatteryView;

    // clock
    private boolean mShowClock = true;
    private int mClockStyle;
    private Clock mClockView;

    // position
    int[] mPositionTmp = new int[2];
    boolean mExpandedVisible;
    private boolean mNotificationPanelIsOpen = false;
    private boolean mQSPanelIsOpen = false;

    // the date view
    DateView mDateView;

    // for heads up notifications
    private HeadsUpNotificationView mHeadsUpNotificationView;
    private int mHeadsUpNotificationDecay;
    private boolean mHeadsUpExpandedByDefault;
    private boolean mHeadsUpNotificationViewAttached;
    private boolean mHeadsUpGravityBottom;
    private boolean mStatusBarShows = true;
    private boolean mImeIsShowing;

    // on-screen navigation buttons
    private NavigationBarView mNavigationBarView = null;
    private int mNavigationBarWindowState = WINDOW_STATE_SHOWING;

    // the tracker view
    int mTrackingPosition; // the position of the top of the tracking view.

    // ticker
    private View mTickerView;
    private boolean mTicking;

    // Tracking finger for opening/closing.
    int mEdgeBorder; // corresponds to R.dimen.status_bar_edge_ignore
    boolean mTracking;
    VelocityTracker mVelocityTracker;

    int[] mAbsPos = new int[2];

    // Notification reminder
    private View mReminderHeader;
    private ImageView mSpacer;
    private boolean mReminderEnabled;
    private int mFlipInterval;
    private int mReminderTitleLandscapeWidth;
    private int mReminderTitlePortraitWidth;
    private int mReminderLandscapeWidth;
    private int mReminderPortraitWidth;
    private ViewFlipper mFlipper;
    private ViewFlipper mFlipperLand;
    private TextView mTextHolder;
    private TextView mReminderTitle;
    private SharedPreferences mShared;

    // last theme that was applied in order to detect theme change (as opposed
    // to some other configuration change).
    ThemeConfig mCurrentTheme;
    private boolean mRecreating = false;

    // Time-Context headers
    private StatusHeaderMachine mStatusHeaderMachine;
    private Runnable mStatusHeaderUpdater;

    private boolean mBrightnessControl;
    private boolean mAnimatingFlip = false;
    private float mScreenWidth;
    private int mMinBrightness;
    private int mPeekHeight;
    private boolean mJustPeeked;
    int mLinger;
    int mInitialTouchX;
    int mInitialTouchY;

    // for disabling the status bar
    int mDisabled = 0;

    // tracking calls to View.setSystemUiVisibility()
    int mSystemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE;

    DisplayMetrics mDisplayMetrics = new DisplayMetrics();

    boolean mTransparentNav = false;

    // XXX: gesture research
    private final GestureRecorder mGestureRec = DEBUG_GESTURES
        ? new GestureRecorder("/sdcard/statusbar_gestures.dat")
        : null;

    private int mNavigationIconHints = 0;
    private final Animator.AnimatorListener mMakeIconsInvisible = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            // double-check to avoid races
            if (mStatusBarContents.getAlpha() == 0) {
                if (DEBUG) Log.d(TAG, "makeIconsInvisible");
                mStatusBarContents.setVisibility(View.INVISIBLE);
            }
        }
    };

    private final Runnable mLongPressBrightnessChange = new Runnable() {
        @Override
        public void run() {
            mStatusBarView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            adjustBrightness(mInitialTouchX);
            mLinger = BRIGHTNESS_CONTROL_LINGER_THRESHOLD + 1;
        }
    };

    private final Runnable mNotifyClearAll = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) {
                Log.v(TAG, "Notifying status bar of notification clear");
            }
            try {
                mPile.setViewRemoval(true);
                mBarService.onClearAllNotifications();
            } catch (RemoteException ex) { }
        }
    };

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_BRIGHTNESS_CONTROL), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SCREEN_BRIGHTNESS_MODE), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_BATTERY), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_BATTERY_SHOW_PERCENT), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_CLOCK), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_SIGNAL_TEXT), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NAVBAR_LEFT_IN_LANDSCAPE), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_CUSTOM_HEADER), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NOTIFICATION_BACKGROUND), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NOTIFICATION_BACKGROUND_LANDSCAPE), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NOTIFICATION_BACKGROUND_ALPHA), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NOTIFICATION_ALPHA), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.REMINDER_ALERT_ENABLED), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.REMINDER_ALERT_INTERVAL), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NAVBAR_RECENT_LONG_PRESS), false, this,
                    UserHandle.USER_ALL);
            // Pie controls
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PIE_CONTROLS), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.EXPANDED_DESKTOP_STATE), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NAVIGATION_BAR_SHOW), false, this,
                    UserHandle.USER_ALL);
            // Heads up
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.HEADS_UP_EXPANDED), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.HEADS_UP_SNOOZE_TIME), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.HEADS_UP_NOTIFCATION_DECAY), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.HEADS_UP_SHOW_UPDATE), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.HEADS_UP_GRAVITY_BOTTOM), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.RECENT_CARD_BG_COLOR), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.RECENT_CARD_TEXT_COLOR), false, this,
                    UserHandle.USER_ALL);

            updateSettings();
            updateClockLocation();
            update();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);

            if (uri.equals(Settings.System.getUriFor(Settings.System.STATUS_BAR_CLOCK))) {
                updateClockLocation();
            } else if (uri.equals(Settings.System.getUriFor(
                    Settings.System.NOTIFICATION_BACKGROUND))
                || uri.equals(Settings.System.getUriFor(
                    Settings.System.NOTIFICATION_BACKGROUND_LANDSCAPE))
                || uri.equals(Settings.System.getUriFor(
                    Settings.System.NOTIFICATION_BACKGROUND_ALPHA))) {
                if (mNotificationPanel != null) {
                    mNotificationPanel.setBackgroundDrawables();
                }
                if (mSettingsPanel != null) {
                    mSettingsPanel.setBackgroundDrawables();
                }
            } else if (uri.equals(Settings.System.getUriFor(
                Settings.System.NOTIFICATION_ALPHA))) {
                setNotificationAlpha();
            // Pie controls
            } else if (uri.equals(Settings.System.getUriFor(
                    Settings.System.PIE_CONTROLS))) {
                attachPieContainer(isPieEnabled());
            } else if (uri.equals(Settings.System.getUriFor(
                    Settings.System.EXPANDED_DESKTOP_STATE))) {
                mNavigationBarOverlay.setIsExpanded(isExpanded());
            } else if (uri.equals(Settings.System.getUriFor(
                    Settings.System.NAVIGATION_BAR_SHOW))) {
                mNavigationBarOverlay.setIsExpanded(noNavBar());
            } else if (uri.equals(Settings.System.getUriFor(
                    Settings.System.HEADS_UP_EXPANDED))) {
                    mHeadsUpExpandedByDefault = Settings.System.getIntForUser(
                            mContext.getContentResolver(),
                            Settings.System.HEADS_UP_EXPANDED, 0,
                            UserHandle.USER_CURRENT) == 1;
            } else if (uri.equals(Settings.System.getUriFor(
                    Settings.System.HEADS_UP_SNOOZE_TIME))) {
                    final int snoozeTime = Settings.System.getIntForUser(
                            mContext.getContentResolver(),
                            Settings.System.HEADS_UP_SNOOZE_TIME,
                            DEFAULT_TIME_HEADS_UP_SNOOZE,
                            UserHandle.USER_CURRENT);
                    setHeadsUpSnoozeTime(snoozeTime);
                    if (mHeadsUpNotificationView != null) {
                        mHeadsUpNotificationView.setSnoozeVisibility(snoozeTime != 0);
                    }
            } else if (uri.equals(Settings.System.getUriFor(
                    Settings.System.HEADS_UP_NOTIFCATION_DECAY))) {
                    mHeadsUpNotificationDecay = Settings.System.getIntForUser(
                            mContext.getContentResolver(),
                            Settings.System.HEADS_UP_NOTIFCATION_DECAY,
                            mContext.getResources().getInteger(
                            R.integer.heads_up_notification_decay),
                            UserHandle.USER_CURRENT);
            } else if (uri.equals(Settings.System.getUriFor(
                    Settings.System.HEADS_UP_SHOW_UPDATE))) {
                    mShowHeadsUpUpdates = Settings.System.getIntForUser(
                            mContext.getContentResolver(),
                            Settings.System.HEADS_UP_SHOW_UPDATE, 0,
                            UserHandle.USER_CURRENT) == 1;
            } else if (uri.equals(Settings.System.getUriFor(
                    Settings.System.HEADS_UP_GRAVITY_BOTTOM))) {
                    mHeadsUpGravityBottom = Settings.System.getIntForUser(
                            mContext.getContentResolver(),
                            Settings.System.HEADS_UP_GRAVITY_BOTTOM, 0,
                            UserHandle.USER_CURRENT) == 1;
                    updateHeadsUpPosition(mStatusBarShows);
            } else if (uri.equals(Settings.System.getUriFor(
                    Settings.System.RECENT_CARD_BG_COLOR))) {
                rebuildRecentsScreen();
            } else if (uri.equals(Settings.System.getUriFor(
                    Settings.System.RECENT_CARD_TEXT_COLOR))) {
                rebuildRecentsScreen();
            }

            updateSettings();
            update();
        }

        public void update() {
            ContentResolver resolver = mContext.getContentResolver();
            
            mFlipInterval = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.REMINDER_ALERT_INTERVAL, 1500, UserHandle.USER_CURRENT);

            boolean reminderHolder = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.REMINDER_ALERT_ENABLED, 0, UserHandle.USER_CURRENT) != 0;
            if (reminderHolder != mReminderEnabled) {
                mReminderEnabled = reminderHolder;
                if (mReminderEnabled) {
                    if (mShared.getString("title", null) == null) {
                        mShared.edit().putString("title",
                                mContext.getResources().getString(
                                R.string.quick_settings_reminder_help_title)).commit();
                        mShared.edit().putString("message",
                                mContext.getResources().getString(
                                R.string.quick_settings_reminder_help_message)).commit();
                    }
                }
                enableOrDisableReminder();
            }
        }
    }

    // Pie controls
    private boolean isPieEnabled() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.PIE_CONTROLS, 0,
                UserHandle.USER_CURRENT) == 1;
    }

    private boolean isExpanded() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.EXPANDED_DESKTOP_STATE, 0,
                UserHandle.USER_CURRENT) == 1;
    }

    private boolean noNavBar() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.NAVIGATION_BAR_SHOW, 0,
                UserHandle.USER_CURRENT) == 0;
    }

    public void updateReminder() {
        String title = mShared.getString("title", null);
        String message = mShared.getString("message", null);
        if (title != null && message != null
                && (!title.isEmpty() || !message.isEmpty())) {
            final int dpi = mContext.getResources().getDisplayMetrics().densityDpi;
            mReminderTitle.setText(title);
            mFlipper.removeAllViews();
            ArrayList<String> splitStringPortrait =
                    splitString(message, mReminderPortraitWidth);
            for (String split : splitStringPortrait) {
                ReminderMessageView entry =
                        new ReminderMessageView(mContext, split);
                mFlipper.addView(entry);
            }

            mFlipperLand.removeAllViews();
            ArrayList<String> splitStringLandscape = mNotificationPanelIsFullScreenWidth ?
                    splitString(message, mReminderLandscapeWidth) : splitStringPortrait;
            for (String splitLand : splitStringLandscape) {
                ReminderMessageView entryLand =
                        new ReminderMessageView(mContext, splitLand);
                mFlipperLand.addView(entryLand);
            }

            toggleReminderFlipper(mExpandedVisible);
        } else {
            clearReminder();
        }
    }

    private void enableOrDisableReminder() {
        if (mReminderEnabled) {
            mSpacer.setEnabled(true);
            mReminderHeader.setVisibility(View.VISIBLE);
            mReminderHeader.setEnabled(true);
            toggleVisibleFlipper();
            updateReminder();
        } else {
            mSpacer.setEnabled(false);
            clearReminder();
        }
    }

    private void clearReminder() {
        mReminderHeader.setVisibility(View.GONE);
        mReminderHeader.setEnabled(false);
        mReminderTitle.setText(null);
        mFlipper.removeAllViews();
        mFlipperLand.removeAllViews();
    }

    private void setTakenSpace() {
        if (mNotificationPanelIsFullScreenWidth) {
            if (mCurrentDisplaySize.x > mCurrentDisplaySize.y) {
                mReminderTitlePortraitWidth = (mCurrentDisplaySize.y / 4);
                mReminderTitleLandscapeWidth = (mCurrentDisplaySize.x / 4);
                mReminderPortraitWidth = mReminderTitlePortraitWidth * 2;
                mReminderLandscapeWidth = mReminderTitleLandscapeWidth * 2;
            } else {
                mReminderTitlePortraitWidth = (mCurrentDisplaySize.x / 4);
                mReminderTitleLandscapeWidth = (mCurrentDisplaySize.y / 4);
                mReminderPortraitWidth = mReminderTitlePortraitWidth * 2;
                mReminderLandscapeWidth = mReminderTitleLandscapeWidth * 2;
            }
        } else {
            mReminderTitlePortraitWidth = (mNotificationPanel.getLayoutParams().width / 4);
            mReminderTitleLandscapeWidth = mReminderTitlePortraitWidth;
            mReminderLandscapeWidth = mReminderTitleLandscapeWidth * 2;
            mReminderPortraitWidth = mReminderLandscapeWidth;
        }
        enableOrDisableReminder();
    }

    private ArrayList<String>splitString(String message, int maxWidth) {
        ArrayList<String> split = new ArrayList<String>();
        String[] parts = message.split("\\s+");
        String combined = "";
        for (int i = 0; i < parts.length; i++) {
            if (combined.equals("") ? acceptableLength(parts[i], maxWidth)
                    : acceptableLength(parts[i] + combined + " ", maxWidth)) {
                if (combined.equals("")) {
                    combined = parts[i];
                } else {
                    combined += " " + parts[i];
                }
            } else {
                if (!combined.equals("")) {
                    split.add(combined);
                    combined = "";
                }
                if (!acceptableLength(parts[i], maxWidth)) {
                    StringBuilder builder = new StringBuilder();
                    int index = 0;
                    for (int j = 0; j < parts[i].length(); j++) {
                        if (!acceptableLength(parts[i].substring(index, j) + "...", maxWidth)) {
                            split.add(builder.toString() + "...");
                            builder.setLength(0);
                            index = j;
                            --j;
                        } else {
                            builder.append(parts[i].charAt(j));
                        }
                    }
                    if (builder.length() > 0) {
                        combined = builder.toString();
                    }
                } else {
                    // Just re-reun this, we've added all previous strings up already
                    --i;
                }
            }
        }
        if (!combined.equals("")) {
            split.add(combined);
        }
        // Just a blank roll at the end to quickly see the message is over
        split.add("");
        return split;
    }

    private boolean acceptableLength(String input, int maxWidth) {
        mTextHolder.setText(input);
        mTextHolder.measure(0, 0);
        return mTextHolder.getMeasuredWidth() < maxWidth;
    }

    public void toggleReminderFlipper(boolean active) {
        if (mReminderEnabled) {
            if (mCurrOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                if (mFlipperLand != null && mFlipper != null) {
                    mReminderTitle.setWidth(mReminderTitleLandscapeWidth);
                    mFlipper.stopFlipping();
                    if (mFlipperLand.getChildCount() <= 2 || !active) {
                        mFlipperLand.setDisplayedChild(0);
                        mFlipperLand.stopFlipping();
                    } else {
                        mFlipperLand.setFlipInterval(mFlipInterval);
                        mFlipperLand.startFlipping();
                    }
                }
            } else {
                if (mFlipper != null && mFlipperLand != null) {
                    mReminderTitle.setWidth(mReminderTitlePortraitWidth);
                    mFlipperLand.stopFlipping();
                    if (mFlipper.getChildCount() <= 2 || !active) {
                        mFlipper.setDisplayedChild(0);
                        mFlipper.stopFlipping();
                    } else {
                        mFlipper.setFlipInterval(mFlipInterval);
                        mFlipper.startFlipping();
                    }
                }
            }
        }
    }

    private void toggleVisibleFlipper() {
        if (mCurrOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            mFlipper.setVisibility(View.GONE);
            mFlipperLand.setVisibility(View.VISIBLE);
        } else {
            mFlipper.setVisibility(View.VISIBLE);
            mFlipperLand.setVisibility(View.GONE);
        }
    }

    // ensure quick settings is disabled until the current user makes it through the setup wizard
    private boolean mUserSetup = false;
    private ContentObserver mUserSetupObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            final boolean userSetup = 0 != Settings.Secure.getIntForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.USER_SETUP_COMPLETE,
                    0 /*default */,
                    mCurrentUserId);
            if (MULTIUSER_DEBUG) Log.d(TAG, String.format("User setup changed: " +
                    "selfChange=%s userSetup=%s mUserSetup=%s",
                    selfChange, userSetup, mUserSetup));
            if (mSettingsButton != null && mHasFlipSettings) {
                mSettingsButton.setVisibility(userSetup ? View.VISIBLE : View.INVISIBLE);
            }
            if (mHoverButton != null && mHasFlipSettings) {
                mHoverButton.setVisibility(userSetup ? View.VISIBLE : View.INVISIBLE);
            }
            if (mSettingsPanel != null) {
                mSettingsPanel.setEnabled(userSetup);
            }
            if (userSetup != mUserSetup) {
                mUserSetup = userSetup;
                if (!mUserSetup && mStatusBarView != null)
                    animateCollapseQuickSettings();
            }
        }
    };

    private int mInteractingWindows;
    private boolean mAutohideSuspended;
    private int mStatusBarMode;
    private int mNavigationBarMode;
    private Boolean mScreenOn;

    private final Runnable mAutohide = new Runnable() {
        @Override
        public void run() {
            doAutoHide();
        }};

    private final Runnable mUserAutohide = new Runnable() {
        @Override
        public void run() {
            doAutoHide();
        }};

    private void doAutoHide() {
        int requested = mSystemUiVisibility & ~STATUS_OR_NAV_TRANSIENT;
        if (mSystemUiVisibility != requested) {
            notifyUiVisibilityChanged(requested);
        }
    }

    @Override
    public void start() {
        mDisplay = ((WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay();
        updateDisplaySize();

        ThemeConfig currentTheme = mContext.getResources().getConfiguration().themeConfig;
        if (currentTheme != null) {
            mCurrentTheme = (ThemeConfig)currentTheme.clone();
        }

        mLocationController = new LocationController(mContext);
        mBatteryController = new BatteryController(mContext);
        mDockBatteryController = new DockBatteryController(mContext);
        mBluetoothController = new BluetoothController(mContext);

        mCurrUiThemeMode = mContext.getResources().getConfiguration().uiThemeMode;

        super.start(); // calls createAndAddWindows()

        addNavigationBar();

        SettingsObserver observer = new SettingsObserver(mHandler);
        observer.observe();

        // Lastly, call to the icon policy to install/update all the icons.
        mIconPolicy = new PhoneStatusBarPolicy(mContext);

        // Add heads up view.
        addHeadsUpView();
    }

    private void cleanupBrightnessSlider() {
        if (mBrightnessView == null) {
            return;
        }
        mBrightnessView.setVisibility(View.GONE);
        mBrightnessController.unregisterCallbacks();
        mBrightnessController = null;
    }

    private void inflateBrightnessSlider() {
        if (mBrightnessView == null) {
            ViewStub br_stub = (ViewStub)
                    mStatusBarWindow.findViewById(R.id.notification_brightness_slider);
            if (br_stub != null) {
                mBrightnessView = br_stub.inflate();
                mBrightnessView.setVisibility(View.VISIBLE);
                mSlider = (ToggleSlider) mStatusBarWindow.findViewById(R.id.brightness_slider);
                mSetupButtonDivider = mStatusBarWindow.findViewById(R.id.brightness_setup_button_divider);
                mSetupButton = (ImageView) mStatusBarWindow.findViewById(R.id.brightness_setup_button);
                mSetupButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        Intent intent = new Intent();
                        intent.setClassName("com.android.settings",
                                "com.android.settings.cyanogenmod.AutoBrightnessSetup");
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                        mContext.startActivity(intent);
                        mStatusBarView.mNotificationPanel.collapse();
                    }
                });
            }
        }

        if (mBrightnessController == null) {
            mBrightnessController = new BrightnessController(
                    mContext, (ImageView) mStatusBarWindow.findViewById(R.id.brightness_icon), mSlider);
            mBrightnessController.addStateChangedCallback(this);
            updateSetupButtonVisibility();
        }
    }

    public void onBrightnessLevelChanged() {
        updateSetupButtonVisibility();
    }

    private void updateSetupButtonVisibility() {
        boolean isAuto = mSlider.isChecked();
        mSetupButtonDivider.setVisibility(isAuto ? View.VISIBLE : View.GONE);
        mSetupButton.setVisibility(isAuto ? View.VISIBLE : View.GONE);
    }

    private void cleanupRibbon() {
        if (mRibbonView != null)
            mRibbonView.setVisibility(View.GONE);
        if (mRibbonQS != null) {
            mRibbonQS.shutdown();
            mRibbonQS = null;
        }
    }

    private void inflateRibbon() {
        if (mRibbonView == null) {
            ViewStub ribbon_stub = (ViewStub) mStatusBarWindow.findViewById(R.id.ribbon_settings_stub);
            if (ribbon_stub != null) {
                mRibbonView = (QuickSettingsHorizontalScrollView) ribbon_stub.inflate();
                mRibbonView.setVisibility(View.VISIBLE);
            }
        }
        if (mRibbonQS == null) {
            QuickSettingsContainerView mRibbonContainer = (QuickSettingsContainerView)
                    mStatusBarWindow.findViewById(R.id.quick_settings_ribbon_container);
            if (mRibbonContainer != null) {
                String settingsKey = mQuickAccessLayoutLinked
                        ? Settings.System.QUICK_SETTINGS_TILES
                        : Settings.System.QUICK_SETTINGS_RIBBON_TILES;
                mRibbonQS = new QuickSettingsController(mContext, mRibbonContainer, this,
                        settingsKey, true);
                mRibbonQS.setService(this);
                mRibbonQS.setBar(mStatusBarView);
                mRibbonQS.setupQuickSettings();
            }
        }
    }

    // ================================================================================
    // Constructing the view
    // ================================================================================
    @ChaosLab(name="GestureAnywhere", classification=Classification.CHANGE_CODE)
    protected PhoneStatusBarView makeStatusBarView() {
        final Context context = mContext;

        Resources res = context.getResources();

        mScreenWidth = (float) context.getResources().getDisplayMetrics().widthPixels;
        mMinBrightness = context.getResources().getInteger(
                com.android.internal.R.integer.config_screenBrightnessDim);

        mCurrOrientation = res.getConfiguration().orientation;

        updateDisplaySize(); // populates mDisplayMetrics
        loadDimens();

        mIconSize = res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_icon_size);

        if (MSimTelephonyManager.getDefault().isMultiSimEnabled()) {
            mStatusBarWindow = (StatusBarWindowView) View.inflate(context,
                    R.layout.msim_super_status_bar, null);
        } else {
            mStatusBarWindow = (StatusBarWindowView) View.inflate(context,
                    R.layout.super_status_bar, null);
        }
        mStatusBarWindow.mService = this;
        mStatusBarWindow.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                checkUserAutohide(v, event);
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    if (mExpandedVisible) {
                        animateCollapsePanels();
                    } else {
                        // ensure to dismiss hover if is about to show while we touch to expand
                        mHover.dismissHover(true, true);
                    }
                }
                return mStatusBarWindow.onTouchEvent(event);
            }});

        if (MSimTelephonyManager.getDefault().isMultiSimEnabled()) {
            mStatusBarView = (PhoneStatusBarView) mStatusBarWindow.findViewById(
                    R.id.msim_status_bar);
        } else {
            mStatusBarView = (PhoneStatusBarView) mStatusBarWindow.findViewById(R.id.status_bar);
        }
        mStatusBarView.setBar(this);

        PanelHolder holder;
        if (MSimTelephonyManager.getDefault().isMultiSimEnabled()) {
            holder = (PanelHolder) mStatusBarWindow.findViewById(R.id.msim_panel_holder);
        } else {
            holder = (PanelHolder) mStatusBarWindow.findViewById(R.id.panel_holder);
        }
        mStatusBarView.setPanelHolder(holder);

        mNotificationPanel = (NotificationPanelView) mStatusBarWindow.findViewById(R.id.notification_panel);
        mNotificationPanel.setStatusBar(this);
        mNotificationPanelIsFullScreenWidth =
            (mNotificationPanel.getLayoutParams().width == ViewGroup.LayoutParams.MATCH_PARENT);

        // make the header non-responsive to clicks
        mNotificationPanel.findViewById(R.id.header).setOnTouchListener(
                new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        return true; // e eats everything
                    }
                });

        if (!ActivityManager.isHighEndGfx()) {
            mStatusBarWindow.setBackground(null);
            mNotificationPanel.setBackground(new FastColorDrawable(context.getResources().getColor(
                    R.color.notification_panel_solid_background)));
        }
        if (mHeadsUpNotificationView == null) {
            mHeadsUpNotificationView =
                    (HeadsUpNotificationView) View.inflate(context, R.layout.heads_up, null);
            mHeadsUpNotificationView.setNotificationHelper(mNotificationHelper);
        }

        mHeadsUpNotificationView.setVisibility(View.GONE);
        mHeadsUpNotificationView.setBar(this);
        mHeadsUpNotificationDecay = Settings.System.getIntForUser(
                mContext.getContentResolver(),
                Settings.System.HEADS_UP_NOTIFCATION_DECAY,
                res.getInteger(R.integer.heads_up_notification_decay),
                UserHandle.USER_CURRENT);
        mHeadsUpExpandedByDefault = Settings.System.getIntForUser(
                mContext.getContentResolver(),
                Settings.System.HEADS_UP_EXPANDED, 0,
                UserHandle.USER_CURRENT) == 1;
        final int snoozeTime = Settings.System.getIntForUser(
                mContext.getContentResolver(),
                Settings.System.HEADS_UP_SNOOZE_TIME,
                DEFAULT_TIME_HEADS_UP_SNOOZE,
                UserHandle.USER_CURRENT);
        setHeadsUpSnoozeTime(snoozeTime);
        mHeadsUpNotificationView.setSnoozeVisibility(snoozeTime != 0);
        mShowHeadsUpUpdates = Settings.System.getIntForUser(
                mContext.getContentResolver(),
                Settings.System.HEADS_UP_SHOW_UPDATE, 0,
                UserHandle.USER_CURRENT) == 1;
        mHeadsUpGravityBottom = Settings.System.getIntForUser(
                mContext.getContentResolver(),
                Settings.System.HEADS_UP_GRAVITY_BOTTOM, 0,
                UserHandle.USER_CURRENT) == 1;

        if (MULTIUSER_DEBUG) {
            mNotificationPanelDebugText = (TextView) mNotificationPanel.findViewById(R.id.header_debug_info);
            mNotificationPanelDebugText.setVisibility(View.VISIBLE);
        }

        updateShowSearchHoldoff();

        if (mNavigationBarView == null) {
            mNavigationBarView =
                (NavigationBarView) View.inflate(context, R.layout.navigation_bar, null);
            mNavigationBarView.updateResources(getNavbarThemedResources());
        }

        mNavigationBarView.setDisabledFlags(mDisabled);
        mNavigationBarView.setBar(this);
        addNavigationBarCallback(mNavigationBarView);
        mNavigationBarView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                checkUserAutohide(v, event);
                return false;
            }
        });

        if (mRecreating) {
            removeSidebarView();
        } else {
            /* ChaosLab: GestureAnywhere - BEGIN */
            addGestureAnywhereView();
            /* ChaosLab: GestureAnywhere - END */
        }
        addSidebarView();

        // Setup pie container if enabled
        attachPieContainer(isPieEnabled());

        // figure out which pixel-format to use for the status bar.
        mPixelFormat = PixelFormat.OPAQUE;

        mSystemIconArea = (LinearLayout) mStatusBarView.findViewById(R.id.system_icon_area);
        mStatusIcons = (LinearLayout)mStatusBarView.findViewById(R.id.statusIcons);
        mNotificationIcons = (IconMerger)mStatusBarView.findViewById(R.id.notificationIcons);
        mMoreIcon = mStatusBarView.findViewById(R.id.moreIcon);
        mNotificationIcons.setOverflowIndicator(mMoreIcon);
        mStatusBarContents = (LinearLayout)mStatusBarView.findViewById(R.id.status_bar_contents);
        mTickerView = mStatusBarView.findViewById(R.id.ticker);

        mPile = (NotificationRowLayout)mStatusBarWindow.findViewById(R.id.latestItems);
        mPile.setLayoutTransitionsEnabled(false);
        mPile.setLongPressListener(getNotificationLongClicker());
        mExpandedContents = mPile; // was: expanded.findViewById(R.id.notificationLinearLayout);

        mNotificationPanelHeader = mStatusBarWindow.findViewById(R.id.header);

        mStatusHeaderMachine = new StatusHeaderMachine(mContext);
        updateCustomHeaderStatus();

        mReminderHeader = mStatusBarWindow.findViewById(R.id.reminder_header);
        mReminderHeader.setOnClickListener(mReminderButtonListener);
        mReminderHeader.setOnLongClickListener(mReminderLongButtonListener);

        mSpacer = (ImageView) mNotificationPanelHeader.findViewById(R.id.spacer);
        mSpacer.setOnClickListener(mReminderButtonListener);
        mSpacer.setOnLongClickListener(mReminderLongButtonListener);

        mReminderTitle = (TextView) mReminderHeader.findViewById(R.id.title);

        mFlipper = (ViewFlipper) mReminderHeader.findViewById(R.id.message);
        mFlipper.setSelfMaintained(true);

        mFlipperLand = (ViewFlipper) mReminderHeader.findViewById(R.id.message_land);
        mFlipperLand.setSelfMaintained(true);
        
        mReminderEnabled = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.REMINDER_ALERT_ENABLED, 0, UserHandle.USER_CURRENT) != 0;

        View view = View.inflate(mContext, R.layout.reminder_entry, null);
        mTextHolder = (TextView) view.findViewById(R.id.message_content);

        mFlipInterval = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.REMINDER_ALERT_INTERVAL, 1500, UserHandle.USER_CURRENT);

        mShared = mContext.getSharedPreferences(
                    KEY_REMINDER_ACTION, Context.MODE_PRIVATE);

        setTakenSpace();

        mClearButton = mStatusBarWindow.findViewById(R.id.clear_all_button);
        mClearButton.setOnClickListener(mClearButtonListener);
        mClearButton.setAlpha(0f);
        mClearButton.setVisibility(View.INVISIBLE);
        mClearButton.setEnabled(false);
        mDateView = (DateView)mStatusBarWindow.findViewById(R.id.date);

        mHasSettingsPanel = res.getBoolean(R.bool.config_hasSettingsPanel);
        mHasFlipSettings = res.getBoolean(R.bool.config_hasFlipSettingsPanel);

        mDateTimeView = mNotificationPanelHeader.findViewById(R.id.datetime);

        mSettingsButton = (ImageView) mStatusBarWindow.findViewById(R.id.settings_button);
        if (mSettingsButton != null) {
            mSettingsButton.setOnClickListener(mSettingsButtonListener);
            if (mHasSettingsPanel) {
                if (mStatusBarView.hasFullWidthNotifications()) {
                    // the settings panel is hiding behind this button
                    mSettingsButton.setImageResource(R.drawable.ic_notify_quicksettings);
                    mSettingsButton.setVisibility(View.VISIBLE);
                } else {
                    // there is a settings panel, but it's on the other side of the (large) screen
                    final View buttonHolder = mStatusBarWindow.findViewById(
                            R.id.settings_button_holder);
                    if (buttonHolder != null) {
                        buttonHolder.setVisibility(View.GONE);
                    }
                }
            } else {
                // no settings panel, go straight to settings
                mSettingsButton.setVisibility(View.VISIBLE);
                mSettingsButton.setImageResource(R.drawable.ic_notify_settings);
            }
        }

        mHoverButton = (ImageView) mStatusBarWindow.findViewById(R.id.hover_button);
        if (mHoverButton != null) {
            mHoverButton.setOnClickListener(mHoverButtonListener);
            mHoverButton.setVisibility(View.VISIBLE);
        }

        if (mHasFlipSettings) {
            mNotificationButton = (ImageView) mStatusBarWindow.findViewById(R.id.notification_button);
            mAddTileButton = (ImageView) mStatusBarWindow.findViewById(R.id.add_tile_button);
            if (mNotificationButton != null) {
                mNotificationButton.setOnClickListener(mNotificationButtonListener);
            }
            if (mAddTileButton != null) {
                mAddTileButton.setOnClickListener(mAddTileButtonListener);
            }
        }

        mScrollView = (ScrollView)mStatusBarWindow.findViewById(R.id.scroll);
        mScrollView.setVerticalScrollBarEnabled(false); // less drawing during pulldowns
        if (!mNotificationPanelIsFullScreenWidth) {
            mScrollView.setSystemUiVisibility(
                    View.STATUS_BAR_DISABLE_NOTIFICATION_TICKER |
                    View.STATUS_BAR_DISABLE_NOTIFICATION_ICONS |
                    View.STATUS_BAR_DISABLE_CLOCK);
        }

        mTicker = new MyTicker(context, mStatusBarView);

        TickerView tickerView = (TickerView)mStatusBarView.findViewById(R.id.tickerText);
        tickerView.mTicker = mTicker;
        if (mHaloActive) mTickerView.setVisibility(View.GONE);

        mEdgeBorder = res.getDimensionPixelSize(R.dimen.status_bar_edge_ignore);

        // set the inital view visibility
        setAreThereNotifications();

        // Other icons
        mBatteryView = (BatteryMeterView) mStatusBarView.findViewById(R.id.battery);
        mDockBatteryView = (DockBatteryMeterView) mStatusBarView.findViewById(R.id.dock_battery);

        if (MSimTelephonyManager.getDefault().isMultiSimEnabled()) {
            mMSimNetworkController = new MSimNetworkController(mContext);
            mMSimSignalClusterView = (MSimSignalClusterView)
              mStatusBarView.findViewById(R.id.msim_signal_cluster);
            for (int i=0; i < MSimTelephonyManager.getDefault().getPhoneCount(); i++) {
                mMSimNetworkController.addSignalCluster(mMSimSignalClusterView, i);
            }
            mMSimSignalClusterView.setNetworkController(mMSimNetworkController);

            mEmergencyCallLabel = (TextView)mStatusBarWindow.findViewById(
                                                          R.id.emergency_calls_only);
            if (mEmergencyCallLabel != null) {
                mMSimNetworkController.addEmergencyLabelView(mEmergencyCallLabel);
                mEmergencyCallLabel.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { }});
                mEmergencyCallLabel.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                    @Override
                    public void onLayoutChange(View v, int left, int top, int right, int bottom,
                            int oldLeft, int oldTop, int oldRight, int oldBottom) {
                        updateCarrierLabelVisibility(false);
                    }});
            }

            mCarrierLabel = (TextView)mStatusBarWindow.findViewById(R.id.carrier_label);
            mSubsLabel = (TextView)mStatusBarWindow.findViewById(R.id.subs_label);
            mShowCarrierInPanel = (mCarrierLabel != null);

            if (DEBUG) Log.v(TAG, "carrierlabel=" + mCarrierLabel + " show=" +
                                    mShowCarrierInPanel + "operator label=" + mSubsLabel);
            if (mShowCarrierInPanel) {
                mCarrierLabel.setVisibility(mCarrierLabelVisible ? View.VISIBLE : View.INVISIBLE);

                // for mobile devices, we always show mobile connection info here (SPN/PLMN)
                // for other devices, we show whatever network is connected
                if (mMSimNetworkController.hasMobileDataFeature()) {
                    mMSimNetworkController.addMobileLabelView(mCarrierLabel);
                } else {
                    mMSimNetworkController.addCombinedLabelView(mCarrierLabel);
                }
                mSubsLabel.setVisibility(View.VISIBLE);
                mMSimNetworkController.addSubsLabelView(mSubsLabel);
                // set up the dynamic hide/show of the label
                mPile.setOnSizeChangedListener(new OnSizeChangedListener() {
                    @Override
                    public void onSizeChanged(View view, int w, int h, int oldw, int oldh) {
                        updateCarrierLabelVisibility(false);
                    }
                });
            }
        } else {
            mNetworkController = new NetworkController(mContext);
            mSignalClusterView = (SignalClusterView) mStatusBarView.findViewById(R.id.signal_cluster);
            mNetworkController.addSignalCluster(mSignalClusterView);
            mSignalClusterView.setNetworkController(mNetworkController);

            mSignalTextView = (SignalClusterTextView)
                    mStatusBarView.findViewById(R.id.signal_cluster_text);
            if (mSignalTextView != null) {
                mNetworkController.addNetworkSignalChangedCallback(mSignalTextView);
                mNetworkController.addSignalStrengthChangedCallback(mSignalTextView);
            }

            final boolean isAPhone = mNetworkController.hasVoiceCallingFeature();
            if (isAPhone) {
                mEmergencyCallLabel = (TextView)mStatusBarWindow.findViewById(
                                                       R.id.emergency_calls_only);
                if (mEmergencyCallLabel != null) {
                    mNetworkController.addEmergencyLabelView(mEmergencyCallLabel);
                    mEmergencyCallLabel.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) { }});
                    mEmergencyCallLabel.addOnLayoutChangeListener(
                                                    new View.OnLayoutChangeListener() {
                        @Override
                        public void onLayoutChange(View v, int left, int top, int right, int bottom,
                            int oldLeft, int oldTop, int oldRight, int oldBottom) {
                            updateCarrierLabelVisibility(false);
                        }});
                }
            }

            mCarrierLabel = (TextView)mStatusBarWindow.findViewById(R.id.carrier_label);
            mShowCarrierInPanel = (mCarrierLabel != null);
            if (DEBUG) Log.v(TAG, "carrierlabel=" + mCarrierLabel + " show=" +
                                                                  mShowCarrierInPanel);
            if (mShowCarrierInPanel) {
                mCarrierLabel.setVisibility(mCarrierLabelVisible ? View.VISIBLE : View.INVISIBLE);

                // for mobile devices, we always show mobile connection info here (SPN/PLMN)
                // for other devices, we show whatever network is connected
                if (mNetworkController.hasMobileDataFeature()) {
                    mNetworkController.addMobileLabelView(mCarrierLabel);
                } else {
                    mNetworkController.addCombinedLabelView(mCarrierLabel);
                }

                // set up the dynamic hide/show of the label
                mPile.setOnSizeChangedListener(new OnSizeChangedListener() {
                    @Override
                    public void onSizeChanged(View view, int w, int h, int oldw, int oldh) {
                        updateCarrierLabelVisibility(false);
                    }
                });
            }
        }

        // Quick Settings (where available, some restrictions apply)
        if (mHasSettingsPanel) {
            // first, figure out where quick settings should be inflated
            final View settings_stub;
            if (mHasFlipSettings) {
                // a version of quick settings that flips around behind the notifications
                settings_stub = mStatusBarWindow.findViewById(R.id.flip_settings_stub);
                if (settings_stub != null) {
                    mFlipSettingsView = ((ViewStub)settings_stub).inflate();
                    mFlipSettingsView.setVisibility(View.GONE);
                    mFlipSettingsView.setVerticalScrollBarEnabled(false);
                }
            } else {
                // full quick settings panel
                settings_stub = mStatusBarWindow.findViewById(R.id.quick_settings_stub);
                if (settings_stub != null) {
                    mSettingsPanel = (SettingsPanelView) ((ViewStub)settings_stub).inflate();
                } else {
                    mSettingsPanel = (SettingsPanelView) mStatusBarWindow.findViewById(R.id.settings_panel);
                }

                if (mSettingsPanel != null) {
                    if (!ActivityManager.isHighEndGfx()) {
                        mSettingsPanel.setBackground(new FastColorDrawable(context.getResources().getColor(
                                R.color.notification_panel_solid_background)));
                    }
                }
            }

            if (mQS != null) {
                mQS.shutdown();
                mQS = null;
            }

            // wherever you find it, Quick Settings needs a container to survive
            mSettingsContainer = (QuickSettingsContainerView)
                    mStatusBarWindow.findViewById(R.id.quick_settings_container);
            if (mSettingsContainer != null) {
                mQS = new QuickSettingsController(mContext, mSettingsContainer, this,
                        Settings.System.QUICK_SETTINGS_TILES, false);
                if (!mNotificationPanelIsFullScreenWidth) {
                    mSettingsContainer.setSystemUiVisibility(
                            View.STATUS_BAR_DISABLE_NOTIFICATION_TICKER
                            | View.STATUS_BAR_DISABLE_SYSTEM_INFO);
                }
                if (mSettingsPanel != null) {
                    mSettingsPanel.setQuickSettings(mQS);
                }
                mQS.setService(this);
                mQS.setBar(mStatusBarView);
                mQS.setupQuickSettings();

                if (mHoverButton != null) {
                    mHoverButton.setImageDrawable(null);
                    mHoverButton.setImageResource(mHoverState != HOVER_DISABLED
                            ? R.drawable.ic_notify_hover_pressed
                                    : R.drawable.ic_notify_hover_normal);
                }

                // Start observing for changes
                if (mTilesChangedObserver == null) {
                    mTilesChangedObserver = new TilesChangedObserver(mHandler);
                    mTilesChangedObserver.startObserving();
                }
            } else {
                mQS = null; // fly away, be free
            }

            final ContentResolver resolver = mContext.getContentResolver();
            mHasQuickAccessSettings = Settings.System.getIntForUser(resolver,
                    Settings.System.QS_QUICK_ACCESS, 0, UserHandle.USER_CURRENT) == 1;
            mQuickAccessLayoutLinked = Settings.System.getIntForUser(resolver,
                    Settings.System.QS_QUICK_ACCESS_LINKED, 1, UserHandle.USER_CURRENT) == 1;
            if (mHasQuickAccessSettings) {
                cleanupRibbon();
                mRibbonView = null;
                inflateRibbon();
            }

            // TODO: make multiuser aware
            mBrightnessSliderEnabled = Settings.System.getBoolean(resolver,
                    Settings.System.NOTIFICATION_BRIGHTNESS_SLIDER, false);
            if (mBrightnessSliderEnabled) {
                cleanupBrightnessSlider();
                mBrightnessView = null;
                inflateBrightnessSlider();
            }
        }

        PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mBroadcastReceiver.onReceive(mContext,
                new Intent(pm.isScreenOn() ? Intent.ACTION_SCREEN_ON : Intent.ACTION_SCREEN_OFF));

        // receive broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(ACTION_DEMO);
        filter.addAction(CUSTOM_LOCKSCREEN_STATE);
        filter.addAction(SCHEDULE_REMINDER_NOTIFY);
        context.registerReceiver(mBroadcastReceiver, filter);

        // listen for USER_SETUP_COMPLETE setting (per-user)
        resetUserSetupObserver();

        if (MSimTelephonyManager.getDefault().isMultiSimEnabled()) {
            mMSimNetworkController.setListener(this);
        } else {
            mNetworkController.setListener(this);
        }

        return mStatusBarView;
    }

    private void updateCustomHeaderStatus() {
        ContentResolver resolver = mContext.getContentResolver();
        boolean customHeader = Settings.System.getInt(
                resolver, Settings.System.STATUS_BAR_CUSTOM_HEADER, 0) == 1;

        if (mNotificationPanelHeader == null) return;

        // Setup the updating notification bar header image
        if (customHeader) {
            if (mStatusHeaderUpdater == null) {
                mStatusHeaderUpdater = new Runnable() {
                    private Drawable mPrevious = mNotificationPanelHeader.getBackground();

                    public void run() {
                        Drawable next = mStatusHeaderMachine.getCurrent();
                        if (next != mPrevious) {
                            Log.i(TAG, "Updating status bar header background");

                            setNotificationPanelHeaderBackground(next);
                            mPrevious = next;
                        }

                        // Check every hour. As postDelayed isn't holding a wakelock, it will basically
                        // only check when the CPU is on. Thus, not consuming battery overnight.
                        mHandler.postDelayed(this, 1000 * 3600);
                    }
                };
            }

            // Cancel any eventual ongoing statusHeaderUpdater, and start a clean one
            mHandler.removeCallbacks(mStatusHeaderUpdater);
            mHandler.post(mStatusHeaderUpdater);
        } else {
            if (mStatusHeaderUpdater != null) {
                mHandler.removeCallbacks(mStatusHeaderUpdater);
            }
            setNotificationPanelHeaderBackground(mStatusHeaderMachine.getDefault());
        }
    }

    private void setNotificationPanelHeaderBackground(final Drawable dw) {
        Drawable[] arrayDrawable = new Drawable[2];
        arrayDrawable[0] = mNotificationPanelHeader.getBackground();
        arrayDrawable[1] = dw;

        TransitionDrawable transitionDrawable = new TransitionDrawable(arrayDrawable);
        transitionDrawable.setCrossFadeEnabled(true);
        mNotificationPanelHeader.setBackgroundDrawable(transitionDrawable);
        transitionDrawable.startTransition(1000);
    }

    @Override
    protected void onShowSearchPanel() {
        if (mNavigationBarView != null) {
            mNavigationBarView.getBarTransitions().setContentVisible(false);
        }
    }

    @Override
    protected void onHideSearchPanel() {
        if (mNavigationBarView != null) {
            mNavigationBarView.getBarTransitions().setContentVisible(true);
        }
    }

    @Override
    protected View getStatusBarView() {
        return mStatusBarView;
    }

    @Override
    protected WindowManager.LayoutParams getSearchLayoutParams(LayoutParams layoutParams) {
        boolean opaque = false;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                (opaque ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT));
        if (ActivityManager.isHighEndGfx()) {
            lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        }
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        lp.setTitle("SearchPanel");
        // TODO: Define custom animation for Search panel
        lp.windowAnimations = com.android.internal.R.style.Animation_RecentApplications;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        return lp;
    }

    @Override
    protected void updateSearchPanel() {
        super.updateSearchPanel();
        if (mNavigationBarView != null) {
            mNavigationBarView.setDelegateView(mSearchPanelView);
        }
    }

    @Override
    public void showSearchPanel() {
        super.showSearchPanel();
        mHandler.removeCallbacks(mShowSearchPanel);

        // we want to freeze the sysui state wherever it is
        mSearchPanelView.setSystemUiVisibility(mSystemUiVisibility);

        if (mNavigationBarView != null) {
            WindowManager.LayoutParams lp =
                (android.view.WindowManager.LayoutParams) mNavigationBarView.getLayoutParams();
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            mWindowManager.updateViewLayout(mNavigationBarView, lp);
        }
    }

    @Override
    public void hideSearchPanel() {
        super.hideSearchPanel();
        if (mNavigationBarView != null) {
            WindowManager.LayoutParams lp =
                (android.view.WindowManager.LayoutParams) mNavigationBarView.getLayoutParams();
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            mWindowManager.updateViewLayout(mNavigationBarView, lp);
        }
    }

    protected int getStatusBarGravity() {
        return Gravity.TOP | Gravity.FILL_HORIZONTAL;
    }

    public int getStatusBarHeight() {
        if (mNaturalBarHeight < 0) {
            final Resources res = mContext.getResources();
            mNaturalBarHeight =
                    res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
        }
        return mNaturalBarHeight;
    }

    private int getBottomGap() {
        return mContext.getResources().getDimensionPixelSize(R.dimen.heads_up_bottom_gap);
    }

    private View.OnClickListener mRecentsClickListener = new View.OnClickListener() {
        public void onClick(View v) {
            awakenDreams();
            toggleRecentApps();
        }
    };

    private View.OnLongClickListener mRecentsLongClickListener = new View.OnLongClickListener() {
        public boolean onLongClick(View v) {
            awakenDreams();
            recentsLongPress();
            return true;
        }
    };

    private int mShowSearchHoldoff = 0;
    private Runnable mShowSearchPanel = new Runnable() {
        public void run() {
            showSearchPanel();
            awakenDreams();
        }
    };

    View.OnTouchListener mHomeSearchActionListener = new View.OnTouchListener() {
        public boolean onTouch(View v, MotionEvent event) {
            switch(event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (!shouldDisableNavbarGestures()) {
                    mHandler.removeCallbacks(mShowSearchPanel);
                    mHandler.postDelayed(mShowSearchPanel, mShowSearchHoldoff);
                }
            break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mHandler.removeCallbacks(mShowSearchPanel);
                awakenDreams();
            break;
        }
        return false;
        }
    };

    private void awakenDreams() {
        if (mDreamManager != null) {
            try {
                mDreamManager.awaken();
            } catch (RemoteException e) {
                // fine, stay asleep then
            }
        }
    }

    private void prepareNavigationBarView() {
        mNavigationBarView.reorient();
        mNavigationBarView.setListeners(mRecentsClickListener, mRecentsLongClickListener,
                mRecentsPreloadOnTouchListener, mHomeSearchActionListener);
        updateSearchPanel();
    }

    // For small-screen devices (read: phones) that lack hardware navigation buttons
    private void addNavigationBar() {
        if (DEBUG) Log.v(TAG, "addNavigationBar: about to add " + mNavigationBarView);
        if (mNavigationBarView == null) return;

        ThemeConfig newTheme = mContext.getResources().getConfiguration().themeConfig;
        if (newTheme != null &&
                (mCurrentTheme == null || !mCurrentTheme.equals(newTheme))) {
            // Nevermind, this will be re-created
            return;
        }

        prepareNavigationBarView();

        mWindowManager.addView(mNavigationBarView, getNavigationBarLayoutParams());
        // Pie controls
        mNavigationBarOverlay.setNavigationBar(mNavigationBarView);
        mNavigationBarOverlay.setIsExpanded(isExpanded());
        mNavigationBarOverlay.setIsExpanded(noNavBar());
    }

    private void repositionNavigationBar() {
        if (mNavigationBarView == null || !mNavigationBarView.isAttachedToWindow()) return;

        prepareNavigationBarView();

        mWindowManager.updateViewLayout(mNavigationBarView, getNavigationBarLayoutParams());
    }

    private void notifyNavigationBarScreenOn(boolean screenOn) {
        if (mNavigationBarView == null) return;
        mNavigationBarView.notifyScreenOn(screenOn);
    }

    private WindowManager.LayoutParams getNavigationBarLayoutParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR,
                    0
                    | WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT);
        // this will allow the navbar to run in an overlay on devices that support this
        if (ActivityManager.isHighEndGfx()) {
            lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        }

        lp.setTitle("NavigationBar");
        lp.windowAnimations = 0;
        return lp;
    }

    private Resources getNavbarThemedResources() {
        String pkgName = mCurrentTheme.getOverlayPkgNameForApp(ThemeConfig.SYSTEMUI_NAVBAR_PKG);
        Resources res = null;
        try {
            res = mContext.getPackageManager().getThemedResourcesForApplication(
                    mContext.getPackageName(), pkgName);
        } catch (PackageManager.NameNotFoundException e) {
            res = mContext.getResources();
        }
        return res;
    }

    private void addHeadsUpView() {
        if (mHeadsUpNotificationViewAttached) {
            return;
        }

        mHeadsUpNotificationViewAttached = true;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL, // above the status bar!
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                  PixelFormat.TRANSPARENT);
        if (ActivityManager.isHighEndGfx()) {
            lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        }
        lp.gravity = mHeadsUpGravityBottom && !mImeIsShowing ? Gravity.BOTTOM : Gravity.TOP;
        lp.y = mHeadsUpGravityBottom && !mImeIsShowing
                ? getBottomGap() : (mStatusBarShows ? getStatusBarHeight() : 0);
        lp.setTitle("Heads Up");
        lp.packageName = mContext.getPackageName();
        lp.windowAnimations = R.style.Animation_StatusBar_HeadsUp;

        mWindowManager.addView(mHeadsUpNotificationView, lp);
    }

    private void removeHeadsUpView() {
        if (mHeadsUpNotificationViewAttached) {
            mHeadsUpNotificationViewAttached = false;
            mWindowManager.removeView(mHeadsUpNotificationView);
        }
    }

    public void refreshAllStatusBarIcons() {
        refreshAllIconsForLayout(mStatusIcons);
        refreshAllIconsForLayout(mNotificationIcons);
    }

    private void refreshAllIconsForLayout(LinearLayout ll) {
        final int count = ll.getChildCount();
        for (int n = 0; n < count; n++) {
            View child = ll.getChildAt(n);
            if (child instanceof StatusBarIconView) {
                ((StatusBarIconView) child).updateDrawable();
            }
        }
    }

    public void addIcon(String slot, int index, int viewIndex, StatusBarIcon icon) {
        if (SPEW) Log.d(TAG, "addIcon slot=" + slot + " index=" + index + " viewIndex=" + viewIndex
                + " icon=" + icon);
        StatusBarIconView view = new StatusBarIconView(mContext, slot, null);
        view.set(icon);
        mStatusIcons.addView(view, viewIndex, new LinearLayout.LayoutParams(mIconSize, mIconSize));
    }

    public void updateIcon(String slot, int index, int viewIndex,
            StatusBarIcon old, StatusBarIcon icon) {
        if (SPEW) Log.d(TAG, "updateIcon slot=" + slot + " index=" + index + " viewIndex=" + viewIndex
                + " old=" + old + " icon=" + icon);
        StatusBarIconView view = (StatusBarIconView)mStatusIcons.getChildAt(viewIndex);
        view.set(icon);
    }

    public void removeIcon(String slot, int index, int viewIndex) {
        if (SPEW) Log.d(TAG, "removeIcon slot=" + slot + " index=" + index + " viewIndex=" + viewIndex);
        mStatusIcons.removeViewAt(viewIndex);
    }

    public void addNotification(IBinder key, StatusBarNotification notification) {
        if (DEBUG) Log.d(TAG, "addNotification score=" + notification.getScore());
        Entry shadeEntry = createNotificationViews(key, notification);
        if (shadeEntry == null) {
            return;
        }
        if (shouldInterrupt(notification) && panelsEnabled() && !isHeadsUpInSnooze()) {
            populateHeadsUp(key, notification, shadeEntry);
        } else if (notification.getNotification().fullScreenIntent != null) {
            // Stop screensaver if the notification has a full-screen intent.
            // (like an incoming phone call)
            awakenDreams();

            // not immersive & a full-screen alert should be shown
            if (DEBUG) Log.d(TAG, "Notification has fullScreenIntent; sending fullScreenIntent");
            try {
                notification.getNotification().fullScreenIntent.send();
            } catch (PendingIntent.CanceledException e) {
            }
        } else if (!mRecreating) {
            // usual case: status bar visible & not immersive

            // show the ticker if there isn't already a heads up
            if (mInterruptingNotificationEntry == null) {
                tick(null, notification, true);
            }
        }
        addNotificationViews(shadeEntry);
        // Recalculate the position of the sliding windows and the titles.
        setAreThereNotifications();
        updateExpandedViewPos(EXPANDED_LEAVE_ALONE);
    }

    @Override
    public void resetHeadsUpDecayTimer() {
        if (mHeadsUpNotificationDecay > 0
                && mHeadsUpNotificationView.isClearable()) {
            mHandler.removeMessages(MSG_HIDE_HEADS_UP);
            mHandler.sendEmptyMessageDelayed(MSG_HIDE_HEADS_UP, mHeadsUpNotificationDecay);
        }
    }

    @Override
    public void populateHeadsUp(IBinder key,
            StatusBarNotification notification, Entry shadeEntry) {
        if (DEBUG) Log.d(TAG, "launching notification in heads up mode");
        Entry interruptionCandidate = new Entry(key, notification, null);
        if (inflateViews(interruptionCandidate, mHeadsUpNotificationView.getHolder())) {
            mInterruptingNotificationTime = System.currentTimeMillis();
            mInterruptingNotificationEntry = interruptionCandidate;
            if (shadeEntry != null) {
                shadeEntry.setInterruption();
            }

            // Either the user want to see every heads up expanded....or the app which
            // requests the heads up force it to show as expanded.
            final boolean isExpanded = notification.getNotification().extras.getInt(
                        Notification.EXTRA_HEADS_UP_EXPANDED,
                        Notification.HEADS_UP_NOT_EXPANDED) == Notification.HEADS_UP_EXPANDED
                        || mHeadsUpExpandedByDefault;

            // 1. Populate mHeadsUpNotificationView
            mHeadsUpNotificationView.setNotification(
                    mInterruptingNotificationEntry, isExpanded);

            // 2. Animate mHeadsUpNotificationView in
            mHandler.sendEmptyMessage(MSG_SHOW_HEADS_UP);

            // 3. Set alarm to age the notification off
            resetHeadsUpDecayTimer();
        }
    }

    @Override // CommandQueue
    public void hideHeadsUp() {
        mHandler.removeMessages(MSG_HIDE_HEADS_UP);
        mHandler.sendEmptyMessage(MSG_HIDE_HEADS_UP);
    }

    @Override // CommandQueue
    public void updateHeadsUpPosition(boolean statusBarShows) {
        mStatusBarShows = statusBarShows;
        // Change y layoutparams of heads up view when statusbar
        // visibility changes.
        // ToDo: We may want to animate this in future in the rare
        // case a heads up is showing and the statusbar visbility
        // changes to avoid a feeling that it is more jumping then animating.
        // With current aosp code we can only do it with
        // dirty workarounds. Lets see to integrate a native implementation
        // which allows transition anymations on y or x changes on layoutparams.
        if (mHeadsUpNotificationView != null) {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams)
                    mHeadsUpNotificationView.getLayoutParams();
            if (lp != null) {
                lp.gravity = mHeadsUpGravityBottom && !mImeIsShowing ? Gravity.BOTTOM : Gravity.TOP;
                lp.y = mHeadsUpGravityBottom && !mImeIsShowing
                        ? getBottomGap() : (mStatusBarShows ? getStatusBarHeight() : 0);
                mWindowManager.updateViewLayout(mHeadsUpNotificationView, lp);
            }
        }
    }

    public void removeNotification(IBinder key) {
        StatusBarNotification old = removeNotificationViews(key);
        if (SPEW) Log.d(TAG, "removeNotification key=" + key + " old=" + old);

        if (old != null) {
            // Cancel the ticker if it's still running
            mTicker.removeEntry(old);

            // Recalculate the position of the sliding windows and the titles.
            updateExpandedViewPos(EXPANDED_LEAVE_ALONE);

            if (mInterruptingNotificationEntry != null
                    && old == mInterruptingNotificationEntry.notification) {
                mHandler.sendEmptyMessage(MSG_HIDE_HEADS_UP);
            }
        }

        setAreThereNotifications();
    }

    @Override
    protected void refreshLayout(int layoutDirection) {
        if (mNavigationBarView != null) {
            mNavigationBarView.setLayoutDirection(layoutDirection);
        }

        if (mClearButton != null && mClearButton instanceof ImageView) {
            // Force asset reloading
            ((ImageView)mClearButton).setImageDrawable(null);
            ((ImageView)mClearButton).setImageResource(R.drawable.ic_notify_clear);
        }

        if (mHoverButton != null) {
            // Force asset reloading
            mHoverButton.setImageDrawable(null);
            mHoverButton.setImageResource(R.drawable.ic_notify_hover_normal);
        }

        if (mSettingsButton != null) {
            // Force asset reloading
            mSettingsButton.setImageDrawable(null);
            mSettingsButton.setImageResource(R.drawable.ic_notify_quicksettings);
        }

        if (mNotificationButton != null) {
            // Force asset reloading
            mNotificationButton.setImageDrawable(null);
            mNotificationButton.setImageResource(R.drawable.ic_notifications);
        }

        if (mAddTileButton != null) {
            // Force asset reloading
            mAddTileButton.setImageDrawable(null);
            mAddTileButton.setImageResource(R.drawable.ic_menu_add);
        }

        refreshAllStatusBarIcons();
    }

    private void updateShowSearchHoldoff() {
        mShowSearchHoldoff = mContext.getResources().getInteger(
            R.integer.config_show_search_delay);
    }

    private void loadNotificationShade() {
        if (mPile == null) return;

        int N = mNotificationData.size();

        ArrayList<View> toShow = new ArrayList<View>();

        final boolean provisioned = isDeviceProvisioned();
        // If the device hasn't been through Setup, we only show system notifications
        for (int i=0; i<N; i++) {
            Entry ent = mNotificationData.get(N-i-1);
            if (!(provisioned || showNotificationEvenIfUnprovisioned(ent.notification))) continue;
            if (!notificationIsForCurrentUser(ent.notification)) continue;
            toShow.add(ent.row);
        }

        ArrayList<View> toRemove = new ArrayList<View>();
        for (int i=0; i<mPile.getChildCount(); i++) {
            View child = mPile.getChildAt(i);
            if (!toShow.contains(child)) {
                toRemove.add(child);
            }
        }

        for (View remove : toRemove) {
            mPile.removeView(remove);
        }

        setNotificationAlpha();
        for (int i=0; i<toShow.size(); i++) {
            View v = toShow.get(i);
            if (v.getParent() == null) {
                mPile.addView(v, i);
            }
        }

        if (mHoverButton != null) {
            mHoverButton.setEnabled(isDeviceProvisioned());
        }

        if (mSettingsButton != null) {
            mSettingsButton.setEnabled(isDeviceProvisioned());
        }
    }

    @Override
    public void updateNotificationIcons() {
        if (mNotificationIcons == null) return;

        loadNotificationShade();

        final LinearLayout.LayoutParams params
            = new LinearLayout.LayoutParams(mIconSize + 2*mIconHPadding, mNaturalBarHeight);

        int N = mNotificationData.size();

        if (DEBUG) {
            Log.d(TAG, "refreshing icons: " + N + " notifications, mNotificationIcons=" + mNotificationIcons);
        }

        ArrayList<View> toShow = new ArrayList<View>();

        final boolean provisioned = isDeviceProvisioned();
        // If the device hasn't been through Setup, we only show system notifications
        for (int i=0; i<N; i++) {
            Entry ent = mNotificationData.get(N-i-1);
            if (!((provisioned && ent.notification.getScore() >= HIDE_ICONS_BELOW_SCORE)
                    || showNotificationEvenIfUnprovisioned(ent.notification) || mHaloTaskerActive)) continue;
            if (!notificationIsForCurrentUser(ent.notification)) continue;
            if (isIconHiddenByUser(ent.notification.getPackageName())) continue;
            toShow.add(ent.icon);
        }

        ArrayList<View> toRemove = new ArrayList<View>();
        for (int i=0; i<mNotificationIcons.getChildCount(); i++) {
            View child = mNotificationIcons.getChildAt(i);
            if (!toShow.contains(child)) {
                toRemove.add(child);
            }
        }

        for (View remove : toRemove) {
            mNotificationIcons.removeView(remove);
        }

        for (int i=0; i<toShow.size(); i++) {
            View v = toShow.get(i);
            if (v.getParent() == null) {
                mNotificationIcons.addView(v, i, params);
            }
        }
    }

    /**
     * Listen for UI updates and refresh layout.
     */
    public void onUpdateUI() {
        updateCarrierLabelVisibility(true);
    }

    protected void updateCarrierLabelVisibility(boolean force) {
        if (!mShowCarrierInPanel) return;
        // The idea here is to only show the carrier label when there is enough room to see it,
        // i.e. when there aren't enough notifications to fill the panel.
        if (SPEW) {
            Log.d(TAG, String.format("pileh=%d scrollh=%d carrierh=%d",
                    mPile.getHeight(), mScrollView.getHeight(), mCarrierLabelHeight));
        }

        final boolean emergencyCallsShownElsewhere = mEmergencyCallLabel != null;
        final boolean isEmergencyOnly = MSimTelephonyManager.getDefault().isMultiSimEnabled() ?
             mMSimNetworkController.isEmergencyOnly() :
             mNetworkController.isEmergencyOnly();

        final boolean makeVisible =
            !(emergencyCallsShownElsewhere && isEmergencyOnly)
            && mPile.getHeight() < (mNotificationPanel.getHeight() - mCarrierLabelHeight - mNotificationHeaderHeight)
            && mScrollView.getVisibility() == View.VISIBLE
            && !mAnimatingFlip;

        if (force || mCarrierLabelVisible != makeVisible) {
            mCarrierLabelVisible = makeVisible;
            if (DEBUG) {
                Log.d(TAG, "making carrier label " + (makeVisible?"visible":"invisible"));
            }
            mCarrierLabel.animate().cancel();
            if (makeVisible) {
                mCarrierLabel.setVisibility(View.VISIBLE);
            }
            mCarrierLabel.animate()
                .alpha(makeVisible ? 1f : 0f)
                //.setStartDelay(makeVisible ? 500 : 0)
                //.setDuration(makeVisible ? 750 : 100)
                .setDuration(150)
                .setListener(makeVisible ? null : new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (!mCarrierLabelVisible) { // race
                            mCarrierLabel.setVisibility(View.INVISIBLE);
                            mCarrierLabel.setAlpha(0f);
                        }
                    }
                })
                .start();
        }
    }

    protected boolean hasVisibleNotifications() {
        return mNotificationData.hasVisibleItems();
    }

    protected boolean hasClearableNotifications() {
        return mNotificationData.hasClearableItems();
    }

    @Override
    protected void setAreThereNotifications() {
        final boolean any = mNotificationData.size() > 0;

        final boolean clearable = any && hasClearableNotifications();

        if (SPEW) {
            Log.d(TAG, "setAreThereNotifications: N=" + mNotificationData.size()
                    + " any=" + any + " clearable=" + clearable);
        }

        if (mHasFlipSettings
                && mFlipSettingsView != null
                && mFlipSettingsView.getVisibility() == View.VISIBLE
                && mScrollView.getVisibility() != View.VISIBLE) {
            // the flip settings panel is unequivocally showing; we should not be shown
            mClearButton.setVisibility(View.INVISIBLE);
        } else if (mClearButton.isShown()) {
            if (clearable != (mClearButton.getAlpha() == 1.0f)) {
                ObjectAnimator clearAnimation = ObjectAnimator.ofFloat(
                        mClearButton, "alpha", clearable ? 1.0f : 0.0f).setDuration(250);
                clearAnimation.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (mClearButton.getAlpha() <= 0.0f) {
                            mClearButton.setVisibility(View.INVISIBLE);
                        }
                    }

                    @Override
                    public void onAnimationStart(Animator animation) {
                        if (mClearButton.getAlpha() <= 0.0f) {
                            mClearButton.setVisibility(View.VISIBLE);
                        }
                    }
                });
                clearAnimation.start();
            }
        } else {
            mClearButton.setAlpha(clearable ? 1.0f : 0.0f);
            mClearButton.setVisibility(clearable ? View.VISIBLE : View.INVISIBLE);
        }
        mClearButton.setEnabled(clearable);

        final View nlo = mStatusBarView.findViewById(R.id.notification_lights_out);
        final boolean showDot = (any&&!areLightsOn());
        if (showDot != (nlo.getAlpha() == 1.0f)) {
            if (showDot) {
                nlo.setAlpha(0f);
                nlo.setVisibility(View.VISIBLE);
            }
            nlo.animate()
                .alpha(showDot ? 1 : 0)
                .setDuration(showDot ? 750 : 250)
                .setInterpolator(new AccelerateInterpolator(2.0f))
                .setListener(showDot ? null : new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator _a) {
                        nlo.setVisibility(View.GONE);
                    }
                })
                .start();
        }

        updateCarrierLabelVisibility(false);
    }

    public void showClock(boolean show) {
        mShowClock = show;
        updateClockVisibility();
    }

    private void updateClockVisibility() {
        if (mClockView != null) {
            int visibility = mClockStyle != CLOCK_STYLE_HIDDEN
                    && mShowClock ? View.VISIBLE : View.GONE;
            if (mClockStyle == CLOCK_STYLE_CENTERED && mTicking) {
                visibility = View.INVISIBLE;
            }
            mClockView.setVisibility(visibility);
        }
    }

    private void updateClockLocation() {
        mClockStyle = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CLOCK, CLOCK_STYLE_DEFAULT, mCurrentUserId);

        mNotificationIcons.setCenteredClock(mClockStyle == CLOCK_STYLE_CENTERED);
        updateNotificationIcons();

        switch (mClockStyle) {
            case CLOCK_STYLE_DEFAULT:
                mClockView = (Clock) mStatusBarView.findViewById(R.id.clock);
                mStatusBarView.findViewById(R.id.center_clock).setVisibility(View.GONE);
                break;
            case CLOCK_STYLE_CENTERED:
                mClockView = (Clock) mStatusBarView.findViewById(R.id.center_clock);
                mStatusBarView.findViewById(R.id.clock).setVisibility(View.GONE);
                break;
        }

        updateClockVisibility();
    }

    /**
     * State is one or more of the DISABLE constants from StatusBarManager.
     */
    public void disable(int state) {
        final int old = mDisabled;
        final int diff = state ^ old;
        mDisabled = state;

        if (DEBUG) {
            Log.d(TAG, String.format("disable: 0x%08x -> 0x%08x (diff: 0x%08x)",
                old, state, diff));
        }

        StringBuilder flagdbg = new StringBuilder();
        flagdbg.append("disable: < ");
        flagdbg.append(((state & StatusBarManager.DISABLE_EXPAND) != 0) ? "EXPAND" : "expand");
        flagdbg.append(((diff  & StatusBarManager.DISABLE_EXPAND) != 0) ? "* " : " ");
        flagdbg.append(((state & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) ? "ICONS" : "icons");
        flagdbg.append(((diff  & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) ? "* " : " ");
        flagdbg.append(((state & StatusBarManager.DISABLE_NOTIFICATION_ALERTS) != 0) ? "ALERTS" : "alerts");
        flagdbg.append(((diff  & StatusBarManager.DISABLE_NOTIFICATION_ALERTS) != 0) ? "* " : " ");
        flagdbg.append(((state & StatusBarManager.DISABLE_NOTIFICATION_TICKER) != 0) ? "TICKER" : "ticker");
        flagdbg.append(((diff  & StatusBarManager.DISABLE_NOTIFICATION_TICKER) != 0) ? "* " : " ");
        flagdbg.append(((state & StatusBarManager.DISABLE_SYSTEM_INFO) != 0) ? "SYSTEM_INFO" : "system_info");
        flagdbg.append(((diff  & StatusBarManager.DISABLE_SYSTEM_INFO) != 0) ? "* " : " ");
        flagdbg.append(((state & StatusBarManager.DISABLE_BACK) != 0) ? "BACK" : "back");
        flagdbg.append(((diff  & StatusBarManager.DISABLE_BACK) != 0) ? "* " : " ");
        flagdbg.append(((state & StatusBarManager.DISABLE_HOME) != 0) ? "HOME" : "home");
        flagdbg.append(((diff  & StatusBarManager.DISABLE_HOME) != 0) ? "* " : " ");
        flagdbg.append(((state & StatusBarManager.DISABLE_RECENT) != 0) ? "RECENT" : "recent");
        flagdbg.append(((diff  & StatusBarManager.DISABLE_RECENT) != 0) ? "* " : " ");
        flagdbg.append(((state & StatusBarManager.DISABLE_CLOCK) != 0) ? "CLOCK" : "clock");
        flagdbg.append(((diff  & StatusBarManager.DISABLE_CLOCK) != 0) ? "* " : " ");
        flagdbg.append(((state & StatusBarManager.DISABLE_SEARCH) != 0) ? "SEARCH" : "search");
        flagdbg.append(((diff  & StatusBarManager.DISABLE_SEARCH) != 0) ? "* " : " ");
        flagdbg.append(">");
        Log.d(TAG, flagdbg.toString());

        if ((diff & StatusBarManager.DISABLE_SYSTEM_INFO) != 0) {
            mSystemIconArea.animate().cancel();
            if ((state & StatusBarManager.DISABLE_SYSTEM_INFO) != 0) {
                mSystemIconArea.animate()
                    .alpha(0f)
                    .translationY(mNaturalBarHeight * 0.5f)
                    .setDuration(175)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .setListener(mMakeIconsInvisible)
                    .start();
            } else {
                mSystemIconArea.setVisibility(View.VISIBLE);
                mSystemIconArea.animate()
                    .alpha(1f)
                    .translationY(0)
                    .setStartDelay(0)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .setDuration(175)
                    .start();
            }
        }

        if ((diff & StatusBarManager.DISABLE_CLOCK) != 0) {
            boolean show = (state & StatusBarManager.DISABLE_CLOCK) == 0;
            showClock(show);
        }
        if ((diff & StatusBarManager.DISABLE_EXPAND) != 0) {
            if ((state & StatusBarManager.DISABLE_EXPAND) != 0) {
                animateCollapsePanels();
            }
        }

        if ((diff & (StatusBarManager.DISABLE_HOME
                        | StatusBarManager.DISABLE_RECENT
                        | StatusBarManager.DISABLE_BACK
                        | StatusBarManager.DISABLE_SEARCH)) != 0) {
            
            // All navigation bar listeners will take care of these
            propagateDisabledFlags(state);

            if ((state & StatusBarManager.DISABLE_RECENT) != 0) {
                // close recents if it's visible
                mHandler.removeMessages(MSG_CLOSE_RECENTS_PANEL);
                mHandler.sendEmptyMessage(MSG_CLOSE_RECENTS_PANEL);
            }
        }

        if ((diff & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) {
            if ((state & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) {
                if (mTicking) {
                    haltTicker();
                }

                mNotificationIcons.animate()
                    .alpha(0f)
                    .translationY(mNaturalBarHeight * 0.5f)
                    .setDuration(175)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .setListener(mMakeIconsInvisible)
                    .start();
            } else {
                mNotificationIcons.setVisibility(View.VISIBLE);
                mNotificationIcons.animate()
                    .alpha(1f)
                    .translationY(0)
                    .setStartDelay(0)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .setDuration(175)
                    .start();
            }
        } else if ((diff & StatusBarManager.DISABLE_NOTIFICATION_TICKER) != 0) {
            if (mTicking && (state & StatusBarManager.DISABLE_NOTIFICATION_TICKER) != 0) {
                haltTicker();
            }
        }
    }

    @Override
    protected BaseStatusBar.H createHandler() {
        return new PhoneStatusBar.H();
    }

    /**
     * All changes to the status bar and notifications funnel through here and are batched.
     */
    private class H extends BaseStatusBar.H {
        public void handleMessage(Message m) {
            super.handleMessage(m);
            switch (m.what) {
                case MSG_OPEN_NOTIFICATION_PANEL:
                    animateExpandNotificationsPanel();
                    break;
                case MSG_OPEN_SETTINGS_PANEL:
                    animateExpandSettingsPanel(true);
                    break;
                case MSG_OPEN_QS_PANEL:
                    animateExpandSettingsPanel(false);
                    break;
                case MSG_FLIP_TO_NOTIFICATION_PANEL:
                    flipToNotifications();
                    break;
                case MSG_FLIP_TO_QS_PANEL:
                    flipToSettings();
                    break;
                case MSG_CLOSE_PANELS:
                    animateCollapsePanels();
                    break;
                case MSG_SHOW_HEADS_UP:
                    setHeadsUpVisibility(true);
                    break;
                case MSG_HIDE_HEADS_UP:
                    setHeadsUpVisibility(false);
                    break;
                case MSG_ESCALATE_HEADS_UP:
                    escalateHeadsUp();
                    setHeadsUpVisibility(false);
                    break;
            }
        }
    }

    /**  if the interrupting notification had a fullscreen intent, fire it now.  */
    private void escalateHeadsUp() {
        if (mInterruptingNotificationEntry != null) {
            final StatusBarNotification sbn = mInterruptingNotificationEntry.notification;
            final Notification notification = sbn.getNotification();
            if (notification.fullScreenIntent != null) {
                if (DEBUG)
                    Log.d(TAG, "converting a heads up to fullScreen");
                try {
                    notification.fullScreenIntent.send();
                } catch (PendingIntent.CanceledException e) {
                }
            }
        }
    }

    public Handler getHandler() {
        return mHandler;
    }

    View.OnFocusChangeListener mFocusChangeListener = new View.OnFocusChangeListener() {
        public void onFocusChange(View v, boolean hasFocus) {
            // Because 'v' is a ViewGroup, all its children will be (un)selected
            // too, which allows marqueeing to work.
            v.setSelected(hasFocus);
        }
    };

    @Override
    public boolean panelsEnabled() {
        return (mDisabled & StatusBarManager.DISABLE_EXPAND) == 0;
    }

    @Override
    public boolean isExpandedVisible() {
        return mExpandedVisible;
    }

    void makeExpandedVisible() {
        if (SPEW) Log.d(TAG, "Make expanded visible: expanded visible=" + mExpandedVisible);
        if (mExpandedVisible || !panelsEnabled()) {
            return;
        }

        mExpandedVisible = true;
        mPile.setLayoutTransitionsEnabled(true);
        if (mNavigationBarView != null)
            mNavigationBarView.setSlippery(true);

        updateCarrierLabelVisibility(true);

        updateExpandedViewPos(EXPANDED_LEAVE_ALONE);

        // Expand the window to encompass the full screen in anticipation of the drag.
        // This is only possible to do atomically because the status bar is at the top of the screen!
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) mStatusBarContainer.getLayoutParams();
        lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        lp.flags |= WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        mWindowManager.updateViewLayout(mStatusBarContainer, lp);

        visibilityChanged(true);

        setInteracting(StatusBarManager.WINDOW_STATUS_BAR, true);
    }

    private void releaseFocus() {
        WindowManager.LayoutParams lp =
                (WindowManager.LayoutParams) mStatusBarContainer.getLayoutParams();
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        lp.flags &= ~WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        mWindowManager.updateViewLayout(mStatusBarContainer, lp);
    }

    public void animateCollapsePanels() {
        mNotificationPanelIsOpen = false;
        mQSPanelIsOpen = false;
        animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE);
    }

    public void animateCollapsePanels(int flags) {
        if (SPEW) {
            Log.d(TAG, "animateCollapse():"
                    + " mExpandedVisible=" + mExpandedVisible
                    + " flags=" + flags);
        }

        // release focus immediately to kick off focus change transition
        releaseFocus();

        if ((flags & CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL) == 0) {
            mHandler.removeMessages(MSG_CLOSE_RECENTS_PANEL);
            mHandler.sendEmptyMessage(MSG_CLOSE_RECENTS_PANEL);
        }

        if ((flags & CommandQueue.FLAG_EXCLUDE_SEARCH_PANEL) == 0) {
            mHandler.removeMessages(MSG_CLOSE_SEARCH_PANEL);
            mHandler.sendEmptyMessage(MSG_CLOSE_SEARCH_PANEL);
        }

        mStatusBarWindow.cancelExpandHelper();
        mStatusBarView.collapseAllPanels(true);
        if(mHover.isShowing() && !mHover.isHiding()) mHover.dismissHover(false, false);
    }

    public ViewPropertyAnimator setVisibilityWhenDone(
            final ViewPropertyAnimator a, final View v, final int vis) {
        a.setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                v.setVisibility(vis);
                a.setListener(null); // oneshot
            }
        });
        return a;
    }

    public Animator setVisibilityWhenDone(
            final Animator a, final View v, final int vis) {
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                v.setVisibility(vis);
            }
        });
        return a;
    }

    public Animator setVisibilityOnStart(
            final Animator a, final View v, final int vis) {
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                v.setVisibility(vis);
            }
        });
        return a;
    }

    public Animator interpolator(TimeInterpolator ti, Animator a) {
        a.setInterpolator(ti);
        return a;
    }

    public Animator startDelay(int d, Animator a) {
        a.setStartDelay(d);
        return a;
    }

    public Animator start(Animator a) {
        a.start();
        return a;
    }

    final TimeInterpolator mAccelerateInterpolator = new AccelerateInterpolator();
    final TimeInterpolator mDecelerateInterpolator = new DecelerateInterpolator();
    final int FLIP_DURATION_OUT = 125;
    final int FLIP_DURATION_IN = 225;
    final int FLIP_DURATION = (FLIP_DURATION_IN + FLIP_DURATION_OUT);

    Animator mScrollViewAnim, mFlipSettingsViewAnim, mNotificationButtonAnim, mSettingsButtonAnim, 
            mHoverButtonAnim, mClearButtonAnim, mRibbonViewAnim, mAddTileButtonAnim, mBrightnessViewAnim;

    @Override
    public void animateExpandNotificationsPanel() {
        if (SPEW) Log.d(TAG, "animateExpand: mExpandedVisible=" + mExpandedVisible);
        if (!panelsEnabled()) {
            return ;
        }

        mNotificationPanel.expand();
        mNotificationPanelIsOpen = true;
        mQSPanelIsOpen = false;

        if (mHasFlipSettings && mScrollView.getVisibility() != View.VISIBLE) {
            flipToNotifications();
        }

        if (false) postStartTracing();
    }

    public void flipToNotifications() {
        if (mFlipSettingsViewAnim != null) mFlipSettingsViewAnim.cancel();
        if (mScrollViewAnim != null) mScrollViewAnim.cancel();
        if (mRibbonViewAnim != null) mRibbonViewAnim.cancel();
        if (mBrightnessViewAnim != null) mBrightnessViewAnim.cancel();
        if (mSettingsButtonAnim != null) mSettingsButtonAnim.cancel();
        if (mNotificationButtonAnim != null) mNotificationButtonAnim.cancel();
        if (mHoverButtonAnim != null) mHoverButtonAnim.cancel();
        if (mClearButtonAnim != null) mClearButtonAnim.cancel();
        if (mAddTileButtonAnim != null) mAddTileButtonAnim.cancel();

        final boolean halfWayDone = mScrollView.getVisibility() == View.VISIBLE;
        final int zeroOutDelays = halfWayDone ? 0 : 1;

        if (!halfWayDone) {
            mScrollView.setScaleX(0f);
            mFlipSettingsView.setScaleX(1f);
        }

        mAnimatingFlip = true;
        mScrollView.setVisibility(View.VISIBLE);
        mScrollViewAnim = start(
            startDelay(FLIP_DURATION_OUT * zeroOutDelays,
                interpolator(mDecelerateInterpolator,
                    ObjectAnimator.ofFloat(mScrollView, View.SCALE_X, 1f)
                        .setDuration(FLIP_DURATION_IN)
                    )));
        if (mRibbonView != null && mHasQuickAccessSettings) {
            mRibbonViewAnim = start(
                    startDelay(FLIP_DURATION_OUT * zeroOutDelays,
                            setVisibilityOnStart(
                                    interpolator(mDecelerateInterpolator,
                                            ObjectAnimator.ofFloat(mRibbonView, View.SCALE_X, 1f)
                                                    .setDuration(FLIP_DURATION_IN)),
                                    mRibbonView, View.VISIBLE)));
        }
        if (mBrightnessView != null && mBrightnessSliderEnabled) {
            mBrightnessViewAnim = start(
                    startDelay(FLIP_DURATION_OUT * zeroOutDelays,
                            setVisibilityOnStart(
                                    interpolator(mDecelerateInterpolator,
                                            ObjectAnimator.ofFloat(mBrightnessView, View.SCALE_X, 1f)
                                                    .setDuration(FLIP_DURATION_IN)),
                                    mBrightnessView, View.VISIBLE)));
        }
        mFlipSettingsViewAnim = start(
            setVisibilityWhenDone(
                interpolator(mAccelerateInterpolator,
                        ObjectAnimator.ofFloat(mFlipSettingsView, View.SCALE_X, 0f)
                        )
                    .setDuration(FLIP_DURATION_OUT),
                mFlipSettingsView, View.INVISIBLE));
        mNotificationButtonAnim = start(
            setVisibilityWhenDone(
                ObjectAnimator.ofFloat(mNotificationButton, View.ALPHA, 0f)
                    .setDuration(FLIP_DURATION),
                mNotificationButton, View.INVISIBLE));
        mAddTileButtonAnim = start(
            setVisibilityWhenDone(
                ObjectAnimator.ofFloat(mAddTileButton, View.ALPHA, 0f)
                    .setDuration(FLIP_DURATION),
                mAddTileButton, View.INVISIBLE));
        mSettingsButton.setVisibility(View.VISIBLE);
        mSettingsButtonAnim = start(
            ObjectAnimator.ofFloat(mSettingsButton, View.ALPHA, 1f)
                .setDuration(FLIP_DURATION));
        mHoverButton.setVisibility(View.VISIBLE);
        mHoverButtonAnim = start(
            ObjectAnimator.ofFloat(mHoverButton, View.ALPHA, 1f)
                .setDuration(FLIP_DURATION));
        mClearButton.setVisibility(View.VISIBLE);
        mClearButton.setAlpha(0f);
        setAreThereNotifications(); // this will show/hide the button as necessary
        mNotificationPanel.postDelayed(new Runnable() {
            public void run() {
                mAnimatingFlip = false;
                updateCarrierLabelVisibility(false);
            }
        }, FLIP_DURATION - 150);

        mNotificationPanelIsOpen = true;
        mQSPanelIsOpen = false;
    }

    @Override
    public void animateExpandSettingsPanel(boolean flip) {
        if (SPEW) Log.d(TAG, "animateExpand: mExpandedVisible=" + mExpandedVisible);
        if (!panelsEnabled()) {
            return;
        }

        // Settings are not available in setup
        if (!mUserSetup) return;

        if (mHasFlipSettings) {
            mNotificationPanel.expand();
            if (mFlipSettingsView.getVisibility() != View.VISIBLE) {
                if (flip) {
                    flipToSettings();
                } else {
                    switchToSettings();
                }
            }
            mNotificationPanelIsOpen = false;
            mQSPanelIsOpen = true;
        } else if (mSettingsPanel != null) {
            mSettingsPanel.expand();
        }

        if (false) postStartTracing();
    }

    public void switchToSettings() {
        // Settings are not available in setup
        if (!mUserSetup) return;

        mFlipSettingsView.setScaleX(1f);
        mFlipSettingsView.setVisibility(View.VISIBLE);
        mSettingsButton.setVisibility(View.GONE);
        mHoverButton.setVisibility(View.GONE);
        mScrollView.setVisibility(View.GONE);
        mScrollView.setScaleX(0f);
        if (mRibbonView != null) {
            mRibbonView.setVisibility(View.GONE);
            mRibbonView.setScaleX(0f);
        }
        if (mBrightnessView != null) {
            mBrightnessView.setVisibility(View.GONE);
            mBrightnessView.setScaleX(0f);
        }
        mNotificationButton.setVisibility(View.VISIBLE);
        mNotificationButton.setAlpha(1f);
        mAddTileButton.setVisibility(View.VISIBLE);
        mAddTileButton.setAlpha(1f);
        mClearButton.setVisibility(View.GONE);
    }

    public boolean isShowingSettings() {
        return mHasFlipSettings && mFlipSettingsView.getVisibility() == View.VISIBLE;
    }

    public void completePartialFlip() {
        if (mHasFlipSettings) {
            if (mFlipSettingsView.getVisibility() == View.VISIBLE) {
                flipToSettings();
            } else {
                flipToNotifications();
            }
        }
    }

    public void partialFlip(float progress) {
        if (mFlipSettingsViewAnim != null) mFlipSettingsViewAnim.cancel();
        if (mScrollViewAnim != null) mScrollViewAnim.cancel();
        if (mSettingsButtonAnim != null) mSettingsButtonAnim.cancel();
        if (mNotificationButtonAnim != null) mNotificationButtonAnim.cancel();
        if (mClearButtonAnim != null) mClearButtonAnim.cancel();
        if (mAddTileButtonAnim != null) mAddTileButtonAnim.cancel();

        progress = Math.min(Math.max(progress, -1f), 1f);
        if (progress < 0f) { // notifications side
            mFlipSettingsView.setScaleX(0f);
            mFlipSettingsView.setVisibility(View.GONE);
            mSettingsButton.setVisibility(View.VISIBLE);
            mSettingsButton.setAlpha(-progress);
            mScrollView.setVisibility(View.VISIBLE);
            mScrollView.setScaleX(-progress);
            if (mRibbonView != null && mHasQuickAccessSettings) {
                mRibbonView.setVisibility(View.VISIBLE);
                mRibbonView.setScaleX(-progress);
            }
            if (mBrightnessView != null && mBrightnessSliderEnabled) {
                mBrightnessView.setVisibility(View.VISIBLE);
                mBrightnessView.setScaleX(-progress);
            }
            mNotificationButton.setVisibility(View.GONE);
            mAddTileButton.setVisibility(View.GONE);
        } else { // settings side
            mFlipSettingsView.setScaleX(progress);
            mFlipSettingsView.setVisibility(View.VISIBLE);
            mSettingsButton.setVisibility(View.GONE);
            mScrollView.setVisibility(View.GONE);
            mScrollView.setScaleX(0f);
            if (mRibbonView != null) {
                mRibbonView.setVisibility(View.GONE);
                mRibbonView.setScaleX(0f);
            }
            if (mBrightnessView != null) {
                mBrightnessView.setVisibility(View.GONE);
                mBrightnessView.setScaleX(0f);
            }
            mNotificationButton.setVisibility(View.VISIBLE);
            mNotificationButton.setAlpha(progress);
            mAddTileButton.setVisibility(View.VISIBLE);
            mAddTileButton.setAlpha(progress);
        }
        mClearButton.setVisibility(View.GONE);

        mAnimatingFlip = true;
        updateCarrierLabelVisibility(false);
    }

    public void flipToSettings() {
        // Settings are not available in setup
        if (!mUserSetup) return;

        if (mFlipSettingsViewAnim != null) mFlipSettingsViewAnim.cancel();
        if (mScrollViewAnim != null) mScrollViewAnim.cancel();
        if (mRibbonViewAnim != null) mRibbonViewAnim.cancel();
        if (mBrightnessViewAnim != null) mBrightnessViewAnim.cancel();
        if (mSettingsButtonAnim != null) mSettingsButtonAnim.cancel();
        if (mNotificationButtonAnim != null) mNotificationButtonAnim.cancel();
        if (mHoverButtonAnim != null) mHoverButtonAnim.cancel();
        if (mClearButtonAnim != null) mClearButtonAnim.cancel();
        if (mAddTileButtonAnim != null) mAddTileButtonAnim.cancel();

        final boolean halfWayDone = mFlipSettingsView.getVisibility() == View.VISIBLE;
        final int zeroOutDelays = halfWayDone ? 0 : 1;

        if (!halfWayDone) {
            mFlipSettingsView.setScaleX(0f);
            mScrollView.setScaleX(1f);
            if (mRibbonView != null) {
                mRibbonView.setScaleX(1f);
            }
            if (mBrightnessView != null) {
                mBrightnessView.setScaleX(1f);
            }
        }

        mFlipSettingsView.setVisibility(View.VISIBLE);
        mAnimatingFlip = true;
        mFlipSettingsViewAnim = start(
            startDelay(FLIP_DURATION_OUT * zeroOutDelays,
                interpolator(mDecelerateInterpolator,
                    ObjectAnimator.ofFloat(mFlipSettingsView, View.SCALE_X, 1f)
                        .setDuration(FLIP_DURATION_IN)
                    )));
        mScrollViewAnim = start(
            setVisibilityWhenDone(
                interpolator(mAccelerateInterpolator,
                        ObjectAnimator.ofFloat(mScrollView, View.SCALE_X, 0f)
                        )
                    .setDuration(FLIP_DURATION_OUT),
                mScrollView, View.INVISIBLE));
        if (mRibbonView != null) {
            mRibbonViewAnim = start(
                    setVisibilityWhenDone(
                            interpolator(mAccelerateInterpolator,
                                    ObjectAnimator.ofFloat(mRibbonView, View.SCALE_X, 0f)
                            )
                                    .setDuration(FLIP_DURATION_OUT),
                            mRibbonView, View.GONE));
        }
        if (mBrightnessView != null) {
            mBrightnessViewAnim = start(
                    setVisibilityWhenDone(
                            interpolator(mAccelerateInterpolator,
                                    ObjectAnimator.ofFloat(mBrightnessView, View.SCALE_X, 0f)
                            )
                                    .setDuration(FLIP_DURATION_OUT),
                            mBrightnessView, View.GONE));
        }
        mSettingsButtonAnim = start(
            setVisibilityWhenDone(
                ObjectAnimator.ofFloat(mSettingsButton, View.ALPHA, 0f)
                    .setDuration(FLIP_DURATION),
                    mScrollView, View.INVISIBLE));
        mHoverButtonAnim = start(
            setVisibilityWhenDone(
                ObjectAnimator.ofFloat(mHoverButton, View.ALPHA, 0f)
                    .setDuration(FLIP_DURATION),
                    mScrollView, View.INVISIBLE));
        mNotificationButton.setVisibility(View.VISIBLE);
        mNotificationButtonAnim = start(
            ObjectAnimator.ofFloat(mNotificationButton, View.ALPHA, 1f)
                .setDuration(FLIP_DURATION));
        mAddTileButton.setVisibility(View.VISIBLE);
        mAddTileButtonAnim = start(
            ObjectAnimator.ofFloat(mAddTileButton, View.ALPHA, 1f)
                .setDuration(FLIP_DURATION));
        mClearButtonAnim = start(
            setVisibilityWhenDone(
                ObjectAnimator.ofFloat(mClearButton, View.ALPHA, 0f)
                .setDuration(FLIP_DURATION),
                mClearButton, View.INVISIBLE));
        mNotificationPanel.postDelayed(new Runnable() {
            public void run() {
                mAnimatingFlip = false;
            }
        }, FLIP_DURATION - 150);
        updateCarrierLabelVisibility(false);
        mNotificationPanelIsOpen = false;
        mQSPanelIsOpen = true;
    }

    public void flipPanels() {
        if (mHasFlipSettings) {
            if (mFlipSettingsView.getVisibility() != View.VISIBLE) {
                flipToSettings();
            } else {
                flipToNotifications();
            }
        }
    }

    public void animateCollapseQuickSettings() {
        mStatusBarView.collapseAllPanels(true);
    }

    void makeExpandedInvisibleSoon() {
        mHandler.postDelayed(new Runnable() { public void run() { makeExpandedInvisible(); }}, 50);
    }

    void makeExpandedInvisible() {
        if (SPEW) Log.d(TAG, "makeExpandedInvisible: mExpandedVisible=" + mExpandedVisible
                + " mExpandedVisible=" + mExpandedVisible);

        if (!mExpandedVisible) {
            return;
        }

        // Ensure the panel is fully collapsed (just in case; bug 6765842, 7260868)
        mStatusBarView.collapseAllPanels(/*animate=*/ false);

        if (mHasFlipSettings) {
            // reset things to their proper state
            if (mFlipSettingsViewAnim != null) mFlipSettingsViewAnim.cancel();
            if (mScrollViewAnim != null) mScrollViewAnim.cancel();
            if (mRibbonViewAnim != null) mRibbonViewAnim.cancel();
            if (mBrightnessViewAnim != null) mBrightnessViewAnim.cancel();
            if (mSettingsButtonAnim != null) mSettingsButtonAnim.cancel();
            if (mNotificationButtonAnim != null) mNotificationButtonAnim.cancel();
            if (mHoverButtonAnim != null) mHoverButtonAnim.cancel();
            if (mClearButtonAnim != null) mClearButtonAnim.cancel();
            if (mAddTileButtonAnim != null) mAddTileButtonAnim.cancel();

            mScrollView.setScaleX(1f);
            mScrollView.setVisibility(View.VISIBLE);
            if (mRibbonView != null && mHasQuickAccessSettings) {
                mRibbonView.setScaleX(1f);
                mRibbonView.setVisibility(View.VISIBLE);
            }
            if (mBrightnessView != null && mBrightnessSliderEnabled) {
                mBrightnessView.setScaleX(1f);
                mBrightnessView.setVisibility(View.VISIBLE);
            }
            mSettingsButton.setAlpha(1f);
            mSettingsButton.setVisibility(View.VISIBLE);
            mHoverButton.setAlpha(1f);
            mHoverButton.setVisibility(View.VISIBLE);
            mNotificationPanel.setVisibility(View.GONE);
            mFlipSettingsView.setVisibility(View.GONE);
            mNotificationButton.setVisibility(View.GONE);
            mAddTileButton.setVisibility(View.GONE);
            setAreThereNotifications(); // show the clear button
        }

        mExpandedVisible = false;
        mPile.setLayoutTransitionsEnabled(false);
        if (mNavigationBarView != null)
            mNavigationBarView.setSlippery(false);
        visibilityChanged(false);

        // Shrink the window to the size of the status bar only
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) mStatusBarContainer.getLayoutParams();
        lp.height = getStatusBarHeight();
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        lp.flags &= ~WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        mWindowManager.updateViewLayout(mStatusBarContainer, lp);

        if ((mDisabled & StatusBarManager.DISABLE_NOTIFICATION_ICONS) == 0) {
            setNotificationIconVisibility(true, com.android.internal.R.anim.fade_in);
        }

        // Close any "App info" popups that might have snuck on-screen
        dismissPopups();

        toggleReminderFlipper(false);

        setInteracting(StatusBarManager.WINDOW_STATUS_BAR, false);
    }

    /**
     * Enables or disables layers on the children of the notifications pile.
     *
     * When layers are enabled, this method attempts to enable layers for the minimal
     * number of children. Only children visible when the notification area is fully
     * expanded will receive a layer. The technique used in this method might cause
     * more children than necessary to get a layer (at most one extra child with the
     * current UI.)
     *
     * @param layerType {@link View#LAYER_TYPE_NONE} or {@link View#LAYER_TYPE_HARDWARE}
     */
    private void setPileLayers(int layerType) {
        final int count = mPile.getChildCount();

        switch (layerType) {
            case View.LAYER_TYPE_NONE:
                for (int i = 0; i < count; i++) {
                    mPile.getChildAt(i).setLayerType(layerType, null);
                }
                break;
            case View.LAYER_TYPE_HARDWARE:
                final int[] location = new int[2];
                mNotificationPanel.getLocationInWindow(location);

                final int left = location[0];
                final int top = location[1];
                final int right = left + mNotificationPanel.getWidth();
                final int bottom = top + getExpandedViewMaxHeight();

                final Rect childBounds = new Rect();

                for (int i = 0; i < count; i++) {
                    final View view = mPile.getChildAt(i);
                    view.getLocationInWindow(location);

                    childBounds.set(location[0], location[1],
                            location[0] + view.getWidth(), location[1] + view.getHeight());

                    if (childBounds.intersects(left, top, right, bottom)) {
                        view.setLayerType(layerType, null);
                    }
                }

                break;
        }
    }

    private void adjustBrightness(int x) {
        float raw = ((float) x) / mScreenWidth;

        // Add a padding to the brightness control on both sides to
        // make it easier to reach min/max brightness
        float padded = Math.min(1.0f - BRIGHTNESS_CONTROL_PADDING,
                Math.max(BRIGHTNESS_CONTROL_PADDING, raw));
        float value = (padded - BRIGHTNESS_CONTROL_PADDING) /
                (1 - (2.0f * BRIGHTNESS_CONTROL_PADDING));

        int newBrightness = mMinBrightness + (int) Math.round(value *
                (android.os.PowerManager.BRIGHTNESS_ON - mMinBrightness));
        newBrightness = Math.min(newBrightness, android.os.PowerManager.BRIGHTNESS_ON);
        newBrightness = Math.max(newBrightness, mMinBrightness);

        try {
            IPowerManager power = IPowerManager.Stub.asInterface(
                    ServiceManager.getService("power"));
            if (power != null) {
                power.setTemporaryScreenBrightnessSettingOverride(newBrightness);
                Settings.System.putInt(mContext.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, newBrightness);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Setting Brightness failed: " + e);
        }
    }

    private void brightnessControl(MotionEvent event) {
        final int action = event.getAction();
        final int x = (int) event.getRawX();
        final int y = (int) event.getRawY();
        if (action == MotionEvent.ACTION_DOWN) {
            if (y < mNotificationHeaderHeight) {
                mLinger = 0;
                mInitialTouchX = x;
                mInitialTouchY = y;
                mJustPeeked = true;
                mHandler.removeCallbacks(mLongPressBrightnessChange);
                mHandler.postDelayed(mLongPressBrightnessChange,
                        BRIGHTNESS_CONTROL_LONG_PRESS_TIMEOUT);
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (y < mNotificationHeaderHeight && mJustPeeked) {
                if (mLinger > BRIGHTNESS_CONTROL_LINGER_THRESHOLD) {
                    adjustBrightness(x);
                } else {
                    final int xDiff = Math.abs(x - mInitialTouchX);
                    final int yDiff = Math.abs(y - mInitialTouchY);
                    final int touchSlop = ViewConfiguration.get(mContext).getScaledTouchSlop();
                    if (xDiff > yDiff) {
                        mLinger++;
                    }
                    if (xDiff > touchSlop || yDiff > touchSlop) {
                        mHandler.removeCallbacks(mLongPressBrightnessChange);
                    }
                }
            } else {
                if (y > mPeekHeight) {
                    mJustPeeked = false;
                }
                mHandler.removeCallbacks(mLongPressBrightnessChange);
            }
        } else if (action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL) {
            mHandler.removeCallbacks(mLongPressBrightnessChange);
        }
    }

    public boolean interceptTouchEvent(MotionEvent event) {
        if (DEBUG_GESTURES) {
            if (event.getActionMasked() != MotionEvent.ACTION_MOVE) {
                EventLog.writeEvent(EventLogTags.SYSUI_STATUSBAR_TOUCH,
                        event.getActionMasked(), (int) event.getX(), (int) event.getY(), mDisabled);
            }

        }

        if (SPEW) {
            Log.d(TAG, "Touch: rawY=" + event.getRawY() + " event=" + event + " mDisabled="
                + mDisabled + " mTracking=" + mTracking);
        } else if (CHATTY) {
            if (event.getAction() != MotionEvent.ACTION_MOVE) {
                Log.d(TAG, String.format(
                            "panel: %s at (%f, %f) mDisabled=0x%08x",
                            MotionEvent.actionToString(event.getAction()),
                            event.getRawX(), event.getRawY(), mDisabled));
            }
        }

        if (DEBUG_GESTURES) {
            mGestureRec.add(event);
        }

        if (mBrightnessControl) {
            brightnessControl(event);
        }
        if ((mDisabled & StatusBarManager.DISABLE_EXPAND) != 0) {
            return true;
        }

        if (mStatusBarWindowState == WINDOW_STATE_SHOWING) {
            final boolean upOrCancel =
                    event.getAction() == MotionEvent.ACTION_UP ||
                    event.getAction() == MotionEvent.ACTION_CANCEL;
            if (upOrCancel && !mExpandedVisible) {
                setInteracting(StatusBarManager.WINDOW_STATUS_BAR, false);
            } else {
                setInteracting(StatusBarManager.WINDOW_STATUS_BAR, true);
            }
        }
        return false;
    }

    public GestureRecorder getGestureRecorder() {
        return mGestureRec;
    }

    private void setNavigationIconHints(int hints) {
        if (hints == mNavigationIconHints) return;

        mNavigationIconHints = hints;

        propagateNavigationIconHints(hints);        

        checkBarModes();
    }

    @Override // CommandQueue
    public void setWindowState(int window, int state) {
        boolean showing = state == WINDOW_STATE_SHOWING;
        if (mStatusBarWindow != null
                && window == StatusBarManager.WINDOW_STATUS_BAR
                && mStatusBarWindowState != state) {
            mStatusBarWindowState = state;
            if (DEBUG_WINDOW_STATE) Log.d(TAG, "Status bar " + windowStateToString(state));
            if (!showing) {
                mStatusBarView.collapseAllPanels(false);
            }
            checkBarModes();
        }
        if (mNavigationBarView != null
                && window == StatusBarManager.WINDOW_NAVIGATION_BAR
                && mNavigationBarWindowState != state) {
            mNavigationBarWindowState = state;
            if (DEBUG_WINDOW_STATE) Log.d(TAG, "Navigation bar " + windowStateToString(state));
        }
    }

    @Override // CommandQueue
    public void setSystemUiVisibility(int vis, int mask) {
        final int oldVal = mSystemUiVisibility;
        final int newVal = (oldVal&~mask) | (vis&mask);
        final int diff = newVal ^ oldVal;
        if (DEBUG) Log.d(TAG, String.format(
                "setSystemUiVisibility vis=%s mask=%s oldVal=%s newVal=%s diff=%s",
                Integer.toHexString(vis), Integer.toHexString(mask),
                Integer.toHexString(oldVal), Integer.toHexString(newVal),
                Integer.toHexString(diff)));
        if (diff != 0) {
            mSystemUiVisibility = newVal;

            // update low profile
            if ((diff & View.SYSTEM_UI_FLAG_LOW_PROFILE) != 0) {
                final boolean lightsOut = (vis & View.SYSTEM_UI_FLAG_LOW_PROFILE) != 0;
                if (lightsOut) {
                    animateCollapsePanels();
                    if (mTicking) {
                        haltTicker();
                    }
                }

                setAreThereNotifications();
            }

            // update status bar mode
            final int sbMode = computeBarMode(oldVal, newVal, mStatusBarView.getBarTransitions(),
                    View.STATUS_BAR_TRANSIENT, View.STATUS_BAR_TRANSLUCENT);

            // update navigation bar mode
            final int nbMode = mNavigationBarView == null ? -1 : computeBarMode(
                    oldVal, newVal, mNavigationBarView.getBarTransitions(),
                    View.NAVIGATION_BAR_TRANSIENT, View.NAVIGATION_BAR_TRANSLUCENT);
            boolean sbModeChanged = sbMode != -1;
            boolean nbModeChanged = nbMode != -1;
            boolean checkBarModes = false;
            if (sbModeChanged && sbMode != mStatusBarMode) {
                mStatusBarMode = sbMode;
                checkBarModes = true;
            }
            if (nbModeChanged && nbMode != mNavigationBarMode) {
                mNavigationBarMode = nbMode;
                checkBarModes = true;
            }
            if (checkBarModes) {
                checkBarModes();
            }

            final boolean sbVisible = (newVal & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0
                    || (newVal & View.STATUS_BAR_TRANSIENT) != 0;
            final boolean nbVisible = (newVal & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0
                    || (newVal & View.NAVIGATION_BAR_TRANSIENT) != 0;

            sbModeChanged = sbModeChanged && sbVisible;
            nbModeChanged = nbModeChanged && nbVisible;


            if (sbModeChanged || nbModeChanged) {
                // update transient bar autohide
                if (sbMode == MODE_SEMI_TRANSPARENT || nbMode == MODE_SEMI_TRANSPARENT) {
                    scheduleAutohide();
                } else {
                    cancelAutohide();
                }
            } else if (!sbVisible && !nbVisible) {
                cancelAutohide();
            }

            // ready to unhide
            if ((vis & View.STATUS_BAR_UNHIDE) != 0) {
                mSystemUiVisibility &= ~View.STATUS_BAR_UNHIDE;
            }
            if ((vis & View.NAVIGATION_BAR_UNHIDE) != 0) {
                mSystemUiVisibility &= ~View.NAVIGATION_BAR_UNHIDE;
            }

            // send updated sysui visibility to window manager
            notifyUiVisibilityChanged(mSystemUiVisibility);
        }
    }

    @Override  // CommandQueue
    public void setAutoRotate(boolean enabled) {
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION,
                enabled ? 1 : 0);
    }

    private int computeBarMode(int oldVis, int newVis, BarTransitions transitions,
            int transientFlag, int translucentFlag) {
        final int oldMode = barMode(oldVis, transientFlag, translucentFlag);
        final int newMode = barMode(newVis, transientFlag, translucentFlag);
        if (oldMode == newMode) {
            return -1; // no mode change
        }
        return newMode;
    }

    @Override  // CommandQueue
    public void toggleNotificationShade() {
        int msg = (mExpandedVisible)
                ? ((mQSPanelIsOpen) ? MSG_FLIP_TO_NOTIFICATION_PANEL : MSG_CLOSE_PANELS)
                : MSG_OPEN_NOTIFICATION_PANEL;
        if (msg == MSG_OPEN_NOTIFICATION_PANEL) {
            try {
                mWindowManagerService.toggleStatusBar();
            } catch (RemoteException ex) {
            }
        }
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    @Override  // CommandQueue
    public void toggleQSShade() {
        int msg = 0;
        if (mHasFlipSettings) {
            msg = (mExpandedVisible)
                ? ((mNotificationPanelIsOpen) ? MSG_FLIP_TO_QS_PANEL
                : MSG_CLOSE_PANELS) : MSG_OPEN_QS_PANEL;
        } else {
            msg = (mExpandedVisible)
                ? MSG_CLOSE_PANELS : MSG_OPEN_QS_PANEL;
        }
        if (msg == MSG_OPEN_QS_PANEL) {
            try {
                mWindowManagerService.toggleStatusBar();
            } catch (RemoteException ex) {
            }
        }
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    private int barMode(int vis, int transientFlag, int translucentFlag) {
        return (vis & transientFlag) != 0 ? MODE_SEMI_TRANSPARENT
                : (vis & translucentFlag) != 0 ? MODE_TRANSLUCENT
                : (vis & View.SYSTEM_UI_FLAG_LOW_PROFILE) != 0 ? MODE_LIGHTS_OUT
                : MODE_OPAQUE;
    }

    private void checkBarModes() {
        if (mDemoMode) return;
        int sbMode = mStatusBarMode;
        if (panelsEnabled() && !mHasFlipSettings &&
        (mInteractingWindows & StatusBarManager.WINDOW_STATUS_BAR) != 0) {
            // if dual panels are expandable, force the status bar opaque on any interaction
            sbMode = MODE_OPAQUE;
        }
        boolean animateSb = shouldAnimateBarTransition(sbMode, mStatusBarWindowState);
        mStatusBarView.getBarTransitions().transitionTo(sbMode, animateSb);
        if (mNavigationBarView != null) {
            mNavigationBarView.getBarTransitions().transitionTo(mNavigationBarMode,
                    shouldAnimateBarTransition(mNavigationBarMode, mNavigationBarWindowState));
            // The status bar blocker is only needed if both
            // - the status bar is visible and
            // - the navigation bar is translucent (as otherwise there is no
            //   visual 'gap' which needs to be filled)
            boolean sbbNeeded = mStatusBarWindowState != WINDOW_STATE_HIDDEN
                    && mNavigationBarMode == MODE_TRANSLUCENT;
            int sbbMode = sbbNeeded ? sbMode : MODE_TRANSPARENT;
            mNavigationBarView.getStatusBarBlockerTransitions().transitionTo(sbbMode, animateSb);
        }
    }

    private boolean shouldAnimateBarTransition(int mode, int windowState) {
        return (mScreenOn == null || mScreenOn) && windowState != WINDOW_STATE_HIDDEN;
    }

    private void finishBarAnimations() {
        mStatusBarView.getBarTransitions().finishAnimations();
        if (mNavigationBarView != null) {
            mNavigationBarView.getBarTransitions().finishAnimations();
            mNavigationBarView.getStatusBarBlockerTransitions().finishAnimations();
        }
    }

    private final Runnable mCheckBarModes = new Runnable() {
        @Override
        public void run() {
            checkBarModes();
        }};

    @Override
    public void setInteracting(int barWindow, boolean interacting) {
        mInteractingWindows = interacting
                ? (mInteractingWindows | barWindow)
                : (mInteractingWindows & ~barWindow);
        if (mInteractingWindows != 0) {
            suspendAutohide();
        } else {
            resumeSuspendedAutohide();
        }
        checkBarModes();
    }

    private void resumeSuspendedAutohide() {
        if (mAutohideSuspended) {
            scheduleAutohide();
            mHandler.postDelayed(mCheckBarModes, 500); // longer than home -> launcher
        }
    }

    private void suspendAutohide() {
        mHandler.removeCallbacks(mAutohide);
        mHandler.removeCallbacks(mUserAutohide);
        mHandler.removeCallbacks(mCheckBarModes);
        mAutohideSuspended = (mSystemUiVisibility & STATUS_OR_NAV_TRANSIENT) != 0;
    }

    private void cancelAutohide() {
        mAutohideSuspended = false;
        mHandler.removeCallbacks(mAutohide);
    }

    private void scheduleAutohide() {
        cancelAutohide();
        mHandler.postDelayed(mAutohide, AUTOHIDE_TIMEOUT_MS);
    }

    private void checkUserAutohide(View v, MotionEvent event) {
        if ((mSystemUiVisibility & STATUS_OR_NAV_TRANSIENT) != 0  // a transient bar is revealed
                && event.getAction() == MotionEvent.ACTION_OUTSIDE // touch outside the source bar
                && event.getX() == 0 && event.getY() == 0  // a touch outside both bars
                ) {
            userAutohide();
        }
    }

    private void userAutohide() {
        cancelAutohide();
        mHandler.postDelayed(mUserAutohide, 350); // longer than app gesture -> flag clear
    }

    private boolean areLightsOn() {
        return 0 == (mSystemUiVisibility & View.SYSTEM_UI_FLAG_LOW_PROFILE);
    }

    public void setLightsOn(boolean on) {
        Log.v(TAG, "setLightsOn(" + on + ")");
        if (on) {
            setSystemUiVisibility(0, View.SYSTEM_UI_FLAG_LOW_PROFILE);
        } else {
            setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE, View.SYSTEM_UI_FLAG_LOW_PROFILE);
        }
    }

    private void notifyUiVisibilityChanged(int vis) {
        try {
            mWindowManagerService.statusBarVisibilityChanged(vis);
        } catch (RemoteException ex) {
        }
    }

    public void topAppWindowChanged(boolean showMenu) {
        if (DEBUG) {
            Log.d(TAG, (showMenu?"showing":"hiding") + " the MENU button");
        }

        propagateMenuVisibility(showMenu);

        // See above re: lights-out policy for legacy apps.
        if (showMenu) setLightsOn(true);
    }

    @Override
    public void setImeWindowStatus(IBinder token, int vis, int backDisposition) {
        boolean altBack = (backDisposition == InputMethodService.BACK_DISPOSITION_WILL_DISMISS)
            || ((vis & InputMethodService.IME_VISIBLE) != 0);

        // If IME shows and heads up gravity is at the bottom, move it to the top.
        if (mImeIsShowing != altBack) {
            mImeIsShowing = altBack;
            updateHeadsUpPosition(mStatusBarShows);
        }

        setNavigationIconHints(
                altBack ? (mNavigationIconHints | NAVIGATION_HINT_BACK_ALT)
                        : (mNavigationIconHints & ~NAVIGATION_HINT_BACK_ALT));
        if (mQS != null) mQS.setImeWindowStatus(vis > 0);
    }

    @Override
    public void setHardKeyboardStatus(boolean available, boolean enabled) {}

    @Override
    protected void tick(IBinder key, StatusBarNotification n, boolean firstTime) {
        // no ticking in lights-out mode, except if halo is active
        if (!areLightsOn() && !mHaloActive) return;

        // no ticking in Setup
        if (!isDeviceProvisioned()) return;

        // not for you
        if (!notificationIsForCurrentUser(n)) return;

        // Show the ticker if one is requested. Also don't do this
        // until status bar window is attached to the window manager,
        // because...  well, what's the point otherwise?  And trying to
        // run a ticker without being attached will crash!
        if (n.getNotification().tickerText != null && mStatusBarContainer.getWindowToken() != null) {
            if (0 == (mDisabled & (StatusBarManager.DISABLE_NOTIFICATION_ICONS
                            | StatusBarManager.DISABLE_NOTIFICATION_TICKER))) {
                boolean blacklisted = false;
                boolean foreground = false;

                if (mHoverState == HOVER_ENABLED) {
                    // don't pass notifications that run in Hover to Ticker
                    try {
                        blacklisted = getNotificationManager().isPackageAllowedForHover(n.getPackageName());
                    } catch (android.os.RemoteException ex) {
                        // System is dead
                    }

                    // same foreground app? pass to ticker, hover doesn't show this one
                    foreground = n.getPackageName().equals(
                           getNotificationHelperInstance().getForegroundPackageName());
                }
                if (!blacklisted | foreground | mHover.excludeTopmost()) mTicker.addEntry(n);
            }
        }
    }

    @Override
    public void animateStatusBarOut() {
        // ensure to not overload
        if (mStatusBarView.getVisibility() == View.VISIBLE) {
            mHandler.post(new Runnable() {
                public void run() {
                    mStatusBarView.setVisibility(View.GONE);
                    mStatusBarView.startAnimation(loadAnim(com.android.internal.R.anim.push_up_out, null));
                }
            });
        }
    }

    @Override
    public void animateStatusBarIn() {
        // ensure to not overload
        if (mStatusBarView.getVisibility() == View.GONE) {
            mHandler.post(new Runnable() {
                public void run() {
                    mStatusBarView.setVisibility(View.VISIBLE);
                    mStatusBarView.startAnimation(loadAnim(com.android.internal.R.anim.push_down_in, null));
                }
            });
        }
    }

    private class MyTicker extends Ticker {
        private boolean hasTicked = false;

        MyTicker(Context context, View sb) {
            super(context, sb);
        }

        @Override
        public void tickerStarting() {
	    if (!mHaloActive) {
                if (mHoverState == HOVER_DISABLED) mTicking = true;
                mStatusBarContents.setVisibility(View.GONE);
                if (mClockStyle == CLOCK_STYLE_CENTERED && mShowClock) {
                mClockView.setVisibility(View.INVISIBLE);
                mClockView.startAnimation(
                        loadAnim(com.android.internal.R.anim.push_up_out, null));
                }
                mTickerView.setVisibility(View.VISIBLE);
                mTickerView.startAnimation(loadAnim(com.android.internal.R.anim.push_up_in, null));
                mStatusBarContents.startAnimation(loadAnim(com.android.internal.R.anim.push_up_out, null));
                hasTicked = true;
            }
        }

        @Override
        public void tickerDone() {
	    if (!mHaloActive) {
                if (!hasTicked) return;
                mStatusBarContents.setVisibility(View.VISIBLE);
                mTickerView.setVisibility(View.GONE);
                if (mClockStyle == CLOCK_STYLE_CENTERED && mShowClock) {
                mClockView.setVisibility(View.VISIBLE);
                mClockView.startAnimation(
                        loadAnim(com.android.internal.R.anim.push_down_in, null));
                }
                mStatusBarContents.startAnimation(loadAnim(com.android.internal.R.anim.push_down_in, null));
                mTickerView.startAnimation(loadAnim(com.android.internal.R.anim.push_down_out,
                            mTickingDoneListener));
                hasTicked = false;
	    }
        }

        public void tickerHalting() {
            if (mStatusBarContents.getVisibility() != View.VISIBLE) {
                mStatusBarContents.setVisibility(View.VISIBLE);
                mStatusBarContents
                        .startAnimation(loadAnim(com.android.internal.R.anim.fade_in, null));
            }
            if (mClockStyle == CLOCK_STYLE_CENTERED && mShowClock) {
                mClockView.setVisibility(View.VISIBLE);
                mClockView.startAnimation(
                        loadAnim(com.android.internal.R.anim.fade_in, null));
            }
            mTickerView.setVisibility(View.GONE);

            // we do not animate the ticker away at this point, just get rid of it (b/6992707)
            if (!mHaloActive) {
                mStatusBarContents.setVisibility(View.VISIBLE);
                mTickerView.setVisibility(View.GONE);
                mStatusBarContents.startAnimation(loadAnim(com.android.internal.R.anim.fade_in, null));
                // we do not animate the ticker away at this point, just get rid of it (b/6992707)
            }
        }
    }

    Animation.AnimationListener mTickingDoneListener = new Animation.AnimationListener() {;
        public void onAnimationEnd(Animation animation) {
            mTicking = false;
        }
        public void onAnimationRepeat(Animation animation) {
        }
        public void onAnimationStart(Animation animation) {
        }
    };

    private Animation loadAnim(int id, Animation.AnimationListener listener) {
        Animation anim = AnimationUtils.loadAnimation(mContext, id);
        if (listener != null) {
            anim.setAnimationListener(listener);
        }
        return anim;
    }

    public static String viewInfo(View v) {
        return "[(" + v.getLeft() + "," + v.getTop() + ")(" + v.getRight() + "," + v.getBottom()
                + ") " + v.getWidth() + "x" + v.getHeight() + "]";
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mQueueLock) {
            pw.println("Current Status Bar state:");
            pw.println("  mExpandedVisible=" + mExpandedVisible
                    + ", mTrackingPosition=" + mTrackingPosition);
            pw.println("  mTicking=" + mTicking);
            pw.println("  mTracking=" + mTracking);
            pw.println("  mDisplayMetrics=" + mDisplayMetrics);
            pw.println("  mPile: " + viewInfo(mPile));
            pw.println("  mTickerView: " + viewInfo(mTickerView));
            pw.println("  mScrollView: " + viewInfo(mScrollView)
                    + " scroll " + mScrollView.getScrollX() + "," + mScrollView.getScrollY());
        }

        pw.print("  mInteractingWindows="); pw.println(mInteractingWindows);
        pw.print("  mStatusBarWindowState=");
        pw.println(windowStateToString(mStatusBarWindowState));
        pw.print("  mStatusBarMode=");
        pw.println(BarTransitions.modeToString(mStatusBarMode));
        dumpBarTransitions(pw, "mStatusBarView", mStatusBarView.getBarTransitions());
        if (mNavigationBarView != null) {
            pw.print("  mNavigationBarWindowState=");
            pw.println(windowStateToString(mNavigationBarWindowState));
            pw.print("  mNavigationBarMode=");
            pw.println(BarTransitions.modeToString(mNavigationBarMode));
            dumpBarTransitions(pw, "mNavigationBarView", mNavigationBarView.getBarTransitions());
            dumpBarTransitions(pw, "mStatusBarBlocker",
                    mNavigationBarView.getStatusBarBlockerTransitions());
        }

        pw.print("  mNavigationBarView=");
        if (mNavigationBarView == null) {
            pw.println("null");
        } else {
            mNavigationBarView.dump(fd, pw, args);
        }

        pw.println("  Panels: ");
        if (mNotificationPanel != null) {
            pw.println("    mNotificationPanel=" +
                mNotificationPanel + " params=" + mNotificationPanel.getLayoutParams().debug(""));
            pw.print  ("      ");
            mNotificationPanel.dump(fd, pw, args);
        }
        if (mSettingsPanel != null) {
            pw.println("    mSettingsPanel=" +
                mSettingsPanel + " params=" + mSettingsPanel.getLayoutParams().debug(""));
            pw.print  ("      ");
            mSettingsPanel.dump(fd, pw, args);
        }

        if (DUMPTRUCK) {
            synchronized (mNotificationData) {
                int N = mNotificationData.size();
                pw.println("  notification icons: " + N);
                for (int i=0; i<N; i++) {
                    NotificationData.Entry e = mNotificationData.get(i);
                    pw.println("    [" + i + "] key=" + e.key + " icon=" + e.icon);
                    StatusBarNotification n = e.notification;
                    pw.println("         pkg=" + n.getPackageName() + " id=" + n.getId() + " score=" + n.getScore());
                    pw.println("         notification=" + n.getNotification());
                    pw.println("         tickerText=\"" + n.getNotification().tickerText + "\"");
                }
            }

            int N = mStatusIcons.getChildCount();
            pw.println("  system icons: " + N);
            for (int i=0; i<N; i++) {
                StatusBarIconView ic = (StatusBarIconView) mStatusIcons.getChildAt(i);
                pw.println("    [" + i + "] icon=" + ic);
            }

            if (false) {
                pw.println("see the logcat for a dump of the views we have created.");
                // must happen on ui thread
                mHandler.post(new Runnable() {
                        public void run() {
                            mStatusBarView.getLocationOnScreen(mAbsPos);
                            Log.d(TAG, "mStatusBarView: ----- (" + mAbsPos[0] + "," + mAbsPos[1]
                                    + ") " + mStatusBarView.getWidth() + "x"
                                    + getStatusBarHeight());
                            mStatusBarView.debug();
                        }
                    });
            }
        }

        if (DEBUG_GESTURES) {
            pw.print("  status bar gestures: ");
            mGestureRec.dump(fd, pw, args);
        }

        if (MSimTelephonyManager.getDefault().isMultiSimEnabled()) {
            for(int i=0; i < MSimTelephonyManager.getDefault().getPhoneCount(); i++) {
                mMSimNetworkController.dump(fd, pw, args, i);
            }
        } else {
            mNetworkController.dump(fd, pw, args);
        }
    }

    private static void dumpBarTransitions(PrintWriter pw, String var, BarTransitions transitions) {
        pw.print("  "); pw.print(var); pw.print(".BarTransitions.mMode=");
        pw.println(BarTransitions.modeToString(transitions.getMode()));
    }

    @Override
    public void createAndAddWindows() {
        addStatusBarWindow();
    }

    private void addStatusBarWindow() {
        // Put up the view
        final int height = getStatusBarHeight();

        // Now that the status bar window encompasses the sliding panel and its
        // translucent backdrop, the entire thing is made TRANSLUCENT and is
        // hardware-accelerated.
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height,
                WindowManager.LayoutParams.TYPE_STATUS_BAR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                    | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);

        lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;

        lp.gravity = getStatusBarGravity();
        lp.setTitle("StatusBar");
        lp.packageName = mContext.getPackageName();

        makeStatusBarView();
        mStatusBarContainer.addView(mStatusBarWindow);
        mWindowManager.addView(mStatusBarContainer, lp);
    }

    void setNotificationIconVisibility(boolean visible, int anim) {
        int old = mNotificationIcons.getVisibility();
        int v = visible ? View.VISIBLE : View.INVISIBLE;
        if (old != v) {
            mNotificationIcons.setVisibility(v);
            mNotificationIcons.startAnimation(loadAnim(anim, null));
        }
    }

    void updateExpandedInvisiblePosition() {
        mTrackingPosition = -mDisplayMetrics.heightPixels;
    }

    static final float saturate(float a) {
        return a < 0f ? 0f : (a > 1f ? 1f : a);
    }

    @Override
    protected int getExpandedViewMaxHeight() {
        return mDisplayMetrics.heightPixels - mNotificationPanelMarginBottomPx;
    }

    @Override
    protected boolean isNotificationPanelFullyVisible() {
        return mExpandedVisible &&
                (!mHasFlipSettings || mScrollView.getVisibility() == View.VISIBLE);
    }

    @Override
    protected boolean isTrackingNotificationPanel() {
        return mNotificationPanel.isTracking();
    }

    @Override
    public void updateExpandedViewPos(int thingy) {
        if (SPEW) Log.v(TAG, "updateExpandedViewPos");

        // on larger devices, the notification panel is propped open a bit
        mNotificationPanel.setMinimumHeight(
                (int)(mNotificationPanelMinHeightFrac * mCurrentDisplaySize.y));

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mNotificationPanel.getLayoutParams();
        lp.gravity = mNotificationPanelGravity;
        lp.setMarginStart(mNotificationPanelMarginPx);
        mNotificationPanel.setLayoutParams(lp);

        if (mSettingsPanel != null) {
            lp = (FrameLayout.LayoutParams) mSettingsPanel.getLayoutParams();
            lp.gravity = mSettingsPanelGravity;
            lp.setMarginEnd(mNotificationPanelMarginPx);
            mSettingsPanel.setLayoutParams(lp);
        }

        if (mHeadsUpNotificationView != null) {
            mHeadsUpNotificationView.setMargin(mNotificationPanelMarginPx);
            mPile.getLocationOnScreen(mPilePosition);
            mHeadsUpVerticalOffset = mPilePosition[1] - mNaturalBarHeight;
        }

        updateCarrierLabelVisibility(false);
    }

    // called by makeStatusbar and also by PhoneStatusBarView
    void updateDisplaySize() {
        mDisplay.getMetrics(mDisplayMetrics);
        mDisplay.getSize(mCurrentDisplaySize);
        if (DEBUG_GESTURES) {
            mGestureRec.tag("display",
                    String.format("%dx%d", mDisplayMetrics.widthPixels, mDisplayMetrics.heightPixels));
        }
    }

    private View.OnClickListener mClearButtonListener = new View.OnClickListener() {
        public void onClick(View v) {
            synchronized (mNotificationData) {
                // animate-swipe all dismissable notifications
                int numChildren = mPile.getChildCount();

                int scrollTop = mScrollView.getScrollY();
                int scrollBottom = scrollTop + mScrollView.getHeight();
                final ArrayList<View> snapshot = new ArrayList<View>(numChildren);
                for (int i=0; i<numChildren; i++) {
                    final View child = mPile.getChildAt(i);
                    if (mPile.canChildBeDismissed(GESTURE_POSITIVE, child) && child.getBottom() > scrollTop &&
                            child.getTop() < scrollBottom) {
                        snapshot.add(child);
                    }
                }

                if (snapshot.isEmpty()) {
                    maybeCollapseAfterNotificationRemoval(true);
                    return;
                }

                // Decrease the delay for every row we animate to give the sense of
                // accelerating the swipes
                final int ROW_DELAY_DECREMENT = 10;
                int currentDelay = 140;
                int totalDelay = 0;

                // Set the shade-animating state to avoid doing other work, in
                // particular layout and redrawing, during all of these animations.
                mPile.setViewRemoval(false);

                View sampleView = snapshot.get(0);
                int width = sampleView.getWidth();
                final int dir = sampleView.isLayoutRtl() ? -1 : +1;
                final int velocity = dir * width * 8; // 1000/8 = 125 ms duration
                for (final View _v : snapshot) {
                    mHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            mPile.dismissRowAnimated(_v, velocity);
                        }
                    }, totalDelay);
                    currentDelay = Math.max(50, currentDelay - ROW_DELAY_DECREMENT);
                    totalDelay += currentDelay;
                }

                // After ending all animations, tell the service to remove the
                // notifications, which will trigger collapsing the shade
                final View lastEntry = snapshot.get(snapshot.size() - 1);
                mPile.runOnDismiss(lastEntry, mNotifyClearAll);
            }
        }
    };

    public void startActivityDismissingKeyguard(Intent intent, boolean onlyProvisioned) {
        if (onlyProvisioned && !isDeviceProvisioned()) return;
        try {
            // Dismiss the lock screen when Settings starts.
            ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
        } catch (RemoteException e) {
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mContext.startActivityAsUser(intent, new UserHandle(UserHandle.USER_CURRENT));
        animateCollapsePanels();
    }

    private View.OnClickListener mReminderButtonListener = new View.OnClickListener() {
        public void onClick(View v) {
            startActivityDismissingKeyguard(new Intent(
                    "com.android.systemui.timedialog.ReminderTimeDialog"), true);
        }
    };

    private View.OnLongClickListener mReminderLongButtonListener = new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
             String message = mShared.getString("message", null);
             if (message != null) {
                Intent intent = new Intent(
                        "com.android.systemui.timedialog.ReminderTimeDialog");
                intent.putExtra("type", "clear");
                startActivityDismissingKeyguard(intent, true);
            }
            return true;
        }
    };

    private View.OnClickListener mSettingsButtonListener = new View.OnClickListener() {
        public void onClick(View v) {
            if (mHasSettingsPanel) {
                animateExpandSettingsPanel(true);
            } else {
                startActivityDismissingKeyguard(
                        new Intent(android.provider.Settings.ACTION_SETTINGS), true);
            }
        }
    };

    private View.OnClickListener mAddTileButtonListener = new View.OnClickListener() {
        public void onClick(View v) {
            Intent intent = new Intent();
            intent.setClassName("com.android.settings",
                    "com.android.settings.Settings$QuickSettingsConfigActivity");
            startActivityDismissingKeyguard(intent, true);
        }
    };

    private View.OnClickListener mHoverButtonListener = new View.OnClickListener() {
        public void onClick(View v) {
            if (mHoverCling != null) {
                boolean firstRun = mHoverCling.loadSetting();
                // we're pushing the button, so use inverse logic
                mHoverCling.hoverChanged(mHoverState == HOVER_DISABLED);
                if (firstRun) {
                    animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE);
                }
            }
            Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.HOVER_STATE,
                            mHoverState != HOVER_DISABLED ? HOVER_DISABLED : HOVER_ENABLED);
            updateHoverState();
        }
    };

    private View.OnClickListener mNotificationButtonListener = new View.OnClickListener() {
        public void onClick(View v) {
            animateExpandNotificationsPanel();
        }
    };

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) Log.v(TAG, "onReceive: " + intent);
            String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                int flags = CommandQueue.FLAG_EXCLUDE_NONE;
                if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                    String reason = intent.getStringExtra("reason");
                    if (reason != null && reason.equals(SYSTEM_DIALOG_REASON_RECENT_APPS)) {
                        flags |= CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL;
                    }
                }
                animateCollapsePanels(flags);
            }
            else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                mScreenOn = false;
                // no waiting!
                makeExpandedInvisible();
                notifyNavigationBarScreenOn(false);
                notifyHeadsUpScreenOn(false);
                resetHeadsUpSnoozeTimer();
                finishBarAnimations();
                // detach hover when screen is turned off
                if (mHover.isShowing()) mHover.dismissHover(false, true);
            }
            else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                mScreenOn = true;
                // work around problem where mDisplay.getRotation() is not stable while screen is off (bug 7086018)
                repositionNavigationBar();
                notifyNavigationBarScreenOn(true);
            } else if (SCHEDULE_REMINDER_NOTIFY.equals(action)) {
                updateAndNotifyReminder();
            } else if (ACTION_DEMO.equals(action)) {
                Bundle bundle = intent.getExtras();
                if (bundle != null) {
                    String command = bundle.getString("command", "").trim().toLowerCase();
                    if (command.length() > 0) {
                        try {
                            dispatchDemoCommand(command, bundle);
                        } catch (Throwable t) {
                            Log.w(TAG, "Error running demo command, intent=" + intent, t);
                        }
                    }
                }
            }
            else if (CUSTOM_LOCKSCREEN_STATE.equals(action)) {
                boolean showing = intent.getBooleanExtra("showing", false);
                if (null != mNavigationBarView) {
                    mNavigationBarView.getBarTransitions().applyTransparent(showing);
                }
            }
        }
    };

    // SystemUIService notifies SystemBars of configuration changes, which then calls down here
    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig); // calls refreshLayout

        if (DEBUG) {
            Log.v(TAG, "configuration changed: " + mContext.getResources().getConfiguration());
        }
        updateDisplaySize(); // populates mDisplayMetrics

        updateResources();
        repositionNavigationBar();
        updateExpandedViewPos(EXPANDED_LEAVE_ALONE);
        updateShowSearchHoldoff();
    }

    @Override
    public void userSwitched(int newUserId) {
        if (MULTIUSER_DEBUG) mNotificationPanelDebugText.setText("USER " + newUserId);
        animateCollapsePanels();
        updateNotificationIcons();
        resetUserSetupObserver();
        updateSettings();
        if (mNavigationBarView != null) {
            mNavigationBarView.updateSettings();
        }
        super.userSwitched(newUserId);
    }

    private void updateAndNotifyReminder() {
        boolean reminderActive = mShared.getBoolean("scheduled", false);
        if (!reminderActive) {
            mShared.edit().putBoolean("scheduled", true).commit();
            enableOrDisableReminder();
        } else {
            if (mReminderEnabled) {
                Intent notify = new Intent();
                notify.setAction("com.android.systemui.POST_REMINDER_NOTIFY");
                mContext.sendBroadcast(notify);
            }
            // Just clear the reminder. Let the user decide not to dismiss the
            // notification, and if they don't the reminder will exist on reboot.
            clearReminder();
        }
    }

    private void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();
        int autoBrightnessSetting = Settings.System.getIntForUser(
                resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0, mCurrentUserId);

        if (autoBrightnessSetting == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
            mBrightnessControl = false;
        } else {
            mBrightnessControl = Settings.System.getIntForUser(resolver,
                    Settings.System.STATUS_BAR_BRIGHTNESS_CONTROL, 0, mCurrentUserId) == 1;
        }

        int batteryStyle = Settings.System.getIntForUser(resolver,
                Settings.System.STATUS_BAR_BATTERY, 0, mCurrentUserId);
        BatteryMeterMode mode = BatteryMeterMode.BATTERY_METER_ICON_PORTRAIT;
        switch (batteryStyle) {
            case 2:
                mode = BatteryMeterMode.BATTERY_METER_CIRCLE;
                break;

            case 4:
                mode = BatteryMeterMode.BATTERY_METER_GONE;
                break;

            case 5:
                mode = BatteryMeterMode.BATTERY_METER_ICON_LANDSCAPE;
                break;

            case 6:
                mode = BatteryMeterMode.BATTERY_METER_TEXT;
                break;

            default:
                break;
        }

        boolean showPercent = Settings.System.getIntForUser(resolver,
                Settings.System.STATUS_BAR_BATTERY_SHOW_PERCENT, 0, mCurrentUserId) == 1;

        mBatteryView.setMode(mode);
        mBatteryController.onBatteryMeterModeChanged(mode);
        mBatteryView.setShowPercent(showPercent);
        mBatteryController.onBatteryMeterShowPercent(showPercent);

        mDockBatteryView.setMode(mode);
        mDockBatteryController.onBatteryMeterModeChanged(mode);
        mDockBatteryView.setShowPercent(showPercent);
        mDockBatteryController.onBatteryMeterShowPercent(showPercent);

        if (mNavigationBarView != null) {
            boolean navLeftInLandscape = Settings.System.getInt(resolver,
                    Settings.System.NAVBAR_LEFT_IN_LANDSCAPE, 0) == 1;
            mNavigationBarView.setLeftInLandscape(navLeftInLandscape);
        }

        int signalStyle = Settings.System.getIntForUser(resolver,
                Settings.System.STATUS_BAR_SIGNAL_TEXT,
                SignalClusterView.STYLE_NORMAL, mCurrentUserId);
        if (MSimTelephonyManager.getDefault().isMultiSimEnabled()) {
            mMSimSignalClusterView.setStyle(signalStyle);
        } else {
            mSignalClusterView.setStyle(signalStyle);
            if (mSignalTextView != null) {
                mSignalTextView.setStyle(signalStyle);
            }
        }
    }

    private void recentsLongPress() {
        int navbarRecentLongPress = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.NAVBAR_RECENT_LONG_PRESS, 0, mCurrentUserId);
        switch(navbarRecentLongPress) {
        case 0:
            break;
        case 1:
            toggleLastApp();
            break;
        case 2:
            toggleScreenshot();
            break;
        case 3:
            toggleKillApp();
            break;
        case 4:
            toggleNotificationShade();
            break;
        case 5:
            toggleQSShade();
            break;
        case 6:
            try {
                IWindowManager windowManagerService = IWindowManager.Stub.asInterface(
                    ServiceManager.getService(Context.WINDOW_SERVICE));
                windowManagerService.toggleGlobalMenu();
            } catch (RemoteException e) {
            }
            break;
        }
    }

    private void resetUserSetupObserver() {
        mContext.getContentResolver().unregisterContentObserver(mUserSetupObserver);
        mUserSetupObserver.onChange(false);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.USER_SETUP_COMPLETE), true,
                mUserSetupObserver,
                mCurrentUserId);
    }

    private void setHeadsUpVisibility(boolean vis) {
        if (DEBUG) Log.v(TAG, (vis ? "showing" : "hiding") + " heads up window");
        if (mHeadsUpNotificationViewAttached) {
            mHeadsUpNotificationView.setVisibility(vis ? View.VISIBLE : View.GONE);
            if (!vis) {
                if (DEBUG) Log.d(TAG, "setting heads up entry to null");
                mInterruptingNotificationEntry = null;
                mHeadsUpPackageName = null;
            }
        }
    }

    public void animateHeadsUp(boolean animateInto, float frac) {
        if (mHeadsUpNotificationView == null) return;
        frac = frac / 0.4f;
        frac = frac < 1.0f ? frac : 1.0f;
        float alpha = 1.0f - frac;
        float offset = mHeadsUpVerticalOffset * frac;
        offset = animateInto ? offset : 0f;
        mHeadsUpNotificationView.setAlpha(alpha);
        mHeadsUpNotificationView.setY(offset);
    }

    public void onHeadsUpDismissed(boolean direction) {
        if (mInterruptingNotificationEntry == null) return;
        hideHeadsUp();
        // If direction == true we know that the notification
        // was dismissed to the right. So we just hide it that
        // the notification will stay in our notification
        // drawer. Left swipe as usual dismisses the notification
        // completely if the notification is clearable.
        if (mHeadsUpNotificationView.isClearable() && !direction) {
            try {
                mBarService.onNotificationClear(
                        mInterruptingNotificationEntry.notification.getPackageName(),
                        mInterruptingNotificationEntry.notification.getTag(),
                        mInterruptingNotificationEntry.notification.getId());
            } catch (android.os.RemoteException ex) {
                // oh well
            }
        }
    }

    private static void copyNotifications(ArrayList<Pair<IBinder, StatusBarNotification>> dest,
            NotificationData source) {
        int N = source.size();
        for (int i = 0; i < N; i++) {
            NotificationData.Entry entry = source.get(i);
            dest.add(Pair.create(entry.key, entry.notification));
        }
    }

    private void recreateStatusBar() {
        mRecreating = true;
        
        removeHeadsUpView();

        mStatusBarContainer.removeAllViews();
        mStatusBarContainer.clearDisappearingChildren();

        // extract icons from the soon-to-be recreated viewgroup.
        int nIcons = mStatusIcons.getChildCount();
        ArrayList<StatusBarIcon> icons = new ArrayList<StatusBarIcon>(nIcons);
        ArrayList<String> iconSlots = new ArrayList<String>(nIcons);
        for (int i = 0; i < nIcons; i++) {
            StatusBarIconView iconView = (StatusBarIconView)mStatusIcons.getChildAt(i);
            icons.add(iconView.getStatusBarIcon());
            iconSlots.add(iconView.getStatusBarSlot());
        }

        removeAllViews(mStatusBarWindow);

        // extract notifications.
        int nNotifs = mNotificationData.size();
        ArrayList<Pair<IBinder, StatusBarNotification>> notifications =
                new ArrayList<Pair<IBinder, StatusBarNotification>>(nNotifs);
        copyNotifications(notifications, mNotificationData);
        mNotificationData.clear();

        makeStatusBarView();
        repositionNavigationBar();
        if (mNavigationBarView != null) {
            mNavigationBarView.updateResources(getNavbarThemedResources());
        }

        rebuildRecentsScreen();

        addHeadsUpView();

        // recreate StatusBarIconViews.
        for (int i = 0; i < nIcons; i++) {
            StatusBarIcon icon = icons.get(i);
            String slot = iconSlots.get(i);
            addIcon(slot, i, i, icon);
        }

        // update the clock location
        updateClockLocation();

        // recreate notifications.
        for (int i = 0; i < nNotifs; i++) {
            Pair<IBinder, StatusBarNotification> notifData = notifications.get(i);
            addNotificationViews(createNotificationViews(notifData.first, notifData.second));
        }

        updateSettings();
        setAreThereNotifications();

        mStatusBarContainer.addView(mStatusBarWindow);

        updateExpandedViewPos(EXPANDED_LEAVE_ALONE);

        // Workaround to update drawable
        // resources of pie on theme changes
        attachPieContainer(!isPieEnabled());
        attachPieContainer(isPieEnabled());

        restorePieTriggerMask();

        checkBarModes();
        mRecreating = false;

        updateHalo();
    }

    private void removeAllViews(ViewGroup parent) {
        int N = parent.getChildCount();
        for (int i = 0; i < N; i++) {
            View child = parent.getChildAt(i);
            if (child instanceof ViewGroup) {
                removeAllViews((ViewGroup) child);
            }
        }
        parent.removeAllViews();
    }

    /**
     * Reload some of our resources when the configuration changes.
     *
     * We don't reload everything when the configuration changes -- we probably
     * should, but getting that smooth is tough.  Someday we'll fix that.  In the
     * meantime, just update the things that we know change.
     */
    void updateResources() {
        final Context context = mContext;
        final Resources res = context.getResources();

        // detect theme change.
        ThemeConfig newTheme = res.getConfiguration().themeConfig;
        int uiThemeMode = res.getConfiguration().uiThemeMode;
        if (newTheme != null &&
                (mCurrentTheme == null || !mCurrentTheme.equals(newTheme))) {
            mCurrentTheme = (ThemeConfig)newTheme.clone();
            recreateStatusBar();

        if (uiThemeMode != mCurrUiThemeMode) {
            mCurrUiThemeMode = uiThemeMode;
            //recreateStatusBar();
        }

        if (mClearButton instanceof TextView) {
                ((TextView)mClearButton).setText(context.getText(R.string.status_bar_clear_all_button));
            }
            loadDimens();
        }

        // Update the QuickSettings container
        if (mQS != null) mQS.updateResources();
        if (mRibbonQS != null)
            mRibbonQS.updateResources();
        if (mNavigationBarView != null)  {
            mNavigationBarView.updateResources(getNavbarThemedResources());
            updateSearchPanel();
        }

        // check for orientation change and update only the container layout
        // for all other configuration changes update complete QS
        int orientation = res.getConfiguration().orientation;
        if (orientation != mCurrOrientation) {
            mCurrOrientation = orientation;
            // Update the settings container
            if (mSettingsContainer != null) {
                mSettingsContainer.updateResources();
            }

            if (mReminderEnabled) {
                toggleVisibleFlipper();
                if (mExpandedVisible) {
                    // Reset to first view since we're expanded and start flipping again
                    toggleReminderFlipper(true);
                }
            }
        } else {
            if (mQS != null) {
                mQS.updateResources();
            }
        }
    }

    protected void loadDimens() {
        final Resources res = mContext.getResources();

        mNaturalBarHeight = res.getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);

        int newIconSize = res.getDimensionPixelSize(
            com.android.internal.R.dimen.status_bar_icon_size);
        int newIconHPadding = res.getDimensionPixelSize(
            R.dimen.status_bar_icon_padding);

        if (newIconHPadding != mIconHPadding || newIconSize != mIconSize) {
//            Log.d(TAG, "size=" + newIconSize + " padding=" + newIconHPadding);
            mIconHPadding = newIconHPadding;
            mIconSize = newIconSize;
            //reloadAllNotificationIcons(); // reload the tray
        }

        mEdgeBorder = res.getDimensionPixelSize(R.dimen.status_bar_edge_ignore);

        mSelfExpandVelocityPx = res.getDimension(R.dimen.self_expand_velocity);
        mSelfCollapseVelocityPx = res.getDimension(R.dimen.self_collapse_velocity);
        mFlingExpandMinVelocityPx = res.getDimension(R.dimen.fling_expand_min_velocity);
        mFlingCollapseMinVelocityPx = res.getDimension(R.dimen.fling_collapse_min_velocity);

        mCollapseMinDisplayFraction = res.getFraction(R.dimen.collapse_min_display_fraction, 1, 1);
        mExpandMinDisplayFraction = res.getFraction(R.dimen.expand_min_display_fraction, 1, 1);

        mExpandAccelPx = res.getDimension(R.dimen.expand_accel);
        mCollapseAccelPx = res.getDimension(R.dimen.collapse_accel);

        mFlingGestureMaxXVelocityPx = res.getDimension(R.dimen.fling_gesture_max_x_velocity);

        mFlingGestureMaxOutputVelocityPx = res.getDimension(R.dimen.fling_gesture_max_output_velocity);

        mNotificationPanelMarginBottomPx
            = (int) res.getDimension(R.dimen.notification_panel_margin_bottom);
        mNotificationPanelMarginPx
            = (int) res.getDimension(R.dimen.notification_panel_margin_left);
        mNotificationPanelGravity = res.getInteger(R.integer.notification_panel_layout_gravity);
        if (mNotificationPanelGravity <= 0) {
            mNotificationPanelGravity = Gravity.START | Gravity.TOP;
        }
        mSettingsPanelGravity = res.getInteger(R.integer.settings_panel_layout_gravity);
        Log.d(TAG, "mSettingsPanelGravity = " + mSettingsPanelGravity);
        if (mSettingsPanelGravity <= 0) {
            mSettingsPanelGravity = Gravity.END | Gravity.TOP;
        }

        mCarrierLabelHeight = res.getDimensionPixelSize(R.dimen.carrier_label_height);
        mNotificationHeaderHeight = res.getDimensionPixelSize(R.dimen.notification_panel_header_height);
        mPeekHeight = res.getDimensionPixelSize(R.dimen.peek_height);
        mNotificationPanelMinHeightFrac = res.getFraction(R.dimen.notification_panel_min_height_frac, 1, 1);
        if (mNotificationPanelMinHeightFrac < 0f || mNotificationPanelMinHeightFrac > 1f) {
            mNotificationPanelMinHeightFrac = 0f;
        }

        mRowHeight =  res.getDimensionPixelSize(R.dimen.default_notification_row_min_height);

        if (false) Log.v(TAG, "updateResources");
    }

    @Override
    public void setButtonDrawable(int buttonId, int iconId) {
        mNavigationBarView.setButtonDrawable(buttonId, iconId);
    }

    //
    // tracing
    //

    void postStartTracing() {
        mHandler.postDelayed(mStartTracing, 3000);
    }

    void vibrate() {
        android.os.Vibrator vib = (android.os.Vibrator)mContext.getSystemService(
                Context.VIBRATOR_SERVICE);
        vib.vibrate(250);
    }

    Runnable mStartTracing = new Runnable() {
        public void run() {
            vibrate();
            SystemClock.sleep(250);
            Log.d(TAG, "startTracing");
            android.os.Debug.startMethodTracing("/data/statusbar-traces/trace");
            mHandler.postDelayed(mStopTracing, 10000);
        }
    };

    Runnable mStopTracing = new Runnable() {
        public void run() {
            android.os.Debug.stopMethodTracing();
            Log.d(TAG, "stopTracing");
            vibrate();
        }
    };

    @Override
    protected void haltTicker() {
        mTicker.halt();
    }

    @Override
    protected boolean shouldDisableNavbarGestures() {
        return !isDeviceProvisioned()
                || mExpandedVisible
                || (mNavigationBarView != null && mNavigationBarView.isInEditMode())
                || (mDisabled & StatusBarManager.DISABLE_SEARCH) != 0;
    }

    private static class FastColorDrawable extends Drawable {
        private final int mColor;

        public FastColorDrawable(int color) {
            mColor = 0xff000000 | color;
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawColor(mColor, PorterDuff.Mode.SRC);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }

        @Override
        public void setBounds(int left, int top, int right, int bottom) {
        }

        @Override
        public void setBounds(Rect bounds) {
        }
    }

    @Override
    public void destroy() {
        super.destroy();
        if (mStatusBarWindow != null) {
            mWindowManager.removeViewImmediate(mStatusBarWindow);
        }
        if (mNavigationBarView != null) {
            mWindowManager.removeViewImmediate(mNavigationBarView);
        }
        mContext.unregisterReceiver(mBroadcastReceiver);
    }

    private boolean mDemoModeAllowed;
    private boolean mDemoMode;
    private DemoStatusIcons mDemoStatusIcons;

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        if (!mDemoModeAllowed) {
            mDemoModeAllowed = Settings.Global.getInt(mContext.getContentResolver(),
                    "sysui_demo_allowed", 0) != 0;
        }
        if (!mDemoModeAllowed) return;
        if (command.equals(COMMAND_ENTER)) {
            mDemoMode = true;
        } else if (command.equals(COMMAND_EXIT)) {
            mDemoMode = false;
            checkBarModes();
        } else if (!mDemoMode) {
            // automatically enter demo mode on first demo command
            dispatchDemoCommand(COMMAND_ENTER, new Bundle());
        }
        boolean modeChange = command.equals(COMMAND_ENTER) || command.equals(COMMAND_EXIT);
        if (modeChange || command.equals(COMMAND_CLOCK)) {
            mClockView.dispatchDemoCommand(command, args);
        }
        if (modeChange || command.equals(COMMAND_BATTERY)) {
            dispatchDemoCommandToView(command, args, R.id.battery);
            dispatchDemoCommandToView(command, args, R.id.dock_battery);
        }
        if (modeChange || command.equals(COMMAND_STATUS)) {
            if (mDemoStatusIcons == null) {
                mDemoStatusIcons = new DemoStatusIcons(mStatusIcons, mIconSize);
            }
            mDemoStatusIcons.dispatchDemoCommand(command, args);
        }
        if (mNetworkController != null && (modeChange || command.equals(COMMAND_NETWORK))) {
            mNetworkController.dispatchDemoCommand(command, args);
        }
        if (command.equals(COMMAND_BARS)) {
            String mode = args.getString("mode");
            int barMode = "opaque".equals(mode) ? MODE_OPAQUE :
                    "translucent".equals(mode) ? MODE_TRANSLUCENT :
                    "semi-transparent".equals(mode) ? MODE_SEMI_TRANSPARENT :
                    -1;
            if (barMode != -1) {
                boolean animate = true;
                if (mStatusBarView != null) {
                    mStatusBarView.getBarTransitions().transitionTo(barMode, animate);
                }
                if (mNavigationBarView != null) {
                    mNavigationBarView.getBarTransitions().transitionTo(barMode, animate);
                    mNavigationBarView.getStatusBarBlockerTransitions().transitionTo(
                            barMode, animate);
                }
            }
        }
    }

    private void dispatchDemoCommandToView(String command, Bundle args, int id) {
        if (mStatusBarView == null) return;
        View v = mStatusBarView.findViewById(id);
        if (v instanceof DemoMode) {
            ((DemoMode)v).dispatchDemoCommand(command, args);
        }
    }

    /**
     *  ContentObserver to watch for Quick Settings tiles changes
     * @author dvtonder
     *
     */
    private class TilesChangedObserver extends ContentObserver {
        public TilesChangedObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            onChange(selfChange, null);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri != null && uri.equals(Settings.System.getUriFor(
                    Settings.System.QS_QUICK_ACCESS))) {
                final ContentResolver resolver = mContext.getContentResolver();
                mHasQuickAccessSettings = Settings.System.getIntForUser(resolver,
                        Settings.System.QS_QUICK_ACCESS, 0, UserHandle.USER_CURRENT) == 1;
                if (mHasQuickAccessSettings) {
                    inflateRibbon();
                    mRibbonView.setVisibility(View.VISIBLE);
                } else {
                    cleanupRibbon();
                }
            } else if (uri != null && uri.equals(Settings.System.getUriFor(
                    Settings.System.NOTIFICATION_BRIGHTNESS_SLIDER))) {
                final ContentResolver resolver = mContext.getContentResolver();
                mBrightnessSliderEnabled = Settings.System.getBoolean(resolver,
                        Settings.System.NOTIFICATION_BRIGHTNESS_SLIDER, false);
                if (mBrightnessSliderEnabled) {
                    inflateBrightnessSlider();
                    mBrightnessView.setVisibility(View.VISIBLE);
                } else {
                    cleanupBrightnessSlider();
                }
            } else if (uri != null && uri.equals(Settings.System.getUriFor(
                    Settings.System.QS_QUICK_ACCESS_SIZE))) {
                if (mRibbonQS != null)
                    mRibbonQS.updateResources();
            } else if (uri != null && uri.equals(Settings.System.getUriFor(
                    Settings.System.QS_QUICK_ACCESS_LINKED))) {
                final ContentResolver resolver = mContext.getContentResolver();
                boolean layoutLinked = Settings.System.getIntForUser(resolver,
                        Settings.System.QS_QUICK_ACCESS_LINKED, 1, UserHandle.USER_CURRENT) == 1;
                if (mQuickAccessLayoutLinked != layoutLinked) {
                    // Reload the ribbon
                    mQuickAccessLayoutLinked = layoutLinked;
                    cleanupRibbon();
                    inflateRibbon();
                    mRibbonView.setVisibility(View.VISIBLE);
                }
            }  else if (uri != null && uri.equals(Settings.System.getUriFor(
                    Settings.System.QUICK_SETTINGS_RIBBON_TILES))) {
                    cleanupRibbon();
                    inflateRibbon();
                    mRibbonView.setVisibility(View.VISIBLE);
            } else if (uri != null && uri.equals(Settings.System.getUriFor(
                    Settings.System.QUICK_SETTINGS_SMALL_ICONS))) {
                if (mSettingsContainer != null) {
                    mQS.setupQuickSettings();
                    mSettingsContainer.updateResources();
                }
            } else if (mSettingsContainer != null) {
                mQS.setupQuickSettings();
                if (mQuickAccessLayoutLinked && mRibbonQS != null) {
                    mRibbonQS.setupQuickSettings();
                }
            }
        updateCustomHeaderStatus();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QUICK_SETTINGS_TILES),
                    false, this, UserHandle.USER_ALL);

            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QS_DYNAMIC_ALARM),
                    false, this, UserHandle.USER_ALL);

            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QS_DYNAMIC_BUGREPORT),
                    false, this, UserHandle.USER_ALL);

            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QS_DYNAMIC_DOCK_BATTERY),
                    false, this, UserHandle.USER_ALL);

            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QS_DYNAMIC_EQUALIZER),
                    false, this, UserHandle.USER_ALL);

            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QS_DYNAMIC_IME),
                    false, this, UserHandle.USER_ALL);

            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QS_DYNAMIC_USBTETHER),
                    false, this, UserHandle.USER_ALL);

            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QS_DYNAMIC_WIFI),
                    false, this, UserHandle.USER_ALL);

            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QS_QUICK_ACCESS),
                    false, this, UserHandle.USER_ALL);

            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QS_QUICK_ACCESS_SIZE),
                    false, this, UserHandle.USER_ALL);

            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QS_QUICK_ACCESS_LINKED),
                    false, this, UserHandle.USER_ALL);

            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NOTIFICATION_BRIGHTNESS_SLIDER),
                    false, this, UserHandle.USER_ALL);

            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QUICK_SETTINGS_RIBBON_TILES),
                    false, this, UserHandle.USER_ALL);

            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QUICK_SETTINGS_SMALL_ICONS),
                    false, this, UserHandle.USER_ALL);

        }
    }

    private void setNotificationAlpha() {
        if (mPile == null || mNotificationData == null) {
            return;
        }
        float notifAlpha = Settings.System.getFloatForUser(
            mContext.getContentResolver(), Settings.System.NOTIFICATION_ALPHA,
            0.0f, UserHandle.USER_CURRENT);
        int alpha = (int) ((1 - notifAlpha) * 255);
        int dataSize = mNotificationData.size();
        for (int i = 0; i < dataSize; i++) {
            Entry ent = mNotificationData.get(dataSize - i - 1);
            View expanded = ent.expanded;
            if (expanded !=null && expanded.getBackground() != null) {
                expanded.getBackground().setAlpha(alpha);
            }
            View expandedBig = ent.getBigContentView();
            if (expandedBig != null && expandedBig.getBackground() != null) {
                expandedBig.getBackground().setAlpha(alpha);
            }
            StatusBarIconView icon = ent.icon;
            if (icon !=null && icon.getBackground() != null) {
                icon.getBackground().setAlpha(alpha);
            }
        }
    }
}
