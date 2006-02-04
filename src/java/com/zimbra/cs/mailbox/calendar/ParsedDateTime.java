/*
 * ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1
 * 
 * The contents of this file are subject to the Mozilla Public License
 * Version 1.1 ("License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.zimbra.com/license
 * 
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See
 * the License for the specific language governing rights and limitations
 * under the License.
 * 
 * The Original Code is: Zimbra Collaboration Suite Server.
 * 
 * The Initial Developer of the Original Code is Zimbra, Inc.
 * Portions created by Zimbra are Copyright (C) 2005 Zimbra, Inc.
 * All Rights Reserved.
 * 
 * Contributor(s): 
 * 
 * ***** END LICENSE BLOCK *****
 */

/**
 * 
 */
package com.zimbra.cs.mailbox.calendar;

import java.text.DecimalFormat;
import java.text.ParseException;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.account.WellKnownTimeZone;
import com.zimbra.cs.mailbox.calendar.ZCalendar.ICalTok;
import com.zimbra.cs.mailbox.calendar.ZCalendar.ZParameter;
import com.zimbra.cs.mailbox.calendar.ZCalendar.ZProperty;
import com.zimbra.cs.service.ServiceException;
import com.zimbra.cs.util.ZimbraLog;

public final class ParsedDateTime {
    
    /**
     * This means that "Date" events are treated as having a time of 00:00:00 in the
     * creator's default timezone, UNLESS they have the "UTC only" flag set
     */
    public static final boolean USE_BROKEN_OUTLOOK_MODE = true;
    
    public static void main(String[] args) {
        ICalTimeZone utc = ICalTimeZone.getUTC();
    	TimeZoneMap tzmap = new TimeZoneMap(utc);
        try {
            ParsedDateTime t1 = ParsedDateTime.parse("20050910", tzmap, null, utc);
            System.out.println(t1);
        } catch (ParseException e) {
            System.out.println("Caught "+e);
            e.printStackTrace();
        }
    }

    // YYYYMMDD'T'HHMMSSss'Z' YYYY MM DD 'T' HH MM SS Z
    static Pattern sDateTimePattern = Pattern
            .compile("(\\d{4})(\\d{2})(\\d{2})(?:T(\\d{2})(\\d{2})(\\d{2})(Z)?)?");

    public static ParsedDateTime fromUTCTime(long utc) {
        return new ParsedDateTime(new java.util.Date(utc));
    }
    
    public static ParsedDateTime parseUtcOnly(String str) throws ParseException {
        return parse(str, null, null, null, true);
    }    

    public static ParsedDateTime parse(String str, TimeZoneMap tzmap, ICalTimeZone tz, ICalTimeZone localTZ)
    throws ParseException {
        return parse(str, tzmap, tz, localTZ, false);
    }

