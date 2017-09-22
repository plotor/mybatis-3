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
    person_id   INT,
    person_name VARCHAR(32)
);

DROP TABLE IF EXISTS pet;
CREATE TABLE pet (
    pet_id   INT,
    owner_id INT,
    pet_name VARCHAR(32)
);

INSERT INTO person (person_id, person_name) VALUES (1, 'John');
INSERT INTO person (person_id, person_name) VALUES (2, 'Rebecca');

INSERT INTO pet (pet_id, owner_id, pet_name) VALUES (1, 1, 'Kotetsu');
INSERT INTO pet (pet_id, owner_id, pet_name) VALUES (2, 1, 'Chien');
INSERT INTO pet (pet_id, owner_id, pet_name) VALUES (3, 2, 'Ren');
