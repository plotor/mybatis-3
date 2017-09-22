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

DROP TABLE IF EXISTS contact;
DROP TABLE IF EXISTS person;
CREATE TABLE person (
    id   INT,
    name VARCHAR(32),
    PRIMARY KEY (id)
);

INSERT INTO person (id, name) VALUES (1, 'John');
INSERT INTO person (id, name) VALUES (2, 'Rebecca');

CREATE TABLE contact (
    id        INT,
    address   VARCHAR(100),
    phone     VARCHAR(32),
    person_id INT,
    FOREIGN KEY (person_id) REFERENCES person (id)
);

INSERT INTO contact (id, address, phone, person_id)
VALUES (1, '123 St. Devel', '555-555-555', 1);
INSERT INTO contact (id, address, phone, person_id)
VALUES (2, '3 Wall Street', '111-111-111', 1);
INSERT INTO contact (id, address, phone, person_id)
VALUES (3, 'White House', '000-999-888', 2);

