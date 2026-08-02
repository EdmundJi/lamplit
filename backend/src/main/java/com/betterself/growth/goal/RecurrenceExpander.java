package com.betterself.growth.goal;

import com.betterself.growth.shared.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class RecurrenceExpander {

    public List<PlannedOccurrence> expand(
        RecurrenceRule rule,
        LocalDate startDate,
        LocalTime localTime,
        ZoneId timezone,
        LocalDate endDate
    ) {
        List<PlannedOccurrence> result = new ArrayList<>();
        LocalDate weekZero = startDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        int matched = 0;
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            if (rule.until() != null && date.isAfter(rule.until())) {
                break;
            }
            boolean occurs = switch (rule.frequency()) {
                case DAILY -> ChronoUnit.DAYS.between(startDate, date) % rule.interval() == 0;
                case WEEKLY -> {
                    LocalDate week = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                    long weekIndex = ChronoUnit.WEEKS.between(weekZero, week);
                    yield weekIndex % rule.interval() == 0 && rule.byDays().contains(date.getDayOfWeek());
                }
            };
            if (!occurs) {
                continue;
            }
            matched++;
            if (rule.count() != null && matched > rule.count()) {
                break;
            }
            result.add(new PlannedOccurrence(date, date.atTime(localTime).atZone(timezone).toInstant()));
        }
        return List.copyOf(result);
    }
}

record PlannedOccurrence(LocalDate localDate, Instant instant) {
}

record RecurrenceRule(
    Frequency frequency,
    int interval,
    Set<DayOfWeek> byDays,
    Integer count,
    LocalDate until
) {

    private static final Set<String> SUPPORTED_KEYS = Set.of("FREQ", "INTERVAL", "BYDAY", "COUNT", "UNTIL");

    static RecurrenceRule parse(String value) {
        if (value == null || value.isBlank()) {
            throw invalid();
        }
        Map<String, String> properties = new LinkedHashMap<>();
        for (String part : value.split(";")) {
            String[] pair = part.split("=", 2);
            if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank()) {
                throw invalid();
            }
            String key = pair[0].toUpperCase(Locale.ROOT);
            if (!SUPPORTED_KEYS.contains(key) || properties.putIfAbsent(key, pair[1]) != null) {
                throw invalid();
            }
        }
        try {
            Frequency frequency = Frequency.valueOf(required(properties, "FREQ").toUpperCase(Locale.ROOT));
            int interval = properties.containsKey("INTERVAL")
                ? Integer.parseInt(properties.get("INTERVAL"))
                : 1;
            if (interval < 1) {
                throw invalid();
            }
            Set<DayOfWeek> byDays = properties.containsKey("BYDAY")
                ? parseDays(properties.get("BYDAY"))
                : EnumSet.noneOf(DayOfWeek.class);
            if (frequency == Frequency.WEEKLY && byDays.isEmpty()) {
                throw invalid();
            }
            Integer count = properties.containsKey("COUNT") ? Integer.valueOf(properties.get("COUNT")) : null;
            if (count != null && count < 1) {
                throw invalid();
            }
            LocalDate until = properties.containsKey("UNTIL") ? parseUntil(properties.get("UNTIL")) : null;
            if (count != null && until != null) {
                throw invalid();
            }
            return new RecurrenceRule(frequency, interval, Set.copyOf(byDays), count, until);
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) {
            throw invalid();
        }
        return value;
    }

    private static Set<DayOfWeek> parseDays(String value) {
        Map<String, DayOfWeek> names = Map.of(
            "MO", DayOfWeek.MONDAY,
            "TU", DayOfWeek.TUESDAY,
            "WE", DayOfWeek.WEDNESDAY,
            "TH", DayOfWeek.THURSDAY,
            "FR", DayOfWeek.FRIDAY,
            "SA", DayOfWeek.SATURDAY,
            "SU", DayOfWeek.SUNDAY
        );
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String name : value.split(",")) {
            DayOfWeek day = names.get(name.toUpperCase(Locale.ROOT));
            if (day == null) {
                throw invalid();
            }
            days.add(day);
        }
        return days;
    }

    private static LocalDate parseUntil(String value) {
        if (value.matches("\\d{8}")) {
            return LocalDate.of(
                Integer.parseInt(value.substring(0, 4)),
                Integer.parseInt(value.substring(4, 6)),
                Integer.parseInt(value.substring(6, 8))
            );
        }
        return LocalDate.parse(value);
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RRULE", "The recurrence rule is invalid");
    }

    enum Frequency {
        DAILY,
        WEEKLY
    }
}
