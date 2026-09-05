package com.betterself.growth.town;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateRetellGeneratorTest {

    private static final Pattern ANY_DIGIT = Pattern.compile("\\d");

    private final TemplateRetellGenerator generator = new TemplateRetellGenerator();

    @Test
    void sameInputAlwaysYieldsIdenticalOutput() {
        TownRetellGenerator.Request request = new TownRetellGenerator.Request(
            "k1", "陆夏", "高分享欲、话密", List.of("EXAGGERATE"), 2, "小吉最近挺常往健身房跑"
        );

        String first = new TemplateRetellGenerator().generate(request);
        String second = new TemplateRetellGenerator().generate(request);

        assertThat(first).isEqualTo(second);
        assertThat(generator.retell(List.of(request))).containsExactly(new TownRetellGenerator.Retold("k1", first));
    }

    @Test
    void hopOneIsFramedAsHearsay() {
        String text = generator.generate(new TownRetellGenerator.Request(
            "k", "柯云", "高好奇心", List.of(), 1, "小吉最近挺常往健身房跑"
        ));

        assertThat(startsWithAny(text, TemplateRetellGenerator.HOP1_PREFIXES)).isTrue();
    }

    @Test
    void hopTwoEscalatesToASofterSecondHandFrame() {
        String text = generator.generate(new TownRetellGenerator.Request(
            "k", "柯云", "高好奇心", List.of(), 2, "小吉最近挺常往健身房跑"
        ));

        assertThat(startsWithAny(text, TemplateRetellGenerator.HOP2_PREFIXES)).isTrue();
    }

    @Test
    void hopThreeAndBeyondDisclaimsTheSourceEntirely() {
        String hopThree = generator.generate(new TownRetellGenerator.Request(
            "k", "柯云", "高好奇心", List.of(), 3, "小吉最近挺常往健身房跑"
        ));
        String hopFive = generator.generate(new TownRetellGenerator.Request(
            "k", "柯云", "高好奇心", List.of(), 5, "小吉最近挺常往健身房跑"
        ));

        assertThat(startsWithAny(hopThree, TemplateRetellGenerator.HOP3_PREFIXES)).isTrue();
        assertThat(startsWithAny(hopFive, TemplateRetellGenerator.HOP3_PREFIXES)).isTrue();
    }

    @Test
    void hopEscalationChangesTheTextForTheSameUnderlyingFact() {
        String base = "小吉最近挺常往健身房跑";
        String hop1 = generator.generate(new TownRetellGenerator.Request("k", "柯云", "p", List.of(), 1, base));
        String hop2 = generator.generate(new TownRetellGenerator.Request("k", "柯云", "p", List.of(), 2, base));
        String hop3 = generator.generate(new TownRetellGenerator.Request("k", "柯云", "p", List.of(), 3, base));

        assertThat(Set.of(hop1, hop2, hop3)).hasSize(3);
    }

    @Test
    void nameMixupDoesNotSwapTheSubjectAtHopOne() {
        String text = generator.generate(new TownRetellGenerator.Request(
            "k", "纪麦", "怪癖担当", List.of("NAME_MIXUP"), 1, "柯云今天在公园坐了一下午"
        ));

        assertThat(text).contains("柯云");
    }

    @Test
    void nameMixupSwapsTheSubjectFromHopTwoOnward() {
        String text = generator.generate(new TownRetellGenerator.Request(
            "k", "纪麦", "怪癖担当", List.of("NAME_MIXUP"), 2, "柯云今天在公园坐了一下午"
        ));

        assertThat(text).doesNotContain("柯云");
        boolean containsAnotherResident = TownNpcCatalog.all().stream()
            .map(TownNpcCatalog.Archetype::displayName)
            .filter(name -> !name.equals("柯云"))
            .anyMatch(text::contains);
        assertThat(containsAnotherResident).isTrue();
    }

    @Test
    void noOutputEverContainsAnArabicNumeralAcrossABroadInputMatrix() {
        List<String> bases = List.of(
            "小吉最近挺常往健身房跑",
            "柯云今天在公园坐了一下午",
            "3天没换头像还老提这事",
            "好像总往学院那边跑",
            "有些日子没见你出来走动了",
            "偶尔来一下，好久没断过了"
        );
        List<List<String>> quirkCombos = List.of(
            List.of(),
            List.of("NAME_MIXUP"),
            List.of("EXAGGERATE"),
            List.of("TIGHT_LIPPED"),
            List.of("NOSTALGIC"),
            List.of("NAME_MIXUP", "EXAGGERATE", "TIGHT_LIPPED", "NOSTALGIC")
        );

        for (String base : bases) {
            for (List<String> quirks : quirkCombos) {
                for (int hops : List.of(1, 2, 3, 5, 8)) {
                    String text = generator.generate(new TownRetellGenerator.Request(
                        "k", "陆夏", "p", quirks, hops, base
                    ));
                    assertThat(ANY_DIGIT.matcher(text).find())
                        .as("output for base=%s quirks=%s hops=%s was '%s'", base, quirks, hops, text)
                        .isFalse();
                }
            }
        }
    }

    @Test
    void neverExceedsFortyCharacters() {
        String longBase = "小吉".repeat(30) + "最近挺常往健身房跑";

        String text = generator.generate(new TownRetellGenerator.Request(
            "k", "陆夏", "p", List.of("NOSTALGIC", "EXAGGERATE"), 3, longBase
        ));

        assertThat(text.length()).isLessThanOrEqualTo(40);
    }

    @Test
    void differentInputsProduceVariedPhrasingRatherThanOneFixedShape() {
        List<TownRetellGenerator.Request> requests = List.of(
            new TownRetellGenerator.Request("a", "柯云", "p", List.of(), 1, "小吉最近挺常往健身房跑"),
            new TownRetellGenerator.Request("b", "陆夏", "p", List.of("EXAGGERATE"), 1, "小吉这周几乎天天出来走动"),
            new TownRetellGenerator.Request("c", "温晴", "p", List.of("NOSTALGIC"), 2, "沈牧好像总在忙工作上的事"),
            new TownRetellGenerator.Request("d", "安禾", "p", List.of("TIGHT_LIPPED"), 3, "柯云今天在公园坐了一下午"),
            new TownRetellGenerator.Request("e", "纪麦", "p", List.of("NAME_MIXUP"), 2, "柯云今天在公园坐了一下午")
        );

        List<String> outputs = new ArrayList<>();
        for (TownRetellGenerator.Request request : requests) {
            outputs.add(generator.generate(request));
        }

        assertThat(Set.copyOf(outputs)).hasSize(outputs.size());
    }

    private boolean startsWithAny(String text, String[] options) {
        for (String option : options) {
            if (text.startsWith(option)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void stripsThePreviousHandsReportingFrameInsteadOfStackingAnotherOneOnTop() {
        TemplateRetellGenerator generator = new TemplateRetellGenerator();

        // 上一手已经是"听说……"了；这一手不该在它前面再叠一层转述框。
        String third = generator.retell(List.of(new TownRetellGenerator.Request(
            "k", "纪麦", "persona", List.of(), 3, "听说小吉最近迷上健身了"))).get(0).text();

        assertThat(third).doesNotContain("说好像听说").doesNotContain("说听说");
        // 内容留着，框换成这一手自己的。
        assertThat(third).contains("小吉").contains("健身");
    }

    @Test
    void stripsSeveralStackedFramesNotJustTheOutermost() {
        TemplateRetellGenerator generator = new TemplateRetellGenerator();

        String text = generator.retell(List.of(new TownRetellGenerator.Request(
            "k", "纪麦", "persona", List.of(), 2, "听说据说小吉最近迷上健身了"))).get(0).text();

        assertThat(text).doesNotContain("听说据说");
        assertThat(text).contains("小吉");
    }

    @Test
    void leavesAFrameAloneWhenItIsTheWholeSentence() {
        TemplateRetellGenerator generator = new TemplateRetellGenerator();

        // 全句就是一个框时不能拆成空串。
        String text = generator.retell(List.of(new TownRetellGenerator.Request(
            "k", "纪麦", "persona", List.of(), 1, "听说"))).get(0).text();

        assertThat(text).isNotBlank();
    }
}
