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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class WeatherFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    private static final String TAG = "WeatherFace";
    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<WeatherFace.Engine> mWeakReference;

        public EngineHandler(WeatherFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            WeatherFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine
                         implements DataApi.DataListener,
                                    GoogleApiClient.ConnectionCallbacks,
                                    GoogleApiClient.OnConnectionFailedListener {


        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTimePaint;
        Paint mTempsPaint;
        Paint mDatePaint;
        Bitmap mConditionArt;

        boolean mAmbient;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        int mTapCount;

        float mXOffset;
        float mYOffset;
        float mLineHeight;

        // Wear API data members
        int mWeatherId;
        String mTempHi;
        String mTempLow;
        String mShortDesc;
        String mUnitFormat;
        String localNodeId;

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(WeatherFace.this)
                                                            .addConnectionCallbacks(this)
                                                            .addOnConnectionFailedListener(this)
                                                            .addApi(Wearable.API).build();

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(WeatherFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());


            Resources resources = WeatherFace.this.getResources();


            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mLineHeight = resources.getDimension(R.dimen.line_space_size);

            mBackgroundPaint = new Paint();

            //Set Background color to Sunshine Blue
            mBackgroundPaint.setColor(resources.getColor(R.color.sunshine_blue));

            mTimePaint = new Paint();
            mTimePaint = createTextPaint(resources.getColor(R.color.digital_text));

            mTempsPaint = new Paint();
            mTempsPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mDatePaint = new Paint();
            mDatePaint = createTextPaint(resources.getColor(R.color.digital_text));



            mTime = new Time();

            //Set Weather Defaults
            mTempHi = "50°";
            mTempLow = "50°";
            mWeatherId = 800; //default to clear
            mConditionArt = null;
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
                mGoogleApiClient.connect();

                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            WeatherFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            WeatherFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = WeatherFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTimePaint.setTextSize(resources.getDimension(R.dimen.digital_text_size_round));

            mTempsPaint.setTextSize(resources.getDimension(R.dimen.temps_text_size));

            mDatePaint.setTextSize(resources.getDimension(R.dimen.date_text_size));
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
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimePaint.setAntiAlias(!inAmbientMode);

                    mTempsPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = WeatherFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.sunshine_blue : R.color.sunshine_blue));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {

            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            //Figure out the Center X and Y
            float centerX = bounds.width() / 2f;
            float centerY = bounds.height() / 2f;

            //Local YOffset
            float faceYOffset = mYOffset;



            //Figure out the date
            Calendar today = Calendar.getInstance();

            String short_month = today.getDisplayName(Calendar.MONTH,Calendar.SHORT, Locale.US);
            int day_of_month = today.get(Calendar.DAY_OF_MONTH);

            String printDate = short_month + " " + day_of_month;

            Log.d(TAG, "onDraw: Date to draw: " + printDate);


            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();
            String digitalTime = String.format("%d:%02d", mTime.hour, mTime.minute);

            //Figure out the offset to draw in the exact center
            float timeWidth = mTimePaint.measureText(digitalTime);

            mXOffset = centerX - (timeWidth / 2f);

            Log.d(TAG, "onDraw: Time: XOffset,YOffset - " + mXOffset + ", " + mYOffset);

            canvas.drawText(digitalTime, mXOffset, faceYOffset, mTimePaint);

            //Calculate Center
            mXOffset = centerX - (mDatePaint.measureText(printDate)/2f);

            //Calcluate the new YOffset
            faceYOffset += calcTextHeight(mTimePaint,digitalTime);

            Log.d(TAG, "onDraw: Date: XOffset,YOffset - " + mXOffset + ", " + faceYOffset);

            //Draw today's date
            canvas.drawText(printDate, mXOffset, faceYOffset, mDatePaint);


            if (!mAmbient) {
                //Draw the Temps if they exists
                if (mTempHi != null && mTempLow != null) {
                    //Make a decent looking Temp string
                    String watchTemps = mTempHi + " | " + mTempLow + " | ";

                    //Calculate Center
                    mXOffset = centerX - (mTempsPaint.measureText(watchTemps) / 2f) - (getResources().getDimension(R.dimen.weather_icon_size));

                    //Calculate the new YOffset
                    faceYOffset += calcTextHeight(mDatePaint, printDate);

                    Log.d(TAG, "onDraw: Temps: XOffset,YOffset - " + mXOffset + ", " + faceYOffset + mLineHeight);

                    canvas.drawText(watchTemps, mXOffset, faceYOffset + mLineHeight, mTempsPaint);

                    if (mConditionArt != null && !mAmbient) {
                        Log.d(TAG, "onDraw: drawing weather art");

                        //Calculate Center
                        mXOffset = centerX + (mTempsPaint.measureText(watchTemps) / 2f);

                        Log.d(TAG, "onDraw: Icon: XOffset, YOffset - " + mXOffset + ", " + faceYOffset);

                        canvas.drawBitmap(mConditionArt, mXOffset - mLineHeight, faceYOffset - calcTextHeight(mTempsPaint, printDate) + (mLineHeight/2f), null);
                    }
                }
            }



        }

        private int calcTextHeight(Paint p, String s){
            Rect r = new Rect();
            p.getTextBounds(s,0,s.length(),r );

            return r.height();
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        private void extractWeatherData(DataMap data) {
            //App Context
            Context context = getBaseContext();
            if (data != null) {
                //Grab the data
                mWeatherId = data.getInt(getString(R.string.wear_cond_key));
                mTempHi = data.getString(getString(R.string.wear_hi_key));
                mTempLow = data.getString(getString(R.string.wear_low_key));
                mShortDesc = data.getString(getString(R.string.wear_short_desc_key));
                mUnitFormat = data.getString(getString(R.string.wear_units_key));

                //Set the Bitmap for Weather Condition
                BitmapDrawable weatherDrawable =
                        (BitmapDrawable) getResources().getDrawable(WeatherFaceUtility.getArtResourceForWeatherCondition(mWeatherId),null);

                Bitmap origBitmap = null;
                if (weatherDrawable != null ) {
                    origBitmap = weatherDrawable.getBitmap();
                    Log.d(TAG, "extractWeatherData: decoded a bitmap for the weather art");
                }


                float scaledSize = getResources().getDimension(R.dimen.weather_icon_size);

                mConditionArt = Bitmap.createScaledBitmap(origBitmap,
                                                          WeatherFaceUtility.dipToPixels(context,scaledSize),
                                                          WeatherFaceUtility.dipToPixels(context,scaledSize),
                                                          true);

                if (mConditionArt != null) {
                    Log.d(TAG, "extractWeatherData: created scaled bitmap for watchface");
                }

                Log.d(TAG, "extractWeatherData: received " + "Weather ID: " + mWeatherId);
                Log.d(TAG, "extractWeatherData: received " + "Hi Temp: " + mTempHi);
                Log.d(TAG, "extractWeatherData: received " + "Low Temp: " + mTempLow);
                Log.d(TAG, "extractWeatherData: received " + "Short Desc: " + mShortDesc);

                invalidate(); //immediate update?
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d(TAG, "onConnected: attaching Listener");

            //Attach Listener
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);

            //Get Local Node info
            Wearable.NodeApi.getLocalNode(mGoogleApiClient).setResultCallback(
                    new ResultCallback<NodeApi.GetLocalNodeResult>() {
                            @Override
                            public void onResult(@NonNull NodeApi.GetLocalNodeResult getLocalNodeResult) {
                                localNodeId = getLocalNodeResult.getNode().getId();
                                Log.d(TAG, "onConnected: This Wear Node: " + localNodeId);

                                //Build the Uri
                                Uri uri = new Uri.Builder()
                                        .scheme("wear")
                                        .path(getString(R.string.wear_weather_path))
                                        .build();

                                Log.d(TAG, "onResult: Built Uri to Query DataApi: " + uri);

                                //Retrieve the DataMap from the DataApi
                                Wearable.DataApi.getDataItems(mGoogleApiClient,uri).setResultCallback(
                                        new ResultCallback<DataItemBuffer>() {
                                            @Override
                                            public void onResult(@NonNull DataItemBuffer dataItemBuffer ) {
                                                if (dataItemBuffer.getStatus().isSuccess() &&
                                                        dataItemBuffer.getCount() != 0) {
                                                    Log.d(TAG, "onResult: Got a Data Buffer!");
                                                    Log.d(TAG, "onResult: DataBuffer has " + dataItemBuffer.getCount() + " items.");

                                                    //we're only expecting one item, so we're just grabbing the first one
                                                    DataItem item = dataItemBuffer.get(0);

                                                    //if this item isn't null, it should be our map
                                                    if (item != null) {
                                                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                                                        Log.d(TAG, "onResult: Extracted map from dataItem");
                                                        extractWeatherData(dataMap);
                                                    }

                                                }

                                                dataItemBuffer.release();
                                            }
                                        }

                                );
                            }
                        });

        }

        @Override
        public void onConnectionSuspended(int cause) {
            Log.d(TAG, "onConnectionSuspended: " + cause);
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            Log.d(TAG, "onDataChanged: ");

            //Attempt to extract Sunshine Data
            for (DataEvent event : dataEvents) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    // DataItem changed
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().equals(getString(R.string.wear_weather_path))) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        extractWeatherData(dataMap);
                    }
                } else if (event.getType() == DataEvent.TYPE_DELETED) {
                    // DataItem deleted
                }
            }
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d(TAG, "onConnectionFailed: " + connectionResult);
        }
    }
}
