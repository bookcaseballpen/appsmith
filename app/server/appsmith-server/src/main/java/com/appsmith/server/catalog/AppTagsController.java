package com.appsmith.server.catalog;

import com.appsmith.server.constants.Url;
import com.appsmith.server.dtos.ResponseDTO;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Fork: sets an app's tags (see {@link ApplicationCatalogService#setTags}), for the UI and the MCP tool set_app_tags.
 *
 * <p>Its own route family, {@link Url#APP_TAGS_URL}, so the MCP allowlist can list it as an edition extension
 * ({@code McpAllowlistExtensions}) without widening the core {@code /api/v1/applications} rules, and so the read-only
 * editor's lock on {@code PUT /api/v1/applications/{id}} does not cover it: tags are metadata, not app content.
 */
@RestController
@RequestMapping(Url.APP_TAGS_URL)
public class AppTagsController {

    private final ApplicationCatalogService applicationCatalogService;

    public AppTagsController(ApplicationCatalogService applicationCatalogService) {
        this.applicationCatalogService = applicationCatalogService;
    }

    @PutMapping("/applications/{applicationId}")
    public Mono<ResponseDTO<ApplicationCatalogDTO.TagsResult>> setTags(
            @PathVariable String applicationId, @RequestBody ApplicationCatalogDTO.TagsRequest request) {
        return applicationCatalogService
                .setTags(applicationId, request == null ? null : request.tags())
                .map(result -> new ResponseDTO<>(HttpStatus.OK, result));
    }
}
