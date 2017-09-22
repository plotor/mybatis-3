--
--    Copyright 2009-2015 the original author or authors.
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

DROP TABLE table1 IF EXISTS;

CREATE TABLE table1 (
    id    INT,
    a     VARCHAR(20),
    col_a VARCHAR(20),
    col_b VARCHAR(20),
    col_c VARCHAR(20)
);

INSERT INTO table1 (id, a, col_a, col_b, col_c) VALUES (1, 'a value', 'col_a value', 'col_b value', 'col_c value');
