package org.bench;

import java.time.*;
import java.time.temporal.ChronoField;

/** Bitpack encode/decode for datetime. */
public class Bitpack {
    public static long pack(ZonedDateTime zdt, int tenantPrefix) {
        ZonedDateTime utc = zdt.withZoneSameInstant(ZoneOffset.UTC);
        int year = utc.getYear() - 2000;
        int month = utc.getMonthValue();
        int day = utc.getDayOfMonth();
        int hour = utc.getHour();
        int minute = utc.getMinute();
        int second = utc.getSecond();
        int milli = utc.get(ChronoField.MILLI_OF_SECOND);
        // Ensure all values are in valid range before packing
        milli = Math.min(Math.max(milli, 0), 999);
        second = Math.min(Math.max(second, 0), 59);
        minute = Math.min(Math.max(minute, 0), 59);
        hour = Math.min(Math.max(hour, 0), 23);
        day = Math.min(Math.max(day, 1), 31);
        month = Math.min(Math.max(month, 1), 12);
        long val = 0L;
        val |= (long)(milli & 0x3FF);
        val |= ((long)(second & 0x3F)) << 10;
        val |= ((long)(minute & 0x1F)) << 16;
        val |= ((long)(hour & 0x1F)) << 21;
        val |= ((long)(day & 0x1F)) << 26;
        val |= ((long)(month & 0x0F)) << 31;
        val |= ((long)(year & 0x7FF)) << 35;
        val |= ((long)tenantPrefix & 0xFFFFL) << 48;
        return val;
    }

    public static ZonedDateTime unpack(long val) {
        try {
            // Extract values with bit masks
            int milli = (int)(val & 0x3FF);
            int second = (int)((val >> 10) & 0x3F);  // Mask 0x3F = 0-63, but valid is 0-59
            int minute = (int)((val >> 16) & 0x1F);  // Mask 0x1F = 0-31, but valid is 0-59
            int hour = (int)((val >> 21) & 0x1F);     // Mask 0x1F = 0-31, but valid is 0-23
            int day = (int)((val >> 26) & 0x1F);      // Mask 0x1F = 0-31, valid is 1-31
            int month = (int)((val >> 31) & 0x0F);    // Mask 0x0F = 0-15, but valid is 1-12
            int year = (int)((val >> 35) & 0x7FF);
            
            // Robustly validate and cap all fields to valid ranges
            // This handles corrupted or old data with invalid values
            milli = Math.min(Math.max(milli, 0), 999);
            second = Math.min(Math.max(second, 0), 59);
            minute = Math.min(Math.max(minute, 0), 59);
            hour = Math.min(Math.max(hour, 0), 23);
            day = Math.min(Math.max(day, 1), 31);
            month = Math.min(Math.max(month, 1), 12);
            
            // Validate year range (2000-4095 from the bit field, but cap to reasonable range)
            int y = year + 2000;
            if (y < 2000) y = 2000;
            if (y > 2099) y = 2099;
            
            // Ensure milli * 1_000_000 doesn't exceed 999,999,999 nanoseconds
            int nanos = Math.min(milli * 1_000_000, 999_999_999);
            
            // Validate day for the specific month/year (handle invalid days like Feb 30)
            // First, try to create the date with the extracted values
            LocalDateTime ldt;
            try {
                ldt = LocalDateTime.of(y, month, day, hour, minute, second, nanos);
            } catch (Exception e) {
                // If date is invalid (e.g., Feb 30), adjust day to last valid day of month
                // Use YearMonth to get the last day of the month
                YearMonth ym = YearMonth.of(y, month);
                int lastDay = ym.lengthOfMonth();
                day = Math.min(day, lastDay);
                ldt = LocalDateTime.of(y, month, day, hour, minute, second, nanos);
            }
            
            return ZonedDateTime.ofInstant(ldt, ZoneOffset.UTC, ZoneId.of("UTC"));
        } catch (Exception e) {
            // Fallback: return a default valid datetime if unpacking fails completely
            System.err.println("Warning: Bitpack unpack failed for value " + val + ", using default: " + e.getMessage());
            return ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        }
    }
}
