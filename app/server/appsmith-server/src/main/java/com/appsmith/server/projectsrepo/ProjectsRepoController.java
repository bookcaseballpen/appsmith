package com.appsmith.server.projectsrepo;

import com.appsmith.server.constants.Url;
import com.appsmith.server.dtos.ResponseDTO;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Fork: the app JSON of the projects repo (see {@link ProjectsRepoService}). MCP tokens reach both routes (see
 * {@code McpAllowlistExtensions}); permissions are the services' own: the edit permission on the app for the export,
 * the normal import permissions for the import.
 */
@RestController
@RequestMapping(Url.PROJECTS_REPO_URL)
public class ProjectsRepoController {

    private final ProjectsRepoService projectsRepoService;

    public ProjectsRepoController(ProjectsRepoService projectsRepoService) {
        this.projectsRepoService = projectsRepoService;
    }

    @GetMapping("/applications/{applicationId}")
    public Mono<ResponseDTO<Object>> exportEditCopy(@PathVariable String applicationId) {
        return projectsRepoService
                .exportEditCopy(applicationId)
                .map(appJson -> new ResponseDTO<>(HttpStatus.OK, appJson));
    }

    @PostMapping(value = "/applications/import/{workspaceId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseDTO<ProjectsRepoService.ImportResult>> importEditCopy(
            @PathVariable String workspaceId,
            @RequestParam(name = "applicationId", required = false) String applicationId,
            @RequestBody String appJson) {
        return projectsRepoService
                .importEditCopy(workspaceId, applicationId, appJson)
                .map(result -> new ResponseDTO<>(HttpStatus.OK, result));
    }
}
