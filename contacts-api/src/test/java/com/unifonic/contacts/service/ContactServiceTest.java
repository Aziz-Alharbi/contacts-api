package com.unifonic.contacts.service;

import com.unifonic.contacts.dto.ContactRequest;
import com.unifonic.contacts.entity.*;
import com.unifonic.contacts.exception.ApiException;
import com.unifonic.contacts.repository.*;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContactServiceTest {
    ContactService service;
    ContactRepository contacts;
    ContactGroupRepository groups;
    JsonWebToken token;
    Contact existing;
    ContactRequest input;
    @BeforeEach void setup() {
        contacts=mock(ContactRepository.class); groups=mock(ContactGroupRepository.class); token=mock(JsonWebToken.class);
        service=new ContactService(); service.events=mock(com.unifonic.contacts.events.ContactEventOutbox.class); service.repository=contacts; service.contactGroupRepository=groups; service.token=token; service.identity=mock(io.quarkus.security.identity.SecurityIdentity.class);
        when(token.getSubject()).thenReturn("alice");
        existing=new Contact(); existing.setId(17L); existing.setOwner("alice"); existing.setFirstName("Old"); existing.setLastName("Name"); existing.setEmail("old@example.test");
        when(contacts.findById(17L)).thenReturn(existing);
        input=new ContactRequest(); input.setFirstName("New"); input.setLastName("Name"); input.setEmail("new@example.test"); input.setPhone("123");
    }
    @Test void createAssignsVerifiedSubjectAndMapsPersistedFields() {
        var captured=org.mockito.ArgumentCaptor.forClass(Contact.class);
        var result=service.create(input);
        verify(contacts).persist(captured.capture());
        assertEquals("alice",captured.getValue().getOwner());
        assertNotNull(captured.getValue().getCreatedAt());
        assertEquals("New",result.getFirstName()); assertEquals("new@example.test",result.getEmail()); assertEquals("123",result.getPhone());
    }
    @Test void createAssociatesExistingGroup() {
        ContactGroup group=new ContactGroup(); group.setId(9L); input.setGroupId(9L); when(groups.findById(9L)).thenReturn(group);
        assertEquals(9L,service.create(input).getGroupId());
    }
    @Test void createRejectsMissingGroupBeforePersistence() {
        input.setGroupId(9L);
        ApiException error=assertThrows(ApiException.class,()->service.create(input));
        assertEquals(404,error.getStatus()); assertTrue(error.getMessage().contains("9")); verifyNoInteractions(contacts);
    }
    @Test void duplicatePersistenceFailureIsPropagatedForConflictMapping() {
        var duplicate=new org.hibernate.exception.ConstraintViolationException("duplicate",new java.sql.SQLException("unique","23505"),"contacts_email");
        doThrow(duplicate).when(contacts).persist(any(Contact.class));
        assertSame(duplicate,assertThrows(org.hibernate.exception.ConstraintViolationException.class,()->service.create(input)));
    }
    @Test void readReturnsOwnedContact() { assertEquals(17L,service.getById(17L).getId()); }
    @Test void updateChangesFieldsButPreservesOwnerAndRemovesOldGroup() {
        existing.setGroup(new ContactGroup());
        var result=service.update(17L,input);
        assertEquals("New",result.getFirstName()); assertEquals("alice",result.getOwner()); assertEquals("new@example.test",existing.getEmail()); assertNull(existing.getGroup());
    }
    @Test void updateAssociatesRequestedGroup() {
        ContactGroup group=new ContactGroup(); group.setId(9L); input.setGroupId(9L); when(groups.findById(9L)).thenReturn(group);
        assertEquals(9L,service.update(17L,input).getGroupId());
    }
    @Test void updateRejectsMissingGroup() {
        input.setGroupId(9L); assertEquals(404,assertThrows(ApiException.class,()->service.update(17L,input)).getStatus());
    }
    @Test void deleteRemovesOwnedContact() { service.delete(17L); verify(contacts).delete(existing); }
    @Test void missingReadReturns404() { assertEquals(404,assertThrows(ApiException.class,()->service.getById(404L)).getStatus()); }
    @Test void missingUpdateReportsContactId() {
        input.setGroupId(9L);
        var error=assertThrows(ApiException.class,()->service.update(404L,input));
        assertEquals(404,error.getStatus()); assertEquals("Contact not found with id: 404",error.getMessage());
    }
    @Test void missingDeleteReturns404() { assertEquals(404,assertThrows(ApiException.class,()->service.delete(404L)).getStatus()); }
    @Test void foreignReadIsForbidden() { existing.setOwner("bob"); assertThrows(ForbiddenException.class,()->service.getById(17L)); }
    @Test void foreignUpdateDoesNotMutateRecord() {
        existing.setOwner("bob"); assertThrows(ForbiddenException.class,()->service.update(17L,input)); assertEquals("Old",existing.getFirstName());
    }
    @Test void administratorDeleteRemovesForeignContact() {
        existing.setOwner("bob"); service.delete(17L); verify(contacts).delete(existing);
    }
    @ParameterizedTest @ValueSource(strings={"", "alice"})
    void listPassesSubjectAndPaginationToRepository(String owner) {
        when(contacts.findPagedByOwner("alice",2,3)).thenReturn(List.of(existing)); when(contacts.countByOwner("alice")).thenReturn(8L);
        var page=service.getAll(2,3,owner);
        assertEquals(8,page.getTotal()); assertEquals(2,page.getPage()); assertEquals(3,page.getSize()); assertEquals(17L,page.getItems().getFirst().getId());
        verify(contacts).findPagedByOwner("alice",2,3);
    }
    @Test void foreignOwnerQueryNeverReadsRepository() {
        assertThrows(ForbiddenException.class,()->service.getAll(0,10,"bob")); verify(contacts,never()).findPagedByOwner(anyString(),anyInt(),anyInt());
    }
    @Test void absentSubjectCannotListContacts() { when(token.getSubject()).thenReturn(null); assertThrows(NotAuthorizedException.class,()->service.getAll(0,10,null)); }
    @Test void groupedListFiltersAndCountsBySubject() {
        when(groups.findById(9L)).thenReturn(new ContactGroup()); when(contacts.findPagedByGroupIdAndOwner(9L,"alice",1,2)).thenReturn(List.of(existing)); when(contacts.countByGroupIdAndOwner(9L,"alice")).thenReturn(5L);
        var page=service.getByGroupId(9L,1,2); assertEquals(5,page.getTotal()); assertEquals(17L,page.getItems().getFirst().getId()); verify(contacts).findPagedByGroupIdAndOwner(9L,"alice",1,2);
    }
    @Test void groupedListRejectsMissingGroup() { assertEquals(404,assertThrows(ApiException.class,()->service.getByGroupId(404L,0,10)).getStatus()); }
}
