/*
* Copyright (C) 2014 MediaTek Inc.
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
/*
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

package com.android.server.wifi;

import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLING;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_FAILED;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLING;
/**
 * TODO:
 * Deprecate WIFI_STATE_UNKNOWN
 */
import static android.net.wifi.WifiManager.WIFI_STATE_UNKNOWN;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.backup.IBackupManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.DhcpResults;
import android.net.BaseDhcpStateMachine;
import android.net.DhcpStateMachine;
import android.net.Network;
import android.net.dhcp.DhcpClient;
import android.net.InterfaceConfiguration;
import android.net.IpReachabilityMonitor;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.NetworkFactory;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkRequest;
import android.net.NetworkUtils;
import android.net.RouteInfo;
import android.net.StaticIpConfiguration;
import android.net.TrafficStats;
import android.net.wifi.RssiPacketCountInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.ScanSettings;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiChannel;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConnectionStatistics;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiLinkLayerStats;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiSsid;
import android.net.wifi.WpsInfo;
import android.net.wifi.WpsResult;
import android.net.wifi.WpsResult.Status;
import android.net.wifi.p2p.IWifiP2pManager;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.WorkSource;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.util.TimeUtils;

import com.android.internal.R;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.net.NetlinkTracker;
import com.android.server.wifi.hotspot2.NetworkDetail;
import com.android.server.wifi.hotspot2.SupplicantBridge;
import com.android.server.wifi.hotspot2.Utils;
import com.android.server.wifi.p2p.WifiP2pServiceImpl;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.mediatek.common.MPlugin;
import com.mediatek.common.wifi.IWifiFwkExt;
import static android.net.wifi.WifiConfiguration.DISABLED_UNKNOWN_REASON;
import static android.net.wifi.WifiConfiguration.INVALID_NETWORK_ID;
import android.net.Network;
import android.net.wifi.PPPOEConfig;
import android.net.wifi.PPPOEInfo;
import android.net.wifi.HotspotClient;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.os.HandlerThread;
import android.os.Handler;
import android.hardware.display.DisplayManager;
import android.hardware.display.WifiDisplay;
import android.hardware.display.WifiDisplayStatus;

/// M: For ContentProviderClient leak aee warning
import com.mediatek.aee.ExceptionLog;

/// M: SmToString
import android.net.wifi.p2p.WifiP2pManager;

import android.telephony.ServiceState;


/**
 * Track the state of Wifi connectivity. All event handling is done here,
 * and all changes in connectivity state are initiated here.
 *
 * Wi-Fi now supports three modes of operation: Client, SoftAp and p2p
 * In the current implementation, we support concurrent wifi p2p and wifi operation.
 * The WifiStateMachine handles SoftAp and Client operations while WifiP2pService
 * handles p2p operation.
 *
 * @hide
 */
public class WifiStateMachine extends StateMachine implements WifiNative.WifiPnoEventHandler {

    private static final String NETWORKTYPE = "WIFI";
    private static final String NETWORKTYPE_UNTRUSTED = "WIFI_UT";
    private static boolean DBG = true;
    private static boolean VDBG = true;
    private static boolean VVDBG = true;
    private static boolean USE_PAUSE_SCANS = false;
    private static boolean mLogMessages = true;
    private static final String TAG = "WifiStateMachine";

    private static final int ONE_HOUR_MILLI = 1000 * 60 * 60;

    private static final String GOOGLE_OUI = "DA-A1-19";

    /* temporary debug flag - best network selection development */
    private static boolean PDBG = false;

    /* debug flag, indicating if handling of ASSOCIATION_REJECT ended up blacklisting
     * the corresponding BSSID.
     */
    private boolean didBlackListBSSID = false;

    /**
     * Log with error attribute
     *
     * @param s is string log
     */
    protected void loge(String s) {
        Log.e(getName(), s);
    }
    protected void logd(String s) {
        Log.d(getName(), s);
    }
    protected void log(String s) {;
        Log.d(getName(), s);
    }

    private WifiMonitor mWifiMonitor;
    private WifiNative mWifiNative;
    private WifiConfigStore mWifiConfigStore;
    private WifiAutoJoinController mWifiAutoJoinController;
    private INetworkManagementService mNwService;
    private ConnectivityManager mCm;
    private WifiLogger mWifiLogger;
    private WifiApConfigStore mWifiApConfigStore;
    private final boolean mP2pSupported;
    private final AtomicBoolean mP2pConnected = new AtomicBoolean(false);
    private boolean mTemporarilyDisconnectWifi = false;
    private final String mPrimaryDeviceType;

    /* Scan results handling */
    private List<ScanDetail> mScanResults = new ArrayList<>();
    private static final Pattern scanResultPattern = Pattern.compile("\t+");
    private static final int SCAN_RESULT_CACHE_SIZE = 160;
    private final LruCache<NetworkDetail, ScanDetail> mScanResultCache;
    // For debug, number of known scan results that were found as part of last scan result event,
    // as well the number of scans results returned by the supplicant with that message
    private int mNumScanResultsKnown;
    private int mNumScanResultsReturned;

    private boolean mScreenOn = false;

    /* Chipset supports background scan */
    private final boolean mBackgroundScanSupported;

    private final String mInterfaceName;
    /* Tethering interface could be separate from wlan interface */
    private String mTetherInterfaceName;

    private int mLastSignalLevel = -1;
    private String mLastBssid;
    private int mLastNetworkId; // The network Id we successfully joined
    private boolean linkDebouncing = false;

    private boolean mHalBasedPnoDriverSupported = false;

    // Below booleans are configurations coming from the Developper Settings
    private boolean mEnableAssociatedNetworkSwitchingInDevSettings = true;
    private boolean mHalBasedPnoEnableInDevSettings = false;


    private int mHalFeatureSet = 0;
    private static int mPnoResultFound = 0;

    @Override
    public void onPnoNetworkFound(ScanResult results[]) {
        if (DBG) {
            Log.e(TAG, "onPnoNetworkFound event received num = " + results.length);
            for (int i = 0; i < results.length; i++) {
                Log.e(TAG, results[i].toString());
            }
        }
        sendMessage(CMD_PNO_NETWORK_FOUND, results.length, 0, results);
    }

    public void processPnoNetworkFound(ScanResult results[]) {
        ScanSettings settings = new ScanSettings();
        settings.channelSet = new ArrayList<WifiChannel>();
        StringBuilder sb = new StringBuilder();
        sb.append("");
        for (int i=0; i<results.length; i++) {
            WifiChannel channel = new WifiChannel();
            channel.freqMHz = results[i].frequency;
            settings.channelSet.add(channel);
            sb.append(results[i].SSID).append(" ");
        }

        stopPnoOffload();

        Log.e(TAG, "processPnoNetworkFound starting scan cnt=" + mPnoResultFound);
        startScan(PNO_NETWORK_FOUND_SOURCE, mPnoResultFound,  settings, null);
        mPnoResultFound ++;
        //sendMessage(CMD_SCAN_RESULTS_AVAILABLE);
        int delay = 30 * 1000;
        // reconfigure Pno after 1 minutes if we're still in disconnected state
        sendMessageDelayed(CMD_RESTART_AUTOJOIN_OFFLOAD, delay,
                mRestartAutoJoinOffloadCounter, " processPnoNetworkFound " + sb.toString(),
                (long)delay);
        mRestartAutoJoinOffloadCounter++;
    }

    public void registerNetworkDisabled(int netId) {
        // Restart legacy PNO and autojoin offload if needed
        sendMessage(CMD_RESTART_AUTOJOIN_OFFLOAD, 0,
                mRestartAutoJoinOffloadCounter, " registerNetworkDisabled " + netId);
        mRestartAutoJoinOffloadCounter++;
    }

    // Testing various network disconnect cases by sending lots of spurious
    // disconnect to supplicant
    private boolean testNetworkDisconnect = false;

    private boolean mEnableRssiPolling = false;
    private boolean mLegacyPnoEnabled = false;
    private int mRssiPollToken = 0;
    /* 3 operational states for STA operation: CONNECT_MODE, SCAN_ONLY_MODE, SCAN_ONLY_WIFI_OFF_MODE
    * In CONNECT_MODE, the STA can scan and connect to an access point
    * In SCAN_ONLY_MODE, the STA can only scan for access points
    * In SCAN_ONLY_WIFI_OFF_MODE, the STA can only scan for access points with wifi toggle being off
    */
    private int mOperationalMode = CONNECT_MODE;
    private boolean mIsScanOngoing = false;
    private boolean mIsFullScanOngoing = false;
    private boolean mSendScanResultsBroadcast = false;

    private final Queue<Message> mBufferedScanMsg = new LinkedList<Message>();
    private WorkSource mScanWorkSource = null;
    private static final int UNKNOWN_SCAN_SOURCE = -1;
    private static final int SCAN_ALARM_SOURCE = -2;
    private static final int ADD_OR_UPDATE_SOURCE = -3;
    private static final int SET_ALLOW_UNTRUSTED_SOURCE = -4;
    private static final int ENABLE_WIFI = -5;
    public static final int DFS_RESTRICTED_SCAN_REQUEST = -6;
    public static final int PNO_NETWORK_FOUND_SOURCE = -7;
    ///M: add for mark inside scan source
    private static final int WIFI_INDIDE_SOURCE = -8;
    ///M: group scan source
    private static final int GROUP_SCAN_SOURCE = -9;

    private static final int SCAN_REQUEST_BUFFER_MAX_SIZE = 10;
    private static final String CUSTOMIZED_SCAN_SETTING = "customized_scan_settings";
    private static final String CUSTOMIZED_SCAN_WORKSOURCE = "customized_scan_worksource";
    private static final String SCAN_REQUEST_TIME = "scan_request_time";

    /* Tracks if state machine has received any screen state change broadcast yet.
     * We can miss one of these at boot.
     */
    private AtomicBoolean mScreenBroadcastReceived = new AtomicBoolean(false);

    private boolean mBluetoothConnectionActive = false;

    private PowerManager.WakeLock mSuspendWakeLock;

    /**
     * Interval in milliseconds between polling for RSSI
     * and linkspeed information
     */
    private static final int POLL_RSSI_INTERVAL_MSECS = 3000;

    /**
     * Interval in milliseconds between receiving a disconnect event
     * while connected to a good AP, and handling the disconnect proper
     */
    private static final int LINK_FLAPPING_DEBOUNCE_MSEC = 7000;

    /**
     * Delay between supplicant restarts upon failure to establish connection
     */
    private static final int SUPPLICANT_RESTART_INTERVAL_MSECS = 5000;

    /**
     * Number of times we attempt to restart supplicant
     */
    private static final int SUPPLICANT_RESTART_TRIES = 5;

    private int mSupplicantRestartCount = 0;
    /* Tracks sequence number on stop failure message */
    private int mSupplicantStopFailureToken = 0;

    /**
     * Tether state change notification time out
     */
     ///M: MTK modify for  performance issue
    private static final int TETHER_NOTIFICATION_TIME_OUT_MSECS = 10000;

    /* Tracks sequence number on a tether notification time out */
    private int mTetherToken = 0;

    /**
     * Driver start time out.
     */
    private static final int DRIVER_START_TIME_OUT_MSECS = 10000;

    /* Tracks sequence number on a driver time out */
    private int mDriverStartToken = 0;

    /**
     * Don't select new network when previous network selection is
     * pending connection for this much time
     */
    private static final int CONNECT_TIMEOUT_MSEC = 3000;

    /**
     * The link properties of the wifi interface.
     * Do not modify this directly; use updateLinkProperties instead.
     */
    private LinkProperties mLinkProperties;

    /* Tracks sequence number on a periodic scan message */
    private int mPeriodicScanToken = 0;

    // Wakelock held during wifi start/stop and driver load/unload
    private PowerManager.WakeLock mWakeLock;

    private Context mContext;

    private final Object mDhcpResultsLock = new Object();
    private DhcpResults mDhcpResults;

    // NOTE: Do not return to clients - use #getWiFiInfoForUid(int)
    private WifiInfo mWifiInfo;
    private NetworkInfo mNetworkInfo;
    private NetworkCapabilities mNetworkCapabilities;
    private SupplicantStateTracker mSupplicantStateTracker;
    private BaseDhcpStateMachine mDhcpStateMachine;
    private boolean mDhcpActive = false;
    ///M: not support
    private int mWifiLinkLayerStatsSupported = 0; // Temporary disable

    private final AtomicInteger mCountryCodeSequence = new AtomicInteger();

    // Whether the state machine goes thru the Disconnecting->Disconnected->ObtainingIpAddress
    private int mAutoRoaming = WifiAutoJoinController.AUTO_JOIN_IDLE;

    // Roaming failure count
    private int mRoamFailCount = 0;

    // This is the BSSID we are trying to associate to, it can be set to "any"
    // if we havent selected a BSSID for joining.
    // if we havent selected a BSSID for joining.
    // The BSSID we are associated to is found in mWifiInfo
    private String mTargetRoamBSSID = "any";

    private long mLastDriverRoamAttempt = 0;

    private WifiConfiguration targetWificonfiguration = null;

    // Used as debug to indicate which configuration last was saved
    private WifiConfiguration lastSavedConfigurationAttempt = null;

    // Used as debug to indicate which configuration last was removed
    private WifiConfiguration lastForgetConfigurationAttempt = null;

    //Random used by softAP channel Selection
    private static Random mRandom = new Random(Calendar.getInstance().getTimeInMillis());

    boolean isRoaming() {
        return mAutoRoaming == WifiAutoJoinController.AUTO_JOIN_ROAMING
                || mAutoRoaming == WifiAutoJoinController.AUTO_JOIN_EXTENDED_ROAMING;
    }

    public void autoRoamSetBSSID(int netId, String bssid) {
        autoRoamSetBSSID(mWifiConfigStore.getWifiConfiguration(netId), bssid);
    }

    public boolean autoRoamSetBSSID(WifiConfiguration config, String bssid) {
        boolean ret = true;
        if (mTargetRoamBSSID == null) mTargetRoamBSSID = "any";
        if (bssid == null) bssid = "any";
        if (config == null) return false; // Nothing to do

        if (mTargetRoamBSSID != null && bssid == mTargetRoamBSSID && bssid == config.BSSID) {
            return false; // We didnt change anything
        }
        if (!mTargetRoamBSSID.equals("any") && bssid.equals("any")) {
            // Changing to ANY
            if (!mWifiConfigStore.roamOnAny) {
                ret = false; // Nothing to do
            }
        }
        if (VDBG) {
            logd("autoRoamSetBSSID " + bssid + " key=" + config.configKey());
        }
        config.autoJoinBSSID = bssid;
        mTargetRoamBSSID = bssid;
        mWifiConfigStore.saveWifiConfigBSSID(config);
        return ret;
    }

    /**
     * Save the UID correctly depending on if this is a new or existing network.
     * @return true if operation is authorized, false otherwise
     */
    boolean recordUidIfAuthorized(WifiConfiguration config, int uid, boolean onlyAnnotate) {
        if (!mWifiConfigStore.isNetworkConfigured(config)) {
            config.creatorUid = uid;
            config.creatorName = mContext.getPackageManager().getNameForUid(uid);
        } else if (!mWifiConfigStore.canModifyNetwork(uid, config, onlyAnnotate)) {
            return false;
        }

        config.lastUpdateUid = uid;
        config.lastUpdateName = mContext.getPackageManager().getNameForUid(uid);

        return true;

    }

    /**
     * Checks to see if user has specified if the apps configuration is connectable.
     * If the user hasn't specified we query the user and return true.
     *
     * @param message The message to be deferred
     * @param netId Network id of the configuration to check against
     * @param allowOverride If true we won't defer to the user if the uid of the message holds the
     *                      CONFIG_OVERRIDE_PERMISSION
     * @return True if we are waiting for user feedback or netId is invalid. False otherwise.
     */
    boolean deferForUserInput(Message message, int netId, boolean allowOverride){
        final WifiConfiguration config = mWifiConfigStore.getWifiConfiguration(netId);

        // We can only evaluate saved configurations.
        if (config == null) {
            logd("deferForUserInput: configuration for netId=" + netId + " not stored");
            return true;
        }

        switch (config.userApproved) {
            case WifiConfiguration.USER_APPROVED:
            case WifiConfiguration.USER_BANNED:
                return false;
            case WifiConfiguration.USER_PENDING:
            default: // USER_UNSPECIFIED
               /* the intention was to ask user here; but a dialog box is   *
                * too invasive; so we are going to allow connection for now */
                config.userApproved = WifiConfiguration.USER_APPROVED;
                return false;
        }
    }

    /**
     * Subset of link properties coming from netlink.
     * Currently includes IPv4 and IPv6 addresses. In the future will also include IPv6 DNS servers
     * and domains obtained from router advertisements (RFC 6106).
     */
    private NetlinkTracker mNetlinkTracker;

    private IpReachabilityMonitor mIpReachabilityMonitor;

    private AlarmManager mAlarmManager;
    private PendingIntent mScanIntent;
    private PendingIntent mDriverStopIntent;
    private PendingIntent mPnoIntent;

    private int mDisconnectedPnoAlarmCount = 0;
    /* Tracks current frequency mode */
    private AtomicInteger mFrequencyBand = new AtomicInteger(WifiManager.WIFI_FREQUENCY_BAND_AUTO);

    /* Tracks if we are filtering Multicast v4 packets. Default is to filter. */
    private AtomicBoolean mFilteringMulticastV4Packets = new AtomicBoolean(true);

    // Channel for sending replies.
    private AsyncChannel mReplyChannel = new AsyncChannel();

    private WifiP2pServiceImpl mWifiP2pServiceImpl;

    // Used to initiate a connection with WifiP2pService
    private AsyncChannel mWifiP2pChannel;
    private AsyncChannel mWifiApConfigChannel;

    private WifiScanner mWifiScanner;

    private int mConnectionRequests = 0;
    private WifiNetworkFactory mNetworkFactory;
    private UntrustedWifiNetworkFactory mUntrustedNetworkFactory;
    private WifiNetworkAgent mNetworkAgent;

    private String[] mWhiteListedSsids = null;

    // Keep track of various statistics, for retrieval by System Apps, i.e. under @SystemApi
    // We should really persist that into the networkHistory.txt file, and read it back when
    // WifiStateMachine starts up
    private WifiConnectionStatistics mWifiConnectionStatistics = new WifiConnectionStatistics();

    // Used to filter out requests we couldn't possibly satisfy.
    private final NetworkCapabilities mNetworkCapabilitiesFilter = new NetworkCapabilities();

    /* The base for wifi message types */
    static final int BASE = Protocol.BASE_WIFI;
    /* Start the supplicant */
    static final int CMD_START_SUPPLICANT                               = BASE + 11;
    /* Stop the supplicant */
    static final int CMD_STOP_SUPPLICANT                                = BASE + 12;
    /* Start the driver */
    static final int CMD_START_DRIVER                                   = BASE + 13;
    /* Stop the driver */
    static final int CMD_STOP_DRIVER                                    = BASE + 14;
    /* Indicates Static IP succeeded */
    static final int CMD_STATIC_IP_SUCCESS                              = BASE + 15;
    /* Indicates Static IP failed */
    static final int CMD_STATIC_IP_FAILURE                              = BASE + 16;
    /* Indicates supplicant stop failed */
    static final int CMD_STOP_SUPPLICANT_FAILED                         = BASE + 17;
    /* Delayed stop to avoid shutting down driver too quick*/
    static final int CMD_DELAYED_STOP_DRIVER                            = BASE + 18;
    /* A delayed message sent to start driver when it fail to come up */
    static final int CMD_DRIVER_START_TIMED_OUT                         = BASE + 19;

    /* Start the soft access point */
    static final int CMD_START_AP                                       = BASE + 21;
    /* Indicates soft ap start succeeded */
    static final int CMD_START_AP_SUCCESS                               = BASE + 22;
    /* Indicates soft ap start failed */
    static final int CMD_START_AP_FAILURE                               = BASE + 23;
    /* Stop the soft access point */
    static final int CMD_STOP_AP                                        = BASE + 24;
    /* Set the soft access point configuration */
    static final int CMD_SET_AP_CONFIG                                  = BASE + 25;
    /* Soft access point configuration set completed */
    static final int CMD_SET_AP_CONFIG_COMPLETED                        = BASE + 26;
    /* Request the soft access point configuration */
    static final int CMD_REQUEST_AP_CONFIG                              = BASE + 27;
    /* Response to access point configuration request */
    static final int CMD_RESPONSE_AP_CONFIG                             = BASE + 28;
    /* Invoked when getting a tether state change notification */
    static final int CMD_TETHER_STATE_CHANGE                            = BASE + 29;
    /* A delayed message sent to indicate tether state change failed to arrive */
    static final int CMD_TETHER_NOTIFICATION_TIMED_OUT                  = BASE + 30;

    static final int CMD_BLUETOOTH_ADAPTER_STATE_CHANGE                 = BASE + 31;

    /* Supplicant commands */
    /* Is supplicant alive ? */
    static final int CMD_PING_SUPPLICANT                                = BASE + 51;
    /* Add/update a network configuration */
    static final int CMD_ADD_OR_UPDATE_NETWORK                          = BASE + 52;
    /* Delete a network */
    static final int CMD_REMOVE_NETWORK                                 = BASE + 53;
    /* Enable a network. The device will attempt a connection to the given network. */
    static final int CMD_ENABLE_NETWORK                                 = BASE + 54;
    /* Enable all networks */
    static final int CMD_ENABLE_ALL_NETWORKS                            = BASE + 55;
    /* Blacklist network. De-prioritizes the given BSSID for connection. */
    static final int CMD_BLACKLIST_NETWORK                              = BASE + 56;
    /* Clear the blacklist network list */
    static final int CMD_CLEAR_BLACKLIST                                = BASE + 57;
    /* Save configuration */
    static final int CMD_SAVE_CONFIG                                    = BASE + 58;
    /* Get configured networks */
    static final int CMD_GET_CONFIGURED_NETWORKS                        = BASE + 59;
    /* Get available frequencies */
    static final int CMD_GET_CAPABILITY_FREQ                            = BASE + 60;
    /* Get adaptors */
    static final int CMD_GET_SUPPORTED_FEATURES                         = BASE + 61;
    /* Get configured networks with real preSharedKey */
    static final int CMD_GET_PRIVILEGED_CONFIGURED_NETWORKS             = BASE + 62;
    /* Get Link Layer Stats thru HAL */
    static final int CMD_GET_LINK_LAYER_STATS                           = BASE + 63;
    /* Supplicant commands after driver start*/
    /* Initiate a scan */
    static final int CMD_START_SCAN                                     = BASE + 71;
    /* Set operational mode. CONNECT, SCAN ONLY, SCAN_ONLY with Wi-Fi off mode */
    static final int CMD_SET_OPERATIONAL_MODE                           = BASE + 72;
    /* Disconnect from a network */
    static final int CMD_DISCONNECT                                     = BASE + 73;
    /* Reconnect to a network */
    static final int CMD_RECONNECT                                      = BASE + 74;
    /* Reassociate to a network */
    static final int CMD_REASSOCIATE                                    = BASE + 75;
    /* Get Connection Statistis */
    static final int CMD_GET_CONNECTION_STATISTICS                      = BASE + 76;

    /* Controls suspend mode optimizations
     *
     * When high perf mode is enabled, suspend mode optimizations are disabled
     *
     * When high perf mode is disabled, suspend mode optimizations are enabled
     *
     * Suspend mode optimizations include:
     * - packet filtering
     * - turn off roaming
     * - DTIM wake up settings
     */
    static final int CMD_SET_HIGH_PERF_MODE                             = BASE + 77;
    /* Set the country code */
    static final int CMD_SET_COUNTRY_CODE                               = BASE + 80;
    /* Enables RSSI poll */
    static final int CMD_ENABLE_RSSI_POLL                               = BASE + 82;
    /* RSSI poll */
    static final int CMD_RSSI_POLL                                      = BASE + 83;
    /* Set up packet filtering */
    static final int CMD_START_PACKET_FILTERING                         = BASE + 84;
    /* Clear packet filter */
    static final int CMD_STOP_PACKET_FILTERING                          = BASE + 85;
    /* Enable suspend mode optimizations in the driver */
    static final int CMD_SET_SUSPEND_OPT_ENABLED                        = BASE + 86;
    /* Delayed NETWORK_DISCONNECT */
    static final int CMD_DELAYED_NETWORK_DISCONNECT                     = BASE + 87;
    /* When there are no saved networks, we do a periodic scan to notify user of
     * an open network */
    static final int CMD_NO_NETWORKS_PERIODIC_SCAN                      = BASE + 88;
    /* Test network Disconnection NETWORK_DISCONNECT */
    static final int CMD_TEST_NETWORK_DISCONNECT                        = BASE + 89;

    private int testNetworkDisconnectCounter = 0;

    /* arg1 values to CMD_STOP_PACKET_FILTERING and CMD_START_PACKET_FILTERING */
    static final int MULTICAST_V6 = 1;
    static final int MULTICAST_V4 = 0;

    /* Set the frequency band */
    static final int CMD_SET_FREQUENCY_BAND                             = BASE + 90;
    /* Enable TDLS on a specific MAC address */
    static final int CMD_ENABLE_TDLS                                    = BASE + 92;
    /* DHCP/IP configuration watchdog */
    static final int CMD_OBTAINING_IP_ADDRESS_WATCHDOG_TIMER            = BASE + 93;

    /**
     * Watchdog for protecting against b/16823537
     * Leave time for 4-way handshake to succeed
     */
    static final int ROAM_GUARD_TIMER_MSEC = 15000;

    int roamWatchdogCount = 0;
    /* Roam state watchdog */
    static final int CMD_ROAM_WATCHDOG_TIMER                            = BASE + 94;
    /* Screen change intent handling */
    static final int CMD_SCREEN_STATE_CHANGED                           = BASE + 95;

    /* Disconnecting state watchdog */
    static final int CMD_DISCONNECTING_WATCHDOG_TIMER                   = BASE + 96;

    /* Remove a packages associated configrations */
    static final int CMD_REMOVE_APP_CONFIGURATIONS                      = BASE + 97;

    /* Disable an ephemeral network */
    static final int CMD_DISABLE_EPHEMERAL_NETWORK                      = BASE + 98;

    /* Get matching network */
    static final int CMD_GET_MATCHING_CONFIG                            = BASE + 99;

    /* alert from firmware */
    static final int CMD_FIRMWARE_ALERT                                 = BASE + 100;

    /**
     * Make this timer 40 seconds, which is about the normal DHCP timeout.
     * In no valid case, the WiFiStateMachine should remain stuck in ObtainingIpAddress
     * for more than 30 seconds.
     */
    static final int OBTAINING_IP_ADDRESS_GUARD_TIMER_MSEC = 40000;

    int obtainingIpWatchdogCount = 0;

    /* Commands from/to the SupplicantStateTracker */
    /* Reset the supplicant state tracker */
    static final int CMD_RESET_SUPPLICANT_STATE                         = BASE + 111;

    int disconnectingWatchdogCount = 0;
    static final int DISCONNECTING_GUARD_TIMER_MSEC = 5000;

    /* P2p commands */
    /* We are ok with no response here since we wont do much with it anyway */
    public static final int CMD_ENABLE_P2P                              = BASE + 131;
    /* In order to shut down supplicant cleanly, we wait till p2p has
     * been disabled */
    public static final int CMD_DISABLE_P2P_REQ                         = BASE + 132;
    public static final int CMD_DISABLE_P2P_RSP                         = BASE + 133;

    public static final int CMD_BOOT_COMPLETED                          = BASE + 134;

    /* We now have a valid IP configuration. */
    static final int CMD_IP_CONFIGURATION_SUCCESSFUL                    = BASE + 138;
    /* We no longer have a valid IP configuration. */
    static final int CMD_IP_CONFIGURATION_LOST                          = BASE + 139;
    /* Link configuration (IP address, DNS, ...) changes notified via netlink */
    static final int CMD_UPDATE_LINKPROPERTIES                          = BASE + 140;

    /* Supplicant is trying to associate to a given BSSID */
    static final int CMD_TARGET_BSSID                                   = BASE + 141;

    /* Reload all networks and reconnect */
    static final int CMD_RELOAD_TLS_AND_RECONNECT                       = BASE + 142;

    static final int CMD_AUTO_CONNECT                                   = BASE + 143;

    private static final int NETWORK_STATUS_UNWANTED_DISCONNECT         = 0;
    private static final int NETWORK_STATUS_UNWANTED_VALIDATION_FAILED  = 1;
    private static final int NETWORK_STATUS_UNWANTED_DISABLE_AUTOJOIN   = 2;

    static final int CMD_UNWANTED_NETWORK                               = BASE + 144;

    static final int CMD_AUTO_ROAM                                      = BASE + 145;

    static final int CMD_AUTO_SAVE_NETWORK                              = BASE + 146;

    static final int CMD_ASSOCIATED_BSSID                               = BASE + 147;

    static final int CMD_NETWORK_STATUS                                 = BASE + 148;

    /* A layer 3 neighbor on the Wi-Fi link became unreachable. */
    static final int CMD_IP_REACHABILITY_LOST                           = BASE + 149;

    /* Remove a packages associated configrations */
    static final int CMD_REMOVE_USER_CONFIGURATIONS                     = BASE + 152;

    static final int CMD_ACCEPT_UNVALIDATED                             = BASE + 153;

    /* used to restart PNO when it was stopped due to association attempt */
    static final int CMD_RESTART_AUTOJOIN_OFFLOAD                       = BASE + 154;

    static int mRestartAutoJoinOffloadCounter = 0;

    /* used to log if PNO was started */
    static final int CMD_STARTED_PNO_DBG                                = BASE + 155;

    static final int CMD_PNO_NETWORK_FOUND                              = BASE + 156;

    /* used to log if PNO was started */
    static final int CMD_UPDATE_ASSOCIATED_SCAN_PERMISSION              = BASE + 158;

    /* used to log if GSCAN was started */
    static final int CMD_STARTED_GSCAN_DBG                              = BASE + 159;


    /* M: Added command */
     private static final int M_CMD_UPDATE_SETTINGS           = BASE + 160;
     private static final int M_CMD_UPDATE_SCAN_INTERVAL      = BASE + 161;
     private static final int M_CMD_UPDATE_COUNTRY_CODE       = BASE + 162;

     private static final int M_CMD_DO_CTIA_TEST_ON           = BASE + 163;
     private static final int M_CMD_DO_CTIA_TEST_OFF          = BASE + 164;
     private static final int M_CMD_DO_CTIA_TEST_RATE         = BASE + 165;
     private static final int M_CMD_GET_CONNECTING_NETWORK_ID = BASE + 166;
     private static final int M_CMD_UPDATE_RSSI               = BASE + 167;
     private static final int M_CMD_SET_TX_POWER_ENABLED      = BASE + 168;
     private static final int M_CMD_SET_TX_POWER              = BASE + 169;
     private static final int M_CMD_BLOCK_CLIENT              = BASE + 170;
     private static final int M_CMD_UNBLOCK_CLIENT            = BASE + 171;
     private static final int M_CMD_START_AP_WPS              = BASE + 172;
     private static final int M_CMD_UPDATE_BGSCAN             = BASE + 173;
     private static final int M_CMD_SET_AP_PROBE_REQUEST_ENABLED = BASE + 174;

     //* M: For stop scan after screen off in disconnected state feature */
     private static final int M_CMD_SLEEP_POLICY_STOP_SCAN    = BASE + 175;
     private static final int M_CMD_NOTIFY_SCREEN_OFF         = BASE + 176;
     private static final int M_CMD_NOTIFY_SCREEN_ON          = BASE + 177;

     private static final int M_CMD_GET_DISCONNECT_FLAG       = BASE + 178;
     private static final int M_CMD_NOTIFY_CONNECTION_FAILURE = BASE + 180;
     private static final int M_CMD_GET_WIFI_STATUS           = BASE + 181;
     private static final int M_CMD_SET_POWER_SAVING_MODE     = BASE + 182;

     /** M: NFC Float II @{ */
     private static final int M_CMD_START_WPS_NFC_TAG_READ    = BASE + 183;
     private static final int M_CMD_HS_RECEIVED               = BASE + 184;
     public static final int M_CMD_HR_RECEIVED                = BASE + 185;
     public static final int M_CMD_CLEAE_HR_WAIT_FLAG         = BASE + 186;
     /** @} */

     /** M: Set Driver Wowlan Mode @{ */
     private static final int M_CMD_SET_WOWLAN_NORMAL_MODE    = BASE + 187;
     private static final int M_CMD_SET_WOWLAN_MAGIC_MODE     = BASE + 188;
     /** @} */

     private static final int M_CMD_FLUSH_BSS                 = BASE + 192;
     ///M: for poor link st value
     private static final int M_CMD_SET_POORLINK_RSSI       = BASE + 218;
     private static final int M_CMD_SET_POORLINK_LINKSPEED       = BASE + 219;
     /// @}
     private static final int M_CMD_DHCP_V6_SUCCESS       = BASE + 220;

     private static final int M_CMD_START_AUTOJOIN_PROFILING       = BASE + 221;
    private static final int M_CMD_GET_TEST_ENV              = BASE + 222;
    ///M: ALPS02279279 [Google Issue] Cannot remove configured networks when factory reset
    private static final int M_CMD_FACTORY_RESET             = BASE + 223;
     ///M: ALPS01986276 should enable EAP SIM in state machine
     private static final int M_CMD_ENABLE_EAP_SIM_CONFIG_NETWORK       = BASE + 233;

     ///M: ALPS02550356 For avoiding frequently disconnect.
     //    Only enable IpReachabilityMonitor for 10 sec after FW roaming @{
     private static final int M_CMD_IP_REACHABILITY_MONITOR_TIMER_10s        = BASE + 235;
     private static final int M_CMD_IP_REACHABILITY_MONITOR_TIMER_3s        = BASE + 236;
     static final int IP_REACHABILITY_MONITOR_TIMER_MSEC_10s = 10000;
     static final int IP_REACHABILITY_MONITOR_TIMER_MSEC_3s = 3000;
     int ipReachabilityMonitorCount = 0;
     boolean mIsListeningIpReachabilityLost = false;
     ///@}

         ///M: group scan
     private static final int M_CMD_GROUP_SCAN = BASE + 237;

    /* Wifi state machine modes of operation */
    /* CONNECT_MODE - connect to any 'known' AP when it becomes available */
    public static final int CONNECT_MODE = 1;
    /* SCAN_ONLY_MODE - don't connect to any APs; scan, but only while apps hold lock */
    public static final int SCAN_ONLY_MODE = 2;
    /* SCAN_ONLY_WITH_WIFI_OFF - scan, but don't connect to any APs */
    public static final int SCAN_ONLY_WITH_WIFI_OFF_MODE = 3;

    private static final int SUCCESS = 1;
    private static final int FAILURE = -1;


    /**
     * The maximum number of times we will retry a connection to an access point
     * for which we have failed in acquiring an IP address from DHCP. A value of
     * N means that we will make N+1 connection attempts in all.
     * <p>
     * See {@link Settings.Secure#WIFI_MAX_DHCP_RETRY_COUNT}. This is the default
     * value if a Settings value is not present.
     */
    ///M: modify retry times
    private static final int DEFAULT_MAX_DHCP_RETRIES = 3;

    /* Tracks if suspend optimizations need to be disabled by DHCP,
     * screen or due to high perf mode.
     * When any of them needs to disable it, we keep the suspend optimizations
     * disabled
     */
    private int mSuspendOptNeedsDisabled = 0;

    private static final int SUSPEND_DUE_TO_DHCP = 1;
    private static final int SUSPEND_DUE_TO_HIGH_PERF = 1 << 1;
    private static final int SUSPEND_DUE_TO_SCREEN = 1 << 2;

    /* Tracks if user has enabled suspend optimizations through settings */
    private AtomicBoolean mUserWantsSuspendOpt = new AtomicBoolean(true);

    /**
     * Default framework scan interval in milliseconds. This is used in the scenario in which
     * wifi chipset does not support background scanning to set up a
     * periodic wake up scan so that the device can connect to a new access
     * point on the move. {@link Settings.Global#WIFI_FRAMEWORK_SCAN_INTERVAL_MS} can
     * override this.
     */
    private final int mDefaultFrameworkScanIntervalMs;


    /**
     * Scan period for the NO_NETWORKS_PERIIDOC_SCAN_FEATURE
     */
    private final int mNoNetworksPeriodicScan;

    /**
     * Supplicant scan interval in milliseconds.
     * Comes from {@link Settings.Global#WIFI_SUPPLICANT_SCAN_INTERVAL_MS} or
     * from the default config if the setting is not set
     */
    private long mSupplicantScanIntervalMs;

    /**
     * timeStamp of last full band scan we perfoemed for autojoin while connected with screen lit
     */
    private long lastFullBandConnectedTimeMilli;

    /**
     * time interval to the next full band scan we will perform for
     * autojoin while connected with screen lit
     */
    private long fullBandConnectedTimeIntervalMilli;

    /**
     * max time interval to the next full band scan we will perform for
     * autojoin while connected with screen lit
     * Max time is 5 minutes
     */
    private static final long maxFullBandConnectedTimeIntervalMilli = 1000 * 60 * 5;

    /**
     * Minimum time interval between enabling all networks.
     * A device can end up repeatedly connecting to a bad network on screen on/off toggle
     * due to enabling every time. We add a threshold to avoid this.
     */
    private static final int MIN_INTERVAL_ENABLE_ALL_NETWORKS_MS = 10 * 60 * 1000; /* 10 minutes */
    private long mLastEnableAllNetworksTime;

    int mRunningBeaconCount = 0;

    /**
     * Starting and shutting down driver too quick causes problems leading to driver
     * being in a bad state. Delay driver stop.
     */
    private final int mDriverStopDelayMs;
    private int mDelayedStopCounter;
    private boolean mInDelayedStop = false;

    // there is a delay between StateMachine change country code and Supplicant change country code
    // here save the current WifiStateMachine set country code
    private volatile String mSetCountryCode = null;

    // Supplicant doesn't like setting the same country code multiple times (it may drop
    // currently connected network), so we save the current device set country code here to avoid
    // redundency
    private String mDriverSetCountryCode = null;

    /* Default parent state */
    private State mDefaultState = new DefaultState();
    /* Temporary initial state */
    private State mInitialState = new InitialState();
    /* Driver loaded, waiting for supplicant to start */
    private State mSupplicantStartingState = new SupplicantStartingState();
    /* Driver loaded and supplicant ready */
    private State mSupplicantStartedState = new SupplicantStartedState();
    /* Waiting for supplicant to stop and monitor to exit */
    private State mSupplicantStoppingState = new SupplicantStoppingState();
    /* Driver start issued, waiting for completed event */
    private State mDriverStartingState = new DriverStartingState();
    /* Driver started */
    private State mDriverStartedState = new DriverStartedState();
    /* Wait until p2p is disabled
     * This is a special state which is entered right after we exit out of DriverStartedState
     * before transitioning to another state.
     */
    private State mWaitForP2pDisableState = new WaitForP2pDisableState();
    /* Driver stopping */
    private State mDriverStoppingState = new DriverStoppingState();
    /* Driver stopped */
    private State mDriverStoppedState = new DriverStoppedState();
    /* Scan for networks, no connection will be established */
    private State mScanModeState = new ScanModeState();
    /* Connecting to an access point */
    private State mConnectModeState = new ConnectModeState();
    /* Connected at 802.11 (L2) level */
    private State mL2ConnectedState = new L2ConnectedState();
    /* fetching IP after connection to access point (assoc+auth complete) */
    private State mObtainingIpState = new ObtainingIpState();
    /* Waiting for link quality verification to be complete */
    private State mVerifyingLinkState = new VerifyingLinkState();
    /* Connected with IP addr */
    private State mConnectedState = new ConnectedState();
    /* Roaming */
    private State mRoamingState = new RoamingState();
    /* disconnect issued, waiting for network disconnect confirmation */
    private State mDisconnectingState = new DisconnectingState();
    /* Network is not connected, supplicant assoc+auth is not complete */
    private State mDisconnectedState = new DisconnectedState();
    /* Waiting for WPS to be completed*/
    private State mWpsRunningState = new WpsRunningState();

    /* Soft ap is starting up */
    private State mSoftApStartingState = new SoftApStartingState();
    /* Soft ap is running */
    private State mSoftApStartedState = new SoftApStartedState();
    /* Soft ap is running and we are waiting for tether notification */
    private State mTetheringState = new TetheringState();
    /* Soft ap is running and we are tethered through connectivity service */
    private State mTetheredState = new TetheredState();
    /* Waiting for untether confirmation before stopping soft Ap */
    private State mUntetheringState = new UntetheringState();



    private class WifiScanListener implements WifiScanner.ScanListener {
        @Override
        public void onSuccess() {
            Log.e(TAG, "WifiScanListener onSuccess");
        };
        @Override
        public void onFailure(int reason, String description) {
            Log.e(TAG, "WifiScanListener onFailure");
        };
        @Override
        public void onPeriodChanged(int periodInMs) {
            Log.e(TAG, "WifiScanListener onPeriodChanged  period=" + periodInMs);
        }
        @Override
        public void onResults(WifiScanner.ScanData[] results) {
            Log.e(TAG, "WifiScanListener onResults2 "  + results.length);
        }
        @Override
        public void onFullResult(ScanResult fullScanResult) {
            Log.e(TAG, "WifiScanListener onFullResult " + fullScanResult.toString());
        }

        WifiScanListener() {}
    }

    WifiScanListener mWifiScanListener = new WifiScanListener();


    private class TetherStateChange {
        ArrayList<String> available;
        ArrayList<String> active;

        TetherStateChange(ArrayList<String> av, ArrayList<String> ac) {
            available = av;
            active = ac;
        }
    }

    public static class SimAuthRequestData {
        int networkId;
        int protocol;
        String ssid;
        // EAP-SIM: data[] contains the 3 rand, one for each of the 3 challenges
        // EAP-AKA/AKA': data[] contains rand & authn couple for the single challenge
        String[] data;
    }

    /**
     * One of  {@link WifiManager#WIFI_STATE_DISABLED},
     * {@link WifiManager#WIFI_STATE_DISABLING},
     * {@link WifiManager#WIFI_STATE_ENABLED},
     * {@link WifiManager#WIFI_STATE_ENABLING},
     * {@link WifiManager#WIFI_STATE_UNKNOWN}
     */
    private final AtomicInteger mWifiState = new AtomicInteger(WIFI_STATE_DISABLED);

    /**
     * One of  {@link WifiManager#WIFI_AP_STATE_DISABLED},
     * {@link WifiManager#WIFI_AP_STATE_DISABLING},
     * {@link WifiManager#WIFI_AP_STATE_ENABLED},
     * {@link WifiManager#WIFI_AP_STATE_ENABLING},
     * {@link WifiManager#WIFI_AP_STATE_FAILED}
     */
    private final AtomicInteger mWifiApState = new AtomicInteger(WIFI_AP_STATE_DISABLED);

    private static final int SCAN_REQUEST = 0;
    private static final String ACTION_START_SCAN =
            "com.android.server.WifiManager.action.START_SCAN";

    private static final int PNO_START_REQUEST = 0;
    private static final String ACTION_START_PNO =
            "com.android.server.WifiManager.action.START_PNO";

    private static final String DELAYED_STOP_COUNTER = "DelayedStopCounter";
    private static final int DRIVER_STOP_REQUEST = 0;
    private static final String ACTION_DELAYED_DRIVER_STOP =
            "com.android.server.WifiManager.action.DELAYED_DRIVER_STOP";

    /**
     * Keep track of whether WIFI is running.
     */
    private boolean mIsRunning = false;

    /**
     * Keep track of whether we last told the battery stats we had started.
     */
    private boolean mReportedRunning = false;

    /**
     * Most recently set source of starting WIFI.
     */
    private final WorkSource mRunningWifiUids = new WorkSource();

    /**
     * The last reported UIDs that were responsible for starting WIFI.
     */
    private final WorkSource mLastRunningWifiUids = new WorkSource();

    private final IBatteryStats mBatteryStats;

    /* M: For customization */
    private IWifiFwkExt mWifiFwkExt;
    private boolean mDisconnectOperation = false;
    private boolean mScanForWeakSignal = false;
    private boolean mShowReselectDialog = false;
    private boolean mIpConfigLost = false;
    private int mDisconnectNetworkId = INVALID_NETWORK_ID;
    private int mLastExplicitNetworkId = INVALID_NETWORK_ID;
    private long mLastCheckWeakSignalTime = 0;

    /* M: For hotspot auto stop */
    private PendingIntent mIntentStopHotspot;
    private static final int STOP_HOTSPOT_REQUEST = 2;
    private static final String ACTION_STOP_HOTSPOT = "com.android.server.WifiManager.action.STOP_HOTSPOT";
    private static final long HOTSPOT_DISABLE_MS = 5 * 60 * 1000;
    private int mDuration = Settings.System.WIFI_HOTSPOT_AUTO_DISABLE_OFF;
    private int mClientNum = 0;
    private HotspotAutoDisableObserver mHotspotAutoDisableObserver;
    private WifiManager mWifiManager;

   ///M: ALPS01805194 mIsFullScanOngoing mIsScanOngoing is not sync with supplicant
   ///* when first enable wifi with saved profile,
   ///* supplicant could start a scan without noticing FWK
   private boolean mIsSupplicantFirstScan = false;

   ///M: ALPS02028415 Supplicant scan is stage scan, 2.4G and 5G scan result are seperate.
   ///* But when receiving first scan result, mIsFullScanOngoing will be cleared.
   ///* Then 5G scan result broadcast will be delayed.
   private int mWifiOnScanCount = 0;

    /* M: The device must have an inactivity timer for Bluetooth tethering and Mobile HotSpot.
     * The following configurable options must be available to the user.
     * Disable after 5 min of inactivity / Disable after 10 min of inactivity / Always ON
     * The default option out of the box must be the "Disable after 10 min of inactivity" option.
     * The device must change the inactivity timer option to "Always ON"
     * when it is plugged in to a wall socket, or to a computer, for charging. */
    private int mPluggedType = 0;

    /* M: For hotspot manager */
    private WifiMonitor mHotspotMonitor;
    private WifiNative mHotspotNative;
    private static HashMap<String, HotspotClient> mHotspotClients = new HashMap<String, HotspotClient>();

    /* M: For DHCPV6 */
    private DhcpStateMachine mDhcpV6StateMachine;
    private DhcpResults mDhcpV6Results;
    private final Object mDhcpV6ResultsLock = new Object();

    private boolean mPreDhcpSetupDone = false;
    private int mDhcpV4Status = 0;
    private int mDhcpV6Status = 0;
    /* M: For IP recover */
    private static HashMap<String, DhcpResults> mDhcpResultMap = new HashMap<String, DhcpResults>();

    /* M: For bug fix */
    private boolean mDeviceIdle;
    private PowerManager.WakeLock mDhcpWakeLock;
    private final AtomicBoolean mWfdConnected = new AtomicBoolean(false);
    private final AtomicBoolean mStopScanStarted = new AtomicBoolean(false);
    private boolean mConnectNetwork = false;
    private boolean mStartApWps = false;
    /* M: For stop scan after screen off in disconnected state feature */
    private boolean mIsPeriodicScanTimeout = false;
    private PendingIntent mStopScanIntent;
    private static final int STOPSCAN_REQUEST = 1;
    private static final String ACTION_STOP_SCAN =
        "com.android.server.WifiManager.action.STOP_SCAN";

    /* M: For PPPoE */
    private static final int EVENT_START_PPPOE = 0;
    private static final int EVENT_PPPOE_SUCCEEDED = 1;
    private static final int EVENT_UPDATE_DNS = 2;
    private static final int UPDATE_DNS_DELAY_MS = 500;
    private static final int PPPOE_NETID = 65500;
    private PPPOEInfo mPppoeInfo;
    private PPPOEConfig mPppoeConfig;
    private boolean mUsingPppoe = false;
    private PppoeHandler mPppoeHandler;
    private long mOnlineStartTime = 0;
    private LinkProperties mPppoeLinkProperties;
    private boolean mMtkCtpppoe = false;

    /** M: NFC Float II @{ */
    private static final int CLEAR_WATI_FLAG_REQUEST = 3;
    private static final long CLEAR_WATI_FLAG_MS = 2 * 60 * 1000;
    private static final String UNKNOWN_COMMAND = "UNKNOWN COMMAND";
    private boolean mWaitingForEnrollee = false;
    private boolean mWaitingForHrToken = false;
    private String mEnrolleeUuid = null;
    private String mEnrolleeBssid = null;
    private String mErApUuid = null;
    private String mErApPin = null;
    private int mWpsErMethod = WpsInfo.INVALID;
    private PendingIntent mClearWaitFlagIntent;

    //intent: send broadcast
    private static final String MTK_WPS_NFC_TESTBED_W_PASSWORD_ACTION = "mtk.wps.nfc.testbed.w.password";
    private static final String MTK_WPS_NFC_TESTBED_R_PASSWORD_ACTION = "mtk.wps.nfc.testbed.r.password";
    private static final String MTK_WPS_NFC_TESTBED_W_CONFIGURATION_ACTION = "mtk.wps.nfc.testbed.w.configuration";
    private static final String MTK_WPS_NFC_TESTBED_R_CONFIGURATION_ACTION = "mtk.wps.nfc.testbed.r.configuration";
    private static final String MTK_WPS_NFC_TESTBED_HR_ACTION = "mtk.wps.nfc.testbed.hr";
    private static final String MTK_WPS_NFC_TESTBED_HS_ACTION = "mtk.wps.nfc.testbed.hs";

    //intent: recv broadcast
    private static final String ACTION_CLEAR_WAIT_FLAG = "com.android.server.WifiManager.action.CLEAR_WAIT_FLAG";

    private static final String MTK_WPS_NFC_TESTBED_CONFIGURATION_RECEIVED_ACTION = "mtk.wps.nfc.testbed.configuration.received";
    private static final String MTK_WPS_NFC_TESTBED_EXTRA_CONFIGURATION = "mtk.wps.nfc.testbed.extra.configuration";

    private static final String MTK_WPS_NFC_TESTBED_PASSWORD_RECEIVED_ACTION = "mtk.wps.nfc.testbed.password.received";
    private static final String MTK_WPS_NFC_TESTBED_EXTRA_PASSWORD = "mtk.wps.nfc.testbed.extra.password";

    private static final String MTK_WPS_NFC_TESTBED_HS_RECEIVED_ACTION = "mtk.wps.nfc.testbed.hs.received";
    private static final String MTK_WPS_NFC_TESTBED_HR_RECEIVED_ACTION = "mtk.wps.nfc.testbed.hr.received";
    private static final String MTK_WPS_NFC_TESTBED_ER_PASSWORD_RECEIVED_ACTION = "mtk.wps.nfc.testbed.externalRegistrar.password.received";

    private boolean mTurnOffWifi_NfcWps = false;

    private String mTcpBufferSizes = null;

    // Used for debug and stats gathering
    private static int sScanAlarmIntentCount = 0;

    //M: for proprietary use, not reconnect or scan during a period time
    private final AtomicBoolean mDontReconnectAndScan = new AtomicBoolean(false);
    private final AtomicBoolean mDontReconnect = new AtomicBoolean(false);

    private static final int MIN_RSSI = -200;
    private static final int MAX_RSSI = 256;

    private static final boolean mMtkDhcpv6cWifi = SystemProperties.get("ro.mtk_dhcpv6c_wifi").equals("1");
    private static final boolean mMtkWpsp2pnfcSupport  = SystemProperties.get("ro.mtk_wifiwpsp2p_nfc_support").equals("1");

    private static final int GEMINI_SIM_1 = 1;
    private static final int GEMINI_SIM_2 = 1;

    ///M: bug fix
    private boolean mDontEnableAllWhenDisconnect = false;

    //M: for hotspot optimization
    private boolean mHotspotOptimization = false;
    //M: for emmode - disable framework scan when connected
    private boolean mAutoJoinScanWhenConnected = true;
    //M: for country code
    private String mOperatorNumeric;
    private String mCountryCode = null;
    ///M: ALPS01940840 new bssid maybe driver roaming
    private boolean mIsNewAssociatedBssid = false;
    //M: ALPS01975084 for auto connect EAP-SIM AP
    private String mIccState = IccCardConstants.INTENT_VALUE_ICC_UNKNOWN;


    final static int frameworkMinScanIntervalSaneValue = 10000;

    boolean mPnoEnabled;
    boolean mLazyRoamEnabled;
    long mGScanStartTimeMilli;
    long mGScanPeriodMilli;

    ///M: ALPS02043890 for check CMD_UPDATE_LINKPROPERTIES is in queue or not
    private Handler mHandler = getHandler();

    ///M: ALPS02575372 For logging link statics in bug report
    String linkstatics ="";


    ///M: group scan: group 3rd party APK scan on Connected state for better performance
    private long mLastFullScanStartTimeMilli = 0;
    private static int mGroupScanDurationMs = 7 * 1000;
    private PendingIntent mGroupScanIntent;
    private static final int GROUP_SCAN_REQUEST = 0;
    private static final String ACTION_START_GROUP_SCAN =
            "com.android.server.WifiManager.action.GROUP_START_SCAN";
    private boolean mGroupAlarmEnabled = false;
    private int mGroupBufferedScanMsgCount = 0;
    private AtomicInteger mGroupScanCounter = new AtomicInteger();


    public WifiStateMachine(Context context, String wlanInterface,
                            WifiTrafficPoller trafficPoller) {
        super("WifiStateMachine");
        ////M: @{
        mWifiFwkExt = MPlugin.createInstance(IWifiFwkExt.class.getName(), context);
        ///@}
        mContext = context;
        mSetCountryCode = Settings.Global.getString(
                mContext.getContentResolver(), Settings.Global.WIFI_COUNTRY_CODE);
        mInterfaceName = wlanInterface;
        mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_WIFI, 0, NETWORKTYPE, "");
        mBatteryStats = IBatteryStats.Stub.asInterface(ServiceManager.getService(
                BatteryStats.SERVICE_NAME));

        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        mNwService = INetworkManagementService.Stub.asInterface(b);

        mP2pSupported = mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WIFI_DIRECT);

        mWifiNative = new WifiNative(mInterfaceName);
        mWifiConfigStore = new WifiConfigStore(context,this,  mWifiNative);
        mWifiAutoJoinController = new WifiAutoJoinController(context, this,
                mWifiConfigStore, mWifiConnectionStatistics, mWifiNative, getHandler());
        ///M: init plugin
        mWifiAutoJoinController.setWifiFwkExt(mWifiFwkExt);

        mWifiMonitor = new WifiMonitor(this, mWifiNative);
        mWifiLogger = new WifiLogger(this);

        mWifiInfo = new WifiInfo();
        mSupplicantStateTracker = new SupplicantStateTracker(context, this, mWifiConfigStore,
                getHandler());
        mLinkProperties = new LinkProperties();

        IBinder s1 = ServiceManager.getService(Context.WIFI_P2P_SERVICE);
        mWifiP2pServiceImpl = (WifiP2pServiceImpl) IWifiP2pManager.Stub.asInterface(s1);

        IBinder s2 = ServiceManager.getService(Context.WIFI_PASSPOINT_SERVICE);

        mNetworkInfo.setIsAvailable(false);
        mLastBssid = null;
        mLastNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
        mLastSignalLevel = -1;

        mNetlinkTracker = new NetlinkTracker(mInterfaceName, new NetlinkTracker.Callback() {
            public void update() {
                ///M: ALPS02043890 if CMD_UPDATE_LINKPROPERTIES exist, no sendMsg
                if (!mHandler.hasMessages(CMD_UPDATE_LINKPROPERTIES)) {
                    loge("There is no CMD_UPDATE_LINKPROPERTIES in queue");
                    ///M: ALPS02468871 Delay message for prevent ip config lost timming issue
                    //sendMessage(CMD_UPDATE_LINKPROPERTIES);
                    sendMessageDelayed(CMD_UPDATE_LINKPROPERTIES, 100);
                } else {
                    loge("CMD_UPDATE_LINKPROPERTIES already in queue, no sendMessage");
                }
            }
        });
        try {
            mNwService.registerObserver(mNetlinkTracker);
        } catch (RemoteException e) {
            loge("Couldn't register netlink tracker: " + e.toString());
        }

        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        mScanIntent = getPrivateBroadcast(ACTION_START_SCAN, SCAN_REQUEST);
        mPnoIntent = getPrivateBroadcast(ACTION_START_PNO, PNO_START_REQUEST);


        ///M:@{
        if (mWifiFwkExt != null) {
            mDefaultFrameworkScanIntervalMs = mWifiFwkExt.defaultFrameworkScanIntervalMs();
        } else {
            // Make sure the interval is not configured less than 10 seconds
            int period = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_scan_interval);
            if (period < frameworkMinScanIntervalSaneValue) {
                period = frameworkMinScanIntervalSaneValue;
            }
            mDefaultFrameworkScanIntervalMs = period;
        }
        ///@}

        mNoNetworksPeriodicScan = mContext.getResources().getInteger(
                R.integer.config_wifi_no_network_periodic_scan_interval);

        mDriverStopDelayMs = mContext.getResources().getInteger(
                R.integer.config_wifi_driver_stop_delay);

        mBackgroundScanSupported = mContext.getResources().getBoolean(
                R.bool.config_wifi_background_scan_support);

        mPrimaryDeviceType = mContext.getResources().getString(
                R.string.config_wifi_p2p_device_type);

        mUserWantsSuspendOpt.set(Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.WIFI_SUSPEND_OPTIMIZATIONS_ENABLED, 1) == 1);

        mNetworkCapabilitiesFilter.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        mNetworkCapabilitiesFilter.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        mNetworkCapabilitiesFilter.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
        mNetworkCapabilitiesFilter.setLinkUpstreamBandwidthKbps(1024 * 1024);
        mNetworkCapabilitiesFilter.setLinkDownstreamBandwidthKbps(1024 * 1024);
        // TODO - needs to be a bit more dynamic
        mNetworkCapabilities = new NetworkCapabilities(mNetworkCapabilitiesFilter);

        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        ArrayList<String> available = intent.getStringArrayListExtra(
                                ConnectivityManager.EXTRA_AVAILABLE_TETHER);
                        ArrayList<String> active = intent.getStringArrayListExtra(
                                ConnectivityManager.EXTRA_ACTIVE_TETHER);
                        sendMessage(CMD_TETHER_STATE_CHANGE, new TetherStateChange(available, active));
                    }
                }, new IntentFilter(ConnectivityManager.ACTION_TETHER_STATE_CHANGED));

        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        sScanAlarmIntentCount++; // Used for debug only
                        startScan(SCAN_ALARM_SOURCE, mDelayedScanCounter.incrementAndGet(), null, null);
                        if (VDBG)
                            logd("SCAN ALARM -> " + mDelayedScanCounter.get());
                    }
                },
                new IntentFilter(ACTION_START_SCAN));

        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        sendMessage(CMD_RESTART_AUTOJOIN_OFFLOAD, 0,
                                mRestartAutoJoinOffloadCounter, "pno alarm");
                        if (DBG)
                            logd("PNO START ALARM sent");
                    }
                },
                new IntentFilter(ACTION_START_PNO));


        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();
                        Log.d(TAG, "onReceive, action:" + action);
                        if (action.equals(Intent.ACTION_SCREEN_ON)) {
                            sendMessage(CMD_SCREEN_STATE_CHANGED, 1);
                        } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                            sendMessage(CMD_SCREEN_STATE_CHANGED, 0);
                        }
                    }
                }, filter);

        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        int counter = intent.getIntExtra(DELAYED_STOP_COUNTER, 0);
                        sendMessage(CMD_DELAYED_STOP_DRIVER, counter, 0);
                    }
                },
                new IntentFilter(ACTION_DELAYED_DRIVER_STOP));

        mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor(
                        Settings.Global.WIFI_SUSPEND_OPTIMIZATIONS_ENABLED), false,
                new ContentObserver(getHandler()) {
                    @Override
                    public void onChange(boolean selfChange) {
                        mUserWantsSuspendOpt.set(Settings.Global.getInt(mContext.getContentResolver(),
                                Settings.Global.WIFI_SUSPEND_OPTIMIZATIONS_ENABLED, 1) == 1);
                    }
                });

        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        sendMessage(CMD_BOOT_COMPLETED);
                    }
                },
                new IntentFilter(Intent.ACTION_BOOT_COMPLETED));

        mScanResultCache = new LruCache<>(SCAN_RESULT_CACHE_SIZE);

        PowerManager powerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getName());

        mSuspendWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WifiSuspend");
        mSuspendWakeLock.setReferenceCounted(false);

        mTcpBufferSizes = mContext.getResources().getString(
                com.android.internal.R.string.config_wifi_tcp_buffers);

        addState(mDefaultState);
            addState(mInitialState, mDefaultState);
            addState(mSupplicantStartingState, mDefaultState);
            addState(mSupplicantStartedState, mDefaultState);
                addState(mDriverStartingState, mSupplicantStartedState);
                addState(mDriverStartedState, mSupplicantStartedState);
                    addState(mScanModeState, mDriverStartedState);
                    addState(mConnectModeState, mDriverStartedState);
                        addState(mL2ConnectedState, mConnectModeState);
                            addState(mObtainingIpState, mL2ConnectedState);
                            addState(mVerifyingLinkState, mL2ConnectedState);
                            addState(mConnectedState, mL2ConnectedState);
                            addState(mRoamingState, mL2ConnectedState);
                        addState(mDisconnectingState, mConnectModeState);
                        addState(mDisconnectedState, mConnectModeState);
                        addState(mWpsRunningState, mConnectModeState);
                addState(mWaitForP2pDisableState, mSupplicantStartedState);
                addState(mDriverStoppingState, mSupplicantStartedState);
                addState(mDriverStoppedState, mSupplicantStartedState);
            addState(mSupplicantStoppingState, mDefaultState);
            addState(mSoftApStartingState, mDefaultState);
            addState(mSoftApStartedState, mDefaultState);
                addState(mTetheringState, mSoftApStartedState);
                addState(mTetheredState, mSoftApStartedState);
                addState(mUntetheringState, mSoftApStartedState);

        setInitialState(mInitialState);

        ///M@{
        initializeExtra();
        ///@}


        setLogRecSize(ActivityManager.isLowRamDeviceStatic() ? 100 : 3000);
        setLogOnlyTransitions(false);
        //if (DBG) setDbg(true);

        //start the state machine
        start();

        final Intent intent = new Intent(WifiManager.WIFI_SCAN_AVAILABLE);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_SCAN_AVAILABLE, WIFI_STATE_DISABLED);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }


    PendingIntent getPrivateBroadcast(String action, int requestCode) {
        Intent intent = new Intent(action, null);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        //intent.setPackage(this.getClass().getPackage().getName());
        intent.setPackage("android");
        return PendingIntent.getBroadcast(mContext, requestCode, intent, 0);
    }

    private int mVerboseLoggingLevel = 0;

    int getVerboseLoggingLevel() {
        return mVerboseLoggingLevel;
    }

    void enableVerboseLogging(int verbose) {
        mVerboseLoggingLevel = verbose;
        if (verbose > 0) {
            DBG = true;
            VDBG = true;
            PDBG = true;
            mLogMessages = true;
            mWifiNative.setSupplicantLogLevel("DEBUG");
            ///M: for autojoin profiling
            sendMessage(M_CMD_START_AUTOJOIN_PROFILING, 1, 0);
        } else {
            DBG = false;
            VDBG = false;
            PDBG = false;
            mLogMessages = false;
            mWifiNative.setSupplicantLogLevel("INFO");
            ///M: for autojoin profiling
            sendMessage(M_CMD_START_AUTOJOIN_PROFILING, 0, 0);
        }
        mWifiLogger.startLogging(mVerboseLoggingLevel > 0);
        mWifiAutoJoinController.enableVerboseLogging(verbose);
        mWifiMonitor.enableVerboseLogging(verbose);
        mWifiNative.enableVerboseLogging(verbose);
        mWifiConfigStore.enableVerboseLogging(verbose);
        mSupplicantStateTracker.enableVerboseLogging(verbose);

        ///M: don't disable normal log @{
        DBG = true;
        mLogMessages = true;
        ///@}
    }

    public void setHalBasedAutojoinOffload(int enabled) {
        // Shoult be used for debug only, triggered form developper settings
        // enabling HAl based PNO dynamically is not safe and not a normal operation
        mHalBasedPnoEnableInDevSettings = enabled > 0;
        mWifiConfigStore.enableHalBasedPno.set(mHalBasedPnoEnableInDevSettings);
        mWifiConfigStore.enableSsidWhitelist.set(mHalBasedPnoEnableInDevSettings);
        sendMessage(CMD_DISCONNECT);
    }

    int getHalBasedAutojoinOffload() {
        return mHalBasedPnoEnableInDevSettings ? 1 : 0;
    }

    boolean useHalBasedAutoJoinOffload() {
        // all three settings need to be true:
        // - developper settings switch
        // - driver support
        // - config option
        return mHalBasedPnoEnableInDevSettings
                && mHalBasedPnoDriverSupported
                && mWifiConfigStore.enableHalBasedPno.get();
    }

    boolean allowFullBandScanAndAssociated() {

        if (!getEnableAutoJoinWhenAssociated()) {
            if (DBG) {
                Log.e(TAG, "allowFullBandScanAndAssociated: "
                        + " enableAutoJoinWhenAssociated : disallow");
            }
            return false;
        }

        if (mWifiInfo.txSuccessRate >
                mWifiConfigStore.maxTxPacketForFullScans
                || mWifiInfo.rxSuccessRate >
                mWifiConfigStore.maxRxPacketForFullScans) {
            if (DBG) {
                Log.e(TAG, "allowFullBandScanAndAssociated: packet rate tx"
                        + mWifiInfo.txSuccessRate + "  rx "
                        + mWifiInfo.rxSuccessRate
                        + " allow scan with traffic " + getAllowScansWithTraffic());
            }
            // Too much traffic at the interface, hence no full band scan
            if (getAllowScansWithTraffic() == 0) {
                return false;
            }
        }

        if (getCurrentState() != mConnectedState) {
            if (DBG) {
                Log.e(TAG, "allowFullBandScanAndAssociated: getCurrentState() : disallow");
            }
            return false;
        }

        return true;
    }

    long mLastScanPermissionUpdate = 0;
    boolean mConnectedModeGScanOffloadStarted = false;
    // Don't do a G-scan enable/re-enable cycle more than once within 20seconds
    // The function updateAssociatedScanPermission() can be called quite frequently, hence
    // we want to throttle the GScan Stop->Start transition
    static final long SCAN_PERMISSION_UPDATE_THROTTLE_MILLI = 20000;
    void updateAssociatedScanPermission() {

        if (useHalBasedAutoJoinOffload()) {
            boolean allowed = allowFullBandScanAndAssociated();

            long now = System.currentTimeMillis();
            if (mConnectedModeGScanOffloadStarted && !allowed) {
                if (DBG) {
                    Log.e(TAG, " useHalBasedAutoJoinOffload stop offload");
                }
                stopPnoOffload();
                stopGScan(" useHalBasedAutoJoinOffload");
            }
            if (!mConnectedModeGScanOffloadStarted && allowed) {
                if ((now - mLastScanPermissionUpdate) > SCAN_PERMISSION_UPDATE_THROTTLE_MILLI) {
                    // Re-enable Gscan offload, this will trigger periodic scans and allow firmware
                    // to look for 5GHz BSSIDs and better networks
                    if (DBG) {
                        Log.e(TAG, " useHalBasedAutoJoinOffload restart offload");
                    }
                    startGScanConnectedModeOffload("updatePermission "
                            + (now - mLastScanPermissionUpdate) + "ms");
                    mLastScanPermissionUpdate = now;
                }
            }
        }
    }

    private int mAggressiveHandover = 0;

    int getAggressiveHandover() {
        return mAggressiveHandover;
    }

    void enableAggressiveHandover(int enabled) {
        mAggressiveHandover = enabled;
    }

    public void clearANQPCache() {
        mWifiConfigStore.trimANQPCache(true);
    }

    public void setAllowScansWithTraffic(int enabled) {
        mWifiConfigStore.alwaysEnableScansWhileAssociated.set(enabled);
    }

    public int getAllowScansWithTraffic() {
        return mWifiConfigStore.alwaysEnableScansWhileAssociated.get();
    }

    public boolean enableAutoJoinWhenAssociated(boolean enabled) {
        boolean old_state = getEnableAutoJoinWhenAssociated();
        mWifiConfigStore.enableAutoJoinWhenAssociated.set(enabled);
        if (!old_state && enabled && mScreenOn && getCurrentState() == mConnectedState) {
            startDelayedScan(mWifiConfigStore.wifiAssociatedShortScanIntervalMilli.get(), null,
                    null);
        }
        return true;
    }

    public boolean getEnableAutoJoinWhenAssociated() {
        return mWifiConfigStore.enableAutoJoinWhenAssociated.get();
    }
    /*
     *
     * Framework scan control
     */

    private boolean mAlarmEnabled = false;

    private AtomicInteger mDelayedScanCounter = new AtomicInteger();

    private void setScanAlarm(boolean enabled) {
        if (PDBG) {
            String state;
            if (enabled) state = "enabled"; else state = "disabled";
            logd("setScanAlarm " + state
                    + " defaultperiod " + mDefaultFrameworkScanIntervalMs
                    + " mBackgroundScanSupported " + mBackgroundScanSupported);
        }
        if (mBackgroundScanSupported == false) {
            // Scan alarm is only used for background scans if they are not
            // offloaded to the wifi chipset, hence enable the scan alarm
            // gicing us RTC_WAKEUP of backgroundScan is NOT supported
            enabled = true;
        }

        if (enabled == mAlarmEnabled) return;
        if (enabled) {
            /* Set RTC_WAKEUP alarms if PNO is not supported - because no one is */
            /* going to wake up the host processor to look for access points */
            mAlarmManager.set(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + mDefaultFrameworkScanIntervalMs,
                    mScanIntent);
            mAlarmEnabled = true;
        } else {
            mAlarmManager.cancel(mScanIntent);
            mAlarmEnabled = false;
        }
    }

    private void cancelDelayedScan() {
        mDelayedScanCounter.incrementAndGet();
    }

    private boolean checkAndRestartDelayedScan(int counter, boolean restart, int milli,
                                               ScanSettings settings, WorkSource workSource) {

        if (counter != mDelayedScanCounter.get()) {
            return false;
        }
        if (restart)
            startDelayedScan(milli, settings, workSource);
        return true;
    }

    private void startDelayedScan(int milli, ScanSettings settings, WorkSource workSource) {
        if (milli <= 0) return;
        /**
         * The cases where the scan alarm should be run are :
         * - DisconnectedState && screenOn => used delayed timer
         * - DisconnectedState && !screenOn && mBackgroundScanSupported => PNO
         * - DisconnectedState && !screenOn && !mBackgroundScanSupported => used RTC_WAKEUP Alarm
         * - ConnectedState && screenOn => used delayed timer
         */

        mDelayedScanCounter.incrementAndGet();
        if (mScreenOn &&
                (getCurrentState() == mDisconnectedState
                        || getCurrentState() == mConnectedState)) {
            Bundle bundle = new Bundle();
            bundle.putParcelable(CUSTOMIZED_SCAN_SETTING, settings);
            bundle.putParcelable(CUSTOMIZED_SCAN_WORKSOURCE, workSource);
            bundle.putLong(SCAN_REQUEST_TIME, System.currentTimeMillis());
            sendMessageDelayed(CMD_START_SCAN, SCAN_ALARM_SOURCE,
                    mDelayedScanCounter.get(), bundle, milli);
            if (DBG) logd("startDelayedScan send -> " + mDelayedScanCounter + " milli " + milli);
        } else if (mBackgroundScanSupported == false
                && !mScreenOn && getCurrentState() == mDisconnectedState) {
            setScanAlarm(true);
            if (DBG) logd("startDelayedScan start scan alarm -> "
                    + mDelayedScanCounter + " milli " + milli);
        } else {
            if (DBG) logd("startDelayedScan unhandled -> "
                    + mDelayedScanCounter + " milli " + milli);
        }
    }

    private boolean setRandomMacOui() {
        String oui = mContext.getResources().getString(
                R.string.config_wifi_random_mac_oui, GOOGLE_OUI);
        String[] ouiParts = oui.split("-");
        byte[] ouiBytes = new byte[3];
        ouiBytes[0] = (byte) (Integer.parseInt(ouiParts[0], 16) & 0xFF);
        ouiBytes[1] = (byte) (Integer.parseInt(ouiParts[1], 16) & 0xFF);
        ouiBytes[2] = (byte) (Integer.parseInt(ouiParts[2], 16) & 0xFF);

        logd("Setting OUI to " + oui);
        return mWifiNative.setScanningMacOui(ouiBytes);
    }

    /**
     * ******************************************************
     * Methods exposed for public use
     * ******************************************************
     */

    public Messenger getMessenger() {
        return new Messenger(getHandler());
    }

    public WifiMonitor getWifiMonitor() {
        return mWifiMonitor;
    }

    /**
     * TODO: doc
     */
    public boolean syncPingSupplicant(AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_PING_SUPPLICANT);
        boolean result = (resultMsg.arg1 != FAILURE);
        resultMsg.recycle();
        return result;
    }

    public List<WifiChannel> syncGetChannelList(AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_GET_CAPABILITY_FREQ);
        List<WifiChannel> list = null;
        if (resultMsg.obj != null) {
            list = new ArrayList<WifiChannel>();
            String freqs = (String) resultMsg.obj;
            String[] lines = freqs.split("\n");
            for (String line : lines)
                if (line.contains("MHz")) {
                    // line format: " 52 = 5260 MHz (NO_IBSS) (DFS)"
                    WifiChannel c = new WifiChannel();
                    String[] prop = line.split(" ");
                    if (prop.length < 5) continue;
                    try {
                        c.channelNum = Integer.parseInt(prop[1]);
                        c.freqMHz = Integer.parseInt(prop[3]);
                    } catch (NumberFormatException e) {
                    }
                    c.isDFS = line.contains("(DFS)");
                    list.add(c);
                } else if (line.contains("Mode[B] Channels:")) {
                    // B channels are the same as G channels, skipped
                    break;
                }
        }
        resultMsg.recycle();
        return (list != null && list.size() > 0) ? list : null;
    }

    /**
     * When settings allowing making use of untrusted networks change, trigger a scan
     * so as to kick of autojoin.
     */
    public void startScanForUntrustedSettingChange() {
        startScan(SET_ALLOW_UNTRUSTED_SOURCE, 0, null, null);
    }

    /**
     * Initiate a wifi scan. If workSource is not null, blame is given to it, otherwise blame is
     * given to callingUid.
     *
     * @param callingUid The uid initiating the wifi scan. Blame will be given here unless
     *                   workSource is specified.
     * @param workSource If not null, blame is given to workSource.
     * @param settings   Scan settings, see {@link ScanSettings}.
     */
    public void startScan(int callingUid, int scanCounter,
                          ScanSettings settings, WorkSource workSource) {
        Bundle bundle = new Bundle();
        bundle.putParcelable(CUSTOMIZED_SCAN_SETTING, settings);
        bundle.putParcelable(CUSTOMIZED_SCAN_WORKSOURCE, workSource);
        bundle.putLong(SCAN_REQUEST_TIME, System.currentTimeMillis());
        sendMessage(CMD_START_SCAN, callingUid, scanCounter, bundle);
    }

    // called from BroadcastListener

    /**
     * Start reading new scan data
     * Data comes in as:
     * "scancount=5\n"
     * "nextcount=5\n"
     * "apcount=3\n"
     * "trunc\n" (optional)
     * "bssid=...\n"
     * "ssid=...\n"
     * "freq=...\n" (in Mhz)
     * "level=...\n"
     * "dist=...\n" (in cm)
     * "distsd=...\n" (standard deviation, in cm)
     * "===="
     * "bssid=...\n"
     * etc
     * "===="
     * "bssid=...\n"
     * etc
     * "%%%%"
     * "apcount=2\n"
     * "bssid=...\n"
     * etc
     * "%%%%
     * etc
     * "----"
     */
    private final static boolean DEBUG_PARSE = false;

    private long mDisconnectedTimeStamp = 0;

    public long getDisconnectedTimeMilli() {
        if (getCurrentState() == mDisconnectedState
                && mDisconnectedTimeStamp != 0) {
            long now_ms = System.currentTimeMillis();
            return now_ms - mDisconnectedTimeStamp;
        }
        return 0;
    }

    // Keeping track of scan requests
    private long lastStartScanTimeStamp = 0;
    private long lastScanDuration = 0;
    // Last connect attempt is used to prevent scan requests:
    //  - for a period of 10 seconds after attempting to connect
    private long lastConnectAttemptTimestamp = 0;
    private String lastScanFreqs = null;

    // For debugging, keep track of last message status handling
    // TODO, find an equivalent mechanism as part of parent class
    private static int MESSAGE_HANDLING_STATUS_PROCESSED = 2;
    private static int MESSAGE_HANDLING_STATUS_OK = 1;
    private static int MESSAGE_HANDLING_STATUS_UNKNOWN = 0;
    private static int MESSAGE_HANDLING_STATUS_REFUSED = -1;
    private static int MESSAGE_HANDLING_STATUS_FAIL = -2;
    private static int MESSAGE_HANDLING_STATUS_OBSOLETE = -3;
    private static int MESSAGE_HANDLING_STATUS_DEFERRED = -4;
    private static int MESSAGE_HANDLING_STATUS_DISCARD = -5;
    private static int MESSAGE_HANDLING_STATUS_LOOPED = -6;
    private static int MESSAGE_HANDLING_STATUS_HANDLING_ERROR = -7;

    private int messageHandlingStatus = 0;

    //TODO: this is used only to track connection attempts, however the link state and packet per
    //TODO: second logic should be folded into that
    private boolean checkOrDeferScanAllowed(Message msg) {
        long now = System.currentTimeMillis();
        if (lastConnectAttemptTimestamp != 0 && (now - lastConnectAttemptTimestamp) < 10000) {
            Message dmsg = Message.obtain(msg);
            sendMessageDelayed(dmsg, 11000 - (now - lastConnectAttemptTimestamp));
            return false;
        }
        return true;
    }

    private int mOnTime = 0;
    private int mTxTime = 0;
    private int mRxTime = 0;
    private int mOnTimeStartScan = 0;
    private int mTxTimeStartScan = 0;
    private int mRxTimeStartScan = 0;
    private int mOnTimeScan = 0;
    private int mTxTimeScan = 0;
    private int mRxTimeScan = 0;
    private int mOnTimeThisScan = 0;
    private int mTxTimeThisScan = 0;
    private int mRxTimeThisScan = 0;

    private int mOnTimeScreenStateChange = 0;
    private int mOnTimeAtLastReport = 0;
    private long lastOntimeReportTimeStamp = 0;
    private long lastScreenStateChangeTimeStamp = 0;
    private int mOnTimeLastReport = 0;
    private int mTxTimeLastReport = 0;
    private int mRxTimeLastReport = 0;

    private long lastLinkLayerStatsUpdate = 0;

    String reportOnTime() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        // Report stats since last report
        int on = mOnTime - mOnTimeLastReport;
        mOnTimeLastReport = mOnTime;
        int tx = mTxTime - mTxTimeLastReport;
        mTxTimeLastReport = mTxTime;
        int rx = mRxTime - mRxTimeLastReport;
        mRxTimeLastReport = mRxTime;
        int period = (int) (now - lastOntimeReportTimeStamp);
        lastOntimeReportTimeStamp = now;
        sb.append(String.format("[on:%d tx:%d rx:%d period:%d]", on, tx, rx, period));
        // Report stats since Screen State Changed
        on = mOnTime - mOnTimeScreenStateChange;
        period = (int) (now - lastScreenStateChangeTimeStamp);
        sb.append(String.format(" from screen [on:%d period:%d]", on, period));
        return sb.toString();
    }

    WifiLinkLayerStats getWifiLinkLayerStats(boolean dbg) {
        WifiLinkLayerStats stats = null;
        if (mWifiLinkLayerStatsSupported > 0) {
            String name = "wlan0";
            stats = mWifiNative.getWifiLinkLayerStats(name);
            if (name != null && stats == null && mWifiLinkLayerStatsSupported > 0) {
                mWifiLinkLayerStatsSupported -= 1;
            } else if (stats != null) {
                lastLinkLayerStatsUpdate = System.currentTimeMillis();
                mOnTime = stats.on_time;
                mTxTime = stats.tx_time;
                mRxTime = stats.rx_time;
                mRunningBeaconCount = stats.beacon_rx;
            }
        }
        if (stats == null || mWifiLinkLayerStatsSupported <= 0) {
            long mTxPkts = TrafficStats.getTxPackets(mInterfaceName);
            long mRxPkts = TrafficStats.getRxPackets(mInterfaceName);
            mWifiInfo.updatePacketRates(mTxPkts, mRxPkts);
        } else {
            mWifiInfo.updatePacketRates(stats);
        }
        if (useHalBasedAutoJoinOffload()) {
            sendMessage(CMD_UPDATE_ASSOCIATED_SCAN_PERMISSION);
        }
        return stats;
    }

    void startRadioScanStats() {
        WifiLinkLayerStats stats = getWifiLinkLayerStats(false);
        if (stats != null) {
            mOnTimeStartScan = stats.on_time;
            mTxTimeStartScan = stats.tx_time;
            mRxTimeStartScan = stats.rx_time;
            mOnTime = stats.on_time;
            mTxTime = stats.tx_time;
            mRxTime = stats.rx_time;
        }
    }

    void closeRadioScanStats() {
        WifiLinkLayerStats stats = getWifiLinkLayerStats(false);
        if (stats != null) {
            mOnTimeThisScan = stats.on_time - mOnTimeStartScan;
            mTxTimeThisScan = stats.tx_time - mTxTimeStartScan;
            mRxTimeThisScan = stats.rx_time - mRxTimeStartScan;
            mOnTimeScan += mOnTimeThisScan;
            mTxTimeScan += mTxTimeThisScan;
            mRxTimeScan += mRxTimeThisScan;
        }
    }

    // If workSource is not null, blame is given to it, otherwise blame is given to callingUid.
    private void noteScanStart(int callingUid, WorkSource workSource) {
        long now = System.currentTimeMillis();
        lastStartScanTimeStamp = now;
        lastScanDuration = 0;
        if (DBG) {
            String ts = String.format("[%,d ms]", now);
            if (workSource != null) {
                if (DBG) logd(ts + " noteScanStart" + workSource.toString()
                        + " uid " + Integer.toString(callingUid));
            } else {
                if (DBG) logd(ts + " noteScanstart no scan source"
                        + " uid " + Integer.toString(callingUid));
            }
        }
        startRadioScanStats();
        if (mScanWorkSource == null && ((callingUid != UNKNOWN_SCAN_SOURCE
                && callingUid != SCAN_ALARM_SOURCE)
                || workSource != null)) {
            mScanWorkSource = workSource != null ? workSource : new WorkSource(callingUid);

            WorkSource batteryWorkSource = mScanWorkSource;
            if (mScanWorkSource.size() == 1 && mScanWorkSource.get(0) < 0) {
                // WiFi uses negative UIDs to mean special things. BatteryStats don't care!
                batteryWorkSource = new WorkSource(Process.WIFI_UID);
            }

            try {
                mBatteryStats.noteWifiScanStartedFromSource(batteryWorkSource);
            } catch (RemoteException e) {
                log(e.toString());
            }
        }
    }

    private void noteScanEnd() {
        long now = System.currentTimeMillis();
        if (lastStartScanTimeStamp != 0) {
            lastScanDuration = now - lastStartScanTimeStamp;
        }
        lastStartScanTimeStamp = 0;
        if (DBG) {
            String ts = String.format("[%,d ms]", now);
            if (mScanWorkSource != null)
                logd(ts + " noteScanEnd " + mScanWorkSource.toString()
                        + " onTime=" + mOnTimeThisScan);
            else
                logd(ts + " noteScanEnd no scan source"
                        + " onTime=" + mOnTimeThisScan);
        }
        if (mScanWorkSource != null) {
            try {
                mBatteryStats.noteWifiScanStoppedFromSource(mScanWorkSource);
            } catch (RemoteException e) {
                log(e.toString());
            } finally {
                mScanWorkSource = null;
            }
        }
    }

    private void clearGroupScanStatus() {
        //clear group scan status
        mGroupBufferedScanMsgCount = 0;
        if (mGroupAlarmEnabled == true) mAlarmManager.cancel(mGroupScanIntent);
        mGroupAlarmEnabled = false;

    }
    private void handleScanRequest(int type, Message message) {
        ScanSettings settings = null;
        WorkSource workSource = null;


        log("handleScanRequest in type= " + type + "message = " + message);

        // unbundle parameters
        Bundle bundle = (Bundle) message.obj;

        if (bundle != null) {
            settings = bundle.getParcelable(CUSTOMIZED_SCAN_SETTING);
            workSource = bundle.getParcelable(CUSTOMIZED_SCAN_WORKSOURCE);
        }

        // parse scan settings
        String freqs = null;
        if (settings != null && settings.channelSet != null) {

            log("handleScanRequest settings=" + settings);
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (WifiChannel channel : settings.channelSet) {
                if (!first) sb.append(',');
                else first = false;
                sb.append(channel.freqMHz);
            }
            freqs = sb.toString();
            log("handleScanRequest freqs=" + freqs);
        }

        ///M:group 3rd party APK full scan on Connected state for better performance
        boolean shouldScan = true;
        if (mGroupScanDurationMs <= 0) {
            // geoup scan disabled
        } else if ( message.arg1 == GROUP_SCAN_SOURCE) {

            log("GROUP_SCAN_SOURCE mGroupBufferedScanMsgCount = " + mGroupBufferedScanMsgCount);
            shouldScan = true;
            clearGroupScanStatus();
        } else if (getCurrentState() != mConnectedState) {

            log("getCurrentState() != mConnectedState");
            if( mGroupBufferedScanMsgCount > 0) {
                log("not in connected state but group scan is in queue, start a full scan");
                freqs = null;
                clearGroupScanStatus();
            }
        } else if (getCurrentState() == mConnectedState) {
            log("getCurrentState() == mConnectedState");
            if (message.arg1 > 1000 && freqs == null) {  // scan source is 3rd party
                long now = System.currentTimeMillis();
                long timeDiff = now - mLastFullScanStartTimeMilli;
                if (timeDiff > mGroupScanDurationMs) {
                    log("scan source is 3rd party diff= "+ timeDiff);
                    shouldScan = true;
                    clearGroupScanStatus();

                } else {
                    mGroupBufferedScanMsgCount += 1;
                    shouldScan = false;
                    //set alarm
                    if (mGroupAlarmEnabled == false) {
                        long nextGroupScanMilli = mGroupScanDurationMs - timeDiff;
                        log("schedule next group scan in " + timeDiff);
                        mAlarmManager.setExact(AlarmManager.RTC_WAKEUP,
                               System.currentTimeMillis() + nextGroupScanMilli,
                               mGroupScanIntent);
                        mGroupAlarmEnabled = true;
                    } else {
                        log("group scan alarm is ongoing");
                    }
                }
            }
            else if (message.arg1 <= 1000) {// FWK scan
                if( mGroupBufferedScanMsgCount > 0 && freqs == null) {
                    clearGroupScanStatus();
                }
            }
         }


        if (shouldScan == false) {
            log("don't scan");
            return;
        }
        ///@}

        // call wifi native to start the scan
        if (startScanNative(type, freqs, message.arg1)) {
            //M: group scan @[
            if (freqs == null) {
                mLastFullScanStartTimeMilli = System.currentTimeMillis();
            }
            ///@}
            // only count battery consumption if scan request is accepted
            noteScanStart(message.arg1, workSource);
            // a full scan covers everything, clearing scan request buffer
            if (freqs == null)
                mBufferedScanMsg.clear();
            messageHandlingStatus = MESSAGE_HANDLING_STATUS_OK;
            if (workSource != null) {
                // External worksource was passed along the scan request,
                // hence always send a broadcast
                mSendScanResultsBroadcast = true;
            }
            return;
        }

        // if reach here, scan request is rejected

        if (!mIsScanOngoing) {
            // if rejection is NOT due to ongoing scan (e.g. bad scan parameters),

            // discard this request and pop up the next one
            if (mBufferedScanMsg.size() > 0) {
                sendMessage(mBufferedScanMsg.remove());
            }
            messageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
        } else if (!mIsFullScanOngoing) {
            // if rejection is due to an ongoing scan, and the ongoing one is NOT a full scan,
            // buffer the scan request to make sure specified channels will be scanned eventually
            if (freqs == null)
                mBufferedScanMsg.clear();
            if (mBufferedScanMsg.size() < SCAN_REQUEST_BUFFER_MAX_SIZE) {
                Message msg = obtainMessage(CMD_START_SCAN,
                        message.arg1, message.arg2, bundle);
                mBufferedScanMsg.add(msg);
            } else {
                // if too many requests in buffer, combine them into a single full scan
                bundle = new Bundle();
                bundle.putParcelable(CUSTOMIZED_SCAN_SETTING, null);
                bundle.putParcelable(CUSTOMIZED_SCAN_WORKSOURCE, workSource);
                Message msg = obtainMessage(CMD_START_SCAN, message.arg1, message.arg2, bundle);
                mBufferedScanMsg.clear();
                mBufferedScanMsg.add(msg);
            }
            messageHandlingStatus = MESSAGE_HANDLING_STATUS_LOOPED;
        } else {
            // mIsScanOngoing and mIsFullScanOngoing
            messageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
        }
    }


    /**
     * return true iff scan request is accepted
     */
    private boolean startScanNative(int type, String freqs, int scanType) {
        //M: notice that we won't block scan request from outside wifi framework
        if (isTemporarilyDontReconnectWifi() &&
            (scanType == SCAN_ALARM_SOURCE || scanType == UNKNOWN_SCAN_SOURCE)) {
            log("startScanNative refused due to  isTemporarilyDontReconnectWifi");
            return false;
        }
        if (mWifiNative.scan(type, freqs)) {
            mIsScanOngoing = true;
            mIsFullScanOngoing = (freqs == null);
            lastScanFreqs = freqs;
            return true;
        //M:@{
        } else if (mIsSupplicantFirstScan == true &&  (freqs == null)) {
            SupplicantState state = mWifiInfo.getSupplicantState();
            if (!(state == SupplicantState.INTERFACE_DISABLED
                || state == SupplicantState.ASSOCIATING
                || state == SupplicantState.AUTHENTICATING
                || state == SupplicantState.FOUR_WAY_HANDSHAKE
                || state == SupplicantState.GROUP_HANDSHAKE
                || state == SupplicantState.ASSOCIATED)) {
                //supplicant should be in scanning state
                mIsScanOngoing = true;
                mIsFullScanOngoing = true;
                mIsSupplicantFirstScan = false;
                log("mIsSupplicantFirstScan");
            }
        }
        return false;
    }

    /**
     * TODO: doc
     */
    public void setSupplicantRunning(boolean enable) {
        if (enable) {
            sendMessage(CMD_START_SUPPLICANT);
        } else {
            sendMessage(CMD_STOP_SUPPLICANT);
        }
    }

    /**
     * TODO: doc
     */
    public void setHostApRunning(WifiConfiguration wifiConfig, boolean enable) {
        if (enable) {
            sendMessage(CMD_START_AP, wifiConfig);
        } else {
            sendMessage(CMD_STOP_AP);
        }
    }

    public void setWifiApConfiguration(WifiConfiguration config) {
        mWifiApConfigChannel.sendMessage(CMD_SET_AP_CONFIG, config);
    }

    public WifiConfiguration syncGetWifiApConfiguration() {
        Message resultMsg = mWifiApConfigChannel.sendMessageSynchronously(CMD_REQUEST_AP_CONFIG);
        WifiConfiguration ret = (WifiConfiguration) resultMsg.obj;
        resultMsg.recycle();
        return ret;
    }

    /**
     * TODO: doc
     */
    public int syncGetWifiState() {
        return mWifiState.get();
    }

    /**
     * TODO: doc
     */
    public String syncGetWifiStateByName() {
        switch (mWifiState.get()) {
            case WIFI_STATE_DISABLING:
                return "disabling";
            case WIFI_STATE_DISABLED:
                return "disabled";
            case WIFI_STATE_ENABLING:
                return "enabling";
            case WIFI_STATE_ENABLED:
                return "enabled";
            case WIFI_STATE_UNKNOWN:
                return "unknown state";
            default:
                return "[invalid state]";
        }
    }

    /**
     * TODO: doc
     */
    public int syncGetWifiApState() {
        return mWifiApState.get();
    }

    /**
     * TODO: doc
     */
    public String syncGetWifiApStateByName() {
        switch (mWifiApState.get()) {
            case WIFI_AP_STATE_DISABLING:
                return "disabling";
            case WIFI_AP_STATE_DISABLED:
                return "disabled";
            case WIFI_AP_STATE_ENABLING:
                return "enabling";
            case WIFI_AP_STATE_ENABLED:
                return "enabled";
            case WIFI_AP_STATE_FAILED:
                return "failed";
            default:
                return "[invalid state]";
        }
    }

    /**
     * Get status information for the current connection, if any.
     *
     * @return a {@link WifiInfo} object containing information about the current connection
     */
    public WifiInfo syncRequestConnectionInfo() {
        return getWiFiInfoForUid(Binder.getCallingUid());
    }

    public DhcpResults syncGetDhcpResults() {
        ///M: note:  keep v4 version, no IPV6
        synchronized (mDhcpResultsLock) {
            return new DhcpResults(mDhcpResults);
        }
    }

    /**
     * TODO: doc
     */
    public void setDriverStart(boolean enable) {
        if (enable) {
            sendMessage(CMD_START_DRIVER);
        } else {
            sendMessage(CMD_STOP_DRIVER);
        }
    }

    /**
     * TODO: doc
     */
    public void setOperationalMode(int mode) {
        if (DBG) log("setting operational mode to " + String.valueOf(mode));
        sendMessage(CMD_SET_OPERATIONAL_MODE, mode, 0);
    }

    /**
     * TODO: doc
     */
    public List<ScanResult> syncGetScanResultsList() {
        synchronized (mScanResultCache) {
            List<ScanResult> scanList = new ArrayList<ScanResult>();
            for (ScanDetail result : mScanResults) {
                scanList.add(new ScanResult(result.getScanResult()));
            }
            return scanList;
        }
    }

    public void disableEphemeralNetwork(String SSID) {
        if (SSID != null) {
            sendMessage(CMD_DISABLE_EPHEMERAL_NETWORK, SSID);
        }
    }

    /**
     * Get unsynchronized pointer to scan result list
     * Can be called only from AutoJoinController which runs in the WifiStateMachine context
     */
    public List<ScanDetail> getScanResultsListNoCopyUnsync() {
        return mScanResults;
    }

    /**
     * Disconnect from Access Point
     */
    public void disconnectCommand() {
        if (hasCustomizedAutoConnect()) {
            mDisconnectOperation = true;
        }
        sendMessage(CMD_DISCONNECT);
    }

    public void disconnectCommand(int uid, int reason) {
        if (hasCustomizedAutoConnect()) {
            mDisconnectOperation = true;
        }
        sendMessage(CMD_DISCONNECT, uid, reason);
    }

    /**
     * Initiate a reconnection to AP
     */
    public void reconnectCommand() {
        sendMessage(CMD_RECONNECT);
    }

    /**
     * Initiate a re-association to AP
     */
    public void reassociateCommand() {
        sendMessage(CMD_REASSOCIATE);
    }

    /**
     * Reload networks and then reconnect; helps load correct data for TLS networks
     */

    public void reloadTlsNetworksAndReconnect() {
        sendMessage(CMD_RELOAD_TLS_AND_RECONNECT);
        ///M: ALPS01982740 TLS network should not connect when screen locked
        setScreenLocked(false);
    }

    /**
     * Add a network synchronously
     *
     * @return network id of the new network
     */
    public int syncAddOrUpdateNetwork(AsyncChannel channel, WifiConfiguration config) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_ADD_OR_UPDATE_NETWORK, config);
        int result = resultMsg.arg1;
        resultMsg.recycle();
        return result;
    }

    /**
     * Get configured networks synchronously
     *
     * @param channel
     * @return
     */

    public List<WifiConfiguration> syncGetConfiguredNetworks(int uuid, AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_GET_CONFIGURED_NETWORKS, uuid);
        List<WifiConfiguration> result = (List<WifiConfiguration>) resultMsg.obj;
        resultMsg.recycle();
        return result;
    }

    public List<WifiConfiguration> syncGetPrivilegedConfiguredNetwork(AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(
                CMD_GET_PRIVILEGED_CONFIGURED_NETWORKS);
        List<WifiConfiguration> result = (List<WifiConfiguration>) resultMsg.obj;
        resultMsg.recycle();
        return result;
    }

    public WifiConfiguration syncGetMatchingWifiConfig(ScanResult scanResult, AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_GET_MATCHING_CONFIG, scanResult);
        return (WifiConfiguration) resultMsg.obj;
    }

    /**
     * Get connection statistics synchronously
     *
     * @param channel
     * @return
     */

    public WifiConnectionStatistics syncGetConnectionStatistics(AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_GET_CONNECTION_STATISTICS);
        WifiConnectionStatistics result = (WifiConnectionStatistics) resultMsg.obj;
        resultMsg.recycle();
        return result;
    }

    /**
     * Get adaptors synchronously
     */

    public int syncGetSupportedFeatures(AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_GET_SUPPORTED_FEATURES);
        int supportedFeatureSet = resultMsg.arg1;
        resultMsg.recycle();
        return supportedFeatureSet;
    }

    /**
     * Get link layers stats for adapter synchronously
     */
    public WifiLinkLayerStats syncGetLinkLayerStats(AsyncChannel channel) {
        loge("syncGetLinkLayerStats called, stack: "
                + Thread.currentThread().getStackTrace());
        Message resultMsg = channel.sendMessageSynchronously(CMD_GET_LINK_LAYER_STATS);
        WifiLinkLayerStats result = (WifiLinkLayerStats) resultMsg.obj;
        resultMsg.recycle();
        return result;
    }

    /**
     * Delete a network
     *
     * @param networkId id of the network to be removed
     */
    public boolean syncRemoveNetwork(AsyncChannel channel, int networkId) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_REMOVE_NETWORK, networkId);
        boolean result = (resultMsg.arg1 != FAILURE);
        resultMsg.recycle();
        return result;
    }

    /**
     * Enable a network
     *
     * @param netId         network id of the network
     * @param disableOthers true, if all other networks have to be disabled
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public boolean syncEnableNetwork(AsyncChannel channel, int netId, boolean disableOthers) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_ENABLE_NETWORK, netId,
                disableOthers ? 1 : 0);
        boolean result = (resultMsg.arg1 != FAILURE);
        resultMsg.recycle();
        return result;
    }

    /**
     * Disable a network
     *
     * @param netId network id of the network
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public boolean syncDisableNetwork(AsyncChannel channel, int netId) {
        Message resultMsg = channel.sendMessageSynchronously(WifiManager.DISABLE_NETWORK, netId);
        boolean result = (resultMsg.arg1 != WifiManager.DISABLE_NETWORK_FAILED);
        resultMsg.recycle();
        return result;
    }

    /**
     * Retrieves a WPS-NFC configuration token for the specified network
     *
     * @return a hex string representation of the WPS-NFC configuration token
     */
    public String syncGetWpsNfcConfigurationToken(int netId) {
        return mWifiNative.getNfcWpsConfigurationToken(netId);
    }

    void enableBackgroundScan(boolean enable) {
        if (enable) {
            mWifiConfigStore.enableAllNetworks();
        }
        boolean ret = mWifiNative.enableBackgroundScan(enable);
        if (ret) {
            mLegacyPnoEnabled = enable;
        } else {
            Log.e(TAG, " Fail to set up pno, want " + enable + " now " + mLegacyPnoEnabled);
        }
    }

    /**
     * Blacklist a BSSID. This will avoid the AP if there are
     * alternate APs to connect
     *
     * @param bssid BSSID of the network
     */
    public void addToBlacklist(String bssid) {
        sendMessage(CMD_BLACKLIST_NETWORK, bssid);
    }

    /**
     * Clear the blacklist list
     */
    public void clearBlacklist() {
        sendMessage(CMD_CLEAR_BLACKLIST);
    }

    public void enableRssiPolling(boolean enabled) {
        sendMessage(CMD_ENABLE_RSSI_POLL, enabled ? 1 : 0, 0);
    }

    public void enableAllNetworks() {
        sendMessage(CMD_ENABLE_ALL_NETWORKS);
    }

    /**
     * Start filtering Multicast v4 packets
     */
    public void startFilteringMulticastV4Packets() {
        mFilteringMulticastV4Packets.set(true);
        sendMessage(CMD_START_PACKET_FILTERING, MULTICAST_V4, 0);
    }

    /**
     * Stop filtering Multicast v4 packets
     */
    public void stopFilteringMulticastV4Packets() {
        mFilteringMulticastV4Packets.set(false);
        sendMessage(CMD_STOP_PACKET_FILTERING, MULTICAST_V4, 0);
    }

    /**
     * Start filtering Multicast v4 packets
     */
    public void startFilteringMulticastV6Packets() {
        sendMessage(CMD_START_PACKET_FILTERING, MULTICAST_V6, 0);
    }

    /**
     * Stop filtering Multicast v4 packets
     */
    public void stopFilteringMulticastV6Packets() {
        sendMessage(CMD_STOP_PACKET_FILTERING, MULTICAST_V6, 0);
    }

    /**
     * Set high performance mode of operation.
     * Enabling would set active power mode and disable suspend optimizations;
     * disabling would set auto power mode and enable suspend optimizations
     *
     * @param enable true if enable, false otherwise
     */
    public void setHighPerfModeEnabled(boolean enable) {
        sendMessage(CMD_SET_HIGH_PERF_MODE, enable ? 1 : 0, 0);
    }

    /**
     * Set the country code
     *
     * @param countryCode following ISO 3166 format
     * @param persist     {@code true} if the setting should be remembered.
     */
    public synchronized void setCountryCode(String countryCode, boolean persist) {
        // If it's a good country code, apply after the current
        // wifi connection is terminated; ignore resetting of code
        // for now (it is unclear what the chipset should do when
        // country code is reset)

        if (TextUtils.isEmpty(countryCode)) {
            log("Ignoring resetting of country code");
        } else {
            // if mCountryCodeSequence == 0, it is the first time to set country code, always set
            // else only when the new country code is different from the current one to set
            int countryCodeSequence = mCountryCodeSequence.get();
            if (countryCodeSequence == 0 || countryCode.equals(mDriverSetCountryCode) == false) {

                countryCodeSequence = mCountryCodeSequence.incrementAndGet();
                mSetCountryCode = countryCode;
                sendMessage(CMD_SET_COUNTRY_CODE, countryCodeSequence, persist ? 1 : 0,
                        countryCode);
            }

            if (persist) {
                Settings.Global.putString(mContext.getContentResolver(),
                        Settings.Global.WIFI_COUNTRY_CODE,
                        countryCode);
            }
        }
    }

    /**
     * Get Network object of current wifi network
     * @return Network object of current wifi network
     */
    public Network getCurrentNetwork() {
        if (mNetworkAgent != null) {
            return new Network(mNetworkAgent.netId);
        } else {
            return null;
        }
    }

    /**
     * Get the country code
     *
     * @param countryCode following ISO 3166 format
     */
    public String getCountryCode() {
        return mSetCountryCode;
    }


    /**
     * Set the operational frequency band
     *
     * @param band
     * @param persist {@code true} if the setting should be remembered.
     */
    public void setFrequencyBand(int band, boolean persist) {
        if (persist) {
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.WIFI_FREQUENCY_BAND,
                    band);
        }
        sendMessage(CMD_SET_FREQUENCY_BAND, band, 0);
    }

    /**
     * Enable TDLS for a specific MAC address
     */
    public void enableTdls(String remoteMacAddress, boolean enable) {
        int enabler = enable ? 1 : 0;
        sendMessage(CMD_ENABLE_TDLS, enabler, 0, remoteMacAddress);
    }

    /**
     * Returns the operational frequency band
     */
    public int getFrequencyBand() {
        return mFrequencyBand.get();
    }

    /**
     * Returns the wifi configuration file
     */
    public String getConfigFile() {
        return mWifiConfigStore.getConfigFile();
    }

    /**
     * Send a message indicating bluetooth adapter connection state changed
     */
    public void sendBluetoothAdapterStateChange(int state) {
        sendMessage(CMD_BLUETOOTH_ADAPTER_STATE_CHANGE, state, 0);
    }

    /**
     * Send a message indicating a package has been uninstalled.
     */
    public void removeAppConfigs(String packageName, int uid) {
        // Build partial AppInfo manually - package may not exist in database any more
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = packageName;
        ai.uid = uid;
        sendMessage(CMD_REMOVE_APP_CONFIGURATIONS, ai);
    }

    /**
     * Send a message indicating a user has been removed.
     */
    public void removeUserConfigs(int userId) {
        sendMessage(CMD_REMOVE_USER_CONFIGURATIONS, userId);
    }

    /**
     * Save configuration on supplicant
     *
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     * <p/>
     * TODO: deprecate this
     */
    public boolean syncSaveConfig(AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_SAVE_CONFIG);
        boolean result = (resultMsg.arg1 != FAILURE);
        resultMsg.recycle();
        return result;
    }

    public void updateBatteryWorkSource(WorkSource newSource) {
        synchronized (mRunningWifiUids) {
            try {
                if (newSource != null) {
                    mRunningWifiUids.set(newSource);
                }
                if (mIsRunning) {
                    if (mReportedRunning) {
                        // If the work source has changed since last time, need
                        // to remove old work from battery stats.
                        if (mLastRunningWifiUids.diff(mRunningWifiUids)) {
                            mBatteryStats.noteWifiRunningChanged(mLastRunningWifiUids,
                                    mRunningWifiUids);
                            mLastRunningWifiUids.set(mRunningWifiUids);
                        }
                    } else {
                        // Now being started, report it.
                        mBatteryStats.noteWifiRunning(mRunningWifiUids);
                        mLastRunningWifiUids.set(mRunningWifiUids);
                        mReportedRunning = true;
                    }
                } else {
                    if (mReportedRunning) {
                        // Last reported we were running, time to stop.
                        mBatteryStats.noteWifiStopped(mLastRunningWifiUids);
                        mLastRunningWifiUids.clear();
                        mReportedRunning = false;
                    }
                }
                mWakeLock.setWorkSource(newSource);
            } catch (RemoteException ignore) {
            }
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dump(fd, pw, args);
        mSupplicantStateTracker.dump(fd, pw, args);
        pw.println("mLinkProperties " + mLinkProperties);
        pw.println("mWifiInfo " + mWifiInfo);
        ////M: log for concurrency Exception @{
        log("dump: mDhcpResults");
        synchronized (mDhcpResultsLock) {
            pw.println("mDhcpResults " + mDhcpResults);
        }
        ///@}
        pw.println("mNetworkInfo " + mNetworkInfo);
        pw.println("mLastSignalLevel " + mLastSignalLevel);
        pw.println("mLastBssid " + mLastBssid);
        pw.println("mLastNetworkId " + mLastNetworkId);
        pw.println("mOperationalMode " + mOperationalMode);
        pw.println("mUserWantsSuspendOpt " + mUserWantsSuspendOpt);
        pw.println("mSuspendOptNeedsDisabled " + mSuspendOptNeedsDisabled);
        ///M: ALPS02053760 If supplicant has closed, should not get status
        if (mWifiMonitor != null) {
            if (mWifiMonitor.isMonitoring()) {
                pw.println("Supplicant status " + mWifiNative.status(true));
            } else {
                loge("mWifiMonitor.isMonitoring = false, cannot get status");
            }
        } else {
            loge("WifiMonitor == null");
        }
        pw.println("mLegacyPnoEnabled " + mLegacyPnoEnabled);
        pw.println("mSetCountryCode " + mSetCountryCode);
        pw.println("mDriverSetCountryCode " + mDriverSetCountryCode);
        pw.println("mConnectedModeGScanOffloadStarted " + mConnectedModeGScanOffloadStarted);
        pw.println("mGScanPeriodMilli " + mGScanPeriodMilli);
        if (mWhiteListedSsids != null && mWhiteListedSsids.length > 0) {
            pw.println("SSID whitelist :" );
            for (int i=0; i < mWhiteListedSsids.length; i++) {
                pw.println("       " + mWhiteListedSsids[i]);
            }
        }
        mNetworkFactory.dump(fd, pw, args);
        mUntrustedNetworkFactory.dump(fd, pw, args);
        pw.println();
        mWifiConfigStore.dump(fd, pw, args);
        pw.println();
        mWifiLogger.captureBugReportData(WifiLogger.REPORT_REASON_USER_ACTION);
        mWifiLogger.dump(fd, pw, args);
    }

    /**
     * ******************************************************
     * Internal private functions
     * ******************************************************
     */

    private void logStateAndMessage(Message message, String state) {
        messageHandlingStatus = 0;
        if (mLogMessages) {
            //long now = SystemClock.elapsedRealtimeNanos();
            //String ts = String.format("[%,d us]", now/1000);

            logd(" " + state + " " + getLogRecString(message));
        }
    }

    /**
     * helper, prints the milli time since boot wi and w/o suspended time
     */
    String printTime() {
        StringBuilder sb = new StringBuilder();
        sb.append(" rt=").append(SystemClock.uptimeMillis());
        sb.append("/").append(SystemClock.elapsedRealtime());
        return sb.toString();
    }

    /**
     * Return the additional string to be logged by LogRec, default
     *
     * @param msg that was processed
     * @return information to be logged as a String
     */
    protected String getLogRecString(Message msg) {
        WifiConfiguration config;
        Long now;
        String report;
        String key;
        StringBuilder sb = new StringBuilder();

        ///M: add log @{
        sb.append("(when=");
        TimeUtils.formatDuration(msg.getWhen() - SystemClock.uptimeMillis() , sb);
        sb.append(" what=");
        sb.append(msg.what);
        if (msg.arg1 != 0) {
            sb.append(" arg1=");
            sb.append(msg.arg1);
        }
        if (msg.arg2 != 0) {
            sb.append(" arg2=");
            sb.append(msg.arg2);
            sb.append(") ");
        }
        ///@}

        if (mScreenOn) {
            sb.append("!");
        }
        if (messageHandlingStatus != MESSAGE_HANDLING_STATUS_UNKNOWN) {
            sb.append("(").append(messageHandlingStatus).append(")");
        }
        sb.append(smToString(msg));
        if (msg.sendingUid > 0 && msg.sendingUid != Process.WIFI_UID) {
            sb.append(" uid=" + msg.sendingUid);
        }
        sb.append(" ").append(printTime());
        switch (msg.what) {
            case CMD_STARTED_GSCAN_DBG:
            case CMD_STARTED_PNO_DBG:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                if (msg.obj != null) {
                    sb.append(" " + (String)msg.obj);
                }
                break;
            case CMD_RESTART_AUTOJOIN_OFFLOAD:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                sb.append("/").append(Integer.toString(mRestartAutoJoinOffloadCounter));
                if (msg.obj != null) {
                    sb.append(" " + (String)msg.obj);
                }
                break;
            case CMD_UPDATE_ASSOCIATED_SCAN_PERMISSION:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                sb.append(" halAllowed=").append(useHalBasedAutoJoinOffload());
                sb.append(" scanAllowed=").append(allowFullBandScanAndAssociated());
                sb.append(" autojoinAllowed=");
                sb.append(mWifiConfigStore.enableAutoJoinWhenAssociated.get());
                sb.append(" withTraffic=").append(getAllowScansWithTraffic());
                sb.append(" tx=").append(mWifiInfo.txSuccessRate);
                sb.append("/").append(mWifiConfigStore.maxTxPacketForFullScans);
                sb.append(" rx=").append(mWifiInfo.rxSuccessRate);
                sb.append("/").append(mWifiConfigStore.maxRxPacketForFullScans);
                sb.append(" -> ").append(mConnectedModeGScanOffloadStarted);
                break;
            case CMD_PNO_NETWORK_FOUND:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                if (msg.obj != null) {
                    ScanResult[] results = (ScanResult[])msg.obj;
                    for (int i = 0; i < results.length; i++) {
                       sb.append(" ").append(results[i].SSID).append(" ");
                       sb.append(results[i].frequency);
                       sb.append(" ").append(results[i].level);
                    }
                }
                break;
            case CMD_START_SCAN:
                now = System.currentTimeMillis();
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                sb.append(" ic=");
                sb.append(Integer.toString(sScanAlarmIntentCount));
                if (msg.obj != null) {
                    Bundle bundle = (Bundle) msg.obj;
                    Long request = bundle.getLong(SCAN_REQUEST_TIME, 0);
                    if (request != 0) {
                        sb.append(" proc(ms):").append(now - request);
                    }
                }
                if (mIsScanOngoing) sb.append(" onGoing");
                if (mIsFullScanOngoing) sb.append(" full");
                if (lastStartScanTimeStamp != 0) {
                    sb.append(" started:").append(lastStartScanTimeStamp);
                    sb.append(",").append(now - lastStartScanTimeStamp);
                }
                if (lastScanDuration != 0) {
                    sb.append(" dur:").append(lastScanDuration);
                }
                sb.append(" cnt=").append(mDelayedScanCounter);
                sb.append(" rssi=").append(mWifiInfo.getRssi());
                sb.append(" f=").append(mWifiInfo.getFrequency());
                sb.append(" sc=").append(mWifiInfo.score);
                sb.append(" link=").append(mWifiInfo.getLinkSpeed());
                sb.append(String.format(" tx=%.1f,", mWifiInfo.txSuccessRate));
                sb.append(String.format(" %.1f,", mWifiInfo.txRetriesRate));
                sb.append(String.format(" %.1f ", mWifiInfo.txBadRate));
                sb.append(String.format(" rx=%.1f", mWifiInfo.rxSuccessRate));
                if (lastScanFreqs != null) {
                    sb.append(" list=").append(lastScanFreqs);
                } else {
                    sb.append(" fiv=").append(fullBandConnectedTimeIntervalMilli);
                }
                report = reportOnTime();
                if (report != null) {
                    sb.append(" ").append(report);
                }
                break;
            case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                StateChangeResult stateChangeResult = (StateChangeResult) msg.obj;
                if (stateChangeResult != null) {
                    sb.append(stateChangeResult.toString());
                }
                break;
            case WifiManager.SAVE_NETWORK:
            case WifiStateMachine.CMD_AUTO_SAVE_NETWORK:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                if (lastSavedConfigurationAttempt != null) {
                    sb.append(" ").append(lastSavedConfigurationAttempt.configKey());
                    sb.append(" nid=").append(lastSavedConfigurationAttempt.networkId);
                    if (lastSavedConfigurationAttempt.hiddenSSID) {
                        sb.append(" hidden");
                    }
                    if (lastSavedConfigurationAttempt.preSharedKey != null
                            && !lastSavedConfigurationAttempt.preSharedKey.equals("*")) {
                        sb.append(" hasPSK");
                    }
                    if (lastSavedConfigurationAttempt.ephemeral) {
                        sb.append(" ephemeral");
                    }
                    if (lastSavedConfigurationAttempt.selfAdded) {
                        sb.append(" selfAdded");
                    }
                    sb.append(" cuid=").append(lastSavedConfigurationAttempt.creatorUid);
                    sb.append(" suid=").append(lastSavedConfigurationAttempt.lastUpdateUid);
                }
                break;
            case WifiManager.FORGET_NETWORK:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                if (lastForgetConfigurationAttempt != null) {
                    sb.append(" ").append(lastForgetConfigurationAttempt.configKey());
                    sb.append(" nid=").append(lastForgetConfigurationAttempt.networkId);
                    if (lastForgetConfigurationAttempt.hiddenSSID) {
                        sb.append(" hidden");
                    }
                    if (lastForgetConfigurationAttempt.preSharedKey != null) {
                        sb.append(" hasPSK");
                    }
                    if (lastForgetConfigurationAttempt.ephemeral) {
                        sb.append(" ephemeral");
                    }
                    if (lastForgetConfigurationAttempt.selfAdded) {
                        sb.append(" selfAdded");
                    }
                    sb.append(" cuid=").append(lastForgetConfigurationAttempt.creatorUid);
                    sb.append(" suid=").append(lastForgetConfigurationAttempt.lastUpdateUid);
                    sb.append(" ajst=").append(lastForgetConfigurationAttempt.autoJoinStatus);
                }
                break;
            case WifiMonitor.ASSOCIATION_REJECTION_EVENT:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                String bssid = (String) msg.obj;
                if (bssid != null && bssid.length() > 0) {
                    sb.append(" ");
                    sb.append(bssid);
                }
                sb.append(" blacklist=" + Boolean.toString(didBlackListBSSID));
                break;
            case WifiMonitor.SCAN_RESULTS_EVENT:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                if (mScanResults != null) {
                    sb.append(" found=");
                    sb.append(mScanResults.size());
                }
                sb.append(" known=").append(mNumScanResultsKnown);
                sb.append(" got=").append(mNumScanResultsReturned);
                if (lastScanDuration != 0) {
                    sb.append(" dur:").append(lastScanDuration);
                }
                if (mOnTime != 0) {
                    sb.append(" on:").append(mOnTimeThisScan).append(",").append(mOnTimeScan);
                    sb.append(",").append(mOnTime);
                }
                if (mTxTime != 0) {
                    sb.append(" tx:").append(mTxTimeThisScan).append(",").append(mTxTimeScan);
                    sb.append(",").append(mTxTime);
                }
                if (mRxTime != 0) {
                    sb.append(" rx:").append(mRxTimeThisScan).append(",").append(mRxTimeScan);
                    sb.append(",").append(mRxTime);
                }
                sb.append(String.format(" bcn=%d", mRunningBeaconCount));
                sb.append(String.format(" con=%d", mConnectionRequests));
                key = mWifiConfigStore.getLastSelectedConfiguration();
                if (key != null) {
                    sb.append(" last=").append(key);
                }
                break;
            case WifiMonitor.SCAN_FAILED_EVENT:
                break;
            case WifiMonitor.NETWORK_CONNECTION_EVENT:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                sb.append(" ").append(mLastBssid);
                sb.append(" nid=").append(mLastNetworkId);
                config = getCurrentWifiConfiguration();
                if (config != null) {
                    sb.append(" ").append(config.configKey());
                }
                key = mWifiConfigStore.getLastSelectedConfiguration();
                if (key != null) {
                    sb.append(" last=").append(key);
                }
                break;
            case CMD_TARGET_BSSID:
            case CMD_ASSOCIATED_BSSID:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                if (msg.obj != null) {
                    sb.append(" BSSID=").append((String) msg.obj);
                }
                if (mTargetRoamBSSID != null) {
                    sb.append(" Target=").append(mTargetRoamBSSID);
                }
                sb.append(" roam=").append(Integer.toString(mAutoRoaming));
                break;
            case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                if (msg.obj != null) {
                    sb.append(" ").append((String) msg.obj);
                }
                sb.append(" nid=").append(msg.arg1);
                sb.append(" reason=").append(msg.arg2);
                if (mLastBssid != null) {
                    sb.append(" lastbssid=").append(mLastBssid);
                }
                if (mWifiInfo.getFrequency() != -1) {
                    sb.append(" freq=").append(mWifiInfo.getFrequency());
                    sb.append(" rssi=").append(mWifiInfo.getRssi());
                }
                if (linkDebouncing) {
                    sb.append(" debounce");
                }
                break;
            case WifiMonitor.SSID_TEMP_DISABLED:
            case WifiMonitor.SSID_REENABLED:
                sb.append(" nid=").append(msg.arg1);
                if (msg.obj != null) {
                    sb.append(" ").append((String) msg.obj);
                }
                config = getCurrentWifiConfiguration();
                if (config != null) {
                    sb.append(" cur=").append(config.configKey());
                    sb.append(" ajst=").append(config.autoJoinStatus);
                    if (config.selfAdded) {
                        sb.append(" selfAdded");
                    }
                    if (config.status != 0) {
                        sb.append(" st=").append(config.status);
                        sb.append(" rs=").append(config.disableReason);
                    }
                    if (config.lastConnected != 0) {
                        now = System.currentTimeMillis();
                        sb.append(" lastconn=").append(now - config.lastConnected).append("(ms)");
                    }
                    if (mLastBssid != null) {
                        sb.append(" lastbssid=").append(mLastBssid);
                    }
                    if (mWifiInfo.getFrequency() != -1) {
                        sb.append(" freq=").append(mWifiInfo.getFrequency());
                        sb.append(" rssi=").append(mWifiInfo.getRssi());
                        sb.append(" bssid=").append(mWifiInfo.getBSSID());
                    }
                }
                break;
            case CMD_RSSI_POLL:
            case CMD_UNWANTED_NETWORK:
            case WifiManager.RSSI_PKTCNT_FETCH:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                if (mWifiInfo.getSSID() != null)
                    if (mWifiInfo.getSSID() != null)
                        sb.append(" ").append(mWifiInfo.getSSID());
                if (mWifiInfo.getBSSID() != null)
                    sb.append(" ").append(mWifiInfo.getBSSID());
                sb.append(" rssi=").append(mWifiInfo.getRssi());
                sb.append(" f=").append(mWifiInfo.getFrequency());
                sb.append(" sc=").append(mWifiInfo.score);
                sb.append(" link=").append(mWifiInfo.getLinkSpeed());
                sb.append(String.format(" tx=%.1f,", mWifiInfo.txSuccessRate));
                sb.append(String.format(" %.1f,", mWifiInfo.txRetriesRate));
                sb.append(String.format(" %.1f ", mWifiInfo.txBadRate));
                sb.append(String.format(" rx=%.1f", mWifiInfo.rxSuccessRate));
                sb.append(String.format(" bcn=%d", mRunningBeaconCount));
                report = reportOnTime();
                if (report != null) {
                    sb.append(" ").append(report);
                }
                if (wifiScoringReport != null) {
                    sb.append(wifiScoringReport);
                }
                if (mConnectedModeGScanOffloadStarted) {
                    sb.append(" offload-started periodMilli " + mGScanPeriodMilli);
                } else {
                    sb.append(" offload-stopped");
                }
                ///M: ALPS02575372 For logging link statics in bug report
                if (linkstatics != null) {
                    sb.append("mmls=");
                    sb.append(linkstatics);
                }
                break;
            case CMD_AUTO_CONNECT:
            case WifiManager.CONNECT_NETWORK:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                config = (WifiConfiguration) msg.obj;
                if (config != null) {
                    sb.append(" ").append(config.configKey());
                    if (config.visibility != null) {
                        sb.append(" ").append(config.visibility.toString());
                    }
                }
                if (mTargetRoamBSSID != null) {
                    sb.append(" ").append(mTargetRoamBSSID);
                }
                sb.append(" roam=").append(Integer.toString(mAutoRoaming));
                config = getCurrentWifiConfiguration();
                if (config != null) {
                    sb.append(config.configKey());
                    if (config.visibility != null) {
                        sb.append(" ").append(config.visibility.toString());
                    }
                }
                break;
            case CMD_AUTO_ROAM:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                ScanResult result = (ScanResult) msg.obj;
                if (result != null) {
                    now = System.currentTimeMillis();
                    sb.append(" bssid=").append(result.BSSID);
                    sb.append(" rssi=").append(result.level);
                    sb.append(" freq=").append(result.frequency);
                    if (result.seen > 0 && result.seen < now) {
                        sb.append(" seen=").append(now - result.seen);
                    } else {
                        // Somehow the timestamp for this scan result is inconsistent
                        sb.append(" !seen=").append(result.seen);
                    }
                }
                if (mTargetRoamBSSID != null) {
                    sb.append(" ").append(mTargetRoamBSSID);
                }
                sb.append(" roam=").append(Integer.toString(mAutoRoaming));
                sb.append(" fail count=").append(Integer.toString(mRoamFailCount));
                break;
            case CMD_ADD_OR_UPDATE_NETWORK:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                if (msg.obj != null) {
                    config = (WifiConfiguration) msg.obj;
                    sb.append(" ").append(config.configKey());
                    sb.append(" prio=").append(config.priority);
                    sb.append(" status=").append(config.status);
                    if (config.BSSID != null) {
                        sb.append(" ").append(config.BSSID);
                    }
                    WifiConfiguration curConfig = getCurrentWifiConfiguration();
                    if (curConfig != null) {
                        if (curConfig.configKey().equals(config.configKey())) {
                            sb.append(" is current");
                        } else {
                            sb.append(" current=").append(curConfig.configKey());
                            sb.append(" prio=").append(curConfig.priority);
                            sb.append(" status=").append(curConfig.status);
                        }
                    }
                }
                break;
            case WifiManager.DISABLE_NETWORK:
            case CMD_ENABLE_NETWORK:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                key = mWifiConfigStore.getLastSelectedConfiguration();
                if (key != null) {
                    sb.append(" last=").append(key);
                }
                config = mWifiConfigStore.getWifiConfiguration(msg.arg1);
                if (config != null && (key == null || !config.configKey().equals(key))) {
                    sb.append(" target=").append(key);
                }
                break;
            case CMD_GET_CONFIGURED_NETWORKS:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                sb.append(" num=").append(mWifiConfigStore.getConfiguredNetworksSize());
                break;
            case DhcpStateMachine.CMD_PRE_DHCP_ACTION:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                sb.append(" txpkts=").append(mWifiInfo.txSuccess);
                sb.append(",").append(mWifiInfo.txBad);
                sb.append(",").append(mWifiInfo.txRetries);
                break;
            case DhcpStateMachine.CMD_POST_DHCP_ACTION:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                if (msg.arg1 == DhcpStateMachine.DHCP_SUCCESS) {
                    sb.append(" OK ");
                } else if (msg.arg1 == DhcpStateMachine.DHCP_FAILURE) {
                    sb.append(" FAIL ");
                }
                if (mLinkProperties != null) {
                    if (mLinkProperties.hasIPv4Address()) {
                        sb.append(" v4");
                    }
                    if (mLinkProperties.hasGlobalIPv6Address()) {
                        sb.append(" v6");
                    }
                    if (mLinkProperties.hasIPv4DefaultRoute()) {
                        sb.append(" v4r");
                    }
                    if (mLinkProperties.hasIPv6DefaultRoute()) {
                        sb.append(" v6r");
                    }
                    if (mLinkProperties.hasIPv4DnsServer()) {
                        sb.append(" v4dns");
                    }
                    if (mLinkProperties.hasIPv6DnsServer()) {
                        sb.append(" v6dns");
                    }
                }
                break;
            case WifiP2pServiceImpl.P2P_CONNECTION_CHANGED:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                if (msg.obj != null) {
                    NetworkInfo info = (NetworkInfo) msg.obj;
                    NetworkInfo.State state = info.getState();
                    NetworkInfo.DetailedState detailedState = info.getDetailedState();
                    if (state != null) {
                        sb.append(" st=").append(state);
                    }
                    if (detailedState != null) {
                        sb.append("/").append(detailedState);
                    }
                }
                break;
            case CMD_IP_CONFIGURATION_LOST:
                int count = -1;
                WifiConfiguration c = getCurrentWifiConfiguration();
                if (c != null) count = c.numIpConfigFailures;
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                sb.append(" failures: ");
                sb.append(Integer.toString(count));
                sb.append("/");
                sb.append(Integer.toString(mWifiConfigStore.getMaxDhcpRetries()));
                if (mWifiInfo.getBSSID() != null) {
                    sb.append(" ").append(mWifiInfo.getBSSID());
                }
                if (c != null) {
                    ScanDetailCache scanDetailCache =
                            mWifiConfigStore.getScanDetailCache(c);
                    if (scanDetailCache != null) {
                        for (ScanDetail sd : scanDetailCache.values()) {
                            ScanResult r = sd.getScanResult();
                            if (r.BSSID.equals(mWifiInfo.getBSSID())) {
                                sb.append(" ipfail=").append(r.numIpConfigFailures);
                                sb.append(",st=").append(r.autoJoinStatus);
                            }
                        }
                    }
                    sb.append(" -> ajst=").append(c.autoJoinStatus);
                    sb.append(" ").append(c.disableReason);
                    sb.append(" txpkts=").append(mWifiInfo.txSuccess);
                    sb.append(",").append(mWifiInfo.txBad);
                    sb.append(",").append(mWifiInfo.txRetries);
                }
                sb.append(String.format(" bcn=%d", mRunningBeaconCount));
                break;
            case CMD_UPDATE_LINKPROPERTIES:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                if (mLinkProperties != null) {
                    if (mLinkProperties.hasIPv4Address()) {
                        sb.append(" v4");
                    }
                    if (mLinkProperties.hasGlobalIPv6Address()) {
                        sb.append(" v6");
                    }
                    if (mLinkProperties.hasIPv4DefaultRoute()) {
                        sb.append(" v4r");
                    }
                    if (mLinkProperties.hasIPv6DefaultRoute()) {
                        sb.append(" v6r");
                    }
                    if (mLinkProperties.hasIPv4DnsServer()) {
                        sb.append(" v4dns");
                    }
                    if (mLinkProperties.hasIPv6DnsServer()) {
                        sb.append(" v6dns");
                    }
                }
                break;
            case CMD_IP_REACHABILITY_LOST:
                if (msg.obj != null) {
                    sb.append(" ").append((String) msg.obj);
                }
                break;
            case CMD_SET_COUNTRY_CODE:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                if (msg.obj != null) {
                    sb.append(" ").append((String) msg.obj);
                }
                break;
            case CMD_ROAM_WATCHDOG_TIMER:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                sb.append(" cur=").append(roamWatchdogCount);
                break;
            case CMD_DISCONNECTING_WATCHDOG_TIMER:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                sb.append(" cur=").append(disconnectingWatchdogCount);
                break;
            default:
                ///M: add log @{
                sb.append(msg.toString());
                ///@}
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                break;
        }

        return sb.toString();
    }

    private void stopPnoOffload() {

        // clear the PNO list
        if (!WifiNative.setPnoList(null, WifiStateMachine.this)) {
            Log.e(TAG, "Failed to stop pno");
        }

    }


    private boolean configureSsidWhiteList() {

        mWhiteListedSsids = mWifiConfigStore.getWhiteListedSsids(getCurrentWifiConfiguration());
        if (mWhiteListedSsids == null || mWhiteListedSsids.length == 0) {
            return true;
        }

       if (!WifiNative.setSsidWhitelist(mWhiteListedSsids)) {
            loge("configureSsidWhiteList couldnt program SSID list, size "
                    + mWhiteListedSsids.length);
            return false;
        }

        logd("configureSsidWhiteList success");
        return true;
    }

    // In associated more, lazy roam will be looking for 5GHz roam candidate
    private boolean configureLazyRoam() {
        boolean status;
        if (!useHalBasedAutoJoinOffload()) return false;

        WifiNative.WifiLazyRoamParams params = mWifiNative.new WifiLazyRoamParams();
        params.A_band_boost_threshold = mWifiConfigStore.bandPreferenceBoostThreshold5.get();
        params.A_band_penalty_threshold = mWifiConfigStore.bandPreferencePenaltyThreshold5.get();
        params.A_band_boost_factor = mWifiConfigStore.bandPreferenceBoostFactor5;
        params.A_band_penalty_factor = mWifiConfigStore.bandPreferencePenaltyFactor5;
        params.A_band_max_boost = 65;
        params.lazy_roam_hysteresis = 25;
        params.alert_roam_rssi_trigger = -75;

        if (DBG) {
            Log.e(TAG, "configureLazyRoam " + params.toString());
        }

        if (!WifiNative.setLazyRoam(true, params)) {

            Log.e(TAG, "configureLazyRoam couldnt program params");

            return false;
        }
        if (DBG) {
            Log.e(TAG, "configureLazyRoam success");
        }
        return true;
    }

    // In associated more, lazy roam will be looking for 5GHz roam candidate
    private boolean stopLazyRoam() {
        boolean status;
        if (!useHalBasedAutoJoinOffload()) return false;
        if (DBG) {
            Log.e(TAG, "stopLazyRoam");
        }
        return WifiNative.setLazyRoam(false, null);
    }

    private boolean startGScanConnectedModeOffload(String reason) {
        if (DBG) {
            if (reason == null) {
                reason = "";
            }
            logd("startGScanConnectedModeOffload " + reason);
        }
        stopGScan("startGScanConnectedModeOffload " + reason);
        if (!mScreenOn) return false;

        if (USE_PAUSE_SCANS) {
            mWifiNative.pauseScan();
        }
        mPnoEnabled = configurePno();
        if (mPnoEnabled == false) {
            if (USE_PAUSE_SCANS) {
                mWifiNative.restartScan();
            }
            return false;
        }
        mLazyRoamEnabled = configureLazyRoam();
        if (mLazyRoamEnabled == false) {
            if (USE_PAUSE_SCANS) {
                mWifiNative.restartScan();
            }
            return false;
        }
        if (mWifiConfigStore.getLastSelectedConfiguration() == null) {
            configureSsidWhiteList();
        }
        if (!startConnectedGScan(reason)) {
            if (USE_PAUSE_SCANS) {
                mWifiNative.restartScan();
            }
            return false;
        }
        if (USE_PAUSE_SCANS) {
            mWifiNative.restartScan();
        }
        mConnectedModeGScanOffloadStarted = true;
        if (DBG) {
            logd("startGScanConnectedModeOffload success");
        }
        return true;
    }

    private boolean startGScanDisconnectedModeOffload(String reason) {
        if (DBG) {
            logd("startGScanDisconnectedModeOffload " + reason);
        }
        stopGScan("startGScanDisconnectedModeOffload " + reason);
        if (USE_PAUSE_SCANS) {
            mWifiNative.pauseScan();
        }
        mPnoEnabled = configurePno();
        if (mPnoEnabled == false) {
            if (USE_PAUSE_SCANS) {
                mWifiNative.restartScan();
            }
            return false;
        }
        if (!startDisconnectedGScan(reason)) {
            if (USE_PAUSE_SCANS) {
                mWifiNative.restartScan();
            }
            return false;
        }
        if (USE_PAUSE_SCANS) {
            mWifiNative.restartScan();
        }
        return true;
    }

    private boolean configurePno() {
        if (!useHalBasedAutoJoinOffload()) return false;

        if (mWifiScanner == null) {
            log("configurePno: mWifiScanner is null ");
            return true;
        }

        List<WifiNative.WifiPnoNetwork> llist
                = mWifiAutoJoinController.getPnoList(getCurrentWifiConfiguration());
        if (llist == null || llist.size() == 0) {
            stopPnoOffload();
            log("configurePno: empty PNO list ");
            return true;
        }
        if (DBG) {
            log("configurePno: got llist size " + llist.size());
        }

        // first program the network we want to look for thru the pno API
        WifiNative.WifiPnoNetwork list[]
                = (WifiNative.WifiPnoNetwork[]) llist.toArray(new WifiNative.WifiPnoNetwork[0]);

        if (!WifiNative.setPnoList(list, WifiStateMachine.this)) {
            Log.e(TAG, "Failed to set pno, length = " + list.length);
            return false;
        }

        if (true) {
            StringBuilder sb = new StringBuilder();
            for (WifiNative.WifiPnoNetwork network : list) {
                sb.append("[").append(network.SSID).append(" auth=").append(network.auth);
                sb.append(" flags=");
                sb.append(network.flags).append(" rssi").append(network.rssi_threshold);
                sb.append("] ");

            }
            sendMessage(CMD_STARTED_PNO_DBG, 1, (int)mGScanPeriodMilli, sb.toString());
        }
        return true;
    }

    final static int DISCONNECTED_SHORT_SCANS_DURATION_MILLI = 2 * 60 * 1000;
    final static int CONNECTED_SHORT_SCANS_DURATION_MILLI = 2 * 60 * 1000;

    private boolean startConnectedGScan(String reason) {
        // send a scan background request so as to kick firmware
        // 5GHz roaming and autojoin
        // We do this only if screen is on
        WifiScanner.ScanSettings settings;

        if (mPnoEnabled || mLazyRoamEnabled) {
            settings = new WifiScanner.ScanSettings();
            settings.band = WifiScanner.WIFI_BAND_BOTH;
            long now = System.currentTimeMillis();

            if (!mScreenOn  || (mGScanStartTimeMilli!= 0 && now > mGScanStartTimeMilli
                    && ((now - mGScanStartTimeMilli) > CONNECTED_SHORT_SCANS_DURATION_MILLI))) {
                settings.periodInMs = mWifiConfigStore.wifiAssociatedLongScanIntervalMilli.get();
            } else {
                mGScanStartTimeMilli = now;
                settings.periodInMs = mWifiConfigStore.wifiAssociatedShortScanIntervalMilli.get();
                // if we start offload with short interval, then reconfigure it after a given
                // duration of time so as to reduce the scan frequency
                int delay = 30 * 1000 + CONNECTED_SHORT_SCANS_DURATION_MILLI;
                sendMessageDelayed(CMD_RESTART_AUTOJOIN_OFFLOAD, delay,
                        mRestartAutoJoinOffloadCounter, " startConnectedGScan " + reason,
                        (long)delay);
                mRestartAutoJoinOffloadCounter++;
            }
            mGScanPeriodMilli = settings.periodInMs;
            settings.reportEvents = WifiScanner.REPORT_EVENT_AFTER_BUFFER_FULL;
            if (DBG) {
                log("startConnectedScan: settings band="+ settings.band
                        + " period=" + settings.periodInMs);
            }

            mWifiScanner.startBackgroundScan(settings, mWifiScanListener);
            if (true) {
                sendMessage(CMD_STARTED_GSCAN_DBG, 1, (int)mGScanPeriodMilli, reason);
            }
        }
        return true;
    }

    private boolean startDisconnectedGScan(String reason) {
        // send a scan background request so as to kick firmware
        // PNO
        // This is done in both screen On and screen Off modes
        WifiScanner.ScanSettings settings;

        if (mWifiScanner == null) {
            log("startDisconnectedGScan: no wifi scanner");
            return false;
        }

        if (mPnoEnabled || mLazyRoamEnabled) {
            settings = new WifiScanner.ScanSettings();
            settings.band = WifiScanner.WIFI_BAND_BOTH;
            long now = System.currentTimeMillis();


            if (!mScreenOn  || (mGScanStartTimeMilli != 0 && now > mGScanStartTimeMilli
                    && ((now - mGScanStartTimeMilli) > DISCONNECTED_SHORT_SCANS_DURATION_MILLI))) {
                settings.periodInMs = mWifiConfigStore.wifiDisconnectedLongScanIntervalMilli.get();
            } else {
                settings.periodInMs = mWifiConfigStore.wifiDisconnectedShortScanIntervalMilli.get();
                mGScanStartTimeMilli = now;
                // if we start offload with short interval, then reconfigure it after a given
                // duration of time so as to reduce the scan frequency
                int delay = 30 * 1000 + DISCONNECTED_SHORT_SCANS_DURATION_MILLI;
                sendMessageDelayed(CMD_RESTART_AUTOJOIN_OFFLOAD, delay,
                        mRestartAutoJoinOffloadCounter, " startDisconnectedGScan " + reason,
                        (long)delay);
                mRestartAutoJoinOffloadCounter++;
            }
            mGScanPeriodMilli = settings.periodInMs;
            settings.reportEvents = WifiScanner.REPORT_EVENT_AFTER_BUFFER_FULL;
            if (DBG) {
                log("startDisconnectedScan: settings band="+ settings.band
                        + " period=" + settings.periodInMs);
            }
            mWifiScanner.startBackgroundScan(settings, mWifiScanListener);
            if (true) {
                sendMessage(CMD_STARTED_GSCAN_DBG, 1, (int)mGScanPeriodMilli, reason);
            }
        }
        return true;
    }

    private boolean stopGScan(String reason) {
        mGScanStartTimeMilli = 0;
        mGScanPeriodMilli = 0;
        if (mWifiScanner != null) {
            mWifiScanner.stopBackgroundScan(mWifiScanListener);
        }
        mConnectedModeGScanOffloadStarted = false;
        if (true) {
            sendMessage(CMD_STARTED_GSCAN_DBG, 0, 0, reason);
        }
        return true;
    }

    private void handleScreenStateChanged(boolean screenOn) {
        mScreenOn = screenOn;
        if (PDBG) {
            logd(" handleScreenStateChanged Enter: screenOn=" + screenOn
                    + " mUserWantsSuspendOpt=" + mUserWantsSuspendOpt
                    + " state " + getCurrentState().getName()
                    + " suppState:" + mSupplicantStateTracker.getSupplicantStateName());
        }
        enableRssiPolling(screenOn);
        if (screenOn) enableAllNetworks();
        if (mUserWantsSuspendOpt.get()) {
            if (screenOn) {
                sendMessage(CMD_SET_SUSPEND_OPT_ENABLED, 0, 0);
            } else {
                // Allow 2s for suspend optimizations to be set
                mSuspendWakeLock.acquire(2000);
                sendMessage(CMD_SET_SUSPEND_OPT_ENABLED, 1, 0);
            }
        }
        mScreenBroadcastReceived.set(true);
        if (hasCustomizedAutoConnect()) {
            sendMessage(M_CMD_UPDATE_SCAN_INTERVAL);
        }
        // M: For stop scan after screen off in disconnected state feature @{
        mScreenOn = screenOn;
        if (screenOn) {
            sendMessage(M_CMD_NOTIFY_SCREEN_ON);
        } else {
            sendMessage(M_CMD_NOTIFY_SCREEN_OFF);
        }
        ///@}

        getWifiLinkLayerStats(false);
        mOnTimeScreenStateChange = mOnTime;
        lastScreenStateChangeTimeStamp = lastLinkLayerStatsUpdate;

        cancelDelayedScan();

        if (screenOn) {
            enableBackgroundScan(false);
            setScanAlarm(false);
            clearBlacklist();

            fullBandConnectedTimeIntervalMilli
                    = mWifiConfigStore.wifiAssociatedShortScanIntervalMilli.get();
            // In either Disconnectedstate or ConnectedState,
            // start the scan alarm so as to enable autojoin
            if (getCurrentState() == mConnectedState
                    && allowFullBandScanAndAssociated()) {
                if (useHalBasedAutoJoinOffload()) {
                    startGScanConnectedModeOffload("screenOnConnected");
                } else {
                    // Scan after 500ms
                    startDelayedScan(500, null, null);
                }
            } else if (getCurrentState() == mDisconnectedState) {
                if (useHalBasedAutoJoinOffload()) {
                    startGScanDisconnectedModeOffload("screenOnDisconnected");
                } else {
                    // Scan after 500ms
                    startDelayedScan(500, null, null);
                }
            }
        } else {
            if (getCurrentState() == mDisconnectedState) {
                // Screen Off and Disconnected and chipset doesn't support scan offload
                //              => start scan alarm
                // Screen Off and Disconnected and chipset does support scan offload
                //              => will use scan offload (i.e. background scan)
                if (useHalBasedAutoJoinOffload()) {
                    startGScanDisconnectedModeOffload("screenOffDisconnected");
                } else {
                    if (!mBackgroundScanSupported) {
                        setScanAlarm(true);
                    } else {
                        if (!mIsScanOngoing) {
                            enableBackgroundScan(true);
                        }
                    }
                }
            } else {
                enableBackgroundScan(false);
                if (useHalBasedAutoJoinOffload()) {
                    // don't try stop Gscan if it is not enabled
                    stopGScan("ScreenOffStop(enableBackground=" + mLegacyPnoEnabled + ") ");
                }
            }
        }
        if (DBG) logd("backgroundScan enabled=" + mLegacyPnoEnabled);

        if (DBG) log("handleScreenStateChanged Exit: " + screenOn);
    }

    private void checkAndSetConnectivityInstance() {
        if (mCm == null) {
            mCm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        }
    }

    private boolean startTethering(ArrayList<String> available) {

        boolean wifiAvailable = false;

        checkAndSetConnectivityInstance();

        String[] wifiRegexs = mCm.getTetherableWifiRegexs();

        for (String intf : available) {
            for (String regex : wifiRegexs) {
                if (intf.matches(regex)) {

                    InterfaceConfiguration ifcg = null;
                    try {
                        ifcg = mNwService.getInterfaceConfig(intf);
                        if (ifcg != null) {
                            /* IP/netmask: 192.168.43.1/255.255.255.0 */
                            ifcg.setLinkAddress(new LinkAddress(
                                    NetworkUtils.numericToInetAddress("192.168.43.1"), 24));
                            ifcg.setInterfaceUp();

                            mNwService.setInterfaceConfig(intf, ifcg);
                        }
                    } catch (Exception e) {
                        loge("Error configuring interface " + intf + ", :" + e);
                        return false;
                    }

                    if (mCm.tether(intf) != ConnectivityManager.TETHER_ERROR_NO_ERROR) {
                        loge("Error tethering on " + intf);
                        return false;
                    }
                    mTetherInterfaceName = intf;
                    return true;
                }
            }
        }
        // We found no interfaces to tether
        return false;
    }

    private void stopTethering() {

        checkAndSetConnectivityInstance();

        /* Clear the interface config to allow dhcp correctly configure new
           ip settings */
        InterfaceConfiguration ifcg = null;
        try {
            ifcg = mNwService.getInterfaceConfig(mTetherInterfaceName);
            if (ifcg != null) {
                ifcg.setLinkAddress(
                        new LinkAddress(NetworkUtils.numericToInetAddress("0.0.0.0"), 0));
                mNwService.setInterfaceConfig(mTetherInterfaceName, ifcg);
            }
        } catch (Exception e) {
            loge("Error resetting interface " + mTetherInterfaceName + ", :" + e);
        }

        if (mCm.untether(mTetherInterfaceName) != ConnectivityManager.TETHER_ERROR_NO_ERROR) {
            loge("Untether initiate failed!");
        }
    }

    private boolean isWifiTethered(ArrayList<String> active) {

        checkAndSetConnectivityInstance();

        String[] wifiRegexs = mCm.getTetherableWifiRegexs();
        for (String intf : active) {
            for (String regex : wifiRegexs) {
                if (intf.matches(regex)) {
                    return true;
                }
            }
        }
        // We found no interfaces that are tethered
        return false;
    }

    /**
     * Set the country code from the system setting value, if any.
     */
    private void setCountryCode() {
        String countryCode = Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.WIFI_COUNTRY_CODE);
        if (countryCode != null && !countryCode.isEmpty()) {
            setCountryCode(countryCode, false);
        } else {
            //use driver default
        }
    }

    /**
     * Set the frequency band from the system setting value, if any.
     */
    private void setFrequencyBand() {
        int band = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.WIFI_FREQUENCY_BAND, WifiManager.WIFI_FREQUENCY_BAND_AUTO);

        if (mWifiNative.setBand(band)) {
            mFrequencyBand.set(band);
            if (PDBG) {
                logd("done set frequency band " + band);
            }
        } else {
            loge("Failed to set frequency band " + band);
        }
    }



    private void setSuspendOptimizationsNative(int reason, boolean enabled) {
        if (DBG) {
            log("setSuspendOptimizationsNative: " + reason + " " + enabled
                    + " -want " + mUserWantsSuspendOpt.get()
                    + " stack:" + Thread.currentThread().getStackTrace()[2].getMethodName()
                    + " - " + Thread.currentThread().getStackTrace()[3].getMethodName()
                    + " - " + Thread.currentThread().getStackTrace()[4].getMethodName()
                    + " - " + Thread.currentThread().getStackTrace()[5].getMethodName());
        }
        //mWifiNative.setSuspendOptimizations(enabled);

        if (enabled) {
            mSuspendOptNeedsDisabled &= ~reason;
            /* None of dhcp, screen or highperf need it disabled and user wants it enabled */
            if (mSuspendOptNeedsDisabled == 0 && mUserWantsSuspendOpt.get()) {
                if (DBG) {
                    log("setSuspendOptimizationsNative do it " + reason + " " + enabled
                            + " stack:" + Thread.currentThread().getStackTrace()[2].getMethodName()
                            + " - " + Thread.currentThread().getStackTrace()[3].getMethodName()
                            + " - " + Thread.currentThread().getStackTrace()[4].getMethodName()
                            + " - " + Thread.currentThread().getStackTrace()[5].getMethodName());
                }
                mWifiNative.setSuspendOptimizations(true);
            }
        } else {
            mSuspendOptNeedsDisabled |= reason;
            mWifiNative.setSuspendOptimizations(false);
        }
    }

    private void setSuspendOptimizations(int reason, boolean enabled) {
        if (DBG) log("setSuspendOptimizations: " + reason + " " + enabled);
        if (enabled) {
            mSuspendOptNeedsDisabled &= ~reason;
        } else {
            mSuspendOptNeedsDisabled |= reason;
        }
        if (DBG) log("mSuspendOptNeedsDisabled " + mSuspendOptNeedsDisabled);
    }

    private void setWifiState(int wifiState) {
        final int previousWifiState = mWifiState.get();

        try {
            if (wifiState == WIFI_STATE_ENABLED) {
                mBatteryStats.noteWifiOn();
            } else if (wifiState == WIFI_STATE_DISABLED) {
                mBatteryStats.noteWifiOff();
            }
        } catch (RemoteException e) {
            loge("Failed to note battery stats in wifi");
        }

        mWifiState.set(wifiState);

        if (DBG) log("setWifiState: " + syncGetWifiStateByName());

        final Intent intent = new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_WIFI_STATE, wifiState);
        intent.putExtra(WifiManager.EXTRA_PREVIOUS_WIFI_STATE, previousWifiState);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void setWifiApState(int wifiApState, int reason) {
        final int previousWifiApState = mWifiApState.get();

        try {
            if (wifiApState == WIFI_AP_STATE_ENABLED) {
                mBatteryStats.noteWifiOn();
            } else if (wifiApState == WIFI_AP_STATE_DISABLED) {
                mBatteryStats.noteWifiOff();
            }
        } catch (RemoteException e) {
            loge("Failed to note battery stats in wifi");
        }

        // Update state
        mWifiApState.set(wifiApState);

        if (DBG) log("setWifiApState: " + syncGetWifiApStateByName());

        final Intent intent = new Intent(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_WIFI_AP_STATE, wifiApState);
        intent.putExtra(WifiManager.EXTRA_PREVIOUS_WIFI_AP_STATE, previousWifiApState);
        if (wifiApState == WifiManager.WIFI_AP_STATE_FAILED) {
            //only set reason number when softAP start failed
            intent.putExtra(WifiManager.EXTRA_WIFI_AP_FAILURE_REASON, reason);
        }

        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    /*
    void ageOutScanResults(int age) {
        synchronized(mScanResultCache) {
            // Trim mScanResults, which prevent WifiStateMachine to return
            // obsolete scan results to queriers
            long now = System.CurrentTimeMillis();
            for (int i = 0; i < mScanResults.size(); i++) {
                ScanResult result = mScanResults.get(i);
                if ((result.seen > now || (now - result.seen) > age)) {
                    mScanResults.remove(i);
                }
            }
        }
    }*/

    private static final String IE_STR = "ie=";
    private static final String ID_STR = "id=";
    private static final String BSSID_STR = "bssid=";
    private static final String FREQ_STR = "freq=";
    private static final String LEVEL_STR = "level=";
    private static final String TSF_STR = "tsf=";
    private static final String FLAGS_STR = "flags=";
    private static final String SSID_STR = "ssid=";
    private static final String DELIMITER_STR = "====";
    private static final String END_STR = "####";

    int emptyScanResultCount = 0;

    // Used for matching BSSID strings, at least one characteer must be a non-zero number
    private static Pattern mNotZero = Pattern.compile("[1-9a-fA-F]");

    /**
     * Format:
     * <p/>
     * id=1
     * bssid=68:7f:76:d7:1a:6e
     * freq=2412
     * level=-44
     * tsf=1344626243700342
     * flags=[WPA2-PSK-CCMP][WPS][ESS]
     * ssid=zfdy
     * ====
     * id=2
     * bssid=68:5f:74:d7:1a:6f
     * freq=5180
     * level=-73
     * tsf=1344626243700373
     * flags=[WPA2-PSK-CCMP][WPS][ESS]
     * ssid=zuby
     * ====
     */
    private void setScanResults() {
        mNumScanResultsKnown = 0;
        mNumScanResultsReturned = 0;

        String bssid = null;


        int level = 0;
        int freq = 0;
        long tsf = 0;
        String flags = "";
        WifiSsid wifiSsid = null;
        String scanResults;
        String tmpResults;
        StringBuffer scanResultsBuf = new StringBuffer();
        int sid = 0;

        while (true) {
            tmpResults = mWifiNative.scanResults(sid);
            if (TextUtils.isEmpty(tmpResults)) break;
            scanResultsBuf.append(tmpResults);
            scanResultsBuf.append("\n");
            String[] lines = tmpResults.split("\n");
            sid = -1;
            for (int i = lines.length - 1; i >= 0; i--) {
                if (lines[i].startsWith(END_STR)) {
                    break;
                } else if (lines[i].startsWith(ID_STR)) {
                    try {
                        sid = Integer.parseInt(lines[i].substring(ID_STR.length())) + 1;
                    } catch (NumberFormatException e) {
                        // Nothing to do
                    }
                    break;
                }
            }
            if (sid == -1) break;
        }

        // Age out scan results, we return all scan results found in the last 12 seconds,
        // and NOT all scan results since last scan.
        // ageOutScanResults(12000);

        scanResults = scanResultsBuf.toString();
        if (TextUtils.isEmpty(scanResults)) {
            emptyScanResultCount++;
            if (emptyScanResultCount > 10) {
                // If we got too many empty scan results, the current scan cache is stale,
                // hence clear it.
                mScanResults = new ArrayList<>();
            }
            return;
        }

        emptyScanResultCount = 0;

        mWifiConfigStore.trimANQPCache(false);

        // note that all these splits and substrings keep references to the original
        // huge string buffer while the amount we really want is generally pretty small
        // so make copies instead (one example b/11087956 wasted 400k of heap here).
        synchronized (mScanResultCache) {
            mScanResults = new ArrayList<>();
            String[] lines = scanResults.split("\n");
            final int bssidStrLen = BSSID_STR.length();
            final int flagLen = FLAGS_STR.length();
            String infoElements = null;
            List<String> anqpLines = null;

            for (String line : lines) {
                if (line.startsWith(BSSID_STR)) {
                    bssid = new String(line.getBytes(), bssidStrLen, line.length() - bssidStrLen);
                } else if (line.startsWith(FREQ_STR)) {
                    try {
                        freq = Integer.parseInt(line.substring(FREQ_STR.length()));
                    } catch (NumberFormatException e) {
                        freq = 0;
                    }
                } else if (line.startsWith(LEVEL_STR)) {
                    try {
                        level = Integer.parseInt(line.substring(LEVEL_STR.length()));
                        /* some implementations avoid negative values by adding 256
                         * so we need to adjust for that here.
                         */
                        if (level > 0) level -= 256;
                    } catch (NumberFormatException e) {
                        level = 0;
                    }
                } else if (line.startsWith(TSF_STR)) {
                    try {
                        tsf = Long.parseLong(line.substring(TSF_STR.length()));
                    } catch (NumberFormatException e) {
                        tsf = 0;
                    }
                } else if (line.startsWith(FLAGS_STR)) {
                    flags = new String(line.getBytes(), flagLen, line.length() - flagLen);
                } else if (line.startsWith(SSID_STR)) {
                    wifiSsid = WifiSsid.createFromAsciiEncoded(
                            line.substring(SSID_STR.length()));
                } else if (line.startsWith(IE_STR)) {
                    infoElements = line;
                } else if (SupplicantBridge.isAnqpAttribute(line)) {
                    if (anqpLines == null) {
                        anqpLines = new ArrayList<>();
                    }
                    anqpLines.add(line);
                } else if (line.startsWith(DELIMITER_STR) || line.startsWith(END_STR)) {
                    if (bssid != null) {
                        try {
                            NetworkDetail networkDetail =
                                    new NetworkDetail(bssid, infoElements, anqpLines, freq);

                            String xssid = (wifiSsid != null) ? wifiSsid.toString() : WifiSsid.NONE;
                            if (!xssid.equals(networkDetail.getTrimmedSSID())) {
                                logd(String.format(
                                        "Inconsistent SSID on BSSID '%s': '%s' vs '%s': %s",
                                        bssid, xssid, networkDetail.getSSID(), infoElements));
                            }

                            if (networkDetail.hasInterworking()) {
                                Log.d(Utils.hs2LogTag(getClass()), "HSNwk: '" + networkDetail);
                            }

                            ScanDetail scanDetail = mScanResultCache.get(networkDetail);
                            ///M: ALPS02349855 check key management is same or not
                            if (scanDetail != null && isSameKeyManagement(scanDetail, flags)) {
                                scanDetail.updateResults(networkDetail, level, wifiSsid, xssid,
                                        flags, freq, tsf);
                            } else {
                                scanDetail = new ScanDetail(networkDetail, wifiSsid, bssid,
                                        flags, level, freq, tsf);
                                mScanResultCache.put(networkDetail, scanDetail);
                            }

                            mNumScanResultsReturned++; // Keep track of how many scan results we got
                            // as part of this scan's processing
                            mScanResults.add(scanDetail);
                        } catch (IllegalArgumentException iae) {
                            Log.d(TAG, "Failed to parse information elements: " + iae);
                        }
                    }
                    bssid = null;
                    level = 0;
                    freq = 0;
                    tsf = 0;
                    flags = "";
                    wifiSsid = null;
                    infoElements = null;
                    anqpLines = null;
                }
            }
        }

        /* don't attempt autojoin if last connect attempt was just scheduled */
        boolean attemptAutoJoin =
                (System.currentTimeMillis() - lastConnectAttemptTimestamp) > CONNECT_TIMEOUT_MSEC;
        SupplicantState state = mWifiInfo.getSupplicantState();
        String selection = mWifiConfigStore.getLastSelectedConfiguration();
        if (getCurrentState() == mRoamingState
                || getCurrentState() == mObtainingIpState
                || getCurrentState() == mScanModeState
                || getCurrentState() == mDisconnectingState
                || (getCurrentState() == mConnectedState
                && !getEnableAutoJoinWhenAssociated())
                || linkDebouncing
                || state == SupplicantState.ASSOCIATING
                || state == SupplicantState.AUTHENTICATING
                || state == SupplicantState.FOUR_WAY_HANDSHAKE
                || state == SupplicantState.GROUP_HANDSHAKE
                || (/* keep autojoin enabled if user has manually selected a wifi network,
                        so as to make sure we reliably remain connected to this network */
                mConnectionRequests == 0 && selection == null)) {
            // Dont attempt auto-joining again while we are already attempting to join
            // and/or obtaining Ip address
            attemptAutoJoin = false;
        }
        if (DBG) {
            if (selection == null) {
                selection = "<none>";
            }
            logd("wifi setScanResults state" + getCurrentState()
                    + " sup_state=" + state
                    + " debouncing=" + linkDebouncing
                    + " mConnectionRequests=" + mConnectionRequests
                    + " selection=" + selection
                    + " mNumScanResultsReturned " + mNumScanResultsReturned
                     + " mScanResults " + mScanResults.size());
        }
        if (attemptAutoJoin) {
            messageHandlingStatus = MESSAGE_HANDLING_STATUS_PROCESSED;
        }
        // Loose last selected configuration if we have been disconnected for 5 minutes
        if (getDisconnectedTimeMilli() > mWifiConfigStore.wifiConfigLastSelectionHysteresis) {
            mWifiConfigStore.setLastSelectedConfiguration(WifiConfiguration.INVALID_NETWORK_ID);
        }

        if (attemptAutoJoin) {
            synchronized (mScanResultCache) {
                // AutoJoincontroller will directly acces the scan result list and update it with
                // ScanResult status
                mNumScanResultsKnown = mWifiAutoJoinController.newSupplicantResults(attemptAutoJoin);
            }
        }
        if (linkDebouncing) {
            // If debouncing, we dont re-select a SSID or BSSID hence
            // there is no need to call the network selection code
            // in WifiAutoJoinController, instead,
            // just try to reconnect to the same SSID by triggering a roam
            sendMessage(CMD_AUTO_ROAM, mLastNetworkId, 1, null);
        }
    }

    /*
     * Fetch RSSI, linkspeed, and frequency on current connection
     */
    private void fetchRssiLinkSpeedAndFrequencyNative() {
        int newRssi = -1;
        int newLinkSpeed = -1;
        int newFrequency = -1;

        String signalPoll = mWifiNative.signalPoll();

        if (signalPoll != null) {
            String[] lines = signalPoll.split("\n");
            for (String line : lines) {
                String[] prop = line.split("=");
                if (prop.length < 2) continue;
                try {
                    if (prop[0].equals("RSSI")) {
                        newRssi = Integer.parseInt(prop[1]);
                    } else if (prop[0].equals("LINKSPEED")) {
                        newLinkSpeed = Integer.parseInt(prop[1]);
                    } else if (prop[0].equals("FREQUENCY")) {
                        newFrequency = Integer.parseInt(prop[1]);
                    }
                } catch (NumberFormatException e) {
                    //Ignore, defaults on rssi and linkspeed are assigned
                }
            }
        }

        Log.i(TAG, "fetchRssiLinkSpeedAndFrequencyNative, newRssi:" + newRssi
                + ", newLinkSpeed:" + newLinkSpeed
                + ", SSID:" + mWifiInfo.getSSID());
        if (PDBG) {
            logd("fetchRssiLinkSpeedAndFrequencyNative rssi="
                    + Integer.toString(newRssi) + " linkspeed="
                    + Integer.toString(newLinkSpeed));
        }
        
        ///M: ALPS02575372 For logging link statics in bug report
		linkstatics = mWifiNative.wifiLinkStatics();
		loge("wifiLinkStatics=" + linkstatics);
        
        if (newRssi > WifiInfo.INVALID_RSSI && newRssi < WifiInfo.MAX_RSSI) {
            // screen out invalid values
            /* some implementations avoid negative values by adding 256
             * so we need to adjust for that here.
             */
            if (newRssi > 0) newRssi -= 256;
            mWifiInfo.setRssi(newRssi);
            /*
             * Rather then sending the raw RSSI out every time it
             * changes, we precalculate the signal level that would
             * be displayed in the status bar, and only send the
             * broadcast if that much more coarse-grained number
             * changes. This cuts down greatly on the number of
             * broadcasts, at the cost of not informing others
             * interested in RSSI of all the changes in signal
             * level.
             */
            int newSignalLevel = WifiManager.calculateSignalLevel(newRssi, WifiManager.RSSI_LEVELS);
            if (newSignalLevel != mLastSignalLevel) {
                sendRssiChangeBroadcast(newRssi);
            }
            Log.d(TAG, "mLastSignalLevel:" + mLastSignalLevel
                    + ", newSignalLevel:" + newSignalLevel);
            mLastSignalLevel = newSignalLevel;
        } else {
            mWifiInfo.setRssi(WifiInfo.INVALID_RSSI);
        }
        if (hasCustomizedAutoConnect()) {
            if (newRssi < IWifiFwkExt.WEAK_SIGNAL_THRESHOLD) {
                int ipAddr = mWifiInfo.getIpAddress();
                long time = android.os.SystemClock.elapsedRealtime();
                boolean autoConnect = mWifiFwkExt.shouldAutoConnect();
                Log.d(TAG, "fetchRssi, ip:" + ipAddr
                        + ", mDisconnectOperation:" + mDisconnectOperation
                        + ", time:" + time + ", lasttime:" + mLastCheckWeakSignalTime);
                if (ipAddr != 0 && !mDisconnectOperation
                    && (time - mLastCheckWeakSignalTime >
                            IWifiFwkExt.MIN_INTERVAL_CHECK_WEAK_SIGNAL_MS
                        || autoConnect)) {
                    Log.d(TAG, "Rssi < -85, scan for checking signal!");
                    if (!autoConnect) {
                        mLastCheckWeakSignalTime = time;
                    }
                    mDisconnectNetworkId = mLastNetworkId;
                    mScanForWeakSignal = true;
                    mWifiNative.bssFlush();
                    startScan(UNKNOWN_SCAN_SOURCE, 0, null, null);
                }
            }
        }
        if (newLinkSpeed != -1) {
            mWifiInfo.setLinkSpeed(newLinkSpeed);
        }
        if (newFrequency > 0) {
            if (ScanResult.is5GHz(newFrequency)) {
                mWifiConnectionStatistics.num5GhzConnected++;
            }
            if (ScanResult.is24GHz(newFrequency)) {
                mWifiConnectionStatistics.num24GhzConnected++;
            }
            mWifiInfo.setFrequency(newFrequency);
        }
        mWifiConfigStore.updateConfiguration(mWifiInfo);
    }

    /**
     * Determine if we need to switch network:
     * - the delta determine the urgency to switch and/or or the expected evilness of the disruption
     * - match the uregncy of the switch versus the packet usage at the interface
     */
    boolean shouldSwitchNetwork(int networkDelta) {
        int delta;
        if (networkDelta <= 0) {
            return false;
        }
        delta = networkDelta;
        if (mWifiInfo != null) {
            if (!getEnableAutoJoinWhenAssociated()
                    && mWifiInfo.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID) {
                // If AutoJoin while associated is not enabled,
                // we should never switch network when already associated
                delta = -1000;
            } else {
                // TODO: Look at per AC packet count, do not switch if VO/VI traffic is present
                // TODO: at the interface. We should also discriminate between ucast and mcast,
                // TODO: since the rxSuccessRate include all the bonjour and Ipv6
                // TODO: broadcasts
                if ((mWifiInfo.txSuccessRate > 20) || (mWifiInfo.rxSuccessRate > 80)) {
                    delta -= 999;
                } else if ((mWifiInfo.txSuccessRate > 5) || (mWifiInfo.rxSuccessRate > 30)) {
                    delta -= 6;
                }
                logd("shouldSwitchNetwork "
                        + " txSuccessRate=" + String.format("%.2f", mWifiInfo.txSuccessRate)
                        + " rxSuccessRate=" + String.format("%.2f", mWifiInfo.rxSuccessRate)
                        + " delta " + networkDelta + " -> " + delta);
            }
        } else {
            logd("shouldSwitchNetwork "
                    + " delta " + networkDelta + " -> " + delta);
        }

        ///M: still reconnect wifi if wfd/beamplus/hotknot enable
        if (isTemporarilyDontReconnectWifi()) {
            loge("mDontReconnect: " + mDontReconnect.get());
            if (mDontReconnect.get()) {
                Log.d(TAG, "shouldSwitchNetwork don't switch due to mDontReconnect");
                return false;
            } else {
                Log.d(TAG, "shouldSwitchNetwork  switch! Even isTemporarilyDontReconnectWifi");
            }
        }
        ///M:(Google Issue) don't reconnect wifi because p2p could channel conflict
        if (mTemporarilyDisconnectWifi) {
            Log.d(TAG, "shouldSwitchNetwork don't switch due to mTemporarilyDisconnectWifi");
            return false;
        }

        ///M: if have special network selection and wifi is connected, don't try to associate.
        if (mWifiFwkExt != null && mWifiFwkExt.hasNetworkSelection() != 0
            && mWifiInfo.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID) {
            Log.d(TAG, "hasNetworkSelection Don't");
            return false;
        }
        if (delta > 0) {
            return true;
        }
        return false;
    }

    // Polling has completed, hence we wont have a score anymore
    private void cleanWifiScore() {
        mWifiInfo.txBadRate = 0;
        mWifiInfo.txSuccessRate = 0;
        mWifiInfo.txRetriesRate = 0;
        mWifiInfo.rxSuccessRate = 0;
    }

    int mBadLinkspeedcount = 0;

    // For debug, provide information about the last scoring operation
    String wifiScoringReport = null;

    private void calculateWifiScore(WifiLinkLayerStats stats) {
        StringBuilder sb = new StringBuilder();

        int score = 56; // Starting score, temporarily hardcoded in between 50 and 60
        boolean isBadLinkspeed = (mWifiInfo.is24GHz()
                && mWifiInfo.getLinkSpeed() < mWifiConfigStore.badLinkSpeed24)
                || (mWifiInfo.is5GHz() && mWifiInfo.getLinkSpeed()
                < mWifiConfigStore.badLinkSpeed5);
        boolean isGoodLinkspeed = (mWifiInfo.is24GHz()
                && mWifiInfo.getLinkSpeed() >= mWifiConfigStore.goodLinkSpeed24)
                || (mWifiInfo.is5GHz() && mWifiInfo.getLinkSpeed()
                >= mWifiConfigStore.goodLinkSpeed5);

        if (isBadLinkspeed) {
            if (mBadLinkspeedcount < 6)
                mBadLinkspeedcount++;
        } else {
            if (mBadLinkspeedcount > 0)
                mBadLinkspeedcount--;
        }

        if (isBadLinkspeed) sb.append(" bl(").append(mBadLinkspeedcount).append(")");
        if (isGoodLinkspeed) sb.append(" gl");

        /**
         * We want to make sure that we use the 24GHz RSSI thresholds if
         * there are 2.4GHz scan results
         * otherwise we end up lowering the score based on 5GHz values
         * which may cause a switch to LTE before roaming has a chance to try 2.4GHz
         * We also might unblacklist the configuation based on 2.4GHz
         * thresholds but joining 5GHz anyhow, and failing over to 2.4GHz because 5GHz is not good
         */
        boolean use24Thresholds = false;
        boolean homeNetworkBoost = false;
        WifiConfiguration currentConfiguration = getCurrentWifiConfiguration();
        ScanDetailCache scanDetailCache =
                mWifiConfigStore.getScanDetailCache(currentConfiguration);
        if (currentConfiguration != null && scanDetailCache != null) {
            currentConfiguration.setVisibility(scanDetailCache.getVisibility(12000));
            if (currentConfiguration.visibility != null) {
                if (currentConfiguration.visibility.rssi24 != WifiConfiguration.INVALID_RSSI
                        && currentConfiguration.visibility.rssi24
                        >= (currentConfiguration.visibility.rssi5 - 2)) {
                    use24Thresholds = true;
                }
            }
            if (scanDetailCache.size() <= 6
                && currentConfiguration.allowedKeyManagement.cardinality() == 1
                && currentConfiguration.allowedKeyManagement.
                    get(WifiConfiguration.KeyMgmt.WPA_PSK) == true) {
                // A PSK network with less than 6 known BSSIDs
                // This is most likely a home network and thus we want to stick to wifi more
                homeNetworkBoost = true;
            }
        }
        if (homeNetworkBoost) sb.append(" hn");
        if (use24Thresholds) sb.append(" u24");

        int rssi = mWifiInfo.getRssi() - 6 * mAggressiveHandover
                + (homeNetworkBoost ? WifiConfiguration.HOME_NETWORK_RSSI_BOOST : 0);
        sb.append(String.format(" rssi=%d ag=%d", rssi, mAggressiveHandover));

        boolean is24GHz = use24Thresholds || mWifiInfo.is24GHz();

        boolean isBadRSSI = (is24GHz && rssi < mWifiConfigStore.thresholdBadRssi24.get())
                || (!is24GHz && rssi < mWifiConfigStore.thresholdBadRssi5.get());
        boolean isLowRSSI = (is24GHz && rssi < mWifiConfigStore.thresholdLowRssi24.get())
                || (!is24GHz && mWifiInfo.getRssi() < mWifiConfigStore.thresholdLowRssi5.get());
        boolean isHighRSSI = (is24GHz && rssi >= mWifiConfigStore.thresholdGoodRssi24.get())
                || (!is24GHz && mWifiInfo.getRssi() >= mWifiConfigStore.thresholdGoodRssi5.get());

        if (isBadRSSI) sb.append(" br");
        if (isLowRSSI) sb.append(" lr");
        if (isHighRSSI) sb.append(" hr");

        int penalizedDueToUserTriggeredDisconnect = 0;        // For debug information
        if (currentConfiguration != null &&
                (mWifiInfo.txSuccessRate > 5 || mWifiInfo.rxSuccessRate > 5)) {
            if (isBadRSSI) {
                currentConfiguration.numTicksAtBadRSSI++;
                if (currentConfiguration.numTicksAtBadRSSI > 1000) {
                    // We remained associated for a compound amount of time while passing
                    // traffic, hence loose the corresponding user triggered disabled stats
                    if (currentConfiguration.numUserTriggeredWifiDisableBadRSSI > 0) {
                        currentConfiguration.numUserTriggeredWifiDisableBadRSSI--;
                    }
                    if (currentConfiguration.numUserTriggeredWifiDisableLowRSSI > 0) {
                        currentConfiguration.numUserTriggeredWifiDisableLowRSSI--;
                    }
                    if (currentConfiguration.numUserTriggeredWifiDisableNotHighRSSI > 0) {
                        currentConfiguration.numUserTriggeredWifiDisableNotHighRSSI--;
                    }
                    currentConfiguration.numTicksAtBadRSSI = 0;
                }
                if (mWifiConfigStore.enableWifiCellularHandoverUserTriggeredAdjustment &&
                        (currentConfiguration.numUserTriggeredWifiDisableBadRSSI > 0
                                || currentConfiguration.numUserTriggeredWifiDisableLowRSSI > 0
                                || currentConfiguration.numUserTriggeredWifiDisableNotHighRSSI > 0)) {
                    score = score - 5;
                    penalizedDueToUserTriggeredDisconnect = 1;
                    sb.append(" p1");
                }
            } else if (isLowRSSI) {
                currentConfiguration.numTicksAtLowRSSI++;
                if (currentConfiguration.numTicksAtLowRSSI > 1000) {
                    // We remained associated for a compound amount of time while passing
                    // traffic, hence loose the corresponding user triggered disabled stats
                    if (currentConfiguration.numUserTriggeredWifiDisableLowRSSI > 0) {
                        currentConfiguration.numUserTriggeredWifiDisableLowRSSI--;
                    }
                    if (currentConfiguration.numUserTriggeredWifiDisableNotHighRSSI > 0) {
                        currentConfiguration.numUserTriggeredWifiDisableNotHighRSSI--;
                    }
                    currentConfiguration.numTicksAtLowRSSI = 0;
                }
                if (mWifiConfigStore.enableWifiCellularHandoverUserTriggeredAdjustment &&
                        (currentConfiguration.numUserTriggeredWifiDisableLowRSSI > 0
                                || currentConfiguration.numUserTriggeredWifiDisableNotHighRSSI > 0)) {
                    score = score - 5;
                    penalizedDueToUserTriggeredDisconnect = 2;
                    sb.append(" p2");
                }
            } else if (!isHighRSSI) {
                currentConfiguration.numTicksAtNotHighRSSI++;
                if (currentConfiguration.numTicksAtNotHighRSSI > 1000) {
                    // We remained associated for a compound amount of time while passing
                    // traffic, hence loose the corresponding user triggered disabled stats
                    if (currentConfiguration.numUserTriggeredWifiDisableNotHighRSSI > 0) {
                        currentConfiguration.numUserTriggeredWifiDisableNotHighRSSI--;
                    }
                    currentConfiguration.numTicksAtNotHighRSSI = 0;
                }
                if (mWifiConfigStore.enableWifiCellularHandoverUserTriggeredAdjustment &&
                        currentConfiguration.numUserTriggeredWifiDisableNotHighRSSI > 0) {
                    score = score - 5;
                    penalizedDueToUserTriggeredDisconnect = 3;
                    sb.append(" p3");
                }
            }
        }
        ///M: add error handling to prevent NullPointerException
        if (currentConfiguration != null) {
            sb.append(String.format(" ticks %d,%d,%d", currentConfiguration.numTicksAtBadRSSI,
                    currentConfiguration.numTicksAtLowRSSI,
                    currentConfiguration.numTicksAtNotHighRSSI));
        }
        if (PDBG) {
            String rssiStatus = "";
            if (isBadRSSI) rssiStatus += " badRSSI ";
            else if (isHighRSSI) rssiStatus += " highRSSI ";
            else if (isLowRSSI) rssiStatus += " lowRSSI ";
            if (isBadLinkspeed) rssiStatus += " lowSpeed ";
            logd("calculateWifiScore freq=" + Integer.toString(mWifiInfo.getFrequency())
                    + " speed=" + Integer.toString(mWifiInfo.getLinkSpeed())
                    + " score=" + Integer.toString(mWifiInfo.score)
                    + rssiStatus
                    + " -> txbadrate=" + String.format("%.2f", mWifiInfo.txBadRate)
                    + " txgoodrate=" + String.format("%.2f", mWifiInfo.txSuccessRate)
                    + " txretriesrate=" + String.format("%.2f", mWifiInfo.txRetriesRate)
                    + " rxrate=" + String.format("%.2f", mWifiInfo.rxSuccessRate)
                    + " userTriggerdPenalty" + penalizedDueToUserTriggeredDisconnect);
        }

        if ((mWifiInfo.txBadRate >= 1) && (mWifiInfo.txSuccessRate < 3)
                && (isBadRSSI || isLowRSSI)) {
            // Link is stuck
            if (mWifiInfo.linkStuckCount < 5)
                mWifiInfo.linkStuckCount += 1;
            sb.append(String.format(" ls+=%d", mWifiInfo.linkStuckCount));
            if (PDBG) logd(" bad link -> stuck count ="
                    + Integer.toString(mWifiInfo.linkStuckCount));
        } else if (mWifiInfo.txBadRate < 0.3) {
            if (mWifiInfo.linkStuckCount > 0)
                mWifiInfo.linkStuckCount -= 1;
            sb.append(String.format(" ls-=%d", mWifiInfo.linkStuckCount));
            if (PDBG) logd(" good link -> stuck count ="
                    + Integer.toString(mWifiInfo.linkStuckCount));
        }

        sb.append(String.format(" [%d", score));

        if (mWifiInfo.linkStuckCount > 1) {
            // Once link gets stuck for more than 3 seconds, start reducing the score
            score = score - 2 * (mWifiInfo.linkStuckCount - 1);
        }
        sb.append(String.format(",%d", score));

        if (isBadLinkspeed) {
            score -= 4;
            if (PDBG) {
                logd(" isBadLinkspeed   ---> count=" + mBadLinkspeedcount
                        + " score=" + Integer.toString(score));
            }
        } else if ((isGoodLinkspeed) && (mWifiInfo.txSuccessRate > 5)) {
            score += 4; // So as bad rssi alone dont kill us
        }
        sb.append(String.format(",%d", score));

        if (isBadRSSI) {
            if (mWifiInfo.badRssiCount < 7)
                mWifiInfo.badRssiCount += 1;
        } else if (isLowRSSI) {
            mWifiInfo.lowRssiCount = 1; // Dont increment the lowRssi count above 1
            if (mWifiInfo.badRssiCount > 0) {
                // Decrement bad Rssi count
                mWifiInfo.badRssiCount -= 1;
            }
        } else {
            mWifiInfo.badRssiCount = 0;
            mWifiInfo.lowRssiCount = 0;
        }

        score -= mWifiInfo.badRssiCount * 2 + mWifiInfo.lowRssiCount;
        sb.append(String.format(",%d", score));

        if (PDBG) logd(" badRSSI count" + Integer.toString(mWifiInfo.badRssiCount)
                + " lowRSSI count" + Integer.toString(mWifiInfo.lowRssiCount)
                + " --> score " + Integer.toString(score));


        if (isHighRSSI) {
            score += 5;
            if (PDBG) logd(" isHighRSSI       ---> score=" + Integer.toString(score));
        }
        sb.append(String.format(",%d]", score));

        sb.append(String.format(" brc=%d lrc=%d", mWifiInfo.badRssiCount, mWifiInfo.lowRssiCount));

        //sanitize boundaries
        if (score > NetworkAgent.WIFI_BASE_SCORE)
            score = NetworkAgent.WIFI_BASE_SCORE;
        if (score < 0)
            score = 0;

        //report score
        if (score != mWifiInfo.score) {
            if (DBG) {
                logd("calculateWifiScore() report new score " + Integer.toString(score));
            }
            mWifiInfo.score = score;
            if (mNetworkAgent != null && !hasCustomizedAutoConnect()) {
                mNetworkAgent.sendNetworkScore(score);
            }
        }
        wifiScoringReport = sb.toString();
    }

    public double getTxPacketRate() {
        if (mWifiInfo != null) {
            return mWifiInfo.txSuccessRate;
        }
        return -1;
    }

    public double getRxPacketRate() {
        if (mWifiInfo != null) {
            return mWifiInfo.rxSuccessRate;
        }
        return -1;
    }

    /**
     * Fetch TX packet counters on current connection
     */
    private void fetchPktcntNative(RssiPacketCountInfo info) {
        String pktcntPoll = mWifiNative.pktcntPoll();

        if (pktcntPoll != null) {
            String[] lines = pktcntPoll.split("\n");
            for (String line : lines) {
                String[] prop = line.split("=");
                if (prop.length < 2) continue;
                try {
                    if (prop[0].equals("TXGOOD")) {
                        info.txgood = Integer.parseInt(prop[1]);
                    } else if (prop[0].equals("TXBAD")) {
                        info.txbad = Integer.parseInt(prop[1]);
                    }

                    ///M: Poor Link@{
                    else if (prop[0].equals("rFailedCount")) {
                        info.rFailedCount = Long.parseLong(prop[1]);
                    } else if (prop[0].equals("rRetryCount")) {
                        info.rRetryCount = Long.parseLong(prop[1]);
                    } else if (prop[0].equals("rMultipleRetryCount")) {
                        info.rMultipleRetryCount = Long.parseLong(prop[1]);
                    } else if (prop[0].equals("rACKFailureCount")) {
                        info.rACKFailureCount = Long.parseLong(prop[1]);
                    } else if (prop[0].equals("rFCSErrorCount")) {
                        info.rFCSErrorCount = Long.parseLong(prop[1]);
                    }
                    ///@}

                } catch (NumberFormatException e) {
                    // Ignore
                }
            }
        }
    }

    private boolean clearIPv4Address(String iface) {
        try {
            InterfaceConfiguration ifcg = new InterfaceConfiguration();
            ifcg.setLinkAddress(new LinkAddress("0.0.0.0/0"));
            mNwService.setInterfaceConfig(iface, ifcg);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    private boolean isProvisioned(LinkProperties lp) {
        return lp.isProvisioned() ||
                (mWifiConfigStore.isUsingStaticIp(mLastNetworkId) && lp.hasIPv4Address());
    }

    /**
     * Creates a new LinkProperties object by merging information from various sources.
     * <p/>
     * This is needed because the information in mLinkProperties comes from multiple sources (DHCP,
     * netlink, static configuration, ...). When one of these sources of information has updated
     * link properties, we can't just assign them to mLinkProperties or we'd lose track of the
     * information that came from other sources. Instead, when one of those sources has new
     * information, we update the object that tracks the information from that source and then
     * call this method to integrate the change into a new LinkProperties object for subsequent
     * comparison with mLinkProperties.
     * <p/>
     * The information used to build LinkProperties is currently obtained as follows:
     *     - Interface name: set in the constructor.
     *     - IPv4 and IPv6 addresses: netlink, passed in by mNetlinkTracker.
     *     - IPv4 routes, DNS servers, and domains: DHCP.
     *     - IPv6 routes and DNS servers: netlink, passed in by mNetlinkTracker.
     *     - HTTP proxy: the wifi config store.
     */
    private LinkProperties makeLinkProperties() {
        LinkProperties newLp = new LinkProperties();

        // Interface name, proxy, and TCP buffer sizes are locally configured.
        newLp.setInterfaceName(mInterfaceName);
        newLp.setHttpProxy(mWifiConfigStore.getProxyProperties(mLastNetworkId));
        if (!TextUtils.isEmpty(mTcpBufferSizes)) {
            newLp.setTcpBufferSizes(mTcpBufferSizes);
        }

        // IPv4/v6 addresses, IPv6 routes and IPv6 DNS servers come from netlink.
        LinkProperties netlinkLinkProperties = mNetlinkTracker.getLinkProperties();
        newLp.setLinkAddresses(netlinkLinkProperties.getLinkAddresses());
        for (RouteInfo route : netlinkLinkProperties.getRoutes()) {
            newLp.addRoute(route);
        }
        for (InetAddress dns : netlinkLinkProperties.getDnsServers()) {
            // Only add likely reachable DNS servers.
            // TODO: investigate deleting this.
            if (newLp.isReachable(dns)) {
                newLp.addDnsServer(dns);
            }
        }

        if (mMtkDhcpv6cWifi) {
             LinkProperties v6LinkProperties = new LinkProperties();
             //get v4
             // IPv4 routes, DNS servers and domains come from mDhcpResults.
            synchronized (mDhcpResultsLock) {
                // Even when we're using static configuration, we don't need to look at the config
                // store, because static IP configuration also populates mDhcpResults.
                if ((mDhcpResults != null)) {
                    for (RouteInfo route : mDhcpResults.getRoutes(mInterfaceName)) {
                        newLp.addRoute(route);
                    }
                    for (InetAddress dns : mDhcpResults.dnsServers) {
                        // Only add likely reachable DNS servers.
                        // TODO: investigate deleting this.
                        if (newLp.isReachable(dns)) {
                            newLp.addDnsServer(dns);
                        }
                    }
                    newLp.setDomains(mDhcpResults.domains);
                }
            }
             //get v6
             synchronized (mDhcpV6ResultsLock) {
                 if ((mDhcpV6Results != null)) {
                     //merge v4 and v6
                     for (RouteInfo route : mDhcpV6Results.getRoutes(mInterfaceName)) {
                        newLp.addRoute(route);
                    }
                    for (InetAddress dns : mDhcpV6Results.dnsServers) {
                        newLp.addDnsServer(dns);
                    }

                      Collection<LinkAddress> v6Addresses = mDhcpV6Results.toLinkProperties(mInterfaceName).getLinkAddresses();
                      for (LinkAddress address : v6Addresses) {
                          newLp.addLinkAddress(address);
                      }
                 }
             }
             //mLinkProperties = newLinkProperties;
             Log.d(TAG, "configureLinkProperties, mLinkProperties:" + mLinkProperties);

         } else {

            // IPv4 routes, DNS servers and domains come from mDhcpResults.
            synchronized (mDhcpResultsLock) {
                // Even when we're using static configuration, we don't need to look at the config
                // store, because static IP configuration also populates mDhcpResults.
                if ((mDhcpResults != null)) {
                    for (RouteInfo route : mDhcpResults.getRoutes(mInterfaceName)) {
                        newLp.addRoute(route);
                    }
                    for (InetAddress dns : mDhcpResults.dnsServers) {
                        // Only add likely reachable DNS servers.
                        // TODO: investigate deleting this.
                        if (newLp.isReachable(dns)) {
                            newLp.addDnsServer(dns);
                        }
                    }
                    newLp.setDomains(mDhcpResults.domains);
                }
            }
        }

        return newLp;
    }

    private void updateLinkProperties(int reason) {
        LinkProperties newLp = makeLinkProperties();

        ///M: ALPS02043890 If link change is only different in mTcpBufferSizes then ignore it
        if (mLinkProperties != null && mLinkProperties.getTcpBufferSizes() != null
                && TextUtils.isEmpty(mTcpBufferSizes) == false) {
            newLp.setTcpBufferSizes(mTcpBufferSizes);
        }
        final boolean linkChanged = !newLp.equals(mLinkProperties);
        final boolean wasProvisioned = isProvisioned(mLinkProperties);
        final boolean isProvisioned = isProvisioned(newLp);
        // TODO: Teach LinkProperties how to understand static assignment
        // and simplify all this provisioning change detection logic by
        // unifying it under LinkProperties.compareProvisioning().
        final boolean lostProvisioning =
                (wasProvisioned && !isProvisioned) ||
                (mLinkProperties.hasIPv4Address() && !newLp.hasIPv4Address()) ||
                (mLinkProperties.isIPv6Provisioned() && !newLp.isIPv6Provisioned());
        final DetailedState detailedState = getNetworkDetailedState();

        if (linkChanged) {
            if (DBG) {
                log("Link configuration changed for netId: " + mLastNetworkId
                        + " old: " + mLinkProperties + " new: " + newLp);
            }
            mLinkProperties = newLp;
            if (mIpReachabilityMonitor != null) {
                mIpReachabilityMonitor.updateLinkProperties(mLinkProperties);
            }
            if (mNetworkAgent != null) mNetworkAgent.sendLinkProperties(mLinkProperties);
        }

        if (lostProvisioning) {
            log("Lost IP layer provisioning!" +
                    " was: " + mLinkProperties +
                    " now: " + newLp);
        }

        if (DBG) {
            StringBuilder sb = new StringBuilder();
            sb.append("updateLinkProperties nid: " + mLastNetworkId);
            sb.append(" state: " + detailedState);
            sb.append(" reason: " + smToString(reason));

            if (mLinkProperties != null) {
                if (mLinkProperties.hasIPv4Address()) {
                    sb.append(" v4");
                }
                if (mLinkProperties.hasGlobalIPv6Address()) {
                    sb.append(" v6");
                }
                if (mLinkProperties.hasIPv4DefaultRoute()) {
                    sb.append(" v4r");
                }
                if (mLinkProperties.hasIPv6DefaultRoute()) {
                    sb.append(" v6r");
                }
                if (mLinkProperties.hasIPv4DnsServer()) {
                    sb.append(" v4dns");
                }
                if (mLinkProperties.hasIPv6DnsServer()) {
                    sb.append(" v6dns");
                }
                if (isProvisioned) {
                    sb.append(" isprov");
                }
            }
            logd(sb.toString());
        }

        // If we just configured or lost IP configuration, do the needful.
        // We don't just call handleSuccessfulIpConfiguration() or handleIpConfigurationLost()
        // here because those should only be called if we're attempting to connect or already
        // connected, whereas updateLinkProperties can be called at any time.
        switch (reason) {
            case DhcpStateMachine.DHCP_SUCCESS:
            case CMD_STATIC_IP_SUCCESS:
            ///M: for dhcp v6
            case M_CMD_DHCP_V6_SUCCESS:
                // IPv4 provisioning succeded. Advance to connected state.
                sendMessage(CMD_IP_CONFIGURATION_SUCCESSFUL);
                if (!isProvisioned) {
                    // Can never happen unless DHCP reports success but isProvisioned thinks the
                    // resulting configuration is invalid (e.g., no IPv4 address, or the state in
                    // mLinkProperties is out of sync with reality, or there's a bug in this code).
                    // TODO: disconnect here instead. If our configuration is not usable, there's no
                    // point in staying connected, and if mLinkProperties is out of sync with
                    // reality, that will cause problems in the future.
                    logd("IPv4 config succeeded, but not provisioned");
                }
                ///M: for dhcp v6
                if (reason == M_CMD_DHCP_V6_SUCCESS) {
                    mWifiConfigStore.setIpConfiguration(mLastNetworkId, new LinkProperties(mLinkProperties));
                    if (linkChanged && getNetworkDetailedState() == DetailedState.CONNECTED) {
                        // If anything has changed and we're already connected, send out a notification.
                        sendLinkConfigurationChangedBroadcast();
                    }
                }
                break;
            case DhcpStateMachine.DHCP_FAILURE:
                // DHCP failed. If we're not already provisioned, or we had IPv4 and now lost it,
                // give up and disconnect.
                // If we're already provisioned (e.g., IPv6-only network), stay connected.
                if (!isProvisioned || lostProvisioning) {
                    sendMessage(CMD_IP_CONFIGURATION_LOST);
                } else {
                    // DHCP failed, but we're provisioned (e.g., if we're on an IPv6-only network).
                    sendMessage(CMD_IP_CONFIGURATION_SUCCESSFUL);

                    // To be sure we don't get stuck with a non-working network if all we had is
                    // IPv4, remove the IPv4 address from the interface (since we're using DHCP,
                    // and DHCP failed). If we had an IPv4 address before, the deletion of the
                    // address  will cause a CMD_UPDATE_LINKPROPERTIES. If the IPv4 address was
                    // necessary for provisioning, its deletion will cause us to disconnect.
                    //
                    // This shouldn't be needed, because on an IPv4-only network a DHCP failure will
                    // have empty DhcpResults and thus empty LinkProperties, and isProvisioned will
                    // not return true if we're using DHCP and don't have an IPv4 default route. So
                    // for now it's only here for extra redundancy. However, it will increase
                    // robustness if we move to getting IPv4 routes from netlink as well.
                    loge("DHCP failure: provisioned, clearing IPv4 address.");
                    if (!clearIPv4Address(mInterfaceName)) {
                        sendMessage(CMD_IP_CONFIGURATION_LOST);
                    }
                }
                break;

            case CMD_STATIC_IP_FAILURE:
                // Static configuration was invalid, or an error occurred in applying it. Give up.
                sendMessage(CMD_IP_CONFIGURATION_LOST);
                break;

            case CMD_UPDATE_LINKPROPERTIES:
                // IP addresses, DNS servers, etc. changed. Act accordingly.
                if (lostProvisioning) {
                    // We no longer have a usable network configuration. Disconnect.
                    sendMessage(CMD_IP_CONFIGURATION_LOST);
                } else if (!wasProvisioned && isProvisioned) {
                    // We have a usable IPv6-only config. Advance to connected state.
                    sendMessage(CMD_IP_CONFIGURATION_SUCCESSFUL);
                }
                if (linkChanged && getNetworkDetailedState() == DetailedState.CONNECTED) {
                    // If anything has changed and we're already connected, send out a notification.
                    sendLinkConfigurationChangedBroadcast();
                }
                break;
        }
    }


    /**
     * Clears all our link properties.
     */
    private void clearLinkProperties() {
        // Clear the link properties obtained from DHCP and netlink.
        synchronized (mDhcpResultsLock) {
            if (mDhcpResults != null) {
                mDhcpResults.clear();
            }
        }
        mNetlinkTracker.clearLinkProperties();
        if (mIpReachabilityMonitor != null) {
            mIpReachabilityMonitor.clearLinkProperties();
        }


        if (mMtkDhcpv6cWifi) {
            synchronized (mDhcpV6ResultsLock) {
                if ((mDhcpV6Results != null)) {
                    mDhcpV6Results.clear();
                }
            }
        }

        // Now clear the merged link properties.
        mLinkProperties.clear();
        if (mNetworkAgent != null) mNetworkAgent.sendLinkProperties(mLinkProperties);
    }

    /**
     * try to update default route MAC address.
     */
    private String updateDefaultRouteMacAddress(int timeout) {
        String address = null;
        for (RouteInfo route : mLinkProperties.getRoutes()) {
            if (route.isDefaultRoute() && route.hasGateway()) {
                InetAddress gateway = route.getGateway();
                if (gateway instanceof Inet4Address) {
                    if (PDBG) {
                        logd("updateDefaultRouteMacAddress found Ipv4 default :"
                                + gateway.getHostAddress());
                    }
                    address = macAddressFromRoute(gateway.getHostAddress());
                    /* The gateway's MAC address is known */
                    if ((address == null) && (timeout > 0)) {
                        boolean reachable = false;
                        try {
                            reachable = gateway.isReachable(timeout);
                        } catch (Exception e) {
                            loge("updateDefaultRouteMacAddress exception reaching :"
                                    + gateway.getHostAddress());

                        } finally {
                            if (reachable == true) {

                                address = macAddressFromRoute(gateway.getHostAddress());
                                if (PDBG) {
                                    logd("updateDefaultRouteMacAddress reachable (tried again) :"
                                            + gateway.getHostAddress() + " found " + address);
                                }
                            }
                        }
                    }
                    if (address != null) {
                        mWifiConfigStore.setDefaultGwMacAddress(mLastNetworkId, address);
                    }
                }
            }
        }
        return address;
    }

    void sendScanResultsAvailableBroadcast(boolean scanSucceeded) {
        Intent intent = new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_RESULTS_UPDATED, scanSucceeded);
        intent.putExtra(IWifiFwkExt.EXTRA_SHOW_RESELECT_DIALOG_FLAG, mShowReselectDialog);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void sendRssiChangeBroadcast(final int newRssi) {
        try {
            mBatteryStats.noteWifiRssiChanged(newRssi);
        } catch (RemoteException e) {
            // Won't happen.
        }
        Intent intent = new Intent(WifiManager.RSSI_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_NEW_RSSI, newRssi);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void sendNetworkStateChangeBroadcast(String bssid) {
        Intent intent = new Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_NETWORK_INFO, new NetworkInfo(mNetworkInfo));
        intent.putExtra(WifiManager.EXTRA_LINK_PROPERTIES, new LinkProperties(mLinkProperties));
        if (bssid != null)
            intent.putExtra(WifiManager.EXTRA_BSSID, bssid);
        if (mNetworkInfo.getDetailedState() == DetailedState.VERIFYING_POOR_LINK ||
                mNetworkInfo.getDetailedState() == DetailedState.CONNECTED) {
            // We no longer report MAC address to third-parties and our code does
            // not rely on this broadcast, so just send the default MAC address.
            WifiInfo sentWifiInfo = new WifiInfo(mWifiInfo);
            sentWifiInfo.setMacAddress(WifiInfo.DEFAULT_MAC_ADDRESS);
            intent.putExtra(WifiManager.EXTRA_WIFI_INFO, sentWifiInfo);
        }
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private WifiInfo getWiFiInfoForUid(int uid) {
        if (Binder.getCallingUid() == Process.myUid()) {
            return mWifiInfo;
        }

        WifiInfo result = new WifiInfo(mWifiInfo);
        result.setMacAddress(WifiInfo.DEFAULT_MAC_ADDRESS);

        IBinder binder = ServiceManager.getService("package");
        IPackageManager packageManager = IPackageManager.Stub.asInterface(binder);

        try {
            if (packageManager.checkUidPermission(Manifest.permission.LOCAL_MAC_ADDRESS,
                    uid) == PackageManager.PERMISSION_GRANTED) {
                result.setMacAddress(mWifiInfo.getMacAddress());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error checking receiver permission", e);
        }

        return result;
    }

    private void sendLinkConfigurationChangedBroadcast() {
        Intent intent = new Intent(WifiManager.LINK_CONFIGURATION_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_LINK_PROPERTIES, new LinkProperties(mLinkProperties));
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void sendSupplicantConnectionChangedBroadcast(boolean connected) {
        Intent intent = new Intent(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_SUPPLICANT_CONNECTED, connected);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    /**
     * Record the detailed state of a network.
     *
     * @param state the new {@code DetailedState}
     */
    private boolean setNetworkDetailedState(NetworkInfo.DetailedState state) {
        boolean hidden = false;

        if (linkDebouncing || isRoaming()) {
            // There is generally a confusion in the system about colluding
            // WiFi Layer 2 state (as reported by supplicant) and the Network state
            // which leads to multiple confusion.
            //
            // If link is de-bouncing or roaming, we already have an IP address
            // as well we were connected and are doing L2 cycles of
            // reconnecting or renewing IP address to check that we still have it
            // This L2 link flapping should ne be reflected into the Network state
            // which is the state of the WiFi Network visible to Layer 3 and applications
            // Note that once debouncing and roaming are completed, we will
            // set the Network state to where it should be, or leave it as unchanged
            //
            hidden = true;
        }
        if (DBG) {
            log("setDetailed state, old ="
                    + mNetworkInfo.getDetailedState() + " and new state=" + state
                    + " hidden=" + hidden);
        }
        if (mNetworkInfo.getExtraInfo() != null && mWifiInfo.getSSID() != null) {
            // Always indicate that SSID has changed
            if (!mNetworkInfo.getExtraInfo().equals(mWifiInfo.getSSID())) {
                if (DBG) {
                    log("setDetailed state send new extra info" + mWifiInfo.getSSID());
                }
                mNetworkInfo.setExtraInfo(mWifiInfo.getSSID());
                sendNetworkStateChangeBroadcast(null);
            }
        }
        if (hidden == true) {
            return false;
        }

        if (state != mNetworkInfo.getDetailedState()) {
            mNetworkInfo.setDetailedState(state, null, mWifiInfo.getSSID());
            if (mNetworkAgent != null) {
                mNetworkAgent.sendNetworkInfo(mNetworkInfo);
            }
            sendNetworkStateChangeBroadcast(null);
            return true;
        }
        return false;
    }

    private DetailedState getNetworkDetailedState() {
        return mNetworkInfo.getDetailedState();
    }

    private SupplicantState handleSupplicantStateChange(Message message) {
        StateChangeResult stateChangeResult = (StateChangeResult) message.obj;
        SupplicantState state = stateChangeResult.state;
        // Supplicant state change
        // [31-13] Reserved for future use
        // [8 - 0] Supplicant state (as defined in SupplicantState.java)
        // 50023 supplicant_state_changed (custom|1|5)
        mWifiInfo.setSupplicantState(state);
        // Network id is only valid when we start connecting
        if (SupplicantState.isConnecting(state)) {
            mWifiInfo.setNetworkId(stateChangeResult.networkId);
        } else {
            mWifiInfo.setNetworkId(WifiConfiguration.INVALID_NETWORK_ID);
        }

        mWifiInfo.setBSSID(stateChangeResult.BSSID);

        if (mWhiteListedSsids != null
                && mWhiteListedSsids.length > 0
                && stateChangeResult.wifiSsid != null) {
            String SSID = stateChangeResult.wifiSsid.toString();
            String currentSSID = mWifiInfo.getSSID();
            if (SSID != null
                    && currentSSID != null
                    && !SSID.equals(WifiSsid.NONE)) {
                    // Remove quote before comparing
                    if (SSID.length() >= 2 && SSID.charAt(0) == '"'
                            && SSID.charAt(SSID.length() - 1) == '"')
                    {
                        SSID = SSID.substring(1, SSID.length() - 1);
                    }
                    if (currentSSID.length() >= 2 && currentSSID.charAt(0) == '"'
                            && currentSSID.charAt(currentSSID.length() - 1) == '"') {
                        currentSSID = currentSSID.substring(1, currentSSID.length() - 1);
                    }
                    if ((!SSID.equals(currentSSID)) && (getCurrentState() == mConnectedState)) {
                        lastConnectAttemptTimestamp = System.currentTimeMillis();
                        targetWificonfiguration
                            = mWifiConfigStore.getWifiConfiguration(mWifiInfo.getNetworkId());
                        transitionTo(mRoamingState);
                    }
             }
        }

        mWifiInfo.setSSID(stateChangeResult.wifiSsid);
        mWifiInfo.setEphemeral(mWifiConfigStore.isEphemeral(mWifiInfo.getNetworkId()));

        mSupplicantStateTracker.sendMessage(Message.obtain(message));

        return state;
    }

    /**
     * Resets the Wi-Fi Connections by clearing any state, resetting any sockets
     * using the interface, stopping DHCP & disabling interface
     */
    private void handleNetworkDisconnect() {
        if (DBG) log("Stopping DHCP and clearing IP");

        if (hasCustomizedAutoConnect()) {
            DetailedState state = getNetworkDetailedState();
            Log.d(TAG, "handleNetworkDisconnect, state:" + state + ", mDisconnectOperation:" + mDisconnectOperation);
            if (state == DetailedState.CONNECTED) {
                mDisconnectNetworkId = mLastNetworkId;
                if (!mDisconnectOperation) {
                    mScanForWeakSignal = true;
                    mWifiNative.bssFlush();
                    startScan(UNKNOWN_SCAN_SOURCE, 0, null, null);
                }
            }
            if (!mWifiFwkExt.shouldAutoConnect()) {
                disableLastNetwork();
            }
            mDisconnectOperation = false;
            mLastCheckWeakSignalTime = 0;
       }
        if (DBG) log("handleNetworkDisconnect: Stopping DHCP and clearing IP"
                + " stack:" + Thread.currentThread().getStackTrace()[2].getMethodName()
                + " - " + Thread.currentThread().getStackTrace()[3].getMethodName()
                + " - " + Thread.currentThread().getStackTrace()[4].getMethodName()
                + " - " + Thread.currentThread().getStackTrace()[5].getMethodName());


        clearCurrentConfigBSSID("handleNetworkDisconnect");

        stopDhcp();

        if (mMtkCtpppoe) {
            if (mUsingPppoe) {
                stopPPPoE();
            }
        }

        try {
            mNwService.clearInterfaceAddresses(mInterfaceName);
            mNwService.disableIpv6(mInterfaceName);
        } catch (Exception e) {
            loge("Failed to clear addresses or disable ipv6" + e);
        }

        /* Reset data structures */
        mBadLinkspeedcount = 0;
        mWifiInfo.reset();
        linkDebouncing = false;
        /* Reset roaming parameters */
        mAutoRoaming = WifiAutoJoinController.AUTO_JOIN_IDLE;

        /**
         *  fullBandConnectedTimeIntervalMilli:
         *  - start scans at mWifiConfigStore.wifiAssociatedShortScanIntervalMilli seconds interval
         *  - exponentially increase to mWifiConfigStore.associatedFullScanMaxIntervalMilli
         *  Initialize to sane value = 20 seconds
         */
        fullBandConnectedTimeIntervalMilli = 20 * 1000;

        setNetworkDetailedState(DetailedState.DISCONNECTED);
        if (mNetworkAgent != null) {
            mNetworkAgent.sendNetworkInfo(mNetworkInfo);
            mNetworkAgent = null;
        }
        mWifiConfigStore.updateStatus(mLastNetworkId, DetailedState.DISCONNECTED);

        /* Clear network properties */
        clearLinkProperties();

        /* Cend event to CM & network change broadcast */
        sendNetworkStateChangeBroadcast(mLastBssid);

        /* Cancel auto roam requests */
        autoRoamSetBSSID(mLastNetworkId, "any");

        mLastBssid = null;
        registerDisconnected();
        mLastNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
    }

    private void handleSupplicantConnectionLoss(boolean killSupplicant) {
        /* Socket connection can be lost when we do a graceful shutdown
        * or when the driver is hung. Ensure supplicant is stopped here.
        */
        if (killSupplicant) {
            mWifiMonitor.killSupplicant(mP2pSupported);
        }
        mWifiNative.closeSupplicantConnection();
        sendSupplicantConnectionChangedBroadcast(false);
        setWifiState(WIFI_STATE_DISABLED);
    }

    void handlePreDhcpSetup() {
        ///M:add@{
        mDhcpWakeLock.acquire(40000);
        //@}
        mDhcpActive = true;
        if (!mBluetoothConnectionActive) {
            /*
             * There are problems setting the Wi-Fi driver's power
             * mode to active when bluetooth coexistence mode is
             * enabled or sense.
             * <p>
             * We set Wi-Fi to active mode when
             * obtaining an IP address because we've found
             * compatibility issues with some routers with low power
             * mode.
             * <p>
             * In order for this active power mode to properly be set,
             * we disable coexistence mode until we're done with
             * obtaining an IP address.  One exception is if we
             * are currently connected to a headset, since disabling
             * coexistence would interrupt that connection.
             */
            // Disable the coexistence mode
            mWifiNative.setBluetoothCoexistenceMode(
                    mWifiNative.BLUETOOTH_COEXISTENCE_MODE_DISABLED);
        }

        // Disable power save and suspend optimizations during DHCP
        // Note: The order here is important for now. Brcm driver changes
        // power settings when we control suspend mode optimizations.
        // TODO: Remove this comment when the driver is fixed.
        setSuspendOptimizationsNative(SUSPEND_DUE_TO_DHCP, false);
        mWifiNative.setPowerSave(false);

        // Update link layer stats
        getWifiLinkLayerStats(false);

        /* P2p discovery breaks dhcp, shut it down in order to get through this */
        Message msg = new Message();
        msg.what = WifiP2pServiceImpl.BLOCK_DISCOVERY;
        msg.arg1 = WifiP2pServiceImpl.ENABLED;
        msg.arg2 = DhcpStateMachine.CMD_PRE_DHCP_ACTION_COMPLETE;
        msg.obj = mDhcpStateMachine;
        mWifiP2pChannel.sendMessage(msg);

        if (mMtkDhcpv6cWifi) {
            mPreDhcpSetupDone = true;
            mDhcpV4Status = 0;
            mDhcpV6Status = 0;
        }

    }


    private boolean useLegacyDhcpClient() {
        return Settings.Global.getInt(
                mContext.getContentResolver(),
                Settings.Global.LEGACY_DHCP_CLIENT, 0) == 1;
    }

    private void maybeInitDhcpStateMachine() {
        if (mDhcpStateMachine == null) {
            if (useLegacyDhcpClient()) {
                mDhcpStateMachine = DhcpStateMachine.makeDhcpStateMachine(
                        mContext, WifiStateMachine.this, mInterfaceName);
            } else {
                mDhcpStateMachine = DhcpClient.makeDhcpStateMachine(
                        mContext, WifiStateMachine.this, mInterfaceName);
            }
        }
    }

    void startDhcp() {
        maybeInitDhcpStateMachine();
        //IP recover
        String ssid = getCurrentWifiConfiguration().getPrintableSsid();
        DhcpResults record = mDhcpResultMap.get(ssid);
        logd("IP recover: get DhcpResult for ssid = " + ssid + ", record = " + record);

        mDhcpStateMachine.registerForPreDhcpNotification();
        ///M: for IP recover @{
        //mDhcpStateMachine.sendMessage(DhcpStateMachine.CMD_START_DHCP);
        mDhcpStateMachine.sendMessage(DhcpStateMachine.CMD_START_DHCP, record);
        /// @}

        //start DHCPV6
        if (mMtkDhcpv6cWifi) {
            if (mDhcpV6StateMachine == null) {
                mDhcpV6StateMachine = DhcpStateMachine.makeDhcpStateMachine(
                        mContext, WifiStateMachine.this, mInterfaceName);
                mDhcpV6StateMachine.sendMessage(DhcpStateMachine.CMD_SETUP_V6);
            }
            mDhcpV6StateMachine.registerForPreDhcpNotification();
            mDhcpV6StateMachine.sendMessage(DhcpStateMachine.CMD_START_DHCP);
        }

    }
    void renewDhcp() {
        maybeInitDhcpStateMachine();
        mDhcpStateMachine.registerForPreDhcpNotification();
        mDhcpStateMachine.sendMessage(DhcpStateMachine.CMD_RENEW_DHCP);
    }

    void stopDhcp() {
        if (mDhcpStateMachine != null) {
            /* In case we were in middle of DHCP operation restore back powermode */
            handlePostDhcpSetup();
            mDhcpStateMachine.sendMessage(DhcpStateMachine.CMD_STOP_DHCP);
            ///M: quikly stop dhcp without delay @{
            if (!NetworkUtils.stopDhcp(mInterfaceName)) {
                Log.e(TAG, "Failed to stop dhcp on " + mInterfaceName);
            } else {
                Log.d(TAG, "Stop dhcp successfully!");
            }
            ///@}
        }

        if (mMtkDhcpv6cWifi) {
            Log.d(TAG, "Stop dhcpv6!");
            if (mDhcpV6StateMachine != null) {
                mDhcpV6StateMachine.sendMessage(DhcpStateMachine.CMD_STOP_DHCP);
            }
            if (!NetworkUtils.stopDhcpv6(mInterfaceName)) {
                Log.e(TAG, "Failed to stop dhcpv6 on " + mInterfaceName);
            } else {
                Log.d(TAG, "Stop dhcpv6 successfully!");
            }
        }

    }

    void handlePostDhcpSetup() {
        /* Restore power save and suspend optimizations */
        setSuspendOptimizationsNative(SUSPEND_DUE_TO_DHCP, true);
        mWifiNative.setPowerSave(true);

        mWifiP2pChannel.sendMessage(WifiP2pServiceImpl.BLOCK_DISCOVERY, WifiP2pServiceImpl.DISABLED);

        // Set the coexistence mode back to its default value
        mWifiNative.setBluetoothCoexistenceMode(
                mWifiNative.BLUETOOTH_COEXISTENCE_MODE_SENSE);

        mDhcpActive = false;

        ///M:add@{
        mDhcpWakeLock.release();

        if (mMtkDhcpv6cWifi) {
            mPreDhcpSetupDone = false;
        }

        //@}
    }

    void connectScanningService() {

        if (mWifiScanner == null) {
            mWifiScanner = (WifiScanner) mContext.getSystemService(Context.WIFI_SCANNING_SERVICE);
        }
    }

    private void handleIPv4Success(DhcpResults dhcpResults, int reason) {

        if (PDBG) {
            logd("handleIPv4Success <" + dhcpResults.toString() + ">");
            logd("link address " + dhcpResults.ipAddress);
        }

        Inet4Address addr;
        synchronized (mDhcpResultsLock) {
            //IP recover
            String ssid = getCurrentWifiConfiguration().getPrintableSsid();
            logd("IP recover: put DhcpResult for ssid = " + ssid);
            mDhcpResultMap.put(ssid, new DhcpResults(dhcpResults));

            mDhcpResults = dhcpResults;
            addr = (Inet4Address) dhcpResults.ipAddress.getAddress();
        }

        if (isRoaming()) {
            int previousAddress = mWifiInfo.getIpAddress();
            int newAddress = NetworkUtils.inetAddressToInt(addr);
            if (previousAddress != newAddress) {
                logd("handleIPv4Success, roaming and address changed" +
                        mWifiInfo + " got: " + addr);
            }
        }
        mWifiInfo.setInetAddress(addr);
        mWifiInfo.setMeteredHint(dhcpResults.hasMeteredHint());
        updateLinkProperties(reason);
    }

    private void handleSuccessfulIpConfiguration() {
        mLastSignalLevel = -1; // Force update of signal strength
        WifiConfiguration c = getCurrentWifiConfiguration();
        if (c != null) {
            // Reset IP failure tracking
            c.numConnectionFailures = 0;

            // Tell the framework whether the newly connected network is trusted or untrusted.
            updateCapabilities(c);
        }
        if (c != null) {
            ScanResult result = getCurrentScanResult();
            if (result == null) {
                logd("WifiStateMachine: handleSuccessfulIpConfiguration and no scan results" +
                        c.configKey());
            } else {
                // Clear the per BSSID failure count
                result.numIpConfigFailures = 0;
                // Clear the WHOLE BSSID blacklist, which means supplicant is free to retry
                // any BSSID, even though it may already have a non zero ip failure count,
                // this will typically happen if the user walks away and come back to his arrea
                // TODO: implement blacklisting based on a timer, i.e. keep BSSID blacklisted
                // in supplicant for a couple of hours or a day
                mWifiConfigStore.clearBssidBlacklist();
            }
        }
    }

    private void handleIPv4Failure(int reason) {
        synchronized(mDhcpResultsLock) {
             if (mDhcpResults != null) {
                 mDhcpResults.clear();
             }
        }
        if (PDBG) {
            logd("handleIPv4Failure");
        }
        updateLinkProperties(reason);
    }

    private void handleIpConfigurationLost() {
        mWifiInfo.setInetAddress(null);
        mWifiInfo.setMeteredHint(false);

        mWifiConfigStore.handleSSIDStateChange(mLastNetworkId, false,
                "DHCP FAILURE", mWifiInfo.getBSSID());

        /* DHCP times out after about 30 seconds, we do a
         * disconnect thru supplicant, we will let autojoin retry connecting to the network
         */
        mWifiNative.disconnect();
        if (hasCustomizedAutoConnect()) {
            mIpConfigLost = true;
            mDisconnectOperation = true;
        }
    }

    // TODO: De-duplicated this and handleIpConfigurationLost().
    private void handleIpReachabilityLost() {
        // No need to be told about any additional neighbors that might also
        // become unreachable--quiet them now while we start disconnecting.
        if (mIpReachabilityMonitor != null) {
            mIpReachabilityMonitor.clearLinkProperties();
        }

        mWifiInfo.setInetAddress(null);
        mWifiInfo.setMeteredHint(false);

        // TODO: Determine whether to call some form of mWifiConfigStore.handleSSIDStateChange().

        // Disconnect via supplicant, and let autojoin retry connecting to the network.
        mWifiNative.disconnect();
    }

    private int convertFrequencyToChannelNumber(int frequency) {
        if (frequency >= 2412 && frequency <= 2484) {
            return (frequency -2412) / 5 + 1;
        } else if (frequency >= 5170  &&  frequency <=5825) {
            //DFS is included
            return (frequency -5170) / 5 + 34;
        } else {
            return 0;
        }
    }

    private int chooseApChannel(int apBand) {
        int apChannel;
        int[] channel;

        if (apBand == 0)  {
            if (mWifiApConfigStore.allowed2GChannel == null ||
                    mWifiApConfigStore.allowed2GChannel.size() == 0) {
                //most safe channel to use
                if(DBG) {
                    Log.d(TAG, "No specified 2G allowed channel list");
                }
                apChannel = 6;
            } else {
                int index = mRandom.nextInt(mWifiApConfigStore.allowed2GChannel.size());
                apChannel = mWifiApConfigStore.allowed2GChannel.get(index).intValue();
            }
        } else {
            //5G without DFS
            channel = mWifiNative.getChannelsForBand(2);
            if (channel != null && channel.length > 0) {
                apChannel = channel[mRandom.nextInt(channel.length)];
                apChannel = convertFrequencyToChannelNumber(apChannel);
            } else {
                Log.e(TAG, "SoftAp do not get available channel list");
                apChannel = 0;
            }
        }

        if(DBG) {
            Log.d(TAG, "SoftAp set on channel " + apChannel);
        }

        return apChannel;
    }

    /* SoftAP configuration */
    private boolean enableSoftAp() {
        if (WifiNative.getInterfaces() != 0) {
            if (!mWifiNative.toggleInterface(0)) {
                if (DBG) Log.e(TAG, "toggleInterface failed");
                return false;
            }
        } else {
            if (DBG) Log.d(TAG, "No interfaces to toggle");
        }

        try {
            mNwService.wifiFirmwareReload(mInterfaceName, "AP");
            if (DBG) Log.d(TAG, "Firmware reloaded in AP mode");
        } catch (Exception e) {
            Log.e(TAG, "Failed to reload AP firmware " + e);
        }

        if (WifiNative.startHal() == false) {
            /* starting HAL is optional */
            Log.e(TAG, "Failed to start HAL");
        }
        return true;
    }

    /* Current design is to not set the config on a running hostapd but instead
     * stop and start tethering when user changes config on a running access point
     *
     * TODO: Add control channel setup through hostapd that allows changing config
     * on a running daemon
     */
    private void startSoftApWithConfig(final WifiConfiguration configuration) {
        // set channel
        final WifiConfiguration config = new WifiConfiguration(configuration);

        if (DBG) {
            Log.d(TAG, "SoftAp config channel is: " + config.apChannel);
        }

        //We need HAL support to set country code and get available channel list, if HAL is
        //not available, like razor, we regress to original implementaion (2GHz, channel 6)
        if (mWifiNative.isHalStarted()) {
            //set country code through HAL Here
            if (mSetCountryCode != null) {
                if (!mWifiNative.setCountryCodeHal(mSetCountryCode.toUpperCase(Locale.ROOT))) {
                    if (config.apBand != 0) {
                        Log.e(TAG, "Fail to set country code. Can not setup Softap on 5GHz");
                        //countrycode is mandatory for 5GHz
                        sendMessage(CMD_START_AP_FAILURE, WifiManager.SAP_START_FAILURE_GENERAL);
                        return;
                    }
                }
            } else {
                if (config.apBand != 0) {
                    //countrycode is mandatory for 5GHz
                    Log.e(TAG, "Can not setup softAp on 5GHz without country code!");
                    sendMessage(CMD_START_AP_FAILURE, WifiManager.SAP_START_FAILURE_GENERAL);
                    return;
                }
            }

            if (config.apChannel == 0) {
                config.apChannel = chooseApChannel(config.apBand);
                if (config.apChannel == 0) {
                    if(mWifiNative.isGetChannelsForBandSupported()) {
                        //fail to get available channel
                        sendMessage(CMD_START_AP_FAILURE, WifiManager.SAP_START_FAILURE_NO_CHANNEL);
                        return;
                    } else {
                        //for some old device, wifiHal may not be supportedget valid channels are not
                        //supported
                        config.apBand = 0;
                        config.apChannel = 6;
                    }
                }
            }
        } else {
            //for some old device, wifiHal may not be supported
            config.apBand = 0;
            config.apChannel = 6;
        }
        // Start hostapd on a separate thread
        new Thread(new Runnable() {
            public void run() {
                if (DBG) { Log.d(TAG, "startSoftApWithConfig, config:" + config); }
                try {
                    mNwService.startAccessPoint(config, mInterfaceName);
                } catch (Exception e) {
                    loge("Exception in softap start " + e);
                    try {
                        mNwService.stopAccessPoint(mInterfaceName);
                        mNwService.startAccessPoint(config, mInterfaceName);
                    } catch (Exception e1) {
                        loge("Exception in softap re-start " + e1);
                        sendMessage(CMD_START_AP_FAILURE, WifiManager.SAP_START_FAILURE_GENERAL);
                        return;
                    }
                }
                if (DBG) log("Soft AP start successful");
                sendMessage(CMD_START_AP_SUCCESS);
            }
        }).start();
    }

    /*
     * Read a MAC address in /proc/arp/table, used by WifistateMachine
     * so as to record MAC address of default gateway.
     **/
    private String macAddressFromRoute(String ipAddress) {
        String macAddress = null;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/net/arp"));

            // Skip over the line bearing colum titles
            String line = reader.readLine();

            while ((line = reader.readLine()) != null) {
                String[] tokens = line.split("[ ]+");
                if (tokens.length < 6) {
                    continue;
                }

                // ARP column format is
                // Address HWType HWAddress Flags Mask IFace
                String ip = tokens[0];
                String mac = tokens[3];

                if (ipAddress.equals(ip)) {
                    macAddress = mac;
                    break;
                }
            }

            if (macAddress == null) {
                loge("Did not find remoteAddress {" + ipAddress + "} in " +
                        "/proc/net/arp");
            }

        } catch (FileNotFoundException e) {
            loge("Could not open /proc/net/arp to lookup mac address");
        } catch (IOException e) {
            loge("Could not read /proc/net/arp to lookup mac address");
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                // Do nothing
            }
        }
        return macAddress;

    }

    private class WifiNetworkFactory extends NetworkFactory {
        public WifiNetworkFactory(Looper l, Context c, String TAG, NetworkCapabilities f) {
            super(l, c, TAG, f);
        }

        @Override
        protected void needNetworkFor(NetworkRequest networkRequest, int score) {
            ++mConnectionRequests;
        }

        @Override
        protected void releaseNetworkFor(NetworkRequest networkRequest) {
            --mConnectionRequests;
        }

        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            pw.println("mConnectionRequests " + mConnectionRequests);
        }

    }

    private class UntrustedWifiNetworkFactory extends NetworkFactory {
        private int mUntrustedReqCount;

        public UntrustedWifiNetworkFactory(Looper l, Context c, String tag, NetworkCapabilities f) {
            super(l, c, tag, f);
        }

        @Override
        protected void needNetworkFor(NetworkRequest networkRequest, int score) {
            if (!networkRequest.networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_TRUSTED)) {
                if (++mUntrustedReqCount == 1) {
                    mWifiAutoJoinController.setAllowUntrustedConnections(true);
                }
            }
        }

        @Override
        protected void releaseNetworkFor(NetworkRequest networkRequest) {
            if (!networkRequest.networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_TRUSTED)) {
                if (--mUntrustedReqCount == 0) {
                    mWifiAutoJoinController.setAllowUntrustedConnections(false);
                }
            }
        }

        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            pw.println("mUntrustedReqCount " + mUntrustedReqCount);
        }
    }

    void maybeRegisterNetworkFactory() {
        if (mNetworkFactory == null) {
            checkAndSetConnectivityInstance();
            if (mCm != null) {
                mNetworkFactory = new WifiNetworkFactory(getHandler().getLooper(), mContext,
                        NETWORKTYPE, mNetworkCapabilitiesFilter);
                mNetworkFactory.setScoreFilter(60);
                mNetworkFactory.register();

                // We can't filter untrusted network in the capabilities filter because a trusted
                // network would still satisfy a request that accepts untrusted ones.
                mUntrustedNetworkFactory = new UntrustedWifiNetworkFactory(getHandler().getLooper(),
                        mContext, NETWORKTYPE_UNTRUSTED, mNetworkCapabilitiesFilter);
                mUntrustedNetworkFactory.setScoreFilter(Integer.MAX_VALUE);
                mUntrustedNetworkFactory.register();
            }
        }
    }

    /********************************************************
     * HSM states
     *******************************************************/

    class DefaultState extends State {
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch (message.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED: {
                    AsyncChannel ac = (AsyncChannel) message.obj;
                    if (ac == mWifiP2pChannel) {
                        if (message.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                            mWifiP2pChannel.sendMessage(AsyncChannel.CMD_CHANNEL_FULL_CONNECTION);
                        } else {
                            loge("WifiP2pService connection failure, error=" + message.arg1);
                        }
                    } else {
                        loge("got HALF_CONNECTED for unknown channel");
                    }
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                    AsyncChannel ac = (AsyncChannel) message.obj;
                    if (ac == mWifiP2pChannel) {
                        loge("WifiP2pService channel lost, message.arg1 =" + message.arg1);
                        //TODO: Re-establish connection to state machine after a delay
                        // mWifiP2pChannel.connect(mContext, getHandler(),
                        // mWifiP2pManager.getMessenger());
                    }
                    break;
                }
                case CMD_BLUETOOTH_ADAPTER_STATE_CHANGE:
                    mBluetoothConnectionActive = (message.arg1 !=
                            BluetoothAdapter.STATE_DISCONNECTED);
                    break;
                    /* Synchronous call returns */
                case CMD_PING_SUPPLICANT:
                case CMD_ENABLE_NETWORK:
                case CMD_ADD_OR_UPDATE_NETWORK:
                case CMD_REMOVE_NETWORK:
                case CMD_SAVE_CONFIG:
                    replyToMessage(message, message.what, FAILURE);
                    break;
                case CMD_GET_CAPABILITY_FREQ:
                    replyToMessage(message, message.what, null);
                    break;
                case CMD_GET_CONFIGURED_NETWORKS:
                    replyToMessage(message, message.what, (List<WifiConfiguration>) null);
                    break;
                case CMD_GET_PRIVILEGED_CONFIGURED_NETWORKS:
                    replyToMessage(message, message.what, (List<WifiConfiguration>) null);
                    break;
                case CMD_ENABLE_RSSI_POLL:
                    mEnableRssiPolling = (message.arg1 == 1);
                    break;
                case CMD_SET_HIGH_PERF_MODE:
                    if (message.arg1 == 1) {
                        setSuspendOptimizations(SUSPEND_DUE_TO_HIGH_PERF, false);
                    } else {
                        setSuspendOptimizations(SUSPEND_DUE_TO_HIGH_PERF, true);
                    }
                    break;
                case CMD_BOOT_COMPLETED:
                    maybeRegisterNetworkFactory();
                    break;
                case CMD_SCREEN_STATE_CHANGED:
                    handleScreenStateChanged(message.arg1 != 0);
                    break;
                    /* Discard */
                case CMD_START_SCAN:
                    messageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                    break;
                case CMD_START_SUPPLICANT:
                case CMD_STOP_SUPPLICANT:
                case CMD_STOP_SUPPLICANT_FAILED:
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_DELAYED_STOP_DRIVER:
                case CMD_DRIVER_START_TIMED_OUT:
                case CMD_START_AP:
                case CMD_START_AP_SUCCESS:
                case CMD_START_AP_FAILURE:
                case CMD_STOP_AP:
                case CMD_TETHER_STATE_CHANGE:
                case CMD_TETHER_NOTIFICATION_TIMED_OUT:
                case CMD_DISCONNECT:
                case CMD_RECONNECT:
                case CMD_REASSOCIATE:
                case CMD_RELOAD_TLS_AND_RECONNECT:
                case WifiMonitor.SUP_CONNECTION_EVENT:
                case WifiMonitor.SUP_DISCONNECTION_EVENT:
                case WifiMonitor.NETWORK_CONNECTION_EVENT:
                case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                case WifiMonitor.SCAN_RESULTS_EVENT:
                case WifiMonitor.SCAN_FAILED_EVENT:
                ///M: Handle it in the end
                //case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                case WifiMonitor.AUTHENTICATION_FAILURE_EVENT:
                case WifiMonitor.ASSOCIATION_REJECTION_EVENT:
                case WifiMonitor.WPS_OVERLAP_EVENT:
                case CMD_BLACKLIST_NETWORK:
                case CMD_CLEAR_BLACKLIST:
                case CMD_SET_OPERATIONAL_MODE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_RSSI_POLL:
                case CMD_ENABLE_ALL_NETWORKS:
                case DhcpStateMachine.CMD_PRE_DHCP_ACTION:
                case DhcpStateMachine.CMD_POST_DHCP_ACTION:
                /* Handled by WifiApConfigStore */
                case CMD_SET_AP_CONFIG:
                case CMD_SET_AP_CONFIG_COMPLETED:
                case CMD_REQUEST_AP_CONFIG:
                case CMD_RESPONSE_AP_CONFIG:
                case WifiWatchdogStateMachine.POOR_LINK_DETECTED:
                case WifiWatchdogStateMachine.GOOD_LINK_DETECTED:
                case CMD_NO_NETWORKS_PERIODIC_SCAN:
                case CMD_DISABLE_P2P_RSP:
                case WifiMonitor.SUP_REQUEST_IDENTITY:
                case CMD_TEST_NETWORK_DISCONNECT:
                case CMD_OBTAINING_IP_ADDRESS_WATCHDOG_TIMER:
                case WifiMonitor.SUP_REQUEST_SIM_AUTH:
                case CMD_TARGET_BSSID:
                case CMD_AUTO_CONNECT:
                case CMD_AUTO_ROAM:
                case CMD_AUTO_SAVE_NETWORK:
                case CMD_ASSOCIATED_BSSID:
                case CMD_UNWANTED_NETWORK:
                case CMD_DISCONNECTING_WATCHDOG_TIMER:
                case CMD_ROAM_WATCHDOG_TIMER:
                case CMD_DISABLE_EPHEMERAL_NETWORK:
                case CMD_RESTART_AUTOJOIN_OFFLOAD:
                case CMD_STARTED_PNO_DBG:
                case CMD_STARTED_GSCAN_DBG:
                case CMD_UPDATE_ASSOCIATED_SCAN_PERMISSION:
                    messageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                    break;
                case DhcpStateMachine.CMD_ON_QUIT:

                    if (mMtkDhcpv6cWifi) {
                        if (message.arg1 == DhcpStateMachine.DHCPV6) {
                            Log.d(TAG, "Set mDhcpV6StateMachine to null!");
                            mDhcpV6StateMachine = null;
                        } else {
                            Log.d(TAG, "Set mDhcpStateMachine to null!");
                            mDhcpStateMachine = null;
                        }
                    } else {
                        mDhcpStateMachine = null;
                    }
                    break;
                case CMD_SET_SUSPEND_OPT_ENABLED:
                    if (message.arg1 == 1) {
                        mSuspendWakeLock.release();
                        setSuspendOptimizations(SUSPEND_DUE_TO_SCREEN, true);
                    } else {
                        setSuspendOptimizations(SUSPEND_DUE_TO_SCREEN, false);
                    }
                    break;
                case WifiMonitor.DRIVER_HUNG_EVENT:
                    setSupplicantRunning(false);
                    setSupplicantRunning(true);
                    break;
                case WifiManager.CONNECT_NETWORK:
                    replyToMessage(message, WifiManager.CONNECT_NETWORK_FAILED,
                            WifiManager.BUSY);
                    break;
                case WifiManager.FORGET_NETWORK:
                    replyToMessage(message, WifiManager.FORGET_NETWORK_FAILED,
                            WifiManager.BUSY);
                    break;
                case WifiManager.SAVE_NETWORK:
                    messageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
                    replyToMessage(message, WifiManager.SAVE_NETWORK_FAILED,
                            WifiManager.BUSY);
                    break;
                case WifiManager.START_WPS:
                    replyToMessage(message, WifiManager.WPS_FAILED,
                            WifiManager.BUSY);
                    break;
                case WifiManager.CANCEL_WPS:
                    replyToMessage(message, WifiManager.CANCEL_WPS_FAILED,
                            WifiManager.BUSY);
                    break;
                case WifiManager.DISABLE_NETWORK:
                    replyToMessage(message, WifiManager.DISABLE_NETWORK_FAILED,
                            WifiManager.BUSY);
                    break;
                case WifiManager.RSSI_PKTCNT_FETCH:
                    replyToMessage(message, WifiManager.RSSI_PKTCNT_FETCH_FAILED,
                            WifiManager.BUSY);
                    break;
                case CMD_GET_SUPPORTED_FEATURES:
                    int featureSet = WifiNative.getSupportedFeatureSet();
                    replyToMessage(message, message.what, featureSet);
                    break;
                case CMD_FIRMWARE_ALERT:
                    if (mWifiLogger != null) {
                        byte[] buffer = (byte[])message.obj;
                        mWifiLogger.captureAlertData(message.arg1, buffer);
                    }
                    break;
                case CMD_GET_LINK_LAYER_STATS:
                    // Not supported hence reply with error message
                    replyToMessage(message, message.what, null);
                    break;
                case WifiP2pServiceImpl.P2P_CONNECTION_CHANGED:
                    NetworkInfo info = (NetworkInfo) message.obj;
                    mP2pConnected.set(info.isConnected());
                    break;
                case WifiP2pServiceImpl.DISCONNECT_WIFI_REQUEST:
                    mTemporarilyDisconnectWifi = (message.arg1 == 1);
                    replyToMessage(message, WifiP2pServiceImpl.DISCONNECT_WIFI_RESPONSE);
                    break;
                /* Link configuration (IP address, DNS, ...) changes notified via netlink */
                case CMD_UPDATE_LINKPROPERTIES:
                    updateLinkProperties(CMD_UPDATE_LINKPROPERTIES);
                    break;
                case CMD_GET_MATCHING_CONFIG:
                    replyToMessage(message, message.what);
                    break;
                case CMD_IP_CONFIGURATION_SUCCESSFUL:
                case CMD_IP_CONFIGURATION_LOST:
                case CMD_IP_REACHABILITY_LOST:
                    messageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                    break;
                case CMD_GET_CONNECTION_STATISTICS:
                    replyToMessage(message, message.what, mWifiConnectionStatistics);
                    break;
                case CMD_REMOVE_APP_CONFIGURATIONS:
                    deferMessage(message);
                    break;
                case CMD_REMOVE_USER_CONFIGURATIONS:
                    deferMessage(message);
                    break;
                case M_CMD_DO_CTIA_TEST_ON:
                case M_CMD_DO_CTIA_TEST_OFF:
                case M_CMD_DO_CTIA_TEST_RATE:
                case M_CMD_UPDATE_RSSI:
                case M_CMD_BLOCK_CLIENT:
                case M_CMD_UNBLOCK_CLIENT:
                case M_CMD_SET_AP_PROBE_REQUEST_ENABLED:
                    replyToMessage(message, message.what, FAILURE);
                    break;
                case M_CMD_GET_CONNECTING_NETWORK_ID:
                    replyToMessage(message, message.what, INVALID_NETWORK_ID);
                    break;
                case M_CMD_SET_TX_POWER_ENABLED:
                    boolean ok = mWifiNative.setTxPowerEnabled(message.arg1 == 1);
                    replyToMessage(message, message.what, ok ? SUCCESS : FAILURE);
                    break;
                case M_CMD_SET_TX_POWER:
                    ok = mWifiNative.setTxPower(message.arg1);
                    replyToMessage(message, message.what, ok ? SUCCESS : FAILURE);
                    break;
                case M_CMD_UPDATE_SETTINGS:
                case M_CMD_SET_POWER_SAVING_MODE:
                case M_CMD_START_AP_WPS:
                case M_CMD_SLEEP_POLICY_STOP_SCAN:
                case M_CMD_NOTIFY_SCREEN_OFF:
                case M_CMD_NOTIFY_SCREEN_ON:
                    // M: For stop scan after screen off in disconnected state feature
                    // M: Discard if not in disconnected state
                case M_CMD_FLUSH_BSS:
                    break;
                case M_CMD_UPDATE_COUNTRY_CODE:
                    mCountryCode = (String) message.obj;
                    mSetCountryCode = (String) message.obj;
                    break;
                case M_CMD_GET_DISCONNECT_FLAG:
                    replyToMessage(message, message.what, mWifiNative.getDisconnectFlag());
                    break;
                case M_CMD_NOTIFY_CONNECTION_FAILURE:
                    mConnectNetwork = false;
                    sendMessage(M_CMD_UPDATE_BGSCAN);
                    break;
                 case M_CMD_GET_WIFI_STATUS:
                 case M_CMD_GET_TEST_ENV:
                    replyToMessage(message, message.what, null);
                    break;
                case WifiManager.START_PPPOE:
                    if (mMtkCtpppoe) {
                        replyToMessage(message, WifiManager.START_PPPOE_FAILED, WifiManager.BUSY);
                    } else {
                        replyToMessage(message, WifiManager.START_PPPOE_FAILED, WifiManager.ERROR);
                    }
                    break;
                case WifiManager.STOP_PPPOE:
                    if (mMtkCtpppoe) {
                        replyToMessage(message, WifiManager.STOP_PPPOE_FAILED, WifiManager.BUSY);
                    } else {
                        replyToMessage(message, WifiManager.STOP_PPPOE_FAILED, WifiManager.ERROR);
                    }
                    break;
                /// M: NFC Float II @{
                case WifiManager.GET_WPS_CRED_AND_CONNECT:
                    replyToMessage(message, WifiManager.GET_WPS_CRED_AND_CONNECT_FAILED, WifiManager.BUSY);
                    break;
                case WifiManager.WRITE_CRED_TO_NFC:
                    replyToMessage(message, WifiManager.WRITE_CRED_TO_NFC_FAILED, WifiManager.BUSY);
                    break;
                case WifiManager.WRITE_PIN_TO_NFC:
                    replyToMessage(message, WifiManager.WRITE_PIN_TO_NFC_FAILED, WifiManager.BUSY);
                    break;
                case WifiManager.GET_CRED_FROM_NFC:
                    replyToMessage(message, WifiManager.GET_CRED_FROM_NFC_FAILED, WifiManager.BUSY);
                    break;
                case WifiManager.GET_PIN_FROM_NFC:
                    replyToMessage(message, WifiManager.GET_PIN_FROM_NFC_FAILED, WifiManager.BUSY);
                    break;
                case M_CMD_CLEAE_HR_WAIT_FLAG:
                    mWaitingForHrToken = false;
                    break;
                case WifiManager.START_WPS_REG:
                case WifiManager.START_WPS_ER:
                case WifiManager.GET_WPS_PIN_AND_CONNECT:
                case M_CMD_START_WPS_NFC_TAG_READ:
                case M_CMD_HS_RECEIVED:
                    replyToMessage(message, WifiManager.WPS_FAILED, WifiManager.BUSY);
                    break;
                /// @}
                ///M: Add for set Wowlan mode @{
                case M_CMD_SET_WOWLAN_NORMAL_MODE:
                    ok = mWifiNative.setWoWlanNormalModeCommand();
                    replyToMessage(message, message.what, ok ? SUCCESS : FAILURE);
                    break;
                case M_CMD_SET_WOWLAN_MAGIC_MODE:
                    ok = mWifiNative.setWoWlanMagicModeCommand();
                    replyToMessage(message, message.what, ok ? SUCCESS : FAILURE);
                    break;
                    ///@}
                //M: for proprietary use, do not reconnect or scan during a period of time
                case WifiManager.SET_WIFI_NOT_RECONNECT_AND_SCAN:
                    loge("SET_WIFI_NOT_RECONNECT_AND_SCAN " + message);
                    if (message.arg1 == 1 || message.arg1 == 2) {
                        loge("set dont_reconnect_scan flag");
                        removeMessages(WifiManager.SET_WIFI_NOT_RECONNECT_AND_SCAN);
                        if (message.arg2 > 0) {
                            sendMessageDelayed(obtainMessage(
                                WifiManager.SET_WIFI_NOT_RECONNECT_AND_SCAN,
                                0, -1), message.arg2 * 1000);
                        }
                        loge("message.arg1: " + message.arg1);
                        if (message.arg1 == 2) {
                            loge("isAllowReconnect is false");
                            mDontReconnect.set(true);
                        }
                        if (isTemporarilyDontReconnectWifi() == true) {
                            //status don't change, skip
                            break;
                        }
                        mDontReconnectAndScan.set(true);
                        sendMessage(M_CMD_UPDATE_BGSCAN);
                    } else {
                        loge("reset dont_reconnect_scan flag");
                        removeMessages(WifiManager.SET_WIFI_NOT_RECONNECT_AND_SCAN);
                        if (isTemporarilyDontReconnectWifi() == false) {
                            //status don't change, skip
                            break;
                        }
                        mDontReconnect.set(false);
                        mDontReconnectAndScan.set(false);
                        sendMessage(M_CMD_UPDATE_BGSCAN);
                    }
                    break;
                case M_CMD_SET_POORLINK_RSSI:
                case M_CMD_SET_POORLINK_LINKSPEED:
                    loge("poor link is unconditionally disabled");
                    replyToMessage(message, message.what, FAILURE);
                  break;
                case M_CMD_START_AUTOJOIN_PROFILING:
                    mWifiAutoJoinController.enableDebugProgiling(message.arg1);
                    loge("enableDebugProgiling = " + message.arg1);
                    break;
                ///M: ALPS02279279 [Google Issue] Cannot remove configured networks @{
                case M_CMD_FACTORY_RESET:
                    deferMessage(message);
                    break;
                ///@}
                ///M: ALPS01975084 enable EAP SIM if SIM is ready @{
                case M_CMD_ENABLE_EAP_SIM_CONFIG_NETWORK:
                    List<WifiConfiguration> networks = mWifiConfigStore.getConfiguredNetworks();
                    if (null != networks) {
                        for (WifiConfiguration network : networks) {
                            int value = network.enterpriseConfig.getEapMethod();
                            log("EAP value:" + value);
                            if ((value == WifiEnterpriseConfig.Eap.SIM
                                        || value == WifiEnterpriseConfig.Eap.AKA
                                        || value == WifiEnterpriseConfig.Eap.AKA_PRIME)
                                && (network.autoJoinStatus
                                    == WifiConfiguration.AUTO_JOIN_DISABLED_EAP_SIM_CARD_ABSENT
                                    || network.autoJoinStatus
                                    == WifiConfiguration.AUTO_JOIN_DISABLED_EAP_SIM_AIRPLANE_MODE))
                            {
                                WifiConfiguration eapSimConfig
                                    = mWifiConfigStore.getWifiConfiguration(network.networkId);
                                eapSimConfig.setAutoJoinStatus(WifiConfiguration.AUTO_JOIN_ENABLED);
                            }
                        }
                    } else {
                        log("Check for EAP_SIM_AKA, networks is null!");
                    }
                    break;
                ///@}
                ///M: ALPS02550356 @{
                case M_CMD_IP_REACHABILITY_MONITOR_TIMER_10s:
                    break;
                case M_CMD_IP_REACHABILITY_MONITOR_TIMER_3s:
                    break;
                ///@}
                ///M: ALPS02595354 for sync network state change event with settings @{
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:

                    if(DBG) {
                        log("handle SUPPLICANT_STATE_CHANGE_EVENT in default state");
                    }
                    StateChangeResult stateChangeResult = (StateChangeResult) message.obj;
                    if(DBG) {
                        loge("SUPPLICANT_STATE_CHANGE_EVENT state=" + stateChangeResult.state +
                                " -> state= " + WifiInfo.getDetailedStateOf(stateChangeResult.state)
                                + " debouncing=" + linkDebouncing);
                    }
                    // update mWifiInfo
                    SupplicantState state = stateChangeResult.state;
                    mWifiInfo.setSupplicantState(state);
                    mWifiInfo.setNetworkId(WifiConfiguration.INVALID_NETWORK_ID);
                    mWifiInfo.setBSSID(stateChangeResult.BSSID);
                    mWifiInfo.setSSID(stateChangeResult.wifiSsid);

                    break;
                ///@}
                default:
                    loge("Error! unhandled message" + message);
                    break;
            }
            return HANDLED;
        }
    }

    class InitialState extends State {
        @Override
        public void enter() {
            if (DBG) loge(getName() + "\n");
            ///M: for clear mIsGBKMapping
            mWifiNative.clearGBKMapping();

            WifiNative.stopHal();
            mWifiNative.unloadDriver();

            if (mWifiP2pChannel == null) {
                mWifiP2pChannel = new AsyncChannel();
                mWifiP2pChannel.connect(mContext, getHandler(),
                    mWifiP2pServiceImpl.getP2pStateMachineMessenger());
            }

            if (mWifiApConfigChannel == null) {
                mWifiApConfigChannel = new AsyncChannel();
                mWifiApConfigStore = WifiApConfigStore.makeWifiApConfigStore(
                        mContext, getHandler());
                mWifiApConfigStore.loadApConfiguration();
                mWifiApConfigChannel.connectSync(mContext, getHandler(),
                        mWifiApConfigStore.getMessenger());
            }

            if (mWifiConfigStore.enableHalBasedPno.get()) {
                // make sure developer Settings are in sync with the config option
                mHalBasedPnoEnableInDevSettings = true;
            }
            ///M: ALPS01933408 Google Issue lastConnectAttempt should be cleared when wifi disabled
            lastConnectAttemptTimestamp = 0;
        }
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());
            switch (message.what) {
                case CMD_START_SUPPLICANT:
                    loge("CMD_START_SUPPLICANT");
                    if (mWifiNative.loadDriver()) {
                        try {
                            mNwService.wifiFirmwareReload(mInterfaceName, "STA");
                        } catch (Exception e) {
                            loge("Failed to reload STA firmware " + e);
                            ///M: add the following
                            Log.e(TAG, "Receive whole chip reset fail, disable wifi!");
                            mWifiManager.setWifiEnabled(false);
                            mWifiManager.setWifiApEnabled(null, false);
                            setWifiState(WIFI_STATE_DISABLED);
                            //throw exception to highlight wifi error
                            ExceptionLog exceptionLog = null;
                            try {
                                if (SystemProperties.get("ro.have_aee_feature").equals("1")) {
                                    exceptionLog = new ExceptionLog();
                                }
                                if (exceptionLog != null) {
                                    exceptionLog.systemreport(
                                        exceptionLog.AEE_EXCEPTION_JNI,
                                        "CRDISPATCH_KEY:WifiStateMachine",
                                        "fwreload fails",
                                        "/data/cursorleak/traces.txt");
                                }
                            } catch (Exception ee) {
                                // AEE disabled or failed to allocate AEE object,
                                //no need to show message
                            }
                            return HANDLED;

                            // Continue
                        }

                        try {
                            // A runtime crash can leave the interface up and
                            // IP addresses configured, and this affects
                            // connectivity when supplicant starts up.
                            // Ensure interface is down and we have no IP
                            // addresses before a supplicant start.
                            mNwService.setInterfaceDown(mInterfaceName);
                            mNwService.clearInterfaceAddresses(mInterfaceName);

                            // Set privacy extensions
                            mNwService.setInterfaceIpv6PrivacyExtensions(mInterfaceName, true);

                            // IPv6 is enabled only as long as access point is connected since:
                            // - IPv6 addresses and routes stick around after disconnection
                            // - kernel is unaware when connected and fails to start IPv6 negotiation
                            // - kernel can start autoconfiguration when 802.1x is not complete
                            mNwService.disableIpv6(mInterfaceName);
                        } catch (RemoteException re) {
                            loge("Unable to change interface settings: " + re);
                        } catch (IllegalStateException ie) {
                            loge("Unable to change interface settings: " + ie);
                        }

                       /* Stop a running supplicant after a runtime restart
                        * Avoids issues with drivers that do not handle interface down
                        * on a running supplicant properly.
                        */
                        mWifiMonitor.killSupplicant(mP2pSupported);

                        if (WifiNative.startHal() == false) {
                            /* starting HAL is optional */
                            loge("Failed to start HAL");
                        }

                        if (mWifiNative.startSupplicant(mP2pSupported)) {
                            setWifiState(WIFI_STATE_ENABLING);
                            if (DBG) loge("Supplicant start successful");
                            mWifiMonitor.startMonitoring();
                            transitionTo(mSupplicantStartingState);
                        } else {
                            loge("Failed to start supplicant!");
                            ///M: add the following
                            Log.e(TAG, "Receive whole chip reset fail, disable wifi!");
                            mWifiManager.setWifiEnabled(false);
                            mWifiManager.setWifiApEnabled(null, false);
                            setWifiState(WIFI_STATE_DISABLED);
                            //throw exception to highlight wifi error
                            ExceptionLog exceptionLog = null;
                            try {
                                if (SystemProperties.get("ro.have_aee_feature").equals("1")) {
                                    exceptionLog = new ExceptionLog();
                                }
                                if (exceptionLog != null) {
                                    exceptionLog.systemreport(
                                        exceptionLog.AEE_EXCEPTION_JNI,
                                        "CRDISPATCH_KEY:WifiStateMachine",
                                        "Failed to start supplicant!",
                                        "/data/cursorleak/traces.txt");
                                }
                            } catch (Exception ee) {
                                // AEE disabled or failed to allocate AEE object,
                                //no need to show message
                            }
                            return HANDLED;
                            ///end
                        }
                    } else {
                        loge("Failed to load driver");
                        ///M: add the following
                        Log.e(TAG, "Receive whole chip reset fail, disable wifi!");
                        mWifiManager.setWifiEnabled(false);
                        mWifiManager.setWifiApEnabled(null, false);
                        setWifiState(WIFI_STATE_DISABLED);
                        //throw exception to highlight wifi error
                        ExceptionLog exceptionLog = null;
                        try {
                            if (SystemProperties.get("ro.have_aee_feature").equals("1")) {
                                exceptionLog = new ExceptionLog();
                            }
                            if (exceptionLog != null) {
                                exceptionLog.systemreport(
                                    exceptionLog.AEE_EXCEPTION_JNI,
                                    "CRDISPATCH_KEY:WifiStateMachine",
                                    "loadDriver fails",
                                    "/data/cursorleak/traces.txt");
                            }
                        } catch (Exception ee) {
                            // AEE disabled or failed to allocate AEE object,
                            //no need to show message
                        }
                        ///end
                    }
                    break;
                case CMD_START_AP:
                    if (mWifiNative.loadDriver() == false) {
                        loge("Failed to load driver for softap");
                    } else {
                        if (enableSoftAp() == true) {
                            setWifiApState(WIFI_AP_STATE_ENABLING, 0);
                            transitionTo(mSoftApStartingState);
                        } else {
                            setWifiApState(WIFI_AP_STATE_FAILED,
                                    WifiManager.SAP_START_FAILURE_GENERAL);
                            transitionTo(mInitialState);
                        }
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class SupplicantStartingState extends State {
        @Override
        public void enter() {
            if (DBG) loge(getName() + "\n");
        }
        private void initializeWpsDetails() {
            String detail;
            detail = SystemProperties.get("ro.product.name", "");
            if (!mWifiNative.setDeviceName(detail)) {
                loge("Failed to set device name " +  detail);
            }
            detail = SystemProperties.get("ro.product.manufacturer", "");
            if (!mWifiNative.setManufacturer(detail)) {
                loge("Failed to set manufacturer " + detail);
            }
            detail = SystemProperties.get("ro.product.model", "");
            if (!mWifiNative.setModelName(detail)) {
                loge("Failed to set model name " + detail);
            }
            detail = SystemProperties.get("ro.product.model", "");
            if (!mWifiNative.setModelNumber(detail)) {
                loge("Failed to set model number " + detail);
            }
            detail = SystemProperties.get("ro.serialno", "");
            if (!mWifiNative.setSerialNumber(detail)) {
                loge("Failed to set serial number " + detail);
            }
            if (!mWifiNative.setConfigMethods("physical_display virtual_push_button")) {
                loge("Failed to set WPS config methods");
            }
            if (!mWifiNative.setDeviceType(mPrimaryDeviceType)) {
                loge("Failed to set primary device type " + mPrimaryDeviceType);
            }
        }

        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch(message.what) {
                case WifiMonitor.SUP_CONNECTION_EVENT:
                    if (DBG) loge("Supplicant connection established");
                    setWifiState(WIFI_STATE_ENABLED);
                    mSupplicantRestartCount = 0;
                    /* Reset the supplicant state to indicate the supplicant
                     * state is not known at this time */
                    mSupplicantStateTracker.sendMessage(CMD_RESET_SUPPLICANT_STATE);
                    /* Initialize data structures */
                    mLastBssid = null;
                    mLastNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
                    mLastSignalLevel = -1;

                    mWifiInfo.setMacAddress(mWifiNative.getMacAddress());
                    /* set frequency band of operation */
                    setFrequencyBand();
                    mWifiNative.enableSaveConfig();
                    mWifiConfigStore.loadAndEnableAllNetworks();
                    if (mWifiConfigStore.enableVerboseLogging.get() > 0) {
                        enableVerboseLogging(mWifiConfigStore.enableVerboseLogging.get());
                    }
                    initializeWpsDetails();
                    mConnectNetwork = false;
                    mLastExplicitNetworkId = INVALID_NETWORK_ID;
                    mOnlineStartTime = 0;
                    mUsingPppoe = false;

                    /// M: NFC Float II @{ ///
                    if (mMtkWpsp2pnfcSupport) {
                        mWaitingForEnrollee = false;
                        mWaitingForHrToken = false;
                        mEnrolleeUuid = null;
                        mEnrolleeBssid = null;
                        mErApUuid = null;
                        mErApPin = null;
                        mWpsErMethod = WpsInfo.INVALID;
                    }
                    ///@}

                    if (hasCustomizedAutoConnect()) {
                        mWifiNative.setBssExpireAge(IWifiFwkExt.BSS_EXPIRE_AGE);
                        mWifiNative.setBssExpireCount(IWifiFwkExt.BSS_EXPIRE_COUNT);
                        mDisconnectOperation = false;
                        mScanForWeakSignal = false;
                        mShowReselectDialog = false;
                        mIpConfigLost = false;
                        mLastCheckWeakSignalTime = 0;
                        if (!mWifiFwkExt.shouldAutoConnect()) {
                            disableAllNetworks(false);
                        }
                    }

                    ///M: ALPS02317999 Prevent supplicant auto connect EAP SIM/AKA @{
                    if (isAirplaneModeOn()) {
                        List<WifiConfiguration> networks = mWifiConfigStore.getConfiguredNetworks();
                        if (null != networks) {
                            for (WifiConfiguration network : networks) {
                                int value = network.enterpriseConfig.getEapMethod();
                                log("EAP value:" + value);
                                if (value == WifiEnterpriseConfig.Eap.SIM
                                        || value == WifiEnterpriseConfig.Eap.AKA
                                        || value == WifiEnterpriseConfig.Eap.AKA_PRIME) {
                                    mWifiConfigStore.disableNetwork(network.networkId,
                                            WifiConfiguration.DISABLED_UNKNOWN_REASON);
                                    ///M: ALPS01975084 airplane mode on,
                                    ///   should not auto connect EAP SIM
                                    WifiConfiguration eapSimConfig =
                                        mWifiConfigStore.getWifiConfiguration(network.networkId);
                                    eapSimConfig.setAutoJoinStatus(
                                            WifiConfiguration.
                                            AUTO_JOIN_DISABLED_EAP_SIM_AIRPLANE_MODE);
                                }
                            }
                        } else {
                            log("Check for EAP_SIM_AKA, networks is null!");
                        }
                        ///M: ALPS02319256 Disable EAP SIM/AKA AP if modem is not ready
                    } else if (!mIccState.equals(IccCardConstants.INTENT_VALUE_ICC_LOADED)) {
                        log("mIccState is not loaded, check EAP SIM/AKA networks");
                        List<WifiConfiguration> networks = mWifiConfigStore.getConfiguredNetworks();
                        if (null != networks) {
                            for (WifiConfiguration network : networks) {
                                int value = network.enterpriseConfig.getEapMethod();
                                log("EAP value:" + value);
                                if (value == WifiEnterpriseConfig.Eap.SIM
                                        || value == WifiEnterpriseConfig.Eap.AKA
                                        || value == WifiEnterpriseConfig.Eap.AKA_PRIME) {
                                    log("diable EAP SIM/AKA network let supplicant cannot "
                                            + "auto connect, netId: " + network.networkId);
                                    mWifiConfigStore.disableNetwork(network.networkId,
                                            WifiConfiguration.DISABLED_UNKNOWN_REASON);
                                    ///M: ALPS02319256 airplane mode off and wifi on
                                    //    modem is not ready, supplicant should not auto connect
                                    WifiConfiguration eapSimConfig
                                        = mWifiConfigStore.getWifiConfiguration(network.networkId);
                                    eapSimConfig.setAutoJoinStatus(
                                            WifiConfiguration.
                                            AUTO_JOIN_DISABLED_EAP_SIM_CARD_ABSENT);
                                }
                            }
                        } else {
                            log("Check for EAP_SIM_AKA, networks is null!");
                        }
                    }
                    ///@}

                    sendSupplicantConnectionChangedBroadcast(true);
                    transitionTo(mDriverStartedState);
                    break;
                case WifiMonitor.SUP_DISCONNECTION_EVENT:
                    if (++mSupplicantRestartCount <= SUPPLICANT_RESTART_TRIES) {
                        loge("Failed to setup control channel, restart supplicant");
                        mWifiMonitor.killSupplicant(mP2pSupported);
                        transitionTo(mInitialState);
                        sendMessageDelayed(CMD_START_SUPPLICANT, SUPPLICANT_RESTART_INTERVAL_MSECS);
                    } else {
                        loge("Failed " + mSupplicantRestartCount +
                                " times to start supplicant, unload driver");
                        mSupplicantRestartCount = 0;
                        setWifiState(WIFI_STATE_UNKNOWN);
                        transitionTo(mInitialState);
                    }
                    break;
                case CMD_START_SUPPLICANT:
                case CMD_STOP_SUPPLICANT:
                case CMD_START_AP:
                case CMD_STOP_AP:
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_SET_OPERATIONAL_MODE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                    messageHandlingStatus = MESSAGE_HANDLING_STATUS_DEFERRED;
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class SupplicantStartedState extends State {
        @Override
        public void enter() {
            if (DBG) loge(getName() + "\n");
            /* Wifi is available as long as we have a connection to supplicant */
            mNetworkInfo.setIsAvailable(true);
            if (mNetworkAgent != null) mNetworkAgent.sendNetworkInfo(mNetworkInfo);

            int defaultInterval = mContext.getResources().getInteger(
                    R.integer.config_wifi_supplicant_scan_interval);

            if (hasCustomizedAutoConnect()) {
                mSupplicantScanIntervalMs = Settings.Global.getLong(mContext.getContentResolver(),
                        Settings.Global.WIFI_SUPPLICANT_SCAN_INTERVAL_MS,
                        mScreenOn ? defaultInterval : mContext.getResources().getInteger(
                                R.integer.config_wifi_framework_scan_interval));
            } else {
                mSupplicantScanIntervalMs = Settings.Global.getLong(mContext.getContentResolver(),
                        Settings.Global.WIFI_SUPPLICANT_SCAN_INTERVAL_MS,
                        defaultInterval);
            }
            updateCountryCode(mCountryCode);

            mWifiNative.setScanInterval((int)mSupplicantScanIntervalMs / 1000);
            mWifiNative.setExternalSim(true);

            /* turn on use of DFS channels */
            WifiNative.setDfsFlag(true);

            /* set country code */
            setCountryCode();

            setRandomMacOui();
            ///M: ALPS01791312 if auto join disable,
            ///we should enable supplicant auto connect ,or there will be no auto connect
            if (mWifiConfigStore.enableAutoJoinWhenAssociated.get()) {
                mWifiNative.enableAutoConnect(false);
            } else {
                mWifiNative.enableAutoConnect(true);
            }

            ///M: if have special network selection and wifi is connected, don't try to associate.
            if (mWifiFwkExt != null && mWifiFwkExt.hasNetworkSelection() != 0) {
                //don't let supplicant auto connect when wifi initial
                mWifiNative.disconnect();
            }
        }

        @Override
        public boolean processMessage(Message message) {
            Intent intent;
            logStateAndMessage(message, getClass().getSimpleName());

            switch(message.what) {
                case CMD_STOP_SUPPLICANT:   /* Supplicant stopped by user */
                    if (mP2pSupported) {
                        transitionTo(mWaitForP2pDisableState);
                    } else {
                        transitionTo(mSupplicantStoppingState);
                    }
                    break;
                case WifiMonitor.SUP_DISCONNECTION_EVENT:  /* Supplicant connection lost */
                    loge("Connection lost, restart supplicant");
                    handleSupplicantConnectionLoss(true);
                    handleNetworkDisconnect();
                    mSupplicantStateTracker.sendMessage(CMD_RESET_SUPPLICANT_STATE);
                    if (mP2pSupported) {
                        transitionTo(mWaitForP2pDisableState);
                    } else {
                        transitionTo(mInitialState);
                    }
                    sendMessageDelayed(CMD_START_SUPPLICANT, SUPPLICANT_RESTART_INTERVAL_MSECS);
                    break;
                case WifiMonitor.SCAN_RESULTS_EVENT:
                case WifiMonitor.SCAN_FAILED_EVENT:
                    maybeRegisterNetworkFactory(); // Make sure our NetworkFactory is registered
                    closeRadioScanStats();
                    noteScanEnd();
                    setScanResults();
                    if (hasCustomizedAutoConnect()) {
                        mShowReselectDialog = false;
                        Log.d(TAG, "SCAN_RESULTS_EVENT, mScanForWeakSignal:" + mScanForWeakSignal);
                        if (mScanForWeakSignal) {
                            showReselectionDialog();
                        }
                        mDisconnectNetworkId = INVALID_NETWORK_ID;
                    }
                    loge("mIsFullScanOngoing: " + mIsFullScanOngoing
                            + ", mSendScanResultsBroadcast: " + mSendScanResultsBroadcast);
                    ///M: ALPS02028415 the last two times scan results should be broadcasted @{
                    if (mIsFullScanOngoing || mSendScanResultsBroadcast || mWifiOnScanCount < 2) {
                        loge("mWifiOnScanCount: " + mWifiOnScanCount);
                        /* Just updated results from full scan, let apps know about this */
                        boolean scanSucceeded = message.what == WifiMonitor.SCAN_RESULTS_EVENT;
                        sendScanResultsAvailableBroadcast(scanSucceeded);
                    }
                    mWifiOnScanCount ++;
                    ///@}
                    mSendScanResultsBroadcast = false;
                    mIsScanOngoing = false;
                    mIsFullScanOngoing = false;
                    if (mBufferedScanMsg.size() > 0)
                        sendMessage(mBufferedScanMsg.remove());
                    break;
                case CMD_PING_SUPPLICANT:
                    boolean ok = mWifiNative.ping();
                    replyToMessage(message, message.what, ok ? SUCCESS : FAILURE);
                    break;
                case CMD_GET_CAPABILITY_FREQ:
                    String freqs = mWifiNative.getFreqCapability();
                    replyToMessage(message, message.what, freqs);
                    break;
                case CMD_START_AP:
                    /* Cannot start soft AP while in client mode */
                    loge("Failed to start soft AP with a running supplicant");
                    setWifiApState(WIFI_AP_STATE_FAILED, WifiManager.SAP_START_FAILURE_GENERAL);
                    break;
                case CMD_SET_OPERATIONAL_MODE:
                    mOperationalMode = message.arg1;
                    mWifiConfigStore.
                            setLastSelectedConfiguration(WifiConfiguration.INVALID_NETWORK_ID);
                    break;
                case CMD_TARGET_BSSID:
                    // Trying to associate to this BSSID
                    if (message.obj != null) {
                        mTargetRoamBSSID = (String) message.obj;
                    }
                    break;
                case CMD_GET_LINK_LAYER_STATS:
                    WifiLinkLayerStats stats = getWifiLinkLayerStats(DBG);
                    if (stats == null) {
                        // When firmware doesnt support link layer stats, return an empty object
                        stats = new WifiLinkLayerStats();
                    }
                    replyToMessage(message, message.what, stats);
                    break;
                case CMD_SET_COUNTRY_CODE:
                    String country = (String) message.obj;

                    final boolean persist = (message.arg2 == 1);
                    final int sequence = message.arg1;

                    if (sequence != mCountryCodeSequence.get()) {
                        if (DBG) log("set country code ignored due to sequnce num");
                        break;
                    }
                    if (DBG) log("set country code " + country);
                    country = country.toUpperCase(Locale.ROOT);

                    if (mDriverSetCountryCode == null || !mDriverSetCountryCode.equals(country)) {
                        if (mWifiNative.setCountryCode(country)) {
                            mDriverSetCountryCode = country;
                        } else {
                            loge("Failed to set country code " + country);
                        }
                    }

                    mWifiP2pChannel.sendMessage(WifiP2pServiceImpl.SET_COUNTRY_CODE, country);
                    break;
                case M_CMD_UPDATE_SETTINGS:
                    updateAutoConnectSettings();
                    break;
                case M_CMD_UPDATE_SCAN_INTERVAL:
                    mSupplicantScanIntervalMs = Settings.Global.getLong(
                            mContext.getContentResolver(),
                            Settings.Global.WIFI_SUPPLICANT_SCAN_INTERVAL_MS,
                            mScreenOn ? mContext.getResources().getInteger(
                                R.integer.config_wifi_supplicant_scan_interval)
                                : mContext.getResources().getInteger(
                                    R.integer.config_wifi_framework_scan_interval));
                    mWifiNative.setScanInterval((int) mSupplicantScanIntervalMs / 1000);
                    break;
                case M_CMD_DO_CTIA_TEST_ON:
                    ok = mWifiNative.doCtiaTestOn((String) message.obj);
                    replyToMessage(message, message.what, ok ? SUCCESS : FAILURE);
                    break;
                case M_CMD_DO_CTIA_TEST_OFF:
                    ok = mWifiNative.doCtiaTestOff((String) message.obj);
                    replyToMessage(message, message.what, ok ? SUCCESS : FAILURE);
                    break;
                case M_CMD_DO_CTIA_TEST_RATE:
                    ok = mWifiNative.doCtiaTestRate(message.arg1);
                    replyToMessage(message, message.what, ok ? SUCCESS : FAILURE);
                    break;
                case M_CMD_GET_CONNECTING_NETWORK_ID:
                    int networkId = getConnectingNetworkId();
                    replyToMessage(message, message.what, networkId);
                    break;
                case M_CMD_UPDATE_RSSI:
                    fetchRssiNative();
                    replyToMessage(message, message.what);
                    break;
                case M_CMD_UPDATE_COUNTRY_CODE:
                    updateCountryCode((String) message.obj);
                    mSetCountryCode = (String) message.obj;
                    break;
                ///M: whole chip reset fail @{
                case WifiMonitor.WHOLE_CHIP_RESET_FAIL_EVENT:
                    Log.e(TAG, "Receive whole chip reset fail, disable wifi!");
                    mWifiManager.setWifiEnabled(false);
                    mWifiManager.setWifiApEnabled(null, false);
                    break;
                ///@}
                ///M:@{
                case M_CMD_FLUSH_BSS:
                    mWifiNative.bssFlush();
                    break;
                case M_CMD_GET_TEST_ENV:
                    String env = mWifiNative.getTestEnv(message.arg1);
                    replyToMessage(message, message.what, env);
                    break;
                 ///M:@}
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        @Override
        public void exit() {
            mNetworkInfo.setIsAvailable(false);
            /// M: NFC Float II @{ //
            if (mMtkWpsp2pnfcSupport) {
                mWaitingForHrToken = false;
            }
            /// @}
            if (mNetworkAgent != null) mNetworkAgent.sendNetworkInfo(mNetworkInfo);
        }
    }

    class SupplicantStoppingState extends State {
        @Override
        public void enter() {
            if (DBG) loge(getName() + "\n");
            /* Send any reset commands to supplicant before shutting it down */
            handleNetworkDisconnect();
            if (mDhcpStateMachine != null) {
                mDhcpStateMachine.doQuit();
            }

            if (mMtkDhcpv6cWifi) {
                if (mDhcpV6StateMachine != null) {
                    mDhcpV6StateMachine.doQuit();
                }
            }

            String suppState = System.getProperty("init.svc.wpa_supplicant");
            if (suppState == null) suppState = "unknown";
            String p2pSuppState = System.getProperty("init.svc.p2p_supplicant");
            if (p2pSuppState == null) p2pSuppState = "unknown";

            logd("SupplicantStoppingState: stopSupplicant "
                    + " init.svc.wpa_supplicant=" + suppState
                    + " init.svc.p2p_supplicant=" + p2pSuppState);
            mWifiMonitor.stopSupplicant();

            /* Send ourselves a delayed message to indicate failure after a wait time */
            sendMessageDelayed(obtainMessage(CMD_STOP_SUPPLICANT_FAILED,
                    ++mSupplicantStopFailureToken, 0), SUPPLICANT_RESTART_INTERVAL_MSECS);
            setWifiState(WIFI_STATE_DISABLING);
            mSupplicantStateTracker.sendMessage(CMD_RESET_SUPPLICANT_STATE);
        }
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch(message.what) {
                case WifiMonitor.SUP_CONNECTION_EVENT:
                    loge("Supplicant connection received while stopping");
                    break;
                case WifiMonitor.SUP_DISCONNECTION_EVENT:
                    if (DBG) log("Supplicant connection lost");
                    handleSupplicantConnectionLoss(false);
                    transitionTo(mInitialState);
                    break;
                case CMD_STOP_SUPPLICANT_FAILED:
                    if (message.arg1 == mSupplicantStopFailureToken) {
                        loge("Timed out on a supplicant stop, kill and proceed");
                        handleSupplicantConnectionLoss(true);
                        ///M: ALPS01968938 solve WifiMonitor infinite loop issue
                        mWifiMonitor.quitMonitoring();
                        transitionTo(mInitialState);
                    }
                    break;
                case CMD_START_SUPPLICANT:
                case CMD_STOP_SUPPLICANT:
                case CMD_START_AP:
                case CMD_STOP_AP:
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_SET_OPERATIONAL_MODE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class DriverStartingState extends State {
        private int mTries;
        @Override
        public void enter() {
            if (DBG) loge(getName() + "\n");
            mTries = 1;
            /* Send ourselves a delayed message to start driver a second time */
            sendMessageDelayed(obtainMessage(CMD_DRIVER_START_TIMED_OUT,
                        ++mDriverStartToken, 0), DRIVER_START_TIME_OUT_MSECS);
        }
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch(message.what) {
               case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                    SupplicantState state = handleSupplicantStateChange(message);
                    /* If suplicant is exiting out of INTERFACE_DISABLED state into
                     * a state that indicates driver has started, it is ready to
                     * receive driver commands
                     */
                    if (SupplicantState.isDriverActive(state)) {
                        transitionTo(mDriverStartedState);
                    }
                    break;
                case CMD_DRIVER_START_TIMED_OUT:
                    if (message.arg1 == mDriverStartToken) {
                        if (mTries >= 2) {
                            loge("Failed to start driver after " + mTries);
                            transitionTo(mDriverStoppedState);
                        } else {
                            loge("Driver start failed, retrying");
                            mWakeLock.acquire();
                            mWifiNative.startDriver();
                            mWakeLock.release();

                            ++mTries;
                            /* Send ourselves a delayed message to start driver again */
                            sendMessageDelayed(obtainMessage(CMD_DRIVER_START_TIMED_OUT,
                                        ++mDriverStartToken, 0), DRIVER_START_TIME_OUT_MSECS);
                        }
                    }
                    break;
                    /* Queue driver commands & connection events */
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case WifiMonitor.NETWORK_CONNECTION_EVENT:
                case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                case WifiMonitor.AUTHENTICATION_FAILURE_EVENT:
                case WifiMonitor.ASSOCIATION_REJECTION_EVENT:
                case WifiMonitor.WPS_OVERLAP_EVENT:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                case CMD_START_SCAN:
                case CMD_DISCONNECT:
                case CMD_REASSOCIATE:
                case CMD_RECONNECT:
                    messageHandlingStatus = MESSAGE_HANDLING_STATUS_DEFERRED;
                    deferMessage(message);
                    break;
                case WifiMonitor.SCAN_RESULTS_EVENT:
                case WifiMonitor.SCAN_FAILED_EVENT:
                    // Loose scan results obtained in Driver Starting state, they can only confuse
                    // the state machine
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class DriverStartedState extends State {
        @Override
        public void enter() {

            if (DBG) loge(getName() + "\n");
            if (PDBG) {
                logd("DriverStartedState enter");
            }

            mWifiLogger.startLogging(mVerboseLoggingLevel > 0);
            mIsRunning = true;
            mInDelayedStop = false;
            mDelayedStopCounter++;
            updateBatteryWorkSource(null);
            /**
             * Enable bluetooth coexistence scan mode when bluetooth connection is active.
             * When this mode is on, some of the low-level scan parameters used by the
             * driver are changed to reduce interference with bluetooth
             */
            mWifiNative.setBluetoothCoexistenceScanMode(mBluetoothConnectionActive);
            /* initialize network state */
            setNetworkDetailedState(DetailedState.DISCONNECTED);

            /* Remove any filtering on Multicast v6 at start */
            mWifiNative.stopFilteringMulticastV6Packets();

            /* Reset Multicast v4 filtering state */
            if (mFilteringMulticastV4Packets.get()) {
                mWifiNative.startFilteringMulticastV4Packets();
            } else {
                mWifiNative.stopFilteringMulticastV4Packets();
            }

            mDhcpActive = false;

            if (mOperationalMode != CONNECT_MODE) {
                mWifiNative.disconnect();
                mWifiConfigStore.disableAllNetworks();
                if (mOperationalMode == SCAN_ONLY_WITH_WIFI_OFF_MODE) {
                    setWifiState(WIFI_STATE_DISABLED);
                }
                transitionTo(mScanModeState);
            } else {

                // Status pulls in the current supplicant state and network connection state
                // events over the monitor connection. This helps framework sync up with
                // current supplicant state
                // TODO: actually check th supplicant status string and make sure the supplicant
                // is in disconnecte4d state.
                String status = mWifiNative.status();
                log("status=" + status);
                mIsSupplicantFirstScan = true;

                ///M: ALPS02028415 reset scan count
                mWifiOnScanCount = 0;

                // Transitioning to Disconnected state will trigger a scan and subsequently AutoJoin
                transitionTo(mDisconnectedState);
                ///M: M eac may be google mistake
                //transitionTo(mDisconnectedState);
            }

            // We may have missed screen update at boot
            if (mScreenBroadcastReceived.get() == false) {
                PowerManager powerManager = (PowerManager)mContext.getSystemService(
                        Context.POWER_SERVICE);
                handleScreenStateChanged(powerManager.isScreenOn());
            } else {
                // Set the right suspend mode settings
                mWifiNative.setSuspendOptimizations(mSuspendOptNeedsDisabled == 0
                        && mUserWantsSuspendOpt.get());
            }
            mWifiNative.setPowerSave(true);

            if (mP2pSupported) {
                if (mOperationalMode == CONNECT_MODE) {
                    mWifiP2pChannel.sendMessage(WifiStateMachine.CMD_ENABLE_P2P);
                } else {
                    // P2P statemachine starts in disabled state, and is not enabled until
                    // CMD_ENABLE_P2P is sent from here; so, nothing needs to be done to
                    // keep it disabled.
                }
            }

            final Intent intent = new Intent(WifiManager.WIFI_SCAN_AVAILABLE);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            intent.putExtra(WifiManager.EXTRA_SCAN_AVAILABLE, WIFI_STATE_ENABLED);
            mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);

            mHalFeatureSet = WifiNative.getSupportedFeatureSet();
            if ((mHalFeatureSet & WifiManager.WIFI_FEATURE_HAL_EPNO)
                    == WifiManager.WIFI_FEATURE_HAL_EPNO) {
                mHalBasedPnoDriverSupported = true;
            }

            // Enable link layer stats gathering
            mWifiNative.setWifiLinkLayerStats("wlan0", 1);

            if (PDBG) {
                logd("Driverstarted State enter done, epno=" + mHalBasedPnoDriverSupported
                     + " feature=" + mHalFeatureSet);
            }
        }

        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch(message.what) {
                ///M: group scan @[
                case M_CMD_GROUP_SCAN:
                    if (mGroupBufferedScanMsgCount > 0) {
                        mGroupBufferedScanMsgCount = 0;
                        Bundle bundle = new Bundle();
                        bundle.putParcelable(CUSTOMIZED_SCAN_SETTING, null);
                        bundle.putParcelable(CUSTOMIZED_SCAN_WORKSOURCE, null);
                        Message msg = obtainMessage(CMD_START_SCAN, GROUP_SCAN_SOURCE,
                            mGroupScanCounter.incrementAndGet(), bundle);
                        handleScanRequest(WifiNative.SCAN_WITHOUT_CONNECTION_SETUP, msg);
                    }
                    break;
                //@}
                case CMD_START_SCAN:
                    handleScanRequest(WifiNative.SCAN_WITHOUT_CONNECTION_SETUP, message);
                    break;
                case CMD_SET_FREQUENCY_BAND:
                    int band =  message.arg1;
                    if (DBG) log("set frequency band " + band);
                    if (mWifiNative.setBand(band)) {

                        if (PDBG)  logd("did set frequency band " + band);

                        mFrequencyBand.set(band);
                        // Flush old data - like scan results
                        mWifiNative.bssFlush();
                        // Fetch the latest scan results when frequency band is set
//                        startScanNative(WifiNative.SCAN_WITHOUT_CONNECTION_SETUP,
//                                WIFI_INDIDE_SOURCE);

                        if (PDBG)  logd("done set frequency band " + band);

                    } else {
                        loge("Failed to set frequency band " + band);
                    }
                    break;
                case CMD_BLUETOOTH_ADAPTER_STATE_CHANGE:
                    mBluetoothConnectionActive = (message.arg1 !=
                            BluetoothAdapter.STATE_DISCONNECTED);
                    mWifiNative.setBluetoothCoexistenceScanMode(mBluetoothConnectionActive);
                    break;
                case CMD_STOP_DRIVER:
                    int mode = message.arg1;

                    /* Already doing a delayed stop */
                    if (mInDelayedStop) {
                        if (DBG) log("Already in delayed stop");
                        break;
                    }
                    /* disconnect right now, but leave the driver running for a bit */
                    mWifiConfigStore.disableAllNetworks();
                    if (hasCustomizedAutoConnect()) {
                        mDisconnectOperation = true;
                    }

                    mInDelayedStop = true;
                    mDelayedStopCounter++;
                    if (DBG) log("Delayed stop message " + mDelayedStopCounter);

                    /* send regular delayed shut down */
                    Intent driverStopIntent = new Intent(ACTION_DELAYED_DRIVER_STOP, null);
                    driverStopIntent.setPackage(this.getClass().getPackage().getName());
                    driverStopIntent.putExtra(DELAYED_STOP_COUNTER, mDelayedStopCounter);
                    mDriverStopIntent = PendingIntent.getBroadcast(mContext,
                            DRIVER_STOP_REQUEST, driverStopIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT);

                    mAlarmManager.setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis()
                            + mDriverStopDelayMs, mDriverStopIntent);
                    break;
                case CMD_START_DRIVER:
                    if (mInDelayedStop) {
                        mInDelayedStop = false;
                        mDelayedStopCounter++;
                        mAlarmManager.cancel(mDriverStopIntent);
                        if (DBG) log("Delayed stop ignored due to start");
                        if (mOperationalMode == CONNECT_MODE) {
                            mWifiConfigStore.enableAllNetworks();
                        }
                    }
                    break;
                case CMD_DELAYED_STOP_DRIVER:
                    if (DBG) log("delayed stop " + message.arg1 + " " + mDelayedStopCounter);
                    if (message.arg1 != mDelayedStopCounter) break;
                    if (getCurrentState() != mDisconnectedState) {
                        mWifiNative.disconnect();
                        handleNetworkDisconnect();
                    }
                    mWakeLock.acquire();
                    mWifiNative.stopDriver();
                    mWakeLock.release();
                    if (mP2pSupported) {
                        transitionTo(mWaitForP2pDisableState);
                    } else {
                        transitionTo(mDriverStoppingState);
                    }
                    break;
                case CMD_START_PACKET_FILTERING:
                    if (message.arg1 == MULTICAST_V6) {
                        mWifiNative.startFilteringMulticastV6Packets();
                    } else if (message.arg1 == MULTICAST_V4) {
                        mWifiNative.startFilteringMulticastV4Packets();
                    } else {
                        loge("Illegal arugments to CMD_START_PACKET_FILTERING");
                    }
                    break;
                case CMD_STOP_PACKET_FILTERING:
                    if (message.arg1 == MULTICAST_V6) {
                        mWifiNative.stopFilteringMulticastV6Packets();
                    } else if (message.arg1 == MULTICAST_V4) {
                        mWifiNative.stopFilteringMulticastV4Packets();
                    } else {
                        loge("Illegal arugments to CMD_STOP_PACKET_FILTERING");
                    }
                    break;
                case CMD_SET_SUSPEND_OPT_ENABLED:
                    if (message.arg1 == 1) {
                        setSuspendOptimizationsNative(SUSPEND_DUE_TO_SCREEN, true);
                        mSuspendWakeLock.release();
                    } else {
                        setSuspendOptimizationsNative(SUSPEND_DUE_TO_SCREEN, false);
                    }
                    break;
                case CMD_SET_HIGH_PERF_MODE:
                    if (message.arg1 == 1) {
                        setSuspendOptimizationsNative(SUSPEND_DUE_TO_HIGH_PERF, false);
                    } else {
                        setSuspendOptimizationsNative(SUSPEND_DUE_TO_HIGH_PERF, true);
                    }
                    break;
                case M_CMD_SET_POWER_SAVING_MODE:
                    mWifiNative.setPowerSave(message.arg1 == 1);
                    break;
                case CMD_ENABLE_TDLS:
                    if (message.obj != null) {
                        String remoteAddress = (String) message.obj;
                        boolean enable = (message.arg1 == 1);
                        mWifiNative.startTdls(remoteAddress, enable);
                    }
                    break;
                case WifiMonitor.ANQP_DONE_EVENT:
                    mWifiConfigStore.notifyANQPDone((Long) message.obj, message.arg1 != 0);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
        @Override
        public void exit() {
            if (DBG) loge(getName() + " exit\n");

            mWifiLogger.stopLogging();

            mIsRunning = false;
            updateBatteryWorkSource(null);
            mScanResults = new ArrayList<>();

            final Intent intent = new Intent(WifiManager.WIFI_SCAN_AVAILABLE);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            intent.putExtra(WifiManager.EXTRA_SCAN_AVAILABLE, WIFI_STATE_DISABLED);
            mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
            noteScanEnd(); // wrap up any pending request.
            mBufferedScanMsg.clear();
        }
    }

    class WaitForP2pDisableState extends State {
        private State mTransitionToState;
        @Override
        public void enter() {
            if (DBG) loge(getName() + "\n");
            switch (getCurrentMessage().what) {
                case WifiMonitor.SUP_DISCONNECTION_EVENT:
                    mTransitionToState = mInitialState;
                    break;
                case CMD_DELAYED_STOP_DRIVER:
                    mTransitionToState = mDriverStoppingState;
                    break;
                case CMD_STOP_SUPPLICANT:
                    mTransitionToState = mSupplicantStoppingState;
                    break;
                ///M: ALPS01872204 Scan mode on. When disabling Wifi, should come there @{
        case CMD_SET_OPERATIONAL_MODE:
            mTransitionToState = mScanModeState;
            break;
                ///@}
                default:
                    mTransitionToState = mDriverStoppingState;
                    break;
            }
            mWifiP2pChannel.sendMessage(WifiStateMachine.CMD_DISABLE_P2P_REQ);
        }
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch(message.what) {
                case WifiStateMachine.CMD_DISABLE_P2P_RSP:
            ///M: ALPS01872204 Scan mode on. When disabling Wifi, should close p2p first @{
            if (mTransitionToState == mScanModeState) {
                log("state == ScanModeState, setWifiState(WIFI_STATE_DISABLED)");
                setWifiState(WIFI_STATE_DISABLED);
            }
                    ///@}
                    transitionTo(mTransitionToState);
                    break;
                /* Defer wifi start/shut and driver commands */
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                case CMD_START_SUPPLICANT:
                case CMD_STOP_SUPPLICANT:
                case CMD_START_AP:
                case CMD_STOP_AP:
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_SET_OPERATIONAL_MODE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                case CMD_START_SCAN:
                case CMD_DISCONNECT:
                case CMD_REASSOCIATE:
                case CMD_RECONNECT:
                    messageHandlingStatus = MESSAGE_HANDLING_STATUS_DEFERRED;
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class DriverStoppingState extends State {
        @Override
        public void enter() {
            if (DBG) loge(getName() + "\n");
        }
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch(message.what) {
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                    SupplicantState state = handleSupplicantStateChange(message);
                    if (state == SupplicantState.INTERFACE_DISABLED) {
                        transitionTo(mDriverStoppedState);
                    }
                    break;
                    /* Queue driver commands */
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                case CMD_START_SCAN:
                case CMD_DISCONNECT:
                case CMD_REASSOCIATE:
                case CMD_RECONNECT:
                    messageHandlingStatus = MESSAGE_HANDLING_STATUS_DEFERRED;
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class DriverStoppedState extends State {
        @Override
        public void enter() {
            if (DBG) loge(getName() + "\n");
        }
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());
            switch (message.what) {
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                    StateChangeResult stateChangeResult = (StateChangeResult) message.obj;
                    SupplicantState state = stateChangeResult.state;
                    // A WEXT bug means that we can be back to driver started state
                    // unexpectedly
                    if (SupplicantState.isDriverActive(state)) {
                        transitionTo(mDriverStartedState);
                    }
                    break;
                case CMD_START_DRIVER:
                    mWakeLock.acquire();
                    mWifiNative.startDriver();
                    mWakeLock.release();
                    transitionTo(mDriverStartingState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class ScanModeState extends State {
        private int mLastOperationMode;
        @Override
        public void enter() {
            if (DBG) loge(getName() + "\n");
            mLastOperationMode = mOperationalMode;

            ///M: ALPS02125847 Google Issue lastConnectAttempt should be cleared when wifi disabled
            lastConnectAttemptTimestamp = 0;

            ///M: ALPS02341431 Scan always on and LTE is on, power consumption issue
            mWifiNative.setAlwaysScanState(1);
        }
        @Override
        public void exit() {
            log(getName() + " exit\n");
            ///M: ALPS02341431 Scan always on and LTE is on, power consumption issue
            mWifiNative.setAlwaysScanState(0);
        }
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch(message.what) {
                case CMD_SET_OPERATIONAL_MODE:
                    if (message.arg1 == CONNECT_MODE) {

                        if (mLastOperationMode == SCAN_ONLY_WITH_WIFI_OFF_MODE) {
                            // setWifiState(WIFI_STATE_ENABLED);
                            // Load and re-enable networks when going back to enabled state
                            // This is essential for networks to show up after restore
                            mWifiConfigStore.loadAndEnableAllNetworks();
                            mWifiP2pChannel.sendMessage(CMD_ENABLE_P2P);
                        } else {
                            mWifiConfigStore.enableAllNetworks();
                        }
                        ///M: @{
                        if (isTemporarilyDontReconnectWifi() == true) {
                            log("stay disconnect because hotknot is on ..");
                            mWifiNative.disconnect();
                        } else {
                        ///@}
                            // Try autojoining with recent network already present in the cache
                            // If none are found then trigger a scan which will trigger autojoin
                            // upon reception of scan results event
                            if (!mWifiAutoJoinController.attemptAutoJoin()) {
                                startScan(ENABLE_WIFI, 0, null, null);
                            }

                            // Loose last selection choice since user toggled WiFi
                            mWifiConfigStore.
                                    setLastSelectedConfiguration(WifiConfiguration.INVALID_NETWORK_ID);

                        }
                        mOperationalMode = CONNECT_MODE;
                        ///M: ALPS01872204 Wifi enabled should be late @{
                        if (mLastOperationMode == SCAN_ONLY_WITH_WIFI_OFF_MODE) {
                            setWifiState(WIFI_STATE_ENABLED);
                        }
                        ///@}

                        ///M: ALPS02028415 reset scan count
                        mWifiOnScanCount = 0;

                        transitionTo(mDisconnectedState);
                    } else {
                         //M: if change to scan only with wifi off, broadcast wifi disable state
                        if (message.arg1 == SCAN_ONLY_WITH_WIFI_OFF_MODE) {
                            mLastOperationMode = SCAN_ONLY_WITH_WIFI_OFF_MODE;
                            setWifiState(WIFI_STATE_DISABLED);
                        }
                        // Nothing to do
                        return HANDLED;
                    }
                    break;
                // Handle scan. All the connection related commands are
                // handled only in ConnectModeState
                case CMD_START_SCAN:
                    handleScanRequest(WifiNative.SCAN_WITHOUT_CONNECTION_SETUP, message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }


    static public String smToString(Message message) {
        return smToString(message.what);
    }

    static public String smToString(int what) {
        String s = "unknown";
        switch (what) {
            case WifiMonitor.DRIVER_HUNG_EVENT:
                s = "DRIVER_HUNG_EVENT";
                break;
            case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                s = "AsyncChannel.CMD_CHANNEL_HALF_CONNECTED";
                break;
            case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                s = "AsyncChannel.CMD_CHANNEL_DISCONNECTED";
                break;
            case CMD_SET_FREQUENCY_BAND:
                s = "CMD_SET_FREQUENCY_BAND";
                break;
            case CMD_DELAYED_NETWORK_DISCONNECT:
                s = "CMD_DELAYED_NETWORK_DISCONNECT";
                break;
            case CMD_TEST_NETWORK_DISCONNECT:
                s = "CMD_TEST_NETWORK_DISCONNECT";
                break;
            case CMD_OBTAINING_IP_ADDRESS_WATCHDOG_TIMER:
                s = "CMD_OBTAINING_IP_ADDRESS_WATCHDOG_TIMER";
                break;
            case CMD_DISABLE_EPHEMERAL_NETWORK:
                s = "CMD_DISABLE_EPHEMERAL_NETWORK";
                break;
            case CMD_START_DRIVER:
                s = "CMD_START_DRIVER";
                break;
            case CMD_STOP_DRIVER:
                s = "CMD_STOP_DRIVER";
                break;
            case CMD_STOP_SUPPLICANT:
                s = "CMD_STOP_SUPPLICANT";
                break;
            case CMD_STOP_SUPPLICANT_FAILED:
                s = "CMD_STOP_SUPPLICANT_FAILED";
                break;
            case CMD_START_SUPPLICANT:
                s = "CMD_START_SUPPLICANT";
                break;
            case CMD_REQUEST_AP_CONFIG:
                s = "CMD_REQUEST_AP_CONFIG";
                break;
            case CMD_RESPONSE_AP_CONFIG:
                s = "CMD_RESPONSE_AP_CONFIG";
                break;
            case CMD_TETHER_STATE_CHANGE:
                s = "CMD_TETHER_STATE_CHANGE";
                break;
            case CMD_TETHER_NOTIFICATION_TIMED_OUT:
                s = "CMD_TETHER_NOTIFICATION_TIMED_OUT";
                break;
            case CMD_BLUETOOTH_ADAPTER_STATE_CHANGE:
                s = "CMD_BLUETOOTH_ADAPTER_STATE_CHANGE";
                break;
            case CMD_ADD_OR_UPDATE_NETWORK:
                s = "CMD_ADD_OR_UPDATE_NETWORK";
                break;
            case CMD_REMOVE_NETWORK:
                s = "CMD_REMOVE_NETWORK";
                break;
            case CMD_ENABLE_NETWORK:
                s = "CMD_ENABLE_NETWORK";
                break;
            case CMD_ENABLE_ALL_NETWORKS:
                s = "CMD_ENABLE_ALL_NETWORKS";
                break;
            case CMD_AUTO_CONNECT:
                s = "CMD_AUTO_CONNECT";
                break;
            case CMD_AUTO_ROAM:
                s = "CMD_AUTO_ROAM";
                break;
            case CMD_AUTO_SAVE_NETWORK:
                s = "CMD_AUTO_SAVE_NETWORK";
                break;
            case CMD_BOOT_COMPLETED:
                s = "CMD_BOOT_COMPLETED";
                break;
            case DhcpStateMachine.CMD_START_DHCP:
                s = "CMD_START_DHCP";
                break;
            case DhcpStateMachine.CMD_STOP_DHCP:
                s = "CMD_STOP_DHCP";
                break;
            case DhcpStateMachine.CMD_RENEW_DHCP:
                s = "CMD_RENEW_DHCP";
                break;
            case DhcpStateMachine.CMD_PRE_DHCP_ACTION:
                s = "CMD_PRE_DHCP_ACTION";
                break;
            case DhcpStateMachine.CMD_POST_DHCP_ACTION:
                s = "CMD_POST_DHCP_ACTION";
                break;
            case DhcpStateMachine.CMD_PRE_DHCP_ACTION_COMPLETE:
                s = "CMD_PRE_DHCP_ACTION_COMPLETE";
                break;
            case DhcpStateMachine.CMD_ON_QUIT:
                s = "CMD_ON_QUIT";
                break;
            case WifiP2pServiceImpl.DISCONNECT_WIFI_REQUEST:
                s = "WifiP2pServiceImpl.DISCONNECT_WIFI_REQUEST";
                break;
            case WifiManager.DISABLE_NETWORK:
                s = "WifiManager.DISABLE_NETWORK";
                break;
            case CMD_BLACKLIST_NETWORK:
                s = "CMD_BLACKLIST_NETWORK";
                break;
            case CMD_CLEAR_BLACKLIST:
                s = "CMD_CLEAR_BLACKLIST";
                break;
            case CMD_SAVE_CONFIG:
                s = "CMD_SAVE_CONFIG";
                break;
            case CMD_GET_CONFIGURED_NETWORKS:
                s = "CMD_GET_CONFIGURED_NETWORKS";
                break;
            case CMD_GET_SUPPORTED_FEATURES:
                s = "CMD_GET_SUPPORTED_FEATURES";
                break;
            case CMD_UNWANTED_NETWORK:
                s = "CMD_UNWANTED_NETWORK";
                break;
            case CMD_NETWORK_STATUS:
                s = "CMD_NETWORK_STATUS";
                break;
            case CMD_GET_LINK_LAYER_STATS:
                s = "CMD_GET_LINK_LAYER_STATS";
                break;
            case CMD_GET_MATCHING_CONFIG:
                s = "CMD_GET_MATCHING_CONFIG";
                break;
            case CMD_GET_PRIVILEGED_CONFIGURED_NETWORKS:
                s = "CMD_GET_PRIVILEGED_CONFIGURED_NETWORKS";
                break;
            case CMD_DISCONNECT:
                s = "CMD_DISCONNECT";
                break;
            case CMD_RECONNECT:
                s = "CMD_RECONNECT";
                break;
            case CMD_REASSOCIATE:
                s = "CMD_REASSOCIATE";
                break;
            case CMD_GET_CONNECTION_STATISTICS:
                s = "CMD_GET_CONNECTION_STATISTICS";
                break;
            case CMD_SET_HIGH_PERF_MODE:
                s = "CMD_SET_HIGH_PERF_MODE";
                break;
            case CMD_SET_COUNTRY_CODE:
                s = "CMD_SET_COUNTRY_CODE";
                break;
            case CMD_ENABLE_RSSI_POLL:
                s = "CMD_ENABLE_RSSI_POLL";
                break;
            case CMD_RSSI_POLL:
                s = "CMD_RSSI_POLL";
                break;
            case CMD_START_PACKET_FILTERING:
                s = "CMD_START_PACKET_FILTERING";
                break;
            case CMD_STOP_PACKET_FILTERING:
                s = "CMD_STOP_PACKET_FILTERING";
                break;
            case CMD_SET_SUSPEND_OPT_ENABLED:
                s = "CMD_SET_SUSPEND_OPT_ENABLED";
                break;
            case CMD_NO_NETWORKS_PERIODIC_SCAN:
                s = "CMD_NO_NETWORKS_PERIODIC_SCAN";
                break;
            case CMD_UPDATE_LINKPROPERTIES:
                s = "CMD_UPDATE_LINKPROPERTIES";
                break;
            case CMD_RELOAD_TLS_AND_RECONNECT:
                s = "CMD_RELOAD_TLS_AND_RECONNECT";
                break;
            case WifiManager.CONNECT_NETWORK:
                s = "CONNECT_NETWORK";
                break;
            case WifiManager.SAVE_NETWORK:
                s = "SAVE_NETWORK";
                break;
            case WifiManager.FORGET_NETWORK:
                s = "FORGET_NETWORK";
                break;
            case WifiMonitor.SUP_CONNECTION_EVENT:
                s = "SUP_CONNECTION_EVENT";
                break;
            case WifiMonitor.SUP_DISCONNECTION_EVENT:
                s = "SUP_DISCONNECTION_EVENT";
                break;
            case WifiMonitor.SCAN_RESULTS_EVENT:
                s = "SCAN_RESULTS_EVENT";
                break;
            case WifiMonitor.SCAN_FAILED_EVENT:
                s = "SCAN_FAILED_EVENT";
                break;
            case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                s = "SUPPLICANT_STATE_CHANGE_EVENT";
                break;
            case WifiMonitor.AUTHENTICATION_FAILURE_EVENT:
                s = "AUTHENTICATION_FAILURE_EVENT";
                break;
            case WifiMonitor.SSID_TEMP_DISABLED:
                s = "SSID_TEMP_DISABLED";
                break;
            case WifiMonitor.SSID_REENABLED:
                s = "SSID_REENABLED";
                break;
            case WifiMonitor.WPS_SUCCESS_EVENT:
                s = "WPS_SUCCESS_EVENT";
                break;
            case WifiMonitor.WPS_FAIL_EVENT:
                s = "WPS_FAIL_EVENT";
                break;
            case WifiMonitor.SUP_REQUEST_IDENTITY:
                s = "SUP_REQUEST_IDENTITY";
                break;
            case WifiMonitor.NETWORK_CONNECTION_EVENT:
                s = "NETWORK_CONNECTION_EVENT";
                break;
            case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                s = "NETWORK_DISCONNECTION_EVENT";
                break;
            case WifiMonitor.ASSOCIATION_REJECTION_EVENT:
                s = "ASSOCIATION_REJECTION_EVENT";
                break;
            case WifiMonitor.ANQP_DONE_EVENT:
                s = "WifiMonitor.ANQP_DONE_EVENT";
                break;
            case WifiMonitor.GAS_QUERY_DONE_EVENT:
                s = "WifiMonitor.GAS_QUERY_DONE_EVENT";
                break;
            case WifiMonitor.HS20_DEAUTH_EVENT:
                s = "WifiMonitor.HS20_DEAUTH_EVENT";
                break;
            case WifiMonitor.GAS_QUERY_START_EVENT:
                s = "WifiMonitor.GAS_QUERY_START_EVENT";
                break;
            case CMD_SET_OPERATIONAL_MODE:
                s = "CMD_SET_OPERATIONAL_MODE";
                break;
            case CMD_START_SCAN:
                s = "CMD_START_SCAN";
                break;
            case CMD_DISABLE_P2P_RSP:
                s = "CMD_DISABLE_P2P_RSP";
                break;
            case CMD_DISABLE_P2P_REQ:
                s = "CMD_DISABLE_P2P_REQ";
                break;
            case WifiWatchdogStateMachine.GOOD_LINK_DETECTED:
                s = "GOOD_LINK_DETECTED";
                break;
            case WifiWatchdogStateMachine.POOR_LINK_DETECTED:
                s = "POOR_LINK_DETECTED";
                break;
            case WifiP2pServiceImpl.GROUP_CREATING_TIMED_OUT:
                s = "GROUP_CREATING_TIMED_OUT";
                break;
            case WifiP2pServiceImpl.P2P_CONNECTION_CHANGED:
                s = "P2P_CONNECTION_CHANGED";
                break;
            case WifiP2pServiceImpl.DISCONNECT_WIFI_RESPONSE:
                s = "P2P.DISCONNECT_WIFI_RESPONSE";
                break;
            case WifiP2pServiceImpl.SET_MIRACAST_MODE:
                s = "P2P.SET_MIRACAST_MODE";
                break;
            case WifiP2pServiceImpl.BLOCK_DISCOVERY:
                s = "P2P.BLOCK_DISCOVERY";
                break;
            case WifiP2pServiceImpl.SET_COUNTRY_CODE:
                s = "P2P.SET_COUNTRY_CODE";
                break;
            case WifiManager.CANCEL_WPS:
                s = "CANCEL_WPS";
                break;
            case WifiManager.CANCEL_WPS_FAILED:
                s = "CANCEL_WPS_FAILED";
                break;
            case WifiManager.CANCEL_WPS_SUCCEDED:
                s = "CANCEL_WPS_SUCCEDED";
                break;
            case WifiManager.START_WPS:
                s = "START_WPS";
                break;
            case WifiManager.START_WPS_SUCCEEDED:
                s = "START_WPS_SUCCEEDED";
                break;
            case WifiManager.WPS_FAILED:
                s = "WPS_FAILED";
                break;
            case WifiManager.WPS_COMPLETED:
                s = "WPS_COMPLETED";
                break;
            case WifiManager.RSSI_PKTCNT_FETCH:
                s = "RSSI_PKTCNT_FETCH";
                break;
            case CMD_IP_CONFIGURATION_LOST:
                s = "CMD_IP_CONFIGURATION_LOST";
                break;
            case CMD_IP_CONFIGURATION_SUCCESSFUL:
                s = "CMD_IP_CONFIGURATION_SUCCESSFUL";
                break;
            case CMD_IP_REACHABILITY_LOST:
                s = "CMD_IP_REACHABILITY_LOST";
                break;
            case CMD_STATIC_IP_SUCCESS:
                s = "CMD_STATIC_IP_SUCCESSFUL";
                break;
            case CMD_STATIC_IP_FAILURE:
                s = "CMD_STATIC_IP_FAILURE";
                break;
            case DhcpStateMachine.DHCP_SUCCESS:
                s = "DHCP_SUCCESS";
                break;
            case DhcpStateMachine.DHCP_FAILURE:
                s = "DHCP_FAILURE";
                break;
            case CMD_TARGET_BSSID:
                s = "CMD_TARGET_BSSID";
                break;
            case CMD_ASSOCIATED_BSSID:
                s = "CMD_ASSOCIATED_BSSID";
                break;
            case CMD_REMOVE_APP_CONFIGURATIONS:
                s = "CMD_REMOVE_APP_CONFIGURATIONS";
                break;
            case CMD_REMOVE_USER_CONFIGURATIONS:
                s = "CMD_REMOVE_USER_CONFIGURATIONS";
                break;
            case CMD_ROAM_WATCHDOG_TIMER:
                s = "CMD_ROAM_WATCHDOG_TIMER";
                break;
            case CMD_SCREEN_STATE_CHANGED:
                s = "CMD_SCREEN_STATE_CHANGED";
                break;
            case CMD_DISCONNECTING_WATCHDOG_TIMER:
                s = "CMD_DISCONNECTING_WATCHDOG_TIMER";
                break;
            case CMD_RESTART_AUTOJOIN_OFFLOAD:
                s = "CMD_RESTART_AUTOJOIN_OFFLOAD";
                break;
            case CMD_STARTED_PNO_DBG:
                s = "CMD_STARTED_PNO_DBG";
                break;
            case CMD_STARTED_GSCAN_DBG:
                s = "CMD_STARTED_GSCAN_DBG";
                break;
            case CMD_PNO_NETWORK_FOUND:
                s = "CMD_PNO_NETWORK_FOUND";
                break;
            case CMD_UPDATE_ASSOCIATED_SCAN_PERMISSION:
                s = "CMD_UPDATE_ASSOCIATED_SCAN_PERMISSION";
                break;
            ///M: add propiretary log @{
            case M_CMD_UPDATE_SETTINGS:
                s = "M_CMD_UPDATE_SETTINGS";
                break;
            case M_CMD_UPDATE_SCAN_INTERVAL:
                s = "M_CMD_UPDATE_SCAN_INTERVAL";
                break;
            case M_CMD_UPDATE_COUNTRY_CODE:
                s = "M_CMD_UPDATE_COUNTRY_CODE";
                break;
            case M_CMD_GET_CONNECTING_NETWORK_ID:
                s = "M_CMD_GET_CONNECTING_NETWORK_ID";
                break;
            case M_CMD_UPDATE_RSSI:
                s = "M_CMD_UPDATE_RSSI";
                break;
            case M_CMD_BLOCK_CLIENT:
                s = "M_CMD_BLOCK_CLIENT";
                break;
            case M_CMD_UNBLOCK_CLIENT:
                s = "M_CMD_UNBLOCK_CLIENT";
                break;
            case M_CMD_START_AP_WPS:
                s = "M_CMD_START_AP_WPS";
                break;
            case M_CMD_UPDATE_BGSCAN:
                s = "M_CMD_UPDATE_BGSCAN";
                break;
            case M_CMD_SET_AP_PROBE_REQUEST_ENABLED:
                s = "M_CMD_SET_AP_PROBE_REQUEST_ENABLED";
                break;
            case M_CMD_SLEEP_POLICY_STOP_SCAN:
                s = "M_CMD_SLEEP_POLICY_STOP_SCAN";
                break;
            case M_CMD_NOTIFY_SCREEN_OFF:
                s = "M_CMD_NOTIFY_SCREEN_OFF";
                break;
            case M_CMD_NOTIFY_SCREEN_ON:
                s = "M_CMD_NOTIFY_SCREEN_ON";
                break;
            case M_CMD_GET_DISCONNECT_FLAG:
                s = "M_CMD_GET_DISCONNECT_FLAG";
                break;
            case M_CMD_NOTIFY_CONNECTION_FAILURE:
                s = "M_CMD_NOTIFY_CONNECTION_FAILURE";
                break;
            case M_CMD_GET_WIFI_STATUS:
                s = "M_CMD_GET_WIFI_STATUS";
                break;
            case M_CMD_SET_POWER_SAVING_MODE:
                s = "M_CMD_SET_POWER_SAVING_MODE";
                break;
            case M_CMD_START_WPS_NFC_TAG_READ:
                s = "M_CMD_START_WPS_NFC_TAG_READ";
                break;
            case M_CMD_HS_RECEIVED:
                s = "M_CMD_HS_RECEIVED";
                break;
            case M_CMD_HR_RECEIVED:
                s = "M_CMD_HR_RECEIVED";
                break;
            case M_CMD_CLEAE_HR_WAIT_FLAG:
                s = "M_CMD_CLEAE_HR_WAIT_FLAG";
                break;
            case M_CMD_FLUSH_BSS:
                s = "M_CMD_FLUSH_BSS";
                break;
            case M_CMD_DHCP_V6_SUCCESS:
                s = "M_CMD_DHCP_V6_SUCCESS";
                break;
            case M_CMD_START_AUTOJOIN_PROFILING:
                s = "M_CMD_START_AUTOJOIN_PROFILING";
                break;
            case WifiP2pManager.ADD_LOCAL_SERVICE:
                s = "P2PMANAGER_ADD_LOCAL_SERVICE";
                break;
            case WifiP2pManager.ADD_SERVICE_REQUEST:
                s = "P2PMANAGER_ADD_SERVICE_REQUEST";
                break;
            case WifiP2pManager.CANCEL_CONNECT:
                s = "P2PMANAGER_CANCEL_CONNECT";
                break;
            case WifiP2pManager.CLEAR_LOCAL_SERVICES:
                s = "P2PMANAGER_CLEAR_LOCAL_SERVICES";
                break;
            case WifiP2pManager.CLEAR_SERVICE_REQUESTS:
                s = "P2PMANAGER_CLEAR_SERVICE_REQUESTS";
                break;
            case WifiP2pManager.CONNECT:
                s = "P2PMANAGER_CONNECT";
                break;
            case WifiP2pManager.CREATE_GROUP:
                s = "P2PMANAGER_CREATE_GROUP";
                break;
            case WifiP2pManager.DELETE_PERSISTENT_GROUP:
                s = "P2PMANAGER_DELETE_PERSISTENT_GROUP";
                break;
            case WifiP2pManager.DISCOVER_PEERS:
                s = "P2PMANAGER_DISCOVER_PEERS";
                break;
            case WifiP2pManager.DISCOVER_SERVICES:
                s = "P2PMANAGER_DISCOVER_SERVICES";
                break;
            case WifiP2pManager.FAST_CONNECT_AS_GC:
                s = "P2PMANAGER_FAST_CONNECT_AS_GC";
                break;
            case WifiP2pManager.FAST_CONNECT_AS_GO:
                s = "P2PMANAGER_FAST_CONNECT_AS_GO";
                break;
            case WifiP2pManager.FAST_DISCOVER_PEERS:
                s = "P2PMANAGER_FAST_DISCOVER_PEERS";
                break;
            case WifiP2pManager.FREQ_CONFLICT_EX_RESULT:
                s = "P2PMANAGER_FREQ_CONFLICT_EX_RESULT";
                break;
            case WifiP2pManager.GET_HANDOVER_REQUEST:
                s = "P2PMANAGER_GET_HANDOVER_REQUEST";
                break;
            case WifiP2pManager.GET_HANDOVER_SELECT:
                s = "P2PMANAGER_GET_HANDOVER_SELECT";
                break;
            case WifiP2pManager.GET_NFC_CONFIG_TOKEN:
                s = "P2PMANAGER_GET_NFC_CONFIG_TOKEN";
                break;
            case WifiP2pManager.GET_NFC_REQUEST_TOKEN:
                s = "P2PMANAGER_GET_NFC_REQUEST_TOKEN";
                break;
            case WifiP2pManager.GET_NFC_SELECT_TOKEN:
                s = "P2PMANAGER_GET_NFC_SELECT_TOKEN";
                break;
            case WifiP2pManager.GET_NFC_WPS_CONFIG_TOKEN:
                s = "P2PMANAGER_GET_NFC_WPS_CONFIG_TOKEN";
                break;
            case WifiP2pManager.INITIATOR_REPORT_NFC_HANDOVER:
                s = "P2PMANAGER_INITIATOR_REPORT_NFC_HANDOVER";
                break;
            case WifiP2pManager.REMOVE_GROUP:
                s = "P2PMANAGER_REMOVE_GROUP";
                break;
            case WifiP2pManager.REMOVE_LOCAL_SERVICE:
                s = "P2PMANAGER_REMOVE_LOCAL_SERVICE";
                break;
            case WifiP2pManager.REMOVE_SERVICE_REQUEST:
                s = "P2PMANAGER_REMOVE_SERVICE_REQUEST";
                break;
            case WifiP2pManager.REQUEST_CONNECTION_INFO:
                s = "P2PMANAGER_REQUEST_CONNECTION_INFO";
                break;
            case WifiP2pManager.REQUEST_GROUP_INFO:
                s = "P2PMANAGER_REQUEST_GROUP_INFO";
                break;
            case WifiP2pManager.REQUEST_LINK_INFO:
                s = "P2PMANAGER_REQUEST_LINK_INFO";
                break;
            case WifiP2pManager.REQUEST_PEERS:
                s = "P2PMANAGER_REQUEST_PEERS";
                break;
            case WifiP2pManager.REQUEST_PERSISTENT_GROUP_INFO:
                s = "P2PMANAGER_REQUEST_PERSISTENT_GROUP_INFO";
                break;
            case WifiP2pManager.RESPONDER_REPORT_NFC_HANDOVER:
                s = "P2PMANAGER_RESPONDER_REPORT_NFC_HANDOVER";
                break;
            case WifiP2pManager.SET_AUTO_CHANNEL_SELECT:
                s = "P2PMANAGER_SET_AUTO_CHANNEL_SELECT";
                break;
            case WifiP2pManager.SET_CHANNEL:
                s = "P2PMANAGER_SET_CHANNEL";
                break;
            case WifiP2pManager.SET_DEVICE_NAME:
                s = "P2PMANAGER_SET_DEVICE_NAME";
                break;
            case WifiP2pManager.SET_WFD_INFO:
                s = "P2PMANAGER_SET_WFD_INFO";
                break;
            case WifiP2pManager.START_FAST_CONNECT_AS_GC:
                s = "P2PMANAGER_START_FAST_CONNECT_AS_GC";
                break;
            case WifiP2pManager.START_FAST_CONNECT_AS_GO:
                s = "P2PMANAGER_START_FAST_CONNECT_AS_GO";
                break;
            case WifiP2pManager.START_LISTEN:
                s = "P2PMANAGER_START_LISTEN";
                break;
            case WifiP2pManager.START_WPS:
                s = "P2PMANAGER_START_WPS";
                break;
            case WifiP2pManager.STOP_DISCOVERY:
                s = "P2PMANAGER_STOP_DISCOVERY";
                break;
            case WifiP2pManager.STOP_LISTEN:
                s = "P2PMANAGER_STOP_LISTEN";
                break;
            case WifiP2pManager.PEER_CONNECTION_USER_ACCEPT_FROM_OUTER:
                s = "P2PMANAGER_PEER_CONNECTION_USER_ACCEPT_FROM_OUTER";
                break;
            case WifiP2pManager.PEER_CONNECTION_USER_REJECT_FROM_OUTER:
                s = "P2PMANAGER_PEER_CONNECTION_USER_REJECT_FROM_OUTER";
                break;
            case WifiMonitor.AP_STA_CONNECTED_EVENT:
                s = "AP_STA_CONNECTED_EVENT";
                break;
            case WifiMonitor.AP_STA_DISCONNECTED_EVENT:
                s = "AP_STA_DISCONNECTED_EVENT";
                break;
            case WifiMonitor.P2P_DEVICE_FOUND_EVENT:
                s = "P2P_DEVICE_FOUND_EVENT";
                break;
            case WifiMonitor.P2P_DEVICE_LOST_EVENT:
                s = "P2P_DEVICE_LOST_EVENT";
                break;
            case WifiMonitor.P2P_FIND_STOPPED_EVENT:
                s = "P2P_FIND_STOPPED_EVENT";
                break;
            case WifiMonitor.P2P_GC_IP_GET_EVENT:
                s = "P2P_GC_IP_GET_EVENT";
                break;
            case WifiMonitor.P2P_GO_IP_ALLOCATE_EVENT:
                s = "P2P_GO_IP_ALLOCATE_EVENT";
                break;
            case WifiMonitor.P2P_GO_NEGOTIATION_FAILURE_EVENT:
                s = "P2P_GO_NEGOTIATION_FAILURE_EVENT";
                break;
            case WifiMonitor.P2P_GO_NEGOTIATION_REQUEST_EVENT:
                s = "P2P_GO_NEGOTIATION_REQUEST_EVENT";
                break;
            case WifiMonitor.P2P_GO_NEGOTIATION_SUCCESS_EVENT:
                s = "P2P_GO_NEGOTIATION_SUCCESS_EVENT";
                break;
            case WifiMonitor.P2P_GROUP_FORMATION_FAILURE_EVENT:
                s = "P2P_GROUP_FORMATION_FAILURE_EVENT";
                break;
            case WifiMonitor.P2P_GROUP_FORMATION_SUCCESS_EVENT:
                s = "P2P_GROUP_FORMATION_SUCCESS_EVENT";
                break;
            case WifiMonitor.P2P_GROUP_REMOVED_EVENT:
                s = "P2P_GROUP_REMOVED_EVENT";
                break;
            case WifiMonitor.P2P_GROUP_STARTED_EVENT:
                s = "P2P_GROUP_STARTED_EVENT";
                break;
            case WifiMonitor.P2P_INVITATION_RECEIVED_EVENT:
                s = "P2P_INVITATION_RECEIVED_EVENT";
                break;
            case WifiMonitor.P2P_INVITATION_RESULT_EVENT:
                s = "P2P_INVITATION_RESULT_EVENT";
                break;
            case WifiMonitor.P2P_NFC_GO_INVITED_EVENT:
                s = "P2P_NFC_GO_INVITED_EVENT";
                break;
            case WifiMonitor.P2P_PEER_DISCONNECT_EVENT:
                s = "P2P_PEER_DISCONNECT_EVENT";
                break;
            case WifiMonitor.P2P_PROV_DISC_ENTER_PIN_EVENT:
                s = "P2P_PROV_DISC_ENTER_PIN_EVENT";
                break;
            case WifiMonitor.P2P_PROV_DISC_FAILURE_EVENT:
                s = "P2P_PROV_DISC_FAILURE_EVENT";
                break;
            case WifiMonitor.P2P_PROV_DISC_PBC_REQ_EVENT:
                s = "P2P_PROV_DISC_PBC_REQ_EVENT";
                break;
            case WifiMonitor.P2P_PROV_DISC_PBC_RSP_EVENT:
                s = "P2P_PROV_DISC_PBC_RSP_EVENT";
                break;
            case WifiMonitor.P2P_PROV_DISC_SHOW_PIN_EVENT:
                s = "P2P_PROV_DISC_SHOW_PIN_EVENT";
                break;
            case WifiMonitor.P2P_SERV_DISC_RESP_EVENT:
                s = "P2P_SERV_DISC_RESP_EVENT";
                break;
            case WifiMonitor.WPS_OVERLAP_EVENT:
                s = "WPS_OVERLAP_EVENT";
                break;
            case WifiMonitor.WPS_TIMEOUT_EVENT:
                s = "WPS_TIMEOUT_EVENT";
                break;
            case WifiStateMachine.CMD_ENABLE_P2P:
                s = "CMD_ENABLE_P2P";
                break;
            case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION:
                s = "CMD_CHANNEL_FULL_CONNECTION";
                break;
            case WifiP2pServiceImpl.DISABLE_P2P_TIMED_OUT:
                s = "P2P_DISABLE_P2P_TIMED_OUT";
                break;
            case WifiP2pServiceImpl.FAST_CONNECT_FIND_GO_TIMED_OUT:
                s = "P2P_FAST_CONNECT_FIND_GO_TIMED_OUT";
                break;
            case M_CMD_FACTORY_RESET:
                s = "M_CMD_FACTORY_RESET";
                break;
            case M_CMD_IP_REACHABILITY_MONITOR_TIMER_10s:
                s = "M_CMD_IP_REACHABILITY_MONITOR_TIMER_10s";
                break;
            case M_CMD_IP_REACHABILITY_MONITOR_TIMER_3s:
                s = "M_CMD_IP_REACHABILITY_MONITOR_TIMER_3s";
                break;
            ///@}
            default:
                s = "what:" + Integer.toString(what);
                break;
        }
        return s;
    }

    void registerConnected() {
       if (mLastNetworkId != WifiConfiguration.INVALID_NETWORK_ID) {
           long now_ms = System.currentTimeMillis();
           // We are switching away from this configuration,
           // hence record the time we were connected last
           WifiConfiguration config = mWifiConfigStore.getWifiConfiguration(mLastNetworkId);
           if (config != null) {
               config.lastConnected = System.currentTimeMillis();
               config.autoJoinBailedDueToLowRssi = false;
               config.setAutoJoinStatus(WifiConfiguration.AUTO_JOIN_ENABLED);
               config.numConnectionFailures = 0;
               config.numIpConfigFailures = 0;
               config.numAuthFailures = 0;
               config.numAssociation++;
           }
           mBadLinkspeedcount = 0;
       }
    }

    void registerDisconnected() {
        if (mLastNetworkId != WifiConfiguration.INVALID_NETWORK_ID) {
            long now_ms = System.currentTimeMillis();
            // We are switching away from this configuration,
            // hence record the time we were connected last
            WifiConfiguration config = mWifiConfigStore.getWifiConfiguration(mLastNetworkId);
            if (config != null) {
                config.lastDisconnected = System.currentTimeMillis();
                if (config.ephemeral) {
                    // Remove ephemeral WifiConfigurations from file
                    mWifiConfigStore.forgetNetwork(mLastNetworkId);
                }
            }
        }
    }

    void noteWifiDisabledWhileAssociated() {
        // We got disabled by user while we were associated, make note of it
        int rssi = mWifiInfo.getRssi();
        WifiConfiguration config = getCurrentWifiConfiguration();
        if (getCurrentState() == mConnectedState
                && rssi != WifiInfo.INVALID_RSSI
                && config != null) {
            boolean is24GHz = mWifiInfo.is24GHz();
            boolean isBadRSSI = (is24GHz && rssi < mWifiConfigStore.thresholdBadRssi24.get())
                    || (!is24GHz && rssi < mWifiConfigStore.thresholdBadRssi5.get());
            boolean isLowRSSI = (is24GHz && rssi < mWifiConfigStore.thresholdLowRssi24.get())
                    || (!is24GHz && mWifiInfo.getRssi() < mWifiConfigStore.thresholdLowRssi5.get());
            boolean isHighRSSI = (is24GHz && rssi >= mWifiConfigStore.thresholdGoodRssi24.get())
                    || (!is24GHz && mWifiInfo.getRssi() >= mWifiConfigStore.thresholdGoodRssi5.get());
            if (isBadRSSI) {
                // Take note that we got disabled while RSSI was Bad
                config.numUserTriggeredWifiDisableLowRSSI++;
            } else if (isLowRSSI) {
                // Take note that we got disabled while RSSI was Low
                config.numUserTriggeredWifiDisableBadRSSI++;
            } else if (!isHighRSSI) {
                // Take note that we got disabled while RSSI was Not high
                config.numUserTriggeredWifiDisableNotHighRSSI++;
            }
        }
    }

    WifiConfiguration getCurrentWifiConfiguration() {
        if (mLastNetworkId == WifiConfiguration.INVALID_NETWORK_ID) {
            return null;
        }
        return mWifiConfigStore.getWifiConfiguration(mLastNetworkId);
    }

    ScanResult getCurrentScanResult() {
        WifiConfiguration config = getCurrentWifiConfiguration();
        if (config == null) {
            return null;
        }
        String BSSID = mWifiInfo.getBSSID();
        if (BSSID == null) {
            BSSID = mTargetRoamBSSID;
        }
        ScanDetailCache scanDetailCache =
                mWifiConfigStore.getScanDetailCache(config);

        if (scanDetailCache == null) {
            return null;
        }

        return scanDetailCache.get(BSSID);
    }

    String getCurrentBSSID() {
        if (linkDebouncing) {
            return null;
        }
        return mLastBssid;
    }


    class ConnectModeState extends State {

        @Override
        public void enter() {
            if (DBG) loge(getName() + "\n");
            connectScanningService();
        }

        @Override
        public boolean processMessage(Message message) {
            WifiConfiguration config;
            int netId;
            boolean ok;
            boolean didDisconnect;
            String bssid;
            String ssid;
            NetworkUpdateResult result;
            logStateAndMessage(message, getClass().getSimpleName());

            switch (message.what) {
                case WifiMonitor.ASSOCIATION_REJECTION_EVENT:
                    mWifiLogger.captureBugReportData(WifiLogger.REPORT_REASON_ASSOC_FAILURE);
                    didBlackListBSSID = false;
                    bssid = (String) message.obj;
                    if (bssid == null || TextUtils.isEmpty(bssid)) {
                        // If BSSID is null, use the target roam BSSID
                        bssid = mTargetRoamBSSID;
                    }
                    if (bssid != null) {
                        // If we have a BSSID, tell configStore to black list it
                        synchronized(mScanResultCache) {
                            didBlackListBSSID = mWifiConfigStore.handleBSSIDBlackList
                                    (mLastNetworkId, bssid, false);
                        }
                    }
                    mSupplicantStateTracker.sendMessage(WifiMonitor.ASSOCIATION_REJECTION_EVENT);
                    break;
                case WifiMonitor.AUTHENTICATION_FAILURE_EVENT:
                    mWifiLogger.captureBugReportData(WifiLogger.REPORT_REASON_AUTH_FAILURE);
                    mSupplicantStateTracker.sendMessage(WifiMonitor.AUTHENTICATION_FAILURE_EVENT);

                    ///M:@{
                    if (mWifiFwkExt != null) {
                        mWifiFwkExt.setNotificationVisible(true);
                    }
                    ///@}
                    break;
                case WifiMonitor.SSID_TEMP_DISABLED:
                case WifiMonitor.SSID_REENABLED:
                    String substr = (String) message.obj;
                    String en = message.what == WifiMonitor.SSID_TEMP_DISABLED ?
                            "temp-disabled" : "re-enabled";
                    logd("ConnectModeState SSID state=" + en + " nid="
                            + Integer.toString(message.arg1) + " [" + substr + "]");
                    synchronized(mScanResultCache) {
                        mWifiConfigStore.handleSSIDStateChange(message.arg1, message.what ==
                                WifiMonitor.SSID_REENABLED, substr, mWifiInfo.getBSSID());
                    }
                    break;
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                    SupplicantState state = handleSupplicantStateChange(message);
                    // A driver/firmware hang can now put the interface in a down state.
                    // We detect the interface going down and recover from it
                    if (!SupplicantState.isDriverActive(state)) {
                        if (mNetworkInfo.getState() != NetworkInfo.State.DISCONNECTED) {
                            handleNetworkDisconnect();
                        }
                        log("Detected an interface down, restart driver");
                        transitionTo(mDriverStoppedState);
                        sendMessage(CMD_START_DRIVER);
                        break;
                    }

                    // Supplicant can fail to report a NETWORK_DISCONNECTION_EVENT
                    // when authentication times out after a successful connection,
                    // we can figure this from the supplicant state. If supplicant
                    // state is DISCONNECTED, but the mNetworkInfo says we are not
                    // disconnected, we need to handle a disconnection
                    if (!linkDebouncing && state == SupplicantState.DISCONNECTED &&
                            mNetworkInfo.getState() != NetworkInfo.State.DISCONNECTED) {
                        if (DBG) log("Missed CTRL-EVENT-DISCONNECTED, disconnect");
                        handleNetworkDisconnect();
                        transitionTo(mDisconnectedState);
                    }

                    // If we have COMPLETED a connection to a BSSID, start doing
                    // DNAv4/DNAv6 -style probing for on-link neighbors of
                    // interest (e.g. routers); harmless if none are configured.
                    if (state == SupplicantState.COMPLETED) {
                        if (mIpReachabilityMonitor != null) {
                            mIpReachabilityMonitor.probeAll();
                        }
                    }
                    break;
                case WifiP2pServiceImpl.DISCONNECT_WIFI_REQUEST:
                    if (message.arg1 == 1) {
                        mWifiNative.disconnect();
                        mTemporarilyDisconnectWifi = true;
                    } else {
                        mWifiNative.reconnect();
                        mTemporarilyDisconnectWifi = false;
                    }
                    break;
                case CMD_ADD_OR_UPDATE_NETWORK:
                    config = (WifiConfiguration) message.obj;

                    if (!recordUidIfAuthorized(config, message.sendingUid,
                            /* onlyAnnotate */ false)) {
                        logw("Not authorized to update network "
                             + " config=" + config.SSID
                             + " cnid=" + config.networkId
                             + " uid=" + message.sendingUid);
                        replyToMessage(message, message.what, FAILURE);
                        break;
                    }

                    int res = mWifiConfigStore.addOrUpdateNetwork(config, message.sendingUid);
                    if (res < 0) {
                        messageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
                    } else {
                        WifiConfiguration curConfig = getCurrentWifiConfiguration();
                        if (curConfig != null && config != null) {
                            if (curConfig.priority < config.priority
                                    && config.status == WifiConfiguration.Status.ENABLED) {
                                // Interpret this as a connect attempt
                                // Set the last selected configuration so as to allow the system to
                                // stick the last user choice without persisting the choice
                                mWifiConfigStore.setLastSelectedConfiguration(res);
                                mWifiConfigStore.updateLastConnectUid(config, message.sendingUid);
                                mWifiConfigStore.writeKnownNetworkHistory(false);

                                // Remember time of last connection attempt
                                lastConnectAttemptTimestamp = System.currentTimeMillis();

                                mWifiConnectionStatistics.numWifiManagerJoinAttempt++;

                                // As a courtesy to the caller, trigger a scan now
                                startScan(ADD_OR_UPDATE_SOURCE, 0, null, null);
                            }
                        }
                        if (hasCustomizedAutoConnect()) {
                            checkIfEapNetworkChanged(config);
                        }
                    }
                    replyToMessage(message, CMD_ADD_OR_UPDATE_NETWORK, res);
                    break;
                case CMD_REMOVE_NETWORK:
                    netId = message.arg1;
                    if (!mWifiConfigStore.canModifyNetwork(message.sendingUid, netId,
                            /* onlyAnnotate */ false)) {
                        logw("Not authorized to remove network "
                             + " cnid=" + netId
                             + " uid=" + message.sendingUid);
                        replyToMessage(message, message.what, FAILURE);
                        break;
                    }

                    ok = mWifiConfigStore.removeNetwork(message.arg1);
                    if (!ok) {
                        messageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
                    }
                    if (hasCustomizedAutoConnect()) {
                        mWifiConfigStore.removeDisconnectNetwork(message.arg1);
                        if (ok && message.arg1 == mWifiInfo.getNetworkId()) {
                            mDisconnectOperation = true;
                            mScanForWeakSignal = false;
                        }
                    }
                    replyToMessage(message, message.what, ok ? SUCCESS : FAILURE);
                    break;
                case CMD_ENABLE_NETWORK:
                    if (hasCustomizedAutoConnect() && message.arg2 == 0) {
                        if (!mWifiFwkExt.shouldAutoConnect()) {
                            Log.d(TAG, "Shouldn't auto connect, ignore the enable network operation!");
                            replyToMessage(message, message.what, SUCCESS);
                            break;
                        } else {
                            List<Integer> disconnectNetworks = mWifiConfigStore.getDisconnectNetworks();
                            if (disconnectNetworks.contains(message.arg1)) {
                                Log.d(TAG, "Network " + message.arg1 + " is disconnected actively, ignore the enable network operation!");
                                replyToMessage(message, message.what, SUCCESS);
                                break;
                            }
                        }
                    }
                    boolean disableOthers = message.arg2 == 1;
                    netId = message.arg1;
                    config = mWifiConfigStore.getWifiConfiguration(netId);
                    if (config == null) {
                        loge("No network with id = " + netId);
                        messageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
                        ///M: ALPS02573254 APK enables a non-existed netId will encounter ANR
                        replyToMessage(message, message.what, FAILURE);
                        break;
                    }

                    // Tell autojoin the user did try to select to that network
                    // However, do NOT persist the choice by bumping the priority of the network
                    // ALPS01962463 Whether is disable others or not, should update config history
                    mWifiAutoJoinController.
                            updateConfigurationHistory(netId, true, false);
                    if (disableOthers) {
                        // Set the last selected configuration so as to allow the system to
                        // stick the last user choice without persisting the choice
                        mWifiConfigStore.setLastSelectedConfiguration(netId);

                        // Remember time of last connection attempt
                        lastConnectAttemptTimestamp = System.currentTimeMillis();

                        mWifiConnectionStatistics.numWifiManagerJoinAttempt++;
                    }
                    // Cancel auto roam requests
                    autoRoamSetBSSID(netId, "any");

                    int uid = message.sendingUid;
                    ok = mWifiConfigStore.enableNetwork(netId, disableOthers, uid);
                    if (!ok) {
                        messageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
                    }
                    if (ok && message.arg2 == 1) {
                        if (hasCustomizedAutoConnect()) {
                            mWifiConfigStore.removeDisconnectNetwork(message.arg1);
                            mDisconnectOperation = true;
                            mScanForWeakSignal = false;
                        }
                        mLastExplicitNetworkId = message.arg1;
                        mConnectNetwork = true;
                        mSupplicantStateTracker.sendMessage(WifiManager.CONNECT_NETWORK,
                                                            message.arg1);
                    }

                    replyToMessage(message, message.what, ok ? SUCCESS : FAILURE);
                    break;
                case CMD_ENABLE_ALL_NETWORKS:
                    long time = android.os.SystemClock.elapsedRealtime();
                    if (time - mLastEnableAllNetworksTime > MIN_INTERVAL_ENABLE_ALL_NETWORKS_MS) {
                        mWifiConfigStore.enableAllNetworks();
                        mLastEnableAllNetworksTime = time;
                    }
                    break;
                case WifiManager.DISABLE_NETWORK:
                    if (mWifiConfigStore.disableNetwork(message.arg1,
                            WifiConfiguration.DISABLED_BY_WIFI_MANAGER) == true) {
                        if (hasCustomizedAutoConnect()) {
                            mWifiConfigStore.addDisconnectNetwork(message.arg1);
                            if (message.arg1 == mWifiInfo.getNetworkId()) {
                                mDisconnectOperation = true;
                                mScanForWeakSignal = false;
                            }
                        }
                        replyToMessage(message, WifiManager.DISABLE_NETWORK_SUCCEEDED);
                    } else {
                        messageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
                        replyToMessage(message, WifiManager.DISABLE_NETWORK_FAILED,
                                WifiManager.ERROR);
                    }
                    break;
                case CMD_DISABLE_EPHEMERAL_NETWORK:
                    config = mWifiConfigStore.disableEphemeralNetwork((String)message.obj);
                    if (config != null) {
                        if (config.networkId == mLastNetworkId) {
                            // Disconnect and let autojoin reselect a new network
                            sendMessage(CMD_DISCONNECT);
                        }
                    }
                    break;
                case CMD_BLACKLIST_NETWORK:
                    mWifiConfigStore.blackListBssid((String) message.obj);
                    break;
                case CMD_CLEAR_BLACKLIST:
                    mWifiConfigStore.clearBssidBlacklist();
                    break;
                case CMD_SAVE_CONFIG:
                    ok = mWifiConfigStore.saveConfig();

                    if (DBG) logd("did save config " + ok);
                    replyToMessage(message, CMD_SAVE_CONFIG, ok ? SUCCESS : FAILURE);

                    // Inform the backup manager about a data change
                    IBackupManager ibm = IBackupManager.Stub.asInterface(
                            ServiceManager.getService(Context.BACKUP_SERVICE));
                    if (ibm != null) {
                        try {
                            ibm.dataChanged("com.android.providers.settings");
                        } catch (Exception e) {
                            // Try again later
                        }
                    }
                    break;
                case CMD_GET_CONFIGURED_NETWORKS:
                    replyToMessage(message, message.what,
                            mWifiConfigStore.getConfiguredNetworks());
                    break;
                case WifiMonitor.SUP_REQUEST_IDENTITY:
                    logd("Received SUP_REQUEST_IDENTITY");
                    int networkId = message.arg2;
                    boolean identitySent = false;
                    int eapMethod = WifiEnterpriseConfig.Eap.NONE;

                    if (targetWificonfiguration != null
                            && targetWificonfiguration.enterpriseConfig != null) {
                        eapMethod = targetWificonfiguration.enterpriseConfig.getEapMethod();
                    }

                    // For SIM & AKA/AKA' EAP method Only, get identity from ICC
                    if (targetWificonfiguration != null
                            && targetWificonfiguration.networkId == networkId
                            && targetWificonfiguration.allowedKeyManagement
                                    .get(WifiConfiguration.KeyMgmt.IEEE8021X)
                            &&  (eapMethod == WifiEnterpriseConfig.Eap.SIM
                            || eapMethod == WifiEnterpriseConfig.Eap.AKA
                            || eapMethod == WifiEnterpriseConfig.Eap.AKA_PRIME)) {
                        TelephonyManager tm = (TelephonyManager)
                                mContext.getSystemService(Context.TELEPHONY_SERVICE);
                        if (tm != null) {
                            log("TelephonyManager != null");
                            ///M: extend to multiple sim card @{
                            String imsi;
                            String mccMnc = "";
                            if (tm.getDefault().getPhoneCount() >= 2) {
                                int slotId = getIntSimSlot(targetWificonfiguration.simSlot);
                                log("simSlot: " + targetWificonfiguration.simSlot + " " + slotId);
                                int subId = getSubId(slotId);
                                log("subId: " + subId);
                                imsi = tm.getSubscriberId(subId);
                                if (tm.getSimState(slotId) == TelephonyManager.SIM_STATE_READY) {
                                    mccMnc = tm.getSimOperator(subId);
                                }
                            } else {
                                imsi = tm.getSubscriberId();
                                if (tm.getSimState() == TelephonyManager.SIM_STATE_READY) {
                                    mccMnc = tm.getSimOperator();
                                }
                            }
                            log("imsi: " + imsi);
                            log("mccMnc: " + mccMnc);
                            ///@}

                            String identity = buildIdentity(eapMethod, imsi, mccMnc);

                            if (!identity.isEmpty()) {
                                mWifiNative.simIdentityResponse(networkId, identity);
                                identitySent = true;
                            }
                        } else {
                            loge("TelephonyManager is null");
                        }
                    }
                    if (!identitySent) {
                        // Supplicant lacks credentials to connect to that network, hence black list
                        ssid = (String) message.obj;
                        if (targetWificonfiguration != null && ssid != null
                                && targetWificonfiguration.SSID != null
                                && targetWificonfiguration.SSID.equals("\"" + ssid + "\"")) {
                            mWifiConfigStore.handleSSIDStateChange(
                                    targetWificonfiguration.networkId, false,
                                    "AUTH_FAILED no identity", null);
                        }
                        // Disconnect now, as we don't have any way to fullfill
                        // the  supplicant request.
                        mWifiConfigStore.setLastSelectedConfiguration(
                                WifiConfiguration.INVALID_NETWORK_ID);
                        if (!hasCustomizedAutoConnect()) {
                            mWifiNative.disconnect();
                        } else {
                            Log.d(TAG, "Skip SUP_REQUEST_IDENTITY disconnect for customization!");
                        }
                    }
                    break;
                case WifiMonitor.SUP_REQUEST_SIM_AUTH:
                    logd("Received SUP_REQUEST_SIM_AUTH");
                    SimAuthRequestData requestData = (SimAuthRequestData) message.obj;
                    if (requestData != null) {
                        if (requestData.protocol == WifiEnterpriseConfig.Eap.SIM) {
                            handleGsmAuthRequest(requestData);
                        } else if (requestData.protocol == WifiEnterpriseConfig.Eap.AKA
                            || requestData.protocol == WifiEnterpriseConfig.Eap.AKA_PRIME) {
                            handle3GAuthRequest(requestData);
                        }
                    } else {
                        loge("Invalid sim auth request");
                    }
                    break;
                case CMD_GET_PRIVILEGED_CONFIGURED_NETWORKS:
                    replyToMessage(message, message.what,
                            mWifiConfigStore.getPrivilegedConfiguredNetworks());
                    break;
                case CMD_GET_MATCHING_CONFIG:
                    replyToMessage(message, message.what,
                            mWifiConfigStore.getMatchingConfig((ScanResult)message.obj));
                    break;
                /* Do a redundant disconnect without transition */
                case CMD_DISCONNECT:
                    mWifiConfigStore.setLastSelectedConfiguration
                            (WifiConfiguration.INVALID_NETWORK_ID);
                    mWifiNative.disconnect();
                    break;
                case CMD_RECONNECT:
                    mWifiAutoJoinController.attemptAutoJoin();
                    break;
                case CMD_REASSOCIATE:
                    lastConnectAttemptTimestamp = System.currentTimeMillis();
                    mWifiNative.reassociate();
                    break;
                case CMD_RELOAD_TLS_AND_RECONNECT:
                    if (mWifiConfigStore.needsUnlockedKeyStore()) {
                        logd("Reconnecting to give a chance to un-connected TLS networks");
                        mWifiNative.disconnect();
                        ///M: ALPS01982740 TLS network should not connect when screen locked @{
                        logd("enable TLS networks");
                        mWifiConfigStore.enableTlsNetworks();
                        ///@}
                        if (hasCustomizedAutoConnect()) {
                            mDisconnectOperation = true;
                        }
                        lastConnectAttemptTimestamp = System.currentTimeMillis();
                        mWifiNative.reconnect();
                    }
                    break;
                case CMD_AUTO_ROAM:
                    messageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                    return HANDLED;
                case CMD_AUTO_CONNECT:
                    if (hasCustomizedAutoConnect() && !mWifiFwkExt.shouldAutoConnect()) {
                        Log.d(TAG, "Skip CMD_AUTO_CONNECT for customization!");
                        return HANDLED;
                    }

                    ///M: ALPS01982740 TLS network should not connect when screen locked @{
                    config = (WifiConfiguration) message.obj;
                    if (mWifiConfigStore.mIsScreenLocked.get()) {
                        if (config != null && mWifiConfigStore.isCertNeeded(config)) {
                            Log.d(TAG, "Skip connect ap due to screen locked");
                            return HANDLED;
                        }
                    }
                    ///@}

                    /* Work Around: wpa_supplicant can get in a bad state where it returns a non
                     * associated status to the STATUS command but somehow-someplace still thinks
                     * it is associated and thus will ignore select/reconnect command with
                     * following message:
                     * "Already associated with the selected network - do nothing"
                     *
                     * Hence, sends a disconnect to supplicant first.
                     */
                    ///M: ALPS01975084 Airplane mode off, modem is not ready yet @{
                    if(!mIccState.equals(IccCardConstants.INTENT_VALUE_ICC_LOADED)){
                        int value = config.enterpriseConfig.getEapMethod();
                        log("EAP value:" + value);
                        if (value == WifiEnterpriseConfig.Eap.SIM
                                || value == WifiEnterpriseConfig.Eap.AKA
                                || value == WifiEnterpriseConfig.Eap.AKA_PRIME) {
                            if(mIccState.equals(IccCardConstants.INTENT_VALUE_ICC_ABSENT)
                                    || mIccState.equals(IccCardConstants.INTENT_VALUE_ICC_LOCKED)
                                    || mIccState.equals(IccCardConstants.INTENT_VALUE_ICC_UNKNOWN)){
                                loge("AUTO_CONNECT EAP-SIM AP, but card is " + mIccState
                                        + ", set autojoinStatus to "
                                        + "AUTO_JOIN_DISABLED_EAP_SIM_CARD_ABSENT"
                                        + ", drop this connect");
                                WifiConfiguration eapSimConfig =
                                    mWifiConfigStore.getWifiConfiguration(config.networkId);
                                eapSimConfig.setAutoJoinStatus(
                                        WifiConfiguration.AUTO_JOIN_DISABLED_EAP_SIM_CARD_ABSENT);
                            }else{
                                loge("AUTO_CONNECT EAP-SIM AP"
                                        + ", but modem is not ready, drop this connect");
                            }
                            break;
                        }
                    }
                    ///M: @}
                    didDisconnect = false;
                    if (getCurrentState() != mDisconnectedState) {
                        /** Supplicant will ignore the reconnect if we are currently associated,
                         * hence trigger a disconnect
                         */
                        didDisconnect = true;
                        mWifiNative.disconnect();
                    }

                    /* connect command coming from auto-join */
                    //config = (WifiConfiguration) message.obj;
                    netId = message.arg1;
                    int roam = message.arg2;
                    logd("CMD_AUTO_CONNECT sup state "
                            + mSupplicantStateTracker.getSupplicantStateName()
                            + " my state " + getCurrentState().getName()
                            + " nid=" + Integer.toString(netId)
                            + " roam=" + Integer.toString(roam));
                    if (config == null) {
                        loge("AUTO_CONNECT and no config, bail out...");
                        break;
                    }

                    /* Make sure we cancel any previous roam request */
                    autoRoamSetBSSID(netId, config.BSSID);

                    /* Save the network config */
                    logd("CMD_AUTO_CONNECT will save config -> " + config.SSID
                            + " nid=" + Integer.toString(netId));
                    result = mWifiConfigStore.saveNetwork(config, WifiConfiguration.UNKNOWN_UID);
                    netId = result.getNetworkId();
                    logd("CMD_AUTO_CONNECT did save config -> "
                            + " nid=" + Integer.toString(netId));

                    // Since we updated the config,read it back from config store:
                    config = mWifiConfigStore.getWifiConfiguration(netId);
                    if (config == null) {
                        loge("CMD_AUTO_CONNECT couldn't update the config, got null config");
                        break;
                    }
                    if (netId != config.networkId) {
                        loge("CMD_AUTO_CONNECT couldn't update the config, want"
                                + " nid=" + Integer.toString(netId) + " but got" + config.networkId);
                        break;
                    }

                    if (deferForUserInput(message, netId, false)) {
                        break;
                    } else if (mWifiConfigStore.getWifiConfiguration(netId).userApproved ==
                                                                   WifiConfiguration.USER_BANNED) {
                        replyToMessage(message, WifiManager.CONNECT_NETWORK_FAILED,
                                WifiManager.NOT_AUTHORIZED);
                        break;
                    }

                    // Make sure the network is enabled, since supplicant will not reenable it
                    mWifiConfigStore.enableNetworkWithoutBroadcast(netId, false);

                    // If we're autojoining a network that the user or an app explicitly selected,
                    // keep track of the UID that selected it.
                    int lastConnectUid = mWifiConfigStore.isLastSelectedConfiguration(config) ?
                            config.lastConnectUid : WifiConfiguration.UNKNOWN_UID;

                    boolean tmpResult = false;
                    if (hasCustomizedAutoConnect()) {
                        tmpResult = mWifiConfigStore.enableNetwork(netId, true, lastConnectUid);
                    } else {
                        tmpResult = mWifiConfigStore.selectNetwork(config,
                            /* updatePriorities = */ false,
                            lastConnectUid);
                    }
                    if (tmpResult &&
                            mWifiNative.reconnect()) {
                        lastConnectAttemptTimestamp = System.currentTimeMillis();
                        targetWificonfiguration = mWifiConfigStore.getWifiConfiguration(netId);
                        config = mWifiConfigStore.getWifiConfiguration(netId);
                        if (config != null
                                && !mWifiConfigStore.isLastSelectedConfiguration(config)) {
                            // If we autojoined a different config than the user selected one,
                            // it means we could not see the last user selection,
                            // or that the last user selection was faulty and ended up blacklisted
                            // for some reason (in which case the user is notified with an error
                            // message in the Wifi picker), and thus we managed to auto-join away
                            // from the selected  config. -> in that case we need to forget
                            // the selection because we don't want to abruptly switch back to it.
                            //
                            // Note that the user selection is also forgotten after a period of time
                            // during which the device has been disconnected.
                            // The default value is 30 minutes : see the code path at bottom of
                            // setScanResults() function.
                            mWifiConfigStore.
                                 setLastSelectedConfiguration(WifiConfiguration.INVALID_NETWORK_ID);
                        }
                        mAutoRoaming = roam;
                        if (isRoaming() || linkDebouncing) {
                            transitionTo(mRoamingState);
                        } else if (didDisconnect) {
                            transitionTo(mDisconnectingState);
                        } else {
                            /* Already in disconnected state, nothing to change */
                            if (!mScreenOn && mLegacyPnoEnabled && mBackgroundScanSupported) {
                                int delay = 60 * 1000;
                                if (VDBG) {
                                    logd("Starting PNO alarm: " + delay);
                                }
                                mAlarmManager.set(AlarmManager.RTC_WAKEUP,
                                       System.currentTimeMillis() + delay,
                                       mPnoIntent);
                            }
                            mRestartAutoJoinOffloadCounter++;
                        }
                    } else {
                        loge("Failed to connect config: " + config + " netId: " + netId);
                        replyToMessage(message, WifiManager.CONNECT_NETWORK_FAILED,
                                WifiManager.ERROR);
                        break;
                    }
                    break;
                case CMD_REMOVE_APP_CONFIGURATIONS:
                    mWifiConfigStore.removeNetworksForApp((ApplicationInfo) message.obj);
                    break;
                case CMD_REMOVE_USER_CONFIGURATIONS:
                    mWifiConfigStore.removeNetworksForUser(message.arg1);
                    break;
                case WifiManager.CONNECT_NETWORK:
                    /**
                     *  The connect message can contain a network id passed as arg1 on message or
                     * or a config passed as obj on message.
                     * For a new network, a config is passed to create and connect.
                     * For an existing network, a network id is passed
                     */
                    netId = message.arg1;
                    config = (WifiConfiguration) message.obj;
                    mWifiConnectionStatistics.numWifiManagerJoinAttempt++;
                    boolean updatedExisting = false;

                    /* Save the network config */
                    if (config != null) {
                        // When connecting to an access point, WifiStateMachine wants to update the
                        // relevant config with administrative data. This update should not be
                        // considered a 'real' update, therefore lockdown by Device Owner must be
                        // disregarded.
                        if (!recordUidIfAuthorized(config, message.sendingUid,
                                /* onlyAnnotate */ true)) {
                            logw("Not authorized to update network "
                                 + " config=" + config.SSID
                                 + " cnid=" + config.networkId
                                 + " uid=" + message.sendingUid);
                            replyToMessage(message, WifiManager.CONNECT_NETWORK_FAILED,
                                           WifiManager.NOT_AUTHORIZED);
                            break;
                        }

                        String configKey = config.configKey(true /* allowCached */);
                        WifiConfiguration savedConfig =
                                mWifiConfigStore.getWifiConfiguration(configKey);
                        if (savedConfig != null) {
                            // There is an existing config with this netId, but it wasn't exposed
                            // (either AUTO_JOIN_DELETED or ephemeral; see WifiConfigStore#
                            // getConfiguredNetworks). Remove those bits and update the config.
                            config = savedConfig;
                            logd("CONNECT_NETWORK updating existing config with id=" +
                                    config.networkId + " configKey=" + configKey);
                            config.ephemeral = false;
                            config.autoJoinStatus = WifiConfiguration.AUTO_JOIN_ENABLED;
                            updatedExisting = true;
                        }

                        result = mWifiConfigStore.saveNetwork(config, message.sendingUid);
                        netId = result.getNetworkId();
                    }
                    config = mWifiConfigStore.getWifiConfiguration(netId);

                    if (config == null) {
                        logd("CONNECT_NETWORK no config for id=" + Integer.toString(netId) + " "
                                + mSupplicantStateTracker.getSupplicantStateName() + " my state "
                                + getCurrentState().getName());
                        replyToMessage(message, WifiManager.CONNECT_NETWORK_FAILED,
                                WifiManager.ERROR);
                        break;
                    } else {
                        String wasSkipped = config.autoJoinBailedDueToLowRssi ? " skipped" : "";
                        logd("CONNECT_NETWORK id=" + Integer.toString(netId)
                                + " config=" + config.SSID
                                + " cnid=" + config.networkId
                                + " supstate=" + mSupplicantStateTracker.getSupplicantStateName()
                                + " my state " + getCurrentState().getName()
                                + " uid = " + message.sendingUid
                                + wasSkipped);
                    }

                    autoRoamSetBSSID(netId, "any");

                    if (message.sendingUid == Process.WIFI_UID
                        || message.sendingUid == Process.SYSTEM_UID) {
                        // As a sanity measure, clear the BSSID in the supplicant network block.
                        // If system or Wifi Settings want to connect, they will not
                        // specify the BSSID.
                        // If an app however had added a BSSID to this configuration, and the BSSID
                        // was wrong, Then we would forever fail to connect until that BSSID
                        // is cleaned up.
                        clearConfigBSSID(config, "CONNECT_NETWORK");
                    }

                    if (deferForUserInput(message, netId, true)) {
                        break;
                    } else if (mWifiConfigStore.getWifiConfiguration(netId).userApproved ==
                                                                    WifiConfiguration.USER_BANNED) {
                        replyToMessage(message, WifiManager.CONNECT_NETWORK_FAILED,
                                WifiManager.NOT_AUTHORIZED);
                        break;
                    }

                    mAutoRoaming = WifiAutoJoinController.AUTO_JOIN_IDLE;

                    /* Tell autojoin the user did try to connect to that network if from settings */
                    boolean persist =
                        mWifiConfigStore.checkConfigOverridePermission(message.sendingUid);
                    mWifiAutoJoinController.updateConfigurationHistory(netId, true, persist);

                    mWifiConfigStore.setLastSelectedConfiguration(netId);

                    didDisconnect = false;
                    if (mLastNetworkId != WifiConfiguration.INVALID_NETWORK_ID
                            && mLastNetworkId != netId) {
                        /** Supplicant will ignore the reconnect if we are currently associated,
                         * hence trigger a disconnect
                         */
                        didDisconnect = true;
                        mWifiNative.disconnect();
                    }

                    // Make sure the network is enabled, since supplicant will not reenable it
                    mWifiConfigStore.enableNetworkWithoutBroadcast(netId, false);

                    if (mWifiConfigStore.selectNetwork(config, /* updatePriorities = */ true,
                            message.sendingUid) && mWifiNative.reconnect()) {
                        lastConnectAttemptTimestamp = System.currentTimeMillis();
                        targetWificonfiguration = mWifiConfigStore.getWifiConfiguration(netId);

                        /* The state tracker handles enabling networks upon completion/failure */
                        mSupplicantStateTracker.sendMessage(WifiManager.CONNECT_NETWORK, netId);
                        replyToMessage(message, WifiManager.CONNECT_NETWORK_SUCCEEDED);

                        ///M:@{
                        mConnectNetwork = true;
                        mLastExplicitNetworkId = netId;
                        if (hasCustomizedAutoConnect()) {
                            mDisconnectOperation = true;
                            mScanForWeakSignal = false;
                            mWifiConfigStore.removeDisconnectNetwork(netId);
                        }
                        ///@}

                        if (didDisconnect) {
                            /* Expect a disconnection from the old connection */
                            transitionTo(mDisconnectingState);
                        } else if (updatedExisting && getCurrentState() == mConnectedState &&
                                getCurrentWifiConfiguration().networkId == netId) {
                            // Update the current set of network capabilities, but stay in the
                            // current state.
                            updateCapabilities(config);
                        } else {
                            /**
                                             *  Directly go to disconnected state where we
                                             * process the connection events from supplicant
                                             **/
                            transitionTo(mDisconnectedState);

                        }
                    } else {
                        loge("Failed to connect config: " + config + " netId: " + netId);
                        replyToMessage(message, WifiManager.CONNECT_NETWORK_FAILED,
                                WifiManager.ERROR);
                        break;
                    }
                    break;
                case WifiManager.SAVE_NETWORK:
                    mWifiConnectionStatistics.numWifiManagerJoinAttempt++;
                    // Fall thru
                case WifiStateMachine.CMD_AUTO_SAVE_NETWORK:
                    lastSavedConfigurationAttempt = null; // Used for debug
                    config = (WifiConfiguration) message.obj;
                    if (config == null) {
                        loge("ERROR: SAVE_NETWORK with null configuration"
                                + mSupplicantStateTracker.getSupplicantStateName()
                                + " my state " + getCurrentState().getName());
                        messageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
                        replyToMessage(message, WifiManager.SAVE_NETWORK_FAILED,
                                WifiManager.ERROR);
                        break;
                    }
                    lastSavedConfigurationAttempt = new WifiConfiguration(config);
                    int nid = config.networkId;
                    logd("SAVE_NETWORK id=" + Integer.toString(nid)
                                + " config=" + config.SSID
                                + " nid=" + config.networkId
                                + " supstate=" + mSupplicantStateTracker.getSupplicantStateName()
                                + " my state " + getCurrentState().getName());

                    // Only record the uid if this is user initiated
                    boolean checkUid = (message.what == WifiManager.SAVE_NETWORK);
                    if (checkUid && !recordUidIfAuthorized(config, message.sendingUid,
                            /* onlyAnnotate */ false)) {
                        logw("Not authorized to update network "
                             + " config=" + config.SSID
                             + " cnid=" + config.networkId
                             + " uid=" + message.sendingUid);
                        replyToMessage(message, WifiManager.SAVE_NETWORK_FAILED,
                                       WifiManager.NOT_AUTHORIZED);
                        break;
                    }

                    result = mWifiConfigStore.saveNetwork(config, WifiConfiguration.UNKNOWN_UID);
                    if (result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID) {
                        if (mWifiInfo.getNetworkId() == result.getNetworkId()) {
                            if (result.hasIpChanged()) {
                                // The currently connection configuration was changed
                                // We switched from DHCP to static or from static to DHCP, or the
                                // static IP address has changed.
                                log("Reconfiguring IP on connection");
                                // TODO: clear addresses and disable IPv6
                                // to simplify obtainingIpState.
                                transitionTo(mObtainingIpState);
                            }
                            if (result.hasProxyChanged()) {
                                log("Reconfiguring proxy on connection");
                                updateLinkProperties(CMD_UPDATE_LINKPROPERTIES);
                            }
                        }
                        replyToMessage(message, WifiManager.SAVE_NETWORK_SUCCEEDED);
                        broadcastWifiCredentialChanged(WifiManager.WIFI_CREDENTIAL_SAVED, config);

                        if (VDBG) {
                           logd("Success save network nid="
                                    + Integer.toString(result.getNetworkId()));
                        }

                        synchronized(mScanResultCache) {
                            /**
                             * If the command comes from WifiManager, then
                             * tell autojoin the user did try to modify and save that network,
                             * and interpret the SAVE_NETWORK as a request to connect
                             */
                            boolean user = message.what == WifiManager.SAVE_NETWORK;

                            // Did this connect come from settings
                            boolean persistConnect =
                                mWifiConfigStore.checkConfigOverridePermission(message.sendingUid);

                            if (user) {
                                mWifiConfigStore.updateLastConnectUid(config, message.sendingUid);
                                mWifiConfigStore.writeKnownNetworkHistory(false);
                            }

                            mWifiAutoJoinController.updateConfigurationHistory(result.getNetworkId()
                                    , user, persistConnect);
                            mWifiAutoJoinController.attemptAutoJoin();
                        }
                        if (hasCustomizedAutoConnect()) {
                            checkIfEapNetworkChanged(config);
                        }
                    } else {
                        loge("Failed to save network");
                        messageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
                        replyToMessage(message, WifiManager.SAVE_NETWORK_FAILED,
                                WifiManager.ERROR);
                    }
                    break;
                case WifiManager.FORGET_NETWORK:
                    // Debug only, remember last configuration that was forgotten
                    WifiConfiguration toRemove
                            = mWifiConfigStore.getWifiConfiguration(message.arg1);
                    if (toRemove == null) {
                        lastForgetConfigurationAttempt = null;
                    } else {
                        lastForgetConfigurationAttempt = new WifiConfiguration(toRemove);
                    }
                    // check that the caller owns this network
                    netId = message.arg1;

                    if (!mWifiConfigStore.canModifyNetwork(message.sendingUid, netId,
                            /* onlyAnnotate */ false)) {
                        logw("Not authorized to forget network "
                             + " cnid=" + netId
                             + " uid=" + message.sendingUid);
                        replyToMessage(message, WifiManager.FORGET_NETWORK_FAILED,
                                WifiManager.NOT_AUTHORIZED);
                        break;
                    }

                    if (mWifiConfigStore.forgetNetwork(message.arg1)) {
                        if (hasCustomizedAutoConnect()) {
                            mWifiConfigStore.removeDisconnectNetwork(message.arg1);
                            if (message.arg1 == mWifiInfo.getNetworkId()) {
                                mDisconnectOperation = true;
                                mScanForWeakSignal = false;
                            }
                        }
                        if (message.arg1 == mLastExplicitNetworkId) {
                            mLastExplicitNetworkId = INVALID_NETWORK_ID;
                            mConnectNetwork = false;
                            mSupplicantStateTracker.sendMessage(WifiManager.FORGET_NETWORK, message.arg1);
                        }
                        replyToMessage(message, WifiManager.FORGET_NETWORK_SUCCEEDED);
                        broadcastWifiCredentialChanged(WifiManager.WIFI_CREDENTIAL_FORGOT,
                                (WifiConfiguration) message.obj);
                    } else {
                        loge("Failed to forget network");
                        replyToMessage(message, WifiManager.FORGET_NETWORK_FAILED,
                                WifiManager.ERROR);
                    }
                    break;
                case WifiManager.START_WPS:
                    if (hasCustomizedAutoConnect()) {
                        mDisconnectOperation = true;
                        mScanForWeakSignal = false;
                        disableLastNetwork();
                    }
                    WpsInfo wpsInfo = (WpsInfo) message.obj;
                    WpsResult wpsResult;
                    switch (wpsInfo.setup) {
                        case WpsInfo.PBC:
                            wpsResult = mWifiConfigStore.startWpsPbc(wpsInfo);
                            break;
                        case WpsInfo.KEYPAD:
                            /** M: NFC Float II @{ */
                            if (mMtkWpsp2pnfcSupport) {
                                if (TextUtils.isEmpty(wpsInfo.pin) || mWifiNative.startApWpsCheckPinCommand(wpsInfo.pin) == null) {
                                    Log.e(TAG, "Invalid pin code.");
                                    replyToMessage(message, WifiManager.WPS_FAILED, WifiManager.WPS_INVALID_PIN);
                                    return HANDLED;
                                } else {
                                    mErApPin = wpsInfo.pin;
                                }
                            }
                            /** @} */
                            wpsResult = mWifiConfigStore.startWpsWithPinFromAccessPoint(wpsInfo);
                            break;
                        case WpsInfo.DISPLAY:
                            wpsResult = mWifiConfigStore.startWpsWithPinFromDevice(wpsInfo);
                            break;
                        default:
                            wpsResult = new WpsResult(Status.FAILURE);
                            loge("Invalid setup for WPS");
                            break;
                    }
                    mWifiConfigStore.setLastSelectedConfiguration
                            (WifiConfiguration.INVALID_NETWORK_ID);
                    if (wpsResult.status == Status.SUCCESS) {
                        replyToMessage(message, WifiManager.START_WPS_SUCCEEDED, wpsResult);
                        transitionTo(mWpsRunningState);
                    } else {
                        loge("Failed to start WPS with config " + wpsInfo.toString());
                        replyToMessage(message, WifiManager.WPS_FAILED, WifiManager.ERROR);
                    }
                    break;
                case WifiMonitor.NETWORK_CONNECTION_EVENT:
                    if (DBG) log("Network connection established");
                    if (hasCustomizedAutoConnect()) {
                        mDisconnectOperation = false;
                    }
                    mConnectNetwork = false;
                    mLastNetworkId = message.arg1;
                    mLastBssid = (String) message.obj;

                    mWifiInfo.setBSSID(mLastBssid);
                    mWifiInfo.setNetworkId(mLastNetworkId);

                    sendNetworkStateChangeBroadcast(mLastBssid);
                    transitionTo(mObtainingIpState);
                    break;
                case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                    // Calling handleNetworkDisconnect here is redundant because we might already
                    // have called it when leaving L2ConnectedState to go to disconnecting state
                    // or thru other path
                    // We should normally check the mWifiInfo or mLastNetworkId so as to check
                    // if they are valid, and only in this case call handleNEtworkDisconnect,
                    // TODO: this should be fixed for a L MR release
                    // The side effect of calling handleNetworkDisconnect twice is that a bunch of
                    // idempotent commands are executed twice (stopping Dhcp, enabling the SPS mode
                    // at the chip etc...
                    if (DBG) log("ConnectModeState: Network connection lost ");
                    handleNetworkDisconnect();
                    transitionTo(mDisconnectedState);
                    break;
                case CMD_PNO_NETWORK_FOUND:
                    processPnoNetworkFound((ScanResult[])message.obj);
                    break;
                case WifiMonitor.WAPI_NO_CERTIFICATION_EVENT:
                    Log.d(TAG, "WAPI no certification!");
                    mContext.sendBroadcastAsUser(new Intent(WifiManager.NO_CERTIFICATION_ACTION), UserHandle.ALL);
                    break;
                case WifiMonitor.NEW_PAC_UPDATED_EVENT:
                    Log.d(TAG, "EAP-FAST new pac updated!");
                    mContext.sendBroadcastAsUser(new Intent(WifiManager.NEW_PAC_UPDATED_ACTION), UserHandle.ALL);
                    break;
                case WifiManager.STOP_PPPOE:
                    if (mMtkCtpppoe) {
                        stopPPPoE();
                        replyToMessage(message, WifiManager.STOP_PPPOE_SUCCEEDED);
                    } else {
                        replyToMessage(message, WifiManager.STOP_PPPOE_FAILED, WifiManager.ERROR);
                    }
                    break;
                /** M: NFC Float II @{ */
                case WifiManager.START_WPS_REG:
                    if (!mMtkWpsp2pnfcSupport) {
                        replyToMessage(message, WifiManager.WPS_FAILED, WifiManager.ERROR);
                        break;
                    }
                    wpsInfo = (WpsInfo) message.obj;
                    int nfcPw = Settings.System.getInt(mContext.getContentResolver(), "nfc_pw", 0);
                    Log.d(TAG, "START_WPS_REG, nfcPw:" + nfcPw);
                    if (wpsInfo.setup == WpsInfo.KEYPAD) {
                        if (nfcPw == 0) {
                            if (!TextUtils.isEmpty(wpsInfo.pin) && (mWifiNative.startApWpsCheckPinCommand(wpsInfo.pin) == null)) {
                                Log.e(TAG, "Invalid pin code.");
                                replyToMessage(message, WifiManager.WPS_FAILED, WifiManager.WPS_INVALID_PIN);
                                return HANDLED;
                            }
                        } else {
                            Log.d(TAG, "Using pin from NFC.");
                            wpsInfo.pin = "nfc-pw";
                        }
                    if (!TextUtils.isEmpty(wpsInfo.ssid)) {
                        String hexStr = bytesToHexString(wpsInfo.ssid.getBytes());
                            if (null != hexStr) {
                            wpsInfo.ssid = hexStr.toLowerCase();
                            } else {
                            wpsInfo.ssid = null;
                            }
                        }
                        if (!TextUtils.isEmpty(wpsInfo.key)) {
                            String hexStr = bytesToHexString(wpsInfo.key.getBytes());
                            if (null != hexStr) {
                                wpsInfo.key = hexStr.toLowerCase();
                            } else {
                                wpsInfo.key = null;
                            }
                        }
                        wpsResult = mWifiConfigStore.startWpsReg(wpsInfo);
                        mErApPin = wpsInfo.pin;
                    } else {
                        wpsResult = new WpsResult(Status.FAILURE);
                        Log.e(TAG, "Invalid setup for WPS REG");
                    }
                    if (wpsResult.status == Status.SUCCESS) {
                        replyToMessage(message, WifiManager.START_WPS_SUCCEEDED, wpsResult);
                        transitionTo(mWpsRunningState);
                    } else {
                        Log.e(TAG, "Failed to start WPS REG with config " + wpsInfo.toString());
                        replyToMessage(message, WifiManager.WPS_FAILED, WifiManager.ERROR);
                    }
                    break;
                case WifiManager.START_WPS_ER:
                    if (!mMtkWpsp2pnfcSupport) {
                        replyToMessage(message, WifiManager.WPS_FAILED, WifiManager.ERROR);
                        break;
                    }
                    wpsInfo = (WpsInfo) message.obj;
                    Log.d(TAG, "START_WPS_ER, mEnrolleeUuid:" + mEnrolleeUuid + ", mEnrolleeBssid:" + mEnrolleeBssid);
                    switch (wpsInfo.setup) {
                        case WpsInfo.PBC:
                            if (!TextUtils.isEmpty(mEnrolleeUuid)) {
                                wpsResult = mWifiConfigStore.startWpsErPbc(mEnrolleeUuid);
                            } else {
                                mWaitingForEnrollee = true;
                                mWpsErMethod = WpsInfo.PBC;
                                mAlarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + CLEAR_WATI_FLAG_MS,
                                                  mClearWaitFlagIntent);
                                return HANDLED;
                            }
                            break;
                        case WpsInfo.KEYPAD:
                            if (TextUtils.isEmpty(wpsInfo.pin) || mWifiNative.startApWpsCheckPinCommand(wpsInfo.pin) == null) {
                                Log.e(TAG, "Invalid pin code.");
                                replyToMessage(message, WifiManager.WPS_FAILED, WifiManager.WPS_INVALID_PIN);
                                return HANDLED;
                            } else {
                                wpsResult = mWifiConfigStore.startWpsErPinAny(wpsInfo.pin);
                            }
                            break;
                        default:
                            wpsResult = new WpsResult(Status.FAILURE);
                            Log.e(TAG, "Invalid setup for WPS ER!");
                            break;
                    }
                    if (wpsResult.status == Status.SUCCESS) {
                        replyToMessage(message, WifiManager.START_WPS_SUCCEEDED, wpsResult);
                        transitionTo(mWpsRunningState);
                    } else {
                        Log.e(TAG, "Failed to start WPS ER with config " + wpsInfo.toString());
                        replyToMessage(message, WifiManager.WPS_FAILED, WifiManager.ERROR);
                    }
                    mEnrolleeUuid = null;
                    mEnrolleeBssid = null;
                    break;
                case WifiManager.GET_WPS_PIN_AND_CONNECT:   //Item 22
                    if (!mMtkWpsp2pnfcSupport) {
                        replyToMessage(message, WifiManager.WPS_FAILED, WifiManager.ERROR);
                        break;
                    }
                    String passwordToken = mWifiNative.wpsNfcToken(message.arg1 == WifiManager.TOKEN_TYPE_NDEF);
                    Log.d(TAG, "Item22: GET_WPS_PIN_AND_CONNECT, passwordToken:" + passwordToken);
                    if (!TextUtils.isEmpty(passwordToken) && !passwordToken.equals(UNKNOWN_COMMAND)) {
                        sendPinToNfcBroadcast(passwordToken);
                        wpsResult = mWifiConfigStore.startWpsNfc();
                        if (wpsResult.status == Status.SUCCESS) {
                            replyToMessage(message, WifiManager.START_WPS_SUCCEEDED, wpsResult);
                            transitionTo(mWpsRunningState);
                        } else {
                            Log.e(TAG, "Failed to start WPS NFC!");
                            replyToMessage(message, WifiManager.WPS_FAILED, WifiManager.ERROR);
                        }
                    } else {
                        Log.e(TAG, "Failed to get password token!");
                        replyToMessage(message, WifiManager.WPS_FAILED, WifiManager.ERROR);
                    }
                    break;
                case WifiManager.GET_WPS_CRED_AND_CONNECT:      //Item 26
                    if (!mMtkWpsp2pnfcSupport) {
                        replyToMessage(message, WifiManager.GET_WPS_CRED_AND_CONNECT_FAILED, WifiManager.ERROR);
                        break;
                    }
                    String credential = mWifiNative.wpsErNfcConfigToken(message.arg1 == WifiManager.TOKEN_TYPE_NDEF, mErApUuid);
                    Log.d(TAG, "Item26: GET_WPS_CRED_AND_CONNECT, credential:" + credential);
                    if (!TextUtils.isEmpty(credential) && !credential.equals(UNKNOWN_COMMAND)) {
                        sendCredentialToNfcBroadcast(credential);
                        replyToMessage(message, WifiManager.GET_WPS_CRED_AND_CONNECT_SUCCEEDED);
                    } else {
                        Log.e(TAG, "Failed to get WPS credential, mErApUuid:" + mErApUuid);
                        replyToMessage(message, WifiManager.GET_WPS_CRED_AND_CONNECT_FAILED, WifiManager.ERROR);
                    }
                    break;
                case WifiManager.WRITE_PIN_TO_NFC:      //Item 28
                    if (!mMtkWpsp2pnfcSupport) {
                        replyToMessage(message, WifiManager.WRITE_PIN_TO_NFC_FAILED, WifiManager.ERROR);
                        break;
                    }
                    passwordToken = mWifiNative.getNfcHandoverToken(true);
                    Log.d(TAG, "Item28: get requester token, passwordToken: " + passwordToken);
                    if (!TextUtils.isEmpty(passwordToken) && !passwordToken.equals(UNKNOWN_COMMAND)) {
                        sendRequesterActionToNfc(passwordToken);
                        int keyType = Settings.System.getInt(mContext.getContentResolver(), "wps_nfc_pubkey", 0);
                        mWifiNative.wpsNfcCfgKeyType(keyType);
                        replyToMessage(message, WifiManager.WRITE_PIN_TO_NFC_SUCCEEDED);
                    } else {
                        Log.e(TAG, "Failed to get requester token!");
                        replyToMessage(message, WifiManager.WRITE_PIN_TO_NFC_FAILED, WifiManager.ERROR);
                    }
                    break;
                case WifiManager.WRITE_CRED_TO_NFC:     //Item 29
                    if (!mMtkWpsp2pnfcSupport) {
                        replyToMessage(message, WifiManager.WRITE_CRED_TO_NFC_FAILED, WifiManager.ERROR);
                        break;
                    }
                    passwordToken = mWifiNative.getNfcHandoverToken(false);
                    Log.d(TAG, "Item29: get selector token, passwordToken: " + passwordToken);
                    if (!TextUtils.isEmpty(passwordToken) && !passwordToken.equals(UNKNOWN_COMMAND)) {
                        mWaitingForHrToken = true;
                        mWifiP2pChannel.sendMessage(M_CMD_CLEAE_HR_WAIT_FLAG);
                        sendSelectorActionToNfc(passwordToken);
                        int keyType = Settings.System.getInt(mContext.getContentResolver(), "wps_nfc_pubkey", 0);
                        mWifiNative.wpsNfcCfgKeyType(keyType);
                        replyToMessage(message, WifiManager.WRITE_CRED_TO_NFC_SUCCEEDED);
                    } else {
                        Log.e(TAG, "Failed to get selector token!");
                        replyToMessage(message, WifiManager.WRITE_CRED_TO_NFC_FAILED, WifiManager.ERROR);
                    }
                    break;
                case WifiManager.GET_CRED_FROM_NFC:     //Item 23
                    if (!mMtkWpsp2pnfcSupport) {
                        replyToMessage(message, WifiManager.GET_CRED_FROM_NFC_FAILED, WifiManager.ERROR);
                        break;
                    }
                    sendReadCredRequestToNfcBroadcast();
                    replyToMessage(message, WifiManager.GET_CRED_FROM_NFC_SUCCEEDED);
                    break;
                case WifiManager.GET_PIN_FROM_NFC:      //Item 25
                    if (!mMtkWpsp2pnfcSupport) {
                        replyToMessage(message, WifiManager.GET_PIN_FROM_NFC_FAILED, WifiManager.ERROR);
                        break;
                    }
                    sendReadPinRequestToNfcBroadcast();
                    replyToMessage(message, WifiManager.GET_PIN_FROM_NFC_SUCCEEDED);
                    break;
                case WifiMonitor.WPS_ER_ENROLLEE_ADD_EVENT:
                    if (!mMtkWpsp2pnfcSupport) {
                        break;
                    }
                    String event = (String) message.obj;
                    String[] tokens = event.split(" ");
                    if (tokens.length >= 3) {
                        mEnrolleeUuid = tokens[1];
                        mEnrolleeBssid = tokens[2];
                        Log.d(TAG, "WPS_ER_ENROLLEE_ADD_EVENT, mEnrolleeUuid:" + mEnrolleeUuid
                            + ", mEnrolleeBssid:" + mEnrolleeBssid + ", mWpsErMethod:" + mWpsErMethod
                            + ", mWaitingForEnrollee:" + mWaitingForEnrollee);
                        if (mWaitingForEnrollee) {
                            mWaitingForEnrollee = false;
                            mAlarmManager.cancel(mClearWaitFlagIntent);
                            if (mWpsErMethod == WpsInfo.PBC) {
                                wpsResult = mWifiConfigStore.startWpsErPbc(mEnrolleeUuid);
                            } else {
                                wpsResult = new WpsResult(Status.FAILURE);
                            }
                            if (wpsResult.status == Status.SUCCESS) {
                                transitionTo(mWpsRunningState);
                            } else {
                                Log.e(TAG, "Failed to start WPS ER!");
                            }
                            mEnrolleeUuid = null;
                            mEnrolleeBssid = null;
                        }
                    } else {
                        Log.e(TAG, "WPS_ER_ENROLLEE_ADD_EVENT format error, event:" + event);
                    }
                    break;
                case WifiMonitor.WPS_ER_AP_ADD_EVENT:
                    if (!mMtkWpsp2pnfcSupport) {
                        break;
                    }
                    event = (String) message.obj;
                    tokens = event.split(" ");
                    if (tokens.length < 3) {
                        Log.e(TAG, "WPS_ER_AP_ADD_EVENT format error, event:" + event);
                        break;
                    }
                    nfcPw = Settings.System.getInt(mContext.getContentResolver(), "nfc_pw", 0);
                    Log.d(TAG, "WPS_ER_AP_ADD_EVENT, erApUuid:" + tokens[1] + ", erApBssid:" + tokens[2]
                        + ", mErApPin:" + mErApPin + ", mLastBssid:" + mLastBssid + ", nfcPw:" + nfcPw);
                    if (!TextUtils.isEmpty(tokens[1]) && !TextUtils.isEmpty(tokens[2]) && tokens[2].equals(mLastBssid)) {
                        mErApUuid = tokens[1];
                        if (nfcPw == 0 && !TextUtils.isEmpty(mErApPin)) {
                            mWifiNative.wpsErLearn(mErApUuid, mErApPin);
                        }
                    }
                    break;
                case M_CMD_START_WPS_NFC_TAG_READ:
                    if (!mMtkWpsp2pnfcSupport) {
                        break;
                    }
                    mWifiNative.wpsNfcTagRead((String) message.obj);
                    if (mErApPin == null) {
                        mWifiConfigStore.loadConfiguredNetworks();
                    } else {
                        Log.d(TAG, "M_CMD_START_WPS_NFC_TAG_READ, mErApPin:" + mErApPin);
                    }
                    break;
                case M_CMD_HS_RECEIVED:
                    if (!mMtkWpsp2pnfcSupport) {
                        replyToMessage(message, WifiManager.WPS_FAILED, WifiManager.ERROR);
                        break;
                    }
                    mWifiNative.nfcRxHandoverToken((String) message.obj, false);
                    wpsResult = mWifiConfigStore.startWpsNfc();
                    if (wpsResult.status == Status.SUCCESS) {
                        replyToMessage(message, WifiManager.START_WPS_SUCCEEDED, wpsResult);
                        transitionTo(mWpsRunningState);
                    } else {
                        Log.e(TAG, "Failed to start WPS NFC!");
                        replyToMessage(message, WifiManager.WPS_FAILED, WifiManager.ERROR);
                    }
                    break;
                case M_CMD_HR_RECEIVED:
                    if (!mMtkWpsp2pnfcSupport) {
                        break;
                    }
                    mWifiNative.nfcRxHandoverToken((String) message.obj, true);
                    mWaitingForHrToken = false;
                    break;
                /** @} */
                ///M: ALPS02279279 [Google Issue] Cannot remove configured networks @{
                case M_CMD_FACTORY_RESET:
                    uid = message.arg1;
                    List<WifiConfiguration> networks = mWifiConfigStore.getConfiguredNetworks();
                    if (networks != null) {
                        for (WifiConfiguration c : networks) {
                            if (!mWifiConfigStore.canModifyNetwork(uid, c.networkId,
                                        /* onlyAnnotate */ false)) {
                                logw("Not authorized to remove network "
                                        + " cnid=" + c.networkId
                                        + " uid=" + uid);
                                continue;
                            }
                            ok = mWifiConfigStore.removeNetwork(c.networkId);
                            if (hasCustomizedAutoConnect()) {
                                mWifiConfigStore.removeDisconnectNetwork(c.networkId);
                                if (ok && c.networkId == mWifiInfo.getNetworkId()) {
                                    mDisconnectOperation = true;
                                    mScanForWeakSignal = false;
                                }
                            }
                        }
                        mWifiConfigStore.saveConfig();
                        logd("M_CMD_FACTORY_RESET, " + networks.size()
                                + " configured networks are removed");
                    } else {
                        loge("M_CMD_FACTORY_RESET networks is null");
                    }
                    break;
                ///@}
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    private void updateCapabilities(WifiConfiguration config) {
        if (config.ephemeral) {
            mNetworkCapabilities.removeCapability(
                    NetworkCapabilities.NET_CAPABILITY_TRUSTED);
        } else {
            mNetworkCapabilities.addCapability(
                    NetworkCapabilities.NET_CAPABILITY_TRUSTED);
        }
        mNetworkAgent.sendNetworkCapabilities(mNetworkCapabilities);
    }

    private class WifiNetworkAgent extends NetworkAgent {
        public WifiNetworkAgent(Looper l, Context c, String TAG, NetworkInfo ni,
                NetworkCapabilities nc, LinkProperties lp, int score) {
            super(l, c, TAG, ni, nc, lp, score);
        }
        protected void unwanted() {
            // Ignore if we're not the current networkAgent.
            if (this != mNetworkAgent) return;
            if (DBG) log("WifiNetworkAgent -> Wifi unwanted score "
                    + Integer.toString(mWifiInfo.score));
            unwantedNetwork(NETWORK_STATUS_UNWANTED_DISCONNECT);
        }

        @Override
        protected void networkStatus(int status) {
            if (this != mNetworkAgent) return;
            if (status == NetworkAgent.INVALID_NETWORK) {
                if (DBG) log("WifiNetworkAgent -> Wifi networkStatus invalid, score="
                        + Integer.toString(mWifiInfo.score));
                unwantedNetwork(NETWORK_STATUS_UNWANTED_VALIDATION_FAILED);
            } else if (status == NetworkAgent.VALID_NETWORK) {
                if (DBG && mWifiInfo != null) log("WifiNetworkAgent -> Wifi networkStatus valid, score= "
                        + Integer.toString(mWifiInfo.score));
                doNetworkStatus(status);
            }
        }

        @Override
        protected void saveAcceptUnvalidated(boolean accept) {
            if (this != mNetworkAgent) return;
            WifiStateMachine.this.sendMessage(CMD_ACCEPT_UNVALIDATED, accept ? 1 : 0);
        }

        @Override
        protected void preventAutomaticReconnect() {
            if (this != mNetworkAgent) return;
            unwantedNetwork(NETWORK_STATUS_UNWANTED_DISABLE_AUTOJOIN);
        }
    }

    void unwantedNetwork(int reason) {
        sendMessage(CMD_UNWANTED_NETWORK, reason);
    }

    void doNetworkStatus(int status) {
        sendMessage(CMD_NETWORK_STATUS, status);
    }

    // rfc4186 & rfc4187:
    // create Permanent Identity base on IMSI,
    // identity = usernam@realm
    // with username = prefix | IMSI
    // and realm is derived MMC/MNC tuple according 3GGP spec(TS23.003)
    private String buildIdentity(int eapMethod, String imsi, String mccMnc) {
        String mcc;
        String mnc;
        String prefix;

        if (imsi == null || imsi.isEmpty())
            return "";

        if (eapMethod == WifiEnterpriseConfig.Eap.SIM)
            prefix = "1";
        else if (eapMethod == WifiEnterpriseConfig.Eap.AKA)
            prefix = "0";
        else if (eapMethod == WifiEnterpriseConfig.Eap.AKA_PRIME)
            prefix = "6";
        else  // not a valide EapMethod
            return "";

        /* extract mcc & mnc from mccMnc */
        if (mccMnc != null && !mccMnc.isEmpty()) {
            mcc = mccMnc.substring(0, 3);
            mnc = mccMnc.substring(3);
            if (mnc.length() == 2)
                mnc = "0" + mnc;
        } else {
            // extract mcc & mnc from IMSI, assume mnc size is 3
            mcc = imsi.substring(0, 3);
            mnc = imsi.substring(3, 6);
        }

        return prefix + imsi + "@wlan.mnc" + mnc + ".mcc" + mcc + ".3gppnetwork.org";
    }

    boolean startScanForConfiguration(WifiConfiguration config, boolean restrictChannelList) {
        if (config == null)
            return false;

        // We are still seeing a fairly high power consumption triggered by autojoin scans
        // Hence do partial scans only for PSK configuration that are roamable since the
        // primary purpose of the partial scans is roaming.
        // Full badn scans with exponential backoff for the purpose or extended roaming and
        // network switching are performed unconditionally.
        ScanDetailCache scanDetailCache =
                mWifiConfigStore.getScanDetailCache(config);
        if (scanDetailCache == null
                || !config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)
                || scanDetailCache.size() > 6) {
            //return true but to not trigger the scan
            return true;
        }
        HashSet<Integer> channels = mWifiConfigStore.makeChannelList(config,
                ONE_HOUR_MILLI, restrictChannelList);
        if (channels != null && channels.size() != 0) {
            StringBuilder freqs = new StringBuilder();
            boolean first = true;
            for (Integer channel : channels) {
                if (!first)
                    freqs.append(",");
                freqs.append(channel.toString());
                first = false;
            }
            //if (DBG) {
            logd("starting scan for " + config.configKey() + " with " + freqs);
            //}
            // Call wifi native to start the scan
            if (startScanNative(
                    WifiNative.SCAN_WITHOUT_CONNECTION_SETUP,
                    freqs.toString(), SCAN_ALARM_SOURCE)) {
                // Only count battery consumption if scan request is accepted
                noteScanStart(SCAN_ALARM_SOURCE, null);
                messageHandlingStatus = MESSAGE_HANDLING_STATUS_OK;
            } else {
                // used for debug only, mark scan as failed
                messageHandlingStatus = MESSAGE_HANDLING_STATUS_HANDLING_ERROR;
            }
            return true;
        } else {
            if (DBG) logd("no channels for " + config.configKey());
            return false;
        }
    }

    void clearCurrentConfigBSSID(String dbg) {
        // Clear the bssid in the current config's network block
        WifiConfiguration config = getCurrentWifiConfiguration();
        if (config == null)
            return;
        clearConfigBSSID(config, dbg);
    }
    void clearConfigBSSID(WifiConfiguration config, String dbg) {
        if (config == null)
            return;
        if (DBG) {
            logd(dbg + " " + mTargetRoamBSSID + " config " + config.configKey()
                    + " config.bssid " + config.BSSID);
        }
        config.autoJoinBSSID = "any";
        config.BSSID = "any";
        if (DBG) {
           logd(dbg + " " + config.SSID
                    + " nid=" + Integer.toString(config.networkId));
        }
        mWifiConfigStore.saveWifiConfigBSSID(config);
    }

    class L2ConnectedState extends State {
        @Override
        public void enter() {
            if (DBG) loge(getName() + "\n");
            mRssiPollToken++;
            if (mEnableRssiPolling) {
                sendMessage(CMD_RSSI_POLL, mRssiPollToken, 0);
            }
            if (mNetworkAgent != null) {
                loge("Have NetworkAgent when entering L2Connected");
                setNetworkDetailedState(DetailedState.DISCONNECTED);
            }
            setNetworkDetailedState(DetailedState.CONNECTING);

            if (!TextUtils.isEmpty(mTcpBufferSizes)) {
                mLinkProperties.setTcpBufferSizes(mTcpBufferSizes);
            }
            mNetworkAgent = new WifiNetworkAgent(getHandler().getLooper(), mContext,
                    "WifiNetworkAgent", mNetworkInfo, mNetworkCapabilitiesFilter,
                    mLinkProperties, 60);

            // We must clear the config BSSID, as the wifi chipset may decide to roam
            // from this point on and having the BSSID specified in the network block would
            // cause the roam to faile and the device to disconnect
            clearCurrentConfigBSSID("L2ConnectedState");

            ///M: [Google Issue] AP will not response ARP when device uses static ip to connect
            if (mWifiConfigStore.isUsingStaticIp(mLastNetworkId)){
                loge("[Google Issue] Don't init IpReachabilityMonitor, "
                        + "when using static ip to connect");
            } else {
                try {
                    mIpReachabilityMonitor = new IpReachabilityMonitor(
                            mInterfaceName,
                            new IpReachabilityMonitor.Callback() {
                                @Override
                                public void notifyLost(InetAddress ip, String logMsg) {
                                    sendMessage(CMD_IP_REACHABILITY_LOST, logMsg);
                                }
                            });
                } catch (IllegalArgumentException e) {
                    Log.wtf("Failed to create IpReachabilityMonitor", e);
                }
            }

            ///M: ALPS02475594 clear flag
            if (DBG) {
                log("Reset mIsListeningIpReachabilityLost");
            }
            mIsListeningIpReachabilityLost = false;
        }

        @Override
        public void exit() {
            if (mIpReachabilityMonitor != null) {
                mIpReachabilityMonitor.stop();
                mIpReachabilityMonitor = null;
            }

            // This is handled by receiving a NETWORK_DISCONNECTION_EVENT in ConnectModeState
            // Bug: 15347363
            // For paranoia's sake, call handleNetworkDisconnect
            // only if BSSID is null or last networkId
            // is not invalid.
            if (DBG) {
                StringBuilder sb = new StringBuilder();
                sb.append("leaving L2ConnectedState state nid=" + Integer.toString(mLastNetworkId));
                if (mLastBssid !=null) {
                    sb.append(" ").append(mLastBssid);
                }
            }
            if (mLastBssid != null || mLastNetworkId != WifiConfiguration.INVALID_NETWORK_ID) {
                handleNetworkDisconnect();
            }
            ///M: NFC Float II @{
            if (mMtkWpsp2pnfcSupport) {
                mErApPin = null;
                mErApUuid = null;
            }
            /// @}
            ///M: ALPS01997294 clear driver roaming flag
            mIsNewAssociatedBssid = false;
        }
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch (message.what) {
              case DhcpStateMachine.CMD_PRE_DHCP_ACTION:
                  if (mMtkDhcpv6cWifi) {
                      if (!mPreDhcpSetupDone) {
                          handlePreDhcpSetup();
                      }
                      if (message.arg1 == DhcpStateMachine.DHCPV6) {
                          mDhcpV6StateMachine.sendMessage(
                                  DhcpStateMachine.CMD_PRE_DHCP_ACTION_COMPLETE);
                      } else {
                          mDhcpStateMachine.sendMessage(
                                  DhcpStateMachine.CMD_PRE_DHCP_ACTION_COMPLETE);
                      }
                  } else {
                      handlePreDhcpSetup();
                      mDhcpStateMachine.sendMessage(DhcpStateMachine.CMD_PRE_DHCP_ACTION_COMPLETE);
                  }
                  break;
              case DhcpStateMachine.CMD_POST_DHCP_ACTION:
                    if (mMtkDhcpv6cWifi) {
                        if (message.arg2 == DhcpStateMachine.DHCPV4) {
                            mDhcpV4Status = message.arg1;
                        } else if (message.arg2 == DhcpStateMachine.DHCPV6) {
                            mDhcpV6Status = message.arg1;
                        }
                        Log.d(TAG, "CMD_POST_DHCP_ACTION for:" + message.arg2 + ", mDhcpV4Status:" + mDhcpV4Status
                               + ", mDhcpV6Status:" + mDhcpV6Status);

                        handlePostDhcpSetup();

                        if (message.arg1 == DhcpStateMachine.DHCP_SUCCESS) {
                            Log.d(TAG, "DHCP succeed for " + (message.arg2 == DhcpStateMachine.DHCPV4 ? "V4" : "V6"));
                            if (message.arg2 == DhcpStateMachine.DHCPV6) {
                                handleSuccessfulIpV6Configuration(
                                        (DhcpResults) message.obj, M_CMD_DHCP_V6_SUCCESS);
                            } else {
                                handleIPv4Success(
                                        (DhcpResults) message.obj, DhcpStateMachine.DHCP_SUCCESS);
                            }
                        } else if (message.arg1 == DhcpStateMachine.DHCP_FAILURE) {
                            mWifiLogger.captureBugReportData(WifiLogger.REPORT_REASON_DHCP_FAILURE);
                            if (mDhcpV4Status == DhcpStateMachine.DHCP_FAILURE
                                && mDhcpV6Status == DhcpStateMachine.DHCP_FAILURE) {
                                Log.d(TAG, "DHCP failed!");
                                if (DBG) {
                                  int count = -1;
                                  WifiConfiguration config = getCurrentWifiConfiguration();
                                  if (config != null) {
                                      count = config.numConnectionFailures;
                                  }
                                  log("WifiStateMachine DHCP failure count=" + count);
                              }
                               handleIPv4Failure(DhcpStateMachine.DHCP_FAILURE);
                            }
                        }
                    } else {
                        handlePostDhcpSetup();
                        if (message.arg1 == DhcpStateMachine.DHCP_SUCCESS) {
                              if (DBG) log("DHCP successful");
                              handleIPv4Success((DhcpResults) message.obj, DhcpStateMachine.DHCP_SUCCESS);
                              // We advance to mConnectedState because handleIPv4Success will call
                              // updateLinkProperties, which then sends CMD_IP_CONFIGURATION_SUCCESSFUL.
                        } else if (message.arg1 == DhcpStateMachine.DHCP_FAILURE) {
                             mWifiLogger.captureBugReportData(
                                     WifiLogger.REPORT_REASON_DHCP_FAILURE);
                             if (DBG) {
                                  int count = -1;
                                  WifiConfiguration config = getCurrentWifiConfiguration();
                                  if (config != null) {
                                      count = config.numConnectionFailures;
                                  }
                                  log("DHCP failure count=" + count);
                              }
                              handleIPv4Failure(DhcpStateMachine.DHCP_FAILURE);
                              // As above, we transition to mDisconnectingState via updateLinkProperties.
                        }
                    }
                  break;
                case CMD_IP_CONFIGURATION_SUCCESSFUL:
                    handleSuccessfulIpConfiguration();
                    sendConnectedState();
                    transitionTo(mConnectedState);
                    break;
                case CMD_IP_CONFIGURATION_LOST:
                    // Get Link layer stats so that we get fresh tx packet counters.
                    getWifiLinkLayerStats(true);
                    handleIpConfigurationLost();
                    transitionTo(mDisconnectingState);
                    break;
                case CMD_IP_REACHABILITY_LOST:
                    ///M: ALPS02550356 Avoid frequently disconnect becasue of ip reachability lost @{
                    Log.d(TAG, "mIsListeningIpReachabilityLost: " + mIsListeningIpReachabilityLost);
                    if (mIsListeningIpReachabilityLost) {
                        if (DBG && message.obj != null) log((String) message.obj);
                        handleIpReachabilityLost();
                        transitionTo(mDisconnectingState);
                    } else {
                        Log.d(TAG, "Ignore CMD_IP_REACHABILITY_LOST");
                    }
                    ///@}
                    break;
                case CMD_DISCONNECT:
                    mWifiNative.disconnect();
                    transitionTo(mDisconnectingState);
                    break;
                case WifiP2pServiceImpl.DISCONNECT_WIFI_REQUEST:
                    if (message.arg1 == 1) {
                        mWifiNative.disconnect();
                        if (hasCustomizedAutoConnect()) {
                            mDisconnectOperation = true;
                        }
                        mTemporarilyDisconnectWifi = true;
                        transitionTo(mDisconnectingState);
                    }
                    break;
                case CMD_SET_OPERATIONAL_MODE:
                    if (message.arg1 != CONNECT_MODE) {
                        sendMessage(CMD_DISCONNECT);
                        deferMessage(message);
                        if (message.arg1 == SCAN_ONLY_WITH_WIFI_OFF_MODE) {
                            noteWifiDisabledWhileAssociated();
                        }
                    }
                    mWifiConfigStore.
                                setLastSelectedConfiguration(WifiConfiguration.INVALID_NETWORK_ID);
                    break;
                case CMD_SET_COUNTRY_CODE:
                    messageHandlingStatus = MESSAGE_HANDLING_STATUS_DEFERRED;
                    deferMessage(message);
                    break;
                case CMD_START_SCAN:
                    if (DBG) {
                        logd("CMD_START_SCAN source " + message.arg1
                              + " txSuccessRate="+String.format( "%.2f", mWifiInfo.txSuccessRate)
                              + " rxSuccessRate="+String.format( "%.2f", mWifiInfo.rxSuccessRate)
                              + " targetRoamBSSID=" + mTargetRoamBSSID
                              + " RSSI=" + mWifiInfo.getRssi());
                    }
                    if (message.arg1 == SCAN_ALARM_SOURCE) {
                        // Check if the CMD_START_SCAN message is obsolete (and thus if it should
                        // not be processed) and restart the scan if neede
                        if (!getEnableAutoJoinWhenAssociated()) {
                            return HANDLED;
                        }
                        boolean shouldScan = mScreenOn;

                        if (!checkAndRestartDelayedScan(message.arg2,
                                shouldScan,
                                mWifiConfigStore.wifiAssociatedShortScanIntervalMilli.get(),
                                null, null)) {
                            messageHandlingStatus = MESSAGE_HANDLING_STATUS_OBSOLETE;
                            logd("L2Connected CMD_START_SCAN source "
                                    + message.arg1
                                    + " " + message.arg2 + ", " + mDelayedScanCounter
                                    + " -> obsolete");
                            return HANDLED;
                        }
                        if (mP2pConnected.get()) {
                            logd("L2Connected CMD_START_SCAN source "
                                    + message.arg1
                                    + " " + message.arg2 + ", " + mDelayedScanCounter
                                    + " ignore because P2P is connected");
                            messageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                            return HANDLED;
                        }
                        boolean tryFullBandScan = false;
                        boolean restrictChannelList = false;
                        long now_ms = System.currentTimeMillis();
                        if (DBG) {
                            logd("CMD_START_SCAN with age="
                                    + Long.toString(now_ms - lastFullBandConnectedTimeMilli)
                                    + " interval=" + fullBandConnectedTimeIntervalMilli
                                    + " maxinterval=" + maxFullBandConnectedTimeIntervalMilli);
                        }
                        if (mWifiInfo != null) {
                            if (mWifiConfigStore.enableFullBandScanWhenAssociated.get() &&
                                    (now_ms - lastFullBandConnectedTimeMilli)
                                    > fullBandConnectedTimeIntervalMilli) {
                                if (DBG) {
                                    logd("CMD_START_SCAN try full band scan age="
                                         + Long.toString(now_ms - lastFullBandConnectedTimeMilli)
                                         + " interval=" + fullBandConnectedTimeIntervalMilli
                                         + " maxinterval=" + maxFullBandConnectedTimeIntervalMilli);
                                }
                                tryFullBandScan = true;
                            }

                            if (mWifiInfo.txSuccessRate >
                                    mWifiConfigStore.maxTxPacketForFullScans
                                    || mWifiInfo.rxSuccessRate >
                                    mWifiConfigStore.maxRxPacketForFullScans) {
                                // Too much traffic at the interface, hence no full band scan
                                if (DBG) {
                                    logd("CMD_START_SCAN " +
                                            "prevent full band scan due to pkt rate");
                                }
                                tryFullBandScan = false;
                            }
                            ///M: group scan enhancement
                            if ((mWifiInfo.is24GHz() && mWifiInfo.getLinkSpeed() < 6 &&
                                mWifiInfo.getRssi() < -70) || (mWifiInfo.is5GHz() &&
                                mWifiInfo.getLinkSpeed() <= 9 && mWifiInfo.getRssi() < -70)) {
                                // Don't scan if current link quality is low
                                restrictChannelList = true;
                                if (mWifiConfigStore.alwaysEnableScansWhileAssociated.get() == 0) {
                                    if (DBG) {
                                     logd("CMD_START_SCAN source " + message.arg1
                                        + " ...and ignore scans due to low link quality"
                                        + " tx=" + String.format("%.2f", mWifiInfo.txSuccessRate)
                                        + " rx=" + String.format("%.2f", mWifiInfo.rxSuccessRate));
                                    }
                                    messageHandlingStatus = MESSAGE_HANDLING_STATUS_REFUSED;
                                    return HANDLED;
                                }
                            }

                            if (mWifiInfo.txSuccessRate >
                                    mWifiConfigStore.maxTxPacketForPartialScans
                                    || mWifiInfo.rxSuccessRate >
                                    mWifiConfigStore.maxRxPacketForPartialScans) {
                                // Don't scan if lots of packets are being sent
                                restrictChannelList = true;
                                if (mWifiConfigStore.alwaysEnableScansWhileAssociated.get() == 0) {
                                    if (DBG) {
                                     logd("CMD_START_SCAN source " + message.arg1
                                        + " ...and ignore scans"
                                        + " tx=" + String.format("%.2f", mWifiInfo.txSuccessRate)
                                        + " rx=" + String.format("%.2f", mWifiInfo.rxSuccessRate));
                                    }
                                    messageHandlingStatus = MESSAGE_HANDLING_STATUS_REFUSED;
                                    return HANDLED;
                                }
                            }
                        }

                        WifiConfiguration currentConfiguration = getCurrentWifiConfiguration();
                        if (DBG) {
                            logd("CMD_START_SCAN full=" +
                                    tryFullBandScan);
                        }
                        if (currentConfiguration != null) {
                            if (fullBandConnectedTimeIntervalMilli
                                    < mWifiConfigStore.wifiAssociatedShortScanIntervalMilli.get()) {
                                // Sanity
                                fullBandConnectedTimeIntervalMilli
                                        = mWifiConfigStore.wifiAssociatedShortScanIntervalMilli.get();
                            }
                            if (tryFullBandScan) {
                                lastFullBandConnectedTimeMilli = now_ms;
                                if (fullBandConnectedTimeIntervalMilli
                                        < mWifiConfigStore.associatedFullScanMaxIntervalMilli) {
                                    // Increase the interval
                                    fullBandConnectedTimeIntervalMilli
                                            = fullBandConnectedTimeIntervalMilli
                                            * mWifiConfigStore.associatedFullScanBackoff.get() / 8;

                                    if (DBG) {
                                        logd("CMD_START_SCAN bump interval ="
                                        + fullBandConnectedTimeIntervalMilli);
                                    }
                                }
                                handleScanRequest(
                                        WifiNative.SCAN_WITHOUT_CONNECTION_SETUP, message);
                            } else {
                                if (!startScanForConfiguration(
                                        currentConfiguration, restrictChannelList)) {
                                    if (DBG) {
                                        logd("starting scan, " +
                                                " did not find channels -> full");
                                    }
                                    lastFullBandConnectedTimeMilli = now_ms;
                                    if (fullBandConnectedTimeIntervalMilli
                                            < mWifiConfigStore.associatedFullScanMaxIntervalMilli) {
                                        // Increase the interval
                                        fullBandConnectedTimeIntervalMilli
                                                = fullBandConnectedTimeIntervalMilli
                                                * mWifiConfigStore.associatedFullScanBackoff.get() / 8;

                                        if (DBG) {
                                            logd("CMD_START_SCAN bump interval ="
                                                    + fullBandConnectedTimeIntervalMilli);
                                        }
                                    }
                                    handleScanRequest(
                                                WifiNative.SCAN_WITHOUT_CONNECTION_SETUP, message);
                                }
                            }

                        } else {
                            logd("CMD_START_SCAN : connected mode and no configuration");
                            messageHandlingStatus = MESSAGE_HANDLING_STATUS_HANDLING_ERROR;
                        }
                    } else {
                        // Not scan alarm source
                        return NOT_HANDLED;
                    }
                    break;
                    /// Ignore connection to same network
                case WifiManager.CONNECT_NETWORK:
                    int netId = message.arg1;
                    if (mWifiInfo.getNetworkId() == netId) {
                        ///M: ALPS01821926 apk need event return
                        replyToMessage(message, WifiManager.CONNECT_NETWORK_SUCCEEDED);
                        break;
                    }
                    return NOT_HANDLED;
                    /* Ignore */
                case WifiMonitor.NETWORK_CONNECTION_EVENT:
                    ///M: ALPS02550356 Encounter FW roaming@{
                    Log.d(TAG, "mLastBssid:" + mLastBssid + ", newBssid:" + (String) message.obj
                            + ", mIsNewAssociatedBssid:" + mIsNewAssociatedBssid);

                    if (mLastBssid != null && message.obj != null
                        && mLastBssid.equals(message.obj) && !mIsNewAssociatedBssid) {
                        break;
                    }
                    mIsNewAssociatedBssid = false;

                    ipReachabilityMonitorCount++;
                    Log.d(TAG, "driver roaming, start to listen ip reachability lost in 3~10 sec, " +
                            "counter: " + ipReachabilityMonitorCount);
                    sendMessageDelayed(obtainMessage(M_CMD_IP_REACHABILITY_MONITOR_TIMER_3s,
                                ipReachabilityMonitorCount, 0), IP_REACHABILITY_MONITOR_TIMER_MSEC_3s);
                    sendMessageDelayed(obtainMessage(M_CMD_IP_REACHABILITY_MONITOR_TIMER_10s,
                                ipReachabilityMonitorCount, 0), IP_REACHABILITY_MONITOR_TIMER_MSEC_10s);
                    ///@}
                    break;
                case CMD_RSSI_POLL:
                    if (message.arg1 == mRssiPollToken) {
                        if (mWifiConfigStore.enableChipWakeUpWhenAssociated.get()) {
                            if (VVDBG) log(" get link layer stats " + mWifiLinkLayerStatsSupported);
                            WifiLinkLayerStats stats = getWifiLinkLayerStats(VDBG);
                            if (stats != null) {
                                // Sanity check the results provided by driver
                                if (mWifiInfo.getRssi() != WifiInfo.INVALID_RSSI
                                        && (stats.rssi_mgmt == 0
                                        || stats.beacon_rx == 0)) {
                                    stats = null;
                                }
                            }
                            // Get Info and continue polling
                            fetchRssiLinkSpeedAndFrequencyNative();
                            calculateWifiScore(stats);
                            //M: add a broadcast for link layer statistic test
                            if (stats != null) {
                                if (VDBG) {
                                    sendLinkLayerStatsBroadcast(stats.toString());
                                }
                            }
                        }
                        sendMessageDelayed(obtainMessage(CMD_RSSI_POLL,
                                mRssiPollToken, 0), POLL_RSSI_INTERVAL_MSECS);

                        if (DBG) sendRssiChangeBroadcast(mWifiInfo.getRssi());
                    } else {
                        // Polling has completed
                    }
                    break;
                case CMD_ENABLE_RSSI_POLL:
                    cleanWifiScore();
                    if (mWifiConfigStore.enableRssiPollWhenAssociated.get()) {
                        mEnableRssiPolling = (message.arg1 == 1);
                    } else {
                        mEnableRssiPolling = false;
                    }
                    mRssiPollToken++;
                    if (mEnableRssiPolling) {
                        // First poll
                        fetchRssiLinkSpeedAndFrequencyNative();
                        sendMessageDelayed(obtainMessage(CMD_RSSI_POLL,
                                mRssiPollToken, 0), POLL_RSSI_INTERVAL_MSECS);
                    }
                    break;
                case WifiManager.RSSI_PKTCNT_FETCH:
                    RssiPacketCountInfo info = new RssiPacketCountInfo();
                    fetchRssiLinkSpeedAndFrequencyNative();
                    info.rssi = mWifiInfo.getRssi();
                    ///M: poor link
                    info.mLinkspeed = mWifiInfo.getLinkSpeed();
                    fetchPktcntNative(info);
                    replyToMessage(message, WifiManager.RSSI_PKTCNT_FETCH_SUCCEEDED, info);
                    break;
                case CMD_DELAYED_NETWORK_DISCONNECT:
                    if (!linkDebouncing && mWifiConfigStore.enableLinkDebouncing) {

                        // Ignore if we are not debouncing
                        logd("CMD_DELAYED_NETWORK_DISCONNECT and not debouncing - ignore "
                                + message.arg1);
                        return HANDLED;
                    } else {
                        logd("CMD_DELAYED_NETWORK_DISCONNECT and debouncing - disconnect "
                                + message.arg1);

                        linkDebouncing = false;
                        // If we are still debouncing while this message comes,
                        // it means we were not able to reconnect within the alloted time
                        // = LINK_FLAPPING_DEBOUNCE_MSEC
                        // and thus, trigger a real disconnect
                        handleNetworkDisconnect();
                        transitionTo(mDisconnectedState);
                    }
                    break;
                case CMD_ASSOCIATED_BSSID:
                    if ((String) message.obj == null) {
                        logw("Associated command w/o BSSID");
                        break;
                    }
                    ///M: ALPS01940840 driver roaming should record @{
                    if (mLastBssid != null && !mLastBssid.equals(message.obj)){
                        mIsNewAssociatedBssid = true;
                    }
                    ///@}
                    mLastBssid = (String) message.obj;
                    if (mLastBssid != null
                            && (mWifiInfo.getBSSID() == null
                            || !mLastBssid.equals(mWifiInfo.getBSSID()))) {
                        mWifiInfo.setBSSID((String) message.obj);
                        sendNetworkStateChangeBroadcast(mLastBssid);
                    }
                    break;
                ///M:
                case M_CMD_GET_WIFI_STATUS:
                    String answer = mWifiNative.status();
                    replyToMessage(message, message.what, answer);
                    break;
                case WifiManager.START_PPPOE:
                    if (!mMtkCtpppoe) {
                        replyToMessage(message, WifiManager.START_PPPOE_FAILED, WifiManager.ERROR);
                        break;
                    }
                    Log.d(TAG, "mPppoeInfo.status:" + mPppoeInfo.status + ", config:" + (PPPOEConfig) message.obj);
                    if (mPppoeInfo.status == PPPOEInfo.Status.ONLINE) {
                        replyToMessage(message, WifiManager.START_PPPOE_SUCCEEDED);
                        sendPppoeCompletedBroadcast(WifiManager.PPPOE_STATUS_ALREADY_ONLINE, -1);
                    } else {
                        mPppoeConfig = (PPPOEConfig) message.obj;
                        mUsingPppoe = true;
                        if (mPppoeHandler == null) {
                            HandlerThread pppoeThread = new HandlerThread("PPPoE Handler Thread");
                            pppoeThread.start();
                            mPppoeHandler = new PppoeHandler(pppoeThread.getLooper(), WifiStateMachine.this);
                        }
                        mPppoeHandler.sendEmptyMessage(EVENT_START_PPPOE);
                        replyToMessage(message, WifiManager.START_PPPOE_SUCCEEDED);
                    }
                    break;
                case EVENT_PPPOE_SUCCEEDED:
                    handleSuccessfulPppoeConfiguration((DhcpResults) message.obj);
                    break;
                ///M: ALPS02550356 Ip reachability monitor timer time out@{
                case M_CMD_IP_REACHABILITY_MONITOR_TIMER_10s:
                    if (message.arg1 == ipReachabilityMonitorCount) {
                        Log.d(TAG, "Ip reachability monitor timer time out, count: "
                                + ipReachabilityMonitorCount);
                        mIsListeningIpReachabilityLost = false;
                    } else {
                        Log.d(TAG, "Ip reachability monitor count mismatch, count: "
                                + ipReachabilityMonitorCount + ", arg1: " + message.arg1);
                    }
                    break;
                case M_CMD_IP_REACHABILITY_MONITOR_TIMER_3s:
                    //request IpReachabilityMonitor to probe for detecting gateway change
                    if (mIpReachabilityMonitor != null) {
                        mIpReachabilityMonitor.probeAll();
                    }
                    Log.d(TAG, "3 sec times up, start listening ip reachability lost for 7s");
                    mIsListeningIpReachabilityLost = true;
                    break;
                ///@}
                default:
                    return NOT_HANDLED;
            }

            return HANDLED;
        }
    }

    class ObtainingIpState extends State {
        @Override
        public void enter() {
            if (DBG) loge(getName() + "\n");
            if (DBG) {
                String key = "";
                if (getCurrentWifiConfiguration() != null) {
                    key = getCurrentWifiConfiguration().configKey();
                }
                log("enter ObtainingIpState netId=" + Integer.toString(mLastNetworkId)
                        + " " + key + " "
                        + " roam=" + mAutoRoaming
                        + " static=" + mWifiConfigStore.isUsingStaticIp(mLastNetworkId)
                        + " watchdog= " + obtainingIpWatchdogCount);
            }

            // Reset link Debouncing, indicating we have successfully re-connected to the AP
            // We might still be roaming
            linkDebouncing = false;

            // Send event to CM & network change broadcast
            setNetworkDetailedState(DetailedState.OBTAINING_IPADDR);

            // We must clear the config BSSID, as the wifi chipset may decide to roam
            // from this point on and having the BSSID specified in the network block would
            // cause the roam to faile and the device to disconnect
            clearCurrentConfigBSSID("ObtainingIpAddress");

            try {
                mNwService.enableIpv6(mInterfaceName);
            } catch (RemoteException re) {
                loge("Failed to enable IPv6: " + re);
            } catch (IllegalStateException e) {
                loge("Failed to enable IPv6: " + e);
            }
            if (!mWifiConfigStore.isUsingStaticIp(mLastNetworkId)) {
                Log.d(TAG, "Supplicant state is " + mWifiInfo.getSupplicantState() + " before start DHCP.");
                if (isRoaming()) {
                    renewDhcp();
                } else {
                    // Remove any IP address on the interface in case we're switching from static
                    // IP configuration to DHCP. This is safe because if we get here when not
                    // roaming, we don't have a usable address.
                    clearIPv4Address(mInterfaceName);
                    startDhcp();
                }
                obtainingIpWatchdogCount++;
                logd("Start Dhcp Watchdog " + obtainingIpWatchdogCount);
                // Get Link layer stats so as we get fresh tx packet counters
                getWifiLinkLayerStats(true);
                sendMessageDelayed(obtainMessage(CMD_OBTAINING_IP_ADDRESS_WATCHDOG_TIMER,
                        obtainingIpWatchdogCount, 0), OBTAINING_IP_ADDRESS_GUARD_TIMER_MSEC);
            } else {
                // stop any running dhcp before assigning static IP
                stopDhcp();
                StaticIpConfiguration config = mWifiConfigStore.getStaticIpConfiguration(
                        mLastNetworkId);
                if (config.ipAddress == null) {
                    logd("Static IP lacks address");
                    sendMessage(CMD_STATIC_IP_FAILURE);
                } else {
                    InterfaceConfiguration ifcg = new InterfaceConfiguration();
                    ifcg.setLinkAddress(config.ipAddress);
                    ifcg.setInterfaceUp();
                    try {
                        mNwService.setInterfaceConfig(mInterfaceName, ifcg);
                        if (DBG) log("Static IP configuration succeeded");
                        // M: ALPS01773535 Remove route due to setInterfaceConfig
                        for (RouteInfo rt : mLinkProperties.getAllRoutes()) {
                            mLinkProperties.removeRoute(rt);
                        }
                        if (DBG) log("Force update mLinkProperties:" + mLinkProperties);
                        if (mNetworkAgent != null) mNetworkAgent.sendLinkProperties(mLinkProperties);
                        DhcpResults dhcpResults = new DhcpResults(config);
                        sendMessage(CMD_STATIC_IP_SUCCESS, dhcpResults);
                    } catch (RemoteException re) {
                        loge("Static IP configuration failed: " + re);
                        sendMessage(CMD_STATIC_IP_FAILURE);
                    } catch (IllegalStateException e) {
                        loge("Static IP configuration failed: " + e);
                        sendMessage(CMD_STATIC_IP_FAILURE);
                    }
                }
            }
        }
      @Override
      public boolean processMessage(Message message) {
          logStateAndMessage(message, getClass().getSimpleName());

          switch(message.what) {
              case CMD_STATIC_IP_SUCCESS:
                  handleIPv4Success((DhcpResults) message.obj, CMD_STATIC_IP_SUCCESS);
                  break;
              case CMD_STATIC_IP_FAILURE:
                  handleIPv4Failure(CMD_STATIC_IP_FAILURE);
                  break;
              case CMD_AUTO_CONNECT:
              case CMD_AUTO_ROAM:
                  messageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                  break;
              case WifiManager.SAVE_NETWORK:
              case WifiStateMachine.CMD_AUTO_SAVE_NETWORK:
                  messageHandlingStatus = MESSAGE_HANDLING_STATUS_DEFERRED;
                  deferMessage(message);
                  break;
                  /* Defer any power mode changes since we must keep active power mode at DHCP */
              case CMD_SET_HIGH_PERF_MODE:
                  messageHandlingStatus = MESSAGE_HANDLING_STATUS_DEFERRED;
                  deferMessage(message);
                  break;
                  /* Defer scan request since we should not switch to other channels at DHCP */
              case CMD_START_SCAN:
                  messageHandlingStatus = MESSAGE_HANDLING_STATUS_DEFERRED;
                  deferMessage(message);
                  break;
              case CMD_OBTAINING_IP_ADDRESS_WATCHDOG_TIMER:
                  if (message.arg1 == obtainingIpWatchdogCount) {
                      logd("ObtainingIpAddress: Watchdog Triggered, count="
                              + obtainingIpWatchdogCount);
                      handleIpConfigurationLost();
                      transitionTo(mDisconnectingState);
                      break;
                  }
                  messageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                  break;
              default:
                  return NOT_HANDLED;
          }
          return HANDLED;
      }
    }

    // Note: currently, this state is never used, because WifiWatchdogStateMachine unconditionally
    // sets mPoorNetworkDetectionEnabled to false.
    class VerifyingLinkState extends State {
        @Override
        public void enter() {
            loge(getName() + " enter");
            setNetworkDetailedState(DetailedState.VERIFYING_POOR_LINK);
            mWifiConfigStore.updateStatus(mLastNetworkId, DetailedState.VERIFYING_POOR_LINK);
            sendNetworkStateChangeBroadcast(mLastBssid);
            // End roaming
            mAutoRoaming = WifiAutoJoinController.AUTO_JOIN_IDLE;
            /** M: NFC Float II @{ */
            if (mMtkWpsp2pnfcSupport) {
                Log.d(TAG, "Enter VerifyingLinkState, mErApPin:" + mErApPin);
                if (mErApPin != null) {
                    mWifiNative.startWpsEr();
                }
            }
            /** @} */
        }
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch (message.what) {
                case WifiWatchdogStateMachine.POOR_LINK_DETECTED:
                    // Stay here
                    log(getName() + " POOR_LINK_DETECTED: no transition");
                    break;
                case WifiWatchdogStateMachine.GOOD_LINK_DETECTED:
                    log(getName() + " GOOD_LINK_DETECTED: transition to CONNECTED");
                    sendConnectedState();
                    transitionTo(mConnectedState);
                    break;
                default:
                    if (DBG) log(getName() + " what=" + message.what + " NOT_HANDLED");
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    private void sendConnectedState() {
        // If this network was explicitly selected by the user, evaluate whether to call
        // explicitlySelected() so the system can treat it appropriately.
        WifiConfiguration config = getCurrentWifiConfiguration();
        if (mWifiConfigStore.isLastSelectedConfiguration(config)) {
            boolean prompt = mWifiConfigStore.checkConfigOverridePermission(config.lastConnectUid);
            if (DBG) {
                log("Network selected by UID " + config.lastConnectUid + " prompt=" + prompt);
            }
            if (prompt) {
                // Selected by the user via Settings or QuickSettings. If this network has Internet
                // access, switch to it. Otherwise, switch to it only if the user confirms that they
                // really want to switch, or has already confirmed and selected "Don't ask again".
                if (DBG) {
                    log("explictlySelected acceptUnvalidated=" + config.noInternetAccessExpected);
                }
                mNetworkAgent.explicitlySelected(config.noInternetAccessExpected);
            }
        }

        setNetworkDetailedState(DetailedState.CONNECTED);
        mWifiConfigStore.updateStatus(mLastNetworkId, DetailedState.CONNECTED);
        sendNetworkStateChangeBroadcast(mLastBssid);
    }

    class RoamingState extends State {
        boolean mAssociated;
        @Override
        public void enter() {
            loge(getName() + " enter");
            if (DBG) {
                log("RoamingState Enter"
                        + " mScreenOn=" + mScreenOn );
            }
            setScanAlarm(false);

            // Make sure we disconnect if roaming fails
            roamWatchdogCount++;
            logd("Start Roam Watchdog " + roamWatchdogCount);
            sendMessageDelayed(obtainMessage(CMD_ROAM_WATCHDOG_TIMER,
                    roamWatchdogCount, 0), ROAM_GUARD_TIMER_MSEC);
            mAssociated = false;
        }
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());
            WifiConfiguration config;
            switch (message.what) {
                case CMD_IP_CONFIGURATION_LOST:
                    config = getCurrentWifiConfiguration();
                    if (config != null) {
                        mWifiLogger.captureBugReportData(WifiLogger.REPORT_REASON_AUTOROAM_FAILURE);
                        mWifiConfigStore.noteRoamingFailure(config,
                                WifiConfiguration.ROAMING_FAILURE_IP_CONFIG);
                    }
                    return NOT_HANDLED;
                case WifiWatchdogStateMachine.POOR_LINK_DETECTED:
                    if (DBG) log("Roaming and Watchdog reports poor link -> ignore");
                    return HANDLED;
                case CMD_UNWANTED_NETWORK:
                    if (DBG) log("Roaming and CS doesnt want the network -> ignore");
                    return HANDLED;
                case CMD_SET_OPERATIONAL_MODE:
                    if (message.arg1 != CONNECT_MODE) {
                        deferMessage(message);
                    }
                    break;
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                    /**
                     * If we get a SUPPLICANT_STATE_CHANGE_EVENT indicating a DISCONNECT
                     * before NETWORK_DISCONNECTION_EVENT
                     * And there is an associated BSSID corresponding to our target BSSID, then
                     * we have missed the network disconnection, transition to mDisconnectedState
                     * and handle the rest of the events there.
                     */
                    StateChangeResult stateChangeResult = (StateChangeResult) message.obj;
                    if (stateChangeResult.state == SupplicantState.DISCONNECTED
                            || stateChangeResult.state == SupplicantState.INACTIVE
                            || stateChangeResult.state == SupplicantState.INTERFACE_DISABLED) {
                        if (DBG) {
                            log("STATE_CHANGE_EVENT in roaming state "
                                    + stateChangeResult.toString() );
                        }
                        if (stateChangeResult.BSSID != null
                                && stateChangeResult.BSSID.equals(mTargetRoamBSSID)) {
                            handleNetworkDisconnect();
                            transitionTo(mDisconnectedState);
                        }
                    }
                    if (stateChangeResult.state == SupplicantState.ASSOCIATED) {
                        // We completed the layer2 roaming part
                        mAssociated = true;
                        if (stateChangeResult.BSSID != null) {
                            mTargetRoamBSSID = (String) stateChangeResult.BSSID;
                        }
                    }
                    break;
                case CMD_ROAM_WATCHDOG_TIMER:
                    if (roamWatchdogCount == message.arg1) {
                        if (DBG) log("roaming watchdog! -> disconnect");
                        mRoamFailCount++;
                        handleNetworkDisconnect();
                        mWifiNative.disconnect();
                        transitionTo(mDisconnectedState);
                    }
                    break;
               case WifiMonitor.NETWORK_CONNECTION_EVENT:
                   if (mAssociated) {
                       if (DBG) log("roaming and Network connection established");
                       mLastNetworkId = message.arg1;
                       mLastBssid = (String) message.obj;
                       mWifiInfo.setBSSID(mLastBssid);
                       mWifiInfo.setNetworkId(mLastNetworkId);
                       mWifiConfigStore.handleBSSIDBlackList(mLastNetworkId, mLastBssid, true);
                       sendNetworkStateChangeBroadcast(mLastBssid);
                       transitionTo(mObtainingIpState);
                   } else {
                       messageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                   }
                   break;
               case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                   // Throw away but only if it corresponds to the network we're roaming to
                   String bssid = (String)message.obj;
                   if (true) {
                       String target = "";
                       if (mTargetRoamBSSID != null) target = mTargetRoamBSSID;
                       log("NETWORK_DISCONNECTION_EVENT in roaming state"
                               + " BSSID=" + bssid
                               + " target=" + target);
                   }
                   if (bssid != null && bssid.equals(mTargetRoamBSSID)) {
                       handleNetworkDisconnect();
                       transitionTo(mDisconnectedState);
                   }
                   break;
                case WifiMonitor.SSID_TEMP_DISABLED:
                    // Auth error while roaming
                    logd("SSID_TEMP_DISABLED nid=" + Integer.toString(mLastNetworkId)
                            + " id=" + Integer.toString(message.arg1)
                            + " isRoaming=" + isRoaming()
                            + " roam=" + Integer.toString(mAutoRoaming));
                    if (message.arg1 == mLastNetworkId) {
                        config = getCurrentWifiConfiguration();
                        if (config != null) {
                            mWifiLogger.captureBugReportData(
                                    WifiLogger.REPORT_REASON_AUTOROAM_FAILURE);
                            mWifiConfigStore.noteRoamingFailure(config,
                                    WifiConfiguration.ROAMING_FAILURE_AUTH_FAILURE);
                        }
                        handleNetworkDisconnect();
                        transitionTo(mDisconnectingState);
                    }
                    return NOT_HANDLED;
                case CMD_START_SCAN:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        @Override
        public void exit() {
            logd("WifiStateMachine: Leaving Roaming state");
        }
    }

    class ConnectedState extends State {
        @Override
        public void enter() {
            String address;
            updateDefaultRouteMacAddress(1000);
            if (DBG) {
                log("Enter ConnectedState "
                       + " mScreenOn=" + mScreenOn
                       + " scanperiod="
                       + Integer.toString(mWifiConfigStore.wifiAssociatedShortScanIntervalMilli.get())
                       + " useGscan=" + mHalBasedPnoDriverSupported + "/"
                        + mWifiConfigStore.enableHalBasedPno.get()
                        + " mHalBasedPnoEnableInDevSettings " + mHalBasedPnoEnableInDevSettings);
            }
            if (mMtkCtpppoe) {
                Log.d(TAG, "Enter ConnectedState, mPppoeInfo.status:" + mPppoeInfo.status);
                if (mPppoeInfo.status == PPPOEInfo.Status.ONLINE) {
                    sendMessageDelayed(EVENT_UPDATE_DNS, UPDATE_DNS_DELAY_MS);
                }
            }
            ///M: add mAutoJoinScanWhenConnected
            if (mScreenOn
                    && getEnableAutoJoinWhenAssociated()) {
                if (useHalBasedAutoJoinOffload()) {
                    startGScanConnectedModeOffload("connectedEnter");
                } else {
                    // restart scan alarm
                    startDelayedScan(mWifiConfigStore.wifiAssociatedShortScanIntervalMilli.get(),
                            null, null);
                }
            }
            registerConnected();
            lastConnectAttemptTimestamp = 0;
            targetWificonfiguration = null;
            // Paranoia
            linkDebouncing = false;

            // Not roaming anymore
            mAutoRoaming = WifiAutoJoinController.AUTO_JOIN_IDLE;

            if (testNetworkDisconnect) {
                testNetworkDisconnectCounter++;
                logd("ConnectedState Enter start disconnect test " +
                        testNetworkDisconnectCounter);
                sendMessageDelayed(obtainMessage(CMD_TEST_NETWORK_DISCONNECT,
                        testNetworkDisconnectCounter, 0), 15000);
            }

            // Reenable all networks, allow for hidden networks to be scanned
            mWifiConfigStore.enableAllNetworks();

            mLastDriverRoamAttempt = 0;

            //startLazyRoam();

        }
        @Override
        public boolean processMessage(Message message) {
            WifiConfiguration config = null;
            logStateAndMessage(message, getClass().getSimpleName());

            switch (message.what) {
                case CMD_RESTART_AUTOJOIN_OFFLOAD:
                    if ( (int)message.arg2 < mRestartAutoJoinOffloadCounter ) {
                        messageHandlingStatus = MESSAGE_HANDLING_STATUS_OBSOLETE;
                        return HANDLED;
                    }
                    /* If we are still in Disconnected state after having discovered a valid
                     * network this means autojoin didnt managed to associate to the network,
                     * then restart PNO so as we will try associating to it again.
                     */
                    if (useHalBasedAutoJoinOffload()) {
                        if (mGScanStartTimeMilli == 0) {
                            // If offload is not started, then start it...
                            startGScanConnectedModeOffload("connectedRestart");
                        } else {
                            // If offload is already started, then check if we need to increase
                            // the scan period and restart the Gscan
                            long now = System.currentTimeMillis();
                            if (mGScanStartTimeMilli != 0 && now > mGScanStartTimeMilli
                                    && ((now - mGScanStartTimeMilli)
                                    > DISCONNECTED_SHORT_SCANS_DURATION_MILLI)
                                && (mGScanPeriodMilli
                                    < mWifiConfigStore.wifiDisconnectedLongScanIntervalMilli.get()))
                            {
                                startConnectedGScan("Connected restart gscan");
                            }
                        }
                    }
                    break;
                case CMD_UPDATE_ASSOCIATED_SCAN_PERMISSION:
                    updateAssociatedScanPermission();
                    break;
                case WifiWatchdogStateMachine.POOR_LINK_DETECTED:
                    if (DBG) log("Watchdog reports poor link");
                    try {
                        mNwService.disableIpv6(mInterfaceName);
                    } catch (RemoteException re) {
                        loge("Failed to disable IPv6: " + re);
                    } catch (IllegalStateException e) {
                        loge("Failed to disable IPv6: " + e);
                    }

                    transitionTo(mVerifyingLinkState);
                    break;
                case CMD_UNWANTED_NETWORK:
                    if (message.arg1 == NETWORK_STATUS_UNWANTED_DISCONNECT) {
                        mWifiConfigStore.handleBadNetworkDisconnectReport(mLastNetworkId, mWifiInfo);
                        mWifiNative.disconnect();
                        if (hasCustomizedAutoConnect()) {
                            mDisconnectOperation = true;
                        }
                        transitionTo(mDisconnectingState);
                    } else if (message.arg1 == NETWORK_STATUS_UNWANTED_DISABLE_AUTOJOIN ||
                            message.arg1 == NETWORK_STATUS_UNWANTED_VALIDATION_FAILED) {
                        if (!hasCustomizedAutoConnect()) {
                            config = getCurrentWifiConfiguration();
                            if (config != null) {
                                // Disable autojoin
                                if (message.arg1 == NETWORK_STATUS_UNWANTED_DISABLE_AUTOJOIN) {
                                    config.validatedInternetAccess = false;
                                    // Clear last-selected status, as being last-selected also
                                    // avoids disabling auto-join.
                                    if (mWifiConfigStore.isLastSelectedConfiguration(config)) {
                                        mWifiConfigStore.setLastSelectedConfiguration(
                                            WifiConfiguration.INVALID_NETWORK_ID);
                                    }
                                }
                                config.numNoInternetAccessReports += 1;
                                config.dirty = true;
                                mWifiConfigStore.writeKnownNetworkHistory(false);
                            }
                        } else {
                            log("Skip unwanted operation because of customization!");
                        }
                    }
                    return HANDLED;
                case CMD_NETWORK_STATUS:
                    if (message.arg1 == NetworkAgent.VALID_NETWORK) {
                        config = getCurrentWifiConfiguration();
                        if (config != null) {
                            if (!config.validatedInternetAccess
                                    || config.numNoInternetAccessReports != 0) {
                                config.dirty = true;
                            }
                            // re-enable autojoin
                            config.numNoInternetAccessReports = 0;
                            config.validatedInternetAccess = true;
                            mWifiConfigStore.writeKnownNetworkHistory(false);
                        }
                    }
                    return HANDLED;
                case CMD_ACCEPT_UNVALIDATED:
                    boolean accept = (message.arg1 != 0);
                    config = getCurrentWifiConfiguration();
                    if (config != null) {
                        config.noInternetAccessExpected = accept;
                    }
                    return HANDLED;
                case CMD_TEST_NETWORK_DISCONNECT:
                    // Force a disconnect
                    if (message.arg1 == testNetworkDisconnectCounter) {
                        mWifiNative.disconnect();
                        if (hasCustomizedAutoConnect()) {
                            mDisconnectOperation = true;
                        }
                    }
                    break;
                case CMD_ASSOCIATED_BSSID:
                    // ASSOCIATING to a new BSSID while already connected, indicates
                    // that driver is roaming
                    mLastDriverRoamAttempt = System.currentTimeMillis();
                    String toBSSID = (String)message.obj;
                    if (toBSSID != null && !toBSSID.equals(mWifiInfo.getBSSID())) {
                        mWifiConfigStore.driverRoamedFrom(mWifiInfo);
                    }
                    return NOT_HANDLED;
                case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                    long lastRoam = 0;
                    if (mLastDriverRoamAttempt != 0) {
                        // Calculate time since last driver roam attempt
                        lastRoam = System.currentTimeMillis() - mLastDriverRoamAttempt;
                        mLastDriverRoamAttempt = 0;
                    }
                    if (unexpectedDisconnectedReason(message.arg2)) {
                        mWifiLogger.captureBugReportData(
                                WifiLogger.REPORT_REASON_UNEXPECTED_DISCONNECT);
                    }
                    config = getCurrentWifiConfiguration();
                    if (mScreenOn
                            && !linkDebouncing
                            && config != null
                            && config.autoJoinStatus == WifiConfiguration.AUTO_JOIN_ENABLED
                            && !mWifiConfigStore.isLastSelectedConfiguration(config)
                            && (message.arg2 != 3 /* reason cannot be 3, i.e. locally generated */
                                || (lastRoam > 0 && lastRoam < 2000) /* unless driver is roaming */)
                            && ((ScanResult.is24GHz(mWifiInfo.getFrequency())
                                    && mWifiInfo.getRssi() >
                                    WifiConfiguration.BAD_RSSI_24)
                                    || (ScanResult.is5GHz(mWifiInfo.getFrequency())
                                    && mWifiInfo.getRssi() >
                                    WifiConfiguration.BAD_RSSI_5))
                            && !hasCustomizedAutoConnect()) {
                        // Start de-bouncing the L2 disconnection:
                        // this L2 disconnection might be spurious.
                        // Hence we allow 7 seconds for the state machine to try
                        // to reconnect, go thru the
                        // roaming cycle and enter Obtaining IP address
                        // before signalling the disconnect to ConnectivityService and L3
                        startScanForConfiguration(getCurrentWifiConfiguration(), false);
                        linkDebouncing = true;

                        sendMessageDelayed(obtainMessage(CMD_DELAYED_NETWORK_DISCONNECT,
                                0, mLastNetworkId), LINK_FLAPPING_DEBOUNCE_MSEC);
                        if (DBG) {
                            log("NETWORK_DISCONNECTION_EVENT in connected state"
                                    + " BSSID=" + mWifiInfo.getBSSID()
                                    + " RSSI=" + mWifiInfo.getRssi()
                                    + " freq=" + mWifiInfo.getFrequency()
                                    + " reason=" + message.arg2
                                    + " -> debounce");
                        }
                        return HANDLED;
                    } else {
                        if (DBG) {
                            int ajst = -1;
                            if (config != null) ajst = config.autoJoinStatus;
                            log("NETWORK_DISCONNECTION_EVENT in connected state"
                                    + " BSSID=" + mWifiInfo.getBSSID()
                                    + " RSSI=" + mWifiInfo.getRssi()
                                    + " freq=" + mWifiInfo.getFrequency()
                                    + " was debouncing=" + linkDebouncing
                                    + " reason=" + message.arg2
                                    + " ajst=" + ajst);
                        }
                    }
                    break;
                case CMD_AUTO_ROAM:
                    // Clear the driver roam indication since we are attempting a framework roam
                    mLastDriverRoamAttempt = 0;
                    if (hasCustomizedAutoConnect() && !mWifiFwkExt.shouldAutoConnect()) {
                        Log.d(TAG, "Skip CMD_AUTO_ROAM for customization!");
                        return HANDLED;
                    }
                    /* Connect command coming from auto-join */
                    ScanResult candidate = (ScanResult)message.obj;
                    String bssid = "any";
                    if (candidate != null && candidate.is5GHz()) {
                        // Only lock BSSID for 5GHz networks
                        bssid = candidate.BSSID;
                    }
                    int netId = mLastNetworkId;
                    config = getCurrentWifiConfiguration();


                    if (config == null) {
                        loge("AUTO_ROAM and no config, bail out...");
                        break;
                    }

                    logd("CMD_AUTO_ROAM sup state "
                            + mSupplicantStateTracker.getSupplicantStateName()
                            + " my state " + getCurrentState().getName()
                            + " nid=" + Integer.toString(netId)
                            + " config " + config.configKey()
                            + " roam=" + Integer.toString(message.arg2)
                            + " to " + bssid
                            + " targetRoamBSSID " + mTargetRoamBSSID);

                    /* Save the BSSID so as to lock it @ firmware */
                    if (!autoRoamSetBSSID(config, bssid) && !linkDebouncing) {
                        logd("AUTO_ROAM nothing to do");
                        // Same BSSID, nothing to do
                        messageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                        break;
                    };

                    // Make sure the network is enabled, since supplicant will not re-enable it
                    mWifiConfigStore.enableNetworkWithoutBroadcast(netId, false);

                    if (deferForUserInput(message, netId, false)) {
                        break;
                    } else if (mWifiConfigStore.getWifiConfiguration(netId).userApproved ==
                            WifiConfiguration.USER_BANNED) {
                        replyToMessage(message, WifiManager.CONNECT_NETWORK_FAILED,
                                WifiManager.NOT_AUTHORIZED);
                        break;
                    }

                    boolean ret = false;
                    if (mLastNetworkId != netId) {
                        boolean tmpResult = false;
                        if (hasCustomizedAutoConnect()) {
                            tmpResult = mWifiConfigStore.enableNetwork(netId, true,
                                    WifiConfiguration.UNKNOWN_UID);
                        } else {
                            tmpResult = mWifiConfigStore.selectNetwork(config,
                                    /* updatePriorities = */ false,
                                    WifiConfiguration.UNKNOWN_UID);
                        }
                       if (tmpResult &&
                           mWifiNative.reconnect()) {
                           ret = true;
                       }
                    } else {
                         ret = mWifiNative.reassociate();
                    }
                    if (ret) {
                        lastConnectAttemptTimestamp = System.currentTimeMillis();
                        targetWificonfiguration = mWifiConfigStore.getWifiConfiguration(netId);

                        // replyToMessage(message, WifiManager.CONNECT_NETWORK_SUCCEEDED);
                        mAutoRoaming = message.arg2;
                        transitionTo(mRoamingState);

                    } else {
                        loge("Failed to connect config: " + config + " netId: " + netId);
                        replyToMessage(message, WifiManager.CONNECT_NETWORK_FAILED,
                                WifiManager.ERROR);
                        messageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
                        break;
                    }
                    break;
                case EVENT_UPDATE_DNS:
                    Log.d(TAG, "Update DNS for pppoe!");
                    Collection<InetAddress> dnses = mPppoeLinkProperties.getDnsServers();
                    ArrayList<String> pppoeDnses = new ArrayList<String>();
                    for (InetAddress dns : dnses) {
                        pppoeDnses.add(dns.getHostAddress());
                    }
                    for (int i = 0; i < pppoeDnses.size(); i++) {
                        Log.d(TAG, "Set net.dns" + (i + 1) + " to " + pppoeDnses.get(i));
                        SystemProperties.set("net.dns" + (i + 1), pppoeDnses.get(i));
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        @Override
        public void exit() {
            logd("WifiStateMachine: Leaving Connected state");
            setScanAlarm(false);
            mLastDriverRoamAttempt = 0;

            stopLazyRoam();

            mWhiteListedSsids = null;
        }
    }

    class DisconnectingState extends State {

        @Override
        public void enter() {

            if (DBG) loge(getName() + "\n");
            if (PDBG) {
                logd(" Enter DisconnectingState State scan interval "
                        + mWifiConfigStore.wifiDisconnectedShortScanIntervalMilli.get()
                        + " mLegacyPnoEnabled= " + mLegacyPnoEnabled
                        + " screenOn=" + mScreenOn);
            }

            // Make sure we disconnect: we enter this state prior to connecting to a new
            // network, waiting for either a DISCONNECT event or a SUPPLICANT_STATE_CHANGE
            // event which in this case will be indicating that supplicant started to associate.
            // In some cases supplicant doesn't ignore the connect requests (it might not
            // find the target SSID in its cache),
            // Therefore we end up stuck that state, hence the need for the watchdog.
            disconnectingWatchdogCount++;
            logd("Start Disconnecting Watchdog " + disconnectingWatchdogCount);
            sendMessageDelayed(obtainMessage(CMD_DISCONNECTING_WATCHDOG_TIMER,
                    disconnectingWatchdogCount, 0), DISCONNECTING_GUARD_TIMER_MSEC);
        }

        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());
            switch (message.what) {
                case CMD_SET_OPERATIONAL_MODE:
                    /// M: ALPS01616519 workaround: force supplicant send state change@{
                    if (message.arg1 == SCAN_ONLY_WITH_WIFI_OFF_MODE) {
                        mWifiNative.status();
                        deferMessage(message);
                        break;
                    }
                    ///@}
                    if (message.arg1 != CONNECT_MODE) {
                        deferMessage(message);
                    }
                    break;
                case CMD_START_SCAN:
                    deferMessage(message);
                    return HANDLED;
                case CMD_DISCONNECTING_WATCHDOG_TIMER:
                    if (disconnectingWatchdogCount == message.arg1) {
                        if (DBG) log("disconnecting watchdog! -> disconnect");
                        handleNetworkDisconnect();
                        transitionTo(mDisconnectedState);
                    }
                    break;
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                    /**
                     * If we get a SUPPLICANT_STATE_CHANGE_EVENT before NETWORK_DISCONNECTION_EVENT
                     * we have missed the network disconnection, transition to mDisconnectedState
                     * and handle the rest of the events there
                     */
                    deferMessage(message);
                    handleNetworkDisconnect();
                    transitionTo(mDisconnectedState);
                    break;
                case M_CMD_UPDATE_BGSCAN:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class DisconnectedState extends State {

        // M: for Stop scan after screen off in disconnected state feature @{
        private long mFrameworkScanStopDelayMs;
        private boolean mFrameworkScanStopSupport = false;
        private boolean mStopScanAlarmEnabled = false;

        private void setStopScanAlarm(boolean enabled) {
            if (enabled == mStopScanAlarmEnabled) return;
            if (enabled) {
                if (mFrameworkScanStopDelayMs > 0) {
                    Log.d(TAG, "setStopScanAlarm, mFrameworkScanStopDelayMs:" + mFrameworkScanStopDelayMs);
                    mAlarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + mFrameworkScanStopDelayMs,
                        mStopScanIntent);
                        mStopScanAlarmEnabled = true;
                }
            } else {
                Log.d(TAG, "Cancel setStopScanAlarm!");
                mAlarmManager.cancel(mStopScanIntent);
                mStopScanAlarmEnabled = false;
            }
        }
        ///M:@}

        @Override
        public void enter() {
            if (DBG) loge(getName() + "\n");
            // We dont scan frequently if this is a temporary disconnect
            // due to p2p
            if (mTemporarilyDisconnectWifi) {
                mWifiP2pChannel.sendMessage(WifiP2pServiceImpl.DISCONNECT_WIFI_RESPONSE);
                return;
            }
            ///M: @{
            if (isTemporarilyDontReconnectWifi()) {
                return;
            }
            ///@}

            if (PDBG) {
                logd(" Enter DisconnectedState scan interval "
                        + mWifiConfigStore.wifiDisconnectedShortScanIntervalMilli.get()
                        + " mLegacyPnoEnabled= " + mLegacyPnoEnabled
                        + " screenOn=" + mScreenOn
                        + " useGscan=" + mHalBasedPnoDriverSupported + "/"
                        + mWifiConfigStore.enableHalBasedPno.get());
            }

            /** clear the roaming state, if we were roaming, we failed */
            mAutoRoaming = WifiAutoJoinController.AUTO_JOIN_IDLE;

            if (useHalBasedAutoJoinOffload()) {
                startGScanDisconnectedModeOffload("disconnectedEnter");
            } else {
                ///M: ALPS02609842 Scan immediately after disconnected
                startScan(UNKNOWN_SCAN_SOURCE, 0, null, null);
                if (mScreenOn) {
                    /**
                     * screen lit and => delayed timer
                     */
                    startDelayedScan(500, null, null);
                } else {
                    /**
                     * screen dark and PNO supported => scan alarm disabled
                     */
                    if (mBackgroundScanSupported) {
                        /* If a regular scan result is pending, do not initiate background
                         * scan until the scan results are returned. This is needed because
                        * initiating a background scan will cancel the regular scan and
                        * scan results will not be returned until background scanning is
                        * cleared
                        */
                        if (!mIsScanOngoing) {
                            enableBackgroundScan(true);
                        }
                    } else {
                        setScanAlarm(true);
                    }
                }
            }

            /**
             * If we have no networks saved, the supplicant stops doing the periodic scan.
             * The scans are useful to notify the user of the presence of an open network.
             * Note that these are not wake up scans.
             */
            if (mNoNetworksPeriodicScan != 0 && !mP2pConnected.get()
                    && mWifiConfigStore.getConfiguredNetworks().size() == 0) {
                sendMessageDelayed(obtainMessage(CMD_NO_NETWORKS_PERIODIC_SCAN,
                        ++mPeriodicScanToken, 0), mNoNetworksPeriodicScan);
            }

            mDisconnectedTimeStamp = System.currentTimeMillis();
            mDisconnectedPnoAlarmCount = 0;

            // M: For stop scan after screen off in disconnected state feature @{
            mFrameworkScanStopSupport = mContext.getResources().getBoolean(
                com.mediatek.internal.R.bool.config_wifi_framework_stop_scan_after_screen_off_support);
            mFrameworkScanStopDelayMs = mContext.getResources().getInteger(
                com.mediatek.internal.R.integer.config_wifi_framework_stop_scan_after_screen_off_delay);
            Log.d(TAG, "mFrameworkScanStopSupport:" + mFrameworkScanStopSupport
                   + ", mFrameworkScanStopDelayMs:" + mFrameworkScanStopDelayMs);
            if (mFrameworkScanStopDelayMs == 0) {
                mFrameworkScanStopSupport = false;
            }
            if (mFrameworkScanStopSupport && (!mScreenOn)) {
                Log.d(TAG, "Start timer setStopScanAlarm!");
                setStopScanAlarm(true);
            }
            mIsPeriodicScanTimeout = false;
            if (hasCustomizedAutoConnect() && mIpConfigLost) {
                Log.d(TAG, "IpConfigLost, reconnect!");
                mIpConfigLost = false;
                reconnectCommand();
            }
            ///@}
        }
        @Override
        public boolean processMessage(Message message) {
            boolean ret = HANDLED;

            logStateAndMessage(message, getClass().getSimpleName());

            switch (message.what) {
                case CMD_NO_NETWORKS_PERIODIC_SCAN:
                    if (mP2pConnected.get()) break;
                    if (isTemporarilyDontReconnectWifi()) {
                        break;
                    }
                    if (mNoNetworksPeriodicScan != 0 && message.arg1 == mPeriodicScanToken &&
                            mWifiConfigStore.getConfiguredNetworks().size() == 0) {

                         // M: For stop scan after screen off in disconnected state feature
                        if (mFrameworkScanStopSupport && mIsPeriodicScanTimeout && (!mScreenOn)) {
                            Log.d(TAG, "No periodic scan because stop scan timeout.");
                            disconnectCommand();
                        } else {
                            startScan(UNKNOWN_SCAN_SOURCE, -1, null, null);
                            sendMessageDelayed(obtainMessage(CMD_NO_NETWORKS_PERIODIC_SCAN,
                                        ++mPeriodicScanToken, 0), mNoNetworksPeriodicScan);
                        }
                    }
                    break;
                case WifiManager.FORGET_NETWORK:
                case CMD_REMOVE_NETWORK:
                case CMD_REMOVE_APP_CONFIGURATIONS:
                case CMD_REMOVE_USER_CONFIGURATIONS:
                    // Set up a delayed message here. After the forget/remove is handled
                    // the handled delayed message will determine if there is a need to
                    // scan and continue
                    sendMessageDelayed(obtainMessage(CMD_NO_NETWORKS_PERIODIC_SCAN,
                                ++mPeriodicScanToken, 0), mNoNetworksPeriodicScan);
                    ret = NOT_HANDLED;
                    break;
                case CMD_SET_OPERATIONAL_MODE:
                    if (message.arg1 != CONNECT_MODE) {
                        mOperationalMode = message.arg1;

                        mWifiConfigStore.disableAllNetworks();
            ///M: ALPS01872204 After disabling wifi should go to WaitP2pDisable first @{
                        if (mOperationalMode == SCAN_ONLY_WITH_WIFI_OFF_MODE) {
                            //mWifiP2pChannel.sendMessage(CMD_DISABLE_P2P_REQ);
                            //setWifiState(WIFI_STATE_DISABLED);
                            setWifiState(WIFI_STATE_DISABLING);
                transitionTo(mWaitForP2pDisableState);
                        } else {
                            transitionTo(mScanModeState);
                        }
                        ///@}
                    }
                    mWifiConfigStore.
                            setLastSelectedConfiguration(WifiConfiguration.INVALID_NETWORK_ID);
                    break;
                    /* Ignore network disconnect */
                case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                    break;
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                    StateChangeResult stateChangeResult = (StateChangeResult) message.obj;
                    if (DBG) {
                        logd("SUPPLICANT_STATE_CHANGE_EVENT state=" + stateChangeResult.state +
                                " -> state= " + WifiInfo.getDetailedStateOf(stateChangeResult.state)
                                + " debouncing=" + linkDebouncing);
                    }
                    setNetworkDetailedState(WifiInfo.getDetailedStateOf(stateChangeResult.state));
                    /* ConnectModeState does the rest of the handling */
                    ret = NOT_HANDLED;
                    break;
                case CMD_START_SCAN:
                    if (!checkOrDeferScanAllowed(message)) {
                        // The scan request was rescheduled
                        messageHandlingStatus = MESSAGE_HANDLING_STATUS_REFUSED;
                        return HANDLED;
                    }
                    if (message.arg1 == SCAN_ALARM_SOURCE) {
                        // Check if the CMD_START_SCAN message is obsolete (and thus if it should
                        // not be processed) and restart the scan
                        int period =  mWifiConfigStore.wifiDisconnectedShortScanIntervalMilli.get();
                        if (mP2pConnected.get()) {
                           period = (int)Settings.Global.getLong(mContext.getContentResolver(),
                                    Settings.Global.WIFI_SCAN_INTERVAL_WHEN_P2P_CONNECTED_MS,
                                    period);
                        }
                        if (!checkAndRestartDelayedScan(message.arg2,
                                true, period, null, null)) {
                            messageHandlingStatus = MESSAGE_HANDLING_STATUS_OBSOLETE;
                            logd("Disconnected CMD_START_SCAN source "
                                    + message.arg1
                                    + " " + message.arg2 + ", " + mDelayedScanCounter
                                    + " -> obsolete");
                            return HANDLED;
                        }
                        /* Disable background scan temporarily during a regular scan */
                        enableBackgroundScan(false);
                        handleScanRequest(WifiNative.SCAN_WITHOUT_CONNECTION_SETUP, message);
                        ret = HANDLED;
                    } else {

                        /*
                         * The SCAN request is not handled in this state and
                         * would eventually might/will get handled in the
                         * parent's state. The PNO, if already enabled had to
                         * get disabled before the SCAN trigger. Hence, stop
                         * the PNO if already enabled in this state, though the
                         * SCAN request is not handled(PNO disable before the
                         * SCAN trigger in any other state is not the right
                         * place to issue).
                         */

                        enableBackgroundScan(false);
                        ret = NOT_HANDLED;
                    }
                    break;
                case CMD_RESTART_AUTOJOIN_OFFLOAD:
                    if ( (int)message.arg2 < mRestartAutoJoinOffloadCounter ) {
                        messageHandlingStatus = MESSAGE_HANDLING_STATUS_OBSOLETE;
                        return HANDLED;
                    }
                    /* If we are still in Disconnected state after having discovered a valid
                     * network this means autojoin didnt managed to associate to the network,
                     * then restart PNO so as we will try associating to it again.
                     */
                    if (useHalBasedAutoJoinOffload()) {
                        if (mGScanStartTimeMilli == 0) {
                            // If offload is not started, then start it...
                            startGScanDisconnectedModeOffload("disconnectedRestart");
                        } else {
                            // If offload is already started, then check if we need to increase
                            // the scan period and restart the Gscan
                            long now = System.currentTimeMillis();
                            if (mGScanStartTimeMilli != 0 && now > mGScanStartTimeMilli
                                    && ((now - mGScanStartTimeMilli)
                                    > DISCONNECTED_SHORT_SCANS_DURATION_MILLI)
                                    && (mGScanPeriodMilli
                                    < mWifiConfigStore.wifiDisconnectedLongScanIntervalMilli.get()))
                            {
                                startDisconnectedGScan("disconnected restart gscan");
                            }
                        }
                    } else {
                        // If we are still disconnected for a short while after having found a
                        // network thru PNO, then something went wrong, and for some reason we
                        // couldn't join this network.
                        // It might be due to a SW bug in supplicant or the wifi stack, or an
                        // interoperability issue, or we try to join a bad bss and failed
                        // In that case we want to restart pno so as to make sure that we will
                        // attempt again to join that network.
                        if (!mScreenOn && !mIsScanOngoing && mBackgroundScanSupported) {
                            enableBackgroundScan(false);
                            enableBackgroundScan(true);
                        }
                        return HANDLED;
                    }
                    break;
                case WifiMonitor.SCAN_RESULTS_EVENT:
                case WifiMonitor.SCAN_FAILED_EVENT:
                    /* Re-enable background scan when a pending scan result is received */
                    if (!mScreenOn && mIsScanOngoing
                            && mBackgroundScanSupported
                            && !useHalBasedAutoJoinOffload()) {
                        enableBackgroundScan(true);
                    } else if (!mScreenOn
                            && !mIsScanOngoing
                            && mBackgroundScanSupported
                            && !useHalBasedAutoJoinOffload()) {
                        // We receive scan results from legacy PNO, hence restart the PNO alarm
                        int delay;
                        if (mDisconnectedPnoAlarmCount < 1) {
                            delay = 30 * 1000;
                        } else if (mDisconnectedPnoAlarmCount < 3) {
                            delay = 60 * 1000;
                        } else {
                            delay = 360 * 1000;
                        }
                        mDisconnectedPnoAlarmCount++;
                        if (VDBG) {
                            logd("Starting PNO alarm " + delay);
                        }
                        mAlarmManager.set(AlarmManager.RTC_WAKEUP,
                                System.currentTimeMillis() + delay,
                                mPnoIntent);
                    }
                    /* Handled in parent state */
                    ret = NOT_HANDLED;
                    break;
                case WifiP2pServiceImpl.P2P_CONNECTION_CHANGED:
                    NetworkInfo info = (NetworkInfo) message.obj;
                    mP2pConnected.set(info.isConnected());
                    if (mP2pConnected.get()) {
                        int defaultInterval = mContext.getResources().getInteger(
                                R.integer.config_wifi_scan_interval_p2p_connected);
                        long scanIntervalMs = Settings.Global.getLong(mContext.getContentResolver(),
                                Settings.Global.WIFI_SCAN_INTERVAL_WHEN_P2P_CONNECTED_MS,
                                defaultInterval);
                        mWifiNative.setScanInterval((int) scanIntervalMs/1000);
                    } else if (mWifiConfigStore.getConfiguredNetworks().size() == 0) {
                        if (DBG) log("Turn on scanning after p2p disconnected");
                        sendMessageDelayed(obtainMessage(CMD_NO_NETWORKS_PERIODIC_SCAN,
                                    ++mPeriodicScanToken, 0), mNoNetworksPeriodicScan);
                    } else {
                        // If P2P is not connected and there are saved networks, then restart
                        // scanning at the normal period. This is necessary because scanning might
                        // have been disabled altogether if WIFI_SCAN_INTERVAL_WHEN_P2P_CONNECTED_MS
                        // was set to zero.
                        if (useHalBasedAutoJoinOffload()) {
                            startGScanDisconnectedModeOffload("p2pRestart");
                        } else {
                            ///M: ALPS02432427 if CMD_START_SCAN already existed,
                            //    then don't need to start delayed scan again
                            if (!mHandler.hasMessages(CMD_START_SCAN)) {
                                startDelayedScan(
                                        mWifiConfigStore.
                                        wifiDisconnectedShortScanIntervalMilli.get(),
                                        null, null);
                            }
                        }
                    }
                    break;
                case CMD_RECONNECT:
                case CMD_REASSOCIATE:
                    if (mTemporarilyDisconnectWifi) {
                        // Drop a third party reconnect/reassociate if STA is
                        // temporarily disconnected for p2p
                        break;
                    } else {
                        // ConnectModeState handles it
                        ret = NOT_HANDLED;
                    }
                    break;
                case CMD_SCREEN_STATE_CHANGED:
                    handleScreenStateChanged(message.arg1 != 0);
                    break;
                case M_CMD_UPDATE_BGSCAN:
                    ///M: decide should disconnect or reconnect before re-enter to disconnectedState
                    if (isTemporarilyDontReconnectWifi()) {
                        Log.d(TAG, "isNetworksDisabledDuringConnect:" +
                            mSupplicantStateTracker.isNetworksDisabledDuringConnect()
                            + ", mConnectNetwork:" + mConnectNetwork);
                        if (mConnectNetwork) {
                            mConnectNetwork = false;
                            ///wps running or user.CONNECT_NETWORK  => don't disconnect
                        } else if (!mSupplicantStateTracker.isNetworksDisabledDuringConnect()) {
                            Log.d(TAG, "Disable supplicant auto scan!");
                                mWifiNative.disconnect();
                        }
                    } else {
                        if (!mTemporarilyDisconnectWifi) {
                            //connect by auto join,so don't need to reconnect/disconnect
                            if (hasCustomizedAutoConnect()) {
                                mWifiNative.reconnect();
                            }
                        }
                    }
                    transitionTo(mDisconnectedState);
                    break;
                // M: For stop scan after screen off in disconnected state feature @{
                case M_CMD_NOTIFY_SCREEN_ON:
                    if (!mFrameworkScanStopSupport) { break; }
                    setStopScanAlarm(false);
                    if (mIsPeriodicScanTimeout) {
                        mIsPeriodicScanTimeout = false;
                        transitionTo(mDisconnectedState);
                        Log.d(TAG, "Screen on, transition to mDisconnectedState!");
                    }
                    break;
                case M_CMD_NOTIFY_SCREEN_OFF:
                    if (!mFrameworkScanStopSupport) { break; }
                    mIsPeriodicScanTimeout = false;
                    Log.d(TAG, "Start stop scan alarm!");
                    setStopScanAlarm(true);
                    break;
                case M_CMD_SLEEP_POLICY_STOP_SCAN:
                    if (!mFrameworkScanStopSupport) { break; }
                    mIsPeriodicScanTimeout = true;
                    Log.d(TAG, "mIsPeriodicScanTimeout get!");
                    if (!mScreenOn) {
                        disconnectCommand();
                        setScanAlarm(false);
                        removeMessages(CMD_NO_NETWORKS_PERIODIC_SCAN);
                    }
                    break;
                ///@}
                ///M: ALPS01456030 return back to p2p imediately to prevent p2p suspend
                case WifiP2pServiceImpl.DISCONNECT_WIFI_REQUEST:
                    if (message.arg1 == 1) {
                        mWifiNative.disconnect();
                        mTemporarilyDisconnectWifi = true;
                        mWifiP2pChannel.sendMessage(WifiP2pServiceImpl.DISCONNECT_WIFI_RESPONSE);
                    } else {
                        mWifiNative.reconnect();
                        mTemporarilyDisconnectWifi = false;
                    }
                    break;
                default:
                    ret = NOT_HANDLED;
            }
            return ret;
        }

        @Override
        public void exit() {
            mDisconnectedPnoAlarmCount = 0;
            /* No need for a background scan upon exit from a disconnected state */
            enableBackgroundScan(false);
            setScanAlarm(false);
            mAlarmManager.cancel(mPnoIntent);
            mIsPeriodicScanTimeout = false;
            setStopScanAlarm(false);
        }
    }

    class WpsRunningState extends State {
        // Tracks the source to provide a reply
        private Message mSourceMessage;
        @Override
        public void enter() {
            if (DBG) loge(getName() + "\n");
            mSourceMessage = Message.obtain(getCurrentMessage());
        }
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch (message.what) {
                case WifiMonitor.WPS_SUCCESS_EVENT:
                    // Ignore intermediate success, wait for full connection
                    mSupplicantStateTracker.sendMessage(WifiMonitor.WPS_SUCCESS_EVENT);
                    mConnectNetwork = true;
                    break;
                case WifiMonitor.NETWORK_CONNECTION_EVENT:
                    replyToMessage(mSourceMessage, WifiManager.WPS_COMPLETED);
                    mSourceMessage.recycle();
                    mSourceMessage = null;
                    deferMessage(message);
                    transitionTo(mDisconnectedState);
                    break;
                case WifiMonitor.WPS_OVERLAP_EVENT:
                    replyToMessage(mSourceMessage, WifiManager.WPS_FAILED,
                            WifiManager.WPS_OVERLAP_ERROR);
                    mSourceMessage.recycle();
                    mSourceMessage = null;
                    transitionTo(mDisconnectedState);
                    break;
                case WifiMonitor.WPS_FAIL_EVENT:
                    // Arg1 has the reason for the failure
                    if ((message.arg1 != WifiManager.ERROR) || (message.arg2 != 0)) {
                        replyToMessage(mSourceMessage, WifiManager.WPS_FAILED, message.arg1);
                        mSourceMessage.recycle();
                        mSourceMessage = null;
                        transitionTo(mDisconnectedState);
                    } else {
                        if (DBG) log("Ignore unspecified fail event during WPS connection");
                    }
                    break;
                case WifiMonitor.WPS_TIMEOUT_EVENT:
                    replyToMessage(mSourceMessage, WifiManager.WPS_FAILED,
                            WifiManager.WPS_TIMED_OUT);
                    mSourceMessage.recycle();
                    mSourceMessage = null;
                    transitionTo(mDisconnectedState);
                    break;
                case WifiManager.START_WPS:
                    replyToMessage(message, WifiManager.WPS_FAILED, WifiManager.IN_PROGRESS);
                    break;
                case WifiManager.CANCEL_WPS:
                    if (mWifiNative.cancelWps()) {
                        replyToMessage(message, WifiManager.CANCEL_WPS_SUCCEDED);
                    } else {
                        replyToMessage(message, WifiManager.CANCEL_WPS_FAILED, WifiManager.ERROR);
                    }
                    transitionTo(mDisconnectedState);
                    break;
                /**
                 * Defer all commands that can cause connections to a different network
                 * or put the state machine out of connect mode
                 */
                case CMD_STOP_DRIVER:
                case CMD_SET_OPERATIONAL_MODE:
                case WifiManager.CONNECT_NETWORK:
                case CMD_ENABLE_NETWORK:
                case CMD_RECONNECT:
                case CMD_REASSOCIATE:
                case CMD_ENABLE_ALL_NETWORKS:
                ///M:  add for ALPS01445838  to fix when screen on enable all network will reconnect to privious network@{
                case CMD_START_DRIVER:
                ///@}
                    deferMessage(message);
                    break;
                case CMD_AUTO_CONNECT:
                case CMD_AUTO_ROAM:
                    messageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                    return HANDLED;
                case CMD_START_SCAN:
                    messageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                    return HANDLED;
                case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                    if (DBG) log("Network connection lost");
                    handleNetworkDisconnect();
                    break;
                case WifiMonitor.ASSOCIATION_REJECTION_EVENT:
                    if (DBG) log("Ignore Assoc reject event during WPS Connection");
                    break;
                case WifiMonitor.AUTHENTICATION_FAILURE_EVENT:
                    // Disregard auth failure events during WPS connection. The
                    // EAP sequence is retried several times, and there might be
                    // failures (especially for wps pin). We will get a WPS_XXX
                    // event at the end of the sequence anyway.
                    if (DBG) log("Ignore auth failure during WPS connection");
                    break;
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                    // Throw away supplicant state changes when WPS is running.
                    // We will start getting supplicant state changes once we get
                    // a WPS success or failure
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        @Override
        public void exit() {
            mWifiConfigStore.enableAllNetworks();
            mWifiConfigStore.loadConfiguredNetworks();
        }
    }

    class SoftApStartingState extends State {
        @Override
        public void enter() {
            if (DBG) loge(getName() + "\n");

            final Message message = getCurrentMessage();
            if (message.what == CMD_START_AP) {
                final WifiConfiguration config = (WifiConfiguration) message.obj;

                if (config == null) {
                    mWifiApConfigChannel.sendMessage(CMD_REQUEST_AP_CONFIG);
                } else {
                    mWifiApConfigChannel.sendMessage(CMD_SET_AP_CONFIG, config);
                    startSoftApWithConfig(config);
                }
            } else {
                throw new RuntimeException("Illegal transition to SoftApStartingState: " + message);
            }
        }
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch(message.what) {
                case CMD_START_SUPPLICANT:
                case CMD_STOP_SUPPLICANT:
                case CMD_START_AP:
                case CMD_STOP_AP:
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_SET_OPERATIONAL_MODE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                case CMD_TETHER_STATE_CHANGE:
                    deferMessage(message);
                    break;
                case WifiStateMachine.CMD_RESPONSE_AP_CONFIG:
                    WifiConfiguration config = (WifiConfiguration) message.obj;
                    if (config != null) {
                        startSoftApWithConfig(config);
                    } else {
                        loge("Softap config is null!");
                        sendMessage(CMD_START_AP_FAILURE, WifiManager.SAP_START_FAILURE_GENERAL);
                    }
                    break;
                case CMD_START_AP_SUCCESS:
                    setWifiApState(WIFI_AP_STATE_ENABLED, 0);
                    Log.d(TAG, "Stop monitoring before start new monitoring!");
                    mHotspotMonitor.stopMonitoring();
                    mHotspotMonitor.startMonitoring();
                    transitionTo(mSoftApStartedState);
                    break;
                case CMD_START_AP_FAILURE:
                    setWifiApState(WIFI_AP_STATE_FAILED, message.arg1);
                    transitionTo(mInitialState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class SoftApStartedState extends State {
        @Override
        public void enter() {
            if (DBG) loge(getName() + "\n");
        }
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch(message.what) {
                case CMD_STOP_AP:
                    if (DBG) log("Stopping Soft AP");
                    Log.d(TAG, "Stop monitoring for hotspot!");
                    mHotspotMonitor.stopMonitoring();
                    /* We have not tethered at this point, so we just shutdown soft Ap */
                    try {
                        mNwService.stopAccessPoint(mInterfaceName);
                    } catch(Exception e) {
                        loge("Exception in stopAccessPoint()");
                    }
                    setWifiApState(WIFI_AP_STATE_DISABLED, 0);
                    transitionTo(mInitialState);
                    break;
                case CMD_START_AP:
                    // Ignore a start on a running access point
                    break;
                    // Fail client mode operation when soft AP is enabled
                case CMD_START_SUPPLICANT:
                    loge("Cannot start supplicant with a running soft AP");
                    setWifiState(WIFI_STATE_UNKNOWN);
                    break;
                case CMD_TETHER_STATE_CHANGE:
                    TetherStateChange stateChange = (TetherStateChange) message.obj;
                    if (startTethering(stateChange.available)) {
                        transitionTo(mTetheringState);
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class TetheringState extends State {
        @Override
        public void enter() {
            if (DBG) loge(getName() + "\n");
            /* Send ourselves a delayed message to shut down if tethering fails to notify */
            sendMessageDelayed(obtainMessage(CMD_TETHER_NOTIFICATION_TIMED_OUT,
                    ++mTetherToken, 0), TETHER_NOTIFICATION_TIME_OUT_MSECS);
        }
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch(message.what) {
                case CMD_TETHER_STATE_CHANGE:
                    TetherStateChange stateChange = (TetherStateChange) message.obj;
                    if (isWifiTethered(stateChange.active)) {
                        transitionTo(mTetheredState);
                    }
                    return HANDLED;
                case CMD_TETHER_NOTIFICATION_TIMED_OUT:
                    if (message.arg1 == mTetherToken) {
                        loge("Failed to get tether update, shutdown soft access point");
                        transitionTo(mSoftApStartedState);
                        // Needs to be first thing handled
                        sendMessageAtFrontOfQueue(CMD_STOP_AP);

                        // M: ALPS01899877 whole chip reset let WifiController not sync
                        // call WifiManager to stop AP @{
                        loge("setWifiApEnabled false!");
                        mWifiManager.setWifiApEnabled(null, false);
                        // @}
                    }
                    break;
                case CMD_START_SUPPLICANT:
                case CMD_STOP_SUPPLICANT:
                case CMD_START_AP:
                case CMD_STOP_AP:
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_SET_OPERATIONAL_MODE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                ///M: ALPS02386887 STA connected before entering TetheredState, defer it @{
                case WifiMonitor.AP_STA_CONNECTED_EVENT:
                ///@}
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class TetheredState extends State {
        private void sendClientsChangedBroadcast() {
            Intent intent = new Intent(WifiManager.WIFI_HOTSPOT_CLIENTS_CHANGED_ACTION);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        }

        @Override
        public void enter() {
            if (DBG) loge(getName() + "\n");
            mClientNum = 0;
            synchronized (mHotspotClients) {
                mHotspotClients.clear();
            }
            if (mDuration != Settings.System.WIFI_HOTSPOT_AUTO_DISABLE_OFF && mPluggedType == 0) {
                mAlarmManager.cancel(mIntentStopHotspot);
                Log.d(TAG, "Set alarm for enter TetheredState, mDuration:" + mDuration);
                mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() +
                    mDuration * HOTSPOT_DISABLE_MS, mIntentStopHotspot);
            }
            String request = SystemProperties.get("persist.radio.hotspot.probe.rq", "");
            Log.d(TAG, "persist.radio.hotspot.probe.rq:" + request);
            if (request.equals("true")) {
                mHotspotNative.setApProbeRequestEnabledCommand(true);
            }
            //wangfjEventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());

            ///M: for hotspot optimization
            mHotspotNative.setHotspotOptimization(mHotspotOptimization);
        }
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch(message.what) {
                case CMD_TETHER_STATE_CHANGE:
                    TetherStateChange stateChange = (TetherStateChange) message.obj;
                    if (!isWifiTethered(stateChange.active)) {
                        loge("Tethering reports wifi as untethered!, shut down soft Ap");
                        setHostApRunning(null, false);
                        setHostApRunning(null, true);
                    }
                    return HANDLED;
                case CMD_STOP_AP:
                    if (DBG) log("Untethering before stopping AP");
                    setWifiApState(WIFI_AP_STATE_DISABLING, 0);
                    stopTethering();
                    transitionTo(mUntetheringState);
                    // More work to do after untethering
                    deferMessage(message);
                    break;
                case WifiMonitor.AP_STA_CONNECTED_EVENT:
                    Log.d(TAG, "AP STA CONNECTED:" + message.obj);
                    ++mClientNum;
                    String address = (String) message.obj;
                    synchronized (mHotspotClients) {
                        if (!mHotspotClients.containsKey(address)) {
                            mHotspotClients.put(address, new HotspotClient(address, false));
                        }
                    }
                    if (mDuration != Settings.System.WIFI_HOTSPOT_AUTO_DISABLE_OFF && mClientNum == 1) {
                        mAlarmManager.cancel(mIntentStopHotspot);
                    }
                    sendClientsChangedBroadcast();
                    break;
                case WifiMonitor.AP_STA_DISCONNECTED_EVENT:
                    Log.d(TAG, "AP STA DISCONNECTED:" + message.obj);
                    --mClientNum;
                    address = (String) message.obj;
                    synchronized (mHotspotClients) {
                        HotspotClient client = mHotspotClients.get(address);
                        if (client != null && !client.isBlocked) {
                            mHotspotClients.remove(address);
                        }
                    }
                    if (mDuration != Settings.System.WIFI_HOTSPOT_AUTO_DISABLE_OFF && mPluggedType == 0 && mClientNum == 0) {
                        Log.d(TAG, "Set alarm for no client, mDuration:" + mDuration);
                        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            android.os.SystemClock.elapsedRealtime() +
                            mDuration * HOTSPOT_DISABLE_MS, mIntentStopHotspot);
                    }
                    sendClientsChangedBroadcast();
                    break;
                case M_CMD_START_AP_WPS:
                    WpsInfo wpsConfig = (WpsInfo) message.obj;
                    switch (wpsConfig.setup) {
                        case WpsInfo.PBC:
                            mStartApWps = true;
                            mHotspotNative.startApWpsPbcCommand();
                            break;
                        case WpsInfo.DISPLAY:
                            String pin = mHotspotNative.startApWpsCheckPinCommand(wpsConfig.pin);
                            Log.d(TAG, "Check pin result:" + pin);
                            if (pin != null) {
                                mHotspotNative.startApWpsWithPinFromDeviceCommand(pin);
                            } else {
                                Intent intent = new Intent(WifiManager.WIFI_WPS_CHECK_PIN_FAIL_ACTION);
                                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                                mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
                            }
                            break;
                        default:
                            Log.e(TAG, "Invalid setup for WPS!");
                            break;
                    }
                    break;
                case M_CMD_BLOCK_CLIENT:
                    boolean ok = mHotspotNative.blockClientCommand(((HotspotClient) message.obj).deviceAddress);
                    if (ok) {
                        synchronized (mHotspotClients) {
                            HotspotClient client = mHotspotClients.get(((HotspotClient) message.obj).deviceAddress);
                            if (client != null) {
                                client.isBlocked = true;
                            } else {
                                Log.e(TAG, "Failed to get " + ((HotspotClient) message.obj).deviceAddress);
                            }
                        }
                        sendClientsChangedBroadcast();
                    } else {
                        Log.e(TAG, "Failed to block " + ((HotspotClient) message.obj).deviceAddress);
                    }
                    mReplyChannel.replyToMessage(message, message.what, ok ? SUCCESS : FAILURE);
                    break;
                case M_CMD_UNBLOCK_CLIENT:

                    ok = mHotspotNative.unblockClientCommand(((HotspotClient) message.obj).deviceAddress);
                    if (ok) {
                        synchronized (mHotspotClients) {
                            mHotspotClients.remove(((HotspotClient) message.obj).deviceAddress);
                        }
                        sendClientsChangedBroadcast();
                    } else {
                        Log.e(TAG, "Failed to unblock " + ((HotspotClient) message.obj).deviceAddress);
                    }
                    mReplyChannel.replyToMessage(message, message.what, ok ? SUCCESS : FAILURE);
                    break;
                case M_CMD_SET_AP_PROBE_REQUEST_ENABLED:
                    ok = mHotspotNative.setApProbeRequestEnabledCommand(message.arg1 == 1 ? true : false);
                    mReplyChannel.replyToMessage(message, message.what, ok ? SUCCESS : FAILURE);
                    break;
                case WifiMonitor.WPS_OVERLAP_EVENT:
                    if (mStartApWps) {
                        Intent intent = new Intent(WifiManager.WIFI_HOTSPOT_OVERLAP_ACTION);
                        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
                        mStartApWps = false;
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        @Override
        public void exit() {
            if (mDuration != Settings.System.WIFI_HOTSPOT_AUTO_DISABLE_OFF) {
                mAlarmManager.cancel(mIntentStopHotspot);
            }
            synchronized (mHotspotClients) {
                mHotspotClients.clear();
            }
            sendClientsChangedBroadcast();

            ///M: for hotspot optimization
            mHotspotNative.setHotspotOptimization(false);
        }
    }

    class UntetheringState extends State {
        @Override
        public void enter() {
            if (DBG) loge(getName() + "\n");
            /* Send ourselves a delayed message to shut down if tethering fails to notify */
            sendMessageDelayed(obtainMessage(CMD_TETHER_NOTIFICATION_TIMED_OUT,
                    ++mTetherToken, 0), TETHER_NOTIFICATION_TIME_OUT_MSECS);

        }
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch(message.what) {
                case CMD_TETHER_STATE_CHANGE:
                    TetherStateChange stateChange = (TetherStateChange) message.obj;

                    /* Wait till wifi is untethered */
                    if (isWifiTethered(stateChange.active)) break;

                    transitionTo(mSoftApStartedState);
                    break;
                case CMD_TETHER_NOTIFICATION_TIMED_OUT:
                    if (message.arg1 == mTetherToken) {
                        loge("Failed to get tether update, force stop access point");
                        transitionTo(mSoftApStartedState);
                    }
                    break;
                case CMD_START_SUPPLICANT:
                case CMD_STOP_SUPPLICANT:
                case CMD_START_AP:
                case CMD_STOP_AP:
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_SET_OPERATIONAL_MODE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    /**
     * State machine initiated requests can have replyTo set to null indicating
     * there are no recepients, we ignore those reply actions.
     */
    private void replyToMessage(Message msg, int what) {
        if (msg.replyTo == null) return;
        Message dstMsg = obtainMessageWithWhatAndArg2(msg, what);
        mReplyChannel.replyToMessage(msg, dstMsg);
    }

    private void replyToMessage(Message msg, int what, int arg1) {
        if (msg.replyTo == null) return;
        Message dstMsg = obtainMessageWithWhatAndArg2(msg, what);
        dstMsg.arg1 = arg1;
        mReplyChannel.replyToMessage(msg, dstMsg);
    }

    private void replyToMessage(Message msg, int what, Object obj) {
        if (msg.replyTo == null) return;
        Message dstMsg = obtainMessageWithWhatAndArg2(msg, what);
        dstMsg.obj = obj;
        mReplyChannel.replyToMessage(msg, dstMsg);
    }

    /**
     * arg2 on the source message has a unique id that needs to be retained in replies
     * to match the request
     * <p>see WifiManager for details
     */
    private Message obtainMessageWithWhatAndArg2(Message srcMsg, int what) {
        Message msg = Message.obtain();
        msg.what = what;
        msg.arg2 = srcMsg.arg2;
        return msg;
    }

    /**
     * @param wifiCredentialEventType WIFI_CREDENTIAL_SAVED or WIFI_CREDENTIAL_FORGOT
     * @param msg Must have a WifiConfiguration obj to succeed
     */
    private void broadcastWifiCredentialChanged(int wifiCredentialEventType,
            WifiConfiguration config) {
        if (config != null && config.preSharedKey != null) {
            Intent intent = new Intent(WifiManager.WIFI_CREDENTIAL_CHANGED_ACTION);
            intent.putExtra(WifiManager.EXTRA_WIFI_CREDENTIAL_SSID, config.SSID);
            intent.putExtra(WifiManager.EXTRA_WIFI_CREDENTIAL_EVENT_TYPE,
                    wifiCredentialEventType);
            mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT,
                    android.Manifest.permission.RECEIVE_WIFI_CREDENTIAL_CHANGE);
        }
    }

    private static int parseHex(char ch) {
        if ('0' <= ch && ch <= '9') {
            return ch - '0';
        } else if ('a' <= ch && ch <= 'f') {
            return ch - 'a' + 10;
        } else if ('A' <= ch && ch <= 'F') {
            return ch - 'A' + 10;
        } else {
            throw new NumberFormatException("" + ch + " is not a valid hex digit");
        }
    }

    private byte[] parseHex(String hex) {
        /* This only works for good input; don't throw bad data at it */
        if (hex == null) {
            return new byte[0];
        }

        if (hex.length() % 2 != 0) {
            throw new NumberFormatException(hex + " is not a valid hex string");
        }

        byte[] result = new byte[(hex.length())/2 + 1];
        result[0] = (byte) ((hex.length())/2);
        for (int i = 0, j = 1; i < hex.length(); i += 2, j++) {
            int val = parseHex(hex.charAt(i)) * 16 + parseHex(hex.charAt(i+1));
            byte b = (byte) (val & 0xFF);
            result[j] = b;
        }

        return result;
    }

    private static String makeHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String makeHex(byte[] bytes, int from, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02x", bytes[from+i]));
        }
        return sb.toString();
    }

    private static byte[] concat(byte[] array1, byte[] array2, byte[] array3) {

        int len = array1.length + array2.length + array3.length;

        if (array1.length != 0) {
            len++;                      /* add another byte for size */
        }

        if (array2.length != 0) {
            len++;                      /* add another byte for size */
        }

        if (array3.length != 0) {
            len++;                      /* add another byte for size */
        }

        byte[] result = new byte[len];

        int index = 0;
        if (array1.length != 0) {
            result[index] = (byte) (array1.length & 0xFF);
            index++;
            for (byte b : array1) {
                result[index] = b;
                index++;
            }
        }

        if (array2.length != 0) {
            result[index] = (byte) (array2.length & 0xFF);
            index++;
            for (byte b : array2) {
                result[index] = b;
                index++;
            }
        }

        if (array3.length != 0) {
            result[index] = (byte) (array3.length & 0xFF);
            index++;
            for (byte b : array3) {
                result[index] = b;
                index++;
            }
        }
        return result;
    }

    private static byte[] concatHex(byte[] array1, byte[] array2) {

        int len = array1.length + array2.length;

        byte[] result = new byte[len];

        int index = 0;
        if (array1.length != 0) {
            for (byte b : array1) {
                result[index] = b;
                index++;
            }
        }

        if (array2.length != 0) {
            for (byte b : array2) {
                result[index] = b;
                index++;
            }
        }

        return result;
    }

    void handleGsmAuthRequest(SimAuthRequestData requestData) {
        if (targetWificonfiguration == null
                || targetWificonfiguration.networkId == requestData.networkId) {
            logd("id matches targetWifiConfiguration");
        } else {
            logd("id does not match targetWifiConfiguration");
            return;
        }

        TelephonyManager tm = (TelephonyManager)
                mContext.getSystemService(Context.TELEPHONY_SERVICE);

        if (tm != null) {
            StringBuilder sb = new StringBuilder();
            for (String challenge : requestData.data) {

                if (challenge == null || challenge.isEmpty())
                    continue;
                logd("RAND = " + challenge);

                byte[] rand = null;
                try {
                    rand = parseHex(challenge);
                } catch (NumberFormatException e) {
                    loge("malformed challenge");
                    continue;
                }

                String base64Challenge = android.util.Base64.encodeToString(
                        rand, android.util.Base64.NO_WRAP);
                /*
                 * First, try with appType = 2 => USIM according to
                 * com.android.internal.telephony.PhoneConstants#APPTYPE_xxx
                 */
                int appType = 2;
                ///M: extend to multiple sim card
                String tmResponse = getIccSimChallengeResponse(appType, base64Challenge, tm
                        , requestData.networkId);

                if (tmResponse == null) {
                    /* Then, in case of failure, issue may be due to sim type, retry as a simple sim
                     * appType = 1 => SIM
                     */
                    appType = 1;
                    ///M: extend to multiple sim card
                    tmResponse = getIccSimChallengeResponse(appType, base64Challenge, tm
                            , requestData.networkId);
                }
                logv("Raw Response - " + tmResponse);

                if (tmResponse != null && tmResponse.length() > 4) {
                    byte[] result = android.util.Base64.decode(tmResponse,
                            android.util.Base64.DEFAULT);
                    logv("Hex Response -" + makeHex(result));
                    ///M: ALPS02449326 USIM and SIM have different response format @{
                    if (appType == 2) { //=> USIM
                        int sres_len = result[0];
                        String sres = makeHex(result, 1, sres_len);
                        int kc_offset = 1+sres_len;
                        int kc_len = result[kc_offset];
                        String kc = makeHex(result, 1+kc_offset, kc_len);
                        sb.append(":" + kc + ":" + sres);
                        logv("kc:" + kc + " sres:" + sres);
                    } else if (appType == 1) { //=> SIM
                        String sres = makeHex(result, 0, 4);
                        String kc = makeHex(result, 4, 8);
                        sb.append(":" + kc + ":" + sres);
                        logv("kc:" + kc + " sres:" + sres);
                    }
                    ///@}
                } else {
                    loge("bad response - " + tmResponse);
                }
            }

            String response = sb.toString();
            logv("Supplicant Response -" + response);
            mWifiNative.simAuthResponse(requestData.networkId, "GSM-AUTH", response);
        } else {
            loge("could not get telephony manager");
        }
    }

    void handle3GAuthRequest(SimAuthRequestData requestData) {
        StringBuilder sb = new StringBuilder();
        byte[] rand = null;
        byte[] authn = null;
        String res_type = "UMTS-AUTH";

        if (targetWificonfiguration == null
                || targetWificonfiguration.networkId == requestData.networkId) {
            logd("id matches targetWifiConfiguration");
        } else {
            logd("id does not match targetWifiConfiguration");
            return;
        }
        if (requestData.data.length == 2) {
            try {
                rand = parseHex(requestData.data[0]);
                authn = parseHex(requestData.data[1]);
            } catch (NumberFormatException e) {
                loge("malformed challenge");
            }
        } else {
               loge("malformed challenge");
        }

        String tmResponse = "";
        if (rand != null && authn != null) {
            String base64Challenge = android.util.Base64.encodeToString(
                    concatHex(rand,authn), android.util.Base64.NO_WRAP);

            TelephonyManager tm = (TelephonyManager)
                    mContext.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                int appType = 2; // 2 => USIM
                tmResponse = getIccSimChallengeResponse(appType, base64Challenge, tm
                        , requestData.networkId);
                logv("Raw Response - " + tmResponse);
            } else {
                loge("could not get telephony manager");
            }
        }

        if (tmResponse != null && tmResponse.length() > 4) {
            byte[] result = android.util.Base64.decode(tmResponse,
                    android.util.Base64.DEFAULT);
            loge("Hex Response - " + makeHex(result));
            byte tag = result[0];
            if (tag == (byte) 0xdb) {
                logv("successful 3G authentication ");
                int res_len = result[1];
                String res = makeHex(result, 2, res_len);
                int ck_len = result[res_len + 2];
                String ck = makeHex(result, res_len + 3, ck_len);
                int ik_len = result[res_len + ck_len + 3];
                String ik = makeHex(result, res_len + ck_len + 4, ik_len);
                sb.append(":" + ik + ":" + ck + ":" + res);
                logv("ik:" + ik + "ck:" + ck + " res:" + res);
            } else if (tag == (byte) 0xdc) {
                loge("synchronisation failure");
                int auts_len = result[1];
                String auts = makeHex(result, 2, auts_len);
                res_type = "UMTS-AUTS";
                sb.append(":" + auts);
                logv("auts:" + auts);
            } else {
                loge("bad response - unknown tag = " + tag);
                return;
            }
        } else {
            loge("bad response - " + tmResponse);
            return;
        }

        String response = sb.toString();
        logv("Supplicant Response -" + response);
        mWifiNative.simAuthResponse(requestData.networkId, res_type, response);
    }

    /**
     * @param reason reason code from supplicant on network disconnected event
     * @return true if this is a suspicious disconnect
     */
    static boolean unexpectedDisconnectedReason(int reason) {
        return reason == 2              // PREV_AUTH_NOT_VALID
                || reason == 6          // CLASS2_FRAME_FROM_NONAUTH_STA
                || reason == 7          // FRAME_FROM_NONASSOC_STA
                || reason == 8          // STA_HAS_LEFT
                || reason == 9          // STA_REQ_ASSOC_WITHOUT_AUTH
                || reason == 14         // MICHAEL_MIC_FAILURE
                || reason == 15         // 4WAY_HANDSHAKE_TIMEOUT
                || reason == 16         // GROUP_KEY_UPDATE_TIMEOUT
                || reason == 18         // GROUP_CIPHER_NOT_VALID
                || reason == 19         // PAIRWISE_CIPHER_NOT_VALID
                || reason == 23         // IEEE_802_1X_AUTH_FAILED
                || reason == 34;        // DISASSOC_LOW_ACK
    }


    // M: Added functions
    // For new request
    public boolean syncDoCtiaTestOn(AsyncChannel channel, String cmd) {
        Message resultMsg = channel.sendMessageSynchronously(M_CMD_DO_CTIA_TEST_ON, 0, 0, cmd);
        boolean result = (resultMsg.arg1 != FAILURE);
        resultMsg.recycle();
        return result;
    }

    public boolean syncDoCtiaTestOff(AsyncChannel channel, String cmd) {
        Message resultMsg = channel.sendMessageSynchronously(M_CMD_DO_CTIA_TEST_OFF, 0, 0, cmd);
        boolean result = (resultMsg.arg1 != FAILURE);
        resultMsg.recycle();
        return result;
    }

    public boolean syncDoCtiaTestRate(AsyncChannel channel, int rate) {
        Message resultMsg = channel.sendMessageSynchronously(M_CMD_DO_CTIA_TEST_RATE, rate);
        boolean result = (resultMsg.arg1 != FAILURE);
        resultMsg.recycle();
        return result;
    }

    public boolean syncSetTxPowerEnabled(AsyncChannel channel, boolean enable) {
        Message resultMsg = channel.sendMessageSynchronously(M_CMD_SET_TX_POWER_ENABLED, enable ? 1 : 0);
        boolean result = (resultMsg.arg1 != FAILURE);
        resultMsg.recycle();
        return result;
    }

    public boolean syncSetTxPower(AsyncChannel channel, int offset) {
        Message resultMsg = channel.sendMessageSynchronously(M_CMD_SET_TX_POWER, offset);
        boolean result = (resultMsg.arg1 != FAILURE);
        resultMsg.recycle();
        return result;
    }

    public void startApWpsCommand(WpsInfo config) {
        sendMessage(obtainMessage(M_CMD_START_AP_WPS, config));
    }

    public List<HotspotClient> syncGetHotspotClientsList() {
        List<HotspotClient> clients = new ArrayList<HotspotClient>();
        synchronized (mHotspotClients) {
            for (HotspotClient client : mHotspotClients.values()) {
                clients.add(new HotspotClient(client));
            }
        }
        return clients;
    }

    public boolean syncBlockClient(AsyncChannel channel, HotspotClient client) {
        if (client == null || client.deviceAddress == null) {
            Log.e(TAG, "Client is null!");
            return false;
        }
        Message resultMsg = channel.sendMessageSynchronously(M_CMD_BLOCK_CLIENT, client);
        boolean result = (resultMsg.arg1 != FAILURE);
        resultMsg.recycle();
        return result;
    }

    public boolean syncUnblockClient(AsyncChannel channel, HotspotClient client) {
        if (client == null || client.deviceAddress == null) {
            Log.e(TAG, "Client is null!");
            return false;
        }
        Message resultMsg = channel.sendMessageSynchronously(M_CMD_UNBLOCK_CLIENT, client);
        boolean result = (resultMsg.arg1 != FAILURE);
        resultMsg.recycle();
        return result;
    }

    public boolean syncSetApProbeRequestEnabled(AsyncChannel channel, boolean enable) {
        Message resultMsg = channel.sendMessageSynchronously(M_CMD_SET_AP_PROBE_REQUEST_ENABLED, enable ? 1 : 0);
        boolean result = (resultMsg.arg1 != FAILURE);
        resultMsg.recycle();
        return result;
    }

    public PPPOEInfo syncGetPppoeInfo() {
        if (mMtkCtpppoe) {
            mPppoeInfo.online_time = (long) (System.currentTimeMillis() / 1000) - mOnlineStartTime;
            return mPppoeInfo;
        } else {
            return null;
        }
    }

    // For auto connect
    public int syncGetConnectingNetworkId(AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(M_CMD_GET_CONNECTING_NETWORK_ID);
        int result = resultMsg.arg1;
        resultMsg.recycle();
        return result;
    }

    public List<Integer> syncGetDisconnectNetworks() {
        return mWifiConfigStore.getDisconnectNetworks();
    }

    public boolean isNetworksDisabledDuringConnect() {
        return (mSupplicantStateTracker.isNetworksDisabledDuringConnect() && isExplicitNetworkExist())
                || (getCurrentState() == mWpsRunningState);
    }

    public boolean isWifiConnecting(int connectingNetworkId) {
        return (mWifiFwkExt != null && mWifiFwkExt.isWifiConnecting(connectingNetworkId,
                                            mWifiConfigStore.getDisconnectNetworks()))
                || (getCurrentState() == mWpsRunningState);
    }

    public boolean hasConnectableAp() {
        sendMessage(M_CMD_FLUSH_BSS);
        return (mWifiFwkExt != null && mWifiFwkExt.hasConnectableAp());
    }

    public void suspendNotification(int type) {
        if (mWifiFwkExt != null) {
            mWifiFwkExt.suspendNotification(type);
        }
    }

    /**
     * In mWifiFwkExt.init(), it has mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
     * We have to make sure that WifiService is added into SystemService before calling this function.
     */
    public void autoConnectInit() {
        if (mWifiFwkExt != null) {
            mWifiFwkExt.init();
        }
        mWifiConfigStore.setWifiFwkExt(mWifiFwkExt);
        mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
    }

    public int getSecurity(WifiConfiguration config) {
        return mWifiFwkExt.getSecurity(config);
    }

    public int getSecurity(ScanResult result) {
        return mWifiFwkExt.getSecurity(result);
    }

    public boolean hasCustomizedAutoConnect() {
        return (mWifiFwkExt != null && mWifiFwkExt.hasCustomizedAutoConnect());
    }

    public boolean syncGetDisconnectFlag(AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(M_CMD_GET_DISCONNECT_FLAG);
        boolean result = (Boolean) resultMsg.obj;
        Log.d(TAG, "syncGetDisconnectFlag:" + result);
        resultMsg.recycle();
        return result;
    }

    // For bug fix
    public void syncUpdateRssi(AsyncChannel channel) {
        if (getCurrentState() == mObtainingIpState || SupplicantState.isHandshakeState(mWifiInfo.getSupplicantState())) {
            Message resultMsg = channel.sendMessageSynchronously(M_CMD_UPDATE_RSSI);
            resultMsg.recycle();
        }
    }

    public void setDeviceIdle(boolean deviceIdle) {
        mDeviceIdle = deviceIdle;
    }

    public boolean shouldStartWifi() {
        Log.d(TAG, "shouldStartWifi, mDeviceIdle:" + mDeviceIdle + ", currentState:" + getCurrentState());
        return !(mDeviceIdle && getCurrentState() == mDriverStoppedState);
    }

    public void notifyConnectionFailure() {
        sendMessage(M_CMD_NOTIFY_CONNECTION_FAILURE);
    }

    private class HotspotAutoDisableObserver extends ContentObserver {
        public HotspotAutoDisableObserver(Handler handler) {
            super(handler);
            mContext.getContentResolver().registerContentObserver(Settings.System.getUriFor(
                Settings.System.WIFI_HOTSPOT_AUTO_DISABLE), false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            mDuration = Settings.System.getInt(mContext.getContentResolver(), Settings.System.WIFI_HOTSPOT_AUTO_DISABLE,
                    Settings.System.WIFI_HOTSPOT_AUTO_DISABLE_OFF);
            if (mDuration != Settings.System.WIFI_HOTSPOT_AUTO_DISABLE_OFF && mPluggedType == 0) {
                if (mClientNum == 0 && WifiStateMachine.this.getCurrentState() == mTetheredState) {
                    mAlarmManager.cancel(mIntentStopHotspot);
                    Log.d(TAG, "Set alarm for setting changed, mDuration:" + mDuration);
                    mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        android.os.SystemClock.elapsedRealtime() +
                        mDuration * HOTSPOT_DISABLE_MS, mIntentStopHotspot);
                }
            } else {
                mAlarmManager.cancel(mIntentStopHotspot);
            }
        }
    }

    private void initializeExtra() {
        PowerManager powerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mDhcpWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DHCP_WAKELOCK");
        mDhcpWakeLock.setReferenceCounted(false);

        mHotspotNative = new WifiNative("ap0");
        mHotspotMonitor = new WifiMonitor(this, mHotspotNative);

        HandlerThread wifiThread = new HandlerThread("WifiSMForObserver");
        wifiThread.start();

        mHotspotAutoDisableObserver = new HotspotAutoDisableObserver(new Handler(wifiThread.getLooper()));
        Intent stopHotspotIntent = new Intent(ACTION_STOP_HOTSPOT);
        mIntentStopHotspot = PendingIntent.getBroadcast(mContext, STOP_HOTSPOT_REQUEST, stopHotspotIntent, 0);
        mDuration = Settings.System.getInt(mContext.getContentResolver(), Settings.System.WIFI_HOTSPOT_AUTO_DISABLE,
                    Settings.System.WIFI_HOTSPOT_AUTO_DISABLE_OFF);

        ///M: group scan@{
        mGroupScanDurationMs = mContext.getResources().getInteger(
            com.mediatek.internal.R.integer.config_wifi_framework_group_scan_delay);
        if (mGroupScanDurationMs <= 0) {
            Log.d(TAG, "group scan disabled");
        }
        mGroupScanIntent = getPrivateBroadcast(ACTION_START_GROUP_SCAN, GROUP_SCAN_REQUEST);
        ///@}

        // M: For stop scan after screen off in disconnected state feature @{
        Intent stopScanIntent = new Intent(ACTION_STOP_SCAN, null);
        mStopScanIntent = PendingIntent.getBroadcast(mContext, STOPSCAN_REQUEST, stopScanIntent, 0);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("com.mtk.stopscan.activated");
        intentFilter.addAction("com.mtk.stopscan.deactivated");
        intentFilter.addAction(ACTION_STOP_HOTSPOT);
        intentFilter.addAction(IWifiFwkExt.AUTOCONNECT_SETTINGS_CHANGE);
        intentFilter.addAction(DisplayManager.ACTION_WIFI_DISPLAY_STATUS_CHANGED);
        intentFilter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        intentFilter.addAction(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED);
        intentFilter.addAction(ACTION_STOP_SCAN);

        intentFilter.addAction(TelephonyIntents.ACTION_LOCATED_PLMN_CHANGED);

        ///M: group scan@{
        intentFilter.addAction(ACTION_START_GROUP_SCAN);
        //@}


        final boolean isHotspotAlwaysOnWhilePlugged = mContext.getResources().getBoolean(
                com.mediatek.internal.R.bool.is_mobile_hotspot_always_on_while_plugged);
        Log.d(TAG, "isHotspotAlwaysOnWhilePlugged:" + isHotspotAlwaysOnWhilePlugged);
        if (isHotspotAlwaysOnWhilePlugged) {
            intentFilter.addAction(Intent.ACTION_BATTERY_CHANGED);
        }

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                Log.d(TAG, "onReceive, action:" + action);
                if (action.equals(ACTION_START_GROUP_SCAN)) {
                    sendMessage(M_CMD_GROUP_SCAN);
                } else if (action.equals("com.mtk.stopscan.activated")) {
                    mStopScanStarted.set(true);
                    sendMessage(M_CMD_UPDATE_BGSCAN);
                } else if (action.equals("com.mtk.stopscan.deactivated")) {
                    mStopScanStarted.set(false);
                    sendMessage(M_CMD_UPDATE_BGSCAN);
                } else if (action.equals(ACTION_STOP_HOTSPOT)) {
                    mWifiManager.setWifiApEnabled(null, false);
                    int wifiSavedState = 0;
                    try {
                        wifiSavedState = Settings.Global.getInt(mContext.getContentResolver(),
                            Settings.Global.WIFI_SAVED_STATE);
                    } catch (Settings.SettingNotFoundException e) {
                        Log.e(TAG, "SettingNotFoundException:" + e);
                    }
                    Log.d(TAG, "Received stop hotspot intent, wifiSavedState:" + wifiSavedState);
                    if (wifiSavedState == 1) {
                        mWifiManager.setWifiEnabled(true);
                        Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.WIFI_SAVED_STATE, 0);
                    }
                } else if (action.equals(IWifiFwkExt.AUTOCONNECT_SETTINGS_CHANGE)) {
                    sendMessage(M_CMD_UPDATE_SETTINGS);
                } else if (action.equals(DisplayManager.ACTION_WIFI_DISPLAY_STATUS_CHANGED)) {
                    WifiDisplayStatus status = (WifiDisplayStatus) intent.getParcelableExtra(
                        DisplayManager.EXTRA_WIFI_DISPLAY_STATUS);
                    Log.d(TAG, "Received ACTION_WIFI_DISPLAY_STATUS_CHANGED.");
                    ///M: disable wfdConnected event
                    setWfdConnected(status);
                    //sendMessage(M_CMD_UPDATE_BGSCAN);
                } else if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                    if (isHotspotAlwaysOnWhilePlugged) {
                        int pluggedType = intent.getIntExtra("plugged", 0);
                        Log.d(TAG, "ACTION_BATTERY_CHANGED pluggedType:" + pluggedType + ", mPluggedType:" + mPluggedType);
                        if (mPluggedType != pluggedType) {
                            mPluggedType = pluggedType;
                            if (mDuration != Settings.System.WIFI_HOTSPOT_AUTO_DISABLE_OFF && mPluggedType == 0) {
                                if (mClientNum == 0 && WifiStateMachine.this.getCurrentState() == mTetheredState) {
                                    mAlarmManager.cancel(mIntentStopHotspot);
                                    Log.d(TAG, "Set alarm for ACTION_BATTERY_CHANGED changed, mDuration:" + mDuration);
                                    mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                                        android.os.SystemClock.elapsedRealtime() +
                                        mDuration * HOTSPOT_DISABLE_MS, mIntentStopHotspot);
                                }
                            } else {
                                mAlarmManager.cancel(mIntentStopHotspot);
                            }
                        }
                    }
                } else if (action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) {
                    String iccState = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                    log("iccState:" + iccState);
                    ///M: ALPS01975084 check sim card is not absent, should enable EAP-SIM config
                    mIccState = iccState;
                    if(!mIccState.equals(IccCardConstants.INTENT_VALUE_ICC_ABSENT)
                        && !mIccState.equals(IccCardConstants.INTENT_VALUE_ICC_LOCKED)
                        && !mIccState.equals(IccCardConstants.INTENT_VALUE_ICC_UNKNOWN)){
                        sendMessage(M_CMD_ENABLE_EAP_SIM_CONFIG_NETWORK);
                    }
                    ///M: @}
                } else if (action.equals(ACTION_STOP_SCAN)) {
                    sendMessage(M_CMD_SLEEP_POLICY_STOP_SCAN);
                } else if (action.equals(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED)) {
                    Log.d(TAG, "mReceiver: ACTION_SERVICE_STATE_CHANGED");
                    ServiceState ss = ServiceState.newFromBundle(intent.getExtras());
                    String newOpNum = ss.getOperatorNumeric();
                    Log.d(TAG, "ServiceState: " + ss.getState() + " :" + newOpNum);
                    TelephonyManager tm = (TelephonyManager)
                                            mContext.getSystemService(Context.TELEPHONY_SERVICE);

                    if ((ss.getState() == ServiceState.STATE_IN_SERVICE)
                        && (newOpNum != null && !newOpNum.equals(mOperatorNumeric))) {
                        mOperatorNumeric = newOpNum;
                        if (tm != null) {
                            sendMessage(obtainMessage(
                                M_CMD_UPDATE_COUNTRY_CODE, tm.getNetworkCountryIso()));
                        }
                    }
                } else if (action.equals(TelephonyIntents.ACTION_LOCATED_PLMN_CHANGED)) {
                    String plmn = (String) intent.getExtra(TelephonyIntents.EXTRA_PLMN);
                    String iso = (String) intent.getExtra(TelephonyIntents.EXTRA_ISO);
                    Log.d(TAG, "ACTION_LOCATED_PLMN_CHANGED: " + plmn + " iso =" + iso);
                    if (iso != null && plmn != null && !plmn.equals(mOperatorNumeric)) {
                            sendMessage(obtainMessage(
                                M_CMD_UPDATE_COUNTRY_CODE, iso));
                    }

                }
            }
        };
        mContext.registerReceiver(receiver, intentFilter);

        if (mWifiFwkExt != null) {
            mMtkCtpppoe = mWifiFwkExt.isPppoeSupported();
        }
        if (mMtkCtpppoe) {
            mPppoeInfo = new PPPOEInfo();
            mPppoeLinkProperties = new LinkProperties();
        }

        /** M: NFC Float II @{ */
        if (mMtkWpsp2pnfcSupport) {
            Intent clearWaitFlagIntent = new Intent(ACTION_CLEAR_WAIT_FLAG);
            mClearWaitFlagIntent = PendingIntent.getBroadcast(mContext, CLEAR_WATI_FLAG_REQUEST, clearWaitFlagIntent, 0);
            IntentFilter wpsFilter = new IntentFilter();
            wpsFilter.addAction(ACTION_CLEAR_WAIT_FLAG);
            wpsFilter.addAction(WifiStateMachine.MTK_WPS_NFC_TESTBED_CONFIGURATION_RECEIVED_ACTION);
            wpsFilter.addAction(WifiStateMachine.MTK_WPS_NFC_TESTBED_PASSWORD_RECEIVED_ACTION);
            wpsFilter.addAction(WifiStateMachine.MTK_WPS_NFC_TESTBED_HS_RECEIVED_ACTION);
            wpsFilter.addAction(WifiStateMachine.MTK_WPS_NFC_TESTBED_HR_RECEIVED_ACTION);
            wpsFilter.addAction(WifiStateMachine.MTK_WPS_NFC_TESTBED_ER_PASSWORD_RECEIVED_ACTION);
            mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();
                        Log.d(TAG, "Received: " + action);
                        if (action.equals(ACTION_CLEAR_WAIT_FLAG)) {
                            mWaitingForEnrollee = false;
                        } else if (action.equals(WifiStateMachine.MTK_WPS_NFC_TESTBED_CONFIGURATION_RECEIVED_ACTION)) {
                            byte[] configurationToken = intent.getByteArrayExtra(WifiStateMachine.MTK_WPS_NFC_TESTBED_EXTRA_CONFIGURATION);
                            if (configurationToken != null) {
                                sendMessage(obtainMessage(M_CMD_START_WPS_NFC_TAG_READ, bytesToHexString(configurationToken)));
                            } else {
                                Log.e(TAG, "No configuration token!");
                            }
                        } else if (action.equals(WifiStateMachine.MTK_WPS_NFC_TESTBED_HS_RECEIVED_ACTION)) {
                            byte[] passwordToken = intent.getByteArrayExtra(WifiStateMachine.MTK_WPS_NFC_TESTBED_EXTRA_PASSWORD);
                            if (passwordToken != null) {
                                sendMessage(obtainMessage(M_CMD_HS_RECEIVED, bytesToHexString(passwordToken)));
                            } else {
                                Log.e(TAG, "No password token!");
                            }
                        } else if (action.equals(WifiStateMachine.MTK_WPS_NFC_TESTBED_HR_RECEIVED_ACTION)) {
                            Log.d(TAG, "Received HR action, mWaitingForHrToken:" + mWaitingForHrToken);
                            byte[] passwordToken = intent.getByteArrayExtra(WifiStateMachine.MTK_WPS_NFC_TESTBED_EXTRA_PASSWORD);
                            if (passwordToken != null) {
                                if (mWaitingForHrToken) {
                                    sendMessage(obtainMessage(M_CMD_HR_RECEIVED, bytesToHexString(passwordToken)));
                                } else {
                                    mWifiP2pChannel.sendMessage(obtainMessage(M_CMD_HR_RECEIVED, bytesToHexString(passwordToken)));
                                }
                            } else {
                                Log.e(TAG, "No password token!");
                            }
                        } else if (action.equals(WifiStateMachine.MTK_WPS_NFC_TESTBED_PASSWORD_RECEIVED_ACTION)
                                   || action.equals(WifiStateMachine.MTK_WPS_NFC_TESTBED_ER_PASSWORD_RECEIVED_ACTION)) {
                            byte[] passwordToken = intent.getByteArrayExtra(WifiStateMachine.MTK_WPS_NFC_TESTBED_EXTRA_PASSWORD);
                            if (passwordToken != null) {
                                sendMessage(obtainMessage(M_CMD_START_WPS_NFC_TAG_READ, bytesToHexString(passwordToken)));
                            } else {
                                Log.e(TAG, "No password token!");
                            }
                        }
                    }
                }, wpsFilter);
        }
        /** @} */

        ///M: ALPS02164902 switch of UTF8-like GBK encoding
        mWifiNative.mUtf8LikeGbkEncoding = mContext.getResources().getBoolean(
                com.mediatek.internal.R.bool.config_wifi_utf8_like_gbk_encoding);
    }

    private void handleSuccessfulIpV6Configuration(DhcpResults dhcpResults, int reason) {
        mLastSignalLevel = -1; // force update of signal strength
        boolean isLpChange = false;
        synchronized (mDhcpV6ResultsLock) {
            mDhcpV6Results = dhcpResults;
            mWifiInfo.setMeteredHint(dhcpResults.hasMeteredHint());
        }
        updateLinkProperties(reason);
    }

    private void fetchRssiNative() {
        int newRssi = -1;
        String signalPoll = mWifiNative.signalPoll();
        if (signalPoll != null) {
            String[] lines = signalPoll.split("\n");
            for (String line : lines) {
                String[] prop = line.split("=");
                if (prop.length < 2) {
                    continue;
                }
                try {
                    if (prop[0].equals("RSSI")) {
                        newRssi = Integer.parseInt(prop[1]);
                    }
                } catch (NumberFormatException e) {
                    Log.e(TAG, "NumberFormatException:" + e.toString());
                }
            }
        }

        Log.i(TAG, "fetchRssiNative, newRssi:" + newRssi);
        if (newRssi != -1 && MIN_RSSI < newRssi && newRssi < MAX_RSSI) { // screen out invalid values
            /* some implementations avoid negative values by adding 256
             * so we need to adjust for that here.
             */
            if (newRssi > 0) {
                newRssi -= 256;
            }
            mWifiInfo.setRssi(newRssi);
        } else {
            mWifiInfo.setRssi(MIN_RSSI);
        }
    }

    private boolean isAirplaneSensitive() {
        String airplaneModeRadios = Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_RADIOS);
        return airplaneModeRadios == null
            || airplaneModeRadios.contains(Settings.Global.RADIO_WIFI);
    }

    private boolean isAirplaneModeOn() {
        return isAirplaneSensitive() && Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
    }

    private void updateCountryCode(String countryIso) {
        Log.e(TAG, "updateCountryCode =" + countryIso);
        if (!TextUtils.isEmpty(countryIso)) {
            Log.d(TAG, "countryIso from onCountryDetected:" + countryIso);
            mWifiNative.setCountryCode(countryIso.toUpperCase(Locale.ROOT));
            mCountryCode = countryIso;
        } else {
            Log.d(TAG, "cant get countryIso");
        }
    }

    private void setWfdConnected(WifiDisplayStatus status) {
        final int featureState = status.getFeatureState();
        final int state = status.getActiveDisplayState();
        Log.d(TAG, "setWfdConnected, featureState:" + featureState + ", state:" + state);
        if (featureState == WifiDisplayStatus.FEATURE_STATE_ON
            && state == WifiDisplayStatus.DISPLAY_STATE_CONNECTED) {
            final WifiDisplay[] displays = status.getDisplays();
            Log.d(TAG, "mWfdConnected set as true!");
            //mWfdConnected.set(true);
        } else {

            Log.d(TAG, "mWfdConnected set as false!");
            //mWfdConnected.set(false);
        }
    }
    private void sendPppoeCompletedBroadcast(final String status, final int errorCode) {
        Intent intent = new Intent(WifiManager.WIFI_PPPOE_COMPLETED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_PPPOE_STATUS, status);
        if (status.equals(WifiManager.PPPOE_STATUS_FAILURE)) {
            intent.putExtra(WifiManager.EXTRA_PPPOE_ERROR, Integer.toString(errorCode));
        }
        Log.d(TAG, "sendPppoeCompletedBroadcast, status:" + status + ", errorCode:" + errorCode);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void sendPppoeStateChangedBroadcast(final String state) {
        Intent intent = new Intent(WifiManager.WIFI_PPPOE_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_PPPOE_STATE, state);
        Log.d(TAG, "sendPppoeStateChangedBroadcast, state:" + state);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void disableLastNetwork() {
        Log.d(TAG, "disableLastNetwork, currentState:" + getCurrentState()
               + ", mLastNetworkId:" + mLastNetworkId + ", mLastBssid:" + mLastBssid);
        if (getCurrentState() != mSupplicantStoppingState && mLastNetworkId != INVALID_NETWORK_ID) {
            mWifiConfigStore.disableNetwork(mLastNetworkId, DISABLED_UNKNOWN_REASON);
        }
    }

    private void updateAutoConnectSettings() {
        boolean isConnecting = isNetworksDisabledDuringConnect();
        Log.d(TAG, "updateAutoConnectSettings, isConnecting:" + isConnecting);
        List<WifiConfiguration> networks = mWifiConfigStore.getConfiguredNetworks();
        if (null != networks) {
            if (mWifiFwkExt.shouldAutoConnect()) {
                if (!isConnecting) {
                    Collections.sort(networks, new Comparator<WifiConfiguration>() {
                        public int compare(WifiConfiguration obj1, WifiConfiguration obj2) {
                            return obj2.priority - obj1.priority;
                        }
                    });
                    List<Integer> disconnectNetworks = mWifiConfigStore.getDisconnectNetworks();
                    for (WifiConfiguration network : networks) {
                        if (network.networkId != mLastNetworkId
                            && network.disableReason == DISABLED_UNKNOWN_REASON
                            && !disconnectNetworks.contains(network.networkId)) {
                            mWifiConfigStore.enableNetwork(network.networkId, false,
                                    WifiConfiguration.UNKNOWN_UID);
                        }
                    }
                }
            } else {
                if (!isConnecting) {
                    for (WifiConfiguration network : networks) {
                        if (network.networkId != mLastNetworkId
                            && network.status != WifiConfiguration.Status.DISABLED) {
                            mWifiConfigStore.disableNetwork(network.networkId, DISABLED_UNKNOWN_REASON);
                        }
                    }
                }
            }
        }
    }

    private void disableAllNetworks(boolean except) {
        Log.d(TAG, "disableAllNetworks, except:" + except);
        List<WifiConfiguration> networks = mWifiConfigStore.getConfiguredNetworks();
        if (except) {
            if (null != networks) {
                for (WifiConfiguration network : networks) {
                    if (network.networkId != mLastNetworkId && network.status != WifiConfiguration.Status.DISABLED) {
                        mWifiConfigStore.disableNetwork(network.networkId, DISABLED_UNKNOWN_REASON);
                    }
                }
            }
        } else {
            if (null != networks) {
                for (WifiConfiguration network : networks) {
                    if (network.status != WifiConfiguration.Status.DISABLED) {
                        mWifiConfigStore.disableNetwork(network.networkId, DISABLED_UNKNOWN_REASON);
                    }
                }
            }
        }
    }

    private int getHighPriorityNetworkId() {
        int networkId = INVALID_NETWORK_ID;
        int priority = -1;
        int rssi = MIN_RSSI;
        String ssid = null;
        List<WifiConfiguration> networks = mWifiConfigStore.getConfiguredNetworks();
        if (networks == null || networks.size() == 0) {
            Log.d(TAG, "No configured networks, ignore!");
            return networkId;
        }
        HashMap<Integer, Integer> foundNetworks = new HashMap<Integer, Integer>();
        if (mScanResults != null) {
            for (WifiConfiguration network : networks) {
                if (network.networkId != mDisconnectNetworkId) {
                    for (ScanDetail scanresult : mScanResults) {
                        if ((network.SSID != null) && (scanresult.getSSID() != null)
                            && network.SSID.equals("\"" + scanresult.getSSID() + "\"")
                            && getSecurity(network) == getSecurity(scanresult.getScanResult())
                            && scanresult.getScanResult().level
                                    > IWifiFwkExt.BEST_SIGNAL_THRESHOLD) {
                            foundNetworks.put(network.priority, scanresult.getScanResult().level);
                        }
                    }
                }
            }
        }
        if (foundNetworks.size() < IWifiFwkExt.MIN_NETWORKS_NUM) {
            Log.d(TAG, "Configured networks number less than two, ignore!");
            return networkId;
        }
        Object[] keys = foundNetworks.keySet().toArray();
        Arrays.sort(keys, new Comparator<Object>() {
            public int compare(Object obj1, Object obj2) {
                return (Integer) obj2 - (Integer) obj1;
            }
        });
        /*for (Object key : keys) {
            Log.d(TAG, "Priority:" + key + ", rssi:" + foundNetworks.get(key));
        }*/
        priority = (Integer) keys[0];
        for (WifiConfiguration network : networks) {
            if (network.priority == priority) {
                networkId = network.networkId;
                ssid = network.SSID;
                rssi = foundNetworks.get(priority);
                break;
            }
        }
        Log.d(TAG, "Found the highest priority AP, networkId:" + networkId
               + ", priority:" + priority + ", rssi:" + rssi + ", ssid:" + ssid);
        return networkId;
    }

    private void showReselectionDialog() {
        mScanForWeakSignal = false;
        Log.d(TAG, "showReselectionDialog, mLastNetworkId:" + mLastNetworkId
                + ", mDisconnectNetworkId:" + mDisconnectNetworkId);
        int networkId = getHighPriorityNetworkId();
        if (networkId == INVALID_NETWORK_ID) {
            return;
        }
        if (mWifiFwkExt.shouldAutoConnect()) {
            Log.d(TAG, "Supplicant state is " + mWifiInfo.getSupplicantState()
                   + " when try to connect network " + networkId);
            if (!isNetworksDisabledDuringConnect()) {
                sendMessage(obtainMessage(CMD_ENABLE_NETWORK, networkId, 1));
            } else {
                Log.d(TAG, "WiFi is connecting!");
            }
        } else {
            mShowReselectDialog = mWifiFwkExt.handleNetworkReselection();
        }
    }

    private boolean isExplicitNetworkExist() {
        List<WifiConfiguration> networks = mWifiConfigStore.getConfiguredNetworks();
        if (mScanResults != null && networks != null) {
            for (WifiConfiguration network : networks) {
                if (network.networkId == mLastExplicitNetworkId) {
                    for (ScanDetail scanresult : mScanResults) {
                        if ((network.SSID != null) && (scanresult.getSSID() != null)
                            && network.SSID.equals("\"" + scanresult.getSSID() + "\"")
                            && getSecurity(network) == getSecurity(scanresult.getScanResult())) {
                            Log.d(TAG, "Explicit network " + mLastExplicitNetworkId + " exists!");
                            return true;
                        }
                    }
                }
            }
        }
        Log.d(TAG, "Explicit network " + mLastExplicitNetworkId + " doesn't exist!");
        return false;
    }

    private int getConnectingNetworkId() {
        int networkId = INVALID_NETWORK_ID;
        String listStr = mWifiNative.listNetworks();
        Log.d(TAG, "listStr:" + listStr);
        if (listStr != null) {
            String[] lines = listStr.split("\n");
            // Skip the first line, which is a header
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].indexOf("[CURRENT]") != -1) {
                    String[] items = lines[i].split("\t");
                    try {
                        networkId = Integer.parseInt(items[0]);
                        break;
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "NumberFormatException:" + e.toString());
                    }
                } else if (lines[i].indexOf("[TEMP-DISABLED]") != -1 && lines[i].indexOf("[DISABLED]") == -1) {
                    String[] items = lines[i].split("\t");
                    try {
                        networkId = Integer.parseInt(items[0]);
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "NumberFormatException:" + e.toString());
                    }
                }
            }
        }
        return networkId;
    }

    private void checkIfEapNetworkChanged(WifiConfiguration newConfig) {
        Log.d(TAG, "checkIfEapNetworkChanged, mLastNetworkId:" + mLastNetworkId
                + ", newConfig:" + newConfig);
        if (newConfig == null) {
            return;
        }
        if (mLastNetworkId != INVALID_NETWORK_ID && mLastNetworkId == newConfig.networkId
            && (newConfig.allowedKeyManagement.get(KeyMgmt.WPA_EAP)
                || newConfig.allowedKeyManagement.get(KeyMgmt.IEEE8021X))) {
            mDisconnectOperation = true;
            mScanForWeakSignal = false;
        }
    }

    public String getWifiStatus(AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(M_CMD_GET_WIFI_STATUS);
        String result = (String) resultMsg.obj;
        resultMsg.recycle();
        return result;
    }

    public void setPowerSavingMode(boolean mode) {
        sendMessage(obtainMessage(M_CMD_SET_POWER_SAVING_MODE, mode ? 1 : 0, 0));
        return;
    }

    private class PppoeHandler extends Handler {
        private StateMachine mController;
        private boolean mCancelCallback;

        public PppoeHandler(Looper looper, StateMachine target) {
            super(looper);
            mController = target;
        }

        public void handleMessage(Message msg) {
            Log.d(TAG, "Handle start PPPOE message!");
            int event;
            DhcpResults pppoeResult = new DhcpResults();
            synchronized (this) {
                // A new request is being made, so assume we will callback
                mCancelCallback = false;
            }
            mPppoeInfo.status = PPPOEInfo.Status.CONNECTING;
            sendPppoeStateChangedBroadcast(WifiManager.PPPOE_STATE_CONNECTING);
            int result = NetworkUtils.runPPPOE(mInterfaceName, mPppoeConfig.timeout, mPppoeConfig.username, mPppoeConfig.password,
                mPppoeConfig.lcp_echo_interval, mPppoeConfig.lcp_echo_failure, mPppoeConfig.mtu, mPppoeConfig.mru,
                mPppoeConfig.MSS, pppoeResult);
            Log.d(TAG, "runPPPOE result:" + result);
            if (result == 0) {
                event = EVENT_PPPOE_SUCCEEDED;
                Log.d(TAG, "PPPoE succeeded, pppoeResult:" + pppoeResult);
                synchronized (this) {
                    if (!mCancelCallback) {
                        mController.sendMessage(event, pppoeResult);
                    }
                }
            } else {
                stopPPPoE();
                sendPppoeCompletedBroadcast(WifiManager.PPPOE_STATUS_FAILURE, result);
                Log.d(TAG, "PPPoE failed, error:" + NetworkUtils.getPPPOEError());
            }
        }

        public synchronized void setCancelCallback(boolean cancelCallback) {
            mCancelCallback = cancelCallback;
        }
    }

    private void stopPPPoE() {
        Log.d(TAG, "stopPPPoE, mPppoeInfo:" + mPppoeInfo);
        mUsingPppoe = false;
        if (null != mPppoeHandler) {
            mPppoeHandler.setCancelCallback(true);
            if (mPppoeHandler.hasMessages(EVENT_START_PPPOE)) {
                Log.e(TAG, "hasMessages EVENT_START_PPPOE!");
                mPppoeHandler.removeMessages(EVENT_START_PPPOE);
            }
        } else {
            Log.e(TAG, "mPppoeHandler is null!");
        }
        sendPppoeStateChangedBroadcast(WifiManager.PPPOE_STATE_DISCONNECTING);
        try {
            mNwService.removeInterfaceFromNetwork(mPppoeLinkProperties.getInterfaceName(), PPPOE_NETID);
            mNwService.removeNetwork(PPPOE_NETID);
            Log.d(TAG, "removeNetwork successfully!");
        } catch (Exception e) {
            Log.e(TAG, "Exception in removeNetwork:" + e.toString());
        }
        try {
            mNwService.disablePPPOE();
            Log.d(TAG, "Stop PPPOE successfully!");
        } catch (Exception e) {
            Log.e(TAG, "Exception in disablePPPOE:" + e.toString());
        }

        mPppoeConfig = null;
        mPppoeInfo.status = PPPOEInfo.Status.OFFLINE;
        mPppoeInfo.online_time = 0;
        mOnlineStartTime = 0;
        mPppoeLinkProperties.clear();
        sendPppoeStateChangedBroadcast(WifiManager.PPPOE_STATE_DISCONNECTED);
        if (null != mPppoeHandler) {
            mPppoeHandler.getLooper().quit();
            mPppoeHandler = null;
        } else {
            Log.e(TAG, "mPppoeHandler is null!");
        }
    }



    private void handleSuccessfulPppoeConfiguration(DhcpResults pppoeResult) {
        mPppoeLinkProperties = pppoeResult.toLinkProperties(pppoeResult.ppplinkname);
        Log.d(TAG, "handleSuccessfulPppoeConfiguration, mPppoeLinkProperties:" + mPppoeLinkProperties);
        Collection<RouteInfo> oldRouteInfos = mLinkProperties.getRoutes();
        for (RouteInfo route : oldRouteInfos) {
            Log.d(TAG, "RouteInfo of wlan0:" + route);
        }
        int wifiNetId = -1;
        Network[] networks = mCm.getAllNetworks();
        if (networks != null && networks.length > 0) {
            for (Network net : networks) {
                NetworkInfo info = mCm.getNetworkInfo(net);
                if (info != null && info.getType() == ConnectivityManager.TYPE_WIFI) {
                    wifiNetId = net.netId;
                    break;
                }
            }
        }
        Log.d(TAG, "wifiNetId:" + wifiNetId);
        if (wifiNetId != -1) {
            for (RouteInfo route : oldRouteInfos) {
                if (route.isDefaultRoute()) {
                    try {
                        mNwService.removeRoute(wifiNetId, route);
                    } catch (Exception e) {
                        Log.e(TAG, "Exception in removeRoute:" + e.toString());
                    }
                }
            }
        }
        Collection<InetAddress> dnses = mPppoeLinkProperties.getDnsServers();
        ArrayList<String> pppoeDnses = new ArrayList<String>();
        for (InetAddress dns : dnses) {
            pppoeDnses.add(dns.getHostAddress());
        }
        String[] dnsArr = new String[pppoeDnses.size()];
        pppoeDnses.toArray(dnsArr);
        for (int i = 0; i < dnsArr.length; i++) {
            Log.d(TAG, "Set net.dns" + (i + 1) + " to " + dnsArr[i]);
            SystemProperties.set("net.dns" + (i + 1), dnsArr[i]);
        }
        try {
            mNwService.createPhysicalNetwork(PPPOE_NETID, null);
            mNwService.addInterfaceToNetwork(pppoeResult.ppplinkname, PPPOE_NETID);
            mNwService.setDnsServersForNetwork(PPPOE_NETID, dnsArr, null);
            mNwService.setDefaultNetId(PPPOE_NETID);
            Collection<RouteInfo> newRouteInfos = mPppoeLinkProperties.getRoutes();
            for (RouteInfo route : newRouteInfos) {
                if (route.isDefaultRoute()) {
                    mNwService.addRoute(PPPOE_NETID, route);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception in config pppoe:" + e.toString());
        }
        mPppoeInfo.status = PPPOEInfo.Status.ONLINE;
        mOnlineStartTime = (long) (System.currentTimeMillis() / 1000);
        sendPppoeStateChangedBroadcast(WifiManager.PPPOE_STATE_CONNECTED);
        sendPppoeCompletedBroadcast(WifiManager.PPPOE_STATUS_SUCCESS, 0);
    }

    ///M: Add API For Set WOWLAN Mode @{
    public boolean syncSetWoWlanNormalMode(AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(M_CMD_SET_WOWLAN_NORMAL_MODE);
        if (null == resultMsg) {
            log("syncSetWoWlanNormalMode fail, resultMsg == null");
            return false;
        }
        boolean result = (resultMsg.arg1 != FAILURE);
        resultMsg.recycle();
        return result;
    }

    public boolean syncSetWoWlanMagicMode(AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(M_CMD_SET_WOWLAN_MAGIC_MODE);
        if (null == resultMsg) {
            log("syncSetWoWlanMagicMode fail, resultMsg == null");
            return false;
        }
        boolean result = (resultMsg.arg1 != FAILURE);
        resultMsg.recycle();
        return result;
    }

    ///@}

    /** M: NFC Float II @{ */
    private byte[] hexStringToBytes(String hexString) {
        if (hexString == null || hexString.equals("")) {
            return null;
        }
        int length = hexString.length() / 2;
        byte[] result = new byte[length];
        for (int i = 0; i < length; i++) {
            result[i] = (byte) Integer.parseInt(hexString.substring(2 * i, 2 * i + 2), 16);
        }
        return result;
    }

    private String bytesToHexString(byte[] src) {
        StringBuilder stringBuilder = new StringBuilder("");
        if (src == null || src.length <= 0) {
            return null;
        }
        for (int i = 0; i < src.length; i++) {
            int v = src[i] & 0xFF;
            String hv = Integer.toHexString(v);
            if (hv.length() < 2) {
                stringBuilder.append(0);
            }
            stringBuilder.append(hv);
        }
        return stringBuilder.toString().toUpperCase();
    }

    private void sendPinToNfcBroadcast(String pin) {    //Item 22
        Intent intent = new Intent(WifiStateMachine.MTK_WPS_NFC_TESTBED_W_PASSWORD_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiStateMachine.MTK_WPS_NFC_TESTBED_EXTRA_PASSWORD, hexStringToBytes(pin));
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void sendCredentialToNfcBroadcast(String credential) {  //Item 26
        Intent intent = new Intent(WifiStateMachine.MTK_WPS_NFC_TESTBED_W_CONFIGURATION_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiStateMachine.MTK_WPS_NFC_TESTBED_EXTRA_CONFIGURATION, hexStringToBytes(credential));
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void sendReadCredRequestToNfcBroadcast() {  //Item 23
        Intent intent = new Intent(WifiStateMachine.MTK_WPS_NFC_TESTBED_R_CONFIGURATION_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void sendReadPinRequestToNfcBroadcast() {   //Item 25
        Intent intent = new Intent(WifiStateMachine.MTK_WPS_NFC_TESTBED_R_PASSWORD_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void sendRequesterActionToNfc(String passwordToken) {   //Item 28
        Intent intent = new Intent(WifiStateMachine.MTK_WPS_NFC_TESTBED_HR_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiStateMachine.MTK_WPS_NFC_TESTBED_EXTRA_PASSWORD, hexStringToBytes(passwordToken));
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void sendSelectorActionToNfc(String passwordToken) {    //Item 29
        Intent intent = new Intent(WifiStateMachine.MTK_WPS_NFC_TESTBED_HS_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiStateMachine.MTK_WPS_NFC_TESTBED_EXTRA_PASSWORD, hexStringToBytes(passwordToken));
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }
    /** @} */


    public boolean syncSetPoorlinkThreshold(AsyncChannel channel, String key, double value) {
        Message resultMsg ;
        boolean result = false;
        if (key.equals("rssi")) {
            resultMsg = channel.sendMessageSynchronously(M_CMD_SET_POORLINK_RSSI, Double.valueOf(value));
            result = (resultMsg.arg1 != FAILURE);
            resultMsg.recycle();
        } else if (key.equals("linkspeed")) {
            resultMsg = channel.sendMessageSynchronously(M_CMD_SET_POORLINK_LINKSPEED, Double.valueOf(value));
            result = (resultMsg.arg1 != FAILURE);
            resultMsg.recycle();
        }
        return result;
    }

    private boolean isTemporarilyDontReconnectWifi() {
        log("stopReconnectWifi" +
           " Wfd=" + mWfdConnected.get()
            + " StopScan=" + mStopScanStarted.get() +
            " mDontReconnectAndScan=" + mDontReconnectAndScan.get());
        if (mWfdConnected.get() ||
            mStopScanStarted.get() || mDontReconnectAndScan.get()) {
            return true;
        }
        return false;
    }

    private void sendLinkLayerStatsBroadcast(final String linkLayerStats) {
        Intent intent = new Intent("wifi.wifi.LinkLayerStats");
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra("linkLayerStats", linkLayerStats);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    public void setHotspotOptimization(boolean enable) {
        log("setHotspotOptimization " + enable);
        mHotspotOptimization = enable;
    }

    public void setAutoJoinScanWhenConnected(boolean enable) {
        log("setAutoJoinScanWhenConnected " + enable);
        mAutoJoinScanWhenConnected = enable;
    }

    /**
     * Get test environment.
     * @param channel AsyncChannel for sending message
     * @param wifiChannel Wi-Fi channel
     * @return test environment string
     */
    public String syncGetTestEnv(AsyncChannel channel, int wifiChannel) {
        Message resultMsg = channel.sendMessageSynchronously(M_CMD_GET_TEST_ENV, wifiChannel);
        String result = (String) resultMsg.obj;
        resultMsg.recycle();
        return result;
    }

    ///M: ALPS01982740 TLS network should not connect when screen locked
    public void setScreenLocked(boolean locked){
        logd("set mIsScreenLocked = " + locked);
        if(mWifiConfigStore != null) {
            mWifiConfigStore.mIsScreenLocked.set(locked);
        }else{
            logd("mWifiConfigStore == null");
        }
    }

     ///M: extend to multiple sim card @{
     private final int GET_SUBID_NULL_ERROR = -1;
     private int getIntSimSlot(String simSlot) {
         int slotId = 0;
         if (simSlot != null) {
             String[] simSlots = simSlot.split("\"");
             if (simSlots.length > 1) {
                 slotId = Integer.parseInt(simSlots[1]);
             }
         }
         return slotId;
     }
     private int getSubId(int simSlot) {
         int[] subIds = SubscriptionManager.getSubId(simSlot);
         if (subIds != null) {
             return subIds[0];
         } else {
             return GET_SUBID_NULL_ERROR;
         }
     }

     public String getIccSimChallengeResponse(
             int appType, String base64Challenge, TelephonyManager tm, int netId) {
         String tmResponse = null;
         WifiConfiguration config = getConfiguredNetworkByNetId(netId);
         if (tm.getDefault().getPhoneCount() >= 2 && config != null) {
             int subId = getSubId(getIntSimSlot(config.simSlot));
             if (subId != -1) {
                 log("subId: " + subId + ", appType: "+ appType + ", " + base64Challenge);
                 tmResponse = tm.getIccSimChallengeResponse(subId, appType, base64Challenge);
                 return tmResponse;
             }
         }
         tmResponse = tm.getIccSimChallengeResponse(appType, base64Challenge);
         return tmResponse;
     }

     private WifiConfiguration getConfiguredNetworkByNetId(int netId) {
         List<WifiConfiguration> networks = mWifiConfigStore.getConfiguredNetworks();
         if (null != networks) {
             for (WifiConfiguration config : networks) {
                 if (config.networkId == netId) {
                     log("getConfiguredNetworkByNetId found config");
                     return config;
                 }
             }
         }
         log("getConfiguredNetworkByNetId don't found config");
         return null;
     }
     ///@}

     ///M: ALPS02279279 [Google Issue] Cannot remove configured networks when factory reset
     public void factoryReset(int uid) {
         sendMessage(M_CMD_FACTORY_RESET, uid);
     }

     ///M: ALPS02349855 check whether old scanDetail and new flags have same key management
     private boolean isSameKeyManagement(ScanDetail scanDetail, String flags) {
         ScanResult scanResult = scanDetail.getScanResult();
         if (scanResult.capabilities.contains("WEP")
                && flags.contains("WEP")) {
             return true;
         } else if (scanResult.capabilities.contains("PSK")
                && flags.contains("PSK")) {
             return true;
         } else if (scanResult.capabilities.contains("EAP")
                && flags.contains("EAP")) {
             return true;
         } else if (!scanResult.capabilities.contains("WEP")
                && !scanResult.capabilities.contains("PSK")
                && !scanResult.capabilities.contains("EAP")
                && !flags.contains("WEP")
                && !flags.contains("PSK")
                && !flags.contains("EAP")) {
             return true;
         }
         logd("isSameKeyManagement: false, old: " + scanResult + ", new: " + flags);
         return false;
     }
}
