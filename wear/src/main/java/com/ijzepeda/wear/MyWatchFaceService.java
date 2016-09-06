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

package com.ijzepeda.wear;

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
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
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
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static android.R.attr.textColor;
import static android.graphics.Bitmap.createScaledBitmap;

/**
 * Sample digital watch face with blinking colons and seconds. In ambient mode, the seconds are
 * replaced with an AM/PM indicator and the colons don't blink. On devices with low-bit ambient
 * mode, the text is drawn without anti-aliasing in ambient mode. On devices which require burn-in
 * protection, the hours are drawn in normal rather than bold. The time is drawn with less contrast
 * and without seconds in mute mode.
 */
public class MyWatchFaceService extends CanvasWatchFaceService {
    private static final String TAG = ">MyWatchFaceService";

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for normal (not ambient and not mute) mode. We update twice
     * a second to blink the colons.
     */
    private static final long NORMAL_UPDATE_RATE_MS = 500;

    /**
     * Update rate in milliseconds for mute mode. We update every minute, like in ambient mode.
     */
    private static final long MUTE_UPDATE_RATE_MS = TimeUnit.MINUTES.toMillis(1);

    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        static final String COLON_STRING = ":";

        /** Alpha value for drawing time when in mute mode. */
        static final int MUTE_ALPHA = 100;

        /** Alpha value for drawing time when not in mute mode. */
        static final int NORMAL_ALPHA = 255;

        static final int MSG_UPDATE_TIME = 0;

        /** How often {@link #mUpdateTimeHandler} ticks in milliseconds. */
        long mInteractiveUpdateRateMs = NORMAL_UPDATE_RATE_MS;
        private static final String WEATHER_PATH = "/weather";
        private static final String WEATHER_INFO_PATH = "/weather-info";

        private static final String KEY_UUID = "uuid";
        private static final String KEY_HIGH = "high";
        private static final String KEY_LOW = "low";
        private static final String KEY_WEATHER_ID = "weatherId";
        /** Handler to update the time periodically in interactive mode. */
        final Handler mUpdateTimeHandler = new Handler() {

            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_TIME:
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "updating time");
                        }
                        invalidate();
                        if (shouldTimerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs =
                                    mInteractiveUpdateRateMs - (timeMs % mInteractiveUpdateRateMs);
                            mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                        }
                        break;
                }
            }
        };

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(MyWatchFaceService.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        /**
         * Handles time zone and locale changes.
         */
        final BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
                invalidate();
            }
        };

        /**
         * Unregistering an unregistered receiver throws an exception. Keep track of the
         * registration state to prevent that.
         */
        boolean mRegisteredReceiver = false;

        Paint mBackgroundPaint;
              boolean mMute;
        Paint mTextPaint;
        Paint mTextDatePaint;
        Paint mTextDateAmbientPaint;
        Paint mTextTempHighPaint;
        Paint mTextTempLowPaint;
        Paint mTextTempLowAmbientPaint;

        Bitmap mWeatherIcon;

        String mWeatherHigh;
        String mWeatherLow;

        boolean mAmbient;

        Calendar mCalendar;
        Date mDate;
        SimpleDateFormat mDayOfWeekFormat;
        java.text.DateFormat mDateFormat;
        float mXOffsetTime;
        float mXOffsetDate;
        float mXOffsetTimeAmbient;
        float mTimeYOffset;
        float mDateYOffset;
        float mDividerYOffset;
        float mWeatherYOffset;

        boolean mShouldDrawColons;
        float mXOffset;
        float mYOffset;
        float mLineHeight;
        String mAmString;
        String mPmString;
        int mInteractiveBackgroundColor =
                getResources().getColor(R.color.primary);
        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onCreate");
            }
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = MyWatchFaceService.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);
            mLineHeight = resources.getDimension(R.dimen.digital_line_height);
            mAmString = resources.getString(R.string.digital_am);
            mPmString = resources.getString(R.string.digital_pm);

            mTimeYOffset = resources.getDimension(R.dimen.digital_time_y_offset);
            mDateYOffset = resources.getDimension(R.dimen.digital_date_y_offset);
            mDividerYOffset = resources.getDimension(R.dimen.digital_divider_y_offset);
            mWeatherYOffset = resources.getDimension(R.dimen.digital_weather_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.primary));
            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mTextDatePaint = new Paint();
            mTextDatePaint = createTextPaint(resources.getColor(R.color.primary_light));

            mTextDateAmbientPaint = new Paint();
            mTextDateAmbientPaint = createTextPaint(Color.WHITE);

            mTextTempHighPaint = createTextPaintBold(Color.WHITE);
            mTextTempLowPaint = createTextPaint(resources.getColor(R.color.primary_light));
            mTextTempLowAmbientPaint = createTextPaint(Color.WHITE);


