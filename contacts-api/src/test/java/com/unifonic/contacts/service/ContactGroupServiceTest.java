package com.unifonic.contacts.service;

import com.unifonic.contacts.dto.ContactGroupRequest;
import com.unifonic.contacts.entity.ContactGroup;
import com.unifonic.contacts.exception.ApiException;
import com.unifonic.contacts.repository.ContactGroupRepository;
import org.junit.jupiter.api.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContactGroupServiceTest {
    ContactGroupService service;
    ContactGroupRepository repository;
    ContactGroup existing;
    ContactGroupRequest input;
    @BeforeEach void setup() {
        repository=mock(ContactGroupRepository.class); service=new ContactGroupService(); service.repository=repository;
        existing=new ContactGroup(); existing.setId(7L); existing.setName("Original"); existing.setDescription("Old description");
        when(repository.findById(7L)).thenReturn(existing);
        input=new ContactGroupRequest(); input.setName("Updated"); input.setDescription("New description");
    }
    @Test void createMapsFieldsAndAssignsTimestamp() {
        var result=service.create(input); var capture=org.mockito.ArgumentCaptor.forClass(ContactGroup.class); verify(repository).persist(capture.capture());
        assertEquals("Updated",result.getName()); assertEquals("New description",result.getDescription()); assertNotNull(capture.getValue().getCreatedAt());
    }
    @Test void readMapsExistingGroup() { assertEquals("Original",service.getById(7L).getName()); }
    @Test void updateMutatesExistingGroup() { var result=service.update(7L,input); assertEquals("Updated",result.getName()); assertEquals("New description",existing.getDescription()); assertEquals(7L,result.getId()); }
    @Test void deleteRemovesExistingGroup() { when(repository.deleteById(7L)).thenReturn(true); service.delete(7L); verify(repository).deleteById(7L); }
    @Test void missingReadReturns404() { assertEquals(404,assertThrows(ApiException.class,()->service.getById(404L)).getStatus()); }
    @Test void missingUpdateReturns404() { assertEquals(404,assertThrows(ApiException.class,()->service.update(404L,input)).getStatus()); }
    @Test void missingDeleteReturns404() { assertEquals(404,assertThrows(ApiException.class,()->service.delete(404L)).getStatus()); }
    @Test void unfilteredListUsesPagedRowsAndTotal() {
        when(repository.findPaged(1,2)).thenReturn(List.of(existing)); when(repository.countAll()).thenReturn(6L);
        var page=service.getAll(1,2,null); assertEquals(6,page.getTotal()); assertEquals(1,page.getPage()); assertEquals(2,page.getSize()); assertEquals(7L,page.getItems().getFirst().getId());
    }
    @Test void filteredListUsesSameNameForRowsAndCount() {
        when(repository.findPagedByName("Original",0,3)).thenReturn(List.of(existing)); when(repository.countByName("Original")).thenReturn(1L);
        var page=service.getAll(0,3,"Original"); assertEquals(1,page.getTotal()); assertEquals("Original",page.getItems().getFirst().getName()); verify(repository).countByName("Original");
    }
    @Test void duplicateGroupPersistenceFailureIsPropagatedForConflictMapping() {
        var duplicate=new org.hibernate.exception.ConstraintViolationException("duplicate",new java.sql.SQLException("unique","23505"),"groups_name");
        doThrow(duplicate).when(repository).persist(any(ContactGroup.class));
        assertSame(duplicate,assertThrows(org.hibernate.exception.ConstraintViolationException.class,()->service.create(input)));
    }
}
