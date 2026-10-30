package com.appsmith.server.grants;

import com.appsmith.server.dtos.ResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Administrator-only API for teams and their members. The ADMIN check is in {@link TeamService}.
 */
@RestController
@RequestMapping(TeamController.URL)
@RequiredArgsConstructor
public class TeamController {

    static final String URL = "/api/v1/teams";

    private final TeamService teamService;

    @GetMapping
    public Mono<ResponseDTO<List<TeamDTO>>> list() {
        return teamService.list().map(teams -> new ResponseDTO<>(HttpStatus.OK, teams));
    }

    @GetMapping("/{teamId}")
    public Mono<ResponseDTO<TeamDTO>> get(@PathVariable String teamId) {
        return teamService.get(teamId).map(team -> new ResponseDTO<>(HttpStatus.OK, team));
    }

    @PostMapping
    public Mono<ResponseDTO<TeamDTO>> create(@RequestBody TeamDTO.Request request) {
        return teamService.create(request).map(team -> new ResponseDTO<>(HttpStatus.CREATED, team));
    }

    @PutMapping("/{teamId}")
    public Mono<ResponseDTO<TeamDTO>> update(@PathVariable String teamId, @RequestBody TeamDTO.Request request) {
        return teamService.update(teamId, request).map(team -> new ResponseDTO<>(HttpStatus.OK, team));
    }

    @DeleteMapping("/{teamId}")
    public Mono<ResponseDTO<Boolean>> delete(@PathVariable String teamId) {
        return teamService.delete(teamId).thenReturn(new ResponseDTO<>(HttpStatus.OK, true));
    }

    @PutMapping("/{teamId}/members/{userId}")
    public Mono<ResponseDTO<TeamDTO>> addMember(@PathVariable String teamId, @PathVariable String userId) {
        return teamService.addMember(teamId, userId).map(team -> new ResponseDTO<>(HttpStatus.OK, team));
    }

    @DeleteMapping("/{teamId}/members/{userId}")
    public Mono<ResponseDTO<TeamDTO>> removeMember(@PathVariable String teamId, @PathVariable String userId) {
        return teamService.removeMember(teamId, userId).map(team -> new ResponseDTO<>(HttpStatus.OK, team));
    }
}
