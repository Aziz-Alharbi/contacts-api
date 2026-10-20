package com.unifonic.contacts.events;

import jakarta.persistence.*;

/** Serializes relay batches across API instances. */
@Entity
@Table(name="contact_event_relay_lock")
public class OutboxRelayLock {
    @Id public Integer id;
}
