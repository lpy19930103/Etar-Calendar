/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.calendar;

import com.android.calendar.event.EditEventHelper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.database.Cursor;
import android.net.Uri;
import android.pim.EventRecurrence;
import android.provider.Calendar;
import android.provider.Calendar.Events;
import android.text.TextUtils;
import android.text.format.Time;
import android.widget.Button;

/**
 * A helper class for deleting events.  If a normal event is selected for
 * deletion, then this pops up a confirmation dialog.  If the user confirms,
 * then the normal event is deleted.
 *
 * <p>
 * If a repeating event is selected for deletion, then this pops up dialog
 * asking if the user wants to delete just this one instance, or all the
 * events in the series, or this event plus all following events.  The user
 * may also cancel the delete.
 * </p>
 *
 * <p>
 * To use this class, create an instance, passing in the parent activity
 * and a boolean that determines if the parent activity should exit if the
 * event is deleted.  Then to use the instance, call one of the
 * {@link delete()} methods on this class.
 *
 * An instance of this class may be created once and reused (by calling
 * {@link #delete()} multiple times).
 */
public class DeleteEventHelper {
    private final Activity mParent;
    private Context mContext;

    private long mStartMillis;
    private long mEndMillis;
    private CalendarEventModel mModel;

    /**
     * If true, then call finish() on the parent activity when done.
     */
    private boolean mExitWhenDone;
    // the runnable to execute when the delete is confirmed
    private Runnable mCallback;

    /**
     * These are the corresponding indices into the array of strings
     * "R.array.delete_repeating_labels" in the resource file.
     */
    public static final int DELETE_SELECTED = 0;
    public static final int DELETE_ALL_FOLLOWING = 1;
    public static final int DELETE_ALL = 2;

    private int mWhichDelete;
    private AlertDialog mAlertDialog;

    private String mSyncId;

    private AsyncQueryService mService;

