package com.betterself.growth.town;

import java.util.Comparator;
import java.util.Map;

/** Public, deterministic plans, never claims that a future errand has already happened. */
final class TownNpcPublicDay {
    private TownNpcPublicDay() {}

    static String line(String name, String dimension, Map<String, Double> interests, TownDayPlan.DayPlan plan) {
        String focus = dimension;
        if (focus == null) {
            var ranked = interests.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .toList();
            // Balanced interests are the free resident's personality, not a random tied dimension.
            if (!ranked.isEmpty() && (ranked.size() == 1 || ranked.get(0).getValue() > ranked.get(1).getValue()))
                focus = ranked.get(0).getKey();
        }
        var outing = plan.errands().stream().filter(e -> !TownNpcSchedules.HOME.equals(e.place()))
            .min(Comparator.comparingInt(TownDayPlan.Errand::startMinute));
        String destination = outing.map(e -> switch (e.place()) {
            case TownNpcSchedules.ACADEMY -> "去学院";
            case TownNpcSchedules.GYM -> "去健身房";
            case TownNpcSchedules.CAFE -> "去咖啡馆";
            case TownNpcSchedules.PARK -> "去公园";
            case TownNpcSchedules.PLAZA -> "去广场";
            default -> "出门走走";
        }).orElse("在家慢慢待着");
        String thought = switch (focus == null ? "" : focus) {
            case "KNOWLEDGE" -> "心里还惦记着没读完的书";
            case "HEALTH" -> "想给紧绷的肩膀松松劲";
            case "CAREER" -> "想把零散的想法理清楚";
            case "RELATIONSHIP" -> "想到熟面孔就有点期待";
            case "WELLBEING" -> "想留点时间听听周围的声音";
            default -> "又对不起眼的小东西起了好奇心";
        };
        return name + "今天打算" + destination + "，" + thought + "。";
    }
}
