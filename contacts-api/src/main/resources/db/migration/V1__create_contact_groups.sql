CREATE TABLE contact_groups (
                                id BIGINT NOT NULL AUTO_INCREMENT,
                                name VARCHAR(100) NOT NULL,
                                description VARCHAR(500),
                                created_at TIMESTAMP NOT NULL,
                                PRIMARY KEY (id),
                                CONSTRAINT uk_contact_groups_name UNIQUE (name)
);