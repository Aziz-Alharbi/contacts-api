package com.unifonic.contacts.resource;

import com.unifonic.contacts.dto.ContactResponse;
import com.unifonic.contacts.dto.PagedResponse;
import com.unifonic.contacts.service.ContactGroupService;
import com.unifonic.contacts.service.ContactService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;

import com.unifonic.contacts.dto.ContactGroupRequest;
import com.unifonic.contacts.dto.ContactGroupResponse;
import jakarta.validation.Valid;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Map;

@Tag(
        name = "Contact Groups",
        description = "Operations for managing groups"
)
@Path("/api/v1/groups")
public class ContactGroupResource {

    @Inject
    ContactGroupService service;

    @Inject
    ContactService contactservice;


    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get group by ID",
            description = "Returns a contact by its ID")
    @APIResponse(
            responseCode = "200",
            description = "Group retrieved successfully"
    )
    @APIResponse(
            responseCode = "404",
            description = "Group not found"
    )
    public ContactGroupResponse getById(@PathParam("id") Long id) {
        return service.getById(id);
    }


    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get all groups",
            description = "Returns a paginated and filtered list of groups")
    @APIResponse(
            responseCode = "200",
            description = "Groups retrieved successfully"
    )
    @APIResponse(
            responseCode = "400",
            description = "Invalid pagination parameters"
    )
    public PagedResponse<ContactGroupResponse> getAll(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("10") int size,
            @QueryParam("name") String name) {

        return service.getAll(page, size, name);
    }


    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Create a group",
            description = "Creates a new group")
    @APIResponse(
            responseCode = "201",
            description = "Group created successfully"
    )
    @APIResponse(
            responseCode = "400",
            description = "Invalid group data"
    )
    @APIResponse(
            responseCode = "409",
            description = "Group name already exists"
    )
    public Response create(@Valid ContactGroupRequest request) {
        ContactGroupResponse created = service.create(request);

        return Response.status(Response.Status.CREATED)
                .entity(created)
                .build();
    }


    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Update a group",
            description = "Updates an existing group")
    @APIResponse(
            responseCode = "201",
            description = "Group updated successfully"
    )
    @APIResponse(
            responseCode = "400",
            description = "Invalid group data"
    )
    @APIResponse(
            responseCode = "404",
            description = "Group not found"
    )
    @APIResponse(
            responseCode = "409",
            description = "Group name already exists"
    )
    public ContactGroupResponse update(
            @PathParam("id") Long id,
            @Valid ContactGroupRequest request) {

        return service.update(id, request);
    }



    @DELETE
    @Path("/{id}")
    @Operation(summary = "Delete a group",
            description = "Deletes a group by its ID")
    @APIResponse(
            responseCode = "200",
            description = "Group deleted successfully"
    )
    @APIResponse(
            responseCode = "404",
            description = "Group not found"
    )
    public Response delete(@PathParam("id") Long id) {
        service.delete(id);

      //  return Response.noContent().build();

        return Response.ok(
                Map.of("message", "Contact group deleted successfully")
        ).build();
    }


    @GET
    @Path("/{id}/contacts")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Get contacts by group",
            description = "Returns contacts belonging to a specific group"
    )
    @APIResponse(
            responseCode = "200",
            description = "Group contacts retrieved successfully"
    )
    @APIResponse(
            responseCode = "404",
            description = "Group not found"
    )
    public List<ContactResponse> getContactByGroup(@PathParam("id") Long groupId) {
        return contactservice.getByGroupId(groupId);
    }



}