package com.appsmith.server.grants;

import com.appsmith.server.dtos.ResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Administrator-only API for an app's grants: list them, and set one team's or user's level (VIEW, EDIT, or NONE to
 * revoke). The ADMIN check is in {@link AppGrantService}.
 */
@RestController
@RequestMapping(AppGrantController.URL)
@RequiredArgsConstructor
public class AppGrantController {

    static final String URL = "/api/v1/applications/{applicationId}/grants";

    private final AppGrantService appGrantService;

    @GetMapping
    public Mono<ResponseDTO<List<AppGrantDTO>>> list(@PathVariable String applicationId) {
        return appGrantService.getGrants(applicationId).map(grants -> new ResponseDTO<>(HttpStatus.OK, grants));
    }

    @PutMapping
    public Mono<ResponseDTO<List<AppGrantDTO>>> set(
            @PathVariable String applicationId, @RequestBody AppGrantDTO.Request request) {
        return appGrantService.setGrant(applicationId, request).map(grants -> new ResponseDTO<>(HttpStatus.OK, grants));
    }
}
