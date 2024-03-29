/*
 * Copyright (c) 2012-2014, 2017 The Linux Foundation. All rights reserved.
 * Not a Contribution.
 * Copyright (C) 2007-2008 Esmertec AG.
 * Copyright (C) 2007-2008 The Android Open Source Project
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

package com.android.mms.transaction;

import static android.provider.Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION;
import static com.google.android.mms.pdu.PduHeaders.MESSAGE_TYPE_DELIVERY_IND;
import static com.google.android.mms.pdu.PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND;
import static com.google.android.mms.pdu.PduHeaders.MESSAGE_TYPE_READ_ORIG_IND;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SqliteWrapper;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.provider.Telephony.Mms;
import android.provider.Telephony.Mms.Inbox;
import android.util.Log;

import com.android.mms.LogTag;
import com.android.mms.MmsConfig;
import com.android.mms.ui.MessageUtils;
import com.android.mms.ui.MessagingPreferenceActivity;
import com.android.mms.util.DownloadManager;
import com.android.mms.util.Recycler;
import com.android.mms.widget.MmsWidgetProvider;
import com.android.mms.R;
import com.android.mmswrapper.ConstantsWrapper;
import com.google.android.mms.ContentType;
import com.google.android.mms.MmsException;
import com.google.android.mms.pdu.DeliveryInd;
import com.google.android.mms.pdu.GenericPdu;
import com.google.android.mms.pdu.NotificationInd;
import com.google.android.mms.pdu.PduHeaders;
import com.google.android.mms.pdu.PduParser;
import com.google.android.mms.pdu.PduPersister;
import com.google.android.mms.pdu.ReadOrigInd;

import java.lang.Class;
import java.lang.reflect.Method;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * Receives Intent.WAP_PUSH_RECEIVED_ACTION intents and starts the
 * TransactionService by passing the push-data to it.
 */
public class PushReceiver extends BroadcastReceiver {
    private static final String TAG = LogTag.TAG;
    private static final boolean DEBUG = false;
    private static final boolean LOCAL_LOGV = false;
    private Context mContext;
    private static final String WAP_PUSH_MESSAGE = "pref_key_enable_wap_push";
    private static final String WAP_PUSH_TYPE_SIC = "application/vnd.wap.sic";
    private static final String WAP_PUSH_TYPE_SLC = "application/vnd.wap.slc";
    private static final String WAP_PUSH = ":Browser Information"; // Wap push key
    private static final String WAP_PUSH_DEFAULT_ADDRESS = "Browser Information";
    private class ReceivePushTask extends AsyncTask<Intent,Void,Void> {
        private Context mContext;
        public ReceivePushTask(Context context) {
            mContext = context;
        }


    int hexCharToInt(char c) {
        if (c >= '0' && c <= '9') return (c - '0');
        if (c >= 'A' && c <= 'F') return (c - 'A' + 10);
        if (c >= 'a' && c <= 'f') return (c - 'a' + 10);

        throw new RuntimeException ("invalid hex char '" + c + "'");
    }


    /**
     * Converts a byte array into a String of hexadecimal characters.
     *
     * @param bytes an array of bytes
     *
     * @return hex string representation of bytes array
     */
    public String bytesToHexString(byte[] bytes) {
        if (bytes == null) return null;

        StringBuilder ret = new StringBuilder(2*bytes.length);

        for (int i = 0 ; i < bytes.length ; i++) {
            int b;

            b = 0x0f & (bytes[i] >> 4);

            ret.append("0123456789abcdef".charAt(b));

            b = 0x0f & bytes[i];

            ret.append("0123456789abcdef".charAt(b));
        }

        return ret.toString();
    }


