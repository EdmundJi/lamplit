package com.betterself.growth.town;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Deterministic, zero-dependency fallback for {@link TownRetellGenerator}: a pure function of
 * its input, used both as the offline default and as the item-by-item safety net behind
 * {@link QwenRetellGenerator}. Never calls out to anything, never throws, never returns a blank
 * or over-length line, and never lets an Arabic numeral slip through.
 *
 * <p>The distortion grows with {@code hops}: hop 1 is framed as "听说", hop 2 softens into
 * "好像有人说" plus extra hedging, and hop 3+ disclaims the source entirely ("不知道谁传的，说是")
 * and leans into exaggeration. Four quirks additionally reshape the line: {@code NAME_MIXUP}
 * swaps the subject for a different town resident once the story is at least two hands old,
 * {@code EXAGGERATE} intensifies its quantifiers, {@code TIGHT_LIPPED} clips it down and hedges
 * harder, and {@code NOSTALGIC} opens with a wistful aside.
 */
@Component
public final class TemplateRetellGenerator implements TownRetellGenerator {

    private static final int MAX_CHARS = 40;
    private static final Pattern DIGIT = Pattern.compile("[0-9０-９]");

    // Package-private (not private) so the test can assert hop escalation without duplicating copy.
    static final String[] HOP1_PREFIXES = {"听说", "听说啊，", "刚听说"};
    static final String[] HOP2_PREFIXES = {"好像有人说", "好像听谁提过，说", "隐约听人讲起，好像"};
    static final String[] HOP3_PREFIXES = {"不知道谁传的，说是", "也不知道从哪传出来的，说", "传来传去都说"};
    private static final String[] HOP3_SUFFIXES = {"", "，越传越邪乎", "，反正是这么传的"};
    private static final String[] NOSTALGIC_PREFIXES = {"想当年，", "说起来也是老早以前的事了，", "想起以前啊，"};
    private static final String[] HEDGE_WORDS = {"好像", "可能", "似乎", "大概", "估计"};
    // Confident-sounding quantifiers worth softening for hop 2+ — see soften(). Checked in order,
    // most specific first, so "挺常" isn't shadowed by a later, broader match.
    private static final String[] QUALIFIER_WORDS = {"挺常", "总", "挺", "都", "老", "经常", "几乎"};

    // Longer keys first so a substring like "挺" doesn't get eaten by a more specific match first.
    private static final Map<String, String> EXAGGERATE_MAP = new LinkedHashMap<>();

    static {
        EXAGGERATE_MAP.put("有些日子", "好长一段日子");
        EXAGGERATE_MAP.put("好些天", "老些天");
        EXAGGERATE_MAP.put("好久", "老半天");
        EXAGGERATE_MAP.put("挺常", "天天");
        EXAGGERATE_MAP.put("偶尔", "老是");
        EXAGGERATE_MAP.put("几乎", "天天都");
        EXAGGERATE_MAP.put("挺", "特别");
        EXAGGERATE_MAP.put("总", "老");
    }

    private static final List<String> NAME_POOL = TownNpcCatalog.all().stream()
        .map(TownNpcCatalog.Archetype::displayName)
        .distinct()
        .collect(Collectors.toUnmodifiableList());

    @Override
    public List<Retold> retell(List<Request> requests) {
        List<Retold> results = new ArrayList<>(requests.size());
        for (Request request : requests) {
            results.add(new Retold(request.key(), generate(request)));
        }
        return results;
    }