//Default icon if hasnt fetch data
            Drawable b = getResources().getDrawable(getWeatherImage(0));
            Bitmap icon = ((BitmapDrawable) b).getBitmap();
            float scaledWidth = (mTextTempHighPaint.getTextSize() / icon.getHeight()) * icon.getWidth();
            mWeatherIcon = Bitmap.createScaledBitmap(icon, (int) scaledWidth, (int) mTextTempHighPaint.getTextSize(), true);

            mCalendar = Calendar.getInstance();
            mDate = new Date();
            initFormats();
        }
        private Paint createTextPaintBold(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(BOLD_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }
        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int defaultInteractiveColor) {
            Paint paint = new Paint();
            paint.setColor(defaultInteractiveColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
//            return createTextPaint(defaultInteractiveColor, NORMAL_TYPEFACE);
        }

        private Paint createTextPaint(int defaultInteractiveColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(defaultInteractiveColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onVisibilityChanged: " + visible);
            }
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();

                registerReceiver();

                // Update time zone and date formats, in case they changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
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

        private void initFormats() {
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
//            mDayOfWeekFormat = new SimpleDateFormat("EEEE", Locale.getDefault());
//            mDayOfWeekFormat.setCalendar(mCalendar);
//            mDateFormat = DateFormat.getDateFormat(MyWatchFaceService.this);
//            mDateFormat.setCalendar(mCalendar);
        }

        private void registerReceiver() {
            if (mRegisteredReceiver) {
                return;
            }
            mRegisteredReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(Intent.ACTION_LOCALE_CHANGED);
            MyWatchFaceService.this.registerReceiver(mReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredReceiver) {
                return;
            }
            mRegisteredReceiver = false;
            MyWatchFaceService.this.unregisterReceiver(mReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onApplyWindowInsets: " + (insets.isRound() ? "round" : "square"));
            }
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFaceService.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float amPmSize = resources.getDimension(isRound
                    ? R.dimen.digital_am_pm_size_round : R.dimen.digital_am_pm_size);
            mXOffsetDate = resources.getDimension(isRound
                    ? R.dimen.digital_date_x_offset_round : R.dimen.digital_date_x_offset);
            mXOffsetTimeAmbient = resources.getDimension(isRound
                    ? R.dimen.digital_time_x_offset_round_ambient : R.dimen.digital_time_x_offset_ambient);
            float timeTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_time_text_size_round : R.dimen.digital_time_text_size);
            float dateTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_date_text_size_round : R.dimen.digital_date_text_size);
            float tempTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_temp_text_size_round : R.dimen.digital_temp_text_size);


            mTextPaint.setTextSize(timeTextSize);
            mTextDatePaint.setTextSize(dateTextSize);
            mTextDateAmbientPaint.setTextSize(dateTextSize);
            mTextTempHighPaint.setTextSize(tempTextSize);
            mTextTempLowAmbientPaint.setTextSize(tempTextSize);
            mTextTempLowPaint.setTextSize(tempTextSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);

//            boolean burnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
//            mHourPaint.setTypeface(burnInProtection ? NORMAL_TYPEFACE : BOLD_TYPEFACE);
//
//            if (Log.isLoggable(TAG, Log.DEBUG)) {
//                Log.d(TAG, "onPropertiesChanged: burn-in protection = " + burnInProtection
//                        + ", low-bit ambient = " + mLowBitAmbient);
//            }
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onTimeTick: ambient = " + isInAmbientMode());
            }
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onAmbientModeChanged: " + inAmbientMode);
            }
