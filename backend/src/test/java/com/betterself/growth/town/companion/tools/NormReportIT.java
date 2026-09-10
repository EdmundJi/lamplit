package com.betterself.growth.town.companion.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs {@link NormDetector} over runs that already happened. Every input it needs is in the exported
 * timeline, so re-reading four past runs costs nothing - which is the point: the instrument has to be
 * arguable before we spend another model run on it.
 *
 * <pre>NORM_RUN_DIRS=/tmp/town-run-v2,/tmp/town-run-venture ./mvnw test -Dtest=NormReportIT</pre>
 *
 * Optionally {@code NORM_CONTROL_DIR} names a rule-only run of the same world; the report then also
 * prints what is left after subtracting everything the rules alone produced.
 */
@EnabledIfEnvironmentVariable(named = "NORM_RUN_DIRS", matches = ".+")
class NormReportIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private static NormDetector.Report read(Path dir) throws Exception {
        List<Map<String, Object>> entries = JSON.readValue(
                Files.readString(dir.resolve("timeline.json"), StandardCharsets.UTF_8), List.class);
        String timezone = "Asia/Shanghai";
        Path manifest = dir.resolve("manifest.json");
        if (Files.exists(manifest)) {
            Map<String, Object> m = JSON.readValue(Files.readString(manifest, StandardCharsets.UTF_8), Map.class);
            if (m.get("timezone") instanceof String tz) timezone = tz;
        }
        // world-snapshot.json is optional - older runs never exported one, and a missing "居民能自己
        //说出来" half is a real 0 (see NormDetector's class comment), not a reason to fail the read.
        List<Map<String, Object>> memories = List.of();
        Path snapshot = dir.resolve("world-snapshot.json");
        if (Files.exists(snapshot)) {
            Map<String, Object> world = JSON.readValue(Files.readString(snapshot, StandardCharsets.UTF_8), Map.class);
            if (world.get("memories") instanceof List<?> ms) memories = (List<Map<String, Object>>) (List<?>) ms;
        }
        return NormDetector.detect(dir.getFileName().toString(), entries, memories, timezone);
    }

    @Test
    void report() throws Exception {
        List<NormDetector.Report> reports = new ArrayList<>();
        for (String dir : System.getenv("NORM_RUN_DIRS").split(",")) {
            Path path = Path.of(dir.trim());
            NormDetector.Report report = read(path);
            reports.add(report);
            Files.writeString(path.resolve("norms.md"), NormDetector.markdown(report), StandardCharsets.UTF_8);
            TimelineExporter.writeJson(path.resolve("norms.json"), report);
            System.out.println(NormDetector.markdown(report));
        }
        String controlDir = System.getenv("NORM_CONTROL_DIR");
        if (controlDir == null || controlDir.isBlank()) return;
        NormDetector.Report control = read(Path.of(controlDir.trim()));
        System.out.println("\n# 减掉规则自己就能长出来的之后\n");
        for (NormDetector.Report r : reports) {
            System.out.println("## " + r.label());
            List<NormDetector.Candidate> left = NormDetector.notWrittenByUs(r, control);
            if (left.isEmpty()) System.out.println("- 全都是我们写的。");
            for (NormDetector.Candidate c : left)
                System.out.println("- " + c.statement() + "（" + c.dimension() + "，强度 " + c.strength() + "）");
        }
        if (reports.size() == 2)
            System.out.println("\n判定：" + NormDetector.judge(reports.get(0), reports.get(1), control));
    }
}
