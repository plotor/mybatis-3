--
--    Copyright 2009-2017 the original author or authors.
--
--    Licensed under the Apache License, Version 2.0 (the "License");
--    you may not use this file except in compliance with the License.
--    You may obtain a copy of the License at
--
--       http://www.apache.org/licenses/LICENSE-2.0
--
--    Unless required by applicable law or agreed to in writing, software
--    distributed under the License is distributed on an "AS IS" BASIS,
--    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
--    See the License for the specific language governing permissions and
--    limitations under the License.
--

DROP TABLE users IF EXISTS;

DROP TABLE books IF EXISTS;

CREATE TABLE users (
    id           INT,
    name         VARCHAR(20),
    phone        VARCHAR(20),
    phone_number BIGINT
);

CREATE TABLE books (
    version INT,
    name    VARCHAR(20)
);

CREATE TABLE pets (
    id      INT,
    owner   INT,
    breeder INT,
    name    VARCHAR(20)
);

CREATE TABLE breeder (
    id   INT,
    name VARCHAR(20)
);

-- '+86 12345678901' can't be converted to a number
INSERT INTO users (id, name, phone, phone_number) VALUES (1, 'User1', '+86 12345678901', 12345678901);
INSERT INTO users (id, name, phone, phone_number) VALUES (2, 'User2', '+86 12345678902', 12345678902);

INSERT INTO books (version, name) VALUES (99, 'Learn Java');

INSERT INTO pets (id, owner, breeder, name) VALUES (11, 1, NULL, 'Ren');
INSERT INTO pets (id, owner, breeder, name) VALUES (12, 2, 101, 'Chien');
INSERT INTO pets (id, owner, breeder, name) VALUES (13, 2, NULL, 'Kotetsu');

INSERT INTO breeder (id, name) VALUES (101, 'John');