//            if (mLowBitAmbient) {
//                boolean antiAlias = !inAmbientMode;
//                mDatePaint.setAntiAlias(antiAlias);
//                mHourPaint.setAntiAlias(antiAlias);
//                mMinutePaint.setAntiAlias(antiAlias);
//                mSecondPaint.setAntiAlias(antiAlias);
//                mAmPmPaint.setAntiAlias(antiAlias);
//                mColonPaint.setAntiAlias(antiAlias);
//            }
//            invalidate();
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                    mTextDatePaint.setAntiAlias(!inAmbientMode);
                    mTextDateAmbientPaint.setAntiAlias(!inAmbientMode);
                    mTextTempHighPaint.setAntiAlias(!inAmbientMode);
                    mTextTempLowAmbientPaint.setAntiAlias(!inAmbientMode);
                    mTextTempLowPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }
            // Whether the timer should be running depends on whether we're in ambient mode (as well
            // as whether we're visible), so we may need to start or stop the timer.
            updateTimer();
        }

        private void adjustPaintColorToCurrentMode(Paint paint, int interactiveColor,
                                                   int ambientColor) {
            paint.setColor(isInAmbientMode() ? ambientColor : interactiveColor);
        }

        @Override
        public void onInterruptionFilterChanged(int interruptionFilter) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onInterruptionFilterChanged: " + interruptionFilter);
            }
            super.onInterruptionFilterChanged(interruptionFilter);

            boolean inMuteMode = interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE;
            // We only need to update once a minute in mute mode.
            setInteractiveUpdateRateMs(inMuteMode ? MUTE_UPDATE_RATE_MS : NORMAL_UPDATE_RATE_MS);

        }

        public void setInteractiveUpdateRateMs(long updateRateMs) {
            if (updateRateMs == mInteractiveUpdateRateMs) {
                return;
            }
            mInteractiveUpdateRateMs = updateRateMs;

            // Stop and restart the timer so the new update rate takes effect immediately.
            if (shouldTimerBeRunning()) {
                updateTimer();
            }
        }



        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            if(mAmbient) {
                canvas.drawColor(Color.BLACK);
            }else{
                canvas.drawRect(0,0,bounds.width(),bounds.height(),mBackgroundPaint);
            }

            //For Ambient remove Seconds, add if Interactive mode on
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            boolean is24Hour =DateFormat.is24HourFormat(MyWatchFaceService.this);

            int mins=mCalendar.get(Calendar.MINUTE);
            int secs=mCalendar.get(Calendar.SECOND);
            int ampm=mCalendar.get(Calendar.AM_PM);
            //Draw the hours
            String timeText;
            if(is24Hour){
                int hour=mCalendar.get(Calendar.HOUR_OF_DAY);
                if(mAmbient)
                    timeText=String.format("%02d:%02d",hour,mins);
                else
                    timeText=String.format("%02d:%02d:%02d",hour,mins,secs);
            }else{
                int hour = mCalendar.get(Calendar.HOUR);
                if(hour==0)
                    hour=12;

                String amPmText=ampm==Calendar.AM?getResources().getString(R.string.digital_am):getResources().getString(R.string.digital_pm);
                if(mAmbient)
                    timeText=String.format("%02d:%02d %s",hour,mins,amPmText);
                else
                    timeText=String.format("%02d:%02d:02d %s",hour,mins,secs,amPmText);
            }

            float xOffsetTime=mTextPaint.measureText(timeText)/2;
            canvas.drawText(timeText,bounds.centerX()-xOffsetTime,mTimeYOffset,mTextPaint);
//switch colors if in ambient
           Paint datePaint = mAmbient ? mTextDateAmbientPaint : mTextDatePaint;


            //Draw Date
            String weekDay= getResources().getStringArray(R.array.weekDay)[mCalendar.get(Calendar.DAY_OF_WEEK)];//getDayOfWeekString(mCalendar.get(Calendar.DAY_OF_WEEK));//mDayOfWeekFormat.format(mDate);
            String month=getResources().getStringArray(R.array.months)[mCalendar.get(Calendar.MONTH)];
            int dayofMonth=mCalendar.get(Calendar.DAY_OF_MONTH);
            int year=mCalendar.get(Calendar.YEAR);
            String dateText=String.format("%s, %s %d %d",weekDay,month,dayofMonth,year);
            float xOffsetDate=datePaint.measureText(dateText)/2;
            canvas.drawText(dateText, bounds.centerX()-xOffsetDate,mDateYOffset,datePaint);




            //time - temperature division line
//            canvas.drawLine(bounds.centerX()-55,mDividerYOffset,bounds.centerX()+55,mDividerYOffset,datePaint);
            /////////////////////////////////////////////////////////////////
