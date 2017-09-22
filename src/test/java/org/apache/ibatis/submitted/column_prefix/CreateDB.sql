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
    id                  INT,
    name                VARCHAR(32),
    billing_address_id  INT,
    shipping_address_id INT,
    room_id             INT
);

DROP TABLE IF EXISTS address;
CREATE TABLE address (
    id        INT,
    state     VARCHAR(32),
    city      VARCHAR(32),
    phone1_id INT,
    phone2_id INT,
    addr_type INT,
    caution   VARCHAR(64)
);

DROP TABLE IF EXISTS zip;
CREATE TABLE zip (
    state    VARCHAR(32),
    city     VARCHAR(32),
    zip_code INT
);

DROP TABLE IF EXISTS phone;
CREATE TABLE phone (
    id        INT,
    phone     VARCHAR(32),
    area_code VARCHAR(2)
);

DROP TABLE IF EXISTS pet;
CREATE TABLE pet (
    id       INT,
    owner_id INT,
    name     VARCHAR(32),
    room_id  INT
);

DROP TABLE IF EXISTS state_bird;
CREATE TABLE state_bird (
    state VARCHAR(32),
    bird  VARCHAR(32)
);

DROP TABLE IF EXISTS room;
CREATE TABLE room (
    room_id   INT,
    room_name VARCHAR(32)
);

DROP TABLE IF EXISTS brand;
CREATE TABLE brand (
    id   INT,
    name VARCHAR(32)
);

-- make columns case sensitive
DROP TABLE IF EXISTS product;
CREATE TABLE product (
    "product_id"   INT,
    "product_name" VARCHAR(32),
    brand_id       INT
);

INSERT INTO room (room_id, room_name) VALUES (31, 'Sakura');
INSERT INTO room (room_id, room_name) VALUES (32, 'Ume');
INSERT INTO room (room_id, room_name) VALUES (33, 'Tsubaki');

INSERT INTO pet (id, owner_id, name, room_id) VALUES (100, 1, 'Kotetsu', 32);
INSERT INTO pet (id, owner_id, name, room_id) VALUES (101, 1, 'Chien', NULL);
INSERT INTO pet (id, owner_id, name, room_id) VALUES (102, 3, 'Dodo', 31);

INSERT INTO phone (id, phone, area_code) VALUES (1000, '0123', '11');
INSERT INTO phone (id, phone, area_code) VALUES (1001, '4567', '33');
INSERT INTO phone (id, phone, area_code) VALUES (1002, '8888', '55');
INSERT INTO phone (id, phone, area_code) VALUES (1003, '9999', '77');

INSERT INTO state_bird (state, bird) VALUES ('IL', 'Cardinal');
INSERT INTO state_bird (state, bird) VALUES ('CA', 'California Valley Quail');
INSERT INTO state_bird (state, bird) VALUES ('TX', 'Mockingbird');

INSERT INTO zip (state, city, zip_code) VALUES ('IL', 'Chicago', 81);
INSERT INTO zip (state, city, zip_code) VALUES ('CA', 'San Francisco', 82);
INSERT INTO zip (state, city, zip_code) VALUES ('CA', 'Los Angeles', 83);
INSERT INTO zip (state, city, zip_code) VALUES ('TX', 'Dallas', 84);

INSERT INTO address (id, state, city, phone1_id, phone2_id, addr_type, caution) VALUES (10, 'IL', 'Chicago', 1000, 1001, 0, NULL);
INSERT INTO address (id, state, city, phone1_id, phone2_id, addr_type, caution) VALUES (11, 'CA', 'San Francisco', 1002, NULL, 1, 'Has a big dog.');
INSERT INTO address (id, state, city, phone1_id, phone2_id, addr_type, caution) VALUES (12, 'CA', 'Los Angeles', NULL, NULL, 1, 'No door bell.');
INSERT INTO address (id, state, city, phone1_id, phone2_id, addr_type, caution) VALUES (13, 'TX', 'Dallas', 1003, 1001, 0, NULL);

INSERT INTO person (id, name, billing_address_id, shipping_address_id, room_id) VALUES (1, 'John', 10, 11, 33);
INSERT INTO person (id, name, billing_address_id, shipping_address_id, room_id) VALUES (2, 'Rebecca', 12, NULL, NULL);
INSERT INTO person (id, name, billing_address_id, shipping_address_id, room_id) VALUES (3, 'Keith', NULL, 13, NULL);

INSERT INTO brand (id, name) VALUES (1, 'alpha');

INSERT INTO product ("product_id", "product_name", brand_id) VALUES (10, 'alpha', 1);
INSERT INTO product ("product_id", "product_name", brand_id) VALUES (20, 'beta', 1);
