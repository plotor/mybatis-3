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

DROP TABLE product;

CREATE TABLE product (
    productid VARCHAR(10)  NOT NULL,
    category  VARCHAR(10)  NOT NULL,
    name      VARCHAR(80)  NULL,
    descn     VARCHAR(255) NULL,
    CONSTRAINT pk_product PRIMARY KEY (productid),
    CONSTRAINT fk_product_1 FOREIGN KEY (category)
    REFERENCES category (catid)
)

