package com.beetle.im;


import android.os.AsyncTask;
import android.util.Log;
import com.beetle.AsyncTCP;
import com.beetle.TCPConnectCallback;
import com.beetle.TCPReadCallback;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import static android.os.SystemClock.uptimeMillis;

/**
 * Created by houxh on 14-7-21.
 */
public class IMService {

    private final String HOST = "imnode2.gobelieve.io";
    private final int PORT = 23000;

    public enum ConnectState {
        STATE_UNCONNECTED,
        STATE_CONNECTING,
        STATE_CONNECTED,
        STATE_CONNECTFAIL,
    }

    private final String TAG = "imservice";
    private final int HEARTBEAT = 60*3;
    private AsyncTCP tcp;
    private boolean stopped = true;
    private boolean suspended = true;
    private boolean reachable = true;
    private boolean isBackground = false;

    private Timer connectTimer;
    private Timer heartbeatTimer;
    private int pingTimestamp;
    private int connectFailCount = 0;
    private int seq = 0;
    private ConnectState connectState = ConnectState.STATE_UNCONNECTED;

    private String hostIP;
    private int timestamp;

    private String host;
    private int port;
    private String token;
    private String deviceID;

    private long roomID;

    //确保一个时刻只有一个同步过程在运行，以免收到重复的消息
    private long syncKey;
    //在同步过程中收到新的syncnotify消息
    private long pendingSyncKey;
    private boolean isSyncing;
    private int syncTimestamp;

    private static class GroupSync {
        public long groupID;
        public long syncKey;
        //在同步过程中收到新的syncnotify消息
        private long pendingSyncKey;
        private boolean isSyncing;
        private int syncTimestamp;
    }

    private HashMap<Long, GroupSync> groupSyncKeys = new HashMap<Long, GroupSync>();

    SyncKeyHandler syncKeyHandler;
    PeerMessageHandler peerMessageHandler;
    GroupMessageHandler groupMessageHandler;
    CustomerMessageHandler customerMessageHandler;
    ArrayList<IMServiceObserver> observers = new ArrayList<IMServiceObserver>();
    ArrayList<GroupMessageObserver> groupObservers = new ArrayList<GroupMessageObserver>();
    ArrayList<PeerMessageObserver> peerObservers = new ArrayList<PeerMessageObserver>();
    ArrayList<SystemMessageObserver> systemMessageObservers = new ArrayList<SystemMessageObserver>();
    ArrayList<CustomerMessageObserver> customerServiceMessageObservers = new ArrayList<CustomerMessageObserver>();
    ArrayList<RTMessageObserver> rtMessageObservers = new ArrayList<RTMessageObserver>();
    ArrayList<RoomMessageObserver> roomMessageObservers = new ArrayList<RoomMessageObserver>();

    HashMap<Integer, IMMessage> peerMessages = new HashMap<Integer, IMMessage>();
    HashMap<Integer, IMMessage> groupMessages = new HashMap<Integer, IMMessage>();
    HashMap<Integer, CustomerMessage> customerMessages = new HashMap<Integer, CustomerMessage>();

    private byte[] data;

    private static IMService im = new IMService();

    public static IMService getInstance() {
        return im;
    }

    public IMService() {
        connectTimer = new Timer() {
            @Override
            protected void fire() {
                IMService.this.connect();
            }
        };

        heartbeatTimer = new Timer() {
            @Override
            protected void fire() {
                IMService.this.sendHeartbeat();
            }
        };

        this.host = HOST;
        this.port = PORT;
    }



    public ConnectState getConnectState() {
        return connectState;
    }

    public void setReachable(boolean reachable) {
        this.reachable = reachable;
    }
    public void setHost(String host) {
        this.host = host;
    }
    public void setToken(String token) {
        this.token = token;
    }

    public void setDeviceID(String deviceID) {
        this.deviceID = deviceID;
    }

    public void setSyncKey(long syncKey) {
        this.syncKey = syncKey;
    }

    public void addSuperGroupSyncKey(long groupID, long syncKey) {
        GroupSync s = new GroupSync();
        s.groupID = groupID;
        s.syncKey = syncKey;
        this.groupSyncKeys.put(groupID, s);
    }

    public void removeSuperGroupSyncKey(long groupID) {
        this.groupSyncKeys.remove(groupID);
    }

    public void clearSuperGroupSyncKeys() {
        this.groupSyncKeys.clear();
    }

    public void setSyncKeyHandler(SyncKeyHandler handler) {
        this.syncKeyHandler = handler;
    }

