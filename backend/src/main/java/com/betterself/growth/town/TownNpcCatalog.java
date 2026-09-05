package com.betterself.growth.town;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The fixed 18-NPC roster for every user's town (plan.md §2.4, CONTRACT.md §7). Layer 1 (2,
 * locked forever) is 小助/邮递员 — the only two NPCs with a formal chat UI. Layer 2 (6) can be
 * approached but never asks for a response, and each one is bound to exactly one of the five
 * growth dimensions except {@link #JI_MAI}, the deliberate free slot. Layer 3 (10) is pure
 * background life, riding on the existing {@code c01..c20} resident sprites.
 *
 * <p>Kept as plain Java constants — there is no admin UI for this roster, and a fixed cast of
 * characters is exactly what makes a returning player recognize a face.
 */
public final class TownNpcCatalog {

    public static final String GUIDE = "GUIDE";
    public static final String POSTMAN = "POSTMAN";
    public static final String KE_YUN = "KE_YUN";
    public static final String LU_XIA = "LU_XIA";
    public static final String SHEN_MU = "SHEN_MU";
    public static final String WEN_QING = "WEN_QING";
    public static final String AN_HE = "AN_HE";
    public static final String JI_MAI = "JI_MAI";

    /** Allowed values for {@link Archetype#quirks()} — CONTRACT.md §7. */
    public static final Set<String> ALLOWED_QUIRKS = Set.of(
        "GOSSIP_HUB", "TIGHT_LIPPED", "NAME_MIXUP", "EXAGGERATE", "LITERAL", "NOSTALGIC"
    );

    /** The five growth dimensions a layer-2 NPC (other than {@link #JI_MAI}) can be bound to. */
    public static final List<String> DIMENSIONS = List.of(
        "KNOWLEDGE", "HEALTH", "CAREER", "RELATIONSHIP", "WELLBEING"
    );

    private static final List<Archetype> ALL = List.of(
        // Layer 1 — locked at 2, formal dialogue UI, never grows.
        new Archetype(
            GUIDE, "小助", 1, "npc_scout", null,
            0.05, 0.90,
            interests(0.20, 0.20, 0.20, 0.20, 0.20),
            Set.of("TIGHT_LIPPED")
        ),
        new Archetype(
            POSTMAN, "邮递员", 1, "npc_postman", null,
            0.20, 0.40,
            interests(0.15, 0.15, 0.15, 0.35, 0.20),
            Set.of("TIGHT_LIPPED")
        ),

        // Layer 2 — 6 NPCs, each bound to one dimension (JI_MAI is the deliberate free slot).
        new Archetype(
            KE_YUN, "柯云", 2, "c01", "KNOWLEDGE",
            0.50, 0.85,
            interests(0.50, 0.125, 0.125, 0.125, 0.125),
            Set.of("LITERAL")
        ),
        new Archetype(
            LU_XIA, "陆夏", 2, "c04", "HEALTH",
            0.85, 0.50,
            interests(0.125, 0.50, 0.125, 0.125, 0.125),
            Set.of("EXAGGERATE")
        ),
        new Archetype(
            SHEN_MU, "沈牧", 2, "c06", "CAREER",
            0.15, 0.30,
            interests(0.125, 0.125, 0.50, 0.125, 0.125),
            Set.of("TIGHT_LIPPED")
        ),
        new Archetype(
            WEN_QING, "温晴", 2, "c09", "RELATIONSHIP",
            0.90, 0.90,
            interests(0.125, 0.125, 0.125, 0.50, 0.125),
            Set.of("GOSSIP_HUB")
        ),
        new Archetype(
            AN_HE, "安禾", 2, "c12", "WELLBEING",
            0.20, 0.80,
            interests(0.125, 0.125, 0.125, 0.125, 0.50),
            Set.of("NOSTALGIC")
        ),
        new Archetype(
            JI_MAI, "纪麦", 2, "c15", null,
            0.60, 0.60,
            interests(0.20, 0.20, 0.20, 0.20, 0.20),
            Set.of("NAME_MIXUP", "EXAGGERATE")
        ),

        // Layer 3 — 10 background residents, ordinary/warm names, only a schedule.
        new Archetype("TOWNIE_01", "王芳", 3, "c02", null, 0.35, 0.35,
            interests(0.15, 0.30, 0.15, 0.25, 0.15), Set.of("NOSTALGIC")),
        new Archetype("TOWNIE_02", "李娜", 3, "c03", null, 0.40, 0.30,
            interests(0.20, 0.15, 0.25, 0.20, 0.20), Set.of("LITERAL")),
        new Archetype("TOWNIE_03", "张伟", 3, "c05", null, 0.20, 0.25,
            interests(0.10, 0.10, 0.40, 0.20, 0.20), Set.of("TIGHT_LIPPED")),
        new Archetype("TOWNIE_04", "刘洋", 3, "c07", null, 0.45, 0.40,
            interests(0.25, 0.25, 0.10, 0.15, 0.25), Set.of("EXAGGERATE")),
        new Archetype("TOWNIE_05", "陈静", 3, "c08", null, 0.55, 0.55,
            interests(0.20, 0.20, 0.20, 0.30, 0.10), Set.of("GOSSIP_HUB")),
        new Archetype("TOWNIE_06", "杨帆", 3, "c10", null, 0.30, 0.35,
            interests(0.15, 0.35, 0.15, 0.15, 0.20), Set.of("NOSTALGIC", "LITERAL")),
        new Archetype("TOWNIE_07", "赵敏", 3, "c11", null, 0.30, 0.30,
            interests(0.30, 0.15, 0.15, 0.20, 0.20), Set.of("NAME_MIXUP")),
        new Archetype("TOWNIE_08", "黄岚", 3, "c13", null, 0.25, 0.20,
            interests(0.10, 0.20, 0.30, 0.10, 0.30), Set.of("TIGHT_LIPPED", "EXAGGERATE")),
        new Archetype("TOWNIE_09", "周雨", 3, "c14", null, 0.40, 0.45,
            interests(0.20, 0.10, 0.20, 0.25, 0.25), Set.of("LITERAL")),
        new Archetype("TOWNIE_10", "吴桐", 3, "c16", null, 0.30, 0.30,
            interests(0.15, 0.15, 0.15, 0.15, 0.40), Set.of("NOSTALGIC"))
    );

    private static final Map<String, Archetype> BY_CODE = ALL.stream()
        .collect(Collectors.toUnmodifiableMap(Archetype::code, archetype -> archetype));

    private TownNpcCatalog() {
    }

    /** All 18 archetypes, in a stable order (layer 1, then layer 2, then layer 3). */
    public static List<Archetype> all() {
        return ALL;
    }

    /** The archetype for one npc code, or null if it isn't part of the fixed roster. */
    public static Archetype byCode(String npcCode) {
        return BY_CODE.get(npcCode);
    }

    private static Map<String, Double> interests(
        double knowledge, double health, double career, double relationship, double wellbeing
    ) {
        Map<String, Double> map = new LinkedHashMap<>();
        map.put("KNOWLEDGE", knowledge);
        map.put("HEALTH", health);
        map.put("CAREER", career);
        map.put("RELATIONSHIP", relationship);
        map.put("WELLBEING", wellbeing);
        return Map.copyOf(map);
    }

    /**
     * One NPC's fixed persona-shaping traits, projected straight into {@code town_npc} rows by
     * {@link TownNpcProvisioner}. {@code dimension} is null for layer-1 NPCs, layer-3 NPCs, and
     * the layer-2 free slot ({@link #JI_MAI}); every other layer-2 NPC has it set to the one
     * dimension it's bound to.
     */
    public record Archetype(
        String code,
        String displayName,
        int layer,
        String sprite,
        String dimension,
        double shareDrive,
        double curiosity,
        Map<String, Double> interests,
        Set<String> quirks
    ) {
    }
}
