package com.unifonic.contacts.repository;

import com.unifonic.contacts.entity.Contact;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class ContactRepository implements PanacheRepository<Contact> {
    public List<Contact> findPaged(int page, int size) {
        return find("order by id").page(page, size).list();
    }
    public List<Contact> findPagedByGroupId(Long groupId, int page, int size) {
        return find("group.id = ?1 order by id", groupId).page(page, size).list();
    }
    public long countByGroupId(Long groupId) { return count("group.id", groupId); }


    public List<Contact> findPagedByGroupIdAndOwner(Long groupId, String owner, int page, int size) {
        return find("group.id = ?1 and owner = ?2 order by id", groupId, owner).page(page, size).list();
    }

    public long countByGroupIdAndOwner(Long groupId, String owner) {
        return count("group.id = ?1 and owner = ?2", groupId, owner);
    }

    public List<Contact> findPagedByOwner(
            String owner,
            int page,
            int size) {

        return find("owner = ?1 order by id", owner)
                .page(page, size)
                .list();
    }

    public long countByOwner(String owner) {
        return count("owner", owner);
    }

}