    public void setPeerMessageHandler(PeerMessageHandler handler) {
        this.peerMessageHandler = handler;
    }
    public void setGroupMessageHandler(GroupMessageHandler handler) {
        this.groupMessageHandler = handler;
    }
    public void setCustomerMessageHandler(CustomerMessageHandler handler) {
        this.customerMessageHandler = handler;
    }

    public void addObserver(IMServiceObserver ob) {
        if (observers.contains(ob)) {
            return;
        }
        observers.add(ob);
    }

    public void removeObserver(IMServiceObserver ob) {
        observers.remove(ob);
    }


    public void addPeerObserver(PeerMessageObserver ob) {
        if (peerObservers.contains(ob)) {
            return;
        }
        peerObservers.add(ob);
    }

    public void removePeerObserver(PeerMessageObserver ob) {
        peerObservers.remove(ob);
    }

    public void addGroupObserver(GroupMessageObserver ob) {
        if (groupObservers.contains(ob)) {
            return;
        }
        groupObservers.add(ob);
    }

    public void removeGroupObserver(GroupMessageObserver ob) {
        groupObservers.remove(ob);
    }

    public void addSystemObserver(SystemMessageObserver ob) {
        if (systemMessageObservers.contains(ob)) {
            return;
        }
        systemMessageObservers.add(ob);
    }

    public void removeSystemObserver(SystemMessageObserver ob) {
        systemMessageObservers.remove(ob);
    }

    public void addCustomerServiceObserver(CustomerMessageObserver ob) {
        if (customerServiceMessageObservers.contains(ob)) {
            return;
        }
        customerServiceMessageObservers.add(ob);
    }

    public void removeCustomerServiceObserver(CustomerMessageObserver ob) {
        customerServiceMessageObservers.remove(ob);
    }

    public void addRTObserver(RTMessageObserver ob) {
        if (rtMessageObservers.contains(ob)) {
            return;
        }
        rtMessageObservers.add(ob);
    }

    public void removeRTObserver(RTMessageObserver ob){
        rtMessageObservers.remove(ob);
    }

    public void addRoomObserver(RoomMessageObserver ob) {
        if (roomMessageObservers.contains(ob)) {
            return;
        }
        roomMessageObservers.add(ob);
    }

    public void removeRoomObserver(RoomMessageObserver ob) {
        roomMessageObservers.remove(ob);
    }

    public void enterBackground() {
        Log.i(TAG, "im service enter background");
        this.isBackground = true;
        if (!this.stopped) {
            suspend();
        }
    }

    public void enterForeground() {
        Log.i(TAG, "im service enter foreground");
        this.isBackground = false;
        if (!this.stopped) {
            resume();
        }
    }

    public void onNetworkConnectivityChange(boolean reachable) {
        this.reachable = reachable;
        Log.i(TAG, "connectivity status:" + reachable);
        if (reachable) {
            if (!IMService.this.stopped && !IMService.this.isBackground) {
                //todo 优化 可以判断当前连接的socket的localip和当前网络的ip是一样的情况下
                //就没有必要重连socket
                Log.i(TAG, "reconnect im service");
                IMService.this.suspend();
                IMService.this.resume();
            }
        } else {
            IMService.this.reachable = false;
            if (!IMService.this.stopped) {
                IMService.this.suspend();
            }
        }
    }

    public void start() {
        if (this.token.length() == 0) {
            throw new RuntimeException("NO TOKEN PROVIDED");
        }

        if (!this.stopped) {
            Log.i(TAG, "already started");
            return;
        }
        Log.i(TAG, "start im service");
        this.stopped = false;
        this.resume();

        //应用在后台的情况下基本不太可能调用start
        if (this.isBackground) {
            Log.w(TAG, "start im service when app is background");
        }
    }

    public void stop() {
        if (this.stopped) {
            Log.i(TAG, "already stopped");
            return;
        }
        Log.i(TAG, "stop im service");
        stopped = true;
        suspend();
    }

    private void suspend() {
        if (this.suspended) {
            Log.i(TAG, "suspended");
            return;
        }
        this.close();
        heartbeatTimer.suspend();
        connectTimer.suspend();
        this.suspended = true;

        Log.i(TAG, "suspend im service");
    }

    private void resume() {
        if (!this.suspended) {
            return;
        }
        Log.i(TAG, "resume im service");
        this.suspended = false;

        connectTimer.setTimer(uptimeMillis());
        connectTimer.resume();

        heartbeatTimer.setTimer(uptimeMillis(), HEARTBEAT*1000);
        heartbeatTimer.resume();
    }

