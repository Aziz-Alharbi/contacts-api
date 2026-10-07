CREATE TABLE contacts (
                          id BIGINT NOT NULL AUTO_INCREMENT,
                          first_name VARCHAR(100) NOT NULL,
                          last_name VARCHAR(100) NOT NULL,
                          email VARCHAR(255) NOT NULL,
                          phone VARCHAR(50),
                          owner VARCHAR(100) NOT NULL,
                          group_id BIGINT,
                          created_at TIMESTAMP NOT NULL,

                          PRIMARY KEY (id),
                          CONSTRAINT uk_contacts_email UNIQUE (email),
                          CONSTRAINT fk_contacts_group
                              FOREIGN KEY (group_id)
                                  REFERENCES contact_groups(id)
);