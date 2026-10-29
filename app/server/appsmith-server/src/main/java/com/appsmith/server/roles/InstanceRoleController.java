package com.appsmith.server.roles;

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
 * Administrator-only API to list users with their role and to change a user's role. The ADMIN check is in
 * {@link InstanceRoleService}.
 */
@RestController
@RequestMapping(InstanceRoleController.URL)
@RequiredArgsConstructor
public class InstanceRoleController {

    static final String URL = "/api/v1/admin/users";

    private final InstanceRoleService instanceRoleService;

    @GetMapping
    public Mono<ResponseDTO<List<InstanceRoleUserDTO>>> list() {
        return instanceRoleService.listUsers().map(users -> new ResponseDTO<>(HttpStatus.OK, users));
    }

    @PutMapping("/{userId}/role")
    public Mono<ResponseDTO<InstanceRoleUserDTO>> setRole(
            @PathVariable String userId, @RequestBody RoleUpdateRequest request) {
        return instanceRoleService
                .setRoleAsAdmin(userId, request == null ? null : request.role())
                .map(user -> new ResponseDTO<>(HttpStatus.OK, user));
    }

    public record RoleUpdateRequest(InstanceRole role) {}
}