//            mWeatherHigh="50~";
//                    mWeatherLow="-12~";
            //TEMPERATURE
            if (mWeatherHigh != null && mWeatherLow != null) {
                canvas.drawLine(bounds.centerX()-55,mDividerYOffset,bounds.centerX()+55,mDividerYOffset,datePaint);

                float highLen = mTextTempHighPaint.measureText(mWeatherHigh);

                if (mAmbient) {
                    float lowTextLen = mTextTempLowAmbientPaint.measureText(mWeatherLow);
                    float xOffset = bounds.centerX()-((highLen+lowTextLen+20)/2);
                    canvas.drawText(mWeatherHigh, xOffset, mWeatherYOffset, mTextTempHighPaint);
                    canvas.drawText(mWeatherLow, xOffset+highLen+20, mWeatherYOffset, mTextTempLowAmbientPaint);
                } else {
                    float xOffset = bounds.centerX()-(highLen/2);
                    canvas.drawText(mWeatherHigh, xOffset, mWeatherYOffset, mTextTempHighPaint);
                    canvas.drawText(mWeatherLow, bounds.centerX()+(highLen/2)+20, mWeatherYOffset, mTextTempLowPaint);
                    float iconXOffset = bounds.centerX()-((highLen/2)+mWeatherIcon.getWidth() + 30);
                    canvas.drawBitmap(mWeatherIcon, iconXOffset, mWeatherYOffset - mWeatherIcon.getHeight(), null);
                }
            }


//            long now = System.currentTimeMillis();
//            mCalendar.setTimeInMillis(now);
//            mDate.setTime(now);
//            boolean is24Hour = DateFormat.is24HourFormat(MyWatchFaceService.this);
//
//            // Show colons for the first half of each second so the colons blink on when the time
//            // updates.
//            mShouldDrawColons = (System.currentTimeMillis() % 1000) < 500;
//
//            // Draw the background.
//            canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
//
//            // Draw the hours.
//            float x = mXOffset;
//            String hourString;
//            if (is24Hour) {
//                hourString = formatTwoDigitNumber(mCalendar.get(Calendar.HOUR_OF_DAY));
//            } else {
//                int hour = mCalendar.get(Calendar.HOUR);
//                if (hour == 0) {
//                    hour = 12;
//                }
//                hourString = String.valueOf(hour);
//            }
//            canvas.drawText(hourString, x, mYOffset, mHourPaint);
//            x += mHourPaint.measureText(hourString);
//
//            // In ambient and mute modes, always draw the first colon. Otherwise, draw the
//            // first colon for the first half of each second.
//            if (isInAmbientMode() || mMute || mShouldDrawColons) {
//                canvas.drawText(COLON_STRING, x, mYOffset, mColonPaint);
//            }
//            x += mColonWidth;
//
//            // Draw the minutes.
//            String minuteString = formatTwoDigitNumber(mCalendar.get(Calendar.MINUTE));
//            canvas.drawText(minuteString, x, mYOffset, mMinutePaint);
//            x += mMinutePaint.measureText(minuteString);
//
//            // In unmuted interactive mode, draw a second blinking colon followed by the seconds.
//            // Otherwise, if we're in 12-hour mode, draw AM/PM
//            if (!isInAmbientMode() && !mMute) {
//                if (mShouldDrawColons) {
//                    canvas.drawText(COLON_STRING, x, mYOffset, mColonPaint);
//                }
//                x += mColonWidth;
//                canvas.drawText(formatTwoDigitNumber(
//                        mCalendar.get(Calendar.SECOND)), x, mYOffset, mSecondPaint);
//            } else if (!is24Hour) {
//                x += mColonWidth;
//                canvas.drawText(getAmPmString(
//                        mCalendar.get(Calendar.AM_PM)), x, mYOffset, mAmPmPaint);
//            }
//
//            // Only render the day of week and date if there is no peek card, so they do not bleed
//            // into each other in ambient mode.
//            if (getPeekCardPosition().isEmpty()) {
//                // Day of week
//                canvas.drawText(
//                        mDayOfWeekFormat.format(mDate),
//                        mXOffset, mYOffset + mLineHeight, mDatePaint);
//                // Date
//                canvas.drawText(
//                        mDateFormat.format(mDate),
//                        mXOffset, mYOffset + mLineHeight * 2, mDatePaint);
//            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "updateTimer");
            }
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



        @Override // DataApi.DataListener
        public void onDataChanged(DataEventBuffer dataEvents) {
            for (DataEvent dataEvent : dataEvents) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {

                    DataMap dataMap = DataMapItem.fromDataItem(dataEvent.getDataItem()).getDataMap();
                    String path = dataEvent.getDataItem().getUri().getPath();
                    Log.d(TAG, path);
//                    if (path.equals(WEATHER_PATH)) {
                    if (path.equals(WEATHER_INFO_PATH)) {
                        if (dataMap.containsKey(KEY_HIGH)) {
                            mWeatherHigh = dataMap.getString(KEY_HIGH);
                        }

                        if (dataMap.containsKey(KEY_LOW)) {
                            mWeatherLow = dataMap.getString(KEY_LOW);
                        }

                        if (dataMap.containsKey(KEY_WEATHER_ID)) {
                            int weatherId = dataMap.getInt(KEY_WEATHER_ID);
                            Drawable b = getResources().getDrawable(getWeatherImage(weatherId));
                            Bitmap icon = ((BitmapDrawable) b).getBitmap();
                            float scaledWidth = (mTextTempHighPaint.getTextSize() / icon.getHeight()) * icon.getWidth();
                            mWeatherIcon = createScaledBitmap(icon, (int) scaledWidth, (int) mTextTempHighPaint.getTextSize(), true);
                        }

                        invalidate();
                    }
                }

//                DataItem dataItem = dataEvent.getDataItem();
//                if (!dataItem.getUri().getPath().equals(
//                        DigitalWatchFaceUtil.PATH_WITH_FEATURE)) {
//                    continue;
//                }
//
//                DataMapItem dataMapItem = DataMapItem.fromDataItem(dataItem);
//                DataMap config = dataMapItem.getDataMap();
//                if (Log.isLoggable(TAG, Log.DEBUG)) {
//                    Log.d(TAG, "Config DataItem updated:" + config);
//                }
//                updateUiForConfigDataMap(config);
            }
        }



        @Override  // GoogleApiClient.ConnectionCallbacks
        public void onConnected(Bundle connectionHint) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnected: " + connectionHint);
            }
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
            requestWeather();

