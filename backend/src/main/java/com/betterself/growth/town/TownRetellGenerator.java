package com.betterself.growth.town;

import java.util.List;

/**
 * Turns "what the previous hand said" into "what this NPC now says", one hop further into the
 * gossip chain. Implementations must be total: given N requests they return exactly N results,
 * in no particular correspondence to input order beyond the {@code key} used to match them back
 * up, and they must never throw.
 */
public interface TownRetellGenerator {

    List<Retold> retell(List<Request> requests);

    /**
     * @param key             caller-supplied identifier used to match a {@link Retold} back to
     *                        this request; not shown to the model as content.
     * @param speakerName     display name of the NPC doing the retelling.
     * @param speakerPersona  a short persona blurb for that NPC (see {@link TownPersonas}).
     * @param quirks          this NPC's quirks, e.g. {@code ["NAME_MIXUP", "EXAGGERATE"]}.
     * @param hops            which hand this retelling is — 1 is the first retelling after the
     *                        original witness, 2 the next, and so on.
     * @param previousText    the text the previous hand told this NPC.
     */
    record Request(
        String key,
        String speakerName,
        String speakerPersona,
        List<String> quirks,
        int hops,
        String previousText
    ) {
    }

    record Retold(String key, String text) {
    }
}