    private static ParsedDateTime parse(String str,
    									TimeZoneMap tzmap,
    									ICalTimeZone tz,
    									ICalTimeZone localTZ,
    									boolean utcOnly)
    throws ParseException {
    	// Time zone map is required unless utcOnly == true.
    	assert(tzmap != null || utcOnly);

    	Matcher m = sDateTimePattern.matcher(str);
        
        if (m.matches()) {
            int year, month, date;
            int hour = -1;
            int minute = -1;
            int second = -1;
            boolean zulu = false;
            
            year = Integer.parseInt(m.group(1));
            month = Integer.parseInt(m.group(2)) - 1; // java months are
            // 0-indexed!
            date = Integer.parseInt(m.group(3));

            if (m.group(4) != null) { // has a T....part
                hour = Integer.parseInt(m.group(4));
                minute = Integer.parseInt(m.group(5));
                second = Integer.parseInt(m.group(6));

                // Ignore TZ part if this is just a DATE, per RFC
                if (m.group(7) != null && m.group(7).equals("Z")) {
                    zulu = true;
                }
                
                if (zulu || utcOnly) {
                    // RFC2445 Section 4.3.5 Date-Time
                    // FORM #2: DATE WITH UTC TIME
                    tz = ICalTimeZone.getUTC();
                } else if (tz == null) {
                    // RFC2445 Section 4.3.5 Date-Time
                    // FORM #1: DATE WITH LOCAL TIME
                    tz = localTZ;
                }
                //else {
                // RFC2445 Section 4.3.5 Date-Time
                // FORM #3: DATE WITH LOCAL TIME AND TIME ZONE REFERENCE
                //}
            } else {
            	// no timezone if it is a DATE entry....note that we *DO* need a
            	// 'local time zone' as a fallback: if we're in BROKEN_OUTLOOK_MODE
            	// we will need to use this local time zone as the zone to render
            	// the appt in (remember, outlook all-day-appts must be 0:00-0:00 in 
            	// the client's timezone!)
            	tz = null;
            }
            
            GregorianCalendar cal = new GregorianCalendar();
            if (zulu || utcOnly) {
                cal.setTimeZone(ICalTimeZone.getUTC());
            } else {
                if (tz == null)
                    tz = localTZ;

                if (tz != null) { // localTZ could have been null
                    cal.setTimeZone(tz);
                    if (tzmap != null)
	                    tzmap.add(tz);
                }
            }

            cal.clear();

            boolean hasTime = false;

            if (hour >= 0) {
                cal.set(year, month, date, hour, minute, second);
                hasTime = true;
            } else {
                cal.set(year, month, date, 0, 0, 0);
            }
            return new ParsedDateTime(cal, tz, hasTime);
        } else {
            if (str.length() == 9 && str.charAt(8) == 'Z') {
                // Some systems/sites are known to generate dates with
                // year, month, date followed by "Z".  That's an invalid
                // format, but we'll try to work with it, by ignoring
                // the unnecessary "Z".
            	//
            	// Since they requested "Z", we'll pass in UTC as their 'default timezone' 
            	// just in case it somehow comes to that (ie Outlook hack)
                return parse(str.substring(0, 8), tzmap, tz, ICalTimeZone.getUTC(), utcOnly);
            } else
                throw new ParseException("Invalid TimeString specified: " + str, 0);
        }
    }

    public static ParsedDateTime parse(ZProperty prop, TimeZoneMap tzmap)
    throws ParseException, ServiceException {
    	assert(tzmap != null);
        String tzname = prop.getParameterVal(ICalTok.TZID, null);
        
        ICalTimeZone tz = null;
        if (tzname != null) 
        	tz = lookupAndAddToTzMap(tzname, tzmap);

        if (tz == null) 
            tz = tzmap.getLocalTimeZone();
        
        return parse(prop.getValue(), tzmap, tz, tzmap.getLocalTimeZone());
    }

    public static ParsedDateTime parse(String str, TimeZoneMap tzmap)
    throws ParseException, ServiceException {
    	assert(tzmap != null);
        if (str == null) return null;

        String datetime;
        ICalTimeZone tz = null;
        int propValueColonIdx = str.lastIndexOf(':');  // colon before property value
        if (propValueColonIdx != -1) {
        	datetime = str.substring(propValueColonIdx + 1);

            int tzidIdx = str.indexOf("TZID=");
            if (tzidIdx != -1) {
                String tzid;
            	int valueParamIdx = str.lastIndexOf(";VALUE=");
            	if (valueParamIdx > tzidIdx)
            		tzid = str.substring(tzidIdx + 5, valueParamIdx);
            	else
            		tzid = str.substring(tzidIdx + 5, propValueColonIdx);

                if ((tzid.equals("GMT") || tzid.equals("UTC"))
                	&& !datetime.endsWith("Z")) {
                	datetime += "Z";
                } else {
                	tz = lookupAndAddToTzMap(tzid, tzmap);
                }
            }
        } else {
        	// no parameters; the whole thing is property value
        	datetime = str;
        }

        return parse(datetime, tzmap, tz, tzmap.getLocalTimeZone());
    }
    
    public static ParsedDateTime MAX_DATETIME;
    static {
        GregorianCalendar cal = new GregorianCalendar();
        cal.set(2099, 1, 1);
        cal.setTimeZone(ICalTimeZone.getUTC());
        MAX_DATETIME = new ParsedDateTime(cal, ICalTimeZone.getUTC(), false);
    }
    
    private GregorianCalendar mCal;
    
    public ICalTimeZone getTimeZone() { return mICalTimeZone; }
    

    // can't rely on cal.isSet, even though we want to -- because cal.toString()
    // sets the flag!!!
    private boolean mHasTime = false;
    
    private ICalTimeZone mICalTimeZone;

    private ParsedDateTime(GregorianCalendar cal, ICalTimeZone iCalTimeZone, boolean hasTime) {
        mCal = cal;
        mHasTime = hasTime;
        mICalTimeZone = iCalTimeZone;
    }
    
