/*
 * Copyright (C) 2014 The Android Open Source Project
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

package mahisoft.mywearapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;


public class MyWatchFace
        extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);
    private static final int MSG_UPDATE_TIME = 0;


    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler
            extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine
            extends CanvasWatchFaceService.Engine
            implements   GoogleApiClient.ConnectionCallbacks,
                         GoogleApiClient.OnConnectionFailedListener,
                         DataApi.DataListener{
        private static final String WEATHER_PATH = "/weather";
        private static final String ID_KEY = "weather_id";
        private static final String LOW_KEY = "low_temp";
        private static final String HIGH_KEY = "high_temp";
        private final Handler mUpdateTimeHandler = new EngineHandler(this);
        private boolean mRegisteredTimeZoneReceiver = false;
        private Paint mBackgroundPaint;
        private boolean mAmbient;
        private float mXOffsetTime;
        private float mYOffsetTime;
        private float mYOffsetMinute;
        private float mXOffsetIcon;
        private float mYOffsetIcon;
        private float mXOffsetLowTemp;
        private float mYOffsetLowTemp;
        private float mXOffsetHighTemp;
        private float mYOffsetHighTemp;
        private boolean mLowBitAmbient;
        private Bitmap mIconBitmap;
        private int mWeatherId = 800;
        private double mLowTemp = 0;
        private double mHighTemp = 0;
        private boolean mIsRound;
        private Paint mTextTimePaint;
        private Paint mTextTempPaint;
        private Calendar mCalendar;
        private GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(MyWatchFace.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();
        private final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };


        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                                      .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                                      .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                                      .setShowSystemUiTime(false)
                                      .setAcceptsTapEvents(true)
                                      .build());
            Resources resources = MyWatchFace.this.getResources();

            mYOffsetTime = resources.getDimension(R.dimen.digital_y_offset_time);
            mYOffsetMinute = resources.getDimension(R.dimen.digital_y_offset_minute_ambient);
            mYOffsetIcon = resources.getDimension(R.dimen.digital_y_offset_icon);
            mYOffsetLowTemp = resources.getDimension(R.dimen.digital_y_offset_low_temp);
            mYOffsetHighTemp = resources.getDimension(R.dimen.digital_y_offset_high_temp);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.sunshine));

            mIconBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.art_clear);

            mTextTimePaint = new Paint();
            mTextTimePaint = createTextPaint(resources.getColor(R.color.primary_text));

            mTextTempPaint = new Paint();
            mTextTempPaint = createTextPaint(resources.getColor(R.color.secondary_text));

            mCalendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                mCalendar.setTimeZone(TimeZone.getDefault());
                mGoogleApiClient.connect();
            } else {
                unregisterReceiver();
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }
           updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            Resources resources = MyWatchFace.this.getResources();
            mIsRound = insets.isRound();
            mXOffsetTime = resources.getDimension(mIsRound
                                                          ? R.dimen.digital_x_offset_round_time : R.dimen.digital_x_offset_time);
            mXOffsetIcon = resources.getDimension(mIsRound
                                                          ? R.dimen.digital_x_offset_round_icon : R.dimen.digital_x_offset_icon);
            mXOffsetLowTemp = resources.getDimension(mIsRound
                                                             ? R.dimen.digital_x_offset_round_low_temp : R.dimen.digital_x_offset_low_temp);
            mXOffsetHighTemp = resources.getDimension(mIsRound
                                                              ? R.dimen.digital_x_offset_round_high_temp : R.dimen.digital_x_offset_high_temp);

            mTextTimePaint.setTextSize(resources.getDimension(R.dimen.time_text_size));
            mTextTempPaint.setTextSize(resources.getDimension(R.dimen.temp_text_size));
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                Resources resources = MyWatchFace.this.getResources();
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextTimePaint.setAntiAlias(!inAmbientMode);
                    mTextTempPaint.setAntiAlias(!inAmbientMode);
                }

                float textSizeTime = resources.getDimension(mAmbient
                                                                    ? R.dimen.time_text_size_ambient : R.dimen.time_text_size);
                float textSizeTemp = resources.getDimension(mAmbient
                                                                    ? R.dimen.time_text_size : R.dimen.temp_text_size);
                mTextTimePaint.setTextSize(textSizeTime);
                mTextTempPaint.setTextSize(textSizeTemp);
                mTextTempPaint.setColor(resources.getColor(mAmbient ? R.color.primary_text:R.color.secondary_text));

                if (mAmbient) {
                    mXOffsetTime = resources.getDimension(mIsRound
                                                                  ? R.dimen.digital_x_offset_round_time_ambient : R.dimen.digital_x_offset_time);
                    mYOffsetTime = resources.getDimension(R.dimen.digital_y_offset_time_ambient);
                    mYOffsetHighTemp = resources.getDimension(R.dimen.digital_y_offset_high_temp_ambient);
                    mXOffsetHighTemp = resources.getDimension(mIsRound ? R.dimen.digital_x_offset_round_high_temp_ambient:
                                                                      R.dimen.digital_x_offset_high_temp_ambient);
                }else {
                    mXOffsetTime = resources.getDimension(mIsRound
                                                                  ? R.dimen.digital_x_offset_round_time : R.dimen.digital_x_offset_time);
                    mYOffsetTime = resources.getDimension(R.dimen.digital_y_offset_time);
                    mYOffsetHighTemp = resources.getDimension(R.dimen.digital_y_offset_high_temp);
                    mXOffsetHighTemp = resources.getDimension(mIsRound ? R.dimen.digital_x_offset_round_high_temp:
                                                                      R.dimen.digital_x_offset_high_temp);
                }
                invalidate();
            }
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            boolean is24Hour = DateFormat.is24HourFormat(MyWatchFace.this);
            int hour = is24Hour ? mCalendar.get(Calendar.HOUR_OF_DAY): mCalendar.get(Calendar.HOUR);

            if (!is24Hour && hour == 0) {
                hour = 12;
            }

            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                mBackgroundPaint.setColor(getResources().getColor(Utility.getColorBackgroundByTime(mCalendar.get(Calendar.HOUR_OF_DAY))));
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
                canvas.drawBitmap(mIconBitmap, mXOffsetIcon, mYOffsetIcon, mBackgroundPaint);
            }
            Locale locale = Locale.getDefault();
            String highTempText = String.format(locale, "%.0f", mHighTemp) + "\u00B0";

            if (!mAmbient) {
                String text = String.format(locale, "%02d:%02d:%02d", hour, mCalendar.get(Calendar.MINUTE), mCalendar.get(Calendar.SECOND));
                canvas.drawText(text, mXOffsetTime, mYOffsetTime, mTextTimePaint);
                String lowTempText = String.format(locale, "%.0f", mLowTemp) + "\u00B0";
                canvas.drawText(lowTempText, mXOffsetLowTemp, mYOffsetLowTemp, mTextTempPaint);
                canvas.drawText(highTempText, mXOffsetHighTemp, mYOffsetHighTemp, mTextTimePaint);
            } else {
                String textHour = String.format(locale, "%02d", hour);
                canvas.drawText(textHour, mXOffsetTime, mYOffsetTime, mTextTimePaint);
                String textMinute = String.format(locale, "%02d", mCalendar.get(Calendar.MINUTE));
                canvas.drawText(textMinute, mXOffsetTime, mYOffsetMinute, mTextTimePaint);
                canvas.drawText(highTempText, mXOffsetHighTemp, mYOffsetHighTemp, mTextTempPaint);
            }
        }

        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
            fetchDataMap();

        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent event : dataEventBuffer) {
                if (event.getDataItem().getUri().getPath().equals(WEATHER_PATH)) {
                    DataMapItem dataMapItem = DataMapItem.fromDataItem(event.getDataItem());
                    updateUiWithDataMap(dataMapItem.getDataMap());
                }
            }
        }

        private void updateUiWithDataMap(DataMap dataMap) {
            mWeatherId = dataMap.getInt(ID_KEY);
            int weatherResource = Utility.getArtResource(mWeatherId);
            if (weatherResource != -1) {
                mIconBitmap = BitmapFactory.decodeResource(getResources(), weatherResource);
            }
            mLowTemp = dataMap.getDouble(LOW_KEY);
            mHighTemp = dataMap.getDouble(HIGH_KEY);
            invalidate();
        }

        private void fetchDataMap() {
            Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).setResultCallback(
                    new ResultCallback<NodeApi.GetConnectedNodesResult>() {
                        @Override
                        public void onResult(NodeApi.GetConnectedNodesResult getNodesResult) {
                            List<Node> nodes = getNodesResult.getNodes();
                            if (nodes.size() > 0) {
                                String remoteNode = nodes.get(0).getId();
                                Uri uri = new Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(WEATHER_PATH)
                                                           .authority(remoteNode)
                                                           .build();
                                Wearable.DataApi.getDataItem(mGoogleApiClient, uri).setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                                    @Override
                                    public void onResult(DataApi.DataItemResult dataItemResult) {
                                        if (dataItemResult.getStatus().isSuccess() && dataItemResult.getDataItem() != null) {
                                            DataItem    configDataItem = dataItemResult.getDataItem();
                                            DataMapItem dataMapItem    = DataMapItem.fromDataItem(configDataItem);
                                            updateUiWithDataMap(dataMapItem.getDataMap());
                                        }
                                    }
                                });
                            }
                        }
                    }
            );
        }
    }
}
