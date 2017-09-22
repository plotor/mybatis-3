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

DROP TABLE IF EXISTS person;
CREATE TABLE person (
    id            INT,
    nr_department INT
);

INSERT INTO person (id, nr_department)
VALUES (1, 1);

DROP TABLE IF EXISTS productattribute;
CREATE TABLE productattribute (
    nr_id INT
);

INSERT INTO productattribute (nr_id)
VALUES (1);

DROP TABLE IF EXISTS department;
CREATE TABLE department (
    nr_id        INT,
    nr_attribute INT,
    person       INT
);

INSERT INTO department (nr_id, nr_attribute, person)
VALUES (1, 1, 1);