    ParsedDateTime(java.util.Date utc) {
        mCal = new GregorianCalendar(ICalTimeZone.getUTC());
        mCal.setTime(utc);
        mICalTimeZone = ICalTimeZone.getUTC();
        mHasTime = true;
    }

    public ParsedDateTime add(ParsedDuration dur) {
        GregorianCalendar cal = (GregorianCalendar) mCal.clone();

        cal.add(java.util.Calendar.WEEK_OF_YEAR, dur.getWeeks());
        cal.add(java.util.Calendar.DAY_OF_YEAR, dur.getDays());
        cal.add(java.util.Calendar.HOUR_OF_DAY, dur.getHours());
        cal.add(java.util.Calendar.MINUTE, dur.getMins());
        cal.add(java.util.Calendar.SECOND, dur.getSecs());

        return new ParsedDateTime(cal, mICalTimeZone, mHasTime);
    }

    public int compareTo(Date other) {
        return getDate().compareTo(other);
    }

    public int compareTo(long other) {
        long myTime = getDate().getTime();
        return (int) (myTime - other);
    }

    public int compareTo(Object other) {
        return compareTo(((ParsedDateTime) other).getDate());
    }

    public int compareTo(ParsedDateTime other) {
        return compareTo(other.getDate());
    }
    
    public boolean equals(Object obj) {
        return (compareTo(obj) == 0);
    }
    
    
    static final long MSECS_PER_SEC = 1000;
    static final long MSECS_PER_MIN = MSECS_PER_SEC * 60;
    static final long MSECS_PER_HOUR = MSECS_PER_MIN * 60;
    static final long MSECS_PER_DAY = MSECS_PER_HOUR * 24;
    static final long MSECS_PER_WEEK = MSECS_PER_DAY * 7;

    public ParsedDuration difference(ParsedDateTime other) {
        long myTime = mCal.getTimeInMillis();
        long otherTime = other.mCal.getTimeInMillis();

        long diff = myTime - otherTime;
        
        boolean negative = false;
        if (diff < 0) {
            negative = true;
            diff *= -1;
        }

        // RFC2445 4.3.6 durations allow Weeks OR DATE'T'TIME -- but weeks must be alone
        // I don't understand quite why, but that's what the spec says...
        
        int weeks = 0, days = 0, hours = 0, mins = 0, secs = 0;
        
        if ((diff >= MSECS_PER_WEEK) && (diff % MSECS_PER_WEEK == 0)) {
            weeks = (int) (diff / MSECS_PER_WEEK);
        } else {
            long dleft = diff;

            days = (int) (dleft / MSECS_PER_DAY);
            dleft = dleft % MSECS_PER_DAY;
            
            hours = (int) (dleft/ MSECS_PER_HOUR);
            dleft = dleft % MSECS_PER_HOUR;
            
            mins = (int) (dleft/ MSECS_PER_MIN);
            dleft = dleft % MSECS_PER_MIN;
            
            secs = (int) (dleft/ MSECS_PER_SEC);
        }

        return ParsedDuration.parse(negative, weeks, days, hours, mins, secs);
    }

    public Date getDate() {
        return mCal.getTime();
    }

