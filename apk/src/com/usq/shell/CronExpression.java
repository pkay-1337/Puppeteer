package com.usq.shell;

import java.util.Calendar;

/**
 * 5-field cron expression parser and evaluator.
 * Fields: minute (0-59), hour (0-23), day-of-month (1-31), month (1-12), day-of-week (0-6, 0=Sun).
 * Supports: *, steps (e.g. * /5, 1-10/2), ranges (e.g. 1-5), lists (e.g. 0,15,30,45).
 */
public class CronExpression {

    private final boolean[] minutes = new boolean[60];
    private final boolean[] hours = new boolean[24];
    private final boolean[] daysOfMonth = new boolean[32]; // 1-31
    private final boolean[] months = new boolean[13];      // 1-12
    private final boolean[] daysOfWeek = new boolean[8];   // 0-7 (0=Sun, 7=Sun)

    private final boolean domRestricted;
    private final boolean dowRestricted;
    private final String raw;

    public CronExpression(String expr) {
        if (expr == null) throw new IllegalArgumentException("cron expression cannot be null");
        this.raw = expr.trim();
        String[] parts = this.raw.split("\\s+");
        if (parts.length != 5) {
            throw new IllegalArgumentException("expected 5 fields in cron expression, got " + parts.length + ": " + expr);
        }

        parseField(parts[0], 0, 59, minutes);
        parseField(parts[1], 0, 23, hours);
        domRestricted = parseField(parts[2], 1, 31, daysOfMonth);
        parseField(parts[3], 1, 12, months);
        dowRestricted = parseField(parts[4], 0, 7, daysOfWeek);
        if (daysOfWeek[7]) daysOfWeek[0] = true;
    }

    public String getRaw() { return raw; }

    /** Returns true if the field was restricted (not '*'). */
    private boolean parseField(String field, int min, int max, boolean[] target) {
        if ("*".equals(field)) {
            for (int i = min; i <= max; i++) target[i] = true;
            return false;
        }

        String[] subparts = field.split(",");
        for (String sub : subparts) {
            int step = 1;
            String rangePart = sub;
            int slashIdx = sub.indexOf('/');
            if (slashIdx >= 0) {
                step = Integer.parseInt(sub.substring(slashIdx + 1));
                rangePart = sub.substring(0, slashIdx);
            }

            int start, end;
            if ("*".equals(rangePart)) {
                start = min;
                end = max;
            } else {
                int dashIdx = rangePart.indexOf('-');
                if (dashIdx >= 0) {
                    start = Integer.parseInt(rangePart.substring(0, dashIdx));
                    end = Integer.parseInt(rangePart.substring(dashIdx + 1));
                } else {
                    start = end = Integer.parseInt(rangePart);
                }
            }

            if (start < min || end > max || start > end || step <= 0) {
                throw new IllegalArgumentException("invalid range/step: " + sub);
            }

            for (int i = start; i <= end; i += step) {
                target[i] = true;
            }
        }
        return true;
    }

    public boolean matches(long timeMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(timeMs);
        int m = c.get(Calendar.MINUTE);
        int h = c.get(Calendar.HOUR_OF_DAY);
        int dom = c.get(Calendar.DAY_OF_MONTH);
        int mon = c.get(Calendar.MONTH) + 1; // 1-12
        int dow = c.get(Calendar.DAY_OF_WEEK) - 1; // 0=Sun..6=Sat

        if (!minutes[m] || !hours[h] || !months[mon]) return false;

        if (domRestricted && dowRestricted) {
            return daysOfMonth[dom] || daysOfWeek[dow];
        } else if (domRestricted) {
            return daysOfMonth[dom];
        } else if (dowRestricted) {
            return daysOfWeek[dow];
        }
        return true;
    }

    /**
     * Calculates the next fire timestamp strictly after afterMs.
     * Searches up to 366 days ahead.
     */
    public long nextFire(long afterMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(afterMs);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.MINUTE, 1);

        // Scan up to 525,600 minutes (1 year)
        for (int i = 0; i < 525600; i++) {
            int m = c.get(Calendar.MINUTE);
            int h = c.get(Calendar.HOUR_OF_DAY);
            int dom = c.get(Calendar.DAY_OF_MONTH);
            int mon = c.get(Calendar.MONTH) + 1;
            int dow = c.get(Calendar.DAY_OF_WEEK) - 1;

            if (months[mon]) {
                boolean dayMatch;
                if (domRestricted && dowRestricted) {
                    dayMatch = daysOfMonth[dom] || daysOfWeek[dow];
                } else if (domRestricted) {
                    dayMatch = daysOfMonth[dom];
                } else if (dowRestricted) {
                    dayMatch = daysOfWeek[dow];
                } else {
                    dayMatch = true;
                }

                if (dayMatch && hours[h] && minutes[m]) {
                    return c.getTimeInMillis();
                }
            }

            c.add(Calendar.MINUTE, 1);
        }
        return -1;
    }
}
