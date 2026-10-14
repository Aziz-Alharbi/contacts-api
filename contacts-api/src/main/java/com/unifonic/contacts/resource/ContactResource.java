package com.unifonic.contacts.resource;

import com.unifonic.contacts.dto.ContactRequest;
import com.unifonic.contacts.dto.ContactResponse;
import com.unifonic.contacts.dto.PagedResponse;
import com.unifonic.contacts.service.ContactService;
import jakarta.inject.Inject;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.validation.Valid;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;


@Tag(
        name = "Contacts",
        description = "Operations for managing contacts"
)
@Path("/api/v1/contacts")
@SecurityScheme(securitySchemeName = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
@SecurityRequirement(name = "bearerAuth")
@APIResponse(responseCode = "401", description = "Missing or invalid JWT")
@APIResponse(responseCode = "403", description = "Insufficient role or contact ownership")
@RolesAllowed({"user", "admin"})
public class ContactResource {

    @Inject
    ContactService service;


    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get contact by ID",
            description = "Returns an owned contact; administrators may read any owner’s contact")
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
            description = "Users list only their own contacts; administrators list all owners or filter by owner")
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
            description = "Contact created successfully",
            headers = @Header(name = "Location", description = "URL of the created contact")
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
    public Response create(@Valid ContactRequest request, @Context UriInfo uriInfo) {
        ContactResponse created = service.create(request);

        return Response.created(uriInfo.getAbsolutePathBuilder().path(created.getId().toString()).build())
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



    @RolesAllowed("admin")
    @DELETE
    @Path("/{id}")
    @Operation(summary = "Delete a contact",
            description = "Administrators only: deletes a contact by its ID regardless of owner")
    @APIResponse(
            responseCode = "204",
            description = "Contact deleted successfully"
    )
    @APIResponse(
            responseCode = "404",
            description = "Contact not found"
    )
    public Response delete(@PathParam("id") Long id) {
        service.delete(id);

        return Response.noContent().build();
    }





}
