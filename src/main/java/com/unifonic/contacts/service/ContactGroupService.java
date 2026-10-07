package com.unifonic.contacts.service;

import com.unifonic.contacts.dto.PagedResponse;
import com.unifonic.contacts.exception.ApiException;
import com.unifonic.contacts.repository.ContactGroupRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import com.unifonic.contacts.dto.ContactGroupRequest;
import com.unifonic.contacts.dto.ContactGroupResponse;
import com.unifonic.contacts.entity.ContactGroup;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@ApplicationScoped
public class ContactGroupService {

    @Inject
    ContactGroupRepository repository;


    public PagedResponse<ContactGroupResponse> getAll(
            int page,
            int size,
            String name) {

        List<ContactGroup> groups;
        long total;

        if (name != null && !name.isBlank()) {
            groups = repository.findPagedByName(name, page, size);
            total = repository.countByName(name);
        } else {
            groups = repository.findPaged(page, size);
            total = repository.countAll();
        }

        List<ContactGroupResponse> items = groups.stream()
                .map(this::toResponse)
                .toList();

        return new PagedResponse<>(items, page, size, total);
    }


    public ContactGroupResponse getById(Long id) {
        ContactGroup group = repository.findById(id);

        if (group == null) {

            throw new ApiException(
                    404,
                    "Not Found",
                    "Contact group not found with id: " + id
            );
        }

        return toResponse(group);
    }

    @Transactional
    public ContactGroupResponse create(ContactGroupRequest request) {
        ContactGroup group = new ContactGroup();

        group.setName(request.getName());
        group.setDescription(request.getDescription());
        group.setCreatedAt(LocalDateTime.now());

        repository.persist(group);

        return toResponse(group);
    }

    @Transactional
    public ContactGroupResponse update(Long id, ContactGroupRequest request) {
        ContactGroup group = repository.findById(id);

        if (group == null) {

            throw new ApiException(
                    404,
                    "Not Found",
                    "Contact not found with id: " + id
            );
        }

        group.setName(request.getName());
        group.setDescription(request.getDescription());

        return toResponse(group);
    }


    @Transactional
    public void delete(Long id) {
        boolean deleted = repository.deleteById(id);

        if (!deleted) {

            throw new ApiException(
                    404,
                    "Not Found",
                    "Contact not found with id: " + id
            );
        }
    }


    private ContactGroupResponse toResponse(ContactGroup group) {
        ContactGroupResponse response = new ContactGroupResponse();

        response.setId(group.getId());
        response.setName(group.getName());
        response.setDescription(group.getDescription());
        response.setCreatedAt(group.getCreatedAt());

        return response;
    }
}