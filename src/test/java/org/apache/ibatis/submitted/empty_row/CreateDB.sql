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

DROP TABLE parent IF EXISTS;
DROP TABLE child IF EXISTS;
DROP TABLE pet IF EXISTS;

CREATE TABLE parent (
    id   INT,
    col1 VARCHAR(20),
    col2 VARCHAR(20)
);

CREATE TABLE child (
    id        INT,
    parent_id INT,
    name      VARCHAR(20)
);

CREATE TABLE pet (
    id        INT,
    parent_id INT,
    name      VARCHAR(20)
);

INSERT INTO parent (id, col1, col2) VALUES
    (1, NULL, NULL),
    (2, NULL, NULL);

INSERT INTO child (id, parent_id, name) VALUES
    (1, 2, 'john'),
    (2, 2, 'mary');
