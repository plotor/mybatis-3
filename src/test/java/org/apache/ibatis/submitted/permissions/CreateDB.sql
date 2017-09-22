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

DROP TABLE permissions IF EXISTS;

CREATE TABLE permissions (
    resourceName  VARCHAR(20),
    principalName VARCHAR(20),
    permission    VARCHAR(20)
);

INSERT INTO permissions VALUES ('resource1', 'user1', 'read');
INSERT INTO permissions VALUES ('resource1', 'user1', 'create');
INSERT INTO permissions VALUES ('resource2', 'user1', 'delete');
INSERT INTO permissions VALUES ('resource2', 'user1', 'update');