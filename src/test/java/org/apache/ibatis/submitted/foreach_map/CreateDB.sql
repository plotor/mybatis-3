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

DROP TABLE string_string IF EXISTS;

CREATE TABLE string_string (
    id identity,
    KEY varchar(255),
    value VARCHAR(255)
);

DROP TABLE int_bool IF EXISTS;

CREATE TABLE int_bool (
    id identity,
    KEY INTEGER,
    value BOOLEAN
);

DROP TABLE nested_bean IF EXISTS;

CREATE TABLE nested_bean (
    id identity,
    keya   INTEGER,
    keyb   BOOLEAN,
    valuea INTEGER,
    valueb BOOLEAN
);

DROP TABLE key_cols IF EXISTS;

CREATE TABLE key_cols (
    id identity,
    col_a INTEGER,
    col_b INTEGER
);

INSERT INTO key_cols (id, col_a, col_b) VALUES (1, 11, 222);
INSERT INTO key_cols (id, col_a, col_b) VALUES (2, 22, 222);
INSERT INTO key_cols (id, col_a, col_b) VALUES (3, 22, 333);
