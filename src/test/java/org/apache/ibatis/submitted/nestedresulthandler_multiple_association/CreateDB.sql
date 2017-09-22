--
--    Copyright 2009-2014 the original author or authors.
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
CREATE TABLE parent (
    id    INTEGER,
    value VARCHAR(20)
);

CREATE TABLE child (
    id    INTEGER,
    value VARCHAR(20)
);

CREATE TABLE parent_child (
    idparent     INTEGER,
    idchild_from INTEGER,
    idchild_to   INTEGER
);

INSERT INTO parent (id, value) VALUES (1, 'parent1');
INSERT INTO parent (id, value) VALUES (2, 'parent2');

INSERT INTO child (id, value) VALUES (1, 'child1');
INSERT INTO child (id, value) VALUES (2, 'child2');
INSERT INTO child (id, value) VALUES (3, 'child3');
INSERT INTO child (id, value) VALUES (4, 'child4');

INSERT INTO parent_child (idparent, idchild_from, idchild_to) VALUES (1, 1, 2);
INSERT INTO parent_child (idparent, idchild_from, idchild_to) VALUES (2, 2, 3);
INSERT INTO parent_child (idparent, idchild_from, idchild_to) VALUES (2, 1, 2);