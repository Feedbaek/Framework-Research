CREATE TABLE users (
    id   BIGINT PRIMARY KEY,
    name VARCHAR(50) NOT NULL
);

INSERT INTO users (id, name) VALUES (1, 'kim'), (2, 'lee'), (3, 'park');
