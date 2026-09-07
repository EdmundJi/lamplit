package com.betterself.growth.privacy;

import com.betterself.growth.execution.IdempotencyService;
import com.betterself.growth.shared.api.ApiException;
import com.betterself.growth.shared.id.PublicIdGenerator;
import com.betterself.growth.shared.storage.ObjectStorage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class ExportService {

    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final PublicIdGenerator ids;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ExportService(
        JdbcTemplate jdbc,
        ObjectStorage storage,
        PublicIdGenerator ids,
        IdempotencyService idempotency,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.ids = ids;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public ExportView create(long userId, String key) {
        IdempotencyService.BeginResult begin = idempotency.begin(userId, "DATA_EXPORT", key, Map.of("format", "zip-v1"));
        if (begin.replay()) {
            return idempotency.replay(begin, ExportView.class);
        }
        Integer recent = jdbc.queryForObject(
            "select count(*) from data_export_job where user_id = ? and requested_at > ?",
            Integer.class, userId, Timestamp.from(clock.instant().minus(Duration.ofHours(24)))
        );
        if (recent != null && recent > 0) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "EXPORT_RATE_LIMIT", "Only one export can be requested every 24 hours");
        }
        String publicId = ids.next();
        String objectKey = "exports/" + userId + "/" + publicId + ".zip";
        byte[] archive = archive(userId);
        storage.put(objectKey, archive, "application/zip");
        String checksum = sha256(archive);
        jdbc.update(
            """
                insert into data_export_job (
                    public_id, user_id, status, object_key, checksum_sha256,
                    requested_at, completed_at, expires_at
                ) values (?, ?, 'READY', ?, ?, ?, ?, ?)
                """,
            publicId, userId, objectKey, checksum, Timestamp.from(clock.instant()), Timestamp.from(clock.instant()),
            Timestamp.from(clock.instant().plus(RetentionPolicy.EXPORT_OBJECT_TTL))
        );
        ExportView result = view(userId, publicId);
        idempotency.complete(userId, "DATA_EXPORT", key, result, publicId);
        return result;
    }

    public ExportView view(long userId, String publicId) {
        ExportView view = jdbc.query(
            "select public_id, status, checksum_sha256, requested_at, completed_at, expires_at from data_export_job where user_id = ? and public_id = ?",
            rs -> rs.next() ? new ExportView(
                rs.getString("public_id"), rs.getString("status"), rs.getString("checksum_sha256"),
                rs.getTimestamp("requested_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
                rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant()
            ) : null,
            userId, publicId
        );
        if (view == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "EXPORT_NOT_FOUND", "Resource not found");
        }
        return view;
    }

    public DownloadView download(long userId, String publicId) {
        ObjectRow row = object(userId, publicId);
        if (!"READY".equals(row.status()) || row.expiresAt().isBefore(clock.instant())) {
            throw new ApiException(HttpStatus.GONE, "EXPORT_EXPIRED", "Export is no longer available");
        }
        return new DownloadView(storage.presignDownload(row.objectKey(), RetentionPolicy.EXPORT_DOWNLOAD_TTL), 900);
    }

    public byte[] content(long userId, String publicId) {
        ObjectRow row = object(userId, publicId);
        if (!"READY".equals(row.status()) || row.expiresAt().isBefore(clock.instant())) {
            throw new ApiException(HttpStatus.GONE, "EXPORT_EXPIRED", "Export is no longer available");
        }
        return storage.get(row.objectKey());
    }

    @Transactional
    public int expireDue() {
        List<ObjectRow> due = jdbc.query(
            "select public_id, object_key, status, expires_at from data_export_job where status = 'READY' and expires_at <= ?",
            (rs, row) -> new ObjectRow(
                rs.getString("public_id"), rs.getString("object_key"), rs.getString("status"), rs.getTimestamp("expires_at").toInstant()
            ),
            Timestamp.from(clock.instant())
        );
        for (ObjectRow row : due) {
            storage.delete(row.objectKey());
            jdbc.update("update data_export_job set status = 'EXPIRED' where public_id = ?", row.publicId());
        }
        return due.size();
    }

    private ObjectRow object(long userId, String publicId) {
        ObjectRow row = jdbc.query(
            "select public_id, object_key, status, expires_at from data_export_job where user_id = ? and public_id = ?",
            rs -> rs.next() ? new ObjectRow(
                rs.getString("public_id"), rs.getString("object_key"), rs.getString("status"), rs.getTimestamp("expires_at").toInstant()
            ) : null,
            userId, publicId
        );
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "EXPORT_NOT_FOUND", "Resource not found");
        }
        return row;
    }

    private byte[] archive(long userId) {
        Map<String, Object> profile = jdbc.queryForMap(
            "select public_id, display_name, timezone, created_at from sys_user where id = ?", userId
        );
        List<Map<String, Object>> goals = jdbc.queryForList(
            "select public_id, title, description, start_date, end_date, status from growth_goal where user_id = ?", userId
        );
        List<Map<String, Object>> events = jdbc.queryForList(
            "select public_id, event_type, occurred_at, completion_ratio, experience_delta, role_code_snapshot, role_experience_delta from task_event where user_id = ?", userId
        );
        List<Map<String, Object>> roleProgress = jdbc.queryForList(
            "select role_code, level, experience, updated_at from user_role_progress where user_id = ? order by id", userId
        );
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(bytes)) {
            add(zip, "manifest.json", objectMapper.writeValueAsString(Map.of(
                "formatVersion", 1, "generatedAt", clock.instant().toString(),
                "categories", List.of("profile", "goals", "task_events", "role_progress", "town_experience")
            )));
            add(zip, "profile.json", objectMapper.writeValueAsString(profile));
            add(zip, "goals.csv", csv(goals));
            add(zip, "task_events.csv", csv(events));
            add(zip, "role_progress.csv", csv(roleProgress));
            Map<String, Object> town = new LinkedHashMap<>();
            town.put("companion", jdbc.queryForList("select state_json,updated_at from town_companion_world where user_id=?", userId));
            town.put("stories", jdbc.queryForList("select npc_code,stage,revision,paused,participation,started_at,updated_at,completed_at from town_story_progress where town_user_id=?", userId));
            town.put("sharing", jdbc.queryForList("select enabled,style,updated_at from town_visit_profile where user_id=?", userId));
            town.put("displayed_mementos", jdbc.queryForList("select achievement_code from town_visit_memento where user_id=?", userId));
            town.put("postcards", jdbc.queryForList("select public_id,body,created_at,owner_deleted,sender_deleted from town_visit_postcard where owner_user_id=? or sender_user_id=?", userId,userId));
            town.put("events", jdbc.queryForList("select public_id,kind,venue,starts_at,ends_at,player_response,attended_at,cancelled_at from town_event where town_user_id=?", userId));
            add(zip, "town_experience.json", objectMapper.writeValueAsString(town));
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to build export archive", exception);
        }
    }

    private String csv(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return "";
        }
        List<String> columns = List.copyOf(rows.getFirst().keySet());
        StringBuilder value = new StringBuilder(String.join(",", columns)).append('\n');
        for (Map<String, Object> row : rows) {
            for (int index = 0; index < columns.size(); index++) {
                if (index > 0) value.append(',');
                String cell = String.valueOf(row.get(columns.get(index))).replace("\"", "\"\"");
                value.append('"').append(cell).append('"');
            }
            value.append('\n');
        }
        return value.toString();
    }

    private void add(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record ExportView(
        String publicId,
        String status,
        String checksumSha256,
        java.time.Instant requestedAt,
        java.time.Instant completedAt,
        java.time.Instant expiresAt
    ) {
    }

    public record DownloadView(String url, int expiresInSeconds) {
    }

    private record ObjectRow(String publicId, String objectKey, String status, java.time.Instant expiresAt) {
    }
}
