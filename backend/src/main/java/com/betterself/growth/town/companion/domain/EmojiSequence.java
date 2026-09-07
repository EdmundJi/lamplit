package com.betterself.growth.town.companion.domain;

import java.util.regex.Pattern;

/** Unicode shape validation only: no topic, character, mood, or location lookup table. */
public final class EmojiSequence {
    private EmojiSequence() {}
    private static final Pattern GRAPHEME=Pattern.compile("\\X");
    public static boolean valid(String value) {
        if(value==null)return true; // Older serialized turns and the old constructor had no emoji.
        if(value.isBlank()||value.length()>64)return false;
        var matcher=GRAPHEME.matcher(value);int count=0;
        while(matcher.find()) {
            String cluster=matcher.group();
            if(cluster.equals(" "))continue;
            if(++count>3)return false;
            boolean keycap=cluster.codePoints().anyMatch(cp->cp==0x20e3),base=false;
            for(int cp:cluster.codePoints().toArray()) {
                if(isBase(cp)){base=true;continue;}
                if(keycap&&(cp>='0'&&cp<='9'||cp=='#'||cp=='*')){base=true;continue;}
                if(cp==0x200d||cp==0xfe0f||cp==0xfe0e||cp==0x20e3||cp>=0x1f3fb&&cp<=0x1f3ff||cp>=0xe0020&&cp<=0xe007f)continue;
                return false;
            }
            if(!base)return false;
        }
        return count>=1;
    }
    private static boolean isBase(int cp) {
        if(cp>=0x1f3fb&&cp<=0x1f3ff)return false;
        return Character.getType(cp)==Character.OTHER_SYMBOL||cp>=0x1f000&&cp<=0x1faff||cp>=0x2190&&cp<=0x21ff||cp>=0x2b00&&cp<=0x2bff
            ||cp==0x203c||cp==0x2049||cp==0x3030||cp==0x303d;
    }
}
