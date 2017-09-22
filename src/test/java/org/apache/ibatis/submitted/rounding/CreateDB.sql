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
DROP TABLE users2 IF EXISTS;

CREATE TABLE users (
    id           INT,
    name         VARCHAR(20),
    funkyNumber  DECIMAL(38),
    roundingMode INT
);

INSERT INTO users (id, name, funkyNumber, roundingMode)
VALUES (1, 'User1', 123456789.9876543212345678987654321, 0);


CREATE TABLE users2 (
    id           INT,
    name         VARCHAR(20),
    funkyNumber  DECIMAL(38),
    roundingMode VARCHAR(12)
);

INSERT INTO users2 (id, name, funkyNumber, roundingMode)
VALUES (1, 'User1', 123456789.9876543212345678987654321, 'UP');

