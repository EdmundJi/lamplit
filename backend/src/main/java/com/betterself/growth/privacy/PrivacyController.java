package com.betterself.growth.privacy;

import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Clock;

@RestController
@RequestMapping("/api/v1/privacy")
public class PrivacyController {

    private final ExportService exports;
    private final DeletionService deletions;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PrivacyController(ExportService exports, DeletionService deletions, JdbcTemplate jdbc, Clock clock) {
        this.exports = exports;
        this.deletions = deletions;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @PostMapping("/exports")
    ResponseEntity<ApiEnvelope<ExportService.ExportView>> createExport(
        @AuthenticationPrincipal CurrentUser user,
        @RequestHeader(name = "Idempotency-Key", required = false) String key,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(envelope(exports.create(user.id(), key), request));
    }

    @GetMapping("/exports/{exportId}")
    ApiEnvelope<ExportService.ExportView> exportStatus(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String exportId,
        HttpServletRequest request
    ) {
        return envelope(exports.view(user.id(), exportId), request);
    }

    @GetMapping("/exports/{exportId}/download")
    ApiEnvelope<ExportService.DownloadView> download(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String exportId,
        HttpServletRequest request
    ) {
        return envelope(exports.download(user.id(), exportId), request);
    }

    @GetMapping(value = "/exports/{exportId}/content", produces = "application/zip")
    ResponseEntity<byte[]> content(@AuthenticationPrincipal CurrentUser user, @PathVariable String exportId) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=better-self-export.zip")
            .contentType(MediaType.parseMediaType("application/zip")).body(exports.content(user.id(), exportId));
    }

    @PostMapping("/deletion")
    ResponseEntity<ApiEnvelope<DeletionService.DeletionView>> createDeletion(
        @AuthenticationPrincipal CurrentUser user,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(envelope(deletions.create(user.id()), request));
    }

    @GetMapping("/deletion")
    ApiEnvelope<DeletionService.DeletionView> currentDeletion(
        @AuthenticationPrincipal CurrentUser user,
        HttpServletRequest request
    ) {
        return envelope(deletions.current(user.id()), request);
    }

    @PostMapping("/deletion/cancel")
    ApiEnvelope<DeletionService.DeletionView> cancelDeletion(
        @AuthenticationPrincipal CurrentUser user,
        HttpServletRequest request
    ) {
        return envelope(deletions.cancel(user.id()), request);
    }

    @DeleteMapping("/ai/sessions/{sessionId}")
    ApiEnvelope<StatusView> deleteConversation(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String sessionId,
        HttpServletRequest request
    ) {
        jdbc.update("update ai_message m join ai_session s on s.id = m.session_id set m.content = '[DELETED]', m.deleted_at = ? where m.user_id = ? and s.public_id = ?",
            Timestamp.from(clock.instant()), user.id(), sessionId);
        jdbc.update("update ai_session set status = 'DELETED', updated_at = ? where user_id = ? and public_id = ?",
            Timestamp.from(clock.instant()), user.id(), sessionId);
        return envelope(new StatusView("DELETED"), request);
    }

    @DeleteMapping("/ai/memories/{memoryId}")
    ApiEnvelope<StatusView> deleteMemory(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String memoryId,
        HttpServletRequest request
    ) {
        jdbc.update("update ai_memory set content = '[DELETED]', status = 'DELETED', deleted_at = ? where user_id = ? and public_id = ?",
            Timestamp.from(clock.instant()), user.id(), memoryId);
        return envelope(new StatusView("DELETED"), request);
    }

    @PutMapping("/ai/retention")
    ApiEnvelope<StatusView> updateRetention(
        @AuthenticationPrincipal CurrentUser user,
        @RequestBody RetentionCommand body,
        HttpServletRequest request
    ) {
        int days = RetentionPolicy.requireAiRetentionDays(body.days());
        jdbc.update("update user_preference set ai_retention_days = ?, updated_at = ? where user_id = ?",
            days, Timestamp.from(clock.instant()), user.id());
        return envelope(new StatusView("UPDATED"), request);
    }

    private <T> ApiEnvelope<T> envelope(T data, HttpServletRequest request) {
        return ApiEnvelope.of(data, String.valueOf(request.getAttribute("requestId")), clock);
    }

    public record RetentionCommand(int days) {
    }

    public record StatusView(String status) {
    }
}
