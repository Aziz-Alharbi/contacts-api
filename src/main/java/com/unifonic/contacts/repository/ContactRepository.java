package com.unifonic.contacts.repository;

import com.unifonic.contacts.entity.Contact;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class ContactRepository implements PanacheRepository<Contact> {

    public List<Contact> findPagedByGroupId(Long groupId, int page, int size) {
        return find("group.id", groupId).page(page, size).list();
    }

    public long countByGroupId(Long groupId) {
        return count("group.id", groupId);
    }

    public List<Contact> findPaged(int page, int size) {
        return findAll()
                .page(page, size)
                .list();
    }

    public long countAll() {
        return count();
    }

    public List<Contact> findPagedByOwner(
            String owner,
            int page,
            int size) {

        return find("owner", owner)
                .page(page, size)
                .list();
    }

    public long countByOwner(String owner) {
        return count("owner", owner);
    }

}
