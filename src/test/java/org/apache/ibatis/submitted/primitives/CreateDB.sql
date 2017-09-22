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

DROP TABLE a IF EXISTS;
DROP TABLE b IF EXISTS;

CREATE TABLE a (
    id   INT,
    name VARCHAR(20)
);

CREATE TABLE b (
    ref   INT,
    entry INT
);

INSERT INTO a (id, name) VALUES (0, 'some');
INSERT INTO a (id, name) VALUES (1, 'other');

INSERT INTO b (ref, entry) VALUES (0, 1);
INSERT INTO b (ref, entry) VALUES (1, 2);
INSERT INTO b (ref, entry) VALUES (0, 3);