        @Override
        protected Void doInBackground(Intent... intents) {
            Intent intent = intents[0];

            // Get raw PDU push-data from the message and parse it
            byte[] pushData = intent.getByteArrayExtra("data");
            if (DEBUG) {
                Log.d(TAG, "PushReceive: pushData= " + bytesToHexString(pushData));
            }
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
            boolean wapPushEnabled = prefs.getBoolean(WAP_PUSH_MESSAGE, true);

            if (wapPushEnabled && (WAP_PUSH_TYPE_SIC.equals(intent.getType())
                    || WAP_PUSH_TYPE_SLC.equals(intent.getType()))) {
                ByteArrayInputStream bais = new ByteArrayInputStream(pushData);
                try {
                    Class mWapPushHandler = Class.forName("com.qrd.wappush.WapPushHandler");
                    Object WapPushHandlerObj = mWapPushHandler.newInstance();
                    Method mHandleWapPush = mWapPushHandler.getDeclaredMethod("handleWapPush",
                            InputStream.class, String.class, Context.class,
                            int.class, String.class);
                    Method mGetThreadID = mWapPushHandler.getDeclaredMethod("getThreadID");
                    String address = intent.getStringExtra("address");
                    if (address == null) {
                        address = WAP_PUSH_DEFAULT_ADDRESS;
                    }
                    Uri pushMsgUri = (Uri)mHandleWapPush.invoke(WapPushHandlerObj, bais,
                            intent.getType(), mContext,
                            intent.getIntExtra(ConstantsWrapper.Phone.PHONE_KEY, 0),
                            address + WAP_PUSH);

                    if (pushMsgUri != null) {
                        // Called off of the UI thread so ok to block.
                        Recycler.getSmsRecycler().deleteOldMessagesByThreadId(
                            mContext.getApplicationContext(),
                            (Long)mGetThreadID.invoke(WapPushHandlerObj));
                        MessagingNotification.blockingUpdateNewMessageIndicator(
                            mContext, (Long)mGetThreadID.invoke(WapPushHandlerObj), false);
                        MmsWidgetProvider.notifyDatasetChanged(mContext);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Wap Push Hander Error :" + e);
                }

                return null;
            }
            PduParser parser = new PduParser(pushData,
                    PduParserUtil.shouldParseContentDisposition());
            GenericPdu pdu = parser.parse();

            if (null == pdu) {
                Log.e(TAG, "Invalid PUSH data");
                return null;
            }

            PduPersister p = PduPersister.getPduPersister(mContext);
            ContentResolver cr = mContext.getContentResolver();
            int type = pdu.getMessageType();
            long threadId = -1;

            try {
                switch (type) {
                    case MESSAGE_TYPE_DELIVERY_IND:
                    case MESSAGE_TYPE_READ_ORIG_IND: {
                        threadId = findThreadId(mContext, pdu, type);
                        if (threadId == -1) {
                            // The associated SendReq isn't found, therefore skip
                            // processing this PDU.
                            break;
                        }

                        Uri uri = p.persist(pdu, Inbox.CONTENT_URI, true,
                                MessagingPreferenceActivity.getIsGroupMmsEnabled(mContext), null);
                        // Update thread ID for ReadOrigInd & DeliveryInd.
                        ContentValues values = new ContentValues(1);
                        values.put(Mms.THREAD_ID, threadId);
                        SqliteWrapper.update(mContext, cr, uri, values, null, null);
                        break;
                    }
                    case MESSAGE_TYPE_NOTIFICATION_IND: {
                        NotificationInd nInd = (NotificationInd) pdu;

                        if (MmsConfig.getTransIdEnabled()) {
                            byte [] contentLocation = nInd.getContentLocation();
                            if ('=' == contentLocation[contentLocation.length - 1]) {
                                byte [] transactionId = nInd.getTransactionId();
                                byte [] contentLocationWithId = new byte [contentLocation.length
                                                                          + transactionId.length];
                                System.arraycopy(contentLocation, 0, contentLocationWithId,
                                        0, contentLocation.length);
                                System.arraycopy(transactionId, 0, contentLocationWithId,
                                        contentLocation.length, transactionId.length);
                                nInd.setContentLocation(contentLocationWithId);
                            }
                        }

                        if (!isDuplicateNotification(mContext, nInd)) {
                            int subId = intent.getIntExtra(ConstantsWrapper.Phone.SUBSCRIPTION_KEY, 0);
                            //Phone ID will be updated in data base
                            Log.d(TAG, "PushReceiver subId : " + subId);
                            ContentValues values = new ContentValues(1);
                            values.put(Mms.SUBSCRIPTION_ID, subId);
                            Uri uri = p.persist(pdu, Inbox.CONTENT_URI,
                                    true,
                                    MessagingPreferenceActivity.getIsGroupMmsEnabled(mContext),
                                    null);
                            SqliteWrapper.update(mContext, cr, uri, values, null, null);
                            String address = pdu.getFrom().getString();
                            threadId = MessagingNotification.getThreadId(mContext, uri);
                            MessageUtils
                                    .markAsNotificationThreadIfNeed(mContext, threadId, address);

                            if (!DownloadManager.getInstance().isAuto() &&
                                    !MessageUtils.isMobileDataEnabled(mContext, subId)) {
                                Log.d(TAG, "Auto retrieve is off, and mobile data is off, do not" +
                                        " download the MMS, just update" +
                                        " new message notification for thread " + threadId);
                                MessagingNotification.blockingUpdateNewMessageIndicator(mContext,
                                        threadId, false);
                                break;
                            }

                            // Start service to finish the notification transaction.
                            Intent svc = new Intent(mContext, TransactionService.class);
                            svc.putExtra(TransactionBundle.URI, uri.toString());
                            svc.putExtra(TransactionBundle.TRANSACTION_TYPE,
                                    Transaction.NOTIFICATION_TRANSACTION);
                            svc.putExtra(Mms.SUBSCRIPTION_ID, subId);
                            mContext.startService(svc);
                        } else if (LOCAL_LOGV) {
                            Log.v(TAG, "Skip downloading duplicate message: "
                                    + new String(nInd.getContentLocation()));
                        }
                        break;
                    }
                    default:
                        Log.e(TAG, "Received unrecognized PDU.");
                }
            } catch (MmsException e) {
                Log.e(TAG, "Failed to save the data from PUSH: type=" + type, e);
            } catch (RuntimeException e) {
                Log.e(TAG, "Unexpected RuntimeException.", e);
            }

            if (LOCAL_LOGV) {
                Log.v(TAG, "PUSH Intent processed.");
            }

            return null;
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!MessageUtils.hasBasicPermissions()){
            Log.d(TAG, "PushReceiver do not have basic permissions");
            return;
        }
        if (intent.getAction().equals(WAP_PUSH_DELIVER_ACTION)
                && (ContentType.MMS_MESSAGE.equals(intent.getType())
                || WAP_PUSH_TYPE_SIC.equals(intent.getType())
                || WAP_PUSH_TYPE_SLC.equals(intent.getType())))  {
            if (LOCAL_LOGV) {
                Log.v(TAG, "Received PUSH Intent: " + intent);
            }

            // Hold a wake lock for 5 seconds, enough to give any
            // services we start time to take their own wake locks.
            PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                                            "MMS PushReceiver");
            wl.acquire(5000);
            new ReceivePushTask(context).execute(intent);
        }
    }

