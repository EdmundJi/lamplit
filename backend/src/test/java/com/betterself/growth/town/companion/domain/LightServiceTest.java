package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real, resident-switched room lighting (see {@link LightService}) - "目前是房间的灯自动亮起，应该是让
 * 小人到房间可以操作开关". Before this batch a lit window was a client-side guess off the clock plus
 * who happened to be nearby; now it is world state that only ever changes because a resident who is
 * actually there, awake, walked up to the switch.
 *
 * <p>Worlds are joined at {@code noon} throughout - not at night - specifically so {@code
 * ResidentSeed.initialize}'s own deterministic warm start (a short run of historical {@code step}s
 * immediately after join) never itself races the new "switch_light" interlude; every scenario below
 * then drives a resident through a real dark room by calling {@link ResidentSimulation#schedule}
 * directly at an explicit {@code night} instant, the same style {@code CafeDoorTest} already uses.
 */
class LightServiceTest {
    /** 12:00 local (Asia/Shanghai) - the middle of anybody's day. */
    private final Instant noon = Instant.parse("2026-09-08T04:00:00Z");
    /** 02:00 local (Asia/Shanghai) - the middle of anybody's night, same convention as AvatarSleepTest. */
    private final Instant night = Instant.parse("2026-09-08T18:00:00Z");

    private CompanionWorld world(String id) {
        CompanionWorld w = CompanionRules.join(id, "住客", "Asia/Shanghai", noon);
        w.conversations.stream().filter(c -> "active".equals(c.status))
            .forEach(c -> ConversationLifecycle.finish(w, c, noon, "测试准备"));
        return w;
    }
    private static String lightState(CompanionWorld w, String roomId) {
        return w.objects.stream().filter(o -> "light".equals(o.kind()) && roomId.equals(o.roomId()))
            .findFirst().orElseThrow(() -> new AssertionError("no light object for room " + roomId)).state();
    }

    @Test
    @DisplayName("建成/老存档补全：每个居民自己的房间、咖啡馆、学院、健身房、商店都有一盏灯，起始是关的")
    void seedingBackfillsEveryLightOffByDefault() {
        CompanionWorld w = world("light-seed");
        List<String> expectedRoomIds = List.of(
            "home-owner-room-owner", "home-student-room-student", "home-artist-room-artist",
            "home-gardener-room-gardener", "cafe-main", "academy-reading-room", "gym-training-room", "shop-workroom");
        List<WorldObject> lights = w.objects.stream().filter(o -> "light".equals(o.kind())).toList();
        assertThat(lights).extracting(WorldObject::roomId).containsAll(expectedRoomIds);
        assertThat(lights).allMatch(o -> "off".equals(o.state()));
        assertThat(lights).extracting(WorldObject::id).allMatch(id -> id.startsWith("light:"));
        // Snapshot projection: CompanionService.View wraps the whole CompanionWorld as-is, so this
        // record's own kind/state/roomId accessors ARE what reaches the frontend's SceneObject - no
        // separate projection step to keep in sync.
        WorldObject cafeLight = lights.stream().filter(o -> "cafe-main".equals(o.roomId())).findFirst().orElseThrow();
        assertThat(cafeLight.kind()).isEqualTo("light");
        assertThat(cafeLight.state()).isEqualTo("off");
        assertThat(cafeLight.roomId()).isEqualTo("cafe-main");
        assertThat(cafeLight.label()).isEqualTo("咖啡馆的灯");
    }

    @Test
    @DisplayName("小人在暗处进房间且灯是关的——先摸黑开灯（短活动+事件），再继续原本要做的事")
    void residentEnteringADarkRoomTurnsTheLightOnAfterAShortSwitchActivity() {
        CompanionWorld w = world("light-on");
        ResidentState gardener = ResidentSimulation.state(w, "gardener");
        String home = TownPlaces.homeOf("gardener"), roomId = home + "-room-gardener";

        ResidentSimulation.schedule(w, gardener, "rest", home, null, "回家歇一会儿", night, 600);

        assertThat(gardener.plan.action()).isEqualTo("switch_light");
        assertThat(ResidentSimulation.actor(w, "gardener").activity()).isEqualTo("switch_light");
        assertThat(lightState(w, roomId)).as("还在摸黑，灯还没真的亮").isEqualTo("off");

        ResidentSimulation.step(w, night.plusSeconds(130));

        assertThat(lightState(w, roomId)).isEqualTo("on");
        assertThat(gardener.plan.action()).as("开完灯，接着做本来要做的事").isEqualTo("rest");
        assertThat(w.events).anyMatch(e -> "light_on".equals(e.type()) && e.actorIds().equals(List.of("gardener")) && e.place().equals(home));
    }

    @Test
    @DisplayName("白天进房间——灯是关的，也不会被打开")
    void enteringARoomInDaytimeNeverTriggersTheSwitch() {
        CompanionWorld w = world("light-daytime");
        ResidentState gardener = ResidentSimulation.state(w, "gardener");
        String roomId = "gym-training-room";

        ResidentSimulation.schedule(w, gardener, "exercise", "gym", null, "锻炼一下", noon, 900);

        assertThat(gardener.plan.action()).as("白天不需要先开灯").isEqualTo("exercise");
        assertThat(lightState(w, roomId)).isEqualTo("off");
    }

    @Test
    @DisplayName("没人去过的房间，夜里也不会自己亮——只有真的开过灯的房间才会")
    void aRoomNobodyEverEntersStaysOffAtNight() {
        CompanionWorld w = world("light-empty");
        ResidentState gardener = ResidentSimulation.state(w, "gardener");

        ResidentSimulation.schedule(w, gardener, "exercise", "gym", null, "锻炼一下", night, 900);
        ResidentSimulation.step(w, night.plusSeconds(130));

        assertThat(lightState(w, "gym-training-room")).as("证明这一夜机制确实跑过了").isEqualTo("on");
        assertThat(lightState(w, "shop-workroom")).as("没人去过商店——灯不会自己变").isEqualTo("off");
    }

    @Test
    @DisplayName("还有别人醒着留在房间里——先走的人不用关灯")
    void aResidentLeavingIsNotTheLastAwakeOneLeavesTheLightOn() {
        CompanionWorld w = world("light-not-last");
        ResidentState student = ResidentSimulation.state(w, "student");
        ResidentState owner = ResidentSimulation.state(w, "owner");
        String roomId = "academy-reading-room";

        ResidentSimulation.schedule(w, student, "study", "academy", null, "去学院看书", night, 1800);
        ResidentSimulation.step(w, night.plusSeconds(130));
        assertThat(lightState(w, roomId)).isEqualTo("on");

        ResidentSimulation.schedule(w, owner, "observe", "academy", null, "过去看看", night.plusSeconds(140), 1800);
        assertThat(ResidentSimulation.actor(w, "owner").activity()).as("灯已经亮着，不用再摸黑开一次").isEqualTo("observe");

        // 学生先走——阿禾还醒着留在房间里，灯不该被关
        ResidentSimulation.schedule(w, student, "wander", "street", null, "先出去逛逛", night.plusSeconds(200), 60);
        assertThat(lightState(w, roomId)).isEqualTo("on");
    }

    @Test
    @DisplayName("最后一个醒着的人离开——顺手关灯")
    void theLastAwakeResidentLeavingSwitchesTheLightOff() {
        CompanionWorld w = world("light-off-leave");
        ResidentState student = ResidentSimulation.state(w, "student");
        ResidentState owner = ResidentSimulation.state(w, "owner");
        String roomId = "academy-reading-room";

        ResidentSimulation.schedule(w, student, "study", "academy", null, "去学院看书", night, 1800);
        ResidentSimulation.step(w, night.plusSeconds(130));
        ResidentSimulation.schedule(w, owner, "observe", "academy", null, "过去看看", night.plusSeconds(140), 1800);
        ResidentSimulation.schedule(w, student, "wander", "street", null, "先出去逛逛", night.plusSeconds(200), 60);
        assertThat(lightState(w, roomId)).isEqualTo("on");

        ResidentSimulation.schedule(w, owner, "wander", "street", null, "也回去了", night.plusSeconds(260), 60);

        assertThat(lightState(w, roomId)).isEqualTo("off");
        assertThat(w.events).anyMatch(e -> "light_off".equals(e.type()) && e.actorIds().equals(List.of("owner")) && "academy".equals(e.place()));
    }

    @Test
    @DisplayName("最后一个醒着的人是去睡觉——睡前先关灯，不是开着灯睡")
    void theLastAwakeResidentGoingToSleepTurnsTheLightOffFirst() {
        CompanionWorld w = world("light-off-sleep");
        ResidentState gardener = ResidentSimulation.state(w, "gardener");
        String home = TownPlaces.homeOf("gardener"), roomId = home + "-room-gardener";

        ResidentSimulation.schedule(w, gardener, "sleep", home, null, "该睡了", night, 28800);
        assertThat(gardener.plan.action()).as("先摸黑开灯，才看得见床在哪").isEqualTo("switch_light");

        ResidentSimulation.step(w, night.plusSeconds(130));

        assertThat(gardener.plan.action()).isEqualTo("sleep");
        assertThat(ResidentSimulation.actor(w, "gardener").activity()).isEqualTo("sleep");
        assertThat(lightState(w, roomId)).as("躺下前又把灯关了").isEqualTo("off");
        assertThat(w.events.stream().filter(e -> "light_on".equals(e.type()) && e.place().equals(home))).hasSize(1);
        assertThat(w.events.stream().filter(e -> "light_off".equals(e.type()) && e.place().equals(home))).hasSize(1);
    }

    @Test
    @DisplayName("咖啡馆打烊——同一个开关，事件文本已经说了灯熄了，不再重复记一条")
    void cafeClosingTurnsTheCafeLightOffThroughTheSameSwitch() {
        CompanionWorld w = world("light-cafe-close");
        ResidentState owner = ResidentSimulation.state(w, "owner");
        ResidentSimulation.replaceActor(w, "owner", "cafe", "idle", "在吧台后面", night.plusSeconds(3600), night);
        owner.plan = null;
        ResidentSimulation.schedule(w, owner, "tend", "cafe", null, "照看柜台", night, 1800);
        ResidentSimulation.step(w, night.plusSeconds(130));
        assertThat(lightState(w, "cafe-main")).isEqualTo("on");

        ResidentSimulation.replaceActor(w, "owner", "street", "walk", "下班了", night.plusSeconds(4000), night.plusSeconds(200));
        owner.plan = null;
        // Nobody else left inside either, so the cafe can actually finish closing once "owner" says so.
        for (String other : List.of("student", "artist", "gardener")) {
            ResidentState o = ResidentSimulation.state(w, other);
            if ("cafe".equals(ResidentSimulation.actor(w, other).place())) {
                ResidentSimulation.replaceActor(w, other, "street", "walk", "也走了", night.plusSeconds(4000), night.plusSeconds(200));
                o.plan = null;
            }
        }
        assertThat(CafeService.pauseOperation(w, "owner", night.plusSeconds(210))).isTrue();
        ResidentSimulation.step(w, night.plusSeconds(220));

        assertThat(w.cafeStatus).isEqualTo("closed");
        assertThat(lightState(w, "cafe-main")).isEqualTo("off");
        assertThat(w.events).anyMatch(e -> "cafe_closed".equals(e.type()) && e.text().contains("灯熄了"));
        // The cafe's own close event already narrates the light going out - a second "light_off"
        // event here would only repeat it, so LightService.turnOffRoom writes none.
        assertThat(w.events).noneMatch(e -> "light_off".equals(e.type()));
    }
}
