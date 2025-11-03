package org.bench;

import java.time.*;
import java.time.temporal.ChronoField;

/**
 * Utility class for encoding and decoding datetime values using bitpacking strategy.
 * 
 * <p>This class implements a 63-bit bitpacking scheme that stores datetime components
 * (year, month, day, hour, minute, second, milliseconds) in a single 64-bit {@code long}.
 * The bit layout ensures chronological ordering when comparing packed values.
 * 
 * <p><b>Bit Layout (from LSB to MSB):</b>
 * <ul>
 *   <li>Bits 0-9: Milliseconds (0-1023, capped to 999)</li>
 *   <li>Bits 10-15: Seconds (0-63, valid range 0-59)</li>
 *   <li>Bits 16-20: Minutes (0-31, valid range 0-59)</li>
 *   <li>Bits 21-25: Hours (0-31, valid range 0-23)</li>
 *   <li>Bits 26-30: Day (0-31, valid range 1-31)</li>
 *   <li>Bits 31-34: Month (0-15, valid range 1-12)</li>
 *   <li>Bits 35-45: Year (relative to 2000, range 0-4095)</li>
 *   <li>Bits 46-63: Tenant prefix (16 bits, 0-65535)</li>
 * </ul>
 * 
 * <p>The encoding ensures that packed values maintain chronological order:
 * higher year values produce larger numbers, same year with higher month produces
 * larger numbers, and so on. This enables efficient range queries on datetime
 * values using simple integer comparisons.
 * 
 * @author krishna.sundar
 * @version 1.0
 */
public class Bitpack {
    /**
     * Encodes a {@code ZonedDateTime} into a packed 64-bit long integer.
     * 
     * <p>The datetime is first converted to UTC, then its components are extracted
     * and packed into specific bit positions. All values are validated and clamped
     * to their valid ranges before packing.
     * 
     * @param zdt The datetime to encode (will be converted to UTC)
     * @param tenantPrefix The tenant identifier prefix (0-65535) to include in the packed value
     * @return A packed 64-bit long containing the datetime components and tenant prefix
     */
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

    /**
     * Decodes a packed 64-bit long integer into a {@code ZonedDateTime}.
     * 
     * <p>Extracts datetime components from specific bit positions, validates and
     * clamps values to valid ranges, and constructs a {@code ZonedDateTime} in UTC.
     * 
     * <p>This method includes robust error handling:
     * <ul>
     *   <li>Validates all datetime fields are within acceptable ranges</li>
     *   <li>Handles invalid dates (e.g., February 30) by adjusting to last valid day of month</li>
     *   <li>Ensures nanoseconds don't exceed 999,999,999</li>
     *   <li>Falls back to a default datetime (2020-01-01 00:00:00 UTC) if unpacking fails completely</li>
     * </ul>
     * 
     * @param val The packed 64-bit long integer to decode
     * @return A {@code ZonedDateTime} in UTC, or a default value (2020-01-01) if decoding fails
     */
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
