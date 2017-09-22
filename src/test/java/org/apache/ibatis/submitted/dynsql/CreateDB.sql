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

CREATE SCHEMA ibtest authorization dba;

CREATE TABLE ibtest.names (
    id          INT,
    description VARCHAR(20)
);

INSERT INTO ibtest.names (id, description) VALUES (1, 'Fred');
INSERT INTO ibtest.names (id, description) VALUES (2, 'Wilma');
INSERT INTO ibtest.names (id, description) VALUES (3, 'Pebbles');
INSERT INTO ibtest.names (id, description) VALUES (4, 'Barney');
INSERT INTO ibtest.names (id, description) VALUES (5, 'Betty');
INSERT INTO ibtest.names (id, description) VALUES (6, 'Bamm Bamm');
INSERT INTO ibtest.names (id, description) VALUES (7, 'Rock ''n Roll');

CREATE TABLE ibtest.numerics (
    id            INT,
    tinynumber    TINYINT,
    smallnumber   SMALLINT,
    longinteger   BIGINT,
    biginteger    BIGINT,
    numericnumber NUMERIC(10, 2),
    decimalnumber DECIMAL(10, 2),
    realnumber    REAL,
    floatnumber   FLOAT,
    doublenumber  DOUBLE
);

INSERT INTO ibtest.numerics VALUES (1, 2, 3, 4, 5, 6, 7, 8, 9, 10);