    public DeleteEventHelper(Context context, Activity parentActivity, boolean exitWhenDone) {
        if (exitWhenDone && parentActivity == null) {
            throw new IllegalArgumentException("parentActivity is required to exit when done");
        }

        mContext = context;
        mParent = parentActivity;
        // TODO move the creation of this service out into the activity.
        mService = new AsyncQueryService(mContext) {
            @Override
            protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
                if (cursor == null) {
                    return;
                }
                cursor.moveToFirst();
                CalendarEventModel mModel = new CalendarEventModel();
                EditEventHelper.setModelFromCursor(mModel, cursor);
                cursor.close();
                DeleteEventHelper.this.delete(mStartMillis, mEndMillis, mModel, mWhichDelete);
            }
        };
        mExitWhenDone = exitWhenDone;
    }

    public void setAsyncQueryService(AsyncQueryService service) {
        mService = service;
    }

    public void setExitWhenDone(boolean exitWhenDone) {
        mExitWhenDone = exitWhenDone;
    }

    /**
     * This callback is used when a normal event is deleted.
     */
    private DialogInterface.OnClickListener mDeleteNormalDialogListener =
            new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface dialog, int button) {
            long id = mModel.mId; // mCursor.getInt(mEventIndexId);
            Uri uri = ContentUris.withAppendedId(Calendar.Events.CONTENT_URI, id);
            mService.startDelete(mService.getNextToken(), null, uri, null, null, Utils.UNDO_DELAY);
            if (mCallback != null) {
                mCallback.run();
            }
            if (mExitWhenDone) {
                mParent.finish();
            }
        }
    };

    /**
     * This callback is used when an exception to an event is deleted
     */
    private DialogInterface.OnClickListener mDeleteExceptionDialogListener =
        new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface dialog, int button) {
            deleteExceptionEvent();
            if (mCallback != null) {
                mCallback.run();
            }
            if (mExitWhenDone) {
                mParent.finish();
            }
        }
    };

    /**
     * This callback is used when a list item for a repeating event is selected
     */
    private DialogInterface.OnClickListener mDeleteListListener =
            new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface dialog, int button) {
            mWhichDelete = button;

            // Enable the "ok" button now that the user has selected which
            // events in the series to delete.
            Button ok = mAlertDialog.getButton(DialogInterface.BUTTON_POSITIVE);
            ok.setEnabled(true);
        }
    };

    /**
     * This callback is used when a repeating event is deleted.
     */
    private DialogInterface.OnClickListener mDeleteRepeatingDialogListener =
            new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface dialog, int button) {
            if (mWhichDelete != -1) {
                deleteRepeatingEvent(mWhichDelete);
            }
        }
    };

    /**
     * Does the required processing for deleting an event, which includes
     * first popping up a dialog asking for confirmation (if the event is
     * a normal event) or a dialog asking which events to delete (if the
     * event is a repeating event).  The "which" parameter is used to check
     * the initial selection and is only used for repeating events.  Set
     * "which" to -1 to have nothing selected initially.
     *
     * @param begin the begin time of the event, in UTC milliseconds
     * @param end the end time of the event, in UTC milliseconds
     * @param eventId the event id
     * @param which one of the values {@link DELETE_SELECTED},
     *  {@link DELETE_ALL_FOLLOWING}, {@link DELETE_ALL}, or -1
     */
    public void delete(long begin, long end, long eventId, int which) {
        Uri uri = ContentUris.withAppendedId(Calendar.Events.CONTENT_URI, eventId);
        mService.startQuery(mService.getNextToken(), null, uri, EditEventHelper.EVENT_PROJECTION,
                null, null, null);
        mStartMillis = begin;
        mEndMillis = end;
        mWhichDelete = which;
    }

    public void delete(long begin, long end, long eventId, int which, Runnable callback) {
        delete(begin, end, eventId, which);
        mCallback = callback;
    }

    /**
     * Does the required processing for deleting an event.  This method
     * takes a {@link CalendarEventModel} object, which must have a valid
     * uri for referencing the event in the database and have the required
     * fields listed below.
     * The required fields for a normal event are:
     *
     * <ul>
     *   <li> Events._ID </li>
     *   <li> Events.TITLE </li>
     *   <li> Events.RRULE </li>
     * </ul>
     *
     * The required fields for a repeating event include the above plus the
     * following fields:
     *
     * <ul>
     *   <li> Events.ALL_DAY </li>
     *   <li> Events.CALENDAR_ID </li>
     *   <li> Events.DTSTART </li>
     *   <li> Events._SYNC_ID </li>
     *   <li> Events.EVENT_TIMEZONE </li>
     * </ul>
     *
     * If the event no longer exists in the db this will still prompt
     * the user but will return without modifying the db after the query
     * returns.
     *
     * @param begin the begin time of the event, in UTC milliseconds
     * @param end the end time of the event, in UTC milliseconds
     * @param cursor the database cursor containing the required fields
     * @param which one of the values {@link DELETE_SELECTED},
     *  {@link DELETE_ALL_FOLLOWING}, {@link DELETE_ALL}, or -1
     */
    public void delete(long begin, long end, CalendarEventModel model, int which) {
        mWhichDelete = which;
        mStartMillis = begin;
        mEndMillis = end;
        mModel = model;
        mSyncId = model.mSyncId;

        // If this is a repeating event, then pop up a dialog asking the
        // user if they want to delete all of the repeating events or
        // just some of them.
        String rRule = model.mRrule;
        String originalEvent = model.mOriginalEvent;
        if (TextUtils.isEmpty(rRule)) {
            AlertDialog dialog = new AlertDialog.Builder(mContext).setTitle(R.string.delete_title)
                    .setMessage(R.string.delete_this_event_title)
                    .setIconAttribute(android.R.attr.alertDialogIcon)
                    .setNegativeButton(android.R.string.cancel, null).create();

            if (originalEvent == null) {
                // This is a normal event. Pop up a confirmation dialog.
                dialog.setButton(DialogInterface.BUTTON_POSITIVE,
                        mContext.getText(android.R.string.ok),
                        mDeleteNormalDialogListener);
            } else {
                // This is an exception event. Pop up a confirmation dialog.
                dialog.setButton(DialogInterface.BUTTON_POSITIVE,
                        mContext.getText(android.R.string.ok),
                        mDeleteExceptionDialogListener);
            }
            dialog.show();
        } else {
            // This is a repeating event.  Pop up a dialog asking which events
            // to delete.
            int labelsArrayId = R.array.delete_repeating_labels;
            if (mSyncId == null) {
                labelsArrayId = R.array.delete_repeating_labels_no_selected;
                if (which > 0) {
                    which--;
                    mWhichDelete--;
                }
            }
            AlertDialog dialog = new AlertDialog.Builder(mContext).setTitle(R.string.delete_title)
                    .setIconAttribute(android.R.attr.alertDialogIcon)
                    .setSingleChoiceItems(labelsArrayId, which, mDeleteListListener)
                    .setPositiveButton(android.R.string.ok, mDeleteRepeatingDialogListener)
                    .setNegativeButton(android.R.string.cancel, null).show();
            mAlertDialog = dialog;

            if (which == -1) {
                // Disable the "Ok" button until the user selects which events
                // to delete.
                Button ok = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
                ok.setEnabled(false);
            }
        }
    }

    private void deleteExceptionEvent() {
        long id = mModel.mId; // mCursor.getInt(mEventIndexId);

        // update a recurrence exception by setting its status to "canceled"
        ContentValues values = new ContentValues();
        values.put(Events.STATUS, Events.STATUS_CANCELED);

        Uri uri = ContentUris.withAppendedId(Calendar.Events.CONTENT_URI, id);
        mService.startUpdate(mService.getNextToken(), null, uri, values, null, null,
                Utils.UNDO_DELAY);
    }

    private void deleteRepeatingEvent(int which) {
        String rRule = mModel.mRrule;
        boolean allDay = mModel.mAllDay;
        long dtstart = mModel.mStart;
        long id = mModel.mId; // mCursor.getInt(mEventIndexId);

        // If the repeating event has not been given a sync id from the server
        // yet, then we can't delete a single instance of this event.  (This is
        // a deficiency in the CalendarProvider and sync code.) We checked for
        // that when creating the list of items in the dialog and we removed
        // the first element ("DELETE_SELECTED") from the dialog in that case.
        // The "which" value is a 0-based index into the list of items, where
        // the "DELETE_SELECTED" item is at index 0.
        if (mSyncId == null) {
            which += 1;
        }

        switch (which) {
            case DELETE_SELECTED: {
                // If we are deleting the first event in the series, then
                // instead of creating a recurrence exception, just change
                // the start time of the recurrence.
                if (dtstart == mStartMillis) {
                    // TODO
                }

                // Create a recurrence exception by creating a new event
                // with the status "cancelled".
                ContentValues values = new ContentValues();

                // The title might not be necessary, but it makes it easier
                // to find this entry in the database when there is a problem.
                String title = mModel.mTitle;
                values.put(Events.TITLE, title);

                String timezone = mModel.mTimezone;
                long calendarId = mModel.mCalendarId;
                values.put(Events.EVENT_TIMEZONE, timezone);
                values.put(Events.ALL_DAY, allDay ? 1 : 0);
                values.put(Events.CALENDAR_ID, calendarId);
                values.put(Events.DTSTART, mStartMillis);
                values.put(Events.DTEND, mEndMillis);
                values.put(Events.ORIGINAL_EVENT, mSyncId);
                values.put(Events.ORIGINAL_INSTANCE_TIME, mStartMillis);
                values.put(Events.STATUS, Events.STATUS_CANCELED);

                mService.startInsert(mService.getNextToken(), null, Events.CONTENT_URI, values,
                        Utils.UNDO_DELAY);
                break;
            }
            case DELETE_ALL: {
                Uri uri = ContentUris.withAppendedId(Calendar.Events.CONTENT_URI, id);
                mService.startDelete(mService.getNextToken(), null, uri, null, null,
                        Utils.UNDO_DELAY);
                break;
            }
            case DELETE_ALL_FOLLOWING: {
                // If we are deleting the first event in the series and all
                // following events, then delete them all.
                if (dtstart == mStartMillis) {
                    Uri uri = ContentUris.withAppendedId(Calendar.Events.CONTENT_URI, id);
                    mService.startDelete(mService.getNextToken(), null, uri, null, null,
                            Utils.UNDO_DELAY);
                    break;
                }

                // Modify the repeating event to end just before this event time
                EventRecurrence eventRecurrence = new EventRecurrence();
                eventRecurrence.parse(rRule);
                Time date = new Time();
                if (allDay) {
                    date.timezone = Time.TIMEZONE_UTC;
                }
                date.set(mStartMillis);
                date.second--;
                date.normalize(false);

                // Google calendar seems to require the UNTIL string to be
                // in UTC.
                date.switchTimezone(Time.TIMEZONE_UTC);
                eventRecurrence.until = date.format2445();

                ContentValues values = new ContentValues();
                values.put(Events.DTSTART, dtstart);
                values.put(Events.RRULE, eventRecurrence.toString());
                Uri uri = ContentUris.withAppendedId(Calendar.Events.CONTENT_URI, id);
                mService.startUpdate(mService.getNextToken(), null, uri, values, null, null,
                        Utils.UNDO_DELAY);
                break;
            }
        }
        if (mCallback != null) {
            mCallback.run();
        }
        if (mExitWhenDone) {
            mParent.finish();
        }
    }
}
