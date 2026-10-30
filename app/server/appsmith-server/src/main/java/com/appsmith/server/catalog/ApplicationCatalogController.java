package com.appsmith.server.catalog;

import com.appsmith.server.constants.Url;
import com.appsmith.server.dtos.ResponseDTO;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Fork: the home page catalog and the distinct tags (see {@link ApplicationCatalogService}). Setting tags is
 * {@link AppTagsController}.
 */
@RestController
@RequestMapping(Url.APPLICATION_URL)
public class ApplicationCatalogController {

    private final ApplicationCatalogService applicationCatalogService;

    public ApplicationCatalogController(ApplicationCatalogService applicationCatalogService) {
        this.applicationCatalogService = applicationCatalogService;
    }

    @GetMapping("/catalog")
    public Mono<ResponseDTO<ApplicationCatalogDTO>> getCatalog(
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "tags", required = false) List<String> tags,
            @RequestParam(name = "tagMode", required = false) String tagMode,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "order", required = false) String order,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        return applicationCatalogService
                .getCatalog(new ApplicationCatalogService.CatalogQuery(search, tags, tagMode, sort, order, page, size))
                .map(catalog -> new ResponseDTO<>(HttpStatus.OK, catalog));
    }

    @GetMapping("/tags")
    public Mono<ResponseDTO<List<String>>> getTags() {
        return applicationCatalogService.getTags().map(tags -> new ResponseDTO<>(HttpStatus.OK, tags));
    }
}
