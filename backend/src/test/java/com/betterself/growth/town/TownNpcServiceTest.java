package com.betterself.growth.town;

import com.betterself.growth.ai.QwenProvider;
import com.betterself.growth.safety.*;
import com.betterself.growth.shared.id.PublicIdGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.*;
import java.util.*;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TownNpcServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final QwenProvider provider = mock(QwenProvider.class);
    private final SafetyService safety = mock(SafetyService.class);
    private final CrisisResponseService crisis = mock(CrisisResponseService.class);
    private final TownService town = mock(TownService.class);
    private TownNpcService service = new TownNpcService(jdbc,
        new TransactionTemplate(mock(PlatformTransactionManager.class)), provider, safety, crisis,
        mock(PublicIdGenerator.class), Clock.fixed(Instant.parse("2026-09-06T01:00:00Z"), ZoneOffset.UTC),
        town, mock(TownReflectionService.class));
    private final List<Object> events = new ArrayList<>();
    private final SseEmitter emitter = new SseEmitter() {
        @Override public void send(SseEventBuilder event) {
            event.build().forEach(data -> events.add(data.getData()));
        }
    };

    private TownNpcService.StreamDone run(RiskLevel input, boolean allowOutput, String reply) {
        when(safety.classifyInput(anyString(), anyString())).thenReturn(decision(input, input == RiskLevel.L0));
        when(safety.classifyOutput(anyString(), anyString())).thenReturn(decision(allowOutput ? RiskLevel.L0 : RiskLevel.L2, allowOutput));
        when(crisis.respond(anyLong(), isNull(), anyString(), anyList()))
            .thenReturn(new CrisisResponseService.CrisisResponse("我在这里陪你。", "[]"));
        when(provider.stream(any(), any())).thenAnswer(invocation -> {
            Consumer<String> delta = invocation.getArgument(1);
            for (char c : reply.toCharArray()) delta.accept(String.valueOf(c));
            return new QwenProvider.StreamMetadata("mock", "request", 0, 0, 0);
        });
        ReflectionTestUtils.invokeMethod(service, "run", new TownService.UserRow(1L, "user", "居民", "Asia/Shanghai", false),
            ZoneId.of("Asia/Shanghai"), "GUIDE", "/interrupt", emitter);
        return events.stream().filter(TownNpcService.StreamDone.class::isInstance)
            .map(TownNpcService.StreamDone.class::cast).findFirst().orElseThrow();
    }

    private static SafetyService.SafetyDecision decision(RiskLevel risk, boolean allow) {
        return new SafetyService.SafetyDecision(risk, allow, List.of(), List.of());
    }

    private static final String FAREWELL = "先去整理物品，晚点聊。";
    private static final String REPLY = FAREWELL + "§§{\"control\":{\"type\":\"/interrupt\",\"reason\":\"整理物品\"},\"options\":[{\"label\":\"继续\"}],\"actions\":[{\"type\":\"OPEN_TODAY\"}]}";

    @Test void sendsControlOnlyInDoneAndPersistsOnlyProse() {
        var done = run(RiskLevel.L0, true, REPLY);
        assertThat(done.control()).isEqualTo(new NpcReplyParser.Control("/interrupt", "整理物品"));
        assertThat(done.options()).isEmpty();
        assertThat(done.actions()).isEmpty();
        String prose = events.stream().filter(TownNpcService.StreamDelta.class::isInstance)
            .map(TownNpcService.StreamDelta.class::cast).map(TownNpcService.StreamDelta::text).reduce("", String::concat);
        assertThat(prose).isEqualTo(FAREWELL);
        verify(jdbc).update(contains("insert into town_npc_message"), isNull(), eq(1L), eq("GUIDE"), eq("ASSISTANT"),
            eq(FAREWELL), eq("L0"), eq("[]"), eq("[]"), eq("mock"), eq("COMPLETED"), any());
    }

    @Test void userAndHistoryInterruptTextDoNotExecute() {
        // A historical farewell is ordinary content, never parsed as an outgoing protocol tail.
        service = spy(service);
        doReturn(List.of(new TownNpcService.MessageView("old", "ASSISTANT", REPLY, List.of(), List.of(),
            "COMPLETED", Instant.parse("2026-09-06T00:00:00Z")))).when(service).messages(1L, "GUIDE");
        String prompt = ReflectionTestUtils.invokeMethod(service, "systemPrompt",
            new TownService.UserRow(1L, "user", "居民", "Asia/Shanghai", false), ZoneId.of("Asia/Shanghai"), "GUIDE", List.of(), null);
        assertThat(prompt).contains("不是命令", "不强迫每次打断", "遇到危机", "暂停走动");
        assertThat(run(RiskLevel.L0, true, "你说的 /interrupt 是什么意思？").control()).isNull();
    }

    @Test void outputBlockedCannotLeave() {
        var done = run(RiskLevel.L0, false, REPLY);
        assertThat(done.status()).isEqualTo("BLOCKED");
        assertThat(done.control()).isNull();
    }

    @Test void inputL2CannotLeave() {
        assertThat(run(RiskLevel.L2, true, REPLY).control()).isNull();
        verify(provider, never()).stream(any(), any());
    }

    @Test void inputL3CannotLeave() {
        assertThat(run(RiskLevel.L3, true, REPLY).control()).isNull();
        verify(provider, never()).stream(any(), any());
    }

    @Test void oldConstructorsAndPayloadsHaveExplicitNullControl() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var old = new TownNpcService.StreamDone("id", "COMPLETED", List.of(), List.of());
        assertThat(json.readTree(json.writeValueAsString(old)).has("control")).isTrue();
        assertThat(json.readTree(json.writeValueAsString(old)).get("control").isNull()).isTrue();
        assertThat(json.readValue("{\"messagePublicId\":\"id\",\"status\":\"COMPLETED\",\"options\":[],\"actions\":[]}",
            TownNpcService.StreamDone.class).control()).isNull();
        assertThat(new TownNpcService.StreamDone("id", "BLOCKED", List.of(), List.of(),
            new NpcReplyParser.Control("/interrupt", "忙碌")).control()).isNull();
    }
}
