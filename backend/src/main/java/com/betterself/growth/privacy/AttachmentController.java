package com.betterself.growth.privacy;

import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiEnvelope;
import com.betterself.growth.shared.api.ApiException;
import com.betterself.growth.shared.id.PublicIdGenerator;
import com.betterself.growth.shared.storage.ObjectStorage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;

@RestController
@RequestMapping("/api/v1/attachments")
public class AttachmentController {

    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final PublicIdGenerator ids;
    private final Clock clock;

    public AttachmentController(JdbcTemplate jdbc, ObjectStorage storage, PublicIdGenerator ids, Clock clock) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.ids = ids;
        this.clock = clock;
    }

    @PostMapping("/presign")
    ApiEnvelope<PresignView> presign(
        @AuthenticationPrincipal CurrentUser user,
        @RequestBody PresignCommand body,
        HttpServletRequest request
    ) {
        AttachmentPolicy.validate(body.contentType(), body.extension(), body.sizeBytes());
        String publicId = ids.next();
        String objectKey = "attachments/" + user.id() + "/" + publicId + "." + body.extension().toLowerCase();
        jdbc.update(
            "insert into attachment (public_id, user_id, object_key, original_filename, content_type, extension, size_bytes) values (?, ?, ?, ?, ?, ?, ?)",
            publicId, user.id(), objectKey, body.filename(), body.contentType(), body.extension().toLowerCase(), body.sizeBytes()
        );
        return envelope(new PresignView(publicId, storage.presignUpload(objectKey, body.contentType(), Duration.ofMinutes(15)), 900), request);
    }

    @PostMapping("/{attachmentId}/associate")
    ApiEnvelope<AttachmentView> associate(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String attachmentId,
        @RequestBody AssociateCommand body,
        HttpServletRequest request
    ) {
        AttachmentRow attachment = jdbc.query(
            "select id, scan_status from attachment where user_id = ? and public_id = ? and deleted_at is null",
            rs -> rs.next() ? new AttachmentRow(rs.getLong(1), rs.getString(2)) : null,
            user.id(), attachmentId
        );
        if (attachment == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "Resource not found");
        }
        AttachmentPolicy.requireClean(attachment.scanStatus());
        Long eventId = jdbc.query(
            "select id from task_event where user_id = ? and public_id = ?",
            rs -> rs.next() ? rs.getLong(1) : null,
            user.id(), body.taskEventPublicId()
        );
        if (eventId == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TASK_EVENT_NOT_FOUND", "Resource not found");
        }
        jdbc.update("update attachment set task_event_id = ? where id = ?", eventId, attachment.id());
        return envelope(new AttachmentView(attachmentId, body.taskEventPublicId(), "CLEAN"), request);
    }

    private <T> ApiEnvelope<T> envelope(T data, HttpServletRequest request) {
        return ApiEnvelope.of(data, String.valueOf(request.getAttribute("requestId")), clock);
    }

    public record PresignCommand(String filename, String contentType, String extension, long sizeBytes) {
    }

    public record PresignView(String attachmentPublicId, String uploadUrl, int expiresInSeconds) {
    }

    public record AssociateCommand(String taskEventPublicId) {
    }

    public record AttachmentView(String publicId, String taskEventPublicId, String scanStatus) {
    }

    private record AttachmentRow(long id, String scanStatus) {
    }
}