    public boolean sendPeerMessage(IMMessage im) {
        Message msg = new Message();
        msg.cmd = Command.MSG_IM;
        msg.body = im;
        if (!sendMessage(msg)) {
            return false;
        }

        peerMessages.put(new Integer(msg.seq), im);

        //在发送需要回执的消息时尽快发现socket已经断开的情况
        sendHeartbeat();

        return true;
    }

    public boolean sendGroupMessage(IMMessage im) {
        Message msg = new Message();
        msg.cmd = Command.MSG_GROUP_IM;
        msg.body = im;
        if (!sendMessage(msg)) {
            return false;
        }

        groupMessages.put(new Integer(msg.seq), im);

        //在发送需要回执的消息时尽快发现socket已经断开的情况
        sendHeartbeat();

        return true;
    }

    public boolean sendCustomerMessage(CustomerMessage im) {
        Message msg = new Message();
        msg.cmd = Command.MSG_CUSTOMER;
        msg.body = im;
        if (!sendMessage(msg)) {
            return false;
        }

        customerMessages.put(new Integer(msg.seq), im);

        //在发送需要回执的消息时尽快发现socket已经断开的情况
        sendHeartbeat();

        return true;
    }

    public boolean sendCustomerSupportMessage(CustomerMessage im) {
        Message msg = new Message();
        msg.cmd = Command.MSG_CUSTOMER_SUPPORT;
        msg.body = im;
        if (!sendMessage(msg)) {
            return false;
        }

        customerMessages.put(new Integer(msg.seq), im);

        //在发送需要回执的消息时尽快发现socket已经断开的情况
        sendHeartbeat();

        return true;
    }

    public boolean sendRTMessage(RTMessage rt) {
        Message msg = new Message();
        msg.cmd = Command.MSG_RT;
        msg.body = rt;
        if (!sendMessage(msg)) {
            return false;
        }
        return true;
    }

    public boolean sendRoomMessage(RoomMessage rm) {
        Message msg = new Message();
        msg.cmd = Command.MSG_ROOM_IM;
        msg.body = rm;
        return sendMessage(msg);
    }

    private void sendEnterRoom(long roomID) {
        Message msg = new Message();
        msg.cmd = Command.MSG_ENTER_ROOM;
        msg.body = new Long(roomID);
        sendMessage(msg);
    }

    private void sendLeaveRoom(long roomID) {
        Message msg = new Message();
        msg.cmd = Command.MSG_LEAVE_ROOM;
        msg.body = new Long(roomID);
        sendMessage(msg);
    }

    public void enterRoom(long roomID) {
        if (roomID == 0) {
            return;
        }
        this.roomID = roomID;
        sendEnterRoom(roomID);
    }

    public void leaveRoom(long roomID) {
        if (this.roomID != roomID || roomID == 0) {
            return;
        }
        sendLeaveRoom(roomID);
        this.roomID = 0;
    }

    private void close() {
        Iterator iter = peerMessages.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<Integer, IMMessage> entry = (Map.Entry<Integer, IMMessage>)iter.next();
            IMMessage im = entry.getValue();
            if (peerMessageHandler != null) {
                peerMessageHandler.handleMessageFailure(im);
            }
            publishPeerMessageFailure(im);
        }
        peerMessages.clear();