    /**
     * Return Date suitable for use as UNTIL parameter of recurrence rule.
     * UNTIL parameter is inclusive, and it can be specified as a date-only
     * value.  For comparison with another Date object, such a time-less
     * Date must be extended to 23:59:59 on the same day, in local time zone.
     * @return
     */
    public Date getDateForRecurUntil() {
        if (!mHasTime) {
            GregorianCalendar cal = (GregorianCalendar) mCal.clone();
            // Add one day, then subtract 1 second.
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1);
            cal.add(java.util.Calendar.SECOND, -1);
            return cal.getTime();
        } else
            return getDate();
    }

    /**
     * @return The YYYYMMDD['T'HHMMSS[Z]] part  
     */
    public String getDateTimePartString() {
        return getDateTimePartString(USE_BROKEN_OUTLOOK_MODE);
    }

    /**
     * @return The YYYYMMDD['T'HHMMSS[Z]] part  
     */
    public String getDateTimePartString(boolean useOutlookCompatMode) {
        DecimalFormat fourDigitFormat = new DecimalFormat("0000");
        DecimalFormat twoDigitFormat = new DecimalFormat("00");

        StringBuffer toRet = new StringBuffer();

        toRet.append(fourDigitFormat.format(mCal.get(java.util.Calendar.YEAR)));
        toRet.append(twoDigitFormat.format(mCal.get(java.util.Calendar.MONTH) + 1));
        toRet.append(twoDigitFormat.format(mCal.get(java.util.Calendar.DATE)));

        // if HOUR is set, then assume it is a DateTime, otherwise assume it is
        // just a Date

        if (mHasTime) {
            toRet.append("T");

            toRet.append(twoDigitFormat.format(mCal
                    .get(java.util.Calendar.HOUR_OF_DAY)));

            if (mCal.isSet(java.util.Calendar.MINUTE)) {
                toRet.append(twoDigitFormat.format(mCal
                        .get(java.util.Calendar.MINUTE)));
            } else {
                toRet.append("00");
            }
            if (mCal.isSet(java.util.Calendar.SECOND)) {
                toRet.append(twoDigitFormat.format(mCal.get(java.util.Calendar.SECOND)));
            } else {
                toRet.append("00");
            }

            if (isUTC()) {
                toRet.append("Z");
            }
        } else if (useOutlookCompatMode) {
            toRet.append("T000000");
        	// OUTLOOK HACK -- remember, outlook all-day-appts 
        	// must be rendered as 0:00-0:00 in the client's timezone...but we
            // need to correctly fallback to UTC here (e.g. UNTILs w/ DATE values
            // that are implicitly therefore in UTC) in some cases.  Sheesh.
            if (getTZName() == null) {
                toRet.append("Z");
            }
        }
        
        return toRet.toString();
    }
    
    public boolean isUTC() {
        if (mICalTimeZone.getID()!=null && mICalTimeZone.getID().equals("Z")) {
            return true;
        } else {
            return false;
        }
    }

    public void toUTC() {
        if (!isUTC()) {
            mICalTimeZone = ICalTimeZone.getUTC();
            Date time = mCal.getTime();
            mCal.setTimeZone(mICalTimeZone);
            mCal.setTime(time);
        }
    }

    /**
     * @return The name of the TimeZone
     */
    public String getTZName() {
        if ((mHasTime || USE_BROKEN_OUTLOOK_MODE) && mICalTimeZone!=null && !isUTC() ) {
            return mICalTimeZone.getID();
        }
        return null;
    }

    /**
     * @return The full RFC2445 parameter string (ie "TZID=blah;") 
     */
    private String getTZParamString() {
        String tzName = getTZName();
        if (tzName != null) {
            return "TZID="+tzName+":";
        } else {
            return "";
        }
    }
    
    public GregorianCalendar getCalendarCopy() {
        return (GregorianCalendar)(mCal.clone());
    }
    

    public long getUtcTime() {
        return mCal.getTimeInMillis();
    }

    public boolean hasTime() {
        return mHasTime;
    }

    public String toString() {
        return getTZParamString() + getDateTimePartString();
    }
    
    public ZProperty toProperty(ICalTok tok) {
        ZProperty toRet = new ZProperty(tok, getDateTimePartString());
        
        String tzName = getTZName();
        if (!USE_BROKEN_OUTLOOK_MODE && !hasTime()) {
            toRet.addParameter(new ZParameter(ICalTok.VALUE, ICalTok.DATE.toString()));
        } else {
            assert(isUTC() || tzName != null);
            if (tzName != null) {
                toRet.addParameter(new ZParameter(ICalTok.TZID, tzName));
            } 
        }
        return toRet;
    }

    private static ICalTimeZone lookupAndAddToTzMap(String tzId,
            										TimeZoneMap tzMap)
    throws ServiceException {
        int len = tzId.length();
        if (len >= 2 && tzId.charAt(0) == '"' && tzId.charAt(len - 1) == '"') {
            tzId = tzId.substring(1, len - 1);
        }
        if (tzId.equals(""))
            return null;

        ICalTimeZone zone = tzMap.getTimeZone(tzId);
        if (zone == null) {
        	// Is it a system-defined TZ?
	        WellKnownTimeZone knownTZ =
	        	Provisioning.getInstance().getTimeZoneById(tzId);
	        if (knownTZ != null)
	            zone = knownTZ.toTimeZone();
	        if (zone != null)
            	tzMap.add(zone);
	        else {
	        	ZimbraLog.calendar.warn(
	        			"Encountered time zone with no definition: TZID=" +
	        			tzId);
	        }
        }
        return zone;
    }
}
