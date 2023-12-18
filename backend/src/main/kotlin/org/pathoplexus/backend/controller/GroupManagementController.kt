package org.pathoplexus.backend.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.pathoplexus.backend.service.GroupManagementDatabaseService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@Validated
@SecurityRequirement(name = "bearerAuth")
class GroupManagementController(
    private val groupManagementDatabaseService: GroupManagementDatabaseService,
) {

    @Operation(description = "Create a new Group. The user creating the group will be added to the group.")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/groups/{groupName}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun createNewGroup(
        @UsernameFromJwt username: String,
        @Parameter(
            description = "A new group name",
        ) @PathVariable groupName: String,
    ) = groupManagementDatabaseService.createNewGroup(groupName, username)
}
