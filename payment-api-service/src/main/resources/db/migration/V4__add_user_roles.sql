CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    PRIMARY KEY (user_id, role)
);

-- back-fill anyone who signed up before this migration
INSERT INTO user_roles (user_id, role) SELECT id, 'USER' from users;

-- normalizes email casing in Kotlin; bring existing rows in line
UPDATE users SET email = LOWER(email);