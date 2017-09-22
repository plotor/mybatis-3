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

CREATE TABLE users (
    id       INT,
    name     VARCHAR(20),
    group_id INT,
    rol_id   INT
);

INSERT INTO users VALUES (1, 'User1', 1, 1);
INSERT INTO users VALUES (1, 'User1', 1, 2);
INSERT INTO users VALUES (1, 'User1', 2, 1);
INSERT INTO users VALUES (1, 'User1', 2, 2);
INSERT INTO users VALUES (1, 'User1', 2, 3);
INSERT INTO users VALUES (2, 'User2', 1, 1);
INSERT INTO users VALUES (2, 'User2', 1, 2);
INSERT INTO users VALUES (2, 'User2', 1, 3);
INSERT INTO users VALUES (3, 'User3', 1, 1);
INSERT INTO users VALUES (3, 'User3', 2, 1);
INSERT INTO users VALUES (3, 'User3', 3, 1);
INSERT INTO users VALUES (4, 'User4', 1, 1);
INSERT INTO users VALUES (4, 'User4', 1, 2);
INSERT INTO users VALUES (4, 'User4', 2, 1);
INSERT INTO users VALUES (4, 'User4', 2, 2);