    /** The pure text-generation core, exposed package-private so {@link QwenRetellGenerator} can reuse it verbatim. */
    String generate(Request request) {
        String base = request.previousText() == null ? "" : request.previousText().strip();
        if (base.isEmpty()) {
            base = "有点小事";
        }
        base = stripDigits(base);

        Set<String> quirks = request.quirks() == null ? Set.of() : Set.copyOf(request.quirks());
        int hops = Math.max(1, request.hops());
        int hash = stableHash(request);

        String subject = detectSubject(base);

        if (quirks.contains("NAME_MIXUP") && hops >= 2 && subject != null) {
            base = swapSubject(base, subject, hash);
        }
        if (quirks.contains("EXAGGERATE")) {
            base = applyExaggeration(base);
        }
        if (quirks.contains("TIGHT_LIPPED")) {
            base = tightenLips(base);
        }

        String line = applyHopFraming(base, hops, hash);

        if (quirks.contains("NOSTALGIC")) {
            line = pick(NOSTALGIC_PREFIXES, hash, 0x51ED270B) + line;
        }

        line = stripDigits(line);
        return clamp(line, MAX_CHARS);
    }

    private String applyHopFraming(String base, int hops, int hash) {
        if (hops == 1) {
            return pick(HOP1_PREFIXES, hash, 0x1B873593) + base;
        }
        if (hops == 2) {
            return pick(HOP2_PREFIXES, hash, 0x27D4EB2F) + soften(base);
        }
        String prefix = pick(HOP3_PREFIXES, hash, 0x85EBCA6B);
        String suffix = pick(HOP3_SUFFIXES, hash, 0xC2B2AE35);
        return prefix + soften(base) + suffix;
    }

    /**
     * Weakens whatever confident-sounding qualifier the line already has (contract's "弱化限定词"),
     * by tucking a hedge word right in front of it rather than pasting one onto the whole sentence
     * — {@code "小吉最近挺常往健身房跑"} becomes {@code "小吉最近好像挺常往健身房跑"}. Falls through to a
     * plain sentence-level hedge only when no known qualifier is present to attach to, and never
     * double-hedges a line that already reads as uncertain.
     */
    private String soften(String text) {
        for (String hedgeWord : HEDGE_WORDS) {
            if (text.contains(hedgeWord)) {
                return text;
            }
        }
        for (String qualifier : QUALIFIER_WORDS) {
            int at = text.indexOf(qualifier);
            if (at >= 0) {
                return text.substring(0, at) + "好像" + text.substring(at);
            }
        }
        return "好像" + text;
    }

    private String applyExaggeration(String text) {
        String result = text;
        for (Map.Entry<String, String> entry : EXAGGERATE_MAP.entrySet()) {
            if (result.contains(entry.getKey())) {
                result = result.replace(entry.getKey(), entry.getValue());
                break;
            }
        }
        return result;
    }

    private String tightenLips(String text) {
        String first = text.split("[，,]", 2)[0];
        if (first.length() > 14) {
            first = first.substring(0, 14);
        }
        return first + "，具体不清楚";
    }

    /**
     * Every NPC display name is a fixed, known two-or-three-character token (see
     * {@link TownNpcCatalog}), so a fact that opens with one of them is treated as being "about"
     * that resident. Facts phrased impersonally (no leading name) have no swappable subject.
     */
    private String detectSubject(String text) {
        for (String name : NAME_POOL) {
            if (text.startsWith(name)) {
                return name;
            }
        }
        return null;
    }

    private String swapSubject(String text, String subject, int hash) {
        List<String> candidates = NAME_POOL.stream().filter(name -> !name.equals(subject)).toList();
        if (candidates.isEmpty()) {
            return text;
        }
        String replacement = candidates.get(Math.floorMod(mix(hash, 0x9E3779B1), candidates.size()));
        return replacement + text.substring(subject.length());
    }

    private String pick(String[] options, int hash, int salt) {
        return options[Math.floorMod(mix(hash, salt), options.length)];
    }

    private int mix(int hash, int salt) {
        return Integer.hashCode(hash ^ salt);
    }

    private int stableHash(Request request) {
        String basis = request.speakerName() + "|" + request.previousText() + "|" + request.hops() + "|"
            + (request.quirks() == null ? "" : String.join(",", request.quirks()));
        return basis.hashCode();
    }

    private static String stripDigits(String text) {
        return DIGIT.matcher(text).replaceAll("");
    }

    private static String clamp(String text, int maxChars) {
        return text.length() > maxChars ? text.substring(0, maxChars) : text;
    }
}
