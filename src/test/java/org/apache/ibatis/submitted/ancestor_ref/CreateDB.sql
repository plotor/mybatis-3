--
--    Copyright 2009-2016 the original author or authors.
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
DROP TABLE friend IF EXISTS;

CREATE TABLE users (
    id   INT,
    name VARCHAR(20)
);

CREATE TABLE friend (
    user_id   INT,
    friend_id INT
);

INSERT INTO users (id, name) VALUES
    (1, 'User1'), (2, 'User2'), (3, 'User3');

INSERT INTO friend (user_id, friend_id) VALUES
    (1, 2), (2, 2), (2, 3);

DROP TABLE blog IF EXISTS;
DROP TABLE author IF EXISTS;

CREATE TABLE blog (
    id           INT,
    title        VARCHAR(16),
    author_id    INT,
    co_author_id INT
);

CREATE TABLE author (
    id         INT,
    name       VARCHAR(16),
    reputation INT
);

INSERT INTO blog (id, title, author_id, co_author_id) VALUES
    (1, 'Blog1', 1, 2), (2, 'Blog2', 2, 3);

INSERT INTO author (id, name, reputation) VALUES
    (1, 'Author1', 1), (2, 'Author2', 2), (3, 'Author3', 3);
