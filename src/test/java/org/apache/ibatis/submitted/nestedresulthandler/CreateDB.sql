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

DROP TABLE persons IF EXISTS;
DROP TABLE items IF EXISTS;

CREATE TABLE persons (
    id   INT,
    name VARCHAR(20)
);

CREATE TABLE items (
    id    INT,
    owner INT,
    name  VARCHAR(20)
);

INSERT INTO persons (id, name) VALUES (1, 'grandma');
INSERT INTO persons (id, name) VALUES (2, 'sister');
INSERT INTO persons (id, name) VALUES (3, 'brother');

INSERT INTO items (id, owner, name) VALUES (1, 1, 'book');
INSERT INTO items (id, owner, name) VALUES (2, 1, 'tv');
INSERT INTO items (id, owner, name) VALUES (3, 2, 'shoes');
INSERT INTO items (id, owner, name) VALUES (4, 3, 'car');
INSERT INTO items (id, owner, name) VALUES (5, 2, 'phone');
