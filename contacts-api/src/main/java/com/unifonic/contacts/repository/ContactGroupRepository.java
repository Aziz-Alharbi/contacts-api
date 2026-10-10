package com.unifonic.contacts.repository;

import com.unifonic.contacts.entity.ContactGroup;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class ContactGroupRepository implements PanacheRepository<ContactGroup> {

    public List<ContactGroup> findPaged(int page, int size) {
        return findAll()
                .page(page, size)
                .list();
    }

    public long countAll() {
        return count();
    }

    public List<ContactGroup> findPagedByName(
            String name,
            int page,
            int size) {

        return find("name", name)
                .page(page, size)
                .list();
    }

    public long countByName(String name) {
        return count("name", name);
    }


}