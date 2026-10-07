package com.unifonic.contacts.resource;

import com.unifonic.contacts.dto.ContactGroupResponse;
import com.unifonic.contacts.dto.ContactRequest;
import com.unifonic.contacts.dto.ContactResponse;
import com.unifonic.contacts.dto.PagedResponse;
import com.unifonic.contacts.service.ContactService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.validation.Valid;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

import java.util.List;
import java.util.Map;

@Tag(
        name = "Contacts",
        description = "Operations for managing contacts"
)
@Path("/api/v1/contacts")
public class ContactResource {

    @Inject
    ContactService service;


    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get contact by ID",
            description = "Returns a contact by its ID")
    @APIResponse(
            responseCode = "200",
            description = "Contact retrieved successfully"
    )
    @APIResponse(
            responseCode = "404",
            description = "Contact not found"
    )
    public ContactResponse getById(@PathParam("id") Long id) {
        return service.getById(id);
    }


    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get all contacts",
            description = "Returns a paginated and filtered list of contacts")
    @APIResponse(
            responseCode = "200",
            description = "Contacts retrieved successfully"
    )
    @APIResponse(
            responseCode = "400",
            description = "Invalid pagination parameters"
    )
    public PagedResponse<ContactResponse> getAll(
            @QueryParam("page")
            @DefaultValue("0")
            @Min(0)
            int page,

            @QueryParam("size")
            @DefaultValue("10")
            @Min(1)
            @Max(100)
            int size,

            @QueryParam("owner")
            String owner
    )

    {

        return service.getAll(page, size, owner);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Create a contact",
            description = "Creates a new contact")
    @APIResponse(
            responseCode = "201",
            description = "Contact created successfully"
    )
    @APIResponse(
            responseCode = "400",
            description = "Invalid contact data"
    )
    @APIResponse(
            responseCode = "404",
            description = "Contact group not found"
    )
    @APIResponse(
            responseCode = "409",
            description = "Contact email already exists"
    )
    public Response create(@Valid ContactRequest request) {
        ContactResponse created = service.create(request);

        return Response.status(Response.Status.CREATED)
                .entity(created)
                .build();
    }


    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Update a contact",
            description = "Updates an existing contact")
    @APIResponse(
            responseCode = "200",
            description = "Contact updated successfully"
    )
    @APIResponse(
            responseCode = "400",
            description = "Invalid contact data"
    )
    @APIResponse(
            responseCode = "404",
            description = "Contact or contact group not found"
    )
    @APIResponse(
            responseCode = "409",
            description = "Contact email already exists"
    )
    public ContactResponse update(
            @PathParam("id") Long id,
            @Valid ContactRequest request) {

        return service.update(id, request);
    }



    @DELETE
    @Path("/{id}")
    @Operation(summary = "Delete a contact",
            description = "Deletes a contact by its ID")
    @APIResponse(
            responseCode = "200",
            description = "Contact deleted successfully"
    )
    @APIResponse(
            responseCode = "404",
            description = "Contact not found"
    )
    public Response delete(@PathParam("id") Long id) {
        service.delete(id);

        //  return Response.noContent().build();

        return Response.ok(Map.of("message", "Contact deleted successfully")).build();
    }





}