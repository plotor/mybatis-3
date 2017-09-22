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

DROP TABLE cars IF EXISTS;

CREATE TABLE cars (
    carid           INTEGER,
    cartype         VARCHAR(20),
    enginetype      VARCHAR(20),
    enginecylinders INTEGER,
    brakestype      VARCHAR(20)
);

INSERT INTO cars (carid, cartype, enginetype, enginecylinders, brakestype) VALUES (1, 'VW', 'Diesel', 4, NULL);
INSERT INTO cars (carid, cartype, enginetype, enginecylinders, brakestype) VALUES (2, 'Opel', NULL, NULL, 'drum');
INSERT INTO cars (carid, cartype, enginetype, enginecylinders, brakestype) VALUES (3, 'Audi', 'Diesel', 4, 'disk');
INSERT INTO cars (carid, cartype, enginetype, enginecylinders, brakestype) VALUES (4, 'Ford', 'Gas', 8, 'drum');
