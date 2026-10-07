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
import jakarta.transaction.Transactional;
import com.unifonic.contacts.entity.ContactGroup;
import java.time.LocalDateTime;
import java.util.List;

@ApplicationScoped
public class ContactService {


    @Inject
    ContactRepository repository;

    @Inject
    ContactGroupRepository contactGroupRepository;




    public PagedResponse<ContactResponse> getAll(
            int page,
            int size,
            String owner) {

        List<Contact> contacts;
        long total;

        if (owner != null && !owner.isBlank()) {
            contacts = repository.findPagedByOwner(owner, page, size);
            total = repository.countByOwner(owner);
        } else {
            contacts = repository.findPaged(page, size);
            total = repository.countAll();
        }

        List<ContactResponse> items = contacts.stream()
                .map(this::toResponse)
                .toList();

        return new PagedResponse<>(items, page, size, total);
    }


    public ContactResponse getById(Long id) {
        Contact contact = repository.findById(id);

        if (contact == null) {
//            throw new ApiExceptionMapper(
//                    "Contact not found with id: " + id
//            );
            throw new ApiException(
                    404,
                    "Not Found",
                    "Contact group not found with id: " + id
            );

        }

        return toResponse(contact);
    }

    @Transactional
    public ContactResponse create(ContactRequest request) {
        Contact contact = new Contact();

        contact.setFirstName(request.getFirstName());
        contact.setLastName(request.getLastName());
        contact.setEmail(request.getEmail());
        contact.setPhone(request.getPhone());
        contact.setOwner(request.getOwner());
       // contact.setGroup(request.getGroupId());

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
                    "Contact group not found with id: " + request.getGroupId()
            );



        }

        contact.setFirstName(request.getFirstName());
        contact.setLastName(request.getLastName());
        contact.setEmail(request.getEmail());
        contact.setPhone(request.getPhone());
        contact.setOwner(request.getOwner());


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
        boolean deleted = repository.deleteById(id);

        if (!deleted) {
            throw new ApiException(
                    404,
                    "Not Found",
                    "Contact group not found with id: " + id
            );
        }
    }


    public List<ContactResponse> getByGroupId(Long groupId) {

        if (contactGroupRepository.findById(groupId) == null) {
            throw new ApiException(
                    404,
                    "Not Found",
                    "Contact group not found with id: " + groupId
            );
        }


        return repository.findByGroupId(groupId)
                .stream()
                .map(this::toResponse)
                .toList();
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