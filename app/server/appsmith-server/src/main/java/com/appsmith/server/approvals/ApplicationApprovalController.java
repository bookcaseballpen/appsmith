package com.appsmith.server.approvals;

import com.appsmith.server.constants.Url;
import com.appsmith.server.dtos.ResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Fork: release approvals. Role checks are in {@link ApplicationApprovalService}. MCP tokens may only read the status
 * and submit (see {@code McpAllowlistExtensions}); approve and reject are for people.
 */
@RestController
@RequestMapping(Url.APPROVAL_URL)
@RequiredArgsConstructor
public class ApplicationApprovalController {

    private final ApplicationApprovalService applicationApprovalService;

    @GetMapping("/pending")
    public Mono<ResponseDTO<List<ApplicationApprovalDTO>>> pending() {
        return applicationApprovalService.listPending().map(list -> new ResponseDTO<>(HttpStatus.OK, list));
    }

    @GetMapping("/applications/{applicationId}")
    public Mono<ResponseDTO<ApplicationApprovalDTO>> status(@PathVariable String applicationId) {
        return applicationApprovalService.getStatus(applicationId).map(dto -> new ResponseDTO<>(HttpStatus.OK, dto));
    }

    @PostMapping("/applications/{applicationId}/submit")
    public Mono<ResponseDTO<ApplicationApprovalDTO>> submit(@PathVariable String applicationId) {
        return applicationApprovalService.submit(applicationId).map(dto -> new ResponseDTO<>(HttpStatus.OK, dto));
    }

    @PostMapping("/applications/{applicationId}/approve")
    public Mono<ResponseDTO<ApplicationApprovalDTO>> approve(@PathVariable String applicationId) {
        return applicationApprovalService.approve(applicationId).map(dto -> new ResponseDTO<>(HttpStatus.OK, dto));
    }

    @PostMapping("/applications/{applicationId}/reject")
    public Mono<ResponseDTO<ApplicationApprovalDTO>> reject(
            @PathVariable String applicationId, @RequestBody(required = false) RejectRequest request) {
        return applicationApprovalService
                .reject(applicationId, request == null ? null : request.comment())
                .map(dto -> new ResponseDTO<>(HttpStatus.OK, dto));
    }

    public record RejectRequest(String comment) {}
}