//            updateConfigDataItemAndUiOnStartup();
        }

        @Override  // GoogleApiClient.ConnectionCallbacks
        public void onConnectionSuspended(int cause) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionSuspended: " + cause);
            }
        }

        @Override  // GoogleApiClient.OnConnectionFailedListener
        public void onConnectionFailed(ConnectionResult result) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionFailed: " + result);
            }
        }


        public void requestWeather() {
            PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(WEATHER_PATH);
            putDataMapRequest.getDataMap().putString("uuid", UUID.randomUUID().toString());
            PutDataRequest request = putDataMapRequest.asPutDataRequest();

            Wearable.DataApi.putDataItem(mGoogleApiClient, request)
                    .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                        @Override
                        public void onResult(DataApi.DataItemResult dataItemResult) {
                            if (!dataItemResult.getStatus().isSuccess()) {
                                Log.d(TAG, "ndp6<<Failed asking phone for weather data");
                            } else {
                                Log.d(TAG, "ndp6<<Successfully asked for weather data");
                            }
                        }
                    });
        }


    }

    public static int getWeatherImage(int weatherId) {
        // Based on weather code data found at:
        // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
        if (weatherId >= 200 && weatherId <= 232) {
            return R.drawable.ic_storm;
        } else if (weatherId >= 300 && weatherId <= 321) {
            return R.drawable.ic_light_rain;
        } else if (weatherId >= 500 && weatherId <= 504) {
            return R.drawable.ic_rain;
        } else if (weatherId == 511) {
            return R.drawable.ic_snow;
        } else if (weatherId >= 520 && weatherId <= 531) {
            return R.drawable.ic_rain;
        } else if (weatherId >= 600 && weatherId <= 622) {
            return R.drawable.ic_snow;
        } else if (weatherId >= 701 && weatherId <= 761) {
            return R.drawable.ic_fog;
        } else if (weatherId == 761 || weatherId == 781) {
            return R.drawable.ic_storm;
        } else if (weatherId == 800) {
            return R.drawable.ic_clear;
        } else if (weatherId == 801) {
            return R.drawable.ic_light_clouds;
        } else if (weatherId >= 802 && weatherId <= 804) {
            return R.drawable.ic_cloudy;
        }else if(weatherId==0){
            return R.drawable.icn_default;
        }
        return -1;
    }
}