        iter = groupMessages.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<Integer, IMMessage> entry = (Map.Entry<Integer, IMMessage>)iter.next();
            IMMessage im = entry.getValue();
            if (groupMessageHandler != null) {
                groupMessageHandler.handleMessageFailure(im);
            }
            publishGroupMessageFailure(im);
        }
        groupMessages.clear();

        iter = customerMessages.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<Integer, CustomerMessage> entry = (Map.Entry<Integer, CustomerMessage>)iter.next();
            CustomerMessage im = entry.getValue();
            if (customerMessageHandler != null) {
                customerMessageHandler.handleMessageFailure(im);
            }
            publishCustomerServiceMessageFailure(im);
        }
        customerMessages.clear();

        if (this.tcp != null) {
            Log.i(TAG, "close tcp");
            this.tcp.close();
            this.tcp = null;
        }
    }

    public static int now() {
        Date date = new Date();
        long t = date.getTime();
        return (int)(t/1000);
    }

    private void refreshHost() {
        new AsyncTask<Void, Integer, String>() {
            @Override
            protected String doInBackground(Void... urls) {
                return lookupHost(IMService.this.host);
            }

            private String lookupHost(String host) {
                try {
                    InetAddress[] inetAddresses = InetAddress.getAllByName(host);
                    for (int i = 0; i < inetAddresses.length; i++) {
                        InetAddress inetAddress = inetAddresses[i];
                        Log.i(TAG, "host name:" + inetAddress.getHostName() + " " + inetAddress.getHostAddress());
                        if (inetAddress instanceof Inet4Address) {
                            return inetAddress.getHostAddress();
                        }
                    }
                    return "";
                } catch (UnknownHostException exception) {
                    exception.printStackTrace();
                    return "";
                }
            }

            @Override
            protected void onPostExecute(String result) {
                if (result.length() > 0) {
                    IMService.this.hostIP = result;
                    IMService.this.timestamp = now();
                }
            }
        }.execute();
    }

    private void startConnectTimer() {
        if (this.stopped || this.suspended || this.isBackground) {
            return;
        }
        long t;
        if (this.connectFailCount > 60) {
            t = uptimeMillis() + 60*1000;
        } else {
            t = uptimeMillis() + this.connectFailCount*1000;
        }
        connectTimer.setTimer(t);
        Log.d(TAG, "start connect timer:" + this.connectFailCount);
    }

    private void onConnected() {
        Log.i(TAG, "tcp connected");

        int now = now();
        this.data = null;
        this.connectFailCount = 0;
        this.connectState = ConnectState.STATE_CONNECTED;
        this.publishConnectState();
        this.sendAuth();
        if (this.roomID > 0) {
            this.sendEnterRoom(this.roomID);
        }
        this.sendSync(this.syncKey);
        this.isSyncing = true;
        this.syncTimestamp = now;
        this.pendingSyncKey = 0;
        for (Map.Entry<Long, GroupSync> e : this.groupSyncKeys.entrySet()) {
            GroupSync s = e.getValue();
            this.sendGroupSync(e.getKey(), s.syncKey);
            s.isSyncing = true;
            s.syncTimestamp = now;
            s.pendingSyncKey = 0;
        }
        this.tcp.startRead();
    }

    private void connect() {
        if (this.tcp != null) {
            return;
        }
        if (this.stopped) {
            Log.e(TAG, "opps....");
            return;
        }

        if (hostIP == null || hostIP.length() == 0) {
            refreshHost();
            IMService.this.connectFailCount++;
            Log.i(TAG, "host ip is't resolved");

            long t;
            if (this.connectFailCount > 60) {


                t = uptimeMillis() + 60*1000;
            } else {
                t = uptimeMillis() + this.connectFailCount*1000;
            }
            connectTimer.setTimer(t);
            return;
        }

        if (now() - timestamp > 5*60) {
            refreshHost();
        }

        this.pingTimestamp = 0;
        this.connectState = ConnectState.STATE_CONNECTING;
        IMService.this.publishConnectState();
        this.tcp = new AsyncTCP();
        Log.i(TAG, "new tcp...");

        this.tcp.setConnectCallback(new TCPConnectCallback() {
            @Override
            public void onConnect(Object tcp, int status) {
                if (status != 0) {
                    Log.i(TAG, "connect err:" + status);
                    IMService.this.connectFailCount++;
                    IMService.this.connectState = ConnectState.STATE_CONNECTFAIL;
                    IMService.this.publishConnectState();
                    IMService.this.close();
                    IMService.this.startConnectTimer();
                } else {
                    IMService.this.onConnected();
                }
            }
        });

        this.tcp.setReadCallback(new TCPReadCallback() {
            @Override
            public void onRead(Object tcp, byte[] data) {
                if (data.length == 0) {
                    Log.i(TAG, "tcp read eof");
                    IMService.this.connectState = ConnectState.STATE_UNCONNECTED;
                    IMService.this.publishConnectState();
                    IMService.this.handleClose();
                } else {
                    IMService.this.pingTimestamp = 0;
                    boolean b = IMService.this.handleData(data);
                    if (!b) {
                        IMService.this.connectState = ConnectState.STATE_UNCONNECTED;
                        IMService.this.publishConnectState();
                        IMService.this.handleClose();
                    }
                }
            }
        });

        boolean r = this.tcp.connect(this.hostIP, this.port);
        Log.i(TAG, "tcp connect:" + r);
        if (!r) {
            this.tcp = null;
            IMService.this.connectFailCount++;
            IMService.this.connectState = ConnectState.STATE_CONNECTFAIL;
            publishConnectState();
            startConnectTimer();
        }
    }

    private void handleAuthStatus(Message msg) {
        Integer status = (Integer)msg.body;
        Log.d(TAG, "auth status:" + status);
        if (status != 0) {
            //失效的accesstoken,2s后重新连接
            this.connectFailCount = 2;
            this.connectState = ConnectState.STATE_UNCONNECTED;
            this.publishConnectState();
            this.close();
            this.startConnectTimer();
        }
    }

    private void handleIMMessage(Message msg) {
        IMMessage im = (IMMessage)msg.body;
        Log.d(TAG, "im message sender:" + im.sender + " receiver:" + im.receiver + " content:" + im.content);
        if (peerMessageHandler != null && !peerMessageHandler.handleMessage(im)) {
            Log.i(TAG, "handle im message fail");
            return;
        }
        if (im.secret) {
            publishPeerSecretMessage(im);
        } else {
            publishPeerMessage(im);
        }
        Message ack = new Message();
        ack.cmd = Command.MSG_ACK;
        ack.body = new Integer(msg.seq);
        sendMessage(ack);
    }

    private void handleGroupIMMessage(Message msg) {
        IMMessage im = (IMMessage)msg.body;
        Log.d(TAG, "group im message sender:" + im.sender + " receiver:" + im.receiver + " content:" + im.content);


        if (groupMessageHandler != null && !groupMessageHandler.handleMessage(im)) {
            Log.i(TAG, "handle im message fail");
            return;
        }

        publishGroupMessage(im);
        Message ack = new Message();
        ack.cmd = Command.MSG_ACK;
        ack.body = new Integer(msg.seq);
        sendMessage(ack);
    }

    private void handleGroupNotification(Message msg) {
        String notification = (String)msg.body;
        Log.d(TAG, "group notification:" + notification);
        if (groupMessageHandler != null && !groupMessageHandler.handleGroupNotification(notification)) {
            Log.i(TAG, "handle group notification fail");
            return;
        }
        publishGroupNotification(notification);

        Message ack = new Message();
        ack.cmd = Command.MSG_ACK;
        ack.body = new Integer(msg.seq);
        sendMessage(ack);

    }

    private void handleClose() {
        close();
        startConnectTimer();
    }

    private void handleACK(Message msg) {
        Integer seq = (Integer)msg.body;
        IMMessage im = peerMessages.get(seq);
        if (im != null) {
            if (peerMessageHandler != null && !peerMessageHandler.handleMessageACK(im)) {
                Log.w(TAG, "handle message ack fail");
                return;
            }
            peerMessages.remove(seq);
            publishPeerMessageACK(im);
            return;
        }
        im = groupMessages.get(seq);
        if (im != null) {

            if (groupMessageHandler != null && !groupMessageHandler.handleMessageACK(im)) {
                Log.i(TAG, "handle group message ack fail");
                return;
            }
            groupMessages.remove(seq);
            publishGroupMessageACK(im);
        }

        CustomerMessage cm = customerMessages.get(seq);
        if (cm != null) {
            if (customerMessageHandler != null && !customerMessageHandler.handleMessageACK(cm)) {
                Log.i(TAG, "handle customer service message ack fail");
                return;
            }
            customerMessages.remove(seq);
            publishCustomerServiceMessageACK(cm);
        }
    }

    private void handleSystemMessage(Message msg) {
        String sys = (String)msg.body;
        for (int i = 0; i < systemMessageObservers.size(); i++ ) {
            SystemMessageObserver ob = systemMessageObservers.get(i);
            ob.onSystemMessage(sys);
        }

        Message ack = new Message();
        ack.cmd = Command.MSG_ACK;
        ack.body = new Integer(msg.seq);
        sendMessage(ack);
    }

    private void handleCustomerMessage(Message msg) {
        CustomerMessage cs = (CustomerMessage)msg.body;
        if (customerMessageHandler != null && !customerMessageHandler.handleMessage(cs)) {
            Log.i(TAG, "handle customer service message fail");
            return;
        }

        publishCustomerMessage(cs);

        Message ack = new Message();
        ack.cmd = Command.MSG_ACK;
        ack.body = new Integer(msg.seq);
        sendMessage(ack);
    }

    private void handleCustomerSupportMessage(Message msg) {
        CustomerMessage cs = (CustomerMessage)msg.body;
        if (customerMessageHandler != null && !customerMessageHandler.handleCustomerSupportMessage(cs)) {
            Log.i(TAG, "handle customer service message fail");
            return;
        }

        publishCustomerSupportMessage(cs);

        Message ack = new Message();
        ack.cmd = Command.MSG_ACK;
        ack.body = new Integer(msg.seq);
        sendMessage(ack);
    }

    private void handleRTMessage(Message msg) {
        RTMessage rt = (RTMessage)msg.body;
        for (int i = 0; i < rtMessageObservers.size(); i++ ) {
            RTMessageObserver ob = rtMessageObservers.get(i);
            ob.onRTMessage(rt);
        }
    }

    private void handleRoomMessage(Message msg) {
        RoomMessage rm = (RoomMessage)msg.body;
        for (int i= 0; i < roomMessageObservers.size(); i++) {
            RoomMessageObserver ob = roomMessageObservers.get(i);
            ob.onRoomMessage(rm);
        }
    }

    private void handleSyncNotify(Message msg) {
        Log.i(TAG, "sync notify:" + msg.body);
        Long newSyncKey = (Long)msg.body;
        int now = now();

        //4s同步超时
        boolean isSyncing = this.isSyncing && (now - this.syncTimestamp < 4);

        if (!isSyncing && newSyncKey > this.syncKey) {
            sendSync(this.syncKey);
            this.isSyncing = true;
            this.syncTimestamp = now;
        } else if (newSyncKey > this.pendingSyncKey) {
            //等待此次同步结束后，再同步
            this.pendingSyncKey = newSyncKey;
        }
    }

    private void handleSyncBegin(Message msg) {
        Log.i(TAG, "sync begin...:" + msg.body);
    }

    private void handleSyncEnd(Message msg) {
        Log.i(TAG, "sync end...:" + msg.body);
        Long newSyncKey = (Long)msg.body;
        if (newSyncKey > this.syncKey) {
            this.syncKey = newSyncKey;
            if (this.syncKeyHandler != null) {
                this.syncKeyHandler.saveSyncKey(this.syncKey);
                this.sendSyncKey(this.syncKey);
            }
        }

        int now = now();
        this.isSyncing = false;
        if (this.pendingSyncKey > this.syncKey) {
            //上次同步过程中，再次收到了新的SyncGroupNotify消息
            this.sendSync(this.syncKey);
            this.isSyncing = true;
            this.syncTimestamp = now;
            this.pendingSyncKey = 0;
        }
    }

    private void handleSyncGroupNotify(Message msg) {
        GroupSyncKey key = (GroupSyncKey)msg.body;
        Log.i(TAG, "group sync notify:" + key.groupID + " " + key.syncKey);

        GroupSync s = null;
        if (this.groupSyncKeys.containsKey(key.groupID)) {
            s = this.groupSyncKeys.get(key.groupID);
        } else {
            //接受到新加入的超级群消息
            s = new GroupSync();
            s.groupID = key.groupID;
            s.syncKey = 0;
            this.groupSyncKeys.put(new Long(key.groupID), s);
        }

        int now = now();
        //4s同步超时
        boolean isSyncing = s.isSyncing && (now - s.syncTimestamp < 4);
        if (!isSyncing && key.syncKey > s.syncKey) {
            this.sendGroupSync(key.groupID, s.syncKey);
            s.isSyncing = true;
            s.syncTimestamp = now;
        } else if (key.syncKey > s.pendingSyncKey) {
            s.pendingSyncKey = key.syncKey;
        }
    }

    private void handleSyncGroupBegin(Message msg) {
        GroupSyncKey key = (GroupSyncKey)msg.body;
        Log.i(TAG, "sync group begin...:" + key.groupID + " " + key.syncKey);
    }

    private void handleSyncGroupEnd(Message msg) {
        GroupSyncKey key = (GroupSyncKey)msg.body;
        Log.i(TAG, "sync group end...:" + key.groupID + " " + key.syncKey);

        GroupSync s = null;
        if (this.groupSyncKeys.containsKey(key.groupID)) {
            s = this.groupSyncKeys.get(key.groupID);
        } else {
            Log.e(TAG, "no group:" + key.groupID + " sync key");
            return;
        }

        if (key.syncKey > s.syncKey) {
            s.syncKey = key.syncKey;
            if (this.syncKeyHandler != null) {
                this.syncKeyHandler.saveGroupSyncKey(key.groupID, key.syncKey);
                this.sendGroupSyncKey(key.groupID, key.syncKey);
            }
        }

        s.isSyncing = false;

        int now = now();
        if (s.pendingSyncKey > s.syncKey) {
            //上次同步过程中，再次收到了新的SyncGroupNotify消息
            this.sendGroupSync(s.groupID, s.syncKey);
            s.isSyncing = true;
            s.syncTimestamp = now;
            s.pendingSyncKey = 0;
        }
    }

    private void handlePong(Message msg) {
        this.pingTimestamp = 0;
    }

    private void handleMessage(Message msg) {
        Log.i(TAG, "message cmd:" + msg.cmd);
        if (msg.cmd == Command.MSG_AUTH_STATUS) {
            handleAuthStatus(msg);
        } else if (msg.cmd == Command.MSG_IM) {
            handleIMMessage(msg);
        } else if (msg.cmd == Command.MSG_ACK) {
            handleACK(msg);
        } else if (msg.cmd == Command.MSG_PONG) {
            handlePong(msg);
        } else if (msg.cmd == Command.MSG_GROUP_IM) {
            handleGroupIMMessage(msg);
        } else if (msg.cmd == Command.MSG_GROUP_NOTIFICATION) {
            handleGroupNotification(msg);
        } else if (msg.cmd == Command.MSG_SYSTEM) {
            handleSystemMessage(msg);
        } else if (msg.cmd == Command.MSG_RT) {
            handleRTMessage(msg);
        } else if (msg.cmd == Command.MSG_CUSTOMER) {
            handleCustomerMessage(msg);
        } else if (msg.cmd == Command.MSG_CUSTOMER_SUPPORT) {
            handleCustomerSupportMessage(msg);
        } else if (msg.cmd == Command.MSG_ROOM_IM) {
            handleRoomMessage(msg);
        } else if (msg.cmd == Command.MSG_SYNC_NOTIFY) {
            handleSyncNotify(msg);
        } else if (msg.cmd == Command.MSG_SYNC_BEGIN) {
            handleSyncBegin(msg);
        } else if (msg.cmd == Command.MSG_SYNC_END) {
            handleSyncEnd(msg);
        } else if (msg.cmd == Command.MSG_SYNC_GROUP_NOTIFY) {
            handleSyncGroupNotify(msg);
        } else if (msg.cmd == Command.MSG_SYNC_GROUP_BEGIN) {
            handleSyncGroupBegin(msg);
        } else if (msg.cmd == Command.MSG_SYNC_GROUP_END) {
            handleSyncGroupEnd(msg);
        } else {
            Log.i(TAG, "unknown message cmd:"+msg.cmd);
        }
    }

    private void appendData(byte[] data) {
        if (this.data != null) {
            int l = this.data.length + data.length;
            byte[] buf = new byte[l];
            System.arraycopy(this.data, 0, buf, 0, this.data.length);
            System.arraycopy(data, 0, buf, this.data.length, data.length);
            this.data = buf;
        } else {
            this.data = data;
        }
    }

    private boolean handleData(byte[] data) {
        appendData(data);

        int pos = 0;
        while (true) {
            if (this.data.length < pos + 4) {
                break;
            }
            int len = BytePacket.readInt32(this.data, pos);
            if (this.data.length < pos + 4 + Message.HEAD_SIZE + len) {
                break;
            }
            Message msg = new Message();
            byte[] buf = new byte[Message.HEAD_SIZE + len];
            System.arraycopy(this.data, pos+4, buf, 0, Message.HEAD_SIZE+len);
            if (!msg.unpack(buf)) {
                Log.i(TAG, "unpack message error");
                return false;
            }
            handleMessage(msg);
            pos += 4 + Message.HEAD_SIZE + len;
        }

        byte[] left = new byte[this.data.length - pos];
        System.arraycopy(this.data, pos, left, 0, left.length);
        this.data = left;
        return true;
    }

    private void sendAuth() {
        final int PLATFORM_ANDROID = 2;

        Message msg = new Message();
        msg.cmd = Command.MSG_AUTH_TOKEN;
        AuthenticationToken auth = new AuthenticationToken();
        auth.platformID = PLATFORM_ANDROID;
        auth.token = this.token;
        auth.deviceID = this.deviceID;
        msg.body = auth;

        sendMessage(msg);
    }

    private void sendSync(long syncKey) {
        Message msg = new Message();
        msg.cmd = Command.MSG_SYNC;
        msg.body = new Long(syncKey);
        sendMessage(msg);
    }

    private void sendSyncKey(long syncKey) {
        Message msg = new Message();
        msg.cmd = Command.MSG_SYNC_KEY;
        msg.body = new Long(syncKey);
        sendMessage(msg);
    }

    private void sendGroupSync(long groupID, long syncKey) {
        Message msg = new Message();
        msg.cmd = Command.MSG_SYNC_GROUP;
        GroupSyncKey key = new GroupSyncKey();
        key.groupID = groupID;
        key.syncKey = syncKey;
        msg.body = key;
        sendMessage(msg);
    }

    private void sendGroupSyncKey(long groupID, long syncKey) {
        Message msg = new Message();
        msg.cmd = Command.MSG_GROUP_SYNC_KEY;
        GroupSyncKey key = new GroupSyncKey();
        key.groupID = groupID;
        key.syncKey = syncKey;
        msg.body = key;
        sendMessage(msg);
    }

    private void sendHeartbeat() {
        if (connectState == ConnectState.STATE_CONNECTED && this.pingTimestamp == 0) {
            Log.i(TAG, "send ping");
            Message msg = new Message();
            msg.cmd = Command.MSG_PING;
            sendMessage(msg);

            this.pingTimestamp = now();

            Timer t = new Timer() {
                @Override
                protected void fire() {
                    int now = now();
                    //3s未收到pong
                    if (pingTimestamp > 0 && now - pingTimestamp >= 3) {
                        Log.i(TAG, "ping timeout");
                        handleClose();
                        return;
                    }
                }
            };

            t.setTimer(uptimeMillis()+1000*3+100);
            t.resume();
        }
    }

    private boolean sendMessage(Message msg) {
        if (this.tcp == null || connectState != ConnectState.STATE_CONNECTED) return false;
        this.seq++;
        msg.seq = this.seq;
        byte[] p = msg.pack();
        if (p.length >= 32*1024) {
            Log.e(TAG, "message length overflow");
            return false;
        }
        int l = p.length - Message.HEAD_SIZE;
        byte[] buf = new byte[p.length + 4];
        BytePacket.writeInt32(l, buf, 0);
        System.arraycopy(p, 0, buf, 4, p.length);
        this.tcp.writeData(buf);
        return true;
    }

    private void publishGroupNotification(String notification) {
        for (int i = 0; i < groupObservers.size(); i++ ) {
            GroupMessageObserver ob = groupObservers.get(i);
            ob.onGroupNotification(notification);
        }
    }

    private void publishGroupMessage(IMMessage msg) {
        for (int i = 0; i < groupObservers.size(); i++ ) {
            GroupMessageObserver ob = groupObservers.get(i);
            ob.onGroupMessage(msg);
        }
    }

    private void publishGroupMessageACK(IMMessage im) {
        for (int i = 0; i < groupObservers.size(); i++ ) {
            GroupMessageObserver ob = groupObservers.get(i);
            ob.onGroupMessageACK(im);
        }
    }


    private void publishGroupMessageFailure(IMMessage im) {
        for (int i = 0; i < groupObservers.size(); i++ ) {
            GroupMessageObserver ob = groupObservers.get(i);
            ob.onGroupMessageFailure(im);
        }
    }

    private void publishPeerMessage(IMMessage msg) {
        for (int i = 0; i < peerObservers.size(); i++ ) {
            PeerMessageObserver ob = peerObservers.get(i);
            ob.onPeerMessage(msg);
        }
    }

    private void publishPeerSecretMessage(IMMessage msg) {
        for (int i = 0; i < peerObservers.size(); i++ ) {
            PeerMessageObserver ob = peerObservers.get(i);
            ob.onPeerSecretMessage(msg);
        }
    }

    private void publishPeerMessageACK(IMMessage msg) {
        for (int i = 0; i < peerObservers.size(); i++ ) {
            PeerMessageObserver ob = peerObservers.get(i);
            ob.onPeerMessageACK(msg);
        }
    }

    private void publishPeerMessageFailure(IMMessage msg) {
        for (int i = 0; i < peerObservers.size(); i++ ) {
            PeerMessageObserver ob = peerObservers.get(i);
            ob.onPeerMessageFailure(msg);
        }
    }

    private void publishConnectState() {
        for (int i = 0; i < observers.size(); i++ ) {
            IMServiceObserver ob = observers.get(i);
            ob.onConnectState(connectState);
        }
    }

    private void publishCustomerMessage(CustomerMessage cs) {
        for (int i = 0; i < customerServiceMessageObservers.size(); i++) {
            CustomerMessageObserver ob = customerServiceMessageObservers.get(i);
            ob.onCustomerMessage(cs);
        }
    }

    private void publishCustomerSupportMessage(CustomerMessage cs) {
        for (int i = 0; i < customerServiceMessageObservers.size(); i++) {
            CustomerMessageObserver ob = customerServiceMessageObservers.get(i);
            ob.onCustomerSupportMessage(cs);
        }
    }

    private void publishCustomerServiceMessageACK(CustomerMessage msg) {
        for (int i = 0; i < customerServiceMessageObservers.size(); i++) {
            CustomerMessageObserver ob = customerServiceMessageObservers.get(i);
            ob.onCustomerMessageACK(msg);
        }
    }


    private void publishCustomerServiceMessageFailure(CustomerMessage msg) {
        for (int i = 0; i < customerServiceMessageObservers.size(); i++) {
            CustomerMessageObserver ob = customerServiceMessageObservers.get(i);
            ob.onCustomerMessageFailure(msg);
        }
    }
}