    private static long findThreadId(Context context, GenericPdu pdu, int type) {
        String messageId;

        if (type == MESSAGE_TYPE_DELIVERY_IND) {
            messageId = new String(((DeliveryInd) pdu).getMessageId());
        } else {
            messageId = new String(((ReadOrigInd) pdu).getMessageId());
        }

        StringBuilder sb = new StringBuilder('(');
        sb.append(Mms.MESSAGE_ID);
        sb.append('=');
        sb.append(DatabaseUtils.sqlEscapeString(messageId));
        sb.append(" AND ");
        sb.append(Mms.MESSAGE_TYPE);
        sb.append('=');
        sb.append(PduHeaders.MESSAGE_TYPE_SEND_REQ);
        // TODO ContentResolver.query() appends closing ')' to the selection argument
        // sb.append(')');

        Cursor cursor = SqliteWrapper.query(context, context.getContentResolver(),
                            Mms.CONTENT_URI, new String[] { Mms.THREAD_ID },
                            sb.toString(), null, null);
        if (cursor != null) {
            try {
                if ((cursor.getCount() == 1) && cursor.moveToFirst()) {
                    return cursor.getLong(0);
                }
            } finally {
                cursor.close();
            }
        }

        return -1;
    }

    private static boolean isDuplicateNotification(
            Context context, NotificationInd nInd) {
        byte[] rawLocation = nInd.getContentLocation();
        if (rawLocation != null) {
            String location = new String(rawLocation);
            String selection = Mms.CONTENT_LOCATION + " = ?";
            String[] selectionArgs = new String[] { location };
            Cursor cursor = SqliteWrapper.query(
                    context, context.getContentResolver(),
                    Mms.CONTENT_URI, new String[] { Mms._ID },
                    selection, selectionArgs, null);
            if (cursor != null) {
                try {
                    if (cursor.getCount() > 0) {
                        // We already received the same notification before.
                        return true;
                    }
                } finally {
                    cursor.close();
                }
            }
        }
        return false;
    }
}
