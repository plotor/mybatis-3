--
--    Copyright 2009-2013 the original author or authors.
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

CREATE TABLE Parent (
    id      INT,
    Name    VARCHAR(20),
    SurName VARCHAR(20)
);

CREATE TABLE Child (
    id       INT,
    Name     VARCHAR(20),
    SurName  VARCHAR(20),
    Age      INT,
    ParentId VARCHAR(20)
);

INSERT INTO Parent VALUES (1, 'Jose', 'Garcia');
INSERT INTO Parent VALUES (2, 'Juan', 'Perez');

INSERT INTO Child VALUES (1, 'Ana', 'Garcia', 1, 1);
INSERT INTO Child VALUES (2, 'Rosa', 'Garcia', 4, 1);

