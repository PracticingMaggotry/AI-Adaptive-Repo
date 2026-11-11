package com.adaptivelearning.adaptivelearningbackend;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.TimeZone;

/**
 * The one place the application's time zone is defined: Philippine Standard Time (UTC+8, no daylight saving).
 *
 * {@link #install()} makes this the JVM default, so LocalDateTime.now(), LocalDate.now(), ZoneId.systemDefault() and the
 * JDBC driver all agree on Philippine time no matter where the server is hosted. Code that needs the zone explicitly
 * should use {@link #ZONE} instead of UTC or the system default.
 */
public final class AppTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Manila");

    private AppTime() {}

    /** Sets the JVM default time zone. Must run before Spring and the database pool start. */
    public static void install() {
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE));
    }

    /** Today's date in Philippine time. */
    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }
}
