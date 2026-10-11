package com.unifonic.contacts.service;

import com.unifonic.contacts.dto.ContactRequest;
import com.unifonic.contacts.dto.ContactResponse;
import com.unifonic.contacts.dto.PagedResponse;
import com.unifonic.contacts.entity.Contact;
import com.unifonic.contacts.exception.ApiException;
import com.unifonic.contacts.repository.ContactGroupRepository;
import com.unifonic.contacts.repository.ContactRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import org.eclipse.microprofile.jwt.JsonWebToken;
import jakarta.transaction.Transactional;
import com.unifonic.contacts.entity.ContactGroup;
import java.time.LocalDateTime;
import java.util.List;

@ApplicationScoped
@RolesAllowed({"user", "admin"})
public class ContactService {


    @Inject
    ContactRepository repository;

    @Inject
    JsonWebToken token;

    @Inject
    ContactGroupRepository contactGroupRepository;




    public PagedResponse<ContactResponse> getAll(
            int page,
            int size,
            String owner) {

        String subject = currentSubject();
        if (owner != null && !owner.isBlank() && !subject.equals(owner)) {
            throw new ForbiddenException("Cannot query another owner's contacts");
        }
        List<Contact> contacts = repository.findPagedByOwner(subject, page, size);
        long total = repository.countByOwner(subject);

        List<ContactResponse> items = contacts.stream()
                .map(this::toResponse)
                .toList();

        return new PagedResponse<>(items, page, size, total);
    }


    public ContactResponse getById(Long id) {
        Contact contact = repository.findById(id);

        if (contact == null) {
            throw new ApiException(
                    404,
                    "Not Found",
                    "Contact not found with id: " + id
            );

        }

        requireOwnership(contact);
        return toResponse(contact);
    }

    @Transactional
    public ContactResponse create(ContactRequest request) {
        Contact contact = new Contact();

        contact.setFirstName(request.getFirstName());
        contact.setLastName(request.getLastName());
        contact.setEmail(request.getEmail());
        contact.setPhone(request.getPhone());
        contact.setOwner(currentSubject());

        ContactGroup group = null;

        if (request.getGroupId() != null) {
            group = contactGroupRepository.findById(request.getGroupId());

            if (group == null) {

                throw new ApiException(
                        404,
                        "Not Found",
                        "Contact group not found with id: " + request.getGroupId()
                );
            }
        }

        contact.setGroup(group);

        contact.setCreatedAt(LocalDateTime.now());

        repository.persist(contact);

        return toResponse(contact);
    }

    @Transactional
    public ContactResponse update(Long id, ContactRequest request) {
        Contact contact = repository.findById(id);

        if (contact == null) {

            throw new ApiException(
                    404,
                    "Not Found",
                    "Contact not found with id: " + id
            );



        }

        requireOwnership(contact);

        contact.setFirstName(request.getFirstName());
        contact.setLastName(request.getLastName());
        contact.setEmail(request.getEmail());
        contact.setPhone(request.getPhone());


        ContactGroup group = null;

        if (request.getGroupId() != null) {
            group = contactGroupRepository.findById(request.getGroupId());

            if (group == null) {

                throw new ApiException(
                        404,
                        "Not Found",
                        "Contact group not found with id: " + request.getGroupId()
                );
            }
        }

        contact.setGroup(group);

        return toResponse(contact);
    }


    @Transactional
    public void delete(Long id) {
        Contact contact = repository.findById(id);
        if (contact == null) {
            throw new ApiException(404, "Not Found", "Contact not found with id: " + id);
        }
        requireOwnership(contact);
        repository.delete(contact);
    }


    public PagedResponse<ContactResponse> getByGroupId(Long groupId, int page, int size) {

        if (contactGroupRepository.findById(groupId) == null) {
            throw new ApiException(
                    404,
                    "Not Found",
                    "Contact group not found with id: " + groupId
            );
        }


        String subject = currentSubject();
        List<ContactResponse> items = repository.findPagedByGroupIdAndOwner(groupId, subject, page, size)
                .stream()
                .map(this::toResponse)
                .toList();
        return new PagedResponse<>(items, page, size, repository.countByGroupIdAndOwner(groupId, subject));
    }


    private String currentSubject() {
        String subject = token.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new NotAuthorizedException("Bearer");
        }
        return subject;
    }

    private void requireOwnership(Contact contact) {
        if (!currentSubject().equals(contact.getOwner())) {
            throw new ForbiddenException("Cannot access another owner's contact");
        }
    }

    private ContactResponse toResponse(Contact contact) {
        ContactResponse response = new ContactResponse();

        response.setId(contact.getId());
        response.setFirstName(contact.getFirstName());
        response.setLastName(contact.getLastName());
        response.setEmail(contact.getEmail());
        response.setPhone(contact.getPhone());
        response.setOwner(contact.getOwner());
        response.setCreatedAt(contact.getCreatedAt());
        response.setGroupId(
                contact.getGroup() != null
                        ? contact.getGroup().getId()
                        : null
        );

        return response;
    }


